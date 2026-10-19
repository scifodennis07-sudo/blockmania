package it.blockmania;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BlockMania extends JavaPlugin {

    private NamespacedKey itemKey;
    private NamespacedKey blockKey;
    private NamespacedKey ammoKey;
    private final Map<String, LauncherItem> items = new LinkedHashMap<>();
    private AmmoManager ammo;
    private BlockManager blocks;

    @Override
    public void onEnable() {
        itemKey = new NamespacedKey(this, "launcher");
        blockKey = new NamespacedKey(this, "block");
        ammoKey = new NamespacedKey(this, "ammo_type");

        saveDefaultConfig();
        // aggiunge al config eventuali voci nuove senza toccare quelle che hai cambiato
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadItems();

        ammo = new AmmoManager(this);
        blocks = new BlockManager(this);
        blocks.load();
        ammo.start();

        PluginCommand command = getCommand("blockmania");
        if (command != null) {
            BlockManiaCommand handler = new BlockManiaCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        getServer().getPluginManager().registerEvents(new LauncherListener(this), this);
        getServer().getPluginManager().registerEvents(new CustomBlockListener(this), this);
        getServer().getScheduler().runTaskTimer(this, () -> blocks.saveIfDirty(), 1200L, 1200L);

        getLogger().info("BlockMania attivo. Oggetti: " + items.size()
                + ", blocchi in gioco: " + blocks.count());
    }

    @Override
    public void onDisable() {
        if (ammo != null) {
            ammo.stop();
        }
        if (blocks != null) {
            blocks.save();
        }
    }

    /** Rilegge gli oggetti dal config.yml. */
    public void loadItems() {
        items.clear();
        ConfigurationSection section = getConfig().getConfigurationSection("items");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection s = section.getConfigurationSection(key);
            if (s == null) {
                continue;
            }
            String id = key.toLowerCase(Locale.ROOT);

            String materialName = s.getString("material", "STICK");
            Material material = Material.matchMaterial(materialName);
            if (material == null || !material.isItem()) {
                getLogger().warning("Oggetto '" + key + "': material '" + materialName
                        + "' non valido, uso STICK.");
                material = Material.STICK;
            }

            Integer cmd = s.contains("custom-model-data") ? s.getInt("custom-model-data") : null;

            items.put(id, new LauncherItem(
                    id,
                    material,
                    s.getString("name", key),
                    s.getStringList("lore"),
                    cmd,
                    s.getDouble("speed", 1.5),
                    Math.max(0, s.getDouble("cooldown", 0.5)),
                    s.getString("ammo", "standardball").toLowerCase(Locale.ROOT)));
        }
    }

    public Map<String, LauncherItem> getItems() {
        return items;
    }

    public LauncherItem getItem(String id) {
        return id == null ? null : items.get(id.toLowerCase(Locale.ROOT));
    }

    public NamespacedKey getItemKey() {
        return itemKey;
    }

    public NamespacedKey getBlockKey() {
        return blockKey;
    }

    public NamespacedKey getAmmoKey() {
        return ammoKey;
    }

    public AmmoManager getAmmo() {
        return ammo;
    }

    public BlockManager getBlocks() {
        return blocks;
    }

    /** Numero massimo di blocco ottenibile (blocks.max nel config, mai oltre il limite del pack). */
    public int getMaxBlock() {
        int configured = getConfig().getInt("blocks.max", BlockManager.MAX_BLOCKS);
        return Math.max(1, Math.min(BlockManager.MAX_BLOCKS, configured));
    }

    /** Crea l'oggetto lanciapalle. */
    public ItemStack createItem(LauncherItem li) {
        ItemStack stack = new ItemStack(li.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(color(li.name()));
        if (!li.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : li.lore()) {
                lore.add(color(line));
            }
            meta.lore(lore);
        }
        if (li.customModelData() != null) {
            meta.setCustomModelData(li.customModelData());
        }
        meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, li.id());
        stack.setItemMeta(meta);
        return stack;
    }

    /** Crea l'oggetto da piazzare per il blocco numero N (la sua prima variante, N-1). */
    public ItemStack createBlockItem(int number) {
        ItemStack stack = new ItemStack(Material.NOTE_BLOCK);
        ItemMeta meta = stack.getItemMeta();
        String name = getConfig().getString("blocks.item-name", "&eBlocco %number%")
                .replace("%number%", String.valueOf(number));
        meta.displayName(color(name));
        meta.getPersistentDataContainer().set(blockKey, PersistentDataType.INTEGER, number);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Trasforma "&aTesto" in un testo colorato (senza corsivo). */
    public Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand()
                .deserialize(text)
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Legge un messaggio dal config e sostituisce i segnaposto (coppie chiave, valore). */
    public Component message(String key, String... replacements) {
        String text = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            text = text.replace(replacements[i], replacements[i + 1]);
        }
        String prefix = getConfig().getString("messages.prefix", "");
        return color(prefix + text);
    }
}
