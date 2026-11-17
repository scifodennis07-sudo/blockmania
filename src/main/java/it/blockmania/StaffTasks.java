package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Task dello staff v2 (GUI TaskGui, /adminmania task). Una task e' una CARTELLA per un player con piu' step (azioni):
 * dai / togli / setta / azzera una risorsa (con la sua specifica: tipo di ball, ricompensa, potenziamento) oppure
 * banna / warna / muta con un codice regola. Ogni step e' ORDINARIA o CUSTOM (con un messaggio libero).
 * Gli helper creano, i mod eseguono/rifiutano gli step ordinari, gli sr_mod quelli custom, gli admin eliminano e
 * ripristinano. Le azioni usano i metodi interni di /adminmania e del profilo player (PlayerCounters.apply,
 * AmmoManager, UpgradeManager, Rewards.setReached, Power, SanctionManager.applyRule).
 * Tutto finisce nel resoconto. Si salva in staff-tasks.yml (version: 2). Da usare dal thread principale.
 */
public final class StaffTasks {

    public enum Kind { ORDINARIA, CUSTOM }

    /** Per task e per step (ELIMINATA solo per le task). */
    public enum Status { APERTA, ESEGUITA, RIFIUTATA, ELIMINATA }

    /**
     * verb = give|remove|set|reset|ban|warn|mute ; what = ball|ricompense|coin|blockcoin|blockshell|mine|upgrade|power|stamina
     * (null per ban/warn/mute) ; spec = id ball / ricompensa / potenziamento ("all" = tutte) o null ; text = solo CUSTOM.
     */
    public record Step(String id, Kind kind, String verb, String what, String spec, long amount, String ruleCode, String text,
                       Status status, UUID doneBy, String doneByName, long doneAt, String reason) {
    }

    /** status: APERTA finche' c'e' uno step aperto; poi ESEGUITA (almeno uno eseguito) o RIFIUTATA; ELIMINATA = cestino. */
    public record Task(String id, UUID target, String targetName, UUID createdBy, String createdByName, long createdAt,
                       Status status, List<Step> steps) {
    }

    /** event: CREATA ESEGUITA RIFIUTATA ELIMINATA RIPRISTINATA ; stepId null per gli eventi della task intera. */
    public record LogEntry(long ts, String taskId, String stepId, String event, UUID by, String byName, String detail) {
    }

    /** Specifica selezionabile (id interno, nome italiano). */
    public record Spec(String id, String label) {
    }

    private static final int VERSION = 2;
    private static final int MAX_LOG = 5000;

    private static final List<String> VERBS = List.of("give", "remove", "set", "reset", "ban", "warn", "mute");
    private static final List<String> WHATS = List.of("ball", "ricompense", "coin", "blockcoin", "blockshell", "mine",
            "upgrade", "power", "stamina");
    /** Ricompense con livelli (la giornaliera no: si gestisce a giorni dal profilo). Ordine e nomi come rewards-gui.types. */
    private static final String[][] REWARDS = {
            {"broken", "blocchi spaccati"}, {"hits", "blocchi colpiti"}, {"bc_earned", "blockcoin guadagnati"},
            {"bc_spent", "blockcoin spesi"}, {"coin_earned", "coin guadagnati"}, {"coin_spent", "coin spesi"},
            {"platinum", "miniere platinate"}, {"play", "tempo di gioco"}, {"pass", "battlepass"}};

    // indici in StaffRoles.SCALE
    private static final int HELPER = StaffRoles.SCALE.indexOf("helper");
    private static final int MOD = StaffRoles.SCALE.indexOf("mod");
    private static final int SR_MOD = StaffRoles.SCALE.indexOf("sr_mod");
    private static final int ADMIN = StaffRoles.SCALE.indexOf("admin");

    private final BlockMania plugin;
    private final File file;
    private final Map<String, Task> tasks = new LinkedHashMap<>();
    private final List<LogEntry> log = new ArrayList<>();

    public StaffTasks(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "staff-tasks.yml");
    }

    // ------------------------------------------------------------------ metadati per la GUI

    /** Verbi in ordine: give remove set reset ban warn mute. */
    public static List<String> verbs() {
        return VERBS;
    }

    /** Cose valide per il verbo (vuota per ban/warn/mute e per i verbi sconosciuti). */
    public static List<String> whatsFor(String verb) {
        String v = low(verb);
        return v.equals("give") || v.equals("remove") || v.equals("set") || v.equals("reset") ? WHATS : List.of();
    }

    /** Specifiche per verbo + cosa: ball (tipi + tutte), ricompense (tipologie), upgrade (potenziamenti + tutti). */
    public List<Spec> specsFor(String verb, String what) {
        List<Spec> out = new ArrayList<>();
        if (!whatsFor(verb).contains(low(what))) {
            return out;
        }
        switch (low(what)) {
            case "ball" -> {
                for (String t : plugin.getAmmo().types()) {
                    String id = t.toLowerCase(Locale.ROOT);
                    out.add(new Spec(id, specLabel("ball", id)));
                }
                out.add(new Spec("all", "tutte"));
            }
            case "ricompense" -> {
                for (String[] r : REWARDS) {
                    out.add(new Spec(r[0], r[1]));
                }
            }
            case "upgrade" -> {
                UpgradeManager up = plugin.getUpgrades();
                if (up != null) {
                    for (String id : up.ids()) {
                        out.add(new Spec(id, specLabel("upgrade", id)));
                    }
                }
                out.add(new Spec("all", "tutti"));
            }
            default -> {
            }
        }
        return out;
    }

    /** Testo del passo valore ("ball da dare", "livelli da togliere"...); null = nessun valore (azzera, sanzioni). */
    public static String valuePrompt(String verb, String what, String spec) {
        String v = low(verb);
        String w = low(what);
        if (!whatsFor(v).contains(w) || v.equals("reset")) {
            return null;
        }
        String act = switch (v) {
            case "give" -> "da dare";
            case "remove" -> "da togliere";
            default -> "da settare";
        };
        String noun = switch (w) {
            case "ball" -> "ball";
            case "ricompense", "upgrade" -> v.equals("set") ? "livello" : "livelli";
            case "coin" -> "coin";
            case "blockcoin" -> "blockcoin";
            case "blockshell" -> "blockshell";
            case "mine" -> v.equals("set") ? "miniera" : "miniere";
            case "power" -> "colpi potenziati";
            default -> "% stamina";
        };
        return noun + " " + act;
    }

    /** {min, max} per askNumber; {0, 0} se non serve un valore. Il massimo reale del player si applica all'esecuzione. */
    public static long[] valueRange(String verb, String what) {
        String v = low(verb);
        String w = low(what);
        if (!whatsFor(v).contains(w) || v.equals("reset")) {
            return new long[]{0, 0};
        }
        long min = v.equals("set") ? (w.equals("mine") ? 1 : 0) : 1;
        long max = switch (w) {
            case "stamina" -> 100;
            case "ricompense", "upgrade" -> 1000;
            default -> Integer.MAX_VALUE;
        };
        return new long[]{min, max};
    }

    /** Nome italiano del verbo: dai, togli, setta, azzera, banna, warna, muta. */
    public static String label(String verb) {
        return switch (low(verb)) {
            case "give" -> "dai";
            case "remove" -> "togli";
            case "set" -> "setta";
            case "reset" -> "azzera";
            case "ban" -> "banna";
            case "warn" -> "warna";
            case "mute" -> "muta";
            default -> low(verb);
        };
    }

    /** Nome italiano della cosa. */
    public static String labelWhat(String what) {
        return switch (low(what)) {
            case "ball" -> "ball";
            case "ricompense" -> "ricompense";
            case "coin" -> "coin";
            case "blockcoin" -> "blockcoin";
            case "blockshell" -> "blockshell";
            case "mine" -> "miniere";
            case "upgrade" -> "potenziamenti";
            case "power" -> "poteri";
            case "stamina" -> "stamina";
            default -> low(what);
        };
    }

    /** Riga leggibile: "dai 5 ball ghost", "setta livello 3 battlepass (ricompense)", "warna (regola 1.2)". */
    public static String describe(Step s) {
        if (s == null) {
            return "";
        }
        String v = low(s.verb());
        if (isSanction(v)) {
            return label(v) + " (regola " + (s.ruleCode() == null ? "?" : s.ruleCode()) + ")";
        }
        String w = low(s.what());
        String spec = s.spec() == null || s.spec().isEmpty() ? "" : " " + specLabel(w, s.spec());
        if (v.equals("reset")) {
            return label(v) + " " + labelWhat(w) + spec;
        }
        String amount = w.equals("stamina") ? s.amount() + "%" : String.valueOf(s.amount());
        return switch (w) {
            // "dai 5 livelli battlepass (ricompense)", "setta livello 3 danno (potenziamenti)"
            case "ricompense", "upgrade" -> label(v) + " " + (v.equals("set") ? "livello " + amount : amount + " livelli") + spec
                    + " (" + labelWhat(w) + ")";
            case "stamina" -> label(v) + " " + amount + " stamina";
            default -> label(v) + " " + amount + " " + labelWhat(w) + spec;
        };
    }

    /** Nuovo step APERTO (id provvisorio; create assegna l'id definitivo). Normalizza i campi che non servono. */
    public static Step newStep(Kind kind, String verb, String what, String spec, long amount, String ruleCode, String text) {
        String v = low(verb);
        boolean sanction = isSanction(v);
        String w = sanction ? null : low(what);
        String sp = sanction || spec == null || spec.isBlank() ? null : spec.trim().toLowerCase(Locale.ROOT);
        long a = sanction || v.equals("reset") ? 0 : amount;
        String rc = sanction && ruleCode != null ? ruleCode.trim() : null;
        String tx = kind == Kind.CUSTOM && text != null ? text.trim() : "";
        return new Step(UUID.randomUUID().toString().substring(0, 8), kind == null ? Kind.ORDINARIA : kind, v, w, sp, a, rc, tx,
                Status.APERTA, null, "", 0L, "");
    }

    // ------------------------------------------------------------------ persistenza

    public synchronized void load() {
        tasks.clear();
        log.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        if (y.getInt("version", 1) != VERSION) {
            // formato v1 (task con una sola azione): si mette da parte
            File old = new File(plugin.getDataFolder(), "staff-tasks.yml.old");
            if (old.exists()) {
                old.delete();
            }
            if (file.renameTo(old)) {
                plugin.getLogger().info("staff-tasks.yml: formato vecchio rinominato in staff-tasks.yml.old");
            } else {
                plugin.getLogger().warning("staff-tasks.yml: formato vecchio, non riesco a rinominarlo (lo ignoro)");
            }
            return;
        }
        for (Map<?, ?> m : y.getMapList("tasks")) {
            try {
                Object id = m.get("id");
                Object status = m.get("status");
                UUID target = uuid(str(m.get("target")));
                if (id == null || status == null || target == null) {
                    continue;
                }
                List<Step> steps = new ArrayList<>();
                Object raw = m.get("steps");
                if (raw instanceof List<?> list) {
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> sm) {
                            Step s = readStep(sm);
                            if (s != null) {
                                steps.add(s);
                            }
                        }
                    }
                }
                Task t = new Task(String.valueOf(id), target, str(m.get("targetName")), uuid(str(m.get("createdBy"))),
                        str(m.get("createdByName")), num(m.get("createdAt")), Status.valueOf(String.valueOf(status)), steps);
                tasks.put(t.id(), t);
            } catch (IllegalArgumentException ignored) {
                // voce non valida: saltata
            }
        }
        for (Map<?, ?> m : y.getMapList("log")) {
            Object tid = m.get("task");
            Object ev = m.get("event");
            if (tid == null || ev == null) {
                continue;
            }
            String sid = str(m.get("step"));
            log.add(new LogEntry(num(m.get("ts")), String.valueOf(tid), sid.isEmpty() ? null : sid, String.valueOf(ev),
                    uuid(str(m.get("by"))), str(m.get("byName")), str(m.get("detail"))));
        }
    }

    private static Step readStep(Map<?, ?> m) {
        try {
            Object id = m.get("id");
            Object kind = m.get("kind");
            Object status = m.get("status");
            if (id == null || kind == null || status == null) {
                return null;
            }
            String what = str(m.get("what"));
            String spec = str(m.get("spec"));
            String rule = str(m.get("rule"));
            return new Step(String.valueOf(id), Kind.valueOf(String.valueOf(kind)), str(m.get("verb")),
                    what.isEmpty() ? null : what, spec.isEmpty() ? null : spec, num(m.get("amount")),
                    rule.isEmpty() ? null : rule, str(m.get("text")), Status.valueOf(String.valueOf(status)),
                    uuid(str(m.get("doneBy"))), str(m.get("doneByName")), num(m.get("doneAt")), str(m.get("reason")));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("version", VERSION);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("target", t.target().toString());
            m.put("targetName", nz(t.targetName()));
            m.put("createdBy", t.createdBy() == null ? "" : t.createdBy().toString());
            m.put("createdByName", nz(t.createdByName()));
            m.put("createdAt", t.createdAt());
            m.put("status", t.status().name());
            List<Map<String, Object>> steps = new ArrayList<>();
            for (Step s : t.steps()) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("id", s.id());
                sm.put("kind", s.kind().name());
                sm.put("verb", nz(s.verb()));
                sm.put("what", nz(s.what()));
                sm.put("spec", nz(s.spec()));
                sm.put("amount", s.amount());
                sm.put("rule", nz(s.ruleCode()));
                sm.put("text", nz(s.text()));
                sm.put("status", s.status().name());
                sm.put("doneBy", s.doneBy() == null ? "" : s.doneBy().toString());
                sm.put("doneByName", nz(s.doneByName()));
                sm.put("doneAt", s.doneAt());
                sm.put("reason", nz(s.reason()));
                steps.add(sm);
            }
            m.put("steps", steps);
            out.add(m);
        }
        y.set("tasks", out);
        List<Map<String, Object>> lo = new ArrayList<>();
        for (LogEntry e : log) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", e.ts());
            m.put("task", e.taskId());
            m.put("step", nz(e.stepId()));
            m.put("event", e.event());
            m.put("by", e.by() == null ? "" : e.by().toString());
            m.put("byName", nz(e.byName()));
            m.put("detail", nz(e.detail()));
            lo.add(m);
        }
        y.set("log", lo);
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("staff-tasks.yml: " + ex.getMessage());
        }
    }

    public void stop() {
        save();
    }

    // ------------------------------------------------------------------ letture

    /** Task con quello stato (null = tutte), le piu' recenti prima. */
    public synchronized List<Task> list(Status statusOrNull) {
        List<Task> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            if (statusOrNull == null || t.status() == statusOrNull) {
                out.add(t);
            }
        }
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    public synchronized Task get(String id) {
        return id == null ? null : tasks.get(id);
    }

    /**
     * Resoconto, le voci piu' recenti prima. onlyForOrNull = tutte le voci delle task create da lui
     * piu' le voci fatte da lui (step eseguiti/rifiutati).
     */
    public synchronized List<LogEntry> log(UUID onlyForOrNull) {
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : log) {
            if (onlyForOrNull != null) {
                Task t = tasks.get(e.taskId());
                boolean mine = onlyForOrNull.equals(e.by()) || (t != null && onlyForOrNull.equals(t.createdBy()));
                if (!mine) {
                    continue;
                }
            }
            out.add(e);
        }
        out.sort((a, b) -> Long.compare(b.ts(), a.ts()));
        return out;
    }

    // ------------------------------------------------------------------ permessi

    /** Indice del grado in StaffRoles.SCALE (-1 = nessuno/solo speciali); OP = tutto. */
    private int rank(Player p) {
        if (p == null) {
            return -1;
        }
        if (p.isOp()) {
            return StaffRoles.SCALE.size() - 1;
        }
        StaffRoles r = plugin.getStaffRoles();
        String role = r == null ? null : r.roleOf(p.getUniqueId());
        return role == null ? -1 : StaffRoles.SCALE.indexOf(role);
    }

    private boolean suspended(Player p) {
        StaffDiscipline d = plugin.getStaffDiscipline();
        return p != null && d != null && d.isSuspended(p.getUniqueId());
    }

    public boolean canCreate(Player p) {
        return p != null && !suspended(p) && rank(p) >= HELPER;
    }

    /**
     * Puo' eseguire o rifiutare lo step: step aperto in una task aperta, non creata da lui, non su se stesso (salvo admin+),
     * mod+ per ORDINARIA (e sanzioni), sr_mod+ per CUSTOM. Per le sanzioni all'esecuzione servono anche i permessi
     * "sanctions" di /adminmania (AdminCommand.authorize).
     */
    public boolean canExecute(Player p, Step s) {
        Task t = findTask(s == null ? null : s.id());
        return cannot(p, t, stepOf(t, s == null ? null : s.id())) == null;
    }

    public boolean isAdmin(Player p) {
        return p != null && !suspended(p) && rank(p) >= ADMIN;
    }

    // ------------------------------------------------------------------ azioni

    /** Crea la cartella; null se non puo' crearla o uno step non e' valido (o la lista e' vuota). */
    public Task create(Player by, UUID target, List<Step> steps) {
        if (by == null || target == null || steps == null || steps.isEmpty() || !canCreate(by)) {
            return null;
        }
        // niente task su se stessi (un helper non si fa dare risorse da un mod), salvo gli admin
        if (by.getUniqueId().equals(target) && !isAdmin(by)) {
            return null;
        }
        for (Step s : steps) {
            if (invalid(s) != null) {
                return null;
            }
        }
        Task t;
        synchronized (this) {
            List<Step> clean = new ArrayList<>();
            for (Step s : steps) {
                Step n = newStep(s.kind(), s.verb(), s.what(), s.spec(), s.amount(), s.ruleCode(), s.text());
                clean.add(new Step(newStepId(clean), n.kind(), n.verb(), n.what(), n.spec(), n.amount(), n.ruleCode(), n.text(),
                        Status.APERTA, null, "", 0L, ""));
            }
            t = new Task(newTaskId(), target, playerName(target), by.getUniqueId(), by.getName(), System.currentTimeMillis(),
                    Status.APERTA, List.copyOf(clean));
            tasks.put(t.id(), t);
            addLog(t.id(), null, "CREATA", by, clean.size() + (clean.size() == 1 ? " azione: " : " azioni: ") + summary(clean));
            save();
        }
        movement(by.getUniqueId(), "Ha creato la task staff " + t.id() + " su " + t.targetName() + ": " + summary(t.steps()));
        log("task " + t.id() + " creata da " + by.getName() + " su " + t.targetName() + ": " + summary(t.steps()));
        return t;
    }

    /** Esegue davvero lo step. null = ok, altrimenti il messaggio d'errore. */
    public String executeStep(Player by, String taskId, String stepId) {
        Task t = get(taskId);
        Step s = stepOf(t, stepId);
        String why = cannot(by, t, s);
        if (why != null) {
            return why;
        }
        String v = low(s.verb());
        String err;
        if (isSanction(v)) {
            err = applySanction(by, t, s);
        } else {
            Player target = Bukkit.getPlayer(t.target());
            if (target == null) {
                return "il player " + t.targetName() + " e' offline: si puo' eseguire solo quando e' online";
            }
            err = apply(by, target, s);
        }
        if (err != null) {
            return err;
        }
        Task done;
        synchronized (this) {
            done = closeStep(t.id(), s.id(), Status.ESEGUITA, by, "");
            addLog(t.id(), s.id(), "ESEGUITA", by, describe(s));
            save();
        }
        AdminCommand ac = plugin.getAdminCommand();
        if (ac != null) {
            ac.record(by, "task " + t.id() + "/" + s.id() + " (" + kindLabel(s.kind()) + ") | " + describe(s) + " | " + t.targetName());
        }
        movement(t.target(), "Task staff " + t.id() + ": " + describe(s) + " (eseguita da " + by.getName() + ")");
        movement(by.getUniqueId(), "Ha eseguito nella task staff " + t.id() + " di " + t.createdByName() + ": " + describe(s)
                + " su " + t.targetName());
        notify(t.createdBy(), "&aNella tua task &f" + t.id() + "&a (" + t.targetName() + ") &f" + by.getName()
                + "&a ha eseguito: &7" + describe(s) + closedNote(done));
        log("task " + t.id() + " step " + s.id() + " eseguito da " + by.getName() + ": " + describe(s));
        return null;
    }

    /** Rifiuta lo step con un motivo. null = ok, altrimenti il messaggio d'errore. */
    public String rejectStep(Player by, String taskId, String stepId, String reason) {
        Task t = get(taskId);
        Step s = stepOf(t, stepId);
        String why = cannot(by, t, s);
        if (why != null) {
            return why;
        }
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) {
            return "scrivi il motivo del rifiuto";
        }
        Task done;
        synchronized (this) {
            done = closeStep(t.id(), s.id(), Status.RIFIUTATA, by, r);
            addLog(t.id(), s.id(), "RIFIUTATA", by, describe(s) + " | motivo: " + r);
            save();
        }
        movement(by.getUniqueId(), "Ha rifiutato nella task staff " + t.id() + " di " + t.createdByName() + ": " + describe(s)
                + " (" + r + ")");
        notify(t.createdBy(), "&cNella tua task &f" + t.id() + "&c (" + t.targetName() + ") &f" + by.getName()
                + "&c ha rifiutato: &7" + describe(s) + " &7Motivo: &f\"" + r + "\"" + closedNote(done));
        log("task " + t.id() + " step " + s.id() + " rifiutato da " + by.getName() + ": " + r);
        return null;
    }

    /** Admin: manda la task nel cestino. */
    public boolean delete(Player admin, String taskId) {
        if (!isAdmin(admin)) {
            return false;
        }
        Task t;
        synchronized (this) {
            t = tasks.get(taskId);
            if (t == null || t.status() == Status.ELIMINATA) {
                return false;
            }
            tasks.put(t.id(), withStatus(t, Status.ELIMINATA));
            addLog(t.id(), null, "ELIMINATA", admin, "era " + statusLabel(t.status()));
            save();
        }
        movement(admin.getUniqueId(), "Ha eliminato la task staff " + t.id() + " di " + t.createdByName());
        log("task " + t.id() + " eliminata da " + admin.getName());
        return true;
    }

    /** Admin: rimette la task dal cestino con lo stato calcolato dagli step. */
    public boolean restore(Player admin, String taskId) {
        if (!isAdmin(admin)) {
            return false;
        }
        Task t;
        Status back;
        synchronized (this) {
            t = tasks.get(taskId);
            if (t == null || t.status() != Status.ELIMINATA) {
                return false;
            }
            back = statusOf(t.steps());
            tasks.put(t.id(), withStatus(t, back));
            addLog(t.id(), null, "RIPRISTINATA", admin, "torna " + statusLabel(back));
            save();
        }
        movement(admin.getUniqueId(), "Ha ripristinato la task staff " + t.id() + " di " + t.createdByName());
        log("task " + t.id() + " ripristinata da " + admin.getName() + " (" + statusLabel(back) + ")");
        return true;
    }

    // ------------------------------------------------------------------ applicazione delle azioni

    /** Sanzione con regola, come UserProfileGui.doRuleSanction (permessi sanctions + SanctionManager.applyRule). */
    private String applySanction(Player by, Task t, Step s) {
        String v = low(s.verb());
        Rules rules = plugin.getRules();
        Rules.Rule rule = rules == null || s.ruleCode() == null ? null : rules.get(s.ruleCode());
        if (rule == null || !rule.type().name().equalsIgnoreCase(v)) {
            return "la regola " + s.ruleCode() + " non esiste piu' o non e' di tipo " + v;
        }
        AdminCommand ac = plugin.getAdminCommand();
        if (ac != null && !ac.authorize(by, "sanctions", v, t.target(), 0)) {
            return "il tuo ruolo non puo' fare " + v;
        }
        SanctionManager sm = plugin.getSanctions();
        if (sm == null) {
            return "sanzioni non disponibili";
        }
        if (!sm.applyRule(by, t.target(), rule, null)) {
            return "nessuna sanzione applicata (regola non applicabile o limite warn raggiunto)";
        }
        return null;
    }

    /** dai / togli / setta / azzera con i metodi interni (come /adminmania e il profilo player). null = ok. */
    private String apply(Player by, Player target, Step s) {
        String v = low(s.verb());
        String w = low(s.what());
        String spec = s.spec();
        int n = (int) Math.max(0, Math.min(Integer.MAX_VALUE, s.amount()));
        AmmoManager ammo = plugin.getAmmo();
        switch (w) {
            case "coin", "blockcoin", "blockshell", "power" -> {
                PlayerCounters pc = plugin.getCounters();
                if (pc == null) {
                    return "contatori non disponibili";
                }
                PlayerCounters.Item it = switch (w) {
                    case "coin" -> PlayerCounters.Item.COIN;
                    case "blockcoin" -> PlayerCounters.Item.BLOCKCOIN;
                    case "blockshell" -> PlayerCounters.Item.BLOCKSHELL;
                    default -> PlayerCounters.Item.POWER_CHARGES;
                };
                String err = pc.apply(target.getUniqueId(), it, v.equals("give") ? "give" : v, n, by);
                return err == null ? null : err.toLowerCase(Locale.ROOT);
            }
            case "ball" -> {
                List<String> chosen = new ArrayList<>();
                boolean all = spec == null || spec.equals("all");
                if (all) {
                    for (String bt : ammo.types()) {
                        chosen.add(bt.toLowerCase(Locale.ROOT));
                    }
                } else if (ammo.exists(spec)) {
                    chosen.add(spec);
                } else {
                    return "tipo di ball sconosciuto: " + spec;
                }
                if (!all && v.equals("set") && n > ammo.getAmmoMax(target, spec)) {
                    return "il massimo di " + specLabel("ball", spec) + " per questo player e' " + ammo.getAmmoMax(target, spec);
                }
                for (String bt : chosen) {
                    int cur = ammo.getAmmo(target, bt);
                    int next = next(v, cur, n, ammo.getAmmoMax(target, bt));
                    if (next != cur) {
                        ammo.setAmmo(target, bt, next);
                    }
                }
                moved(target, MovementLog.Type.ITEMS, v, n, "ball " + (all ? "(tutte)" : specLabel("ball", spec)));
            }
            case "mine" -> {
                int cur = ammo.getMine(target);
                int next = v.equals("set") ? Math.max(1, n) : next(v, cur, n, Integer.MAX_VALUE);
                if (next != cur) {
                    ammo.setMine(target, next);
                }
                moved(target, MovementLog.Type.ITEMS, v, n, "miniere");
            }
            case "upgrade" -> {
                UpgradeManager up = plugin.getUpgrades();
                List<String> chosen = new ArrayList<>();
                boolean all = spec == null || spec.equals("all");
                if (all) {
                    chosen.addAll(up.ids());
                } else if (up.ids().contains(spec)) {
                    chosen.add(spec);
                } else {
                    return "potenziamento sconosciuto: " + spec;
                }
                for (String id : chosen) {
                    int cur = up.getLevel(target, id);
                    int next = next(v, cur, n, up.maxLevel(id));
                    if (next != cur) {
                        up.setLevelAdmin(target, id, next);
                    }
                }
                moved(target, MovementLog.Type.UPGRADE, v, n,
                        "livelli " + (all ? "di tutti i potenziamenti" : specLabel("upgrade", spec)));
            }
            case "ricompense" -> {
                Rewards rw = plugin.getRewards();
                if (rw == null) {
                    return "ricompense non disponibili";
                }
                if (spec == null || rewardLabel(spec) == null) {
                    return "ricompensa sconosciuta: " + spec;
                }
                int cur = rw.reached(target, spec);
                int max = rw.staffMaxLevel(spec);
                rw.setReached(target, spec, next(v, cur, n, max));
                moved(target, MovementLog.Type.REWARD, v, n, "livelli " + rewardLabel(spec));
            }
            case "stamina" -> {
                Power power = plugin.getPower();
                if (power == null) {
                    return "stamina non disponibile";
                }
                double cur = power.stamina(target) * 100.0;
                switch (v) {
                    case "give" -> power.setPercent(target, Math.min(100.0, cur + n));
                    case "remove" -> power.setPercent(target, Math.max(0.0, cur - n));
                    case "set" -> power.setPercent(target, Math.min(100.0, n));
                    default -> {
                        // azzera = come /adminmania profile stamina empty
                        power.setMode(target, "normal");
                        power.drain(target, 1.0);
                    }
                }
                ammo.refreshMain(target);
                moved(target, MovementLog.Type.ITEMS, v, n, "% stamina");
            }
            default -> {
                return "azione non valida";
            }
        }
        return null;
    }

    /** give fino al massimo, remove fino a 0, set limitato al massimo, reset = 0 (come runStats / setLevelAdmin). */
    private static int next(String verb, int cur, int n, int max) {
        return switch (verb) {
            case "give" -> (int) Math.min((long) max, (long) cur + n);
            case "remove" -> Math.max(0, cur - n);
            case "set" -> Math.min(n, max);
            default -> 0;
        };
    }

    private void moved(Player target, MovementLog.Type type, String verb, int n, String what) {
        if (plugin.getMovements() == null) {
            return;
        }
        String text = switch (verb) {
            case "give" -> "Ha ricevuto " + n + " " + what;
            case "remove" -> "Gli sono stati tolti " + n + " " + what;
            case "set" -> "Ha " + what + " impostato a " + n;
            default -> "Ha azzerato " + what;
        };
        plugin.getMovements().record(target.getUniqueId(), type, text + " (task staff)");
    }

    // ------------------------------------------------------------------ interni

    /** Motivo per cui by non puo' eseguire/rifiutare lo step, o null. */
    private String cannot(Player by, Task t, Step s) {
        if (t == null || s == null) {
            return "azione non trovata";
        }
        if (by == null) {
            return "solo uno staffer puo' farlo";
        }
        if (t.status() == Status.ELIMINATA) {
            return "la task e' nel cestino";
        }
        if (s.status() != Status.APERTA) {
            return "questa azione e' gia' chiusa";
        }
        if (suspended(by)) {
            return "sei sospeso dallo staff";
        }
        if (by.getUniqueId().equals(t.createdBy())) {
            return "non puoi eseguire o rifiutare le azioni di una task creata da te";
        }
        if (by.getUniqueId().equals(t.target()) && !isAdmin(by)) {
            return "col tuo grado non puoi eseguire azioni su te stesso";
        }
        if (rank(by) < (s.kind() == Kind.CUSTOM ? SR_MOD : MOD)) {
            return s.kind() == Kind.CUSTOM ? "le azioni custom le gestiscono gli sr_mod in su" : "le azioni le gestiscono i mod in su";
        }
        return null;
    }

    /** Motivo per cui lo step non e' valido, o null. */
    private String invalid(Step s) {
        if (s == null || s.kind() == null) {
            return "azione vuota";
        }
        String v = low(s.verb());
        if (!VERBS.contains(v)) {
            return "verbo sconosciuto";
        }
        if (s.kind() == Kind.CUSTOM && (s.text() == null || s.text().isBlank())) {
            return "manca il messaggio custom";
        }
        if (isSanction(v)) {
            Rules rules = plugin.getRules();
            Rules.Rule rule = rules == null || s.ruleCode() == null ? null : rules.get(s.ruleCode().trim());
            return rule != null && rule.type().name().equalsIgnoreCase(v) ? null : "regola inesistente o di altro tipo";
        }
        String w = low(s.what());
        if (!whatsFor(v).contains(w)) {
            return "cosa non valida";
        }
        List<Spec> specs = specsFor(v, w);
        if (!specs.isEmpty()) {
            String sp = s.spec() == null ? "" : s.spec().toLowerCase(Locale.ROOT);
            boolean found = false;
            for (Spec x : specs) {
                if (x.id().equals(sp)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return "specifica non valida";
            }
        }
        if (v.equals("reset")) {
            return null;
        }
        long[] r = valueRange(v, w);
        return s.amount() >= r[0] && s.amount() <= r[1] ? null : "valore fuori dai limiti";
    }

    private static boolean isSanction(String verb) {
        return verb.equals("ban") || verb.equals("warn") || verb.equals("mute");
    }

    private synchronized Task findTask(String stepId) {
        if (stepId == null) {
            return null;
        }
        for (Task t : tasks.values()) {
            if (stepOf(t, stepId) != null) {
                return t;
            }
        }
        return null;
    }

    private static Step stepOf(Task t, String stepId) {
        if (t == null || stepId == null) {
            return null;
        }
        for (Step s : t.steps()) {
            if (s.id().equals(stepId)) {
                return s;
            }
        }
        return null;
    }

    /** Chiude lo step e ricalcola lo stato della task. Chiamare dentro synchronized. Non salva. */
    private Task closeStep(String taskId, String stepId, Status st, Player by, String reason) {
        Task t = tasks.get(taskId);
        List<Step> steps = new ArrayList<>();
        for (Step s : t.steps()) {
            steps.add(!s.id().equals(stepId) ? s : new Step(s.id(), s.kind(), s.verb(), s.what(), s.spec(), s.amount(),
                    s.ruleCode(), s.text(), st, by.getUniqueId(), by.getName(), System.currentTimeMillis(), reason));
        }
        Task done = new Task(t.id(), t.target(), t.targetName(), t.createdBy(), t.createdByName(), t.createdAt(),
                t.status() == Status.ELIMINATA ? Status.ELIMINATA : statusOf(steps), List.copyOf(steps));
        tasks.put(done.id(), done);
        return done;
    }

    private static Status statusOf(List<Step> steps) {
        boolean done = false;
        for (Step s : steps) {
            if (s.status() == Status.APERTA) {
                return Status.APERTA;
            }
            if (s.status() == Status.ESEGUITA) {
                done = true;
            }
        }
        return done ? Status.ESEGUITA : Status.RIFIUTATA;
    }

    private static Task withStatus(Task t, Status s) {
        return new Task(t.id(), t.target(), t.targetName(), t.createdBy(), t.createdByName(), t.createdAt(), s, t.steps());
    }

    /** " (task completata)" quando l'ultimo step si e' chiuso. */
    private static String closedNote(Task t) {
        return t.status() == Status.APERTA ? "" : " &7(task chiusa: " + statusLabel(t.status()) + ")";
    }

    private static String summary(List<Step> steps) {
        List<String> parts = new ArrayList<>();
        for (Step s : steps) {
            parts.add(describe(s));
        }
        return String.join(", ", parts);
    }

    /** Chiamare dentro synchronized. Non salva. */
    private void addLog(String taskId, String stepId, String event, CommandSender by, String detail) {
        log.add(new LogEntry(System.currentTimeMillis(), taskId, stepId, event, by instanceof Player p ? p.getUniqueId() : null,
                by == null ? "console" : by.getName(), detail == null ? "" : detail));
        while (log.size() > MAX_LOG) {
            log.remove(0);
        }
    }

    /** Nome italiano della specifica (ball: "ghost"; upgrade: "danno"...; ricompense: "battlepass"...). */
    private static String specLabel(String what, String spec) {
        if (spec == null) {
            return "";
        }
        String s = spec.toLowerCase(Locale.ROOT);
        if (s.equals("all")) {
            return low(what).equals("ball") ? "tutte" : "tutti";
        }
        switch (low(what)) {
            case "ball" -> {
                return s.endsWith("ball") && s.length() > 4 ? s.substring(0, s.length() - 4) : s;
            }
            case "ricompense" -> {
                String l = rewardLabel(s);
                return l == null ? s : l;
            }
            case "upgrade" -> {
                return switch (s) {
                    case "damage" -> "danno";
                    case "cooldown" -> "ricarica";
                    case "speed" -> "velocita'";
                    case "capacity" -> "capienza blockshell";
                    default -> s.startsWith("capacity_") ? "capienza " + specLabel("ball", s.substring("capacity_".length())) : s;
                };
            }
            default -> {
                return s;
            }
        }
    }

    private static String rewardLabel(String id) {
        for (String[] r : REWARDS) {
            if (r[0].equals(id)) {
                return r[1];
            }
        }
        return null;
    }

    private static String kindLabel(Kind k) {
        return k == Kind.CUSTOM ? "custom" : "ordinaria";
    }

    private static String statusLabel(Status s) {
        return switch (s) {
            case APERTA -> "aperta";
            case ESEGUITA -> "eseguita";
            case RIFIUTATA -> "rifiutata";
            case ELIMINATA -> "eliminata";
        };
    }

    /** Id corto univoco della task (8 caratteri esadecimali). Chiamare dentro synchronized. */
    private String newTaskId() {
        while (true) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            if (!tasks.containsKey(id)) {
                return id;
            }
        }
    }

    /** Id corto dello step, univoco fra tutte le task (canExecute trova la task dallo step). Dentro synchronized. */
    private String newStepId(List<Step> pending) {
        while (true) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            boolean used = false;
            for (Step s : pending) {
                if (s.id().equals(id)) {
                    used = true;
                    break;
                }
            }
            for (Task t : tasks.values()) {
                if (used) {
                    break;
                }
                used = stepOf(t, id) != null;
            }
            if (!used) {
                return id;
            }
        }
    }

    private void notify(UUID target, String msg) {
        Player p = target == null ? null : Bukkit.getPlayer(target);
        if (p != null) {
            GuiMsg.send(plugin, p, msg);
        }
    }

    private void movement(UUID target, String text) {
        if (target != null && plugin.getMovements() != null) {
            plugin.getMovements().record(target, MovementLog.Type.STAFF, text);
        }
    }

    private void log(String text) {
        plugin.getLogger().info("Task staff: " + text);
    }

    private static String low(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String playerName(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
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
