package it.blockmania;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Gestisce le palle "virtuali" di ogni giocatore e la bossbar che le mostra. */
public final class AmmoManager {

    private final BlockMania plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BukkitTask task;

    public AmmoManager(BlockMania plugin) {
        this.plugin = plugin;
    }

    private NamespacedKey key(String type) {
        return new NamespacedKey(plugin, "ammo_" + type.toLowerCase(Locale.ROOT));
    }

    public int getAmmo(Player player, String type) {
        return player.getPersistentDataContainer()
                .getOrDefault(key(type), PersistentDataType.INTEGER, 0);
    }

    public void setAmmo(Player player, String type, int amount) {
        player.getPersistentDataContainer()
                .set(key(type), PersistentDataType.INTEGER, Math.max(0, amount));
    }

    public void addAmmo(Player player, String type, int amount) {
        setAmmo(player, type, getAmmo(player, type) + amount);
    }

    /** Vero se nel config esiste un tipo di palla con questo nome (sezione "ammo"). */
    public boolean exists(String type) {
        return plugin.getConfig().isConfigurationSection("ammo." + type.toLowerCase(Locale.ROOT));
    }

    public Set<String> types() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("ammo");
        return s == null ? Set.of() : s.getKeys(false);
    }

    /** Danno che questo tipo di palla fa a un blocco. */
    public int getDamage(String type) {
        return plugin.getConfig().getInt("ammo." + type.toLowerCase(Locale.ROOT) + ".damage", 50);
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
        }
        for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            Player p = Bukkit.getPlayer(entry.getKey());
            if (p != null) {
                p.hideBossBar(entry.getValue());
            }
        }
        bars.clear();
    }

    private boolean isHoldingLauncher(Player player) {
        ItemStack inHand = player.getInventory().getItemInMainHand();
        if (inHand == null || !inHand.hasItemMeta()) {
            return false;
        }
        return inHand.getItemMeta().getPersistentDataContainer()
                .has(plugin.getItemKey(), PersistentDataType.STRING);
    }

    private BossBar.Color parseColor(String name) {
        try {
            return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BossBar.Color.BLUE;
        }
    }

    private void tick() {
        FileConfiguration c = plugin.getConfig();
        boolean enabled = c.getBoolean("bossbar.enabled", true);
        boolean always = c.getBoolean("bossbar.always-visible", true);
        String type = c.getString("bossbar.ammo-type", "standardball").toLowerCase(Locale.ROOT);
        String titleTemplate = c.getString("bossbar.title", "&bPalle: &f%ammo%");
        int max = Math.max(1, c.getInt("bossbar.max", 100));
        BossBar.Color color = parseColor(c.getString("bossbar.color", "BLUE"));

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            BossBar bar = bars.get(id);
            boolean visible = enabled && (always || isHoldingLauncher(player));

            if (!visible) {
                if (bar != null) {
                    player.hideBossBar(bar);
                    bars.remove(id);
                }
                continue;
            }

            int ammo = getAmmo(player, type);
            Component title = plugin.color(titleTemplate.replace("%ammo%", String.valueOf(ammo)));
            float progress = (float) Math.max(0.0, Math.min(1.0, ammo / (double) max));

            if (bar == null) {
                bar = BossBar.bossBar(title, progress, color, BossBar.Overlay.PROGRESS);
                bars.put(id, bar);
                player.showBossBar(bar);
            } else {
                bar.name(title);
                bar.progress(progress);
                bar.color(color);
            }
        }
        bars.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }
}
