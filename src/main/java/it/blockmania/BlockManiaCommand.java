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

        if (sub.equals("get")) {
            if (!sender.hasPermission("blockmania.get")) {
                sender.sendMessage(plugin.message("no-permission"));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(plugin.message("usage"));
                return true;
            }

            LauncherItem li = plugin.getItem(args[1]);
            if (li == null) {
                sender.sendMessage(plugin.message("unknown-item", "%item%", args[1]));
                return true;
            }

            Player target;
            if (args.length >= 3) {
                target = Bukkit.getPlayerExact(args[2]);
            } else if (sender instanceof Player player) {
                target = player;
            } else {
                target = null;
            }
            if (target == null) {
                sender.sendMessage(plugin.message("player-not-found"));
                return true;
            }

            ItemStack stack = plugin.createItem(li);
            Map<Integer, ItemStack> leftover = target.getInventory().addItem(stack);
            for (ItemStack extra : leftover.values()) {
                target.getWorld().dropItem(target.getLocation(), extra);
            }

            target.sendMessage(plugin.message("given", "%item%", li.id()));
            if (!target.equals(sender)) {
                sender.sendMessage(plugin.message("given-other", "%item%", li.id(), "%player%", target.getName()));
            }
            return true;
        }

        sender.sendMessage(plugin.message("usage"));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            if (sender.hasPermission("blockmania.get")) {
                result.add("get");
            }
            if (sender.hasPermission("blockmania.reload")) {
                result.add("reload");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("get")
                && sender.hasPermission("blockmania.get")) {
            result.addAll(plugin.getItems().keySet());
        } else if (args.length == 3 && args[0].equalsIgnoreCase("get")
                && sender.hasPermission("blockmania.get")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                result.add(p.getName());
            }
        }
        String typed = args[args.length - 1].toLowerCase(Locale.ROOT);
        result.removeIf(s -> !s.toLowerCase(Locale.ROOT).startsWith(typed));
        return result;
    }
}
