package it.blockmania;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Regole del server (codice, testo, tipo di sanzione, durata), salvate in rules.yml.
 */
public final class Rules {

    public enum Type { BAN, WARN, MUTE, REGOLAMENTO }

    /** Valori speciali di Rule.durationMs (la durata e' in millisecondi). */
    public static final long PERMANENT = -1, BLACKLIST = -2;

    public record Rule(String code, String title, String text, Type type, long durationMs,
                       UUID createdBy, String createdByName, long createdAt,
                       UUID editedBy, String editedByName, long editedAt) {
        /** Nuova regola: creata adesso da "by"; mai modificata (editedBy null, editedAt 0). */
        public static Rule created(String code, String title, String text, Type type, long durationMs, Player by) {
            return new Rule(code, title, text, type, durationMs,
                    by == null ? null : by.getUniqueId(), by == null ? "sconosciuto" : by.getName(),
                    System.currentTimeMillis(), null, null, 0L);
        }
    }

    /** Voce di cronologia. field: "creata","codice","titolo","descrizione","sanzione","tempo". */
    public record Change(long time, UUID by, String byName, String field, String oldValue, String newValue) { }

    /** Sanzione applicata con una regola. durationMs: durata in ms (PERMANENT/BLACKLIST/0 per warn). */
    public record Hit(long time, UUID target, String targetName, UUID by, String byName, Type kind, long durationMs) { }

    private final BlockMania plugin;
    private final File file;
    private final File logFile;
    // codice -> cronologia / sanzioni (in ordine cronologico, dal piu' vecchio)
    private final Map<String, List<Change>> histories = new HashMap<>();
    private final Map<String, List<Hit>> hitLog = new HashMap<>();
    // codice -> regola
    private final TreeMap<String, Rule> rules = new TreeMap<>(Rules::compareCodes);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public Rules(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rules.yml");
        this.logFile = new File(plugin.getDataFolder(), "rules-log.yml");
    }

    // ------------------------------------------------------------------ query

    /** Ordina i codici per numero: 1.2 < 1.10 < 2.1. */
    private static int compareCodes(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            long x = i < pa.length ? num(pa[i]) : -1;
            long y = i < pb.length ? num(pb[i]) : -1;
            if (x != y) {
                return Long.compare(x, y);
            }
        }
        return a.compareTo(b);
    }

    private static long num(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return Long.MAX_VALUE;
        }
    }

    public synchronized List<Rule> all() {
        return new ArrayList<>(rules.values());
    }

    public synchronized List<Rule> byType(Type t) {
        if (t == null) {
            return all();
        }
        List<Rule> out = new ArrayList<>();
        for (Rule r : rules.values()) {
            if (r.type() == t) {
                out.add(r);
            }
        }
        return out;
    }

    public synchronized Rule get(String code) {
        return code == null ? null : rules.get(code.trim());
    }

    public static boolean validCode(String s) {
        return s != null && s.matches("^\\d+\\.\\d+$");
    }

    // ------------------------------------------------------------------ modifica

    public boolean add(Rule r) {
        synchronized (this) {
            if (r == null || rules.containsKey(r.code())) {
                return false;
            }
            rules.put(r.code(), r);
            histories.computeIfAbsent(r.code(), k -> new ArrayList<>())
                    .add(new Change(r.createdAt() > 0 ? r.createdAt() : System.currentTimeMillis(), r.createdBy(),
                            r.createdByName(), "creata", "", r.title()));
            save();
        }
        fire();
        return true;
    }

    // ------------------------------------------------------------------ modifica campi

    private static final String NOT_FOUND = "La regola non esiste piu'.";

    private static Rule withEdit(Rule r, String code, String title, String text, Type type, long durationMs, Player by) {
        return new Rule(code, title, text, type, durationMs, r.createdBy(), r.createdByName(), r.createdAt(),
                by == null ? null : by.getUniqueId(), by == null ? "sconosciuto" : by.getName(),
                System.currentTimeMillis());
    }

    private void addChange(String code, Player by, String field, String oldV, String newV) {
        histories.computeIfAbsent(code, k -> new ArrayList<>()).add(new Change(System.currentTimeMillis(),
                by == null ? null : by.getUniqueId(), by == null ? "sconosciuto" : by.getName(),
                field, oldV == null ? "" : oldV, newV == null ? "" : newV));
    }

    /** Cambia il codice (sposta anche cronologia e sanzioni). null se ok, altrimenti errore. */
    public String editCode(String code, String newCode, Player by) {
        synchronized (this) {
            Rule r = code == null ? null : rules.get(code.trim());
            if (r == null) {
                return NOT_FOUND;
            }
            String nc = newCode == null ? "" : newCode.trim();
            if (!validCode(nc)) {
                return "Codice non valido: usa numero.numero, per esempio 1.2";
            }
            if (nc.equals(r.code())) {
                return "Il codice e' gia' " + nc + ".";
            }
            if (rules.containsKey(nc)) {
                return "Esiste gia' una regola con il codice " + nc + ".";
            }
            String old = r.code();
            rules.remove(old);
            rules.put(nc, withEdit(r, nc, r.title(), r.text(), r.type(), r.durationMs(), by));
            List<Change> h = histories.remove(old);
            if (h != null) {
                histories.put(nc, h);
            }
            List<Hit> hl = hitLog.remove(old);
            if (hl != null) {
                hitLog.put(nc, hl);
            }
            addChange(nc, by, "codice", old, nc);
            save();
        }
        fire();
        return null;
    }

    public String editTitle(String code, String value, Player by) {
        synchronized (this) {
            Rule r = code == null ? null : rules.get(code.trim());
            if (r == null) {
                return NOT_FOUND;
            }
            String v = value == null ? "" : value.trim();
            if (v.isEmpty()) {
                return "Il titolo non puo' essere vuoto.";
            }
            if (v.equals(r.title())) {
                return "Il titolo e' gia' questo.";
            }
            rules.put(r.code(), withEdit(r, r.code(), v, r.text(), r.type(), r.durationMs(), by));
            addChange(r.code(), by, "titolo", r.title(), v);
            save();
        }
        fire();
        return null;
    }

    public String editText(String code, String value, Player by) {
        synchronized (this) {
            Rule r = code == null ? null : rules.get(code.trim());
            if (r == null) {
                return NOT_FOUND;
            }
            String v = value == null ? "" : value.trim();
            if (v.isEmpty()) {
                return "La descrizione non puo' essere vuota.";
            }
            if (v.equals(r.text())) {
                return "La descrizione e' gia' questa.";
            }
            rules.put(r.code(), withEdit(r, r.code(), r.title(), v, r.type(), r.durationMs(), by));
            addChange(r.code(), by, "descrizione", r.text(), v);
            save();
        }
        fire();
        return null;
    }

    /** Cambia sanzione e/o durata (durationMs gia' parsato con parseTime, in ms; 0 per WARN/REGOLAMENTO). */
    public String editType(String code, Type t, long durationMs, Player by) {
        synchronized (this) {
            Rule r = code == null ? null : rules.get(code.trim());
            if (r == null) {
                return NOT_FOUND;
            }
            if (t == null) {
                return "Sanzione non valida.";
            }
            long m = (t == Type.WARN || t == Type.REGOLAMENTO) ? 0 : durationMs;
            if ((t == Type.BAN || t == Type.MUTE) && m == 0) {
                return "Serve una durata valida (es: 10h+23m+16s, o permanente).";
            }
            if (t != Type.BAN && m == BLACKLIST) {
                return "\"blacklist\" vale solo per i ban.";
            }
            if (t == r.type() && m == r.durationMs()) {
                return "La sanzione e' gia' questa.";
            }
            Rule n = withEdit(r, r.code(), r.title(), r.text(), t, m, by);
            rules.put(r.code(), n);
            if (t != r.type()) {
                addChange(r.code(), by, "sanzione", typeLabel(r.type()), typeLabel(t));
            }
            if (m != r.durationMs()) {
                addChange(r.code(), by, "tempo", timeLabel(r), timeLabel(n));
            }
            save();
        }
        fire();
        return null;
    }

    // ------------------------------------------------------------------ cronologia e sanzioni

    /** Cronologia modifiche, dalla piu' recente. */
    public synchronized List<Change> history(String code) {
        List<Change> l = code == null ? null : histories.get(code.trim());
        List<Change> out = l == null ? new ArrayList<>() : new ArrayList<>(l);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Sanzioni applicate con la regola, dalla piu' recente. */
    public synchronized List<Hit> hits(String code) {
        List<Hit> l = code == null ? null : hitLog.get(code.trim());
        List<Hit> out = l == null ? new ArrayList<>() : new ArrayList<>(l);
        java.util.Collections.reverse(out);
        return out;
    }

    public synchronized int hitCount(String code) {
        List<Hit> l = code == null ? null : hitLog.get(code.trim());
        return l == null ? 0 : l.size();
    }

    public void logHit(String code, Hit h) {
        if (code == null || h == null) {
            return;
        }
        synchronized (this) {
            if (!rules.containsKey(code.trim())) {
                return;
            }
            hitLog.computeIfAbsent(code.trim(), k -> new ArrayList<>()).add(h);
            save();
        }
        fire();
    }

    public boolean remove(String code) {
        synchronized (this) {
            if (code == null || rules.remove(code.trim()) == null) {
                return false;
            }
            histories.remove(code.trim());
            hitLog.remove(code.trim());
            save();
        }
        fire();
        return true;
    }

    public void onChange(Runnable l) {
        if (l != null) {
            listeners.add(l);
        }
    }

    private void fire() {
        for (Runnable l : listeners) {
            try {
                l.run();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("rules: listener: " + ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ tempo

    /** null se ok, altrimenti messaggio di errore in italiano. */
    public static String parseTimeError(Type t, String in) {
        if (t == Type.WARN || t == Type.REGOLAMENTO || t == null) {
            return null;
        }
        String s = in == null ? "" : in.trim().toLowerCase(Locale.ROOT);
        String fmtHelp = t == Type.BAN
                ? "Scrivi un tempo come 10s, 10m, 10h, 10d o una somma (es: 10h+23m+16s), \"permanente\" o \"blacklist\"."
                : "Scrivi un tempo come 10s, 10m, 10h, 10d o una somma (es: 10h+23m+16s), oppure \"permanente\".";
        if (s.isEmpty()) {
            return fmtHelp;
        }
        if (s.equals("permanente")) {
            return null;
        }
        if (s.equals("blacklist")) {
            return t == Type.BAN ? null : "\"blacklist\" vale solo per i ban.";
        }
        long ms = parseDurationMs(s);
        if (ms == -1) {
            return fmtHelp + " Un numero senza unita' vale minuti.";
        }
        if (ms == -2) {
            return "Tempo troppo grande: il massimo e' 10 anni.";
        }
        if (ms < 1000) {
            return "Il tempo minimo e' 1 secondo (es: 10s).";
        }
        return null;
    }

    private static final long MAX_MS = 10L * 365 * 86_400_000L;

    /** Somma di termini "numero+unita'" (s/m/h/d), numero nudo = minuti. -1 formato errato, -2 troppo grande. */
    private static long parseDurationMs(String s) {
        long total = 0;
        String[] parts = s.split("\\+", -1);
        for (String raw : parts) {
            String p = raw.trim();
            if (p.isEmpty()) {
                return -1;
            }
            char u = p.charAt(p.length() - 1);
            long mult = 60_000L;
            String num = p;
            if (!Character.isDigit(u)) {
                switch (u) {
                    case 's' -> mult = 1000L;
                    case 'm' -> mult = 60_000L;
                    case 'h' -> mult = 3_600_000L;
                    case 'd' -> mult = 86_400_000L;
                    default -> {
                        return -1;
                    }
                }
                num = p.substring(0, p.length() - 1).trim();
            }
            if (num.isEmpty() || num.length() > 12 || !num.chars().allMatch(Character::isDigit)) {
                return -1;
            }
            long v = Long.parseLong(num);
            if (v > MAX_MS / mult) {
                return -2;
            }
            total += v * mult;
            if (total > MAX_MS) {
                return -2;
            }
        }
        return total;
    }

    /** Converte l'input in millisecondi (>=1000), PERMANENT o BLACKLIST; 0 per WARN/REGOLAMENTO o input non valido. */
    public static long parseTime(Type t, String in) {
        if (t == Type.WARN || t == Type.REGOLAMENTO || parseTimeError(t, in) != null) {
            return 0;
        }
        String s = in.trim().toLowerCase(Locale.ROOT);
        if (s.equals("permanente")) {
            return PERMANENT;
        }
        if (s.equals("blacklist")) {
            return BLACKLIST;
        }
        return parseDurationMs(s);
    }

    public static String timeLabel(Rule r) {
        if (r == null) {
            return "-";
        }
        long ms = r.durationMs();
        if (ms == PERMANENT) {
            return "permanente";
        }
        if (ms == BLACKLIST) {
            return "blacklist";
        }
        if (ms <= 0) {
            return "-";
        }
        return SanctionManager.fmt(ms).trim();
    }

    public static String typeLabel(Type t) {
        return t == null ? "" : t.name();
    }

    // ------------------------------------------------------------------ persistenza

    private static UUID uuid(String s) {
        try {
            return s == null || s.isEmpty() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String str(UUID u) {
        return u == null ? "" : u.toString();
    }

    public synchronized void load() {
        rules.clear();
        histories.clear();
        hitLog.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = y.getConfigurationSection("rules");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            ConfigurationSection r = sec.getConfigurationSection(key);
            if (r == null) {
                continue;
            }
            String code = r.getString("code", key.replace('_', '.'));
            if (!validCode(code)) {
                continue;
            }
            Type type;
            try {
                type = Type.valueOf(r.getString("type", "REGOLAMENTO").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                continue;
            }
            rules.put(code, new Rule(code, r.getString("title", "Regola " + code), r.getString("text", ""), type,
                    r.contains("durationMs") ? r.getLong("durationMs", 0) : legacyToMs(r.getLong("minutes", 0)), uuid(r.getString("createdBy")), r.getString("createdByName", "sconosciuto"),
                    r.getLong("createdAt", 0), uuid(r.getString("editedBy")), r.getString("editedByName"),
                    r.getLong("editedAt", 0)));
        }
        loadLog();
    }

    private void loadLog() {
        if (!logFile.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(logFile);
        ConfigurationSection sec = y.getConfigurationSection("log");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            String code = key.replace('_', '.');
            if (!rules.containsKey(code)) {
                continue;
            }
            List<Change> ch = new ArrayList<>();
            for (Map<?, ?> m : sec.getMapList(key + ".changes")) {
                ch.add(new Change(num(m.get("time")), uuid(sv(m.get("by"))), sv(m.get("byName")), sv(m.get("field")),
                        sv(m.get("old")), sv(m.get("new"))));
            }
            histories.put(code, ch);
            List<Hit> hl = new ArrayList<>();
            for (Map<?, ?> m : sec.getMapList(key + ".hits")) {
                Type k;
                try {
                    k = Type.valueOf(sv(m.get("kind")));
                } catch (IllegalArgumentException e) {
                    continue;
                }
                hl.add(new Hit(num(m.get("time")), uuid(sv(m.get("target"))), sv(m.get("targetName")),
                        uuid(sv(m.get("by"))), sv(m.get("byName")), k,
                        m.containsKey("durationMs") ? num(m.get("durationMs")) : legacyToMs(num(m.get("minutes")))));
            }
            hitLog.put(code, hl);
        }
    }

    /** Migrazione dal vecchio formato in minuti: i valori speciali (<=0) restano invariati. */
    private static long legacyToMs(long minutes) {
        return minutes > 0 ? minutes * 60_000L : minutes;
    }

    private static String sv(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Rule r : rules.values()) {
            // la chiave non puo' contenere il punto (sarebbe un sotto-livello): uso "_"
            String p = "rules." + r.code().replace('.', '_') + ".";
            y.set(p + "code", r.code());
            y.set(p + "title", r.title());
            y.set(p + "text", r.text());
            y.set(p + "type", r.type().name());
            y.set(p + "durationMs", r.durationMs());
            y.set(p + "createdBy", str(r.createdBy()));
            y.set(p + "createdByName", r.createdByName());
            y.set(p + "createdAt", r.createdAt());
            y.set(p + "editedBy", str(r.editedBy()));
            y.set(p + "editedByName", r.editedByName());
            y.set(p + "editedAt", r.editedAt());
        }
        YamlConfiguration lg = new YamlConfiguration();
        for (Rule r : rules.values()) {
            String p = "log." + r.code().replace('.', '_');
            List<Map<String, Object>> ch = new ArrayList<>();
            for (Change c : histories.getOrDefault(r.code(), List.of())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", c.time());
                m.put("by", str(c.by()));
                m.put("byName", c.byName());
                m.put("field", c.field());
                m.put("old", c.oldValue());
                m.put("new", c.newValue());
                ch.add(m);
            }
            List<Map<String, Object>> hl = new ArrayList<>();
            for (Hit h : hitLog.getOrDefault(r.code(), List.of())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("time", h.time());
                m.put("target", str(h.target()));
                m.put("targetName", h.targetName());
                m.put("by", str(h.by()));
                m.put("byName", h.byName());
                m.put("kind", h.kind().name());
                m.put("durationMs", h.durationMs());
                hl.add(m);
            }
            lg.set(p + ".changes", ch);
            lg.set(p + ".hits", hl);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
            lg.save(logFile);
        } catch (IOException e) {
            plugin.getLogger().warning("rules.yml: " + e.getMessage());
        }
    }
}
