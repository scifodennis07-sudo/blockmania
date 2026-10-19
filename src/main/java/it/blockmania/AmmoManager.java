package it.blockmania;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Gestisce i numeri "virtuali" di ogni giocatore (palle, blockshell, blockcoin)
 * e la bossbar che li mostra.
 */
public final class AmmoManager {

    private static final String SHELL = "blockshell";
    private static final String COIN = "blockcoin";

    private final BlockMania plugin;
    private final Map<UUID, BossBar> bars = new HashMap<>();
    private BukkitTask task;

    public AmmoManager(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ---------- valori salvati sul giocatore ----------

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name.toLowerCase(Locale.ROOT));
    }

    private int getValue(Player player, String name) {
        return player.getPersistentDataContainer()
                .getOrDefault(key(name), PersistentDataType.INTEGER, 0);
    }

    private void setValue(Player player, String name, int value) {
        player.getPersistentDataContainer()
                .set(key(name), PersistentDataType.INTEGER, Math.max(0, value));
    }

    // ---------- palle ----------

    public int getAmmo(Player player, String type) {
        return getValue(player, "ammo_" + type);
    }

    public void setAmmo(Player player, String type, int amount) {
        setValue(player, "ammo_" + type, amount);
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

    // ---------- blockshell (con massimo) e blockcoin (senza limite) ----------

    public int getShellMax() {
        return Math.max(1, plugin.getConfig().getInt("blockshell.max", 10));
    }

    public int getShell(Player player) {
        return getValue(player, SHELL);
    }

    public void setShell(Player player, int value) {
        setValue(player, SHELL, Math.min(value, getShellMax()));
    }

    public int getCoin(Player player) {
        return getValue(player, COIN);
    }

    public void setCoin(Player player, int value) {
        setValue(player, COIN, value);
    }

    // ---------- bossbar ----------

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
        var inHand = player.getInventory().getItemInMainHand();
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
        String template = c.getString("bossbar.text",
                "&bstandardball: &f%ammo% &8| &6blockshell: &f%shell%/%shell_max% &8| &eblockcoin: &f%coin%");
        BossBar.Color color = parseColor(c.getString("bossbar.color", "BLUE"));
        int shellMax = getShellMax();

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

            String text = template
                    .replace("%ammo%", String.valueOf(getAmmo(player, type)))
                    .replace("%shell%", String.valueOf(getShell(player)))
                    .replace("%shell_max%", String.valueOf(shellMax))
                    .replace("%coin%", String.valueOf(getCoin(player)));
            Component title = plugin.color(text);

            // la barra resta sempre piena: conta solo il testo
            if (bar == null) {
                bar = BossBar.bossBar(title, 1.0f, color, BossBar.Overlay.PROGRESS);
                bars.put(id, bar);
                player.showBossBar(bar);
            } else {
                bar.name(title);
                bar.progress(1.0f);
                bar.color(color);
            }
        }
        bars.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }
}
