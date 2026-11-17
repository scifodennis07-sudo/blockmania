package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Disciplina dello staff (profilo staff, pagina 2): warn, depex e sospensioni date con le regole staff
 * (BlockMania.getStaffRules()), piu' il tutor di ogni staffer. Si salva in staff-discipline.yml.
 * Uno staffer sospeso non riceve i permessi di ruolo (StaffRoles.apply controlla isSuspended); un task ogni 30 s
 * chiude le sospensioni scadute e gli rimette i permessi. Da usare dal thread principale.
 */
public final class StaffDiscipline {

    public enum Kind { WARN, DEPEX, SUSPEND }

    /** Voce della disciplina. durationMs > 0 solo per SUSPEND; active/endedAt hanno senso solo per SUSPEND. */
    public record Entry(String id, Kind kind, String ruleCode, String message, UUID by, String byName, long ts, long durationMs,
                        boolean active, long endedAt) {
        /** Fine prevista della sospensione (ts + durata). */
        public long until() {
            return ts + durationMs;
        }
    }

    private static final long CHECK_TICKS = 20L * 30;

    private final BlockMania plugin;
    private final File file;
    private final Map<UUID, List<Entry>> data = new HashMap<>();
    private final Map<UUID, UUID> tutors = new HashMap<>();
    private BukkitTask task;

    public StaffDiscipline(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "staff-discipline.yml");
    }

    // ------------------------------------------------------------------ persistenza

    public synchronized void load() {
        data.clear();
        tutors.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("players");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                UUID id = uuid(key);
                if (id == null) {
                    plugin.getLogger().warning("staff-discipline.yml: uuid non valido " + key);
                    continue;
                }
                List<Entry> list = new ArrayList<>();
                for (Map<?, ?> m : sec.getMapList(key)) {
                    try {
                        Object eid = m.get("id");
                        Object kind = m.get("kind");
                        if (eid == null || kind == null) {
                            continue;
                        }
                        list.add(new Entry(String.valueOf(eid), Kind.valueOf(String.valueOf(kind)), str(m.get("rule")),
                                str(m.get("message")), uuid(str(m.get("by"))), str(m.get("byName")), num(m.get("ts")),
                                num(m.get("durationMs")), Boolean.TRUE.equals(m.get("active")), num(m.get("endedAt"))));
                    } catch (IllegalArgumentException ignored) {
                        // voce non valida: saltata
                    }
                }
                if (!list.isEmpty()) {
                    data.put(id, list);
                }
            }
        }
        ConfigurationSection ts = y.getConfigurationSection("tutors");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                UUID id = uuid(key);
                UUID t = uuid(ts.getString(key));
                if (id != null && t != null) {
                    tutors.put(id, t);
                }
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, List<Entry>> e : data.entrySet()) {
            if (e.getValue().isEmpty()) {
                continue;
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Entry en : e.getValue()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", en.id());
                m.put("kind", en.kind().name());
                m.put("rule", en.ruleCode() == null ? "" : en.ruleCode());
                m.put("message", en.message() == null ? "" : en.message());
                m.put("by", en.by() == null ? "" : en.by().toString());
                m.put("byName", en.byName() == null ? "" : en.byName());
                m.put("ts", en.ts());
                m.put("durationMs", en.durationMs());
                m.put("active", en.active());
                m.put("endedAt", en.endedAt());
                out.add(m);
            }
            y.set("players." + e.getKey(), out);
        }
        for (Map.Entry<UUID, UUID> e : tutors.entrySet()) {
            y.set("tutors." + e.getKey(), e.getValue().toString());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("staff-discipline.yml: " + ex.getMessage());
        }
    }

    /** Avvia il controllo delle sospensioni scadute (ogni 30 s). */
    public void start() {
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                checkExpired();
            }, CHECK_TICKS, CHECK_TICKS);
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        save();
    }

    // ------------------------------------------------------------------ letture

    /** Voci dello staffer, le piu' recenti prima; kindOrNull = solo quel tipo. */
    public synchronized List<Entry> entries(UUID staffer, Kind kindOrNull) {
        List<Entry> out = new ArrayList<>();
        List<Entry> list = staffer == null ? null : data.get(staffer);
        if (list != null) {
            for (Entry e : list) {
                if (kindOrNull == null || e.kind() == kindOrNull) {
                    out.add(e);
                }
            }
        }
        out.sort((a, b) -> Long.compare(b.ts(), a.ts()));
        return out;
    }

    /** Sospensione attiva e non ancora scaduta, o null. */
    private synchronized Entry activeSuspension(UUID id) {
        List<Entry> list = id == null ? null : data.get(id);
        if (list == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        for (Entry e : list) {
            if (e.kind() == Kind.SUSPEND && e.active() && e.until() > now) {
                return e;
            }
        }
        return null;
    }

    public boolean isSuspended(UUID id) {
        return activeSuspension(id) != null;
    }

    /** Fine della sospensione attiva (ms epoch), -1 se non e' sospeso. */
    public long suspendedUntil(UUID id) {
        Entry e = activeSuspension(id);
        return e == null ? -1 : e.until();
    }

    // ------------------------------------------------------------------ azioni

    public Entry warn(Player by, UUID target, Rules.Rule rule, String msg) {
        if (target == null || rule == null) {
            return null;
        }
        Entry e = add(by, target, Kind.WARN, rule, msg, 0, false);
        logHit(rule, target, by, 0);
        movement(target, "Ha ricevuto un warn staff (regola " + rule.code() + ") da " + name(by) + suffix(msg));
        notify(target, "&cHai ricevuto un warn staff per la regola \"" + rule.code() + "\" \"" + rule.text() + "\""
                + staffMsg(msg));
        return e;
    }

    public Entry depex(Player by, UUID target, Rules.Rule rule, String msg) {
        if (target == null || rule == null) {
            return null;
        }
        Entry e = add(by, target, Kind.DEPEX, rule, msg, 0, false);
        if (plugin.getStaffRoles() != null) {
            // toglie solo il grado della scala (la carriera DEPEX la registra StaffRoles); i ruoli speciali restano
            plugin.getStaffRoles().set(target, null, by);
        }
        logHit(rule, target, by, 0);
        movement(target, "E' stato depexato (regola " + rule.code() + ") da " + name(by) + suffix(msg));
        notify(target, "&cSei stato depexato per la regola \"" + rule.code() + "\" \"" + rule.text() + "\"" + staffMsg(msg));
        return e;
    }

    /** Sospende lo staffer per rule.durationMs(); null se la regola non ha una durata valida. */
    public Entry suspend(Player by, UUID target, Rules.Rule rule, String msg) {
        if (target == null || rule == null || rule.durationMs() <= 0) {
            return null;
        }
        long ms = rule.durationMs();
        synchronized (this) {
            // una sola sospensione attiva: quella vecchia si chiude
            closeActive(target, System.currentTimeMillis());
        }
        Entry e = add(by, target, Kind.SUSPEND, rule, msg, ms, true);
        reapply(target);
        logHit(rule, target, by, ms);
        movement(target, "E' stato sospeso dallo staff per " + SanctionManager.fmt(ms) + " (regola " + rule.code() + ") da "
                + name(by) + suffix(msg));
        notify(target, "&cSei stato sospeso dallo staff per la regola \"" + rule.code() + "\" \"" + rule.text() + "\" &7Tempo: &f"
                + SanctionManager.fmt(ms) + staffMsg(msg));
        return e;
    }

    /** Chiude la sospensione attiva e rimette i permessi. false se non era sospeso. */
    public boolean unsuspend(UUID target, CommandSender by) {
        if (target == null) {
            return false;
        }
        boolean closed;
        synchronized (this) {
            closed = closeActive(target, System.currentTimeMillis());
            if (closed) {
                save();
            }
        }
        if (!closed) {
            return false;
        }
        reapply(target);
        movement(target, "Sospensione staff tolta da " + (by == null ? "console" : by.getName()));
        notify(target, "&aLa tua sospensione staff e' stata tolta. &7Hai di nuovo i permessi del tuo ruolo.");
        log("sospensione di " + playerName(target) + " tolta" + (by == null ? "" : " (da " + by.getName() + ")"));
        return true;
    }

    /** Cancella una voce; se era la sospensione attiva la chiude e rimette i permessi. */
    public boolean removeEntry(UUID target, String entryId) {
        if (target == null || entryId == null) {
            return false;
        }
        Entry removed = null;
        synchronized (this) {
            List<Entry> list = data.get(target);
            if (list != null) {
                for (int i = 0; i < list.size(); i++) {
                    if (list.get(i).id().equals(entryId)) {
                        removed = list.remove(i);
                        break;
                    }
                }
                if (list.isEmpty()) {
                    data.remove(target);
                }
            }
            if (removed != null) {
                save();
            }
        }
        if (removed == null) {
            return false;
        }
        if (removed.kind() == Kind.SUSPEND && removed.active()) {
            reapply(target);
            if (removed.until() > System.currentTimeMillis()) {
                notify(target, "&aLa tua sospensione staff e' stata cancellata. &7Hai di nuovo i permessi del tuo ruolo.");
            }
        }
        movement(target, "Voce staff cancellata: " + kindLabel(removed.kind()) + " (regola " + removed.ruleCode() + ")");
        return true;
    }

    /** Cancella tutte le voci (kindOrNull = solo quel tipo); ritorna quante. Se toglie una sospensione attiva rimette i permessi. */
    public int resetAll(UUID target, Kind kindOrNull) {
        if (target == null) {
            return 0;
        }
        int n = 0;
        boolean hadActive = false;
        synchronized (this) {
            List<Entry> list = data.get(target);
            if (list != null) {
                for (java.util.Iterator<Entry> it = list.iterator(); it.hasNext(); ) {
                    Entry e = it.next();
                    if (kindOrNull == null || e.kind() == kindOrNull) {
                        if (e.kind() == Kind.SUSPEND && e.active()) {
                            hadActive = true;
                        }
                        it.remove();
                        n++;
                    }
                }
                if (list.isEmpty()) {
                    data.remove(target);
                }
            }
            if (n > 0) {
                save();
            }
        }
        if (n == 0) {
            return 0;
        }
        if (hadActive) {
            reapply(target);
            notify(target, "&aLa tua sospensione staff e' stata cancellata. &7Hai di nuovo i permessi del tuo ruolo.");
        }
        movement(target, "Reset " + (kindOrNull == null ? "della disciplina staff" : "dei " + kindLabel(kindOrNull) + " staff")
                + " (" + n + " voci)");
        return n;
    }

    // ------------------------------------------------------------------ tutor

    public synchronized UUID tutorOf(UUID id) {
        return id == null ? null : tutors.get(id);
    }

    /** Assegna (o toglie con null) il tutor dello staffer; avvisa i due se online. */
    public void setTutor(UUID target, UUID tutorOrNull, CommandSender by) {
        if (target == null || target.equals(tutorOrNull)) {
            return;
        }
        UUID old;
        synchronized (this) {
            old = tutorOrNull == null ? tutors.remove(target) : tutors.put(target, tutorOrNull);
            save();
        }
        String who = by == null ? "console" : by.getName();
        String tn = playerName(target);
        if (old != null && !old.equals(tutorOrNull)) {
            notify(old, "&7Non sei piu' il tutor di &f" + tn + "&7.");
        }
        if (tutorOrNull == null) {
            if (old != null) {
                notify(target, "&7Non hai piu' un tutor.");
                movement(target, "Tutor tolto da " + who);
            }
        } else if (!tutorOrNull.equals(old)) {
            String nn = playerName(tutorOrNull);
            notify(target, "&aIl tuo tutor ora e' &f" + nn + "&a.");
            notify(tutorOrNull, "&aOra sei il tutor di &f" + tn + "&a.");
            movement(target, "Tutor assegnato: " + nn + " (da " + who + ")");
        }
        log("tutor di " + tn + ": " + (old == null ? "nessuno" : playerName(old)) + " -> "
                + (tutorOrNull == null ? "nessuno" : playerName(tutorOrNull)) + " (da " + who + ")");
    }

    /** Staffer di cui tutor e' il tutor. */
    public synchronized List<UUID> pupilsOf(UUID tutor) {
        List<UUID> out = new ArrayList<>();
        if (tutor == null) {
            return out;
        }
        for (Map.Entry<UUID, UUID> e : tutors.entrySet()) {
            if (tutor.equals(e.getValue())) {
                out.add(e.getKey());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ interni

    /** Chiude le sospensioni scadute, rimette i permessi e avvisa chi e' online. */
    private void checkExpired() {
        long now = System.currentTimeMillis();
        List<UUID> ended = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<UUID, List<Entry>> en : data.entrySet()) {
                List<Entry> list = en.getValue();
                for (int i = 0; i < list.size(); i++) {
                    Entry e = list.get(i);
                    if (e.kind() == Kind.SUSPEND && e.active() && e.until() <= now) {
                        list.set(i, close(e, e.until()));
                        if (!ended.contains(en.getKey())) {
                            ended.add(en.getKey());
                        }
                    }
                }
            }
            if (!ended.isEmpty()) {
                save();
            }
        }
        for (UUID id : ended) {
            reapply(id);
            movement(id, "Sospensione staff finita");
            notify(id, "&aLa tua sospensione staff e' finita. &7Hai di nuovo i permessi del tuo ruolo.");
        }
    }

    /** Chiude la sospensione attiva (anche se gia' scaduta). Chiamare dentro synchronized. Non salva. */
    private boolean closeActive(UUID target, long now) {
        List<Entry> list = data.get(target);
        if (list == null) {
            return false;
        }
        boolean closed = false;
        for (int i = 0; i < list.size(); i++) {
            Entry e = list.get(i);
            if (e.kind() == Kind.SUSPEND && e.active()) {
                list.set(i, close(e, Math.min(now, e.until())));
                closed = true;
            }
        }
        return closed;
    }

    private static Entry close(Entry e, long endedAt) {
        return new Entry(e.id(), e.kind(), e.ruleCode(), e.message(), e.by(), e.byName(), e.ts(), e.durationMs(), false, endedAt);
    }

    private Entry add(Player by, UUID target, Kind kind, Rules.Rule rule, String msg, long durationMs, boolean active) {
        Entry e;
        synchronized (this) {
            e = new Entry(newId(), kind, rule.code(), msg == null ? "" : msg.trim(), by == null ? null : by.getUniqueId(),
                    name(by), System.currentTimeMillis(), durationMs, active, 0L);
            data.computeIfAbsent(target, k -> new ArrayList<>()).add(e);
            save();
        }
        log(kindLabel(kind) + " a " + playerName(target) + " (regola " + rule.code() + ") da " + name(by));
        return e;
    }

    /** Id corto univoco (8 caratteri esadecimali di un UUID casuale). Chiamare dentro synchronized. */
    private String newId() {
        while (true) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            boolean used = false;
            for (List<Entry> list : data.values()) {
                for (Entry e : list) {
                    if (e.id().equals(id)) {
                        used = true;
                        break;
                    }
                }
                if (used) {
                    break;
                }
            }
            if (!used) {
                return id;
            }
        }
    }

    private void logHit(Rules.Rule rule, UUID target, Player by, long durationMs) {
        Rules r = plugin.getStaffRules();
        if (r == null) {
            return;
        }
        r.logHit(rule.code(), new Rules.Hit(System.currentTimeMillis(), target, playerName(target),
                by == null ? null : by.getUniqueId(), name(by), rule.type(), durationMs));
    }

    private void reapply(UUID target) {
        Player p = Bukkit.getPlayer(target);
        if (p != null && plugin.getStaffRoles() != null) {
            plugin.getStaffRoles().apply(p);
        }
    }

    private void notify(UUID target, String msg) {
        Player p = target == null ? null : Bukkit.getPlayer(target);
        if (p != null) {
            GuiMsg.send(plugin, p, msg);
        }
    }

    private void movement(UUID target, String text) {
        if (plugin.getMovements() != null) {
            plugin.getMovements().record(target, MovementLog.Type.STAFF, text);
        }
    }

    private void log(String text) {
        plugin.getLogger().info("Disciplina staff: " + text);
    }

    private static String kindLabel(Kind k) {
        return switch (k) {
            case WARN -> "warn";
            case DEPEX -> "depex";
            case SUSPEND -> "sospensione";
        };
    }

    private static String name(CommandSender by) {
        return by == null ? "console" : by.getName();
    }

    private static String playerName(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    private static String suffix(String msg) {
        return msg == null || msg.isBlank() ? "" : ": " + msg.trim();
    }

    private static String staffMsg(String msg) {
        return msg == null || msg.isBlank() ? "" : " &7Messaggio: &f\"" + msg.trim() + "\"";
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        try {
            return o == null ? 0L : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return 0L;
        }
    }

    private static UUID uuid(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
