package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
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

    /** Un numero del giocatore che si puo' leggere e modificare (palle, blockshell, blockcoin). */
    private record Stat(String name, ToIntFunction<Player> getter, BiConsumer<Player, Integer> setter, int max) {
    }

    private final BlockMania plugin;

    public BlockManiaCommand(BlockMania plugin) {
        this.plugin = plugin;
    }

    private Player resolveTarget(CommandSender sender, String name) {
        if (name != null) {
            return Bukkit.getPlayerExact(name);
        }
        return sender instanceof Player player ? player : null;
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

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            plugin.send(sender, "help");
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "get" -> getItem(sender, args);
            case "getblock" -> getBlock(sender, args);
            case "ball" -> ball(sender, args);
            case "blockcoin" -> counter(sender, args, "blockcoin");
            case "blockshell" -> counter(sender, args, "blockshell");
            default -> plugin.send(sender, "help");
        }
        return true;
    }

    // ---------- /blockmania reload ----------

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("blockmania.reload")) {
            plugin.send(sender, "no-permission");
            return;
        }
        plugin.reloadConfig();
        plugin.loadItems();
        plugin.send(sender, "reloaded", "%count%", String.valueOf(plugin.getItems().size()));
    }

    // ---------- /blockmania get <oggetto> [giocatore] ----------

    private void getItem(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.get")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.send(sender, "help");
            return;
        }
        LauncherItem li = plugin.getItem(args[1]);
        if (li == null) {
            plugin.send(sender, "unknown-item", "%item%", args[1]);
            return;
        }
        Player target = resolveTarget(sender, args.length >= 3 ? args[2] : null);
        if (target == null) {
            plugin.send(sender, "player-not-found");
            return;
        }
        deliver(sender, target, plugin.createItem(li), plugin.tiny(li.id()));
    }

    // ---------- /blockmania getblock <N-1> [giocatore] ----------

    private void getBlock(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.get")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            plugin.send(sender, "help");
            return;
        }
        Integer number = parseBlockNumber(args[1].toLowerCase(Locale.ROOT));
        int max = plugin.getMaxBlock();
        if (number == null || number < 1 || number > max) {
            plugin.send(sender, "block-not-found", "%max%", String.valueOf(max));
            return;
        }
        Player target = resolveTarget(sender, args.length >= 3 ? args[2] : null);
        if (target == null) {
            plugin.send(sender, "player-not-found");
            return;
        }
        deliver(sender, target, plugin.createBlockItem(number), number + "-1");
    }

    private void deliver(CommandSender sender, Player target, ItemStack stack, String name) {
        give(target, stack);
        plugin.send(target, "given", "%item%", name);
        if (!target.equals(sender)) {
            plugin.send(sender, "given-other", "%item%", name, "%player%", target.getName());
        }
    }

    // ---------- /blockmania ball <get|remove|see|reset> <tipo|all> [numero] [giocatore] ----------

    private void ball(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.stats")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (args.length < 3 || !ACTIONS.contains(args[1].toLowerCase(Locale.ROOT))) {
            plugin.send(sender, "help");
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        String type = args[2].toLowerCase(Locale.ROOT);
        AmmoManager ammo = plugin.getAmmo();

        List<String> chosen = new ArrayList<>();
        if (type.equals("all")) {
            chosen.addAll(ammo.types());
        } else if (ammo.exists(type)) {
            chosen.add(type);
        } else {
            plugin.send(sender, "unknown-ammo", "%type%", args[2]);
            return;
        }

        List<Stat> stats = new ArrayList<>();
        for (String t : chosen) {
            stats.add(new Stat(t, p -> ammo.getAmmo(p, t), (p, v) -> ammo.setAmmo(p, t, v), Integer.MAX_VALUE));
        }
        runStats(sender, action, stats, args, 3);
    }

    // ---------- /blockmania blockcoin|blockshell <get|remove|see|reset> [numero] [giocatore] ----------

    private void counter(CommandSender sender, String[] args, String which) {
        if (!sender.hasPermission("blockmania.stats")) {
            plugin.send(sender, "no-permission");
            return;
        }
        if (args.length < 2 || !ACTIONS.contains(args[1].toLowerCase(Locale.ROOT))) {
            plugin.send(sender, "help");
            return;
        }
        AmmoManager ammo = plugin.getAmmo();
        Stat stat;
        if (which.equals("blockshell")) {
            stat = new Stat("blockshell", ammo::getShell, ammo::setShell, ammo.getShellMax());
        } else {
            stat = new Stat("blockcoin", ammo::getCoin, ammo::setCoin, Integer.MAX_VALUE);
        }
        runStats(sender, args[1].toLowerCase(Locale.ROOT), List.of(stat), args, 2);
    }

    /**
     * Esegue get / remove / see / reset. amountIndex e' la posizione nel comando dove
     * si trova il numero. Per see e reset il numero non c'e' e li' c'e' direttamente il giocatore.
     */
    private void runStats(CommandSender sender, String action, List<Stat> stats, String[] args, int amountIndex) {
        boolean needsAmount = action.equals("get") || action.equals("remove");
        int amount = 0;
        int playerIndex = amountIndex;
        if (needsAmount) {
            if (args.length <= amountIndex) {
                plugin.send(sender, "help");
                return;
            }
            amount = parseAmount(args[amountIndex]);
            if (amount <= 0) {
                plugin.send(sender, "invalid-number");
                return;
            }
            playerIndex = amountIndex + 1;
        }

        Player target = resolveTarget(sender, args.length > playerIndex ? args[playerIndex] : null);
        if (target == null) {
            plugin.send(sender, "player-not-found");
            return;
        }

        for (Stat stat : stats) {
            int current = stat.getter().applyAsInt(target);

            // see: mostra solo il valore in chat, senza cambiare niente
            if (action.equals("see")) {
                plugin.send(sender, "see",
                        "%stat%", plugin.tiny(stat.name()),
                        "%player%", target.getName(),
                        "%value%", format(stat, current));
                continue;
            }

            int next;
            switch (action) {
                case "get" -> next = (int) Math.min((long) stat.max(), (long) current + amount);
                case "remove" -> next = Math.max(0, current - amount);
                default -> next = 0; // reset
            }
            if (next != current) {
                stat.setter().accept(target, next);
            }

            String[] replacements = {
                    "%stat%", plugin.tiny(stat.name()),
                    "%player%", target.getName(),
                    "%value%", format(stat, next)
            };
            plugin.send(sender, "stat-updated", replacements);
            if (!target.equals(sender)) {
                plugin.send(target, "stat-updated", replacements);
            }
        }
    }

    /** "12" oppure, per le cose con un massimo, "3/10". */
    private String format(Stat stat, int value) {
        return stat.max() == Integer.MAX_VALUE ? String.valueOf(value) : value + "/" + stat.max();
    }

    // ---------- completamento con TAB (senza liste di numeri) ----------

    private void addPlayers(List<String> result) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            result.add(p.getName());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        boolean canGet = sender.hasPermission("blockmania.get");
        boolean canStats = sender.hasPermission("blockmania.stats");

        if (args.length == 1) {
            if (canGet) {
                result.add("get");
                result.add("getblock");
            }
            if (canStats) {
                result.add("ball");
                result.add("blockcoin");
                result.add("blockshell");
            }
            if (sender.hasPermission("blockmania.reload")) {
                result.add("reload");
            }
        } else {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("get") && canGet) {
                if (args.length == 2) {
                    result.addAll(plugin.getItems().keySet());
                } else if (args.length == 3) {
                    addPlayers(result);
                }
            } else if (sub.equals("getblock") && canGet) {
                if (args.length == 3) {
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
                    if (args.length == (amount ? 5 : 4)) {
                        addPlayers(result);
                    }
                }
            } else if ((sub.equals("blockcoin") || sub.equals("blockshell")) && canStats) {
                if (args.length == 2) {
                    result.addAll(ACTIONS);
                } else {
                    boolean amount = args[1].equalsIgnoreCase("get") || args[1].equalsIgnoreCase("remove");
                    if (args.length == (amount ? 4 : 3)) {
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
