package it.blockmania;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Sanzioni (mute, warn, ban): logica e persistenza in sanctions.yml, nessuna GUI.
 * Il ban e' INTERNO (controllato al login), non usa la BanList di Paper.
 */
public final class SanctionManager implements Listener {

    public enum Kind { MUTE, WARN, BAN }

    public record Entry(long id, UUID target, Kind kind, String action, UUID by, String byName, long time,
                        long durationMs, String reason, String note) {}

    public record Status(boolean active, long startMs, long endMs, long totalMs) {}

    private static final long DEFAULT_WARN_BAN_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_WARNS = 4;
    private static final Set<String> MUTED_COMMANDS = Set.of(
            "msg", "tell", "w", "m", "t", "whisper", "r", "reply", "me", "say", "pm", "dm");

    /** Stato di un giocatore. Accesso solo sotto lock su this. */
    private static final class State {
        long muteStart;
        long muteEnd;
        long banStart;
        long banEnd;
        boolean banFromWarns;
        int warns;
        int noteType; // 0 = niente, 1 = bannato per i warn, 2 = era gia' bannato (esteso)
        long noteMs;
    }

    private final BlockMania plugin;
    private final File file;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    private final TreeMap<Long, Entry> entries = new TreeMap<>();
    private final List<Consumer<UUID>> listeners = new CopyOnWriteArrayList<>();
    private long nextId = 1;
    private BukkitTask task;

    public SanctionManager(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "sanctions.yml");
    }

    // ------------------------------------------------------------------ ciclo di vita

    public void start() {
        synchronized (this) {
            load();
        }
        if (task != null) {
            task.cancel();
        }
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(), 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        synchronized (this) {
            save();
        }
    }

    public void onChange(Consumer<UUID> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    // ------------------------------------------------------------------ lettura

    public synchronized Status mute(UUID p) {
        State s = states.get(p);
        if (s == null) {
            return new Status(false, 0, 0, 0);
        }
        return status(s.muteStart, s.muteEnd);
    }

    public synchronized Status ban(UUID p) {
        State s = states.get(p);
        if (s == null) {
            return new Status(false, 0, 0, 0);
        }
        return status(s.banStart, s.banEnd);
    }

    public synchronized int warns(UUID p) {
        State s = states.get(p);
        return s == null ? 0 : s.warns;
    }

    public synchronized boolean banFromWarns(UUID p) {
        State s = states.get(p);
        return s != null && s.banFromWarns && s.banEnd > System.currentTimeMillis();
    }

    public synchronized String warnNote(UUID p) {
        State s = states.get(p);
        if (s == null || s.warns < MAX_WARNS || s.noteType == 0) {
            return "";
        }
        if (s.noteType == 2) {
            return "era gia' bannato, aggiunti " + fmt(s.noteMs) + " per aver raggiunto " + MAX_WARNS + " warn";
        }
        return "bannato per " + fmt(s.noteMs) + " perche' ha raggiunto " + MAX_WARNS + " warn";
    }

    private static Status status(long start, long end) {
        boolean active = end > System.currentTimeMillis();
        if (!active) {
            return new Status(false, 0, 0, 0);
        }
        return new Status(true, start, end, Math.max(1, end - start));
    }

    public synchronized List<Entry> history(UUID p, Kind kind) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries.descendingMap().values()) {
            if (e.target().equals(p) && e.kind() == kind) {
                out.add(e);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ mute

    public void addMute(UUID p, long ms, UUID by, String reason) {
        if (ms <= 0) {
            return;
        }
        synchronized (this) {
            State s = state(p);
            long now = System.currentTimeMillis();
            if (s.muteEnd > now) {
                s.muteEnd = plus(s.muteEnd, ms);
            } else {
                s.muteStart = now;
                s.muteEnd = plus(now, ms);
            }
            record(p, Kind.MUTE, "ADD", by, ms, reason, "");
            save();
        }
        notifyMute(p);
        testNotice(by, p, "MUTE a %t per " + fmt(ms));
        fire(p);
    }

    public void reduceMute(UUID p, long ms, UUID by) {
        if (ms <= 0) {
            return;
        }
        synchronized (this) {
            State s = state(p);
            long now = System.currentTimeMillis();
            if (s.muteEnd <= now) {
                return;
            }
            s.muteEnd -= ms;
            if (s.muteEnd <= now) {
                clearMute(s);
            }
            record(p, Kind.MUTE, "REDUCE", by, ms, "", "");
            save();
        }
        fire(p);
    }

    public void removeMute(UUID p, UUID by) {
        endMute(p, by, "REMOVE");
    }

    public void resetMute(UUID p, UUID by) {
        endMute(p, by, "RESET");
    }

    private void endMute(UUID p, UUID by, String action) {
        synchronized (this) {
            State s = states.get(p);
            if (s == null || s.muteEnd <= System.currentTimeMillis()) {
                return;
            }
            clearMute(s);
            record(p, Kind.MUTE, action, by, 0, "", "");
            save();
        }
        fire(p);
    }

    private static void clearMute(State s) {
        s.muteStart = 0;
        s.muteEnd = 0;
    }

    // ------------------------------------------------------------------ ban

    public void addBan(UUID p, long ms, UUID by, String reason) {
        if (ms <= 0) {
            return;
        }
        synchronized (this) {
            State s = state(p);
            long now = System.currentTimeMillis();
            if (s.banEnd > now) {
                s.banEnd = plus(s.banEnd, ms);
            } else {
                s.banStart = now;
                s.banEnd = plus(now, ms);
                s.banFromWarns = false;
            }
            record(p, Kind.BAN, "ADD", by, ms, reason, "");
            save();
        }
        kickIfBanned(p);
        testNotice(by, p, "BAN a %t per " + fmt(ms));
        fire(p);
    }

    public void reduceBan(UUID p, long ms, UUID by) {
        if (ms <= 0) {
            return;
        }
        synchronized (this) {
            State s = state(p);
            long now = System.currentTimeMillis();
            if (s.banEnd <= now) {
                return;
            }
            s.banEnd -= ms;
            boolean ended = s.banEnd <= now;
            record(p, Kind.BAN, "REDUCE", by, ms, "", ended ? "il ban e' terminato" : "");
            if (ended) {
                endBanState(s);
            }
            save();
        }
        fire(p);
    }

    /** Sban. */
    public void removeBan(UUID p, UUID by) {
        endBan(p, by, "UNBAN");
    }

    public void resetBan(UUID p, UUID by) {
        endBan(p, by, "RESET");
    }

    private void endBan(UUID p, UUID by, String action) {
        synchronized (this) {
            State s = states.get(p);
            if (s == null || s.banEnd <= System.currentTimeMillis()) {
                return;
            }
            boolean warnsReset = s.banFromWarns;
            endBanState(s);
            record(p, Kind.BAN, action, by, 0, "", warnsReset ? "warn azzerati" : "");
            save();
        }
        fire(p);
    }

    /** Chiude il ban; se era dovuto ai warn, i warn si azzerano. */
    private static void endBanState(State s) {
        if (s.banFromWarns) {
            s.warns = 0;
            s.noteType = 0;
            s.noteMs = 0;
        }
        s.banStart = 0;
        s.banEnd = 0;
        s.banFromWarns = false;
    }

    // ------------------------------------------------------------------ warn

    public void addWarn(UUID p, UUID by, String reason) {
        boolean banned = false;
        boolean extended = false;
        synchronized (this) {
            State s = state(p);
            if (s.warns >= MAX_WARNS) {
                return;
            }
            s.warns++;
            record(p, Kind.WARN, "ADD", by, 0, reason, "");
            if (s.warns >= MAX_WARNS) {
                long ms = plugin.getConfig().getLong("sanctions.warn-ban-ms", DEFAULT_WARN_BAN_MS);
                if (ms <= 0) {
                    ms = DEFAULT_WARN_BAN_MS;
                }
                long now = System.currentTimeMillis();
                if (s.banEnd > now) {
                    s.banEnd = plus(s.banEnd, ms);
                    s.noteType = 2;
                    extended = true;
                    record(p, Kind.BAN, "EXTEND", by, ms, "", "era gia' bannato, aggiunti " + fmt(ms)
                            + " per aver raggiunto " + MAX_WARNS + " warn");
                } else {
                    s.banStart = now;
                    s.banEnd = plus(now, ms);
                    s.noteType = 1;
                    record(p, Kind.BAN, "AUTO_BAN", by, ms, "", "bannato per " + fmt(ms)
                            + " perche' ha raggiunto " + MAX_WARNS + " warn");
                    banned = true;
                }
                s.noteMs = ms;
                s.banFromWarns = true;
            }
            save();
        }
        if (banned) {
            kickIfBanned(p);
        }
        testNotice(by, p, "WARN a %t" + (extended ? " (4 warn: ban allungato)" : banned ? " (4 warn: ban automatico)" : ""));
        fire(p);
    }

    public void removeWarn(UUID p, UUID by) {
        synchronized (this) {
            State s = states.get(p);
            if (s == null || s.warns <= 0) {
                return;
            }
            s.warns--;
            if (s.warns < MAX_WARNS) {
                s.noteType = 0;
                s.noteMs = 0;
            }
            record(p, Kind.WARN, "REMOVE", by, 0, "", "");
            save();
        }
        fire(p);
    }

    public void resetWarns(UUID p, UUID by) {
        synchronized (this) {
            State s = states.get(p);
            if (s == null || s.warns <= 0) {
                return;
            }
            s.warns = 0;
            s.noteType = 0;
            s.noteMs = 0;
            s.banFromWarns = false; // l'eventuale ban resta, ma non e' piu' "per i warn"
            record(p, Kind.WARN, "RESET", by, 0, "", "");
            save();
        }
        fire(p);
    }

    // ------------------------------------------------------------------ sanzione da regola

    /** Durata "infinita" per permanente / blacklist (in millisecondi). */
    private static final long FOREVER_MS = Long.MAX_VALUE / 2;

    /**
     * Applica una regola (ban / mute / warn) a un giocatore. staffMsg null, vuoto o "null" = nessun messaggio staff.
     * La cronologia riceve "[codice] testo" (+ " | staff: msg"); il player riceve il messaggio da config sanctions.msg.*.
     * Ritorna false se la regola non sanziona (REGOLAMENTO) o non e' applicabile.
     */
    public boolean applyRule(CommandSender staff, UUID target, Rules.Rule rule, String staffMsg) {
        if (rule == null || target == null || rule.type() == Rules.Type.REGOLAMENTO) {
            return false;
        }
        String msg = staffMsg == null ? "" : staffMsg.trim();
        if (msg.equalsIgnoreCase("null")) {
            msg = "";
        }
        UUID by = staff instanceof Player sp ? sp.getUniqueId() : null;
        boolean blacklist = rule.type() == Rules.Type.BAN && rule.minutes() == Rules.BLACKLIST;
        boolean forever = rule.minutes() == Rules.PERMANENT || rule.minutes() == Rules.BLACKLIST;
        long ms = forever ? FOREVER_MS : rule.minutes() * 60_000L;
        String reason = "[" + rule.code() + "] " + rule.text() + (msg.isEmpty() ? "" : " | staff: " + msg);
        String note = "msg:" + playerMessage(rule, msg, blacklist);
        String label = forever ? (blacklist ? "blacklist" : "permanente") : fmt(ms);
        switch (rule.type()) {
            case BAN -> {
                if (ms <= 0) {
                    return false;
                }
                synchronized (this) {
                    State s = state(target);
                    long now = System.currentTimeMillis();
                    if (s.banEnd > now) {
                        s.banEnd = plus(s.banEnd, ms);
                    } else {
                        s.banStart = now;
                        s.banEnd = plus(now, ms);
                        s.banFromWarns = false;
                    }
                    record(target, Kind.BAN, "ADD", by, ms, reason, note);
                    save();
                }
                kickIfBanned(target);
                testNotice(by, target, "BAN a %t, regola " + rule.code() + " (" + label + ")");
            }
            case MUTE -> {
                if (ms <= 0) {
                    return false;
                }
                synchronized (this) {
                    State s = state(target);
                    long now = System.currentTimeMillis();
                    if (s.muteEnd > now) {
                        s.muteEnd = plus(s.muteEnd, ms);
                    } else {
                        s.muteStart = now;
                        s.muteEnd = plus(now, ms);
                    }
                    record(target, Kind.MUTE, "ADD", by, ms, reason, note);
                    save();
                }
                notifyRule(target, playerMessage(rule, msg, false));
                testNotice(by, target, "MUTE a %t, regola " + rule.code() + " (" + label + ")");
            }
            default -> {
                int before = warns(target);
                addWarn(target, by, reason);
                if (warns(target) <= before) {
                    return false; // gia' al massimo di warn
                }
                notifyRule(target, playerMessage(rule, msg, false));
                fire(target);
                return true;
            }
        }
        fire(target);
        return true;
    }

    /** Testo per il player: "Sei stato bannato per la regola ..." da config sanctions.msg.* (placeholder %code% %text% %staff% %type%). */
    private String playerMessage(Rules.Rule rule, String staffMsg, boolean blacklist) {
        String type = switch (rule.type()) {
            case BAN -> blacklist ? "messo in blacklist" : "bannato";
            case MUTE -> "mutato";
            default -> "warnato";
        };
        String key = switch (rule.type()) {
            case BAN -> blacklist ? "blacklist" : "ban";
            case MUTE -> "mute";
            default -> "warn";
        };
        String def = blacklist
                ? "&cSei stato messo in blacklist per la regola \"%code%\" \"%text%\""
                : "&cSei stato %type% per la regola \"%code%\" \"%text%\"";
        String t = plugin.getConfig().getString("sanctions.msg." + key, def);
        if (!staffMsg.isEmpty()) {
            t += plugin.getConfig().getString("sanctions.msg.staff", " &7Messaggio staff: &f\"%staff%\"");
        }
        return t.replace("%type%", type).replace("%code%", rule.code()).replace("%text%", rule.text())
                .replace("%staff%", staffMsg);
    }

    /** Messaggio al player in chat + bossbar (mute / warn). Solo con sanctions.enforce: true. */
    private void notifyRule(UUID p, String text) {
        if (!enforce()) {
            return;
        }
        runMain(() -> {
            Player pl = Bukkit.getPlayer(p);
            if (pl == null) {
                return;
            }
            GuiMsg.send(plugin, pl, text);
            BossBar bar = BossBar.bossBar(plugin.color(text), 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
            pl.showBossBar(bar);
            Bukkit.getScheduler().runTaskLater(plugin, () -> pl.hideBossBar(bar), 200L);
        });
    }

    /** Messaggio salvato dell'ultimo ban da regola ancora in corso, "" se non c'e'. */
    private synchronized String ruleBanMessage(UUID p) {
        State s = states.get(p);
        long start = s == null ? 0 : s.banStart;
        for (Entry e : entries.descendingMap().values()) {
            if (e.target().equals(p) && e.kind() == Kind.BAN && "ADD".equals(e.action())) {
                if (e.time() >= start && e.note() != null && e.note().startsWith("msg:")) {
                    return e.note().substring(4);
                }
                return "";
            }
        }
        return "";
    }

    // ------------------------------------------------------------------ cronologia

    public boolean deleteEntry(long id, UUID by) {
        UUID target;
        synchronized (this) {
            Entry e = entries.remove(id);
            if (e == null) {
                return false;
            }
            target = e.target();
            save();
        }
        fire(target);
        return true;
    }

    // ------------------------------------------------------------------ listener

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        if (!enforce()) {
            return;
        }
        Player pl = event.getPlayer();
        Status st = mute(pl.getUniqueId());
        if (!st.active()) {
            return;
        }
        event.setCancelled(true);
        GuiMsg.send(plugin, pl, "&cSei mutato. &7Tempo rimasto: &f" + fmt(st.endMs() - System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!enforce()) {
            return;
        }
        String msg = event.getMessage();
        if (msg.length() < 2) {
            return;
        }
        String label = msg.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (!MUTED_COMMANDS.contains(label)) {
            return;
        }
        Player pl = event.getPlayer();
        Status st = mute(pl.getUniqueId());
        if (!st.active()) {
            return;
        }
        event.setCancelled(true);
        GuiMsg.send(plugin, pl, "&cSei mutato. &7Tempo rimasto: &f" + fmt(st.endMs() - System.currentTimeMillis()));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!enforce()) {
            return;
        }
        Status st = ban(event.getUniqueId());
        if (!st.active()) {
            return;
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                banMessage(event.getUniqueId(), st.endMs() - System.currentTimeMillis()));
    }

    // ------------------------------------------------------------------ interni

    private void tick() {
        List<UUID> changed = new ArrayList<>();
        synchronized (this) {
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, State> e : states.entrySet()) {
                State s = e.getValue();
                boolean ch = false;
                if (s.muteEnd > 0 && s.muteEnd <= now) {
                    clearMute(s);
                    ch = true;
                }
                if (s.banEnd > 0 && s.banEnd <= now) {
                    endBanState(s);
                    ch = true;
                }
                if (ch) {
                    changed.add(e.getKey());
                }
            }
            if (!changed.isEmpty()) {
                save();
            }
        }
        for (UUID u : changed) {
            fire(u);
        }
    }

    private Component banMessage(UUID p, long remaining) {
        String ruleMsg = ruleBanMessage(p);
        if (!ruleMsg.isEmpty()) {
            return plugin.color(ruleMsg);
        }
        Component c = Component.empty()
                .append(plugin.color("&cSei stato bannato."))
                .append(Component.newline())
                .append(plugin.color("&7Tempo rimasto: &f" + fmt(remaining)));
        String reason = lastReason(p, Kind.BAN);
        if (!reason.isEmpty()) {
            c = c.append(Component.newline()).append(plugin.color("&7Motivo: &f" + reason));
        } else if (banFromWarns(p)) {
            c = c.append(Component.newline()).append(plugin.color("&7Motivo: &f" + MAX_WARNS + " warn"));
        }
        return c;
    }

    private synchronized String lastReason(UUID p, Kind kind) {
        for (Entry e : entries.descendingMap().values()) {
            if (e.target().equals(p) && e.kind() == kind && "ADD".equals(e.action())
                    && e.reason() != null && !e.reason().isEmpty()) {
                return e.reason();
            }
        }
        return "";
    }

    private void kickIfBanned(UUID p) {
        if (!enforce()) {
            return;
        }
        runMain(() -> {
            Player pl = Bukkit.getPlayer(p);
            Status st = ban(p);
            if (pl != null && st.active()) {
                pl.kick(banMessage(p, st.endMs() - System.currentTimeMillis()));
            }
        });
    }

    private void notifyMute(UUID p) {
        if (!enforce()) {
            return;
        }
        runMain(() -> {
            Player pl = Bukkit.getPlayer(p);
            Status st = mute(p);
            if (pl != null && st.active()) {
                GuiMsg.send(plugin, pl, "&cSei stato mutato. &7Tempo: &f"
                        + fmt(st.endMs() - System.currentTimeMillis()));
            }
        });
    }

    /** sanctions.enforce: false (default) = modalita' TEST, nessun blocco reale (login, chat, kick), solo messaggi in chat. */
    private boolean enforce() {
        return plugin.getConfig().getBoolean("sanctions.enforce", false);
    }

    /** In modalita' test avvisa in chat chi ha fatto l'azione (e il target se online) che non succede nulla di reale. */
    private void testNotice(UUID by, UUID target, String text) {
        if (enforce()) {
            return;
        }
        runMain(() -> {
            String name = Bukkit.getOfflinePlayer(target).getName();
            String msg = "&e[TEST] &f" + text.replace("%t", name == null ? target.toString() : name)
                    + " &7(nessuna sanzione reale applicata)";
            Player a = by == null ? null : Bukkit.getPlayer(by);
            if (a != null) {
                GuiMsg.send(plugin, a, msg);
            }
            Player t = Bukkit.getPlayer(target);
            if (t != null && t != a) {
                GuiMsg.send(plugin, t, msg);
            }
        });
    }

    private void runMain(Runnable r) {
        if (Bukkit.isPrimaryThread()) {
            r.run();
        } else if (plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> r.run());
        }
    }

    private void fire(UUID p) {
        runMain(() -> {
            for (Consumer<UUID> l : listeners) {
                try {
                    l.accept(p);
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("sanctions: listener: " + ex.getMessage());
                }
            }
        });
    }

    private State state(UUID p) {
        return states.computeIfAbsent(p, k -> new State());
    }

    /** Da chiamare sotto lock. */
    private void record(UUID target, Kind kind, String action, UUID by, long ms, String reason, String note) {
        String byName = "Sistema";
        if (by != null) {
            String n = Bukkit.getOfflinePlayer(by).getName();
            byName = n == null ? by.toString().substring(0, 8) : n;
        }
        long id = nextId++;
        entries.put(id, new Entry(id, target, kind, action, by, byName, System.currentTimeMillis(), ms,
                reason == null ? "" : reason, note == null ? "" : note));
    }

    private static long plus(long a, long b) {
        long r = a + b;
        return r < a ? Long.MAX_VALUE : r;
    }

    /** "2g 3h 4m 5s", senza le parti a zero. */
    public static String fmt(long ms) {
        long s = Math.max(0, ms) / 1000;
        if (s == 0) {
            return "0s";
        }
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("g ");
        }
        if (h > 0) {
            sb.append(h).append("h ");
        }
        if (m > 0) {
            sb.append(m).append("m ");
        }
        if (sec > 0 && d == 0) {
            sb.append(sec).append("s ");
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ persistenza

    private void load() {
        states.clear();
        entries.clear();
        nextId = 1;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, y.getLong("next-id", 1));
        ConfigurationSection ps = y.getConfigurationSection("players");
        if (ps != null) {
            for (String key : ps.getKeys(false)) {
                try {
                    UUID u = UUID.fromString(key);
                    ConfigurationSection c = ps.getConfigurationSection(key);
                    if (c == null) {
                        continue;
                    }
                    State s = new State();
                    s.muteStart = c.getLong("mute-start");
                    s.muteEnd = c.getLong("mute-end");
                    s.banStart = c.getLong("ban-start");
                    s.banEnd = c.getLong("ban-end");
                    s.banFromWarns = c.getBoolean("ban-from-warns");
                    s.warns = Math.max(0, Math.min(MAX_WARNS, c.getInt("warns")));
                    s.noteType = c.getInt("note-type");
                    s.noteMs = c.getLong("note-ms");
                    states.put(u, s);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("sanctions.yml: giocatore " + key + " non letto (" + e.getMessage() + ")");
                }
            }
        }
        ConfigurationSection es = y.getConfigurationSection("entries");
        if (es != null) {
            for (String key : es.getKeys(false)) {
                try {
                    ConfigurationSection c = es.getConfigurationSection(key);
                    if (c == null) {
                        continue;
                    }
                    long id = Long.parseLong(key);
                    String byRaw = c.getString("by", "");
                    UUID by = byRaw == null || byRaw.isEmpty() ? null : UUID.fromString(byRaw);
                    entries.put(id, new Entry(id, UUID.fromString(c.getString("target", "")),
                            Kind.valueOf(c.getString("kind", "WARN")), c.getString("action", ""), by,
                            c.getString("by-name", "Sistema"), c.getLong("time"), c.getLong("duration"),
                            c.getString("reason", ""), c.getString("note", "")));
                    nextId = Math.max(nextId, id + 1);
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("sanctions.yml: voce " + key + " non letta (" + e.getMessage() + ")");
                }
            }
        }
    }

    /** Da chiamare sotto lock. */
    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Map.Entry<UUID, State> e : states.entrySet()) {
            State s = e.getValue();
            if (s.muteEnd == 0 && s.banEnd == 0 && s.warns == 0) {
                continue;
            }
            String p = "players." + e.getKey() + ".";
            y.set(p + "mute-start", s.muteStart);
            y.set(p + "mute-end", s.muteEnd);
            y.set(p + "ban-start", s.banStart);
            y.set(p + "ban-end", s.banEnd);
            y.set(p + "ban-from-warns", s.banFromWarns);
            y.set(p + "warns", s.warns);
            y.set(p + "note-type", s.noteType);
            y.set(p + "note-ms", s.noteMs);
        }
        for (Entry e : entries.values()) {
            String p = "entries." + e.id() + ".";
            y.set(p + "target", e.target().toString());
            y.set(p + "kind", e.kind().name());
            y.set(p + "action", e.action());
            y.set(p + "by", e.by() == null ? "" : e.by().toString());
            y.set(p + "by-name", e.byName());
            y.set(p + "time", e.time());
            y.set(p + "duration", e.durationMs());
            y.set(p + "reason", e.reason());
            y.set(p + "note", e.note());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("sanctions.yml: " + e.getMessage());
        }
    }
}
