package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachment;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * /adminmania (alias /am): tutti i comandi dello staff divisi in gruppi (economy, profile, items, build, shops, system).
 * Ogni comando viene controllato in base al ruolo (blockmania.role.<ruolo>) e al reparto (blockmania.dept.<reparto>),
 * con tetti per comando e al giorno, protezione contro le azioni su se stessi, conferma per i comandi distruttivi e un
 * registro di tutto quello che fa lo staff. Poi il comando viene eseguito dalla logica di /bm di sempre.
 */
public final class AdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> GROUPS = List.of("economy", "profile", "items", "build", "shops", "system");

    /** Nomi italiani dei gruppi (funzionano come gli inglesi). */
    private static final Map<String, String> GROUP_ALIAS = Map.of(
            "economia", "economy", "profilo", "profile", "oggetti", "items", "costruzione", "build",
            "negozi", "shops", "sistema", "system");

    /** Nome nel gruppo -> comando di /bm. */
    private static final Map<String, String> TO_LEGACY = new LinkedHashMap<>();
    /** Comando di /bm -> gruppo. */
    private static final Map<String, String> LEGACY_GROUP = new HashMap<>();
    /** Comando di /bm -> nome nel gruppo (per i suggerimenti). */
    private static final Map<String, String> FROM_LEGACY = new HashMap<>();

    private static void map(String group, String name, String legacy) {
        TO_LEGACY.put(group + ":" + name, legacy);
        LEGACY_GROUP.put(legacy, group);
        FROM_LEGACY.put(legacy, group + " " + name);
    }

    static {
        map("economy", "coin", "coin");
        map("economy", "blockcoin", "blockcoin");
        map("economy", "blockshell", "blockshell");
        map("profile", "mine", "mine");
        map("profile", "ball", "ball");
        map("profile", "upgrade", "upgrade");
        map("profile", "power", "power");
        map("profile", "stamina", "stamina");
        map("profile", "rewards", "ricompense");
        map("profile", "reset", "resetall");
        map("items", "get", "get");
        map("items", "block", "getblock");
        map("items", "cheque", "cheque");
        map("items", "list", "items");
        map("build", "block", "block");
        map("build", "mines", "mines");
        map("shops", "shop", "shop");
        map("system", "reload", "reload");
        map("system", "limits", "limits");
        map("system", "bgtest", "bgtest");
        map("system", "guidebug", "guidebug");
        map("system", "tutorial", "tutorial");
    }

    /** Come si scrive in /adminmania un comando di /bm (per il messaggio che spiega dove e' finito). */
    static String suggestion(String[] legacyArgs) {
        if (legacyArgs.length == 0) {
            return "/adminmania help";
        }
        String name = legacyArgs[0].toLowerCase(Locale.ROOT);
        if (name.equals("assegno")) {
            name = "cheque";
        }
        if (name.equals("rewards")) {
            name = "ricompense";
        }
        String base = FROM_LEGACY.get(name);
        if (base == null) {
            return "/adminmania help";
        }
        StringBuilder sb = new StringBuilder("/adminmania ").append(base);
        for (int i = 1; i < legacyArgs.length; i++) {
            sb.append(' ').append(legacyArgs[i]);
        }
        return sb.toString();
    }

    private final BlockMania plugin;
    private final BlockManiaCommand legacy;
    private final File dataFile;
    private YamlConfiguration data;
    private final Map<UUID, String[]> pendingConfirm = new HashMap<>();
    private final Map<UUID, Long> pendingTime = new HashMap<>();

    public AdminCommand(BlockMania plugin, BlockManiaCommand legacy) {
        this.plugin = plugin;
        this.legacy = legacy;
        this.dataFile = new File(plugin.getDataFolder(), "adminmania-data.yml");
        this.data = YamlConfiguration.loadConfiguration(dataFile);
    }

    // ------------------------------------------------------------------
    //  Ruoli
    // ------------------------------------------------------------------

    private List<String> roleIds() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("adminmania.roles");
        return s == null ? List.of() : new ArrayList<>(s.getKeys(false));
    }

    /** Livello del ruolo piu' alto (1 = primo della lista, 0 = nessun ruolo). Console e OP sono all'ultimo. */
    int level(CommandSender sender) {
        List<String> roles = roleIds();
        if (sender instanceof ConsoleCommandSender || sender.isOp()) {
            return roles.size();
        }
        for (int i = roles.size() - 1; i >= 0; i--) {
            if (sender.hasPermission("blockmania.role." + roles.get(i))) {
                return i + 1;
            }
        }
        return 0;
    }

    private int roleLevel(String id) {
        int i = roleIds().indexOf(id);
        return i < 0 ? Integer.MAX_VALUE : i + 1;
    }

    private String roleName(CommandSender sender) {
        int lv = level(sender);
        List<String> roles = roleIds();
        if (lv <= 0) {
            return "nessun ruolo";
        }
        return plugin.getConfig().getString("adminmania.roles." + roles.get(lv - 1), roles.get(lv - 1));
    }

    private boolean hasDept(CommandSender sender, List<String> depts) {
        if (sender instanceof ConsoleCommandSender || sender.isOp()) {
            return true;
        }
        for (String d : depts) {
            if (sender.hasPermission("blockmania.dept." + d)) {
                return true;
            }
        }
        return false;
    }

    /** L'azione "gruppo.verbo" e' permessa a questo staffer? */
    private boolean allowed(CommandSender sender, String group, String verb) {
        ConfigurationSection a = plugin.getConfig().getConfigurationSection("adminmania.actions." + group + "." + verb);
        if (a == null) {
            return level(sender) >= roleIds().size();
        }
        if (level(sender) >= roleLevel(a.getString("min", "founder"))) {
            return true;
        }
        return hasDept(sender, a.getStringList("depts"));
    }

    private boolean anyAllowed(CommandSender sender, String group) {
        ConfigurationSection g = plugin.getConfig().getConfigurationSection("adminmania.actions." + group);
        if (g == null) {
            return false;
        }
        for (String verb : g.getKeys(false)) {
            if (allowed(sender, group, verb)) {
                return true;
            }
        }
        return false;
    }

    /** Tetto per ruolo: -1 = nessun tetto, -2 = non definito. */
    private long capOf(CommandSender sender, String group, String verb, String kind) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("adminmania.actions." + group + "." + verb + "." + kind);
        if (c == null || c.getKeys(false).isEmpty()) {
            return -2;
        }
        if (sender instanceof ConsoleCommandSender || sender.isOp()) {
            return -1;
        }
        int lv = level(sender);
        long found = -2;
        long lowest = -2;
        int lowestLv = Integer.MAX_VALUE;
        for (String role : c.getKeys(false)) {
            int rl = roleLevel(role);
            if (rl < lowestLv) {
                lowestLv = rl;
                lowest = c.getLong(role);
            }
            if (rl <= lv) {
                found = c.getLong(role);
            }
        }
        // un reparto senza il ruolo vale come il ruolo piu' basso con un tetto
        return found == -2 ? lowest : found;
    }

    // ------------------------------------------------------------------
    //  Registro e tetti giornalieri
    // ------------------------------------------------------------------

    private void log(CommandSender sender, String text) {
        if (!plugin.getConfig().getBoolean("adminmania.log", true)) {
            return;
        }
        try {
            plugin.getDataFolder().mkdirs();
            try (FileWriter w = new FileWriter(new File(plugin.getDataFolder(), "adminmania.log"), true)) {
                w.write(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + " | "
                        + sender.getName() + " (" + roleName(sender) + ") | " + text + "\n");
            }
        } catch (IOException e) {
            plugin.getLogger().warning("adminmania.log: " + e.getMessage());
        }
    }

    private String today() {
        return LocalDate.now().toString();
    }

    private long usedToday(Player p, String key) {
        return data.getLong("days." + today() + "." + p.getUniqueId() + "." + key.replace('.', '_'), 0L);
    }

    private void addUsed(Player p, String key, long amount) {
        String path = "days." + today() + "." + p.getUniqueId() + "." + key.replace('.', '_');
        data.set(path, data.getLong(path, 0L) + amount);
        ConfigurationSection days = data.getConfigurationSection("days");
        if (days != null) {
            for (String d : days.getKeys(false)) {
                if (!d.equals(today())) {
                    data.set("days." + d, null);
                }
            }
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("adminmania-data.yml: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  Che azione e' un comando
    // ------------------------------------------------------------------

    private static String action(String word) {
        return switch (word.toLowerCase(Locale.ROOT)) {
            case "get", "give", "add" -> "get";
            case "remove", "take" -> "remove";
            case "see" -> "see";
            case "reset" -> "reset";
            default -> "edit";
        };
    }

    /** Verbo (see/get/remove/reset/edit/use...) del comando di /bm gia' tradotto, o null se non si capisce. */
    private String verbOf(String[] t) {
        String name = t[0];
        String group = LEGACY_GROUP.getOrDefault(name, "");
        switch (name) {
            case "coin", "blockcoin", "blockshell", "mine", "ball", "upgrade":
                return t.length > 1 ? action(t[1]) : null;
            case "power":
                return t.length > 1 ? action(t[1]) : null;
            case "stamina":
                if (t.length < 2) {
                    return null;
                }
                return t[1].equalsIgnoreCase("see") ? "see" : "edit";
            case "ricompense":
                if (t.length < 2) {
                    return "see";
                }
                if (t[1].equalsIgnoreCase("tipi") || t[1].equalsIgnoreCase("types")) {
                    return "see";
                }
                if (t[1].equalsIgnoreCase("exp")) {
                    return t.length > 2 ? action(t[2]) : null;
                }
                return t.length > 3 ? action(t[3]) : null;
            case "resetall":
                return "resetall";
            case "get", "getblock":
                return "get";
            case "cheque", "assegno":
                return "cheque";
            case "items":
                return "list";
            case "block", "mines", "shop":
                return "use";
            case "reload":
                return "reload";
            case "limits":
                if (t.length < 2) {
                    return null;
                }
                return t[1].equalsIgnoreCase("see") ? "see" : "limits";
            case "bgtest", "guidebug":
                return "debug";
            case "tutorial":
                return "tutorial";
            default:
                return group.isEmpty() ? null : "use";
        }
    }

    private static boolean isWrite(String verb) {
        return verb.equals("get") || verb.equals("remove") || verb.equals("reset") || verb.equals("edit")
                || verb.equals("resetall") || verb.equals("cheque");
    }

    // ------------------------------------------------------------------
    //  Traduzione /adminmania -> /bm
    // ------------------------------------------------------------------

    private static String group(String word) {
        String w = word.toLowerCase(Locale.ROOT);
        return GROUP_ALIAS.getOrDefault(w, w);
    }

    /** [gruppo, nome, ...] -> comando di /bm; null se non esiste. */
    private String[] translate(String[] args) {
        if (args.length == 0) {
            return null;
        }
        String g = group(args[0]);
        if (g.equals("shops")) {
            String[] out = new String[args.length];
            out[0] = "shop";
            System.arraycopy(args, 1, out, 1, args.length - 1);
            return out;
        }
        if (args.length < 2) {
            return null;
        }
        String legacyName = TO_LEGACY.get(g + ":" + args[1].toLowerCase(Locale.ROOT));
        if (legacyName == null) {
            return null;
        }
        String[] out = new String[args.length - 1];
        out[0] = legacyName;
        System.arraycopy(args, 2, out, 1, args.length - 2);
        return out;
    }

    // ------------------------------------------------------------------
    //  Esecuzione
    // ------------------------------------------------------------------

    private void deny(CommandSender sender, String text) {
        sender.sendMessage(plugin.text("&c" + text));
        if (sender instanceof Player p) {
            plugin.playConfiguredSound(p.getLocation(), "message-sounds.error");
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("ticket")) {
            return plugin.getTickets().staffCommand(sender, args);
        }
        if (level(sender) <= 0 && !hasDept(sender, List.of("builder", "texturemaker", "developer"))) {
            deny(sender, "questo comando è solo per lo staff");
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help(sender, args.length > 1 ? group(args[1]) : "");
            return true;
        }
        String g = group(args[0]);
        if (!GROUPS.contains(g)) {
            deny(sender, "gruppo sconosciuto: " + args[0] + ". Scegli tra: " + visibleGroups(sender) + " (o /adminmania help)");
            return true;
        }
        if (!anyAllowed(sender, g)) {
            log(sender, "NEGATO gruppo " + g + " | " + String.join(" ", args));
            deny(sender, "il tuo ruolo (" + roleName(sender) + ") non può usare il gruppo " + g);
            return true;
        }
        if (args.length < 2 && !g.equals("shops")) {
            sender.sendMessage(plugin.text("&7scrivi cosa vuoi fare in &f" + g + "&7: &f" + groupNames(sender, g)));
            return true;
        }
        String[] t = translate(args);
        if (t == null) {
            deny(sender, "in " + g + " non esiste " + args[1] + ". Scegli tra: " + groupNames(sender, g));
            return true;
        }
        String verb = verbOf(t);
        String group = LEGACY_GROUP.get(t[0]);
        if (group != null && verb != null) {
            if (!allowed(sender, group, verb)) {
                log(sender, "NEGATO " + group + "." + verb + " | " + String.join(" ", args));
                deny(sender, "il tuo ruolo (" + roleName(sender) + ") non può fare questo (" + group + " " + verb
                        + "). Chiedilo a un ruolo più alto.");
                return true;
            }
            if (isWrite(verb) && !checkSelf(sender, t)) {
                log(sender, "NEGATO su se stesso " + group + "." + verb + " | " + String.join(" ", args));
                return true;
            }
            if (!checkCaps(sender, group, verb, t)) {
                log(sender, "NEGATO tetto " + group + "." + verb + " | " + String.join(" ", args));
                return true;
            }
            if (needsConfirm(t, verb) && !confirmed(sender, args)) {
                return true;
            }
        } else if (group != null && !anyAllowed(sender, group)) {
            deny(sender, "il tuo ruolo non può usare questo comando");
            return true;
        }
        run(sender, command, label, t);
        if (group != null && verb != null && (isWrite(verb) || verb.equals("use") || verb.equals("reload"))) {
            log(sender, group + "." + verb + " | " + String.join(" ", args));
        }
        return true;
    }

    /** Dare/togliere a se stessi serve un ruolo alto. */
    private boolean checkSelf(CommandSender sender, String[] t) {
        if (!(sender instanceof Player self) || sender.isOp()) {
            return true;
        }
        if (level(sender) >= roleLevel(plugin.getConfig().getString("adminmania.self-min", "admin"))) {
            return true;
        }
        String target = self.getName();
        for (int i = 1; i < t.length; i++) {
            Player p = Bukkit.getPlayerExact(t[i]);
            if (p != null) {
                target = p.getName();
                break;
            }
        }
        if (target.equalsIgnoreCase(self.getName())) {
            deny(sender, "col tuo ruolo non puoi dare, togliere o azzerare niente a te stesso: scrivi il nome di un altro giocatore, "
                    + "oppure chiedi a un ruolo più alto");
            return false;
        }
        return true;
    }

    private boolean checkCaps(CommandSender sender, String group, String verb, String[] t) {
        long perCommand = capOf(sender, group, verb, "cap");
        long perDay = capOf(sender, group, verb, "day");
        if (perCommand == -2 && perDay == -2) {
            return true;
        }
        long amount = 0;
        for (int i = 2; i < t.length; i++) {
            try {
                amount = Long.parseLong(t[i]);
                break;
            } catch (NumberFormatException ignored) {
                // non e' il numero
            }
        }
        if (amount <= 0) {
            return true;
        }
        if (perCommand >= 0 && amount > perCommand) {
            deny(sender, "col tuo ruolo (" + roleName(sender) + ") puoi fare al massimo " + perCommand
                    + " per volta. Per di più chiedi a un ruolo più alto.");
            return false;
        }
        if (perDay >= 0 && sender instanceof Player p) {
            long used = usedToday(p, group + "." + verb);
            if (used + amount > perDay) {
                deny(sender, "col tuo ruolo (" + roleName(sender) + ") oggi puoi ancora fare " + Math.max(0, perDay - used)
                        + " (limite giornaliero " + perDay + "). Per di più chiedi a un ruolo più alto.");
                return false;
            }
            addUsed(p, group + "." + verb, amount);
        }
        return true;
    }

    private static boolean needsConfirm(String[] t, String verb) {
        if (verb.equals("resetall")) {
            return true;
        }
        return t[0].equals("mines") && t.length > 1
                && (t[1].equalsIgnoreCase("deletemines") || t[1].equalsIgnoreCase("remove"));
    }

    private boolean confirmed(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            return true;
        }
        long wait = plugin.getConfig().getLong("adminmania.confirm-seconds", 10) * 1000L;
        String[] before = pendingConfirm.get(p.getUniqueId());
        Long when = pendingTime.get(p.getUniqueId());
        String[] lower = Arrays.stream(args).map(s -> s.toLowerCase(Locale.ROOT)).toArray(String[]::new);
        if (before != null && when != null && System.currentTimeMillis() - when <= wait && Arrays.equals(before, lower)) {
            pendingConfirm.remove(p.getUniqueId());
            pendingTime.remove(p.getUniqueId());
            return true;
        }
        pendingConfirm.put(p.getUniqueId(), lower);
        pendingTime.put(p.getUniqueId(), System.currentTimeMillis());
        sender.sendMessage(plugin.text("&e&lattenzione&7: questo comando non si può annullare. Per confermare riscrivi lo stesso comando entro &f"
                + (wait / 1000) + "&7 secondi."));
        return false;
    }

    /** Esegue il comando di /bm dando per un attimo i permessi che servono (il ruolo e' gia' stato controllato). */
    private void run(CommandSender sender, Command command, String label, String[] t) {
        PermissionAttachment att = grant(sender);
        try {
            legacy.run(sender, command, label, t);
        } finally {
            revoke(sender, att);
        }
    }

    private PermissionAttachment grant(CommandSender sender) {
        if (sender instanceof ConsoleCommandSender) {
            return null;
        }
        PermissionAttachment att = sender.addAttachment(plugin);
        for (String perm : List.of("blockmania.get", "blockmania.reload", "blockmania.stats", "blockmania.shop.edit",
                "blockmania.build", "blockmania.limits", "blockmania.tutorial.admin")) {
            att.setPermission(perm, true);
        }
        return att;
    }

    private void revoke(CommandSender sender, PermissionAttachment att) {
        if (att != null) {
            try {
                sender.removeAttachment(att);
            } catch (IllegalArgumentException ignored) {
                // gia' tolto
            }
        }
    }

    // ------------------------------------------------------------------
    //  Aiuto
    // ------------------------------------------------------------------

    private String visibleGroups(CommandSender sender) {
        List<String> out = new ArrayList<>();
        for (String g : GROUPS) {
            if (anyAllowed(sender, g)) {
                out.add(g);
            }
        }
        return String.join(", ", out);
    }

    /** I nomi dentro un gruppo che questo staffer puo' usare almeno in parte. */
    private String groupNames(CommandSender sender, String g) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, String> e : TO_LEGACY.entrySet()) {
            if (e.getKey().startsWith(g + ":")) {
                out.add(e.getKey().substring(g.length() + 1));
            }
        }
        return String.join(", ", out);
    }

    private String verbsOf(CommandSender sender, String g) {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("adminmania.actions." + g);
        List<String> ok = new ArrayList<>();
        if (s != null) {
            for (String verb : s.getKeys(false)) {
                if (allowed(sender, g, verb)) {
                    ok.add(verbLabel(verb));
                }
            }
        }
        return String.join(", ", ok);
    }

    private static String verbLabel(String verb) {
        return switch (verb) {
            case "see" -> "vedere";
            case "get" -> "dare";
            case "remove" -> "togliere";
            case "reset" -> "azzerare";
            case "edit" -> "impostare";
            case "resetall" -> "reset totale";
            case "list" -> "lista";
            case "cheque" -> "assegni";
            case "use" -> "usare";
            case "limits" -> "limitazioni";
            case "reload" -> "ricaricare";
            case "debug" -> "test grafici";
            case "tutorial" -> "tutorial";
            default -> verb;
        };
    }

    private static String example(String g) {
        return switch (g) {
            case "economy" -> "/adminmania economy coin get 100 Mario";
            case "profile" -> "/adminmania profile mine remove 2 Mario";
            case "items" -> "/adminmania items cheque coin 1 Mario";
            case "build" -> "/adminmania build mines createmines 3";
            case "shops" -> "/adminmania shops ammo";
            default -> "/adminmania system reload";
        };
    }

    private void help(CommandSender sender, String only) {
        sender.sendMessage(plugin.text("&6&ladminmania &8- &7il tuo ruolo: &f" + roleName(sender)));
        for (String g : GROUPS) {
            if (!only.isEmpty() && !only.equals(g)) {
                continue;
            }
            if (!anyAllowed(sender, g)) {
                continue;
            }
            sender.sendMessage(plugin.text("&e/adminmania " + g + " &7(" + groupNames(sender, g) + ")"));
            sender.sendMessage(plugin.text("&8  » &7puoi: &f" + verbsOf(sender, g)));
            if (!only.isEmpty()) {
                sender.sendMessage(plugin.text("&8  » &7esempio: &f" + example(g)));
            }
        }
        if (only.isEmpty()) {
            sender.sendMessage(plugin.text("&8scrivi /adminmania help <gruppo> per gli esempi. Italiano: economia, profilo, oggetti, costruzione, negozi, sistema"));
        }
    }

    // ------------------------------------------------------------------
    //  Tab complete: solo quello che il ruolo puo' fare
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (level(sender) <= 0 && !hasDept(sender, List.of("builder", "texturemaker", "developer"))) {
            return out;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("ticket")) {
            return plugin.getTickets().staffComplete(args);
        }
        if (args.length == 1) {
            out.add("help");
            out.add("ticket");
            for (String g : GROUPS) {
                if (anyAllowed(sender, g)) {
                    out.add(g);
                }
            }
        } else {
            String g = group(args[0]);
            if (args[0].equalsIgnoreCase("help")) {
                if (args.length == 2) {
                    out.addAll(GROUPS);
                }
            } else if (GROUPS.contains(g) && anyAllowed(sender, g)) {
                if (g.equals("shops")) {
                    String[] t = new String[args.length];
                    t[0] = "shop";
                    System.arraycopy(args, 1, t, 1, args.length - 1);
                    out.addAll(legacyTab(sender, command, alias, t));
                } else if (args.length == 2) {
                    for (Map.Entry<String, String> e : TO_LEGACY.entrySet()) {
                        if (e.getKey().startsWith(g + ":") && groupAllowsName(sender, g, e.getValue())) {
                            out.add(e.getKey().substring(g.length() + 1));
                        }
                    }
                } else {
                    String[] t = translate(Arrays.copyOf(args, args.length));
                    if (t != null && groupAllowsName(sender, g, t[0])) {
                        out.addAll(filterVerbs(sender, t, legacyTab(sender, command, alias, t)));
                    }
                }
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(typed));
        return out;
    }

    /** Il nome (ad esempio "coin") ha almeno un'azione permessa a questo ruolo? */
    private boolean groupAllowsName(CommandSender sender, String g, String legacyName) {
        String[] probe = {legacyName};
        String verbs = switch (legacyName) {
            case "coin", "blockcoin", "blockshell", "mine", "ball", "upgrade" -> "see get remove reset";
            case "power", "stamina", "ricompense" -> "see get remove reset edit";
            default -> "";
        };
        if (verbs.isEmpty()) {
            String v = verbOf(probe);
            return v == null ? anyAllowed(sender, g) : allowed(sender, LEGACY_GROUP.getOrDefault(legacyName, g), v);
        }
        String grp = LEGACY_GROUP.getOrDefault(legacyName, g);
        for (String v : verbs.split(" ")) {
            if (allowed(sender, grp, v)) {
                return true;
            }
        }
        return false;
    }

    /** Toglie dai suggerimenti dei verbi quelli che il ruolo non puo' fare. */
    private List<String> filterVerbs(CommandSender sender, String[] t, List<String> options) {
        if (t.length != 2) {
            return options;
        }
        String grp = LEGACY_GROUP.get(t[0]);
        if (grp == null) {
            return options;
        }
        List<String> keep = new ArrayList<>();
        for (String o : options) {
            String v = switch (t[0]) {
                case "stamina" -> o.equalsIgnoreCase("see") ? "see" : "edit";
                case "limits" -> o.equalsIgnoreCase("see") ? "see" : "limits";
                case "ricompense" -> "see";
                case "coin", "blockcoin", "blockshell", "mine", "ball", "upgrade", "power" -> action(o);
                default -> null;
            };
            if (v == null || allowed(sender, grp, v)) {
                keep.add(o);
            }
        }
        return keep;
    }

    private List<String> legacyTab(CommandSender sender, Command command, String alias, String[] t) {
        PermissionAttachment att = grant(sender);
        try {
            return legacy.legacyTab(sender, command, alias, t);
        } finally {
            revoke(sender, att);
        }
    }
}
