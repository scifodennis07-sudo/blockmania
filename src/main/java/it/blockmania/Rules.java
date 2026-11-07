package it.blockmania;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Regole del server (codice, testo, tipo di sanzione, durata), salvate in rules.yml.
 */
public final class Rules {

    public enum Type { BAN, WARN, MUTE, REGOLAMENTO }

    /** Valori speciali di Rule.minutes. */
    public static final long PERMANENT = -1, BLACKLIST = -2;

    public record Rule(String code, String text, Type type, long minutes) { }

    private final BlockMania plugin;
    private final File file;
    // codice -> regola
    private final TreeMap<String, Rule> rules = new TreeMap<>(Rules::compareCodes);
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    public Rules(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rules.yml");
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
            save();
        }
        fire();
        return true;
    }

    public boolean remove(String code) {
        synchronized (this) {
            if (code == null || rules.remove(code.trim()) == null) {
                return false;
            }
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
        if (s.isEmpty()) {
            return t == Type.BAN ? "Scrivi un numero di minuti, \"permanente\" o \"blacklist\"."
                    : "Scrivi un numero di minuti o \"permanente\".";
        }
        if (s.equals("permanente")) {
            return null;
        }
        if (s.equals("blacklist")) {
            return t == Type.BAN ? null : "\"blacklist\" vale solo per i ban.";
        }
        try {
            long v = Long.parseLong(s);
            if (v < 1) {
                return "Il numero di minuti deve essere almeno 1.";
            }
            if (v > 5_000_000_000L) {
                return "Numero di minuti troppo grande.";
            }
            return null;
        } catch (NumberFormatException e) {
            return t == Type.BAN ? "Scrivi un numero di minuti, \"permanente\" o \"blacklist\"."
                    : "Scrivi un numero di minuti o \"permanente\".";
        }
    }

    /** Converte l'input in minuti (>=1), PERMANENT o BLACKLIST; 0 per WARN/REGOLAMENTO o input non valido. */
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
        return Long.parseLong(s);
    }

    public static String timeLabel(Rule r) {
        if (r == null) {
            return "-";
        }
        long m = r.minutes();
        if (m == PERMANENT) {
            return "permanente";
        }
        if (m == BLACKLIST) {
            return "blacklist";
        }
        if (m <= 0) {
            return "-";
        }
        long d = m / 1440;
        long h = (m % 1440) / 60;
        long mi = m % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("g");
        }
        if (h > 0) {
            sb.append(sb.length() > 0 ? " " : "").append(h).append("h");
        }
        if (mi > 0 && d == 0) {
            sb.append(sb.length() > 0 ? " " : "").append(mi).append(" min");
        }
        return sb.length() == 0 ? m + " min" : sb.toString().replace("h min", "h");
    }

    public static String typeLabel(Type t) {
        return t == null ? "" : t.name();
    }

    // ------------------------------------------------------------------ persistenza

    public synchronized void load() {
        rules.clear();
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
            rules.put(code, new Rule(code, r.getString("text", ""), type, r.getLong("minutes", 0)));
        }
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (Rule r : rules.values()) {
            // la chiave non puo' contenere il punto (sarebbe un sotto-livello): uso "_"
            String p = "rules." + r.code().replace('.', '_') + ".";
            y.set(p + "code", r.code());
            y.set(p + "text", r.text());
            y.set(p + "type", r.type().name());
            y.set(p + "minutes", r.minutes());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("rules.yml: " + e.getMessage());
        }
    }
}
