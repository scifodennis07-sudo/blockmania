package it.blockmania;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Gestisce i numeri "virtuali" di ogni giocatore (palle, blockshell, blockcoin, coin)
 * e la bossbar che li mostra.
 *
 * Le blockshell si ricordano anche per livello del blocco che le ha date (1-1 = livello 1,
 * 2-1 = livello 2 ...): il giocatore vede solo il totale, il livello serve a calcolare
 * quanti coin valgono quando le vende.
 */
public final class AmmoManager {

    private static final String SHELL_LEGACY = "blockshell";
    private static final String SHELL_DATA = "shell_data";
    private static final String BLOCKCOIN = "blockcoin";
    private static final String COIN = "coin";

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

    // ---------- palle (con massimo) ----------

    /** Massimo di base per questo tipo di palla, dal config (ammo.<tipo>.max). */
    public int getAmmoBaseMax(String type) {
        return Math.max(1, plugin.getConfig().getInt("ammo." + type.toLowerCase(Locale.ROOT) + ".max", 20));
    }

    /** Massimo reale per questo giocatore: base + potenziamento capienza. */
    public int getAmmoMax(Player player, String type) {
        return getAmmoBaseMax(type) + plugin.getUpgrades().capacityBonus(player);
    }

    public int getAmmo(Player player, String type) {
        return getValue(player, "ammo_" + type);
    }

    public void setAmmo(Player player, String type, int amount) {
        setValue(player, "ammo_" + type, Math.min(amount, getAmmoMax(player, type)));
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

    /** Danno base che questo tipo di palla fa a un blocco. */
    public int getDamage(String type) {
        return plugin.getConfig().getInt("ammo." + type.toLowerCase(Locale.ROOT) + ".damage", 50);
    }

    // ---------- blockshell (con massimo, divise per livello) ----------

    public int getShellMax() {
        return Math.max(1, plugin.getConfig().getInt("blockshell.max", 10));
    }

    /** Le shell del giocatore: livello -> quantita', in ordine di livello crescente. */
    public Map<Integer, Integer> getShells(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        Map<Integer, Integer> map = new TreeMap<>();
        String raw = pdc.get(key(SHELL_DATA), PersistentDataType.STRING);
        if (raw != null) {
            for (String part : raw.split(",")) {
                String[] kv = part.split(":");
                if (kv.length != 2) {
                    continue;
                }
                try {
                    int level = Integer.parseInt(kv[0].trim());
                    int count = Integer.parseInt(kv[1].trim());
                    if (level > 0 && count > 0) {
                        map.merge(level, count, Integer::sum);
                    }
                } catch (NumberFormatException ignored) {
                    // riga rovinata: la saltiamo
                }
            }
        } else {
            // vecchie versioni: le shell erano un solo numero, le consideriamo di livello 1
            Integer legacy = pdc.get(key(SHELL_LEGACY), PersistentDataType.INTEGER);
            if (legacy != null) {
                if (legacy > 0) {
                    map.put(1, legacy);
                }
                pdc.remove(key(SHELL_LEGACY));
                saveShells(player, map);
            }
        }
        return map;
    }

    private void saveShells(Player player, Map<Integer, Integer> map) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        player.getPersistentDataContainer().set(key(SHELL_DATA), PersistentDataType.STRING, sb.toString());
    }

    /** Numero totale di shell. */
    public int getShell(Player player) {
        int sum = 0;
        for (int count : getShells(player).values()) {
            sum += count;
        }
        return sum;
    }

    /** Aggiunge shell di un livello, senza superare il massimo totale. */
    public void addShell(Player player, int level, int amount) {
        if (amount <= 0) {
            return;
        }
        int add = Math.min(amount, getShellMax() - getShell(player));
        if (add <= 0) {
            return;
        }
        Map<Integer, Integer> map = getShells(player);
        map.merge(Math.max(1, level), add, Integer::sum);
        saveShells(player, map);
    }

    /** Toglie shell partendo dal livello piu' basso. Ritorna quante ne ha tolte. */
    public int removeShells(Player player, int amount) {
        Map<Integer, Integer> map = getShells(player);
        int removed = 0;
        Iterator<Map.Entry<Integer, Integer>> it = map.entrySet().iterator();
        while (it.hasNext() && removed < amount) {
            Map.Entry<Integer, Integer> entry = it.next();
            int take = Math.min(entry.getValue(), amount - removed);
            removed += take;
            if (take >= entry.getValue()) {
                it.remove();
            } else {
                entry.setValue(entry.getValue() - take);
            }
        }
        saveShells(player, map);
        return removed;
    }

    /** Usato dai comandi: porta il totale a un valore. Quelle aggiunte hanno il livello command-level. */
    public void setShell(Player player, int value) {
        int target = Math.max(0, Math.min(value, getShellMax()));
        int current = getShell(player);
        if (target > current) {
            int level = Math.max(1, plugin.getConfig().getInt("shells.command-level", 1));
            addShell(player, level, target - current);
        } else if (target < current) {
            removeShells(player, current - target);
        }
    }

    /** Quanti coin vale una shell di questo livello. */
    public int shellValue(int level) {
        FileConfiguration c = plugin.getConfig();
        int override = c.getInt("shells.values.overrides." + level, -1);
        if (override >= 0) {
            return override;
        }
        int first = c.getInt("shells.values.first", 1);
        int step = c.getInt("shells.values.step", 5);
        return level <= 1 ? first : (level - 1) * step;
    }

    // ---------- blockcoin e coin (senza limite) ----------

    public int getBlockCoin(Player player) {
        return getValue(player, BLOCKCOIN);
    }

    public void setBlockCoin(Player player, int value) {
        setValue(player, BLOCKCOIN, value);
    }

    public int getCoin(Player player) {
        return getValue(player, COIN);
    }

    public void setCoin(Player player, int value) {
        setValue(player, COIN, value);
    }

    /** Miniera del giocatore (parte da 1). Serve a sapere fino a che livello puo' potenziare. */
    public int getMine(Player player) {
        return Math.max(1, getValue(player, "mine"));
    }

    public void setMine(Player player, int value) {
        setValue(player, "mine", value);
    }

    /** Saldo di una valuta per nome: coin, blockcoin oppure blockshell. */
    public int getBalance(Player player, String currency) {
        return switch (currency.toLowerCase(Locale.ROOT)) {
            case "blockcoin" -> getBlockCoin(player);
            case "blockshell" -> getShell(player);
            default -> getCoin(player);
        };
    }

    public void setBalance(Player player, String currency, int value) {
        switch (currency.toLowerCase(Locale.ROOT)) {
            case "blockcoin" -> setBlockCoin(player, value);
            case "blockshell" -> setShell(player, value);
            default -> setCoin(player, value);
        }
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
        return plugin.getLauncher(player.getInventory().getItemInMainHand()) != null;
    }

    private BossBar.Color parseColor(String name) {
        try {
            return BossBar.Color.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BossBar.Color.BLUE;
        }
    }

    private BossBar.Overlay parseOverlay(String name) {
        try {
            return BossBar.Overlay.valueOf(name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return BossBar.Overlay.PROGRESS;
        }
    }

    private void tick() {
        FileConfiguration c = plugin.getConfig();
        boolean enabled = c.getBoolean("bossbar.enabled", true);
        boolean always = c.getBoolean("bossbar.always-visible", true);
        String type = c.getString("bossbar.ammo-type", "standardball").toLowerCase(Locale.ROOT);
        List<String> templates = c.getStringList("bossbar.segments");
        if (templates.isEmpty()) {
            templates = List.of(c.getString("bossbar.text",
                    "&bstandardball: &f%ammo%&8/&f%ammo_max% &8| &6blockshell: &f%shell%/%shell_max% "
                            + "&8| &eblockcoin: &f%blockcoin% &8| &acoin: &f%coin%"));
        }
        BossBar.Color color = parseColor(c.getString("bossbar.color", "BLUE"));
        BossBar.Overlay overlay = parseOverlay(c.getString("bossbar.style", "PROGRESS"));
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

            Component title = plugin.bossSegments(templates, -1,
                    "%ammo%", String.valueOf(getAmmo(player, type)),
                    "%ammo_max%", String.valueOf(getAmmoMax(player, type)),
                    "%shell%", String.valueOf(getShell(player)),
                    "%shell_max%", String.valueOf(shellMax),
                    "%blockcoin%", String.valueOf(getBlockCoin(player)),
                    "%coin%", String.valueOf(getCoin(player)));

            // la barra resta sempre piena: conta solo il testo
            if (bar == null) {
                bar = BossBar.bossBar(title, 1.0f, color, overlay);
                bars.put(id, bar);
                player.showBossBar(bar);
            } else {
                bar.name(title);
                bar.progress(1.0f);
                bar.color(color);
                bar.overlay(overlay);
            }
        }
        bars.keySet().removeIf(id -> Bukkit.getPlayer(id) == null);
    }
}
