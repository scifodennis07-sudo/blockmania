package it.blockmania;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BlockMania extends JavaPlugin {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexColors()
            .build();

    private NamespacedKey itemKey;
    private NamespacedKey blockKey;
    private NamespacedKey ammoKey;
    private NamespacedKey damageKey;
    private UpgradeManager upgrades;
    private final Map<String, LauncherItem> items = new LinkedHashMap<>();
    private AmmoManager ammo;
    private BlockManager blocks;

    @Override
    public void onEnable() {
        itemKey = new NamespacedKey(this, "launcher");
        blockKey = new NamespacedKey(this, "block");
        ammoKey = new NamespacedKey(this, "ammo_type");
        damageKey = new NamespacedKey(this, "ammo_damage");

        saveDefaultConfig();
        // aggiunge al config eventuali voci nuove senza toccare quelle che hai cambiato
        getConfig().options().copyDefaults(true);
        saveConfig();
        loadItems();

        ammo = new AmmoManager(this);
        blocks = new BlockManager(this);
        blocks.load();
        ammo.start();
        upgrades = new UpgradeManager(this);

        PluginCommand command = getCommand("blockmania");
        if (command != null) {
            BlockManiaCommand handler = new BlockManiaCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        getServer().getPluginManager().registerEvents(new LauncherListener(this), this);
        getServer().getPluginManager().registerEvents(new CustomBlockListener(this), this);
        getServer().getPluginManager().registerEvents(upgrades, this);
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

    public NamespacedKey getDamageKey() {
        return damageKey;
    }

    public UpgradeManager getUpgrades() {
        return upgrades;
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

    // ------------------------------------------------------------------
    //  Testi: tiny text, colori, messaggi standard
    // ------------------------------------------------------------------

    /** Applica il tiny text (se attivo nel config) a un testo. I numeri restano normali. */
    public String tiny(String text) {
        return getConfig().getBoolean("tiny-text", true) ? Tiny.convert(text) : text;
    }

    /** Trasforma "&aTesto" (e "&#rrggbb") in un testo colorato, senza corsivo. */
    public Component color(String legacy) {
        return LEGACY.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    /** Nomi, lore e bossbar: tiny text, segnaposto sostituiti (coppie chiave, valore) e colori. */
    public Component text(String template, String... replacements) {
        String t = tiny(template);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            t = t.replace(replacements[i], replacements[i + 1]);
        }
        return color(t);
    }

    /**
     * Invia un messaggio standard preso da "messages.<chiave>" nel config.
     * Il tipo (error, success, info, warning) decide lo stile, il canale (chat o actionbar)
     * decide dove appare.
     */
    public void send(CommandSender to, String key, String... replacements) {
        ConfigurationSection m = getConfig().getConfigurationSection("messages." + key);
        String type = "info";
        String channel = "chat";
        List<String> lines;
        if (m != null) {
            type = m.getString("type", "info");
            channel = m.getString("channel", "chat");
            lines = m.isList("text") ? m.getStringList("text") : List.of(m.getString("text", key));
        } else {
            lines = List.of(getConfig().getString("messages." + key, key));
        }

        String base = "styles." + type;
        String baseColor = getConfig().getString(base + ".color", "&7");
        String highlight = getConfig().getString(base + ".highlight", "&f");
        String prefix = getConfig().getString(base + ".prefix", "");
        String suffix = getConfig().getString(base + ".suffix", "");
        boolean actionBar = channel.equalsIgnoreCase("actionbar") && to instanceof Player;

        for (String line : lines) {
            String t = tiny(prefix + line + suffix);
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                t = t.replace(replacements[i], highlight + replacements[i + 1] + baseColor);
            }
            Component component = color(baseColor + t);
            if (actionBar) {
                ((Player) to).sendActionBar(component);
                return;
            }
            to.sendMessage(component);
        }
    }

    /** Suono preso dal config: <percorso>.sound / .volume / .pitch. Se il nome e' vuoto non suona. */
    public void playConfiguredSound(Location location, String path) {
        String name = getConfig().getString(path + ".sound", "");
        if (name == null || name.isBlank() || location.getWorld() == null) {
            return;
        }
        float volume = (float) getConfig().getDouble(path + ".volume", 1.0);
        float pitch = (float) getConfig().getDouble(path + ".pitch", 1.0);
        location.getWorld().playSound(location, name, volume, pitch);
    }

    /** 1.5 -> "1.5", 2.0 -> "2". */
    public static String fmt(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    // ------------------------------------------------------------------
    //  Oggetti
    // ------------------------------------------------------------------

    public ItemStack createItem(LauncherItem li) {
        return createItem(li, null);
    }

    /**
     * Crea il lanciapalle con nome e lore dal config. Se si indica il giocatore, i valori
     * mostrati nella lore (danno, velocita', ricarica) tengono conto dei suoi potenziamenti.
     */
    public ItemStack createItem(LauncherItem li, Player owner) {
        int damage = owner == null ? ammo.getDamage(li.ammoType()) : upgrades.damage(owner, li.ammoType());
        double speed = owner == null ? li.speed() : upgrades.speed(owner, li.speed());
        double cooldown = owner == null ? li.cooldownSeconds() : upgrades.cooldown(owner, li.cooldownSeconds());
        String[] r = {
                "%damage%", String.valueOf(damage),
                "%speed%", fmt(Math.round(speed * 100) / 100.0),
                "%cooldown%", fmt(Math.round(cooldown * 100) / 100.0),
                "%ammo%", tiny(li.ammoType())
        };

        ItemStack stack = new ItemStack(li.material());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text(li.name(), r));
        if (!li.lore().isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : li.lore()) {
                lore.add(text(line, r));
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
        String[] r = {
                "%number%", String.valueOf(number),
                "%variant%", number + "-1",
                "%health%", String.valueOf(blocks.maxHealth(number))
        };

        ItemStack stack = new ItemStack(Material.NOTE_BLOCK);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(text(getConfig().getString("blocks.item-name", "&e&lBlocco %number%"), r));
        List<String> loreLines = getConfig().getStringList("blocks.lore");
        if (!loreLines.isEmpty()) {
            List<Component> lore = new ArrayList<>();
            for (String line : loreLines) {
                lore.add(text(line, r));
            }
            meta.lore(lore);
        }
        meta.getPersistentDataContainer().set(blockKey, PersistentDataType.INTEGER, number);
        stack.setItemMeta(meta);
        return stack;
    }
}
