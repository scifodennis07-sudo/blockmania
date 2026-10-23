package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.ToIntFunction;

public final class BlockManiaCommand implements TabExecutor {

    private static final List<String> ACTIONS = List.of("get", "remove", "see", "reset");

    /** Un numero del giocatore che si puo' leggere e modificare (palle, blockshell, blockcoin, coin). */
    private record Stat(String name, ToIntFunction<Player> getter, BiConsumer<Player, Integer> setter,
                        ToIntFunction<Player> max) {
    }

    private final BlockMania plugin;

    public BlockManiaCommand(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Utilita'
    // ------------------------------------------------------------------

    /** La riga "uso" di un comando, presa dal config (help.commands.<chiave>.usage). */
    private String usage(String key) {
        return plugin.getConfig().getString("help.commands." + key + ".usage", "/bm " + key);
    }

    private String actions() {
        return String.join(", ", ACTIONS);
    }

    /** Trova il giocatore indicato all'indice dato, oppure il mittente se non e' indicato. */
    private Player resolveTarget(CommandSender sender, String[] args, int index, String usageKey) {
        if (args.length > index) {
            Player found = Bukkit.getPlayerExact(args[index]);
            if (found == null) {
                plugin.sendCommand(sender, "player-not-found", "%value%", args[index]);
            }
            return found;
        }
        if (sender instanceof Player self) {
            return self;
        }
        plugin.sendCommand(sender, "missing-player", "%usage%", usage(usageKey));
        return null;
    }

    /** Legge "3-1" e ritorna 3. Ritorna null se non e' scritto cosi'. */
    private Integer parseBlockNumber(String text) {
        if (!text.endsWith("-1")) {
            return null;
        }
        try {
            return Integer.parseInt(text.substring(0, text.length() - 2));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private int parseAmount(String text) {
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private void give(Player target, ItemStack stack) {
        Map<Integer, ItemStack> leftover = target.getInventory().addItem(stack);
        for (ItemStack extra : leftover.values()) {
            target.getWorld().dropItem(target.getLocation(), extra);
        }
    }

    // ------------------------------------------------------------------
    //  Comando principale
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help" -> help(sender);
            case "reload" -> reload(sender);
            case "get" -> getItem(sender, args);
            case "getblock" -> getBlock(sender, args, 1);
            case "block" -> block(sender, args);
            case "ball" -> ball(sender, args);
            case "blockcoin" -> counter(sender, args, "blockcoin");
            case "blockshell" -> counter(sender, args, "blockshell");
            case "coin" -> counter(sender, args, "coin");
            case "mine" -> counter(sender, args, "mine");
            case "upgrade" -> upgrade(sender, args);
            case "resetall" -> resetAll(sender, args);
            case "shop" -> shop(sender, args);
            case "items" -> items(sender, args);
            case "bgtest" -> bgTest(sender, args);
            case "guidebug" -> {
                if (!sender.hasPermission("blockmania.reload")) {
                    plugin.sendCommand(sender, "no-permission");
                } else {
                    for (String line : plugin.getUpgrades().debugTitle()) {
                        sender.sendMessage(plugin.text(line));
                    }
                }
            }
            default -> plugin.sendCommand(sender, "unknown-subcommand", "%value%", args[0]);
        }
        return true;
    }

    // ---------- /blockmania help ----------

    private void help(CommandSender sender) {
        var c = plugin.getConfig();
        sender.sendMessage(plugin.text(c.getString("help.header", "&6&lblockmania")));

        ConfigurationSection commands = c.getConfigurationSection("help.commands");
        if (commands != null) {
            for (String key : commands.getKeys(false)) {
                String path = "help.commands." + key;
                String permission = c.getString(path + ".permission", "");
                if (!permission.isEmpty() && !sender.hasPermission(permission)) {
                    continue;
                }
                sender.sendMessage(plugin.text(c.getString("help.usage-line", "&e%usage%"),
                        "%usage%", c.getString(path + ".usage", "/bm " + key)));
                sender.sendMessage(plugin.text(c.getString("help.description-line", "&7%description%"),
                        "%description%", plugin.tiny(c.getString(path + ".description", ""))));
                String example = c.getString(path + ".example", "");
                if (!example.isEmpty()) {
                    sender.sendMessage(plugin.text(c.getString("help.example-line", "&7%example%"),
                            "%example%", example));
                }
            }
        }

        String footer = c.getString("help.footer", "");
        if (!footer.isEmpty()) {
            sender.sendMessage(plugin.text(footer));
        }
    }

    // ---------- /blockmania reload ----------

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("blockmania.reload")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        plugin.reloadConfig();
        plugin.loadItems();
        plugin.getBackground().reload();
        plugin.reloadPalette();
        plugin.sendCommand(sender, "reloaded", "%count%", String.valueOf(plugin.getItems().size()));
    }

    // ---------- /blockmania get <oggetto> [giocatore] ----------

    private void getItem(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.get")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.sendCommand(sender, "missing-item", "%usage%", usage("get"),
                    "%items%", String.join(", ", plugin.getItems().keySet()));
            return;
        }
        LauncherItem li = plugin.getItem(args[1]);
        if (li == null) {
            plugin.sendCommand(sender, "unknown-item", "%value%", args[1],
                    "%items%", String.join(", ", plugin.getItems().keySet()));
            return;
        }
        Player target = resolveTarget(sender, args, 2, "get");
        if (target == null) {
            return;
        }
        deliver(sender, target, plugin.createItem(li, target), plugin.tiny(li.id()));
    }

    // ---------- /blockmania block <get|wand|pos1|pos2|set|replace|remove|undo> ----------

    private static final List<String> BLOCK_ACTIONS = List.of("get", "wand", "pos1", "pos2", "set", "replace", "remove", "undo");

    private String blockActions() {
        return String.join(", ", BLOCK_ACTIONS);
    }

    private void block(CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.sendCommand(sender, "block-sub-missing", "%usage%", usage("block"), "%actions%", blockActions());
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        if (!BLOCK_ACTIONS.contains(sub)) {
            plugin.sendCommand(sender, "block-sub-invalid", "%value%", args[1], "%actions%", blockActions());
            return;
        }
        if (sub.equals("get")) {
            getBlock(sender, args, 2);
            return;
        }

        // tutto il resto e' costruzione: serve il permesso e dev'essere un giocatore
        if (!sender.hasPermission("blockmania.build")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        BlockEdit edit = plugin.getBlockEdit();
        switch (sub) {
            case "wand" -> {
                Player target = resolveTarget(sender, args, 2, "block");
                if (target != null) {
                    give(target, edit.createWand());
                    plugin.send(target, "wand-given");
                }
            }
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player player)) {
                    plugin.sendCommand(sender, "player-only");
                    return;
                }
                edit.setPos(player, sub.equals("pos1") ? 1 : 2, player.getLocation());
            }
            case "undo" -> {
                if (!(sender instanceof Player player)) {
                    plugin.sendCommand(sender, "player-only");
                    return;
                }
                edit.undo(player);
            }
            default -> {
                if (!(sender instanceof Player player)) {
                    plugin.sendCommand(sender, "player-only");
                    return;
                }
                blockEdit(player, sub, args);
            }
        }
    }

    /** set <codice> | replace <da> <a> | remove [codice] */
    private void blockEdit(Player player, String sub, String[] args) {
        int need = sub.equals("replace") ? 2 : sub.equals("set") ? 1 : 0;
        // args: [block, sub, codice1, codice2]  quindi servono 2 + need parole
        if (args.length < 2 + need) {
            plugin.sendCommand(player, "missing-block", "%usage%", usage("block"),
                    "%max%", String.valueOf(plugin.getMaxBlock()));
            return;
        }
        Integer first = null;
        Integer second = null;
        if (need >= 1 || (sub.equals("remove") && args.length > 2)) {
            first = parseBlockCode(player, args[2]);
            if (first == null) {
                return;
            }
        }
        if (need == 2) {
            second = parseBlockCode(player, args[3]);
            if (second == null) {
                return;
            }
        }
        BlockEdit.Mode mode = switch (sub) {
            case "set" -> BlockEdit.Mode.SET;
            case "replace" -> BlockEdit.Mode.REPLACE;
            default -> BlockEdit.Mode.REMOVE;
        };
        plugin.getBlockEdit().run(player, mode, first, second);
    }

    /** Legge "3-1" oppure "3" e controlla che il blocco esista. Se no manda l'errore e ritorna null. */
    private Integer parseBlockCode(CommandSender sender, String text) {
        String t = text.toLowerCase(Locale.ROOT);
        int dash = t.indexOf('-');
        String number = dash >= 0 ? t.substring(0, dash) : t;
        int max = plugin.getMaxBlock();
        try {
            int n = Integer.parseInt(number);
            if (n >= 1 && n <= max) {
                return n;
            }
        } catch (NumberFormatException ignored) {
            // non e' un numero: errore qui sotto
        }
        plugin.sendCommand(sender, "invalid-block", "%value%", text, "%max%", String.valueOf(max));
        return null;
    }

    // ---------- /blockmania block get <N-1> [giocatore] ----------

    private void getBlock(CommandSender sender, String[] args, int codeIndex) {
        if (!sender.hasPermission("blockmania.get")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        if (args.length <= codeIndex) {
            plugin.sendCommand(sender, "missing-block", "%usage%", usage("block"),
                    "%max%", String.valueOf(plugin.getMaxBlock()));
            return;
        }
        Integer number = parseBlockCode(sender, args[codeIndex]);
        if (number == null) {
            return;
        }
        Player target = resolveTarget(sender, args, codeIndex + 1, "block");
        if (target == null) {
            return;
        }
        deliver(sender, target, plugin.createBlockItem(number), number + "-1");
    }

    private void deliver(CommandSender sender, Player target, ItemStack stack, String name) {
        give(target, stack);
        plugin.send(target, "given", "%item%", name);
        if (!target.equals(sender)) {
            plugin.sendCommand(sender, "given-other", "%item%", name, "%player%", target.getName());
        }
    }

    // ---------- /blockmania bgtest <testo> ----------

    /** Mostra un testo nella actionbar con lo sfondo, per controllare che sia centrato. */
    private void bgTest(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.reload")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        if (!(sender instanceof Player player)) {
            plugin.sendCommand(sender, "player-only");
            return;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("debug")) {
            sender.sendMessage(plugin.getBackground().describe());
            return;
        }
        String text = args.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)) : "coin 25";
        String legacy = "&f" + plugin.tiny(text);
        player.sendActionBar(plugin.withBackground(legacy));
    }

    // ---------- /blockmania items [giocatore] ----------

    private void items(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.get")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        Player target = resolveTarget(sender, args, 1, "items");
        if (target == null) {
            return;
        }
        plugin.getItemsGui().open(target);
    }

    // ---------- /blockmania shop <ammo|sell> ----------

    private void shop(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.shop.edit")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.sendCommand(sender, "missing-shop", "%usage%", usage("shop"));
            return;
        }
        String type = args[1].toLowerCase(Locale.ROOT);
        if (!ShopManager.TYPES.contains(type)) {
            plugin.sendCommand(sender, "invalid-shop", "%value%", args[1]);
            return;
        }
        Player target = resolveTarget(sender, args, 2, "shop");
        if (target == null) {
            return;
        }
        give(target, plugin.getShops().createShopItem(type));
        plugin.send(target, "shop-spawned", "%shop%", type);
    }

    // ---------- /blockmania ball <get|remove|see|reset> <tipo|all> [numero] [giocatore] ----------

    private void ball(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.stats")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.sendCommand(sender, "missing-action", "%usage%", usage("ball"), "%actions%", actions());
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            plugin.sendCommand(sender, "invalid-action", "%value%", args[1], "%actions%", actions());
            return;
        }

        AmmoManager ammo = plugin.getAmmo();
        String types = String.join(", ", ammo.types());
        if (args.length < 3) {
            plugin.sendCommand(sender, "missing-type", "%usage%", usage("ball"), "%types%", types);
            return;
        }
        String type = args[2].toLowerCase(Locale.ROOT);

        List<String> chosen = new ArrayList<>();
        if (type.equals("all")) {
            chosen.addAll(ammo.types());
        } else if (ammo.exists(type)) {
            chosen.add(type);
        } else {
            plugin.sendCommand(sender, "unknown-ammo", "%value%", args[2], "%types%", types);
            return;
        }

        List<Stat> stats = new ArrayList<>();
        for (String t : chosen) {
            stats.add(new Stat(t, p -> ammo.getAmmo(p, t), (p, v) -> ammo.setAmmo(p, t, v),
                    p -> ammo.getAmmoMax(p, t)));
        }
        runStats(sender, action, stats, args, 3, "ball");
    }

    // ---------- /blockmania upgrade <get|remove|see|reset> <potenziamento|all> [numero] [giocatore] ----------

    private void upgrade(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.stats")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.sendCommand(sender, "missing-action", "%usage%", usage("upgrade"), "%actions%", actions());
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            plugin.sendCommand(sender, "invalid-action", "%value%", args[1], "%actions%", actions());
            return;
        }
        UpgradeManager up = plugin.getUpgrades();
        String types = String.join(", ", up.ids());
        if (args.length < 3) {
            plugin.sendCommand(sender, "missing-type", "%usage%", usage("upgrade"), "%types%", types);
            return;
        }
        String type = args[2].toLowerCase(Locale.ROOT);
        List<String> chosen = new ArrayList<>();
        if (type.equals("all")) {
            chosen.addAll(up.ids());
        } else if (up.ids().contains(type)) {
            chosen.add(type);
        } else {
            plugin.sendCommand(sender, "unknown-upgrade", "%value%", args[2], "%types%", types);
            return;
        }
        List<Stat> stats = new ArrayList<>();
        for (String id : chosen) {
            stats.add(new Stat(id, p -> up.getLevel(p, id), (p, v) -> up.setLevelAdmin(p, id, v),
                    p -> up.maxLevel(id)));
        }
        runStats(sender, action, stats, args, 3, "upgrade");
    }

    // ---------- /blockmania resetall [giocatore] ----------

    private void resetAll(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.stats")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        Player target = resolveTarget(sender, args, 1, "resetall");
        if (target == null) {
            return;
        }
        AmmoManager ammo = plugin.getAmmo();
        for (String t : ammo.types()) {
            ammo.setAmmo(target, t, 0);
        }
        ammo.setShell(target, 0);
        ammo.setBlockCoin(target, 0);
        ammo.setCoin(target, 0);
        ammo.setMine(target, 0);
        plugin.getUpgrades().resetLevels(target);
        plugin.sendCommand(sender, "reset-all", "%player%", target.getName());
        if (!target.equals(sender)) {
            plugin.send(target, "reset-all", "%player%", target.getName());
        }
    }

    // ---------- /blockmania blockcoin|blockshell|coin <get|remove|see|reset> [numero] [giocatore] ----------

    private void counter(CommandSender sender, String[] args, String which) {
        if (!sender.hasPermission("blockmania.stats")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.sendCommand(sender, "missing-action", "%usage%", usage(which), "%actions%", actions());
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!ACTIONS.contains(action)) {
            plugin.sendCommand(sender, "invalid-action", "%value%", args[1], "%actions%", actions());
            return;
        }

        AmmoManager ammo = plugin.getAmmo();
        Stat stat = switch (which) {
            case "blockshell" -> new Stat("blockshell", ammo::getShell, ammo::setShell, p -> ammo.getShellMax());
            case "blockcoin" -> new Stat("blockcoin", ammo::getBlockCoin, ammo::setBlockCoin, p -> Integer.MAX_VALUE);
            case "mine" -> new Stat("mine", ammo::getMine, ammo::setMine, p -> Integer.MAX_VALUE);
            default -> new Stat("coin", ammo::getCoin, ammo::setCoin, p -> Integer.MAX_VALUE);
        };
        runStats(sender, action, List.of(stat), args, 2, which);
    }

    /**
     * Esegue get / remove / see / reset. amountIndex e' la posizione nel comando dove
     * si trova il numero. Per see e reset il numero non c'e' e li' c'e' direttamente il giocatore.
     */
    private void runStats(CommandSender sender, String action, List<Stat> stats,
                          String[] args, int amountIndex, String usageKey) {
        boolean needsAmount = action.equals("get") || action.equals("remove");
        int amount = 0;
        int playerIndex = amountIndex;
        if (needsAmount) {
            if (args.length <= amountIndex) {
                plugin.sendCommand(sender, "missing-number", "%usage%", usage(usageKey), "%action%", action);
                return;
            }
            amount = parseAmount(args[amountIndex]);
            if (amount <= 0) {
                plugin.sendCommand(sender, "invalid-number", "%value%", args[amountIndex]);
                return;
            }
            playerIndex = amountIndex + 1;
        }

        Player target = resolveTarget(sender, args, playerIndex, usageKey);
        if (target == null) {
            return;
        }

        for (Stat stat : stats) {
            int current = stat.getter().applyAsInt(target);

            // see: mostra solo il valore in chat, senza cambiare niente
            if (action.equals("see")) {
                plugin.sendCommand(sender, "see",
                        "%stat%", plugin.tiny(stat.name()),
                        "%player%", target.getName(),
                        "%value%", format(stat, target, current));
                continue;
            }

            int next;
            switch (action) {
                case "get" -> next = (int) Math.min((long) stat.max().applyAsInt(target), (long) current + amount);
                case "remove" -> next = Math.max(0, current - amount);
                default -> next = 0; // reset
            }
            if (next != current) {
                stat.setter().accept(target, next);
            }

            String[] replacements = {
                    "%stat%", plugin.tiny(stat.name()),
                    "%player%", target.getName(),
                    "%value%", format(stat, target, next)
            };
            plugin.sendCommand(sender, "stat-updated", replacements);
            if (!target.equals(sender)) {
                plugin.send(target, "stat-updated", replacements);
            }
        }
    }

    /** "12" oppure, per le cose con un massimo, "3/10". */
    private String format(Stat stat, Player target, int value) {
        int max = stat.max().applyAsInt(target);
        return max == Integer.MAX_VALUE ? String.valueOf(value) : value + "/" + max;
    }

    // ------------------------------------------------------------------
    //  Completamento con TAB (niente liste di numeri, solo un suggerimento)
    // ------------------------------------------------------------------

    private void addPlayers(List<String> result) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            result.add(p.getName());
        }
    }

    private void addNumberHint(List<String> result) {
        result.add(plugin.getConfig().getString("tab.number", "<numero>"));
    }

    /** Qualche esempio di codice blocco da suggerire con TAB (config: tab.block-codes). */
    private List<String> blockCodes() {
        List<String> codes = plugin.getConfig().getStringList("tab.block-codes");
        return codes.isEmpty() ? List.of("1-1", "2-1", "3-1") : codes;
    }

    private void blockTab(CommandSender sender, String[] args, List<String> result) {
        boolean canGet = sender.hasPermission("blockmania.get");
        boolean canBuild = sender.hasPermission("blockmania.build");
        if (args.length == 2) {
            if (canGet) {
                result.add("get");
            }
            if (canBuild) {
                result.addAll(List.of("wand", "pos1", "pos2", "set", "replace", "remove", "undo"));
            }
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "get" -> {
                if (canGet) {
                    if (args.length == 3) {
                        result.addAll(blockCodes());
                    } else if (args.length == 4) {
                        addPlayers(result);
                    }
                }
            }
            case "set", "remove" -> {
                if (canBuild && args.length == 3) {
                    result.addAll(blockCodes());
                }
            }
            case "replace" -> {
                if (canBuild && (args.length == 3 || args.length == 4)) {
                    result.addAll(blockCodes());
                }
            }
            case "wand" -> {
                if (canBuild && args.length == 3) {
                    addPlayers(result);
                }
            }
            default -> {
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        boolean canGet = sender.hasPermission("blockmania.get");
        boolean canStats = sender.hasPermission("blockmania.stats");

        if (args.length == 1) {
            result.add("help");
            if (canGet) {
                result.add("get");
                result.add("block");
                result.add("items");
            }
            if (canStats) {
                result.add("ball");
                result.add("blockcoin");
                result.add("blockshell");
                result.add("coin");
                result.add("mine");
                result.add("upgrade");
                result.add("resetall");
            }
            if (sender.hasPermission("blockmania.shop.edit")) {
                result.add("shop");
            }
            if (sender.hasPermission("blockmania.reload")) {
                result.add("bgtest");
                result.add("guidebug");
                result.add("reload");
            }
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("shop") && sender.hasPermission("blockmania.shop.edit")) {
                if (args.length == 2) {
                    result.add("ammo");
                    result.add("sell");
                } else if (args.length == 3) {
                    addPlayers(result);
                }
            } else if (sub.equals("get") && canGet) {
                if (args.length == 2) {
                    result.addAll(plugin.getItems().keySet());
                } else if (args.length == 3) {
                    addPlayers(result);
                }
            } else if (sub.equals("items") && canGet) {
                if (args.length == 2) {
                    addPlayers(result);
                }
            } else if (sub.equals("block")) {
                blockTab(sender, args, result);
            } else if (sub.equals("getblock") && canGet) {
                if (args.length == 3) {
                    result.addAll(blockCodes());
                } else if (args.length == 4) {
                    addPlayers(result);
                }
            } else if (sub.equals("ball") && canStats) {
                if (args.length == 2) {
                    result.addAll(ACTIONS);
                } else if (args.length == 3) {
                    result.addAll(plugin.getAmmo().types());
                    result.add("all");
                } else {
                    boolean amount = args[1].equalsIgnoreCase("get") || args[1].equalsIgnoreCase("remove");
                    if (amount && args.length == 4) {
                        addNumberHint(result);
                    } else if (args.length == (amount ? 5 : 4)) {
                        addPlayers(result);
                    }
                }
            } else if (sub.equals("upgrade") && canStats) {
                if (args.length == 2) {
                    result.addAll(ACTIONS);
                } else if (args.length == 3) {
                    result.addAll(plugin.getUpgrades().ids());
                    result.add("all");
                } else {
                    boolean amount = args[1].equalsIgnoreCase("get") || args[1].equalsIgnoreCase("remove");
                    if (amount && args.length == 4) {
                        addNumberHint(result);
                    } else if (args.length == (amount ? 5 : 4)) {
                        addPlayers(result);
                    }
                }
            } else if (sub.equals("resetall") && canStats) {
                if (args.length == 2) {
                    addPlayers(result);
                }
            } else if ((sub.equals("blockcoin") || sub.equals("blockshell") || sub.equals("coin") || sub.equals("mine")) && canStats) {
                if (args.length == 2) {
                    result.addAll(ACTIONS);
                } else {
                    boolean amount = args[1].equalsIgnoreCase("get") || args[1].equalsIgnoreCase("remove");
                    if (amount && args.length == 3) {
                        addNumberHint(result);
                    } else if (args.length == (amount ? 4 : 3)) {
                        addPlayers(result);
                    }
                }
            }
        }

        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        result.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(typed));
        return result;
    }
}
