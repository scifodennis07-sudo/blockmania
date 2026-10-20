package it.blockmania;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Potenziamenti del lanciapalle (danno, velocita', ricarica) e la GUI a 6 righe per comprarli.
 * I livelli sono salvati sul giocatore e valgono per tutti i suoi lanciapalle.
 */
public final class UpgradeManager implements Listener {

    public static final List<String> IDS = List.of("damage", "cooldown", "speed");
    private static final int SIZE = 54;

    /** Serve a riconoscere la nostra GUI tra tutti gli inventari. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final String launcherId;

        Holder(String launcherId) {
            this.launcherId = launcherId;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BlockMania plugin;

    public UpgradeManager(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ---------- livelli e valori ----------

    private NamespacedKey key(String id) {
        return new NamespacedKey(plugin, "upgrade_" + id);
    }

    public int getLevel(Player player, String id) {
        return player.getPersistentDataContainer().getOrDefault(key(id), PersistentDataType.INTEGER, 0);
    }

    private void setLevel(Player player, String id, int level) {
        player.getPersistentDataContainer().set(key(id), PersistentDataType.INTEGER, Math.max(0, level));
    }

    public int maxLevel(String id) {
        return Math.max(0, plugin.getConfig().getInt("upgrades." + id + ".max-level", 10));
    }

    private double perLevel(String id) {
        return plugin.getConfig().getDouble("upgrades." + id + ".per-level", 0);
    }

    private int cost(String id, int level) {
        int base = plugin.getConfig().getInt("upgrades." + id + ".cost-base", 5);
        int increase = plugin.getConfig().getInt("upgrades." + id + ".cost-increase", 5);
        return Math.max(0, base + increase * level);
    }

    private String currency() {
        return plugin.getConfig().getString("upgrades.currency", "coin").toLowerCase(Locale.ROOT);
    }

    private int damageAt(int level, String ammoType) {
        return plugin.getAmmo().getDamage(ammoType) + (int) Math.round(level * perLevel("damage"));
    }

    private double cooldownAt(int level, double base) {
        double min = Math.min(base, plugin.getConfig().getDouble("upgrades.cooldown.min", 0.1));
        return Math.max(min, base - level * perLevel("cooldown"));
    }

    private double speedAt(int level, double base) {
        return base + level * perLevel("speed");
    }

    /** Danno reale delle palle di questo giocatore. */
    public int damage(Player player, String ammoType) {
        return damageAt(getLevel(player, "damage"), ammoType);
    }

    /** Ricarica reale (in secondi) di questo giocatore. */
    public double cooldown(Player player, double base) {
        return cooldownAt(getLevel(player, "cooldown"), base);
    }

    /** Velocita' reale delle palle di questo giocatore. */
    public double speed(Player player, double base) {
        return speedAt(getLevel(player, "speed"), base);
    }

    private String valueText(String id, int level, LauncherItem li) {
        return switch (id) {
            case "damage" -> String.valueOf(damageAt(level, li.ammoType()));
            case "cooldown" -> BlockMania.fmt(Math.round(cooldownAt(level, li.cooldownSeconds()) * 100) / 100.0);
            default -> BlockMania.fmt(Math.round(speedAt(level, li.speed()) * 100) / 100.0);
        };
    }

    // ---------- oggetti della GUI ----------

    private Material material(String path, Material fallback) {
        Material m = Material.matchMaterial(plugin.getConfig().getString(path + ".material", fallback.name()));
        return m == null || !m.isItem() ? fallback : m;
    }

    private ItemStack makeItem(String path, Material fallback, List<String> loreLines, String[] r) {
        ItemStack stack = new ItemStack(material(path, fallback));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text(plugin.getConfig().getString(path + ".name", " "), r));
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(plugin.text(line, r));
            }
            meta.lore(lore);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private int slot(String path, int fallback) {
        int s = plugin.getConfig().getInt(path + ".slot", fallback);
        return s >= 0 && s < SIZE ? s : fallback;
    }

    private ItemStack upgradeItem(Player player, String id, LauncherItem li) {
        int level = getLevel(player, id);
        int max = maxLevel(id);
        boolean maxed = level >= max;
        String path = "upgrades." + id;
        String[] r = {
                "%level%", String.valueOf(level),
                "%max%", String.valueOf(max),
                "%current%", valueText(id, level, li),
                "%next%", maxed ? "-" : valueText(id, level + 1, li),
                "%cost%", maxed ? "-" : String.valueOf(cost(id, level)),
                "%currency%", plugin.tiny(currency()),
                "%balance%", String.valueOf(plugin.getAmmo().getBalance(player, currency()))
        };
        List<String> lore = plugin.getConfig().getStringList(path + (maxed ? ".lore-max" : ".lore"));
        return makeItem(path, Material.STONE, lore, r);
    }

    private void fill(Inventory inv, Player player, LauncherItem li) {
        AmmoManager ammo = plugin.getAmmo();
        String ammoType = plugin.getConfig().getString("bossbar.ammo-type", "standardball");
        String[] general = {
                "%coin%", String.valueOf(ammo.getCoin(player)),
                "%blockcoin%", String.valueOf(ammo.getBlockCoin(player)),
                "%shell%", String.valueOf(ammo.getShell(player)),
                "%shell_max%", String.valueOf(ammo.getShellMax()),
                "%ammo%", String.valueOf(ammo.getAmmo(player, ammoType))
        };

        ItemStack filler = makeItem("upgrades.gui.filler", Material.BLACK_STAINED_GLASS_PANE, List.of(), general);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, filler);
        }

        inv.setItem(slot("upgrades.gui.info", 4), makeItem("upgrades.gui.info", Material.GOLD_INGOT,
                plugin.getConfig().getStringList("upgrades.gui.info.lore"), general));
        inv.setItem(slot("upgrades.gui.close", 49), makeItem("upgrades.gui.close", Material.BARRIER,
                plugin.getConfig().getStringList("upgrades.gui.close.lore"), general));

        for (String id : IDS) {
            inv.setItem(slot("upgrades." + id, 20), upgradeItem(player, id, li));
        }
    }

    // ---------- aprire e usare la GUI ----------

    public void open(Player player, LauncherItem li) {
        Holder holder = new Holder(li.id());
        String title = plugin.getConfig().getString("upgrades.gui.title", "&8potenziamenti");
        Inventory inv = Bukkit.createInventory(holder, SIZE, plugin.text(title));
        holder.inventory = inv;
        fill(inv, player, li);
        player.openInventory(inv);
        plugin.playConfiguredSound(player.getLocation(), "upgrades.gui.sounds.open");
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true); // dentro la GUI non si puo' spostare niente
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !(clicked.getHolder() instanceof Holder)) {
            return;
        }

        LauncherItem li = plugin.getItem(holder.launcherId);
        if (li == null) {
            player.closeInventory();
            return;
        }

        int slot = event.getSlot();
        if (slot == slot("upgrades.gui.close", 49)) {
            player.closeInventory();
            return;
        }
        for (String id : IDS) {
            if (slot == slot("upgrades." + id, 20)) {
                buy(player, id, li, holder.inventory);
                return;
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void buy(Player player, String id, LauncherItem li, Inventory inv) {
        String label = plugin.tiny(plugin.getConfig().getString("upgrades." + id + ".label", id));
        int level = getLevel(player, id);

        if (level >= maxLevel(id)) {
            plugin.send(player, "upgrade-max", "%upgrade%", label);
            plugin.playConfiguredSound(player.getLocation(), "upgrades.gui.sounds.error");
            return;
        }

        int price = cost(id, level);
        AmmoManager ammo = plugin.getAmmo();
        int balance = ammo.getBalance(player, currency());
        if (balance < price) {
            plugin.send(player, "upgrade-no-funds",
                    "%cost%", String.valueOf(price), "%balance%", String.valueOf(balance),
                    "%currency%", plugin.tiny(currency()));
            plugin.playConfiguredSound(player.getLocation(), "upgrades.gui.sounds.error");
            return;
        }

        ammo.setBalance(player, currency(), balance - price);
        setLevel(player, id, level + 1);
        plugin.send(player, "upgrade-bought",
                "%upgrade%", label, "%level%", String.valueOf(level + 1),
                "%cost%", String.valueOf(price), "%currency%", plugin.tiny(currency()));
        plugin.playConfiguredSound(player.getLocation(), "upgrades.gui.sounds.success");

        fill(inv, player, li);
        refreshLaunchers(player);
    }

    /** Rifa nome e lore dei lanciapalle che il giocatore ha addosso, con i valori potenziati. */
    public void refreshLaunchers(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack item = inv.getItem(i);
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            String id = item.getItemMeta().getPersistentDataContainer()
                    .get(plugin.getItemKey(), PersistentDataType.STRING);
            LauncherItem li = plugin.getItem(id);
            if (li == null) {
                continue;
            }
            ItemStack fresh = plugin.createItem(li, player);
            fresh.setAmount(item.getAmount());
            inv.setItem(i, fresh);
        }
    }
}
