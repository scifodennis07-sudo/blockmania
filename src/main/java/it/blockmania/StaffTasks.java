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
 * Task dello staff (GUI TaskGui, /adminmania task): gli helper creano una task (ordinaria = azione guidata su un player,
 * custom = testo libero + azione), i mod eseguono le ordinarie e gli sr_mod le custom, gli admin eliminano/ripristinano.
 * L'azione si applica con gli stessi metodi interni usati da /adminmania (BlockManiaCommand) e dal profilo player
 * (PlayerCounters.apply). I permessi sono quelli per grado delle task (StaffRoles), non adminmania.actions.
 * Tutto finisce nel resoconto. Si salva in staff-tasks.yml. Da usare dal thread principale.
 */
public final class StaffTasks {

    public enum Type { ORDINARIA, CUSTOM }

    public enum Status { APERTA, ESEGUITA, RIFIUTATA, ELIMINATA }

    /** what = coin|blockcoin|blockshell|mine|ball|upgrade|power|stamina|ricompense ; verb = get|remove|edit|reset. */
    public record Action(String what, String verb, long amount) {
    }

    public record Task(String id, Type type, UUID target, String targetName, Action action, String text,
                       UUID createdBy, String createdByName, long createdAt,
                       Status status, UUID doneBy, String doneByName, long doneAt, String reason) {
    }

    /** event: CREATA ESEGUITA RIFIUTATA ELIMINATA RIPRISTINATA. */
    public record LogEntry(long ts, String taskId, String event, UUID by, String byName, String detail) {
    }

    /** Le risorse che /adminmania sa gestire (economy: coin, blockcoin, blockshell; profile: il resto). */
    public static final List<String> WHATS = List.of("coin", "blockcoin", "blockshell", "mine", "ball", "upgrade", "power",
            "stamina", "ricompense");

    private static final List<String> VERBS_BASIC = List.of("get", "remove", "reset");
    private static final List<String> VERBS_EDIT = List.of("get", "remove", "edit", "reset");

    /** Verbi validi per la risorsa (come AdminCommand.groupAllowsName, senza "see"). Lista vuota se non esiste. */
    public static List<String> verbsFor(String what) {
        return switch (base(what)) {
            case "coin", "blockcoin", "blockshell", "mine", "ball", "upgrade" -> VERBS_BASIC;
            case "power", "stamina", "ricompense" -> VERBS_EDIT;
            default -> List.of();
        };
    }

    private static final int MAX_LOG = 5000;

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

    // ------------------------------------------------------------------ persistenza

    public synchronized void load() {
        tasks.clear();
        log.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> m : y.getMapList("tasks")) {
            try {
                Object id = m.get("id");
                Object type = m.get("type");
                Object status = m.get("status");
                UUID target = uuid(str(m.get("target")));
                if (id == null || type == null || status == null || target == null) {
                    continue;
                }
                Action a = new Action(str(m.get("what")), str(m.get("verb")), num(m.get("amount")));
                Task t = new Task(String.valueOf(id), Type.valueOf(String.valueOf(type)), target, str(m.get("targetName")), a,
                        str(m.get("text")), uuid(str(m.get("createdBy"))), str(m.get("createdByName")), num(m.get("createdAt")),
                        Status.valueOf(String.valueOf(status)), uuid(str(m.get("doneBy"))), str(m.get("doneByName")),
                        num(m.get("doneAt")), str(m.get("reason")));
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
            log.add(new LogEntry(num(m.get("ts")), String.valueOf(tid), String.valueOf(ev), uuid(str(m.get("by"))),
                    str(m.get("byName")), str(m.get("detail"))));
        }
    }

    public synchronized void save() {
        YamlConfiguration y = new YamlConfiguration();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Task t : tasks.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", t.id());
            m.put("type", t.type().name());
            m.put("target", t.target().toString());
            m.put("targetName", t.targetName() == null ? "" : t.targetName());
            m.put("what", t.action().what());
            m.put("verb", t.action().verb());
            m.put("amount", t.action().amount());
            m.put("text", t.text() == null ? "" : t.text());
            m.put("createdBy", t.createdBy() == null ? "" : t.createdBy().toString());
            m.put("createdByName", t.createdByName() == null ? "" : t.createdByName());
            m.put("createdAt", t.createdAt());
            m.put("status", t.status().name());
            m.put("doneBy", t.doneBy() == null ? "" : t.doneBy().toString());
            m.put("doneByName", t.doneByName() == null ? "" : t.doneByName());
            m.put("doneAt", t.doneAt());
            m.put("reason", t.reason() == null ? "" : t.reason());
            out.add(m);
        }
        y.set("tasks", out);
        List<Map<String, Object>> lo = new ArrayList<>();
        for (LogEntry e : log) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ts", e.ts());
            m.put("task", e.taskId());
            m.put("event", e.event());
            m.put("by", e.by() == null ? "" : e.by().toString());
            m.put("byName", e.byName() == null ? "" : e.byName());
            m.put("detail", e.detail() == null ? "" : e.detail());
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

    /** Resoconto, le voci piu' recenti prima. onlyForOrNull = solo le voci delle task create o eseguite/rifiutate da lui. */
    public synchronized List<LogEntry> log(UUID onlyForOrNull) {
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : log) {
            if (onlyForOrNull != null) {
                Task t = tasks.get(e.taskId());
                boolean mine = t != null && (onlyForOrNull.equals(t.createdBy()) || onlyForOrNull.equals(t.doneBy()));
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

    /** Puo' eseguire (o rifiutare) la task: aperta, non creata da lui, mod+ per le ordinarie, sr_mod+ per le custom. */
    public boolean canExecute(Player p, Task t) {
        if (p == null || t == null || t.status() != Status.APERTA || suspended(p)) {
            return false;
        }
        if (p.getUniqueId().equals(t.createdBy())) {
            return false;
        }
        return rank(p) >= (t.type() == Type.CUSTOM ? SR_MOD : MOD);
    }

    public boolean isAdmin(Player p) {
        return p != null && !suspended(p) && rank(p) >= ADMIN;
    }

    // ------------------------------------------------------------------ azioni

    /** Crea la task; null se non puo' crearla o i dati non sono validi. textOrNull obbligatorio per CUSTOM. */
    public Task create(Player by, Type type, UUID target, Action action, String textOrNull) {
        if (by == null || type == null || target == null || !canCreate(by) || !valid(action)) {
            return null;
        }
        String text = textOrNull == null ? "" : textOrNull.trim();
        if (type == Type.CUSTOM && text.isEmpty()) {
            return null;
        }
        // niente task su se stessi (un helper non si fa dare risorse da un mod), salvo gli admin
        if (by.getUniqueId().equals(target) && !isAdmin(by)) {
            return null;
        }
        Action a = new Action(action.what().toLowerCase(Locale.ROOT), action.verb().toLowerCase(Locale.ROOT),
                action.verb().equalsIgnoreCase("reset") ? 0 : action.amount());
        Task t;
        synchronized (this) {
            t = new Task(newId(), type, target, playerName(target), a, type == Type.CUSTOM ? text : "",
                    by.getUniqueId(), by.getName(), System.currentTimeMillis(), Status.APERTA, null, "", 0L, "");
            tasks.put(t.id(), t);
            addLog(t.id(), "CREATA", by, describe(t));
            save();
        }
        movement(by.getUniqueId(), "Ha creato la task staff " + t.id() + " (" + typeLabel(type) + "): " + describe(t));
        log("task " + t.id() + " creata da " + by.getName() + ": " + describe(t));
        return t;
    }

    /** Esegue davvero l'azione. null = ok, altrimenti il messaggio d'errore. */
    public String execute(Player by, String id) {
        Task t = get(id);
        if (t == null) {
            return "task non trovata";
        }
        if (t.status() != Status.APERTA) {
            return "la task non e' piu' aperta";
        }
        String why = cannot(by, t);
        if (why != null) {
            return why;
        }
        Player target = Bukkit.getPlayer(t.target());
        if (target == null) {
            return "il player " + t.targetName() + " e' offline: si puo' eseguire solo quando e' online";
        }
        // permessi per grado (regole delle task), non quelli di adminmania.actions: la task e' gia' l'autorizzazione.
        // Resta solo la protezione su se stessi (come adminmania.self-min = admin).
        if (by.getUniqueId().equals(t.target()) && !isAdmin(by)) {
            return "col tuo grado non puoi eseguire una task su te stesso";
        }
        Action a = t.action();
        AdminCommand ac = plugin.getAdminCommand();
        String err = apply(by, target, a);
        if (err != null) {
            return err;
        }
        Task done;
        synchronized (this) {
            Task now = tasks.get(t.id());
            if (now == null || now.status() != Status.APERTA) {
                // non dovrebbe succedere (thread principale), l'azione pero' e' gia' stata fatta: la segno comunque
                plugin.getLogger().warning("Task staff " + t.id() + ": stato cambiato durante l'esecuzione");
            }
            done = withStatus(t, Status.ESEGUITA, by, "");
            tasks.put(done.id(), done);
            addLog(done.id(), "ESEGUITA", by, describe(done));
            save();
        }
        if (ac != null) {
            ac.record(by, "task." + typeLabel(t.type()) + " " + t.id() + " | " + a.what() + " " + a.verb()
                    + (a.verb().equals("reset") ? "" : " " + a.amount()) + " " + t.targetName());
        }
        movement(t.target(), "Task staff " + t.id() + " eseguita da " + by.getName() + ": " + describe(t));
        movement(by.getUniqueId(), "Ha eseguito la task staff " + t.id() + " di " + t.createdByName() + ": " + describe(t));
        notify(t.createdBy(), "&aLa tua task &f" + t.id() + "&a e' stata eseguita da &f" + by.getName() + "&a: &7" + describe(t));
        log("task " + t.id() + " eseguita da " + by.getName());
        return null;
    }

    /** Rifiuta la task con un motivo. null = ok, altrimenti il messaggio d'errore. */
    public String reject(Player by, String id, String reason) {
        Task t = get(id);
        if (t == null) {
            return "task non trovata";
        }
        if (t.status() != Status.APERTA) {
            return "la task non e' piu' aperta";
        }
        String why = cannot(by, t);
        if (why != null) {
            return why;
        }
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) {
            return "scrivi il motivo del rifiuto";
        }
        synchronized (this) {
            Task done = withStatus(t, Status.RIFIUTATA, by, r);
            tasks.put(done.id(), done);
            addLog(done.id(), "RIFIUTATA", by, r);
            save();
        }
        movement(by.getUniqueId(), "Ha rifiutato la task staff " + t.id() + " di " + t.createdByName() + ": " + r);
        notify(t.createdBy(), "&cLa tua task &f" + t.id() + "&c e' stata rifiutata da &f" + by.getName() + "&c. &7Motivo: &f\""
                + r + "\"");
        log("task " + t.id() + " rifiutata da " + by.getName() + ": " + r);
        return null;
    }

    /** Admin: manda la task nel cestino. */
    public boolean delete(Player admin, String id) {
        if (!isAdmin(admin)) {
            return false;
        }
        Task t;
        synchronized (this) {
            t = tasks.get(id);
            if (t == null || t.status() == Status.ELIMINATA) {
                return false;
            }
            tasks.put(t.id(), copy(t, Status.ELIMINATA));
            addLog(t.id(), "ELIMINATA", admin, "era " + statusLabel(t.status()));
            save();
        }
        movement(admin.getUniqueId(), "Ha eliminato la task staff " + t.id() + " di " + t.createdByName());
        log("task " + t.id() + " eliminata da " + admin.getName());
        return true;
    }

    /** Admin: rimette la task dal cestino nello stato che aveva (aperta, eseguita o rifiutata). */
    public boolean restore(Player admin, String id) {
        if (!isAdmin(admin)) {
            return false;
        }
        Task t;
        Status back;
        synchronized (this) {
            t = tasks.get(id);
            if (t == null || t.status() != Status.ELIMINATA) {
                return false;
            }
            back = t.doneAt() <= 0 ? Status.APERTA : (t.reason() == null || t.reason().isEmpty() ? Status.ESEGUITA : Status.RIFIUTATA);
            tasks.put(t.id(), copy(t, back));
            addLog(t.id(), "RIPRISTINATA", admin, "torna " + statusLabel(back));
            save();
        }
        movement(admin.getUniqueId(), "Ha ripristinato la task staff " + t.id() + " di " + t.createdByName());
        log("task " + t.id() + " ripristinata da " + admin.getName() + " (" + statusLabel(back) + ")");
        return true;
    }

    // ------------------------------------------------------------------ applicazione dell'azione

    /**
     * Stessa logica di BlockManiaCommand (counter / ball / upgrade / power / stamina / ricompense exp), con i metodi interni.
     * coin, blockcoin, blockshell e power passano da PlayerCounters.apply come il profilo player. null = ok.
     */
    private String apply(Player by, Player target, Action a) {
        String what = base(a.what());
        String type = sub(a.what());
        String verb = a.verb();
        int n = (int) Math.max(0, Math.min(Integer.MAX_VALUE, a.amount()));
        AmmoManager ammo = plugin.getAmmo();
        switch (what) {
            case "coin", "blockcoin", "blockshell", "power" -> {
                PlayerCounters pc = plugin.getCounters();
                if (pc == null) {
                    return "contatori non disponibili";
                }
                PlayerCounters.Item it = switch (what) {
                    case "coin" -> PlayerCounters.Item.COIN;
                    case "blockcoin" -> PlayerCounters.Item.BLOCKCOIN;
                    case "blockshell" -> PlayerCounters.Item.BLOCKSHELL;
                    default -> PlayerCounters.Item.POWER_CHARGES;
                };
                String cmd = switch (verb) {
                    case "get" -> "give";
                    case "edit" -> "set";
                    default -> verb; // remove, reset
                };
                String err = pc.apply(target.getUniqueId(), it, cmd, n, by);
                return err == null ? null : err.toLowerCase(Locale.ROOT);
            }
            case "mine" -> {
                int cur = ammo.getMine(target);
                int next = next(verb, cur, n, Integer.MAX_VALUE);
                if (next != cur) {
                    ammo.setMine(target, next);
                }
                itemsMovement(target, verb, n, "mine");
            }
            case "ball" -> {
                List<String> chosen = new ArrayList<>();
                if (type == null || type.equals("all")) {
                    chosen.addAll(ammo.types());
                } else if (ammo.exists(type)) {
                    chosen.add(type);
                } else {
                    return "tipo di palla sconosciuto: " + type;
                }
                for (String bt : chosen) {
                    int cur = ammo.getAmmo(target, bt);
                    int next = next(verb, cur, n, ammo.getAmmoMax(target, bt));
                    if (next != cur) {
                        ammo.setAmmo(target, bt, next);
                    }
                }
                itemsMovement(target, verb, n, type == null || type.equals("all") ? "palle (tutte)" : type);
            }
            case "upgrade" -> {
                UpgradeManager up = plugin.getUpgrades();
                List<String> chosen = new ArrayList<>();
                if (type == null || type.equals("all")) {
                    chosen.addAll(up.ids());
                } else if (up.ids().contains(type)) {
                    chosen.add(type);
                } else {
                    return "potenziamento sconosciuto: " + type;
                }
                for (String uid : chosen) {
                    int cur = up.getLevel(target, uid);
                    int next = next(verb, cur, n, up.maxLevel(uid));
                    if (next != cur) {
                        up.setLevelAdmin(target, uid, next);
                    }
                }
                if (plugin.getMovements() != null) {
                    plugin.getMovements().record(target.getUniqueId(), MovementLog.Type.UPGRADE,
                            verbText(verb, n, "livelli " + (type == null || type.equals("all") ? "di tutti i potenziamenti" : type)));
                }
            }
            case "stamina" -> {
                Power power = plugin.getPower();
                if (power == null) {
                    return "stamina non disponibile";
                }
                double cur = power.stamina(target) * 100.0;
                switch (verb) {
                    case "get" -> power.setPercent(target, Math.min(100.0, cur + n));
                    case "remove" -> power.setPercent(target, Math.max(0.0, cur - n));
                    case "edit" -> power.setPercent(target, Math.min(100.0, n));
                    default -> {
                        // reset = come /adminmania profile stamina empty
                        power.setMode(target, "normal");
                        power.drain(target, 1.0);
                    }
                }
                ammo.refreshMain(target);
                itemsMovement(target, verb, n, "% stamina");
            }
            case "ricompense" -> {
                // come /adminmania profile rewards exp <give|remove|set|reset>
                int cur = ammo.getCounter(target, "rw_exp");
                int next = switch (verb) {
                    case "get" -> (int) Math.min(Integer.MAX_VALUE, (long) cur + n);
                    case "remove" -> Math.max(0, cur - n);
                    case "edit" -> n;
                    default -> 0;
                };
                ammo.setCounter(target, "rw_exp", next);
                if (plugin.getMovements() != null) {
                    plugin.getMovements().record(target.getUniqueId(), MovementLog.Type.REWARD, verbText(verb, n, "exp ricompense"));
                }
            }
            default -> {
                return "azione non valida";
            }
        }
        return null;
    }

    /** Come BlockManiaCommand.runStats: get fino al massimo, remove fino a 0, reset = 0. */
    private static int next(String verb, int cur, int n, int max) {
        return switch (verb) {
            case "get" -> (int) Math.min((long) max, (long) cur + n);
            case "remove" -> Math.max(0, cur - n);
            default -> 0;
        };
    }

    private void itemsMovement(Player target, String verb, int n, String what) {
        if (plugin.getMovements() != null) {
            plugin.getMovements().record(target.getUniqueId(), MovementLog.Type.ITEMS, verbText(verb, n, what));
        }
    }

    private static String verbText(String verb, int n, String what) {
        return switch (verb) {
            case "get" -> "Ha ricevuto " + n + " " + what + " (task staff)";
            case "remove" -> "Gli sono stati tolti " + n + " " + what + " (task staff)";
            case "edit" -> "Ha " + what + " impostato a " + n + " (task staff)";
            default -> "Ha azzerato " + what + " (task staff)";
        };
    }

    // ------------------------------------------------------------------ interni

    /** Motivo per cui non puo' eseguire/rifiutare (la task e' gia' aperta), o null. */
    private String cannot(Player by, Task t) {
        if (by == null) {
            return "solo uno staffer puo' farlo";
        }
        if (suspended(by)) {
            return "sei sospeso dallo staff";
        }
        if (by.getUniqueId().equals(t.createdBy())) {
            return "non puoi eseguire o rifiutare una task creata da te";
        }
        if (!canExecute(by, t)) {
            return t.type() == Type.CUSTOM ? "le task custom le gestiscono gli sr_mod in su" : "le task le gestiscono i mod in su";
        }
        return null;
    }

    private static boolean valid(Action a) {
        if (a == null || a.what() == null || a.verb() == null) {
            return false;
        }
        String what = base(a.what());
        String verb = a.verb().toLowerCase(Locale.ROOT);
        if (!WHATS.contains(what) || !verbsFor(what).contains(verb)) {
            return false;
        }
        if (verb.equals("reset")) {
            return true;
        }
        if (verb.equals("edit")) {
            return a.amount() >= 0 && (!what.equals("stamina") || a.amount() <= 100);
        }
        return a.amount() > 0 && a.amount() <= Integer.MAX_VALUE;
    }

    /** "ball:ghostball" -> "ball" (si puo' indicare il tipo di palla / potenziamento; senza = tutti). */
    private static String base(String what) {
        if (what == null) {
            return "";
        }
        String w = what.toLowerCase(Locale.ROOT);
        int i = w.indexOf(':');
        return i < 0 ? w : w.substring(0, i);
    }

    private static String sub(String what) {
        int i = what == null ? -1 : what.indexOf(':');
        return i < 0 ? null : what.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static Task withStatus(Task t, Status s, Player by, String reason) {
        return new Task(t.id(), t.type(), t.target(), t.targetName(), t.action(), t.text(), t.createdBy(), t.createdByName(),
                t.createdAt(), s, by.getUniqueId(), by.getName(), System.currentTimeMillis(), reason);
    }

    private static Task copy(Task t, Status s) {
        return new Task(t.id(), t.type(), t.target(), t.targetName(), t.action(), t.text(), t.createdBy(), t.createdByName(),
                t.createdAt(), s, t.doneBy(), t.doneByName(), t.doneAt(), t.reason());
    }

    /** Chiamare dentro synchronized. Non salva. */
    private void addLog(String taskId, String event, CommandSender by, String detail) {
        log.add(new LogEntry(System.currentTimeMillis(), taskId, event, by instanceof Player p ? p.getUniqueId() : null,
                name(by), detail == null ? "" : detail));
        while (log.size() > MAX_LOG) {
            log.remove(0);
        }
    }

    /** "dai 100 coin a Mario" / "azzera stamina a Mario" (+ testo per le custom). */
    private static String describe(Task t) {
        Action a = t.action();
        String verb = switch (a.verb()) {
            case "get" -> "dai";
            case "remove" -> "togli";
            case "edit" -> "imposta";
            default -> "azzera";
        };
        String amount = a.verb().equals("reset") ? "" : " " + a.amount();
        String s = verb + amount + " " + a.what() + " a " + t.targetName();
        return t.text() == null || t.text().isEmpty() ? s : s + " (\"" + t.text() + "\")";
    }

    private static String typeLabel(Type t) {
        return t == Type.CUSTOM ? "custom" : "ordinaria";
    }

    private static String statusLabel(Status s) {
        return switch (s) {
            case APERTA -> "aperta";
            case ESEGUITA -> "eseguita";
            case RIFIUTATA -> "rifiutata";
            case ELIMINATA -> "eliminata";
        };
    }

    /** Id corto univoco (8 caratteri esadecimali di un UUID casuale). Chiamare dentro synchronized. */
    private String newId() {
        while (true) {
            String id = UUID.randomUUID().toString().substring(0, 8);
            if (!tasks.containsKey(id)) {
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

    private static String name(CommandSender by) {
        return by == null ? "console" : by.getName();
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
