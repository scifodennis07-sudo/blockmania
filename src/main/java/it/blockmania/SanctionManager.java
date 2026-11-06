package it.blockmania;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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
        Player pl = event.getPlayer();
        Status st = mute(pl.getUniqueId());
        if (!st.active()) {
            return;
        }
        event.setCancelled(true);
        pl.sendMessage(plugin.color("&cSei mutato. &7Tempo rimasto: &f" + fmt(st.endMs() - System.currentTimeMillis())));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
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
        pl.sendMessage(plugin.color("&cSei mutato. &7Tempo rimasto: &f" + fmt(st.endMs() - System.currentTimeMillis())));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
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
        runMain(() -> {
            Player pl = Bukkit.getPlayer(p);
            Status st = ban(p);
            if (pl != null && st.active()) {
                pl.kick(banMessage(p, st.endMs() - System.currentTimeMillis()));
            }
        });
    }

    private void notifyMute(UUID p) {
        runMain(() -> {
            Player pl = Bukkit.getPlayer(p);
            Status st = mute(p);
            if (pl != null && st.active()) {
                pl.sendMessage(plugin.color("&cSei stato mutato. &7Tempo: &f"
                        + fmt(st.endMs() - System.currentTimeMillis())));
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
