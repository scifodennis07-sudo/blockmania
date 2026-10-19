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
    private final Map<String, LauncherItem> items = new LinkedHashMap<>();

    @Override
    public void onEnable() {
        itemKey = new NamespacedKey(this, "launcher");
        saveDefaultConfig();
        loadItems();

        PluginCommand command = getCommand("blockmania");
        if (command != null) {
            BlockManiaCommand handler = new BlockManiaCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        getServer().getPluginManager().registerEvents(new LauncherListener(this), this);
        getLogger().info("BlockMania attivo. Oggetti caricati: " + items.size());
    }

    /** Rilegge il config.yml e ricrea la lista degli oggetti. */
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
                    Math.max(0, s.getDouble("cooldown", 0.5))));
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

    /** Crea l'oggetto da dare al giocatore. */
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
