package it.blockmania;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Task dello staff v4 (GUI TaskGui, /adminmania task, frasi capite da TaskParser). Una task e' una CARTELLA con id
 * numerico (#1, #2...), una MOTIVAZIONE e piu' ORDINI. Un ordine e' semplice (un ramo con le azioni), condizionale
 * (un ramo per condizione + "non fare nulla" in coda) o custom (testo da fare a mano).
 * Uno staffer PRENDE IN CARICO un ordine (un ramo) per 5 minuti: una bossbar gli mostra cosa fare e quanto manca.
 * Le azioni le fa lui a mano con lo userprofile o /adminmania: sotto admin le puo' fare SOLO se sono nel suo incarico
 * (allows), e ogni azione fatta si spunta (done). Ramo completo = ordine completato. Se scadono i 5 minuti (anche da
 * offline) l'incarico si annulla: le azioni gia' fatte si rimettono come prima (subito o al prossimo login del player)
 * e l'ordine torna libero. Un incarico per persona. Admin+ e OP restano liberi di dare/togliere.
 * Tutto finisce nel resoconto. Si salva in staff-tasks.yml (version 4; il formato 3 si converte, i piu' vecchi -> .old).
 * Da usare dal thread principale.
 */
public final class StaffTasks implements Listener {

    /** Per task e ordini (RIFIUTATA resta solo per i dati vecchi; ELIMINATA solo per le task). */
    public enum Status { APERTA, ESEGUITA, RIFIUTATA, ELIMINATA }

    public enum OrderType { SEMPLICE, CONDIZIONALE, CUSTOM }

    /**
     * verb = give|remove|set|reset|ban|warn|mute ; what = ball|ricompense|coin|blockcoin|blockshell|mine|upgrade|power|stamina
     * (null per le sanzioni) ; spec = ball / ricompensa / potenziamento ("all") o null ; ruleCode per le sanzioni.
     * targetUid = id utente nascosto (PlayerIds): si mostra solo targetName. before = valore di prima (interno, annullamento).
     */
    public record Action(String verb, String what, String spec, long amount, String ruleCode, UUID target, String targetName,
                         String targetUid, boolean done, UUID doneBy, long doneAt, String before) {
    }

    /** condition null = ordine semplice o "non fare nulla" finale ; nothing = completa l'ordine senza fare niente. */
    public record Branch(String condition, List<Action> actions, boolean nothing) {
    }

    /** chosenBranch = ramo scelto da chi l'ha completato (-1 = nessuno). */
    public record Order(String id, String text, List<Branch> branches, Status status, UUID doneBy, String doneByName, long doneAt,
                        int chosenBranch) {
    }

    /** id = numero progressivo come testo ("1", "2"...: la GUI mostra "#" + id). reason = motivazione. */
    /** Modalita' test (solo OP): permette di prendere in carico le proprie task. config: tasks.modalita-test */
    public boolean testMode(Player p) {
        return p != null && p.isOp() && plugin.getConfig().getBoolean("tasks.modalita-test", false);
    }

    public boolean toggleTestMode() {
        boolean v = !plugin.getConfig().getBoolean("tasks.modalita-test", false);
        plugin.getConfig().set("tasks.modalita-test", v);
        plugin.saveConfig();
        return v;
    }

    public record Task(String id, UUID createdBy, String createdByName, long createdAt, Status status, String reason,
                       List<Order> orders) {
    }

    /** Incarico: lo staffer ha preso il ramo branch dell'ordine; scade a expiresAt (ms epoch). */
    public record Charge(UUID staff, String taskId, String orderId, int branch, long expiresAt) {
    }

    /** Conteggi della cartella (ordini): done = completati. */
    public record Counts(int total, int simple, int conditional, int custom, int done, int doneSimple, int doneConditional,
                         int doneCustom) {
    }

    /** event: CREATA PRESA AZIONE COMPLETATA ANNULLATA TOLTO ELIMINATA RIPRISTINATA ; orderId null per gli eventi della task. */
    public record LogEntry(long ts, String taskId, String orderId, String event, UUID by, String byName, String detail) {
    }

    /** Specifica selezionabile (id interno, nome italiano). */
    public record Spec(String id, String label) {
    }

    /** Annullamento da fare al prossimo login del player (era offline allo scadere). */
    private record Undo(UUID target, String what, String spec, String before) {
    }

    /**
     * Modifica per un player OFFLINE (i dati sono nel player: si applica al suo prossimo login). link = azione d'incarico
     * spuntata con questa modifica ("task|ordine|ramo|indice"), "" se nessuna.
     */
    private record Pending(String id, UUID target, String verb, String what, String spec, long amount, UUID by, String byName,
                           long ts, String link) {
    }

    public static final long CHARGE_MS = 5 * 60 * 1000L;
    public static final String EXPIRED = "annullato per tempo scaduto";

    private static final int VERSION = 4;
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
    private final Map<UUID, Charge> charges = new LinkedHashMap<>();
    private final List<Undo> undos = new ArrayList<>();
    private final List<Pending> offline = new ArrayList<>();
    private final Map<UUID, BossBar> bars = new HashMap<>();
    /** Valore di prima preso da allows() per l'azione che lo staffer sta per fare: staff -> {indice azione, valore}. */
    private final Map<UUID, Object[]> pendingBefore = new HashMap<>();
    private long nextId = 1;
    private BukkitTask timer;
    private TaskParser parser;

    public StaffTasks(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "staff-tasks.yml");
    }

    /** Parser delle frasi (task-words.yml), creato alla prima richiesta. */
    public synchronized TaskParser parser() {
        if (parser == null) {
            parser = new TaskParser(plugin);
        }
        return parser;
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

    /** {min, max} per askNumber; {0, 0} se non serve un valore. */
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
            case "mine" -> "miniere";
            case "upgrade" -> "potenziamenti";
            case "power" -> "poteri";
            default -> low(what);
        };
    }

    /** Nome italiano della specifica (ball "ghost", upgrade "danno", ricompense "battlepass", "tutte"/"tutti"). */
    public static String labelSpec(String what, String spec) {
        return specLabel(what, spec);
    }

    /** Riga leggibile col player: "dai 3000 blockcoin a DoppiaX", "setta miniera 4 a DoppiaX", "warna crack (regola 1.2)". */
    public static String describe(Action a) {
        if (a == null) {
            return "";
        }
        String v = low(a.verb());
        String who = a.targetName() == null || a.targetName().isEmpty() ? "?" : a.targetName();
        if (isSanction(v)) {
            return label(v) + " " + who + " (regola " + (a.ruleCode() == null ? "?" : a.ruleCode()) + ")";
        }
        String w = low(a.what());
        String spec = a.spec() == null || a.spec().isEmpty() ? "" : " " + specLabel(w, a.spec());
        if (v.equals("reset")) {
            return label(v) + " " + labelWhat(w) + spec + " a " + who;
        }
        String amount = w.equals("stamina") ? a.amount() + "%" : String.valueOf(a.amount());
        return switch (w) {
            case "ricompense", "upgrade" -> label(v) + " " + (v.equals("set") ? "livello " + amount : amount + " livelli") + spec
                    + " (" + labelWhat(w) + ")";
            case "mine" -> v.equals("set") ? "setta miniera " + amount : label(v) + " " + amount + (a.amount() == 1 ? " miniera" : " miniere");
            case "stamina" -> label(v) + " " + amount + " stamina";
            default -> label(v) + " " + amount + " " + labelWhat(w) + spec;
        } + " a " + who;
    }

    /** "se ha la miniera 5: togli 1 miniera a DoppiaX, dai 3000 blockcoin a DoppiaX" / "non fare nulla". */
    public static String describe(Branch b) {
        if (b == null) {
            return "";
        }
        String cond = b.condition() == null || b.condition().isEmpty() ? "" : "se " + b.condition() + ": ";
        if (b.nothing()) {
            return cond + "non fare nulla";
        }
        if (b.actions().isEmpty()) {
            return cond + "da fare a mano";
        }
        List<String> l = new ArrayList<>();
        for (Action a : b.actions()) {
            l.add(describe(a));
        }
        return cond + String.join(", ", l);
    }

    public static OrderType type(Order o) {
        if (o == null || o.branches().isEmpty()) {
            return OrderType.CUSTOM;
        }
        if (o.branches().size() == 1) {
            Branch b = o.branches().get(0);
            return b.actions().isEmpty() && !b.nothing() ? OrderType.CUSTOM : OrderType.SEMPLICE;
        }
        return OrderType.CONDIZIONALE;
    }

    /** Nuova azione (non fatta). Normalizza i campi che non servono. */
    public static Action newAction(String verb, String what, String spec, long amount, String ruleCode, UUID target,
                                   String targetName) {
        String v = low(verb);
        boolean sanction = isSanction(v);
        String w = sanction ? null : low(what);
        String sp = sanction || spec == null || spec.isBlank() ? null : spec.trim().toLowerCase(Locale.ROOT);
        long a = sanction || v.equals("reset") ? 0 : amount;
        String rc = sanction && ruleCode != null ? ruleCode.trim() : null;
        return new Action(v, w, sp, a, rc, target, targetName == null ? "" : targetName, "", false, null, 0L, "");
    }

    public static Branch newBranch(String condition, List<Action> actions, boolean nothing) {
        String c = condition == null || condition.isBlank() ? null : condition.trim();
        return new Branch(c, nothing || actions == null ? List.of() : List.copyOf(actions), nothing);
    }

    /** Nuovo ordine APERTO (id provvisorio; create assegna quello vero). */
    public static Order newOrder(String text, List<Branch> branches) {
        return new Order("0", text == null ? "" : text.trim(), branches == null ? List.of() : List.copyOf(branches),
                Status.APERTA, null, "", 0L, -1);
    }

    // ------------------------------------------------------------------ persistenza

    public synchronized void load() {
        tasks.clear();
        log.clear();
        charges.clear();
        undos.clear();
        offline.clear();
        nextId = 1;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        int version = y.getInt("version", 1);
        if (version != VERSION && version != 3) {
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
        boolean v3 = version == 3;
        long max = 0;
        for (Map<?, ?> m : y.getMapList("tasks")) {
            try {
                Object id = m.get("id");
                Object status = m.get("status");
                if (id == null || status == null) {
                    continue;
                }
                List<Order> orders = new ArrayList<>();
                Object raw = m.get(v3 ? "steps" : "orders");
                if (raw instanceof List<?> list) {
                    int n = 1;
                    for (Object o : list) {
                        if (o instanceof Map<?, ?> om) {
                            Order ord = v3 ? fromV3Step(om, String.valueOf(n)) : readOrder(om);
                            if (ord != null) {
                                orders.add(ord);
                                n++;
                            }
                        }
                    }
                }
                Task t = new Task(String.valueOf(id), uuid(str(m.get("createdBy"))), str(m.get("createdByName")),
                        num(m.get("createdAt")), Status.valueOf(String.valueOf(status)), str(m.get("reason")), List.copyOf(orders));
                tasks.put(t.id(), t);
                max = Math.max(max, num(t.id()));
            } catch (IllegalArgumentException ignored) {
                // voce non valida: saltata
            }
        }
        nextId = Math.max(max + 1, y.getLong("next-id", 1));
        for (Map<?, ?> m : y.getMapList("log")) {
            Object tid = m.get("task");
            Object ev = m.get("event");
            if (tid == null || ev == null) {
                continue;
            }
            String oid = str(m.get(v3 ? "step" : "order"));
            log.add(new LogEntry(num(m.get("ts")), String.valueOf(tid), oid.isEmpty() || v3 ? null : oid, String.valueOf(ev),
                    uuid(str(m.get("by"))), str(m.get("byName")), str(m.get("detail"))));
        }
        for (Map<?, ?> m : y.getMapList("charges")) {
            UUID staff = uuid(str(m.get("staff")));
            if (staff != null) {
                charges.put(staff, new Charge(staff, str(m.get("task")), str(m.get("order")), (int) num(m.get("branch")),
                        num(m.get("expiresAt"))));
            }
        }
        for (Map<?, ?> m : y.getMapList("offline")) {
            UUID target = uuid(str(m.get("target")));
            if (target != null) {
                offline.add(new Pending(str(m.get("id")), target, str(m.get("verb")), str(m.get("what")),
                        str(m.get("spec")).isEmpty() ? null : str(m.get("spec")), num(m.get("amount")), uuid(str(m.get("by"))),
                        str(m.get("byName")), num(m.get("ts")), str(m.get("link"))));
            }
        }
        for (Map<?, ?> m : y.getMapList("undo")) {
            UUID target = uuid(str(m.get("target")));
            if (target != null) {
                undos.add(new Undo(target, str(m.get("what")), str(m.get("spec")), str(m.get("before"))));
            }
        }
        if (v3) {
            plugin.getLogger().info("staff-tasks.yml: convertito dal formato 3 (" + tasks.size() + " task, ogni step = un ordine)");
            save();
        }
    }

    /** v3: uno step diventa un ordine semplice (o custom se non aveva azione). */
    private static Order fromV3Step(Map<?, ?> m, String id) {
        try {
            String verb = str(m.get("verb"));
            Status st = Status.valueOf(str(m.get("status")));
            UUID target = uuid(str(m.get("target")));
            String tn = str(m.get("targetName"));
            boolean done = st == Status.ESEGUITA;
            UUID doneBy = uuid(str(m.get("doneBy")));
            long doneAt = num(m.get("doneAt"));
            List<Branch> br = new ArrayList<>();
            String text;
            if (verb.isEmpty()) {
                text = str(m.get("text"));
                br.add(new Branch(null, List.of(), false));
            } else {
                String what = str(m.get("what"));
                String spec = str(m.get("spec"));
                String rule = str(m.get("rule"));
                Action a = new Action(verb, what.isEmpty() ? null : what, spec.isEmpty() ? null : spec, num(m.get("amount")),
                        rule.isEmpty() ? null : rule, target, tn, str(m.get("targetUid")), done, done ? doneBy : null,
                        done ? doneAt : 0L, "");
                text = str(m.get("text")).isEmpty() ? describe(a) : str(m.get("text"));
                br.add(new Branch(null, List.of(a), false));
            }
            return new Order(id, text, List.copyOf(br), st, doneBy, str(m.get("doneByName")), doneAt, done ? 0 : -1);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static Order readOrder(Map<?, ?> m) {
        try {
            List<Branch> branches = new ArrayList<>();
            Object raw = m.get("branches");
            if (raw instanceof List<?> list) {
                for (Object o : list) {
                    if (!(o instanceof Map<?, ?> bm)) {
                        continue;
                    }
                    List<Action> actions = new ArrayList<>();
                    Object ra = bm.get("actions");
                    if (ra instanceof List<?> al) {
                        for (Object x : al) {
                            if (x instanceof Map<?, ?> am) {
                                String what = str(am.get("what"));
                                String spec = str(am.get("spec"));
                                String rule = str(am.get("rule"));
                                actions.add(new Action(str(am.get("verb")), what.isEmpty() ? null : what,
                                        spec.isEmpty() ? null : spec, num(am.get("amount")), rule.isEmpty() ? null : rule,
                                        uuid(str(am.get("target"))), str(am.get("targetName")), str(am.get("targetUid")),
                                        Boolean.TRUE.equals(am.get("done")), uuid(str(am.get("doneBy"))), num(am.get("doneAt")),
                                        str(am.get("before"))));
                            }
                        }
                    }
                    String cond = str(bm.get("condition"));
                    branches.add(new Branch(cond.isEmpty() ? null : cond, List.copyOf(actions),
                            Boolean.TRUE.equals(bm.get("nothing"))));
                }
            }
            return new Order(str(m.get("id")), str(m.get("text")), List.copyOf(branches), Status.valueOf(str(m.get("status"))),
                    uuid(str(m.get("doneBy"))), str(m.get("doneByName")), num(m.get("doneAt")), (int) num(m.get("chosen")));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("version", VERSION);
        y.set("next-id", nextId);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("createdBy", t.createdBy() == null ? "" : t.createdBy().toString());
            m.put("createdByName", nz(t.createdByName()));
            m.put("createdAt", t.createdAt());
            m.put("status", t.status().name());
            m.put("reason", nz(t.reason()));
            List<Map<String, Object>> orders = new ArrayList<>();
            for (Order o : t.orders()) {
                Map<String, Object> om = new LinkedHashMap<>();
                om.put("id", o.id());
                om.put("text", nz(o.text()));
                om.put("status", o.status().name());
                om.put("doneBy", o.doneBy() == null ? "" : o.doneBy().toString());
                om.put("doneByName", nz(o.doneByName()));
                om.put("doneAt", o.doneAt());
                om.put("chosen", o.chosenBranch());
                List<Map<String, Object>> branches = new ArrayList<>();
                for (Branch b : o.branches()) {
                    Map<String, Object> bm = new LinkedHashMap<>();
                    bm.put("condition", nz(b.condition()));
                    bm.put("nothing", b.nothing());
                    List<Map<String, Object>> actions = new ArrayList<>();
                    for (Action a : b.actions()) {
                        Map<String, Object> am = new LinkedHashMap<>();
                        am.put("verb", nz(a.verb()));
                        am.put("what", nz(a.what()));
                        am.put("spec", nz(a.spec()));
                        am.put("amount", a.amount());
                        am.put("rule", nz(a.ruleCode()));
                        am.put("target", a.target() == null ? "" : a.target().toString());
                        am.put("targetName", nz(a.targetName()));
                        am.put("targetUid", nz(a.targetUid()));
                        am.put("done", a.done());
                        am.put("doneBy", a.doneBy() == null ? "" : a.doneBy().toString());
                        am.put("doneAt", a.doneAt());
                        am.put("before", nz(a.before()));
                        actions.add(am);
                    }
                    bm.put("actions", actions);
                    branches.add(bm);
                }
                om.put("branches", branches);
                orders.add(om);
            }
            m.put("orders", orders);
            out.add(m);
        }
        y.set("tasks", out);
        List<Map<String, Object>> lo = new ArrayList<>();
        for (LogEntry e : log) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", e.ts());
            m.put("task", e.taskId());
            m.put("order", nz(e.orderId()));
            m.put("event", e.event());
            m.put("by", e.by() == null ? "" : e.by().toString());
            m.put("byName", nz(e.byName()));
            m.put("detail", nz(e.detail()));
            lo.add(m);
        }
        y.set("log", lo);
        List<Map<String, Object>> ch = new ArrayList<>();
        for (Charge c : charges.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("staff", c.staff().toString());
            m.put("task", c.taskId());
            m.put("order", c.orderId());
            m.put("branch", c.branch());
            m.put("expiresAt", c.expiresAt());
            ch.add(m);
        }
        y.set("charges", ch);
        List<Map<String, Object>> un = new ArrayList<>();
        for (Undo u : undos) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("target", u.target().toString());
            m.put("what", u.what());
            m.put("spec", nz(u.spec()));
            m.put("before", u.before());
            un.add(m);
        }
        y.set("undo", un);
        List<Map<String, Object>> off = new ArrayList<>();
        for (Pending pe : offline) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", pe.id());
            m.put("target", pe.target().toString());
            m.put("verb", pe.verb());
            m.put("what", pe.what());
            m.put("spec", nz(pe.spec()));
            m.put("amount", pe.amount());
            m.put("by", pe.by() == null ? "" : pe.by().toString());
            m.put("byName", nz(pe.byName()));
            m.put("ts", pe.ts());
            m.put("link", nz(pe.link()));
            off.add(m);
        }
        y.set("offline", off);
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("staff-tasks.yml: " + ex.getMessage());
        }
    }

    /** Avvia il controllo degli incarichi (ogni secondo: bossbar e scadenze, anche per chi e' offline). */
    public void start() {
        if (timer == null) {
            timer = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
                tick();
            }, 20L, 20L);
        }
    }

    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        for (UUID id : new ArrayList<>(bars.keySet())) {
            hideBar(id);
        }
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

    /** Accetta "12" o "#12". */
    public synchronized Task get(String id) {
        if (id == null) {
            return null;
        }
        String k = id.trim();
        return tasks.get(k.startsWith("#") ? k.substring(1) : k);
    }

    /** Conteggi della cartella per tipo di ordine. */
    public static Counts counts(Task t) {
        int[] c = new int[8];
        if (t != null) {
            for (Order o : t.orders()) {
                OrderType ty = type(o);
                boolean done = o.status() == Status.ESEGUITA || o.status() == Status.RIFIUTATA;
                int k = ty == OrderType.SEMPLICE ? 1 : ty == OrderType.CONDIZIONALE ? 2 : 3;
                c[0]++;
                c[k]++;
                if (done) {
                    c[4]++;
                    c[4 + k]++;
                }
            }
        }
        return new Counts(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7]);
    }

    /**
     * Resoconto, le voci piu' recenti prima. onlyForOrNull = tutte le voci delle task create da lui piu' le voci fatte da lui.
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

    public synchronized Charge chargeOf(UUID staff) {
        return staff == null ? null : charges.get(staff);
    }

    /** Chi ha in carico l'ordine (null = libero). */
    public synchronized Charge chargeOn(String taskId, String orderId) {
        Task t = get(taskId);
        if (t == null) {
            return null;
        }
        for (Charge c : charges.values()) {
            if (c.taskId().equals(t.id()) && c.orderId().equals(orderId)) {
                return c;
            }
        }
        return null;
    }

    /** Prossima azione da fare dell'incarico (null = nessuna: custom o tutte fatte). */
    public synchronized Action nextAction(Charge c) {
        Branch b = branchOf(c);
        if (b == null) {
            return null;
        }
        for (Action a : b.actions()) {
            if (!a.done()) {
                return a;
            }
        }
        return null;
    }

    public static long remainingMs(Charge c) {
        return c == null ? 0 : Math.max(0, c.expiresAt() - System.currentTimeMillis());
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

    public boolean isAdmin(Player p) {
        return p != null && !suspended(p) && rank(p) >= ADMIN;
    }

    /** Puo' eliminare la task: chi l'ha creata o admin+, non sospeso. */
    public boolean canDelete(Player p, Task t) {
        return p != null && t != null && !suspended(p) && (p.getUniqueId().equals(t.createdBy()) || isAdmin(p));
    }

    /** null = p puo' prendere in carico l'ordine; altrimenti il motivo. */
    public synchronized String canTake(Player p, Task t, Order o) {
        if (p == null || t == null || o == null) {
            return "ordine non trovato";
        }
        if (t.status() == Status.ELIMINATA) {
            return "la task e' nel cestino";
        }
        if (o.status() != Status.APERTA) {
            return "ordine gia' completato";
        }
        if (suspended(p)) {
            return "sei sospeso dallo staff";
        }
        if (p.getUniqueId().equals(t.createdBy()) && !testMode(p)) {
            return "non puoi prendere in carico gli ordini di una task creata da te";
        }
        Charge on = chargeOn(t.id(), o.id());
        if (on != null) {
            return on.staff().equals(p.getUniqueId()) ? "ce l'hai gia' in carico" : "gia' in carico a " + playerName(on.staff());
        }
        Charge mine = charges.get(p.getUniqueId());
        if (mine != null) {
            return "hai gia' un incarico: finiscilo prima di prenderne un altro";
        }
        if (rank(p) < (type(o) == OrderType.CUSTOM ? SR_MOD : MOD)) {
            return "il tuo grado non puo' prendere questo ordine";
        }
        if (!isAdmin(p)) {
            for (Branch b : o.branches()) {
                for (Action a : b.actions()) {
                    if (p.getUniqueId().equals(a.target())) {
                        return "col tuo grado non puoi fare azioni su te stesso";
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ creazione ed eliminazione

    /** Crea la cartella; null se non puo' crearla, manca la motivazione, la lista e' vuota o un ordine non e' valido. */
    public Task create(Player by, List<Order> orders, String reason) {
        String why = reason == null ? "" : reason.trim();
        if (by == null || orders == null || orders.isEmpty() || why.isEmpty() || !canCreate(by)) {
            return null;
        }
        for (Order o : orders) {
            if (whyInvalid(by, o) != null) {
                return null;
            }
        }
        Task t;
        synchronized (this) {
            List<Order> clean = new ArrayList<>();
            int n = 1;
            for (Order o : orders) {
                clean.add(cleanOrder(o, String.valueOf(n++)));
            }
            t = new Task(newTaskId(), by.getUniqueId(), by.getName(), System.currentTimeMillis(), Status.APERTA, why,
                    List.copyOf(clean));
            tasks.put(t.id(), t);
            addLog(t.id(), null, "CREATA", by, clean.size() + (clean.size() == 1 ? " ordine" : " ordini") + " | motivazione: " + why);
            save();
        }
        movement(by.getUniqueId(), "Ha creato la task staff #" + t.id() + " (" + t.orders().size() + " ordini): " + why);
        log("task #" + t.id() + " creata da " + by.getName() + ": " + why);
        return t;
    }

    /** Dopo il salvataggio una task non accetta piu' ordini nuovi (scelta dell'utente): rifiuta sempre. */
    public String addOrders(Player by, String taskId, List<Order> orders) {
        return "dopo il salvataggio non si possono aggiungere ordini: crea una nuova task";
    }

    /**
     * Toglie un ordine non completato (creatore o admin+). Se qualcuno lo ha in carico l'incarico si annulla (azioni fatte
     * rimesse come prima). Se era l'ultimo ordine la task va nel cestino. null = ok, altrimenti il motivo.
     */
    public String removeOrder(Player by, String taskId, String orderId) {
        Task t = get(taskId);
        Order o = orderOf(t, orderId);
        if (t == null || o == null) {
            return "ordine non trovato";
        }
        if (t.status() == Status.ELIMINATA) {
            return "la task e' nel cestino";
        }
        if (!canDelete(by, t)) {
            return "solo chi ha creato la task o un admin puo' togliere ordini";
        }
        if (o.status() != Status.APERTA) {
            return "l'ordine e' gia' completato";
        }
        Charge c = chargeOn(t.id(), o.id());
        if (c != null) {
            release(c.staff(), "ordine tolto dalla task");
        }
        boolean last;
        synchronized (this) {
            Task cur = tasks.get(t.id());
            Order now = orderOf(cur, orderId);
            if (now == null) {
                return "ordine non trovato";
            }
            List<Order> rest = new ArrayList<>(cur.orders());
            rest.remove(now);
            last = rest.isEmpty();
            if (last) {
                tasks.put(cur.id(), withStatus(cur, Status.ELIMINATA));
            } else {
                tasks.put(cur.id(), new Task(cur.id(), cur.createdBy(), cur.createdByName(), cur.createdAt(), statusOf(rest),
                        cur.reason(), List.copyOf(rest)));
            }
            addLog(cur.id(), orderId, "TOLTO", by, shortText(now.text()) + (last ? " (ultimo ordine: task nel cestino)" : ""));
            save();
        }
        movement(by.getUniqueId(), "Ha tolto l'ordine " + orderId + " dalla task staff #" + t.id());
        log("task #" + t.id() + " ordine " + orderId + " tolto da " + by.getName() + (last ? " (task nel cestino)" : ""));
        return null;
    }

    /** Ordine pulito con l'id definitivo (azioni non fatte, nomi aggiornati, id utente nascosto). Dentro synchronized. */
    private Order cleanOrder(Order o, String id) {
        List<Branch> br = new ArrayList<>();
        for (Branch b : o.branches()) {
            List<Action> acts = new ArrayList<>();
            for (Action a : b.actions()) {
                Action x = newAction(a.verb(), a.what(), a.spec(), a.amount(), a.ruleCode(), a.target(),
                        a.target() == null ? a.targetName() : playerName(a.target()));
                acts.add(new Action(x.verb(), x.what(), x.spec(), x.amount(), x.ruleCode(), x.target(), x.targetName(),
                        userId(x.target()), false, null, 0L, ""));
            }
            br.add(newBranch(b.condition(), acts, b.nothing()));
        }
        return new Order(id, o.text() == null ? "" : o.text().trim(), List.copyOf(br), Status.APERTA, null, "", 0L, -1);
    }

    /** Perche' l'ordine non si puo' salvare (per l'anteprima), o null se va bene. */
    public String whyInvalid(Player by, Order o) {
        if (o == null || o.branches().isEmpty()) {
            return "ordine vuoto";
        }
        OrderType ty = type(o);
        if (ty == OrderType.CUSTOM && (o.text() == null || o.text().isBlank())) {
            return "manca il testo";
        }
        for (Branch b : o.branches()) {
            if (!b.nothing() && b.actions().isEmpty() && ty != OrderType.CUSTOM) {
                return "un ramo non ha azioni" + (b.condition() == null ? "" : " (se " + b.condition() + ")");
            }
            for (Action a : b.actions()) {
                String err = invalid(a);
                if (err != null) {
                    return err;
                }
                if (by != null && by.getUniqueId().equals(a.target()) && !isAdmin(by)) {
                    return "col tuo grado non puoi creare azioni su te stesso";
                }
            }
        }
        return null;
    }

    /** Creatore o admin+: manda la task nel cestino (gli incarichi su quella task si annullano). */
    public boolean delete(Player by, String taskId) {
        Task t = get(taskId);
        if (t == null || t.status() == Status.ELIMINATA || !canDelete(by, t)) {
            return false;
        }
        List<UUID> held = new ArrayList<>();
        synchronized (this) {
            for (Charge c : charges.values()) {
                if (c.taskId().equals(t.id())) {
                    held.add(c.staff());
                }
            }
        }
        for (UUID s : held) {
            release(s, "task eliminata");
        }
        synchronized (this) {
            Task now = tasks.get(t.id());
            tasks.put(t.id(), withStatus(now, Status.ELIMINATA));
            addLog(t.id(), null, "ELIMINATA", by, "era " + statusLabel(now.status()));
            save();
        }
        movement(by.getUniqueId(), "Ha eliminato la task staff #" + t.id() + " di " + t.createdByName());
        log("task #" + t.id() + " eliminata da " + by.getName());
        return true;
    }

    /** Admin: rimette la task dal cestino con lo stato calcolato dagli ordini. */
    public boolean restore(Player admin, String taskId) {
        if (!isAdmin(admin)) {
            return false;
        }
        Task t;
        Status back;
        synchronized (this) {
            t = get(taskId);
            if (t == null || t.status() != Status.ELIMINATA) {
                return false;
            }
            back = statusOf(t.orders());
            tasks.put(t.id(), withStatus(t, back));
            addLog(t.id(), null, "RIPRISTINATA", admin, "torna " + statusLabel(back));
            save();
        }
        movement(admin.getUniqueId(), "Ha ripristinato la task staff #" + t.id() + " di " + t.createdByName());
        log("task #" + t.id() + " ripristinata da " + admin.getName() + " (" + statusLabel(back) + ")");
        return true;
    }

    // ------------------------------------------------------------------ incarichi

    /** Prende in carico il ramo dell'ordine. Ramo "non fare nulla" = ordine completato subito. null = ok. */
    public String take(Player p, String taskId, String orderId, int branch) {
        Task t = get(taskId);
        Order o = orderOf(t, orderId);
        String why = canTake(p, t, o);
        if (why != null) {
            return why;
        }
        if (branch < 0 || branch >= o.branches().size()) {
            return "ramo non valido";
        }
        Branch b = o.branches().get(branch);
        if (b.nothing()) {
            complete(p, t.id(), o.id(), branch, "non fare nulla");
            return null;
        }
        Charge c = new Charge(p.getUniqueId(), t.id(), o.id(), branch, System.currentTimeMillis() + CHARGE_MS);
        synchronized (this) {
            charges.put(p.getUniqueId(), c);
            addLog(t.id(), o.id(), "PRESA", p, describe(b));
            save();
        }
        movement(p.getUniqueId(), "Ha preso in carico l'ordine " + o.id() + " della task staff #" + t.id() + ": " + describe(b));
        log("task #" + t.id() + " ordine " + o.id() + " preso da " + p.getName() + " (ramo " + branch + ")");
        GuiMsg.send(plugin, p, "&aincarico preso");
        showBar(p);
        return null;
    }

    /** "fatto" per l'ordine custom in carico. null = ok. */
    public String finishCustom(Player p) {
        Charge c = p == null ? null : chargeOf(p.getUniqueId());
        if (c == null) {
            return "non hai incarichi";
        }
        Order o = orderOf(get(c.taskId()), c.orderId());
        if (o == null || type(o) != OrderType.CUSTOM) {
            return "il tuo incarico non e' un ordine da fare a mano";
        }
        complete(p, c.taskId(), c.orderId(), c.branch(), "fatto a mano");
        return null;
    }

    /** Annulla l'incarico: le azioni gia' fatte si rimettono come prima e l'ordine torna libero. */
    public void release(UUID staff, String why) {
        Charge c;
        List<Action> undoNow = new ArrayList<>();
        synchronized (this) {
            c = charges.remove(staff);
            pendingBefore.remove(staff);
            if (c == null) {
                return;
            }
            Task t = tasks.get(c.taskId());
            Order o = orderOf(t, c.orderId());
            if (t != null && o != null && c.branch() >= 0 && c.branch() < o.branches().size()) {
                Branch b = o.branches().get(c.branch());
                List<Action> fresh = new ArrayList<>();
                for (int i = 0; i < b.actions().size(); i++) {
                    Action a = b.actions().get(i);
                    if (a.done() && !dropLinked(c.taskId() + "|" + c.orderId() + "|" + c.branch() + "|" + i)) {
                        undoNow.add(a);
                    }
                    fresh.add(new Action(a.verb(), a.what(), a.spec(), a.amount(), a.ruleCode(), a.target(), a.targetName(),
                            a.targetUid(), false, null, 0L, ""));
                }
                replaceBranch(t, o, c.branch(), new Branch(b.condition(), List.copyOf(fresh), b.nothing()));
            }
            addLog(c.taskId(), c.orderId(), "ANNULLATA", null, why + (undoNow.isEmpty() ? "" : " (" + undoNow.size()
                    + " azioni rimesse come prima)"));
        }
        for (Action a : undoNow) {
            restoreBefore(a);
        }
        synchronized (this) {
            save();
        }
        hideBar(staff);
        Player p = Bukkit.getPlayer(staff);
        if (p != null) {
            GuiMsg.send(plugin, p, "&cincarico annullato: &7" + why);
        }
        movement(staff, "Incarico della task staff #" + c.taskId() + " annullato: " + why);
        log("incarico di " + playerName(staff) + " (task #" + c.taskId() + " ordine " + c.orderId() + ") annullato: " + why);
    }

    // ------------------------------------------------------------------ blocco delle azioni a mano

    /**
     * Lo staffer puo' fare questa azione (userprofile, /adminmania)? Admin+ e OP sempre; sotto admin solo se e' un'azione
     * non ancora fatta del suo incarico (stesso verbo, cosa, specifica, player e quantita'). Le sanzioni non si bloccano.
     * Se nega avvisa lo staffer. Se combacia ricorda il valore di prima (per l'annullamento).
     */
    public boolean allows(Player staff, String verb, String what, String spec, UUID target, long amount) {
        if (staff == null) {
            return true;
        }
        String v = low(verb);
        if (isSanction(v)) {
            return true;
        }
        int idx = matching(staff.getUniqueId(), v, what, spec, target, amount);
        if (idx >= 0) {
            Player tp = target == null ? null : Bukkit.getPlayer(target);
            synchronized (this) {
                pendingBefore.put(staff.getUniqueId(), new Object[]{idx, tp == null ? "" : read(tp, low(what), spec)});
            }
            return true;
        }
        if (staff.isOp() || isAdmin(staff)) {
            return true;
        }
        GuiMsg.send(plugin, staff, "&cnon hai questa azione in carico");
        plugin.playConfiguredSound(staff.getLocation(), "message-sounds.error");
        return false;
    }

    /** Dopo un'azione riuscita: se e' nel suo incarico la spunta; ramo finito = ordine completato. */
    public void done(Player staff, String verb, String what, String spec, UUID target, long amount) {
        if (staff == null) {
            return;
        }
        String v = low(verb);
        int idx = matching(staff.getUniqueId(), v, what, spec, target, amount);
        if (idx < 0) {
            return;
        }
        Charge c;
        boolean finished;
        Action did;
        synchronized (this) {
            c = charges.get(staff.getUniqueId());
            Task t = c == null ? null : tasks.get(c.taskId());
            Order o = orderOf(t, c == null ? null : c.orderId());
            if (o == null) {
                return;
            }
            Branch b = o.branches().get(c.branch());
            Object[] pb = pendingBefore.remove(staff.getUniqueId());
            String before = pb != null && ((Integer) pb[0]) == idx ? (String) pb[1] : "";
            Player tp = target == null ? null : Bukkit.getPlayer(target);
            if (!isSanction(v) && tp != null && !before.isEmpty() && (v.equals("give") || v.equals("remove"))
                    && before.equals(read(tp, low(what), spec))) {
                return; // non e' cambiato niente: l'azione non e' riuscita
            }
            List<Action> acts = new ArrayList<>(b.actions());
            Action a = acts.get(idx);
            did = new Action(a.verb(), a.what(), a.spec(), a.amount(), a.ruleCode(), a.target(), a.targetName(), a.targetUid(),
                    true, staff.getUniqueId(), System.currentTimeMillis(), before);
            acts.set(idx, did);
            replaceBranch(t, o, c.branch(), new Branch(b.condition(), List.copyOf(acts), b.nothing()));
            finished = true;
            for (Action x : acts) {
                finished &= x.done();
            }
            if (tp == null && !isSanction(v)) {
                // player offline: la modifica e' in coda; la collego all'azione per poterla annullare
                for (int k = offline.size() - 1; k >= 0; k--) {
                    Pending pe = offline.get(k);
                    if (pe.target().equals(target) && pe.link().isEmpty() && staff.getUniqueId().equals(pe.by())
                            && pe.verb().equals(v) && pe.what().equals(low(what))) {
                        offline.set(k, new Pending(pe.id(), pe.target(), pe.verb(), pe.what(), pe.spec(), pe.amount(), pe.by(),
                                pe.byName(), pe.ts(), c.taskId() + "|" + c.orderId() + "|" + c.branch() + "|" + idx));
                        break;
                    }
                }
            }
            addLog(c.taskId(), c.orderId(), "AZIONE", staff, describe(did));
            save();
        }
        movement(staff.getUniqueId(), "Task staff #" + c.taskId() + ": " + describe(did));
        if (finished) {
            complete(staff, c.taskId(), c.orderId(), c.branch(), describe(branchOf(c)));
        } else {
            showBar(staff);
        }
    }

    /** Indice dell'azione non fatta dell'incarico che combacia, -1 se nessuna. */
    private synchronized int matching(UUID staff, String verb, String what, String spec, UUID target, long amount) {
        Branch b = branchOf(charges.get(staff));
        if (b == null || target == null) {
            return -1;
        }
        String w = low(what);
        String sp = spec == null || spec.isBlank() ? "" : spec.trim().toLowerCase(Locale.ROOT);
        for (int i = 0; i < b.actions().size(); i++) {
            Action a = b.actions().get(i);
            if (a.done() || !verb.equals(a.verb()) || !target.equals(a.target())) {
                continue;
            }
            if (isSanction(verb)) {
                return i;
            }
            String asp = a.spec() == null ? "" : a.spec();
            if (!w.equals(low(a.what())) || !sp.equals(asp)) {
                continue;
            }
            if (!verb.equals("reset") && amount != a.amount()) {
                continue;
            }
            return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------ completamento, bossbar, scadenze

    private void complete(Player by, String taskId, String orderId, int branch, String detail) {
        Task t;
        synchronized (this) {
            charges.remove(by.getUniqueId());
            pendingBefore.remove(by.getUniqueId());
            Task cur = tasks.get(taskId);
            Order o = orderOf(cur, orderId);
            if (o == null) {
                return;
            }
            Order done = new Order(o.id(), o.text(), o.branches(), Status.ESEGUITA, by.getUniqueId(), by.getName(),
                    System.currentTimeMillis(), branch);
            List<Order> orders = new ArrayList<>(cur.orders());
            orders.set(orders.indexOf(o), done);
            t = new Task(cur.id(), cur.createdBy(), cur.createdByName(), cur.createdAt(),
                    cur.status() == Status.ELIMINATA ? Status.ELIMINATA : statusOf(orders), cur.reason(), List.copyOf(orders));
            tasks.put(t.id(), t);
            addLog(t.id(), o.id(), "COMPLETATA", by, detail);
            save();
        }
        hideBar(by.getUniqueId());
        GuiMsg.send(plugin, by, "&aordine completato");
        plugin.playConfiguredSound(by.getLocation(), "message-sounds.success");
        AdminCommand ac = plugin.getAdminCommand();
        if (ac != null) {
            ac.record(by, "task #" + taskId + " ordine " + orderId + " completato | " + detail);
        }
        movement(by.getUniqueId(), "Ha completato l'ordine " + orderId + " della task staff #" + taskId + ": " + detail);
        notify(t.createdBy(), "&aNella tua task &f#" + taskId + "&a &f" + by.getName() + "&a ha completato un ordine: &7" + detail
                + (t.status() == Status.APERTA ? "" : " &7(task completata)"));
        log("task #" + taskId + " ordine " + orderId + " completato da " + by.getName() + ": " + detail);
    }

    /** Ogni secondo: scadenze (anche offline) e bossbar di chi e' online. */
    private void tick() {
        long now = System.currentTimeMillis();
        List<UUID> expired = new ArrayList<>();
        List<Charge> live = new ArrayList<>();
        synchronized (this) {
            for (Charge c : charges.values()) {
                if (c.expiresAt() <= now || orderOf(tasks.get(c.taskId()), c.orderId()) == null) {
                    expired.add(c.staff());
                } else {
                    live.add(c);
                }
            }
        }
        for (UUID s : expired) {
            release(s, EXPIRED);
        }
        for (Charge c : live) {
            Player p = Bukkit.getPlayer(c.staff());
            if (p != null) {
                showBar(p);
            }
        }
    }

    private void showBar(Player p) {
        Charge c = chargeOf(p.getUniqueId());
        if (c == null) {
            hideBar(p.getUniqueId());
            return;
        }
        long left = remainingMs(c);
        Action next = nextAction(c);
        Order o = orderOf(get(c.taskId()), c.orderId());
        String what = next != null ? describe(next) : o != null && type(o) == OrderType.CUSTOM ? shortText(o.text())
                : "ordine";
        long s = left / 1000;
        String title = (s < 60 ? "&c" : "&e") + what + " &7" + String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
        double progress = Math.max(0.0, Math.min(1.0, left / (double) CHARGE_MS));
        // stesso sfondo scuro dei riquadri dell'HUD (bossTextBar) e barra del tempo disegnata sotto il testo;
        // nella pila delle bossbar (BarStack) cosi' non finisce sopra la barra della principale
        net.kyori.adventure.text.Component name = plugin.bossTextBar(title, progress);
        BossBar bar = bars.get(p.getUniqueId());
        if (bar == null) {
            bar = BossBar.bossBar(name, 1f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
            bars.put(p.getUniqueId(), bar);
            plugin.getBars().set(p, BAR_KEY, bar, BAR_PRIORITY, true);
        } else {
            bar.name(name);
        }
    }

    private static final String BAR_KEY = "task";
    private static final int BAR_PRIORITY = 70;

    private void hideBar(UUID id) {
        bars.remove(id);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            plugin.getBars().remove(p, BAR_KEY);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        List<Pending> edits = new ArrayList<>();
        synchronized (this) {
            for (java.util.Iterator<Pending> it = offline.iterator(); it.hasNext(); ) {
                Pending pe = it.next();
                if (pe.target().equals(id)) {
                    edits.add(pe);
                    it.remove();
                }
            }
        }
        if (!edits.isEmpty()) {
            // le modifiche fatte mentre era offline, un attimo dopo il join (prima degli annullamenti)
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                applyPending(p, edits);
            }, 10L);
        }
        List<Undo> mine = new ArrayList<>();
        synchronized (this) {
            for (java.util.Iterator<Undo> it = undos.iterator(); it.hasNext(); ) {
                Undo u = it.next();
                if (u.target().equals(id)) {
                    mine.add(u);
                    it.remove();
                }
            }
        }
        if (!mine.isEmpty()) {
            // un attimo dopo il join, quando i dati del player sono pronti
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                for (Undo u : mine) {
                    if (p.isOnline()) {
                        write(p, u.what(), u.spec(), u.before());
                    } else {
                        synchronized (this) {
                            undos.add(u);
                        }
                    }
                }
                synchronized (this) {
                    save();
                }
                movement(id, "Azioni staff annullate (" + EXPIRED + ")");
            }, 20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && chargeOf(id) != null) {
                showBar(p);
            }
        }, 20L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bars.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------ modifiche ai player offline

    /**
     * Dai / togli / setta / azzera su un player online (subito) o OFFLINE (in coda, applicata al suo prossimo login:
     * i dati di coin, ball, miniere, potenziamenti, stamina e ricompense stanno nel player e si leggono solo da online).
     * Non controlla i permessi (li controlla chi chiama, con allows). null = ok, altrimenti il motivo.
     */
    public String applyOrQueue(CommandSender by, UUID target, String verb, String what, String spec, long amount) {
        if (target == null) {
            return "player sconosciuto";
        }
        String v = low(verb);
        String w = low(what);
        if (!List.of("give", "remove", "set", "reset").contains(v) || !WHATS.contains(w)) {
            return "azione non valida";
        }
        Player tp = Bukkit.getPlayer(target);
        if (tp != null) {
            return applyLive(by, tp, v, w, spec == null || spec.isBlank() ? null : low(spec), (int) Math.max(0, Math.min(Integer.MAX_VALUE, amount)));
        }
        Pending pe = new Pending(UUID.randomUUID().toString().substring(0, 8), target, v, w,
                spec == null || spec.isBlank() ? null : low(spec), v.equals("reset") ? 0 : amount,
                by instanceof Player bp ? bp.getUniqueId() : null, by == null ? "console" : by.getName(), System.currentTimeMillis(), "");
        synchronized (this) {
            offline.add(pe);
            save();
        }
        String desc = describe(newAction(v, w, pe.spec(), pe.amount(), null, target, playerName(target)));
        movement(target, "In attesa del login (offline): " + desc + " (da " + pe.byName() + ")");
        log("modifica offline in coda: " + desc + " (da " + pe.byName() + ")");
        return null;
    }

    /** Modifiche in coda per il player (per la lore: "in attesa del login"). */
    public synchronized List<String> pendingOffline(UUID target) {
        List<String> out = new ArrayList<>();
        for (Pending pe : offline) {
            if (pe.target().equals(target)) {
                out.add(describe(newAction(pe.verb(), pe.what(), pe.spec(), pe.amount(), null, target, playerName(target))));
            }
        }
        return out;
    }

    private void applyPending(Player p, List<Pending> edits) {
        if (!p.isOnline()) {
            synchronized (this) {
                offline.addAll(edits);
            }
            return;
        }
        for (Pending pe : edits) {
            String before = read(p, pe.what(), pe.spec());
            String err = applyLive(null, p, pe.verb(), pe.what(), pe.spec(), (int) Math.max(0, Math.min(Integer.MAX_VALUE, pe.amount())));
            if (err != null) {
                log("modifica offline per " + p.getName() + " non applicata: " + err);
                continue;
            }
            movement(p.getUniqueId(), "Applicata al login: " + describe(newAction(pe.verb(), pe.what(), pe.spec(), pe.amount(), null,
                    p.getUniqueId(), p.getName())) + " (da " + pe.byName() + ")");
            if (!pe.link().isEmpty()) {
                setBefore(pe.link(), before);
            }
        }
        synchronized (this) {
            save();
        }
    }

    /** Se l'azione d'incarico collegata e' ancora spuntata, le salva il valore di prima (per l'annullamento). */
    private synchronized void setBefore(String link, String before) {
        String[] k = link.split("\\|");
        if (k.length != 4) {
            return;
        }
        Task t = tasks.get(k[0]);
        Order o = orderOf(t, k[1]);
        int b = (int) num(k[2]);
        int i = (int) num(k[3]);
        if (o == null || b < 0 || b >= o.branches().size() || i < 0 || i >= o.branches().get(b).actions().size()) {
            return;
        }
        Branch br = o.branches().get(b);
        Action a = br.actions().get(i);
        if (!a.done()) {
            return;
        }
        List<Action> acts = new ArrayList<>(br.actions());
        acts.set(i, new Action(a.verb(), a.what(), a.spec(), a.amount(), a.ruleCode(), a.target(), a.targetName(), a.targetUid(),
                true, a.doneBy(), a.doneAt(), before));
        replaceBranch(t, o, b, new Branch(br.condition(), List.copyOf(acts), br.nothing()));
    }

    /** Toglie dalla coda offline la modifica collegata all'azione (annullamento prima del login). true se c'era. */
    private synchronized boolean dropLinked(String link) {
        for (java.util.Iterator<Pending> it = offline.iterator(); it.hasNext(); ) {
            if (link.equals(it.next().link())) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    /** dai / togli / setta / azzera con i metodi interni (come /adminmania e il profilo player). null = ok. */
    private String applyLive(CommandSender by, Player target, String v, String w, String spec, int n) {
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
                String err = pc.apply(target.getUniqueId(), it, v, n, by);
                return err == null ? null : err.toLowerCase(Locale.ROOT);
            }
            case "ball" -> {
                List<String> chosen = new ArrayList<>();
                boolean all = spec == null || spec.equals("all");
                for (String bt : ammo.types()) {
                    if (all || bt.equalsIgnoreCase(spec)) {
                        chosen.add(bt.toLowerCase(Locale.ROOT));
                    }
                }
                if (chosen.isEmpty()) {
                    return "tipo di ball sconosciuto: " + spec;
                }
                for (String bt : chosen) {
                    int cur = ammo.getAmmo(target, bt);
                    int next = next(v, cur, n, ammo.getAmmoMax(target, bt));
                    if (next != cur) {
                        ammo.setAmmo(target, bt, next);
                    }
                }
            }
            case "mine" -> {
                int cur = ammo.getMine(target);
                int next = v.equals("set") ? Math.max(1, n) : v.equals("reset") ? 1 : Math.max(1, next(v, cur, n, Integer.MAX_VALUE));
                if (next != cur) {
                    ammo.setMine(target, next);
                }
                ammo.refreshMain(target);
            }
            case "upgrade" -> {
                UpgradeManager up = plugin.getUpgrades();
                List<String> chosen = new ArrayList<>();
                boolean all = spec == null || spec.equals("all");
                for (String id : up.ids()) {
                    if (all || id.equals(spec)) {
                        chosen.add(id);
                    }
                }
                if (chosen.isEmpty()) {
                    return "potenziamento sconosciuto: " + spec;
                }
                for (String id : chosen) {
                    int cur = up.getLevel(target, id);
                    int next = next(v, cur, n, up.maxLevel(id));
                    if (next != cur) {
                        up.setLevelAdmin(target, id, next);
                    }
                }
            }
            case "ricompense" -> {
                Rewards rw = plugin.getRewards();
                if (rw == null || spec == null || rewardLabel(spec) == null) {
                    return "ricompensa sconosciuta: " + spec;
                }
                rw.setReached(target, spec, next(v, rw.reached(target, spec), n, rw.staffMaxLevel(spec)));
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
                    default -> power.setPercent(target, 0);
                }
                ammo.refreshMain(target);
            }
            default -> {
                return "azione non valida";
            }
        }
        return null;
    }

    /** give fino al massimo, remove fino a 0, set limitato al massimo, reset = 0. */
    private static int next(String verb, int cur, int n, int max) {
        return switch (verb) {
            case "give" -> (int) Math.min((long) max, (long) cur + n);
            case "remove" -> Math.max(0, cur - n);
            case "set" -> Math.min(n, max);
            default -> 0;
        };
    }

    // ------------------------------------------------------------------ valori (per l'annullamento)

    /** Rimette l'azione com'era prima: subito se il player e' online, altrimenti al suo prossimo login. */
    private void restoreBefore(Action a) {
        if (isSanction(low(a.verb())) || a.target() == null || a.before() == null || a.before().isEmpty()) {
            return;
        }
        Player tp = Bukkit.getPlayer(a.target());
        if (tp != null) {
            write(tp, low(a.what()), a.spec(), a.before());
            if (plugin.getMovements() != null) {
                plugin.getMovements().record(a.target(), MovementLog.Type.STAFF, "Annullato (" + EXPIRED + "): " + describe(a));
            }
        } else {
            synchronized (this) {
                undos.add(new Undo(a.target(), low(a.what()), a.spec(), a.before()));
            }
        }
    }

    /** Valore attuale come "chiave=valore;..." (piu' chiavi per ball/upgrade "all"). */
    private String read(Player p, String what, String spec) {
        AmmoManager ammo = plugin.getAmmo();
        Map<String, String> m = new LinkedHashMap<>();
        switch (what) {
            case "coin" -> m.put("_", String.valueOf(ammo.getCoin(p)));
            case "blockcoin" -> m.put("_", String.valueOf(ammo.getBlockCoin(p)));
            case "blockshell" -> m.put("_", String.valueOf(ammo.getShell(p)));
            case "mine" -> m.put("_", String.valueOf(ammo.getMine(p)));
            case "power" -> m.put("_", String.valueOf(plugin.getPower() == null ? 0 : plugin.getPower().charges(p)));
            case "stamina" -> {
                Power pw = plugin.getPower();
                if (pw != null) {
                    m.put("_", String.valueOf(Math.round(pw.stamina(p) * 100)));
                    m.put("mode", pw.mode(p));
                }
            }
            case "ball" -> {
                for (String t : ammo.types()) {
                    String bt = t.toLowerCase(Locale.ROOT);
                    if (spec == null || spec.equals("all") || spec.equals(bt)) {
                        m.put(bt, String.valueOf(ammo.getAmmo(p, bt)));
                    }
                }
            }
            case "upgrade" -> {
                for (String id : plugin.getUpgrades().ids()) {
                    if (spec == null || spec.equals("all") || spec.equals(id)) {
                        m.put(id, String.valueOf(plugin.getUpgrades().getLevel(p, id)));
                    }
                }
            }
            case "ricompense" -> {
                if (spec != null && plugin.getRewards() != null) {
                    m.put(spec, String.valueOf(plugin.getRewards().reached(p, spec)));
                }
            }
            default -> {
            }
        }
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, String> e : m.entrySet()) {
            parts.add(e.getKey() + "=" + e.getValue());
        }
        return String.join(";", parts);
    }

    private void write(Player p, String what, String spec, String before) {
        AmmoManager ammo = plugin.getAmmo();
        Map<String, String> m = new LinkedHashMap<>();
        for (String part : before.split(";")) {
            int k = part.indexOf('=');
            if (k > 0) {
                m.put(part.substring(0, k), part.substring(k + 1));
            }
        }
        int v = (int) num(m.get("_"));
        switch (what) {
            case "coin" -> ammo.setCoinQuiet(p, v);
            case "blockcoin" -> ammo.setBlockCoinQuiet(p, v);
            case "blockshell" -> ammo.setShell(p, v);
            case "mine" -> ammo.setMine(p, Math.max(1, v));
            case "power" -> {
                if (plugin.getPower() != null) {
                    plugin.getPower().setChargesTo(p, v);
                }
            }
            case "stamina" -> {
                Power pw = plugin.getPower();
                if (pw != null) {
                    pw.setPercent(p, v);
                    String mode = m.getOrDefault("mode", "normal");
                    if (!mode.equals("normal")) {
                        pw.setMode(p, mode);
                    }
                    ammo.refreshMain(p);
                }
            }
            case "ball" -> {
                for (Map.Entry<String, String> e : m.entrySet()) {
                    if (ammo.exists(e.getKey())) {
                        ammo.setAmmo(p, e.getKey(), (int) num(e.getValue()));
                    }
                }
            }
            case "upgrade" -> {
                for (Map.Entry<String, String> e : m.entrySet()) {
                    plugin.getUpgrades().setLevelAdmin(p, e.getKey(), (int) num(e.getValue()));
                }
            }
            case "ricompense" -> {
                if (plugin.getRewards() != null) {
                    for (Map.Entry<String, String> e : m.entrySet()) {
                        plugin.getRewards().setReached(p, e.getKey(), (int) num(e.getValue()));
                    }
                }
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ ponti per userprofile e /adminmania

    /** cosa + specifica di un contatore del profilo (ball "ghostball", coin...; power_uses non e' nelle task). */
    public static String[] itemWhatOf(PlayerCounters.Item it) {
        return itemWhat(it);
    }

    /** cosa + specifica di un contatore del profilo (ball "ghostball", coin...). */
    private static String[] itemWhat(PlayerCounters.Item it) {
        return switch (it) {
            case DOUBLEBALL, GHOSTBALL, STANDARDBALL, EXPLOSIONBALL -> new String[]{"ball", it.name().toLowerCase(Locale.ROOT)};
            case BLOCKSHELL -> new String[]{"blockshell", null};
            case BLOCKCOIN -> new String[]{"blockcoin", null};
            case COIN -> new String[]{"coin", null};
            case POWER_CHARGES -> new String[]{"power", null};
            case POWER_USES -> new String[]{"power_uses", null};
        };
    }

    /** Verbo e quantita' di un comando del profilo (give remove set full reset setuses): full = set al massimo. */
    private long[] itemAmount(UUID target, PlayerCounters.Item it, String cmd, int n) {
        if (cmd.equals("full") && plugin.getCounters() != null) {
            return new long[]{plugin.getCounters().max(target, it)};
        }
        return new long[]{n};
    }

    private static String itemVerb(String cmd) {
        return switch (cmd) {
            case "give" -> "give";
            case "remove" -> "remove";
            case "reset" -> "reset";
            default -> "set"; // set, full, setuses
        };
    }

    /** allows per i contatori dello userprofile (UserProfileGui.applyItem). */
    public boolean allowsItem(Player staff, UUID target, PlayerCounters.Item it, String cmd, int n) {
        String[] w = itemWhat(it);
        return allows(staff, itemVerb(cmd), w[0], w[1], target, itemAmount(target, it, cmd, n)[0]);
    }

    /** done per i contatori dello userprofile. Chiamare dopo l'apply riuscito (amount calcolato prima: full). */
    public void doneItem(Player staff, UUID target, PlayerCounters.Item it, String cmd, long amount) {
        String[] w = itemWhat(it);
        done(staff, itemVerb(cmd), w[0], w[1], target, amount);
    }

    /** Azione a livelli scelta da allowsLevel (staff -> {verbo, quantita', cosa, spec}), per doneLevel. */
    private final Map<UUID, Object[]> pendingLevel = new HashMap<>();

    /**
     * Cambio di livello dalla GUI dello userprofile (miniere, potenziamenti, ricompense: da from a to): combacia con
     * "dai/togli N", "setta N" o "azzera" dell'incarico (spec = potenziamento / ricompensa, null per le miniere).
     */
    public boolean allowsLevel(Player staff, UUID target, String what, String spec, int from, int to) {
        List<Object[]> cands = new ArrayList<>();
        if (to > from) {
            cands.add(new Object[]{"give", (long) (to - from)});
        } else if (to < from) {
            cands.add(new Object[]{"remove", (long) (from - to)});
        }
        cands.add(new Object[]{"set", (long) to});
        if (to <= ("mine".equals(what) ? 1 : 0)) {
            cands.add(new Object[]{"reset", 0L});
        }
        synchronized (this) {
            pendingLevel.remove(staff.getUniqueId());
        }
        for (Object[] c : cands) {
            if (matching(staff.getUniqueId(), (String) c[0], what, spec, target, (Long) c[1]) >= 0) {
                synchronized (this) {
                    pendingLevel.put(staff.getUniqueId(), new Object[]{c[0], c[1], what, spec});
                }
                return allows(staff, (String) c[0], what, spec, target, (Long) c[1]);
            }
        }
        return allows(staff, (String) cands.get(0)[0], what, spec, target, (Long) cands.get(0)[1]);
    }

    /** Dopo il cambio di livello riuscito (vedi allowsLevel). */
    public void doneLevel(Player staff, UUID target) {
        Object[] c;
        synchronized (this) {
            c = pendingLevel.remove(staff.getUniqueId());
        }
        if (c != null) {
            done(staff, (String) c[0], (String) c[2], (String) c[3], target, (Long) c[1]);
        }
    }

    /** Miniere dalla GUI (da miniera from a miniera to). */
    public boolean allowsMine(Player staff, UUID target, int from, int to) {
        return allowsLevel(staff, target, "mine", null, from, to);
    }

    public void doneMine(Player staff, UUID target) {
        doneLevel(staff, target);
    }

    /** Quantita' di un comando del profilo come la vede il task (full = massimo del player). */
    public long itemAmountOf(UUID target, PlayerCounters.Item it, String cmd, int n) {
        return itemAmount(target, it, cmd, n)[0];
    }

    /**
     * Comando di /bm gia' tradotto da /adminmania (coin|blockcoin|blockshell|mine|ball|upgrade|power|stamina|ricompense ...)
     * -> {verb, what, spec, amount, targetName}; null se non e' un'azione che le task conoscono (allora vale come
     * "fuori incarico" per chi e' sotto admin, se scrive).
     */
    public static String[] legacyAction(String[] t, String senderName) {
        if (t == null || t.length < 2) {
            return null;
        }
        String name = low(t[0]);
        String sub = low(t[1]);
        switch (name) {
            case "coin", "blockcoin", "blockshell", "mine" -> {
                String verb = sub.equals("get") || sub.equals("give") || sub.equals("add") ? "give"
                        : sub.equals("remove") || sub.equals("take") ? "remove" : sub.equals("reset") ? "reset" : null;
                if (verb == null) {
                    return null;
                }
                boolean amt = !verb.equals("reset");
                String amount = amt ? at(t, 2) : "0";
                String target = at(t, amt ? 3 : 2);
                return new String[]{verb, name, null, amount, target == null ? senderName : target};
            }
            case "ball", "upgrade" -> {
                String verb = sub.equals("get") || sub.equals("give") || sub.equals("add") ? "give"
                        : sub.equals("remove") || sub.equals("take") ? "remove" : sub.equals("reset") ? "reset" : null;
                if (verb == null || t.length < 3) {
                    return null;
                }
                String spec = low(t[2]);
                boolean amt = !verb.equals("reset");
                String amount = amt ? at(t, 3) : "0";
                String target = at(t, amt ? 4 : 3);
                return new String[]{verb, name, spec, amount, target == null ? senderName : target};
            }
            case "power" -> {
                String verb = switch (sub) {
                    case "give" -> "give";
                    case "reset" -> "reset";
                    case "set" -> "set";
                    default -> null;
                };
                if (verb == null) {
                    return null;
                }
                String amount = verb.equals("give") ? "1" : verb.equals("set") ? at(t, 2) : "0";
                String target = at(t, verb.equals("set") ? 3 : 2);
                return new String[]{verb, "power", null, amount, target == null ? senderName : target};
            }
            case "stamina" -> {
                String verb = switch (sub) {
                    case "set" -> "set";
                    case "refill" -> "set";
                    case "empty" -> "reset";
                    default -> null;
                };
                if (verb == null) {
                    return sub.equals("see") ? null : new String[]{"set", "stamina_mode", null, "0", at(t, 2) == null ? senderName : at(t, 2)};
                }
                String amount = sub.equals("set") ? at(t, 2) : sub.equals("refill") ? "100" : "0";
                String target = at(t, sub.equals("set") ? 3 : 2);
                return new String[]{verb, "stamina", null, amount == null ? "" : amount.replace("%", ""),
                        target == null ? senderName : target};
            }
            case "ricompense" -> {
                if ((sub.equals("livello") || sub.equals("level") || sub.equals("lvl")) && t.length >= 4) {
                    String type = Rewards.typeFromName(t[2]);
                    String act = low(t[3]);
                    String verb = act.equals("add") || act.equals("give") ? "give" : act.equals("remove") || act.equals("take")
                            ? "remove" : act.equals("set") ? "set" : act.equals("reset") ? "reset" : null;
                    if (verb == null) {
                        return null;
                    }
                    boolean amt = !verb.equals("reset");
                    String amount = amt ? at(t, 4) : "0";
                    String target = at(t, amt ? 5 : 4);
                    return new String[]{verb, "ricompense", type, amount, target == null ? senderName : target};
                }
                if (sub.equals("exp") && t.length >= 3) {
                    String act = low(t[2]);
                    if (act.equals("get") || act.equals("see")) {
                        return null;
                    }
                    return new String[]{"set", "ricompense_exp", null, "0", senderName};
                }
                return null;
            }
            default -> {
                return null;
            }
        }
    }

    private static String at(String[] t, int i) {
        return i < t.length ? t[i] : null;
    }

    // ------------------------------------------------------------------ interni

    private static String invalid(Action a) {
        if (a == null) {
            return "azione vuota";
        }
        String v = low(a.verb());
        if (!VERBS.contains(v)) {
            return "verbo sconosciuto";
        }
        if (a.target() == null) {
            return "player inesistente: " + (a.targetName() == null || a.targetName().isEmpty() ? "?" : a.targetName());
        }
        if (isSanction(v)) {
            return a.ruleCode() == null || a.ruleCode().isBlank() ? "manca il codice regola" : null;
        }
        String w = low(a.what());
        if (!whatsFor(v).contains(w)) {
            return "cosa non valida";
        }
        if (("ball".equals(w) || "ricompense".equals(w) || "upgrade".equals(w)) && (a.spec() == null || a.spec().isEmpty())) {
            return "manca la specifica (" + labelWhat(w) + ")";
        }
        if (v.equals("reset")) {
            return null;
        }
        long[] r = valueRange(v, w);
        return a.amount() >= r[0] && a.amount() <= r[1] ? null : "valore fuori dai limiti";
    }

    static boolean isSanction(String verb) {
        return "ban".equals(verb) || "warn".equals(verb) || "mute".equals(verb);
    }

    private static Order orderOf(Task t, String orderId) {
        if (t == null || orderId == null) {
            return null;
        }
        for (Order o : t.orders()) {
            if (o.id().equals(orderId)) {
                return o;
            }
        }
        return null;
    }

    private synchronized Branch branchOf(Charge c) {
        if (c == null) {
            return null;
        }
        Order o = orderOf(tasks.get(c.taskId()), c.orderId());
        return o == null || c.branch() < 0 || c.branch() >= o.branches().size() ? null : o.branches().get(c.branch());
    }

    /** Sostituisce un ramo dell'ordine. Chiamare dentro synchronized. Non salva. */
    private void replaceBranch(Task t, Order o, int branch, Branch b) {
        List<Branch> br = new ArrayList<>(o.branches());
        br.set(branch, b);
        Order no = new Order(o.id(), o.text(), List.copyOf(br), o.status(), o.doneBy(), o.doneByName(), o.doneAt(), o.chosenBranch());
        List<Order> orders = new ArrayList<>(t.orders());
        orders.set(orders.indexOf(o), no);
        tasks.put(t.id(), new Task(t.id(), t.createdBy(), t.createdByName(), t.createdAt(), t.status(), t.reason(),
                List.copyOf(orders)));
    }

    private static Status statusOf(List<Order> orders) {
        for (Order o : orders) {
            if (o.status() == Status.APERTA) {
                return Status.APERTA;
            }
        }
        return Status.ESEGUITA;
    }

    private static Task withStatus(Task t, Status s) {
        return new Task(t.id(), t.createdBy(), t.createdByName(), t.createdAt(), s, t.reason(), t.orders());
    }

    private static String shortText(String s) {
        String x = s == null ? "" : s;
        return x.length() > 40 ? x.substring(0, 39) + "..." : x;
    }

    /** Chiamare dentro synchronized. Non salva. */
    private void addLog(String taskId, String orderId, String event, CommandSender by, String detail) {
        log.add(new LogEntry(System.currentTimeMillis(), taskId, orderId, event, by instanceof Player p ? p.getUniqueId() : null,
                by == null ? "server" : by.getName(), detail == null ? "" : detail));
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

    private static String statusLabel(Status s) {
        return switch (s) {
            case APERTA -> "aperta";
            case ESEGUITA -> "completata";
            case RIFIUTATA -> "rifiutata";
            case ELIMINATA -> "eliminata";
        };
    }

    /** Id numerico progressivo della task ("1", "2"...). Chiamare dentro synchronized. */
    private String newTaskId() {
        while (tasks.containsKey(String.valueOf(nextId))) {
            nextId++;
        }
        return String.valueOf(nextId++);
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

    /** Id utente nascosto (u-00012) del player, "" se non c'e'. */
    private String userId(UUID id) {
        PlayerIds ids = plugin.getPlayerIds();
        String u = id == null || ids == null ? null : ids.userId(id);
        return u == null ? "" : u;
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
            return o == null ? 0L : Long.parseLong(String.valueOf(o).trim());
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
