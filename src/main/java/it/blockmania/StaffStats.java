package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Statistiche della carriera di uno staffer (profilo staff). I conteggi di mute/warn/ban/blacklist si derivano da
 * SanctionManager, ticket e stelle da Tickets; la sola cosa salvata qui e' la carriera (staff-career.yml).
 * Thread-safe per recordCareer(); le altre letture vanno fatte dal thread principale.
 */
public final class StaffStats {

    public enum Cat {
        BAN("Ban"), WARN("Warn"), MUTE("Mute"), BLACKLIST("Blacklist"), TICKET("Ticket"), VOICE("Assistenze vocali");

        private final String label;

        Cat(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum Act {
        DONE("Fatti"), UNDONE("Annullati"), MODIFIED("Modificati");

        private final String label;

        Act(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Count(int done, int undone, int modified) {}

    public record Entry(long ts, String who, String text, Act act) {}

    public record CareerEntry(long ts, String kind, String roleFrom, String roleTo, String byName) {}

    public record Info(long firstJoin, long playMs, long staffSince, String role) {}

    public record Ratings(double avg30, int n30, double avg7, int n7, double avgAll, int nAll) {}

    private static final long DAY_MS = 24L * 3600_000L;
    /** Soglia sopra la quale una durata di sanzione e' "per sempre" (SanctionManager usa Long.MAX_VALUE / 2). */
    private static final long FOREVER_MIN = Long.MAX_VALUE / 4;

    private final BlockMania plugin;
    private final File file;
    private final Map<UUID, LinkedList<CareerEntry>> career = new HashMap<>();

    public StaffStats(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "staff-career.yml");
    }

    // ------------------------------------------------------------------ persistenza (carriera)

    public synchronized void load() {
        career.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yaml.getConfigurationSection("players");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            LinkedList<CareerEntry> list = new LinkedList<>();
            for (String line : sec.getStringList(key)) {
                String[] p = line.split(";", 5);
                if (p.length < 5) {
                    continue;
                }
                try {
                    list.add(new CareerEntry(Long.parseLong(p[0]), p[1], p[2], p[3], p[4]));
                } catch (NumberFormatException ignored) {
                    // riga non valida: saltata
                }
            }
            career.put(id, list);
        }
    }

    public void save() {
        Map<String, List<String>> snap = new HashMap<>();
        synchronized (this) {
            for (Map.Entry<UUID, LinkedList<CareerEntry>> e : career.entrySet()) {
                List<String> lines = new ArrayList<>(e.getValue().size());
                for (CareerEntry c : e.getValue()) {
                    lines.add(c.ts() + ";" + clean(c.kind()) + ";" + clean(c.roleFrom()) + ";" + clean(c.roleTo()) + ";"
                            + clean(c.byName()));
                }
                snap.put(e.getKey().toString(), lines);
            }
        }
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, List<String>> e : snap.entrySet()) {
            yaml.set("players." + e.getKey(), e.getValue());
        }
        synchronized (file) {
            try {
                plugin.getDataFolder().mkdirs();
                File tmp = new File(plugin.getDataFolder(), "staff-career.yml.tmp");
                yaml.save(tmp);
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ex) {
                plugin.getLogger().severe("Non riesco a salvare staff-career.yml: " + ex.getMessage());
            }
        }
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(';', ',').replace('\n', ' ');
    }

    // ------------------------------------------------------------------ carriera

    public void recordCareer(UUID staff, UUID byOrNull, String kind, String roleFrom, String roleTo) {
        if (staff == null || kind == null) {
            return;
        }
        String byName = "Sistema";
        if (byOrNull != null) {
            String n = Bukkit.getOfflinePlayer(byOrNull).getName();
            byName = n == null ? byOrNull.toString().substring(0, 8) : n;
        }
        CareerEntry e = new CareerEntry(System.currentTimeMillis(), kind, roleFrom == null ? "" : roleFrom,
                roleTo == null ? "" : roleTo, byName);
        synchronized (this) {
            career.computeIfAbsent(staff, k -> new LinkedList<>()).addLast(e);
        }
        save();
    }

    /** Carriera, le voci piu' recenti prima. */
    public synchronized List<CareerEntry> career(UUID staff) {
        List<CareerEntry> out = new ArrayList<>();
        LinkedList<CareerEntry> list = career.get(staff);
        if (list != null) {
            for (java.util.Iterator<CareerEntry> it = list.descendingIterator(); it.hasNext(); ) {
                out.add(it.next());
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ info

    public Info info(UUID staff) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(staff);
        long first = 0;
        try {
            first = op.getFirstPlayed();
        } catch (Throwable ignored) {
            // non disponibile
        }
        long playMs = -1;
        try {
            // PLAY_ONE_MINUTE e' in tick (20 al secondo)
            playMs = op.getStatistic(Statistic.PLAY_ONE_MINUTE) * 50L;
        } catch (Throwable ignored) {
            // non disponibile
        }
        long since = 0;
        List<CareerEntry> c = career(staff);
        if (!c.isEmpty()) {
            since = c.get(c.size() - 1).ts();
            for (CareerEntry e : c) {
                if ("PEX".equals(e.kind())) {
                    since = e.ts(); // il piu' vecchio dei PEX (la lista parte dal piu' recente)
                }
            }
        }
        String role = "";
        StaffRoles roles = plugin.getStaffRoles();
        if (roles != null) {
            String id = roles.roleOf(staff);
            role = id == null ? "" : roles.label(id);
        }
        return new Info(first, playMs, since, role);
    }

    // ------------------------------------------------------------------ conteggi e liste

    public Count count(UUID staff, Cat cat) {
        int d = 0;
        int u = 0;
        int m = 0;
        for (Entry e : entries(staff, cat, null)) {
            switch (e.act()) {
                case DONE -> d++;
                case UNDONE -> u++;
                default -> m++;
            }
        }
        return new Count(d, u, m);
    }

    /** Azioni dello staffer per categoria, le piu' recenti prima; actOrNull = solo quel tipo. */
    public List<Entry> entries(UUID staff, Cat cat, Act actOrNull) {
        List<Entry> out = new ArrayList<>();
        if (staff == null || cat == null) {
            return out;
        }
        if (cat == Cat.TICKET) {
            addTickets(staff, out);
        } else if (cat != Cat.VOICE) {
            addSanctions(staff, cat, out);
        }
        // VOICE: funzione non ancora esistente, sempre vuoto
        if (actOrNull != null) {
            out.removeIf(e -> e.act() != actOrNull);
        }
        out.sort(Comparator.comparingLong(Entry::ts).reversed());
        return out;
    }

    private void addSanctions(UUID staff, Cat cat, List<Entry> out) {
        SanctionManager sm = plugin.getSanctions();
        if (sm == null) {
            return;
        }
        // per distinguere ban e blacklist su REMOVE/RESET/REDUCE (che non hanno durata) si ricorda l'ultimo ADD del target
        Map<UUID, Boolean> lastBanWasBlacklist = new HashMap<>();
        for (SanctionManager.Entry e : sm.allEntries()) {
            Cat c;
            switch (e.kind()) {
                case WARN -> c = Cat.WARN;
                case MUTE -> c = Cat.MUTE;
                default -> {
                    String a = e.action();
                    if ("ADD".equals(a)) {
                        boolean bl = e.durationMs() >= FOREVER_MIN && e.note() != null
                                && e.note().toLowerCase().contains("blacklist");
                        lastBanWasBlacklist.put(e.target(), bl);
                    }
                    c = lastBanWasBlacklist.getOrDefault(e.target(), false) ? Cat.BLACKLIST : Cat.BAN;
                }
            }
            if (c != cat || e.by() == null || !e.by().equals(staff)) {
                continue;
            }
            Act act = actOf(e.action());
            if (act == null) {
                continue;
            }
            out.add(new Entry(e.time(), nameOf(e.target()), textOf(e, act), act));
        }
    }

    /** ADD = fatto; REMOVE/RESET = annullato (sban, unwarn, unmute); REDUCE = modificato. AUTO_BAN/EXTEND (effetti dei warn) non contano. */
    private static Act actOf(String action) {
        if (action == null) {
            return null;
        }
        return switch (action) {
            case "ADD" -> Act.DONE;
            case "REMOVE", "RESET" -> Act.UNDONE;
            case "REDUCE" -> Act.MODIFIED;
            default -> null;
        };
    }

    private static String textOf(SanctionManager.Entry e, Act act) {
        StringBuilder sb = new StringBuilder();
        if (act == Act.DONE) {
            sb.append(e.reason() == null || e.reason().isBlank() ? "(senza motivo)" : e.reason());
            if (e.durationMs() > 0) {
                sb.append(" (").append(e.durationMs() >= FOREVER_MIN ? "permanente" : SanctionManager.fmt(e.durationMs())).append(')');
            }
        } else if (act == Act.MODIFIED) {
            sb.append("Ridotto di ").append(SanctionManager.fmt(e.durationMs()));
        } else {
            sb.append("RESET".equals(e.action()) ? "Azzerato" : "Tolto");
        }
        return sb.toString();
    }

    private void addTickets(UUID staff, List<Entry> out) {
        Tickets tk = plugin.getTickets();
        if (tk == null) {
            return;
        }
        for (Tickets.StaffView t : tk.ticketsRepliedBy(staff)) {
            long ts = t.closedAt() > 0 ? t.closedAt() : t.created();
            String text = "Ticket #" + t.id() + (t.open() ? " (aperto)" : staff.equals(t.closedBy()) ? " (chiuso da te)" : " (chiuso)")
                    + (t.rating() > 0 ? ", voto " + t.rating() + "/5" : "");
            out.add(new Entry(ts, t.ownerName() == null || t.ownerName().isEmpty() ? "?" : t.ownerName(), text, Act.DONE));
        }
    }

    // ------------------------------------------------------------------ stelle

    public Ratings ratings(UUID staff) {
        double s30 = 0;
        double s7 = 0;
        double sAll = 0;
        int n30 = 0;
        int n7 = 0;
        int nAll = 0;
        Tickets tk = plugin.getTickets();
        if (staff != null && tk != null) {
            long now = System.currentTimeMillis();
            for (Tickets.StaffView t : tk.ticketsRepliedBy(staff)) {
                if (t.open() || t.rating() <= 0) {
                    continue;
                }
                long ts = t.closedAt() > 0 ? t.closedAt() : t.created();
                sAll += t.rating();
                nAll++;
                if (now - ts <= 30 * DAY_MS) {
                    s30 += t.rating();
                    n30++;
                }
                if (now - ts <= 7 * DAY_MS) {
                    s7 += t.rating();
                    n7++;
                }
            }
        }
        return new Ratings(n30 == 0 ? -1 : s30 / n30, n30, n7 == 0 ? -1 : s7 / n7, n7, nAll == 0 ? -1 : sAll / nAll, nAll);
    }

    private static String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }
}
