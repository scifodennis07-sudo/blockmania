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

public final class BlockManiaCommand implements TabExecutor {

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

    /** Legge "3-1" e ritorna 3. Ritorna null se non e' una prima variante valida. */
    private Integer parseBlockName(String text) {
        if (!text.endsWith("-1")) {
            return null;
        }
        try {
            return Integer.parseInt(text.substring(0, text.length() - 2));
        } catch (NumberFormatException ex) {
            return null;
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
            sender.sendMessage(plugin.message("usage"));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        if (sub.equals("reload")) {
            if (!sender.hasPermission("blockmania.reload")) {
                sender.sendMessage(plugin.message("no-permission"));
                return true;
            }
            plugin.reloadConfig();
            plugin.loadItems();
            sender.sendMessage(plugin.message("reloaded", "%count%", String.valueOf(plugin.getItems().size())));
            return true;
        }

        if (!sub.equals("get")) {
            sender.sendMessage(plugin.message("usage"));
            return true;
        }

        if (!sender.hasPermission("blockmania.get")) {
            sender.sendMessage(plugin.message("no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(plugin.message("usage"));
            return true;
        }

        String what = args[1].toLowerCase(Locale.ROOT);
        AmmoManager ammo = plugin.getAmmo();

        // /blockmania get standardball <numero> [giocatore]
        if (ammo.exists(what)) {
            if (args.length < 3) {
                sender.sendMessage(plugin.message("usage"));
                return true;
            }
            int amount;
            try {
                amount = Integer.parseInt(args[2]);
            } catch (NumberFormatException ex) {
                amount = -1;
            }
            if (amount <= 0) {
                sender.sendMessage(plugin.message("invalid-number"));
                return true;
            }
            Player target = resolveTarget(sender, args.length >= 4 ? args[3] : null);
            if (target == null) {
                sender.sendMessage(plugin.message("player-not-found"));
                return true;
            }
            ammo.addAmmo(target, what, amount);
            String total = String.valueOf(ammo.getAmmo(target, what));
            target.sendMessage(plugin.message("ammo-given",
                    "%amount%", String.valueOf(amount), "%type%", what, "%total%", total));
            if (!target.equals(sender)) {
                sender.sendMessage(plugin.message("ammo-given-other",
                        "%amount%", String.valueOf(amount), "%type%", what,
                        "%player%", target.getName(), "%total%", total));
            }
            return true;
        }

        // /blockmania get <oggetto|N-1> [giocatore]
        ItemStack stack = null;
        LauncherItem li = plugin.getItem(what);
        if (li != null) {
            stack = plugin.createItem(li);
        } else {
            Integer number = parseBlockName(what);
            if (number != null && plugin.getEnabledBlocks().contains(number)) {
                stack = plugin.createBlockItem(number);
            }
        }
        if (stack == null) {
            sender.sendMessage(plugin.message("unknown-item", "%item%", args[1]));
            return true;
        }

        Player target = resolveTarget(sender, args.length >= 3 ? args[2] : null);
        if (target == null) {
            sender.sendMessage(plugin.message("player-not-found"));
            return true;
        }

        give(target, stack);
        target.sendMessage(plugin.message("given", "%item%", what));
        if (!target.equals(sender)) {
            sender.sendMessage(plugin.message("given-other", "%item%", what, "%player%", target.getName()));
        }
        return true;
    }

    private void addPlayers(List<String> result) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            result.add(p.getName());
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        boolean canGet = sender.hasPermission("blockmania.get");

        if (args.length == 1) {
            if (canGet) {
                result.add("get");
            }
            if (sender.hasPermission("blockmania.reload")) {
                result.add("reload");
            }
        } else if (args[0].equalsIgnoreCase("get") && canGet) {
            AmmoManager ammo = plugin.getAmmo();
            if (args.length == 2) {
                result.addAll(ammo.types());
                result.addAll(plugin.getItems().keySet());
                for (int n : plugin.getEnabledBlocks()) {
                    result.add(n + "-1");
                }
            } else if (args.length == 3) {
                if (ammo.exists(args[1].toLowerCase(Locale.ROOT))) {
                    result.addAll(List.of("10", "50", "100"));
                } else {
                    addPlayers(result);
                }
            } else if (args.length == 4 && ammo.exists(args[1].toLowerCase(Locale.ROOT))) {
                addPlayers(result);
            }
        }

        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        result.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(typed));
        return result;
    }
}
