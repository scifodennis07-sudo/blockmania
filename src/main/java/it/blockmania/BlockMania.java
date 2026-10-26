package it.blockmania;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.Style;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class BlockMania extends JavaPlugin {

    private NamespacedKey itemKey;
    private NamespacedKey blockKey;
    private NamespacedKey ammoKey;
    private NamespacedKey damageKey;
    private UpgradeManager upgrades;
    private ShopManager shops;
    private final Map<String, LauncherItem> items = new LinkedHashMap<>();
    private AmmoManager ammo;
    private BlockManager blocks;
    private Background background;
    private ItemsGui itemsGui;
    private BlockVariantsGui variantsGui;
    private MinesGui minesGui;
    private Power power;
    private final BarStack bars = new BarStack();
    private BlockEdit blockEdit;
    private MineTemplates mineTemplates;

    /** Palette: codice colore (c, a, e ...) -> {r1,g1,b1, r2,g2,b2}. */
    private final Map<Character, int[]> palette = new HashMap<>();
    private final Map<UUID, BossBar> messageBars = new HashMap<>();
    private final Map<UUID, BukkitTask> messageTasks = new HashMap<>();
    /** Fino a quando un messaggio sta occupando l'actionbar (il testo fisso del colpo potenziato aspetta). */
    private final Map<UUID, Long> actionBarBusy = new HashMap<>();

    @Override
    public void onEnable() {
        itemKey = new NamespacedKey(this, "launcher");
        blockKey = new NamespacedKey(this, "block");
        ammoKey = new NamespacedKey(this, "ammo_type");
        damageKey = new NamespacedKey(this, "ammo_damage");

        migrateConfig();
        saveDefaultConfig();
        applyTuning();
        // aggiunge al config eventuali voci nuove senza toccare quelle che hai cambiato
        getConfig().options().copyDefaults(true);
        saveConfig();
        // ricarica dal file appena scritto: senza questo le sezioni nuove (come background.icons) restano vuote al primo avvio
        reloadConfig();
        loadItems();
        background = new Background(this);
        reloadPalette();

        ammo = new AmmoManager(this);
        blocks = new BlockManager(this);
        blocks.load();
        ammo.start();
        upgrades = new UpgradeManager(this);
        shops = new ShopManager(this);

        PluginCommand command = getCommand("blockmania");
        if (command != null) {
            BlockManiaCommand handler = new BlockManiaCommand(this);
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }
        getServer().getPluginManager().registerEvents(new LauncherListener(this), this);
        getServer().getPluginManager().registerEvents(new CustomBlockListener(this), this);
        getServer().getPluginManager().registerEvents(upgrades, this);
        getServer().getPluginManager().registerEvents(shops, this);
        blockEdit = new BlockEdit(this);
        mineTemplates = new MineTemplates(this);
        getServer().getPluginManager().registerEvents(mineTemplates, this);
        mineTemplates.start();
        getServer().getPluginManager().registerEvents(blockEdit, this);
        itemsGui = new ItemsGui(this);
        getServer().getPluginManager().registerEvents(itemsGui, this);
        variantsGui = new BlockVariantsGui(this);
        getServer().getPluginManager().registerEvents(variantsGui, this);
        minesGui = new MinesGui(this);
        getServer().getPluginManager().registerEvents(minesGui, this);
        PluginCommand minesCommand = getCommand("mines");
        if (minesCommand != null) {
            minesCommand.setExecutor((sender, cmd, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player player)) {
                    sender.sendMessage("solo i giocatori possono aprire le miniere");
                    return true;
                }
                if (!player.hasPermission("blockmania.use")) {
                    return true;
                }
                if (args.length > 0 && (args[0].equalsIgnoreCase("exit") || args[0].equalsIgnoreCase("esci"))) {
                    mineTemplates.leave(player);
                    return true;
                }
                minesGui.open(player);
                return true;
            });
        }
        power = new Power(this);
        getServer().getPluginManager().registerEvents(power, this);
        power.start();
        shops.start();
        getServer().getScheduler().runTaskTimer(this, () -> blocks.saveIfDirtyAsync(), 1200L, 1200L);

        getLogger().info("BlockMania attivo. Oggetti: " + items.size()
                + ", blocchi in gioco: " + blocks.count());
    }

    /** Aumenta questo numero (anche nel config.yml) quando cambiano le voci del config. */
    private static final int CONFIG_VERSION = 2;

    /** Se il config e' vecchio lo salva come copia di backup e ne crea uno nuovo. */
    /**
     * Aggiorna da solo i valori che nei vecchi config erano ancora quelli di default (prezzi delle palle,
     * capienze, numeri delle icone), senza toccare quelli che hai cambiato tu. Si fa una volta sola.
     */
    private void applyTuning() {
        var c = getConfig();
        int done = c.getInt("tuning-version", 0);
        if (done < 2) {
            // l'attacco potenziato ora fa danno x10 (prima x3)
            if (c.isDouble("power-attack.damage-multiplier") && c.getDouble("power-attack.damage-multiplier") == 3.0) {
                c.set("power-attack.damage-multiplier", 10.0);
            }
        }
        if (done < 3) {
            // il colpo potenziato ora e' shift + click destro (prima shift + f): aggiorno i testi ancora di default
            for (String path : new String[]{"power-attack.bossbar.ready", "messages.power-unlocked.text"}) {
                String old = c.getString(path, "");
                if (old.contains("shift + f")) {
                    c.set(path, old.replace("shift + f", "shift + click destro"));
                }
            }
            // "clicca per ..." nelle miniere: ora la riga dice cosa succede davvero (%action%)
            for (String st : new String[]{"owned", "locked"}) {
                List<String> lore = new ArrayList<>(c.getStringList("mines-gui.states." + st + ".lore"));
                boolean changed = false;
                for (int i = 0; i < lore.size(); i++) {
                    String line = lore.get(i);
                    if (line.contains("clicca per ...")) {
                        lore.set(i, "%action%");
                        changed = true;
                    } else if (line.contains("devi aver finito la miniera prima")) {
                        lore.remove(i--);
                        changed = true;
                    }
                }
                if (changed) {
                    c.set("mines-gui.states." + st + ".lore", lore);
                }
            }
        }
        if (done < 4) {
            // click sulle miniere gia tue: entrano nella miniera personale
            if (c.getString("mines-gui.actions.owned", "").isBlank()) {
                c.set("mines-gui.actions.owned", "&eclicca per entrare");
            }
            if (c.getString("mines-gui.actions.platinum", "").isBlank()) {
                c.set("mines-gui.actions.platinum", "&eclicca per entrare");
            }
            for (String st : new String[]{"owned", "platinum"}) {
                List<String> lore = new ArrayList<>(c.getStringList("mines-gui.states." + st + ".lore"));
                if (!lore.isEmpty() && lore.stream().noneMatch(l -> l.contains("%action%"))) {
                    if (st.equals("platinum")) {
                        lore.removeIf(l -> l.contains("non si rifa piu"));
                    }
                    lore.add("");
                    lore.add("%action%");
                    c.set("mines-gui.states." + st + ".lore", lore);
                }
            }
        }
        if (done < 5) {
            // i comandi nei testi diventano tasti (riquadro separato): {key:...}
            String[][] fixes = {
                    {"power-attack.bossbar.ready", "shift + click destro"},
                    {"messages.power-unlocked.text", "shift + click destro"},
                    {"mines.edit.bossbar", "shift + f"},
                    {"mines.edit.bossbar", "shift + q"}};
            for (String[] f : fixes) {
                String old = c.getString(f[0], "");
                if (old.contains(f[1]) && !old.contains("{key:" + f[1] + "}")) {
                    c.set(f[0], old.replace("&e" + f[1], f[1]).replace("&f" + f[1], f[1])
                            .replace(f[1], "{key:" + f[1] + "}"));
                }
            }
        }
        if (done < 6) {
            // testi dell'attacco potenziato (solo bossbar, corti) e comandi rinominati (setmines -> createmines)
            c.set("power-attack.bossbar.ready", "&dx%charges% attacco potenziato sbloccato &8| {key:shift + click destro}");
            c.set("power-attack.bossbar.waiting", "&dx%charges% attacco potenziato sbloccato &8| &7aspetta la stamina");
            for (String path : new String[]{"messages.mines-usage.text", "messages.mine-tpl-exists.text",
                    "messages.mine-tpl-missing.text", "messages.mine-tpl-none.text", "help.commands.mines.description",
                    "help.commands.mines.example", "help.commands.mines.usage"}) {
                String old = c.getString(path, "");
                if (old.contains("setmines")) {
                    c.set(path, old.replace("setmines", "createmines"));
                }
            }
            String usage = c.getString("messages.mines-usage.text", "");
            if (!usage.contains("deletemines")) {
                c.set("messages.mines-usage.text", "uso: /bm mines <wand|pos1|pos2|set|replace|remove|undo|createmines <n>|deletemines <n>|changemines <n>|editmines <n>|area [n]|list>");
            }
        }
        if (done < 7) {
            // le icone hanno la loro larghezza (background.icons): il valore generale torna normale
            if (c.getInt("background.unknown-char-width", 6) == 12) {
                c.set("background.unknown-char-width", 6);
            }
        }
        if (done < 8) {
            // colpo potenziato nella actionbar (non piu bossbar), suono sblocco morbido, icone nuove (59, come i PNG del pack)
            c.set("power-attack.bossbar", null);
            c.set("background.icons", null);
            if ("ui.toast.challenge_complete".equals(c.getString("power-attack.sounds.unlock.sound", ""))) {
                c.set("power-attack.sounds.unlock.sound", "block.amethyst_block.chime");
                c.set("power-attack.sounds.unlock.volume", 0.6);
                c.set("power-attack.sounds.unlock.pitch", 1.4);
            }
        }
        if (done < 9) {
            // bossbar principale: icona della palla scelta e icone giuste (i nomi delle icone sono cambiati)
            c.set("bossbar.segments", null);
            c.set("messages.ball-selected.text", null);
            c.set("messages.ball-selected.icon", null);
        }
        if (done >= 1) {
            c.set("tuning-version", 9);
            saveConfig();
            return;
        }
        c.set("tuning-version", 9);
        retune("shops.doubleball.price", 150, 60);
        retune("shops.ghostball.price", 2500, 250);
        retune("shops.explosionball.price", 40000, 800);
        retune("ammo.doubleball.max", 20, 15);
        retune("ammo.ghostball.max", 20, 10);
        retune("ammo.explosionball.max", 20, 6);
        retune("upgrades.capacity_doubleball.per-level", 5, 4);
        retune("upgrades.capacity_ghostball.per-level", 5, 3);
        retune("upgrades.capacity_explosionball.per-level", 5, 2);
        // le icone dei potenziamenti lasciano il posto ai modelli delle miniere (101-331)
        String[] ids = {"damage", "cooldown", "speed", "capacity", "capacity_doubleball", "capacity_ghostball",
                "capacity_explosionball", "capacity_standardball"};
        int[] oldCmd = {101, 102, 103, 104, 105, 106, 107, 108};
        for (int i = 0; i < ids.length; i++) {
            retune("upgrade-gui.icons." + ids[i] + ".cmd", oldCmd[i], 401 + i);
        }
        // testi delle miniere senza colori (versione precedente): si rimettono quelli nuovi
        if ("&fminiera %number%".equals(c.getString("mines-gui.states.owned.name"))) {
            c.set("mines-gui.states", null);
        }
        c.set("tuning-version", 9);
        saveConfig();
    }

    private void retune(String path, int oldValue, int newValue) {
        var c = getConfig();
        if (c.isInt(path) && c.getInt(path) == oldValue) {
            c.set(path, newValue);
        }
    }

    private void migrateConfig() {
        java.io.File file = new java.io.File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            return;
        }
        int version = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file)
                .getInt("config-version", 0);
        if (version >= CONFIG_VERSION) {
            return;
        }
        java.io.File backup = new java.io.File(getDataFolder(), "config_vecchio_" + System.currentTimeMillis() + ".yml");
        if (file.renameTo(backup)) {
            getLogger().warning("Config vecchio: salvato come " + backup.getName() + ", ne ho creato uno nuovo.");
        }
    }

    @Override
    public void onDisable() {
        if (mineTemplates != null) {
            mineTemplates.stop();
        }
        if (power != null) {
            power.stop();
        }
        if (shops != null) {
            shops.stop();
        }
        if (ammo != null) {
            ammo.stop();
        }
        if (blocks != null) {
            blocks.finishAllShakes();
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
            Integer aim = s.contains("aim-custom-model-data") ? s.getInt("aim-custom-model-data") : null;

            items.put(id, new LauncherItem(
                    id,
                    material,
                    s.getString("name", key),
                    s.getStringList("lore"),
                    cmd,
                    aim,
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

    public ShopManager getShops() {
        return shops;
    }

    public UpgradeManager getUpgrades() {
        return upgrades;
    }

    public MineTemplates getMineTemplates() {
        return mineTemplates;
    }

    public BlockEdit getBlockEdit() {
        return blockEdit;
    }

    public BarStack getBars() {
        return bars;
    }

    public Power getPower() {
        return power;
    }

    public MinesGui getMinesGui() {
        return minesGui;
    }

    public ItemsGui getItemsGui() {
        return itemsGui;
    }

    public BlockVariantsGui getVariantsGui() {
        return variantsGui;
    }

    public AmmoManager getAmmo() {
        return ammo;
    }

    public BlockManager getBlocks() {
        return blocks;
    }

    /** Se l'oggetto e' un nostro lanciapalle ritorna la sua definizione, altrimenti null. */
    public LauncherItem getLauncher(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return null;
        }
        String id = item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
        return getItem(id);
    }

    /** Mette il modello normale o quello "di mira" al lanciapalle. Cambia solo se serve. */
    public ItemStack applyModel(ItemStack item, LauncherItem li, boolean aim) {
        Integer cmd = aim && li.aimModelData() != null ? li.aimModelData() : li.customModelData();
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        Integer current = meta.hasCustomModelData() ? meta.getCustomModelData() : null;
        if (java.util.Objects.equals(current, cmd)) {
            return item;
        }
        meta.setCustomModelData(cmd);
        item.setItemMeta(meta);
        return item;
    }

    public Background getBackground() {
        return background;
    }

    // ---------- tasti: {key:shift + f} diventa un riquadro separato che sembra un tasto ----------

    private static final char KEY_OPEN = '\uE000';
    private static final char KEY_CLOSE = '\uE001';
    /** Davanti a un pezzo di testo lo segna come "tasto" (sfondo diverso). */
    public static final String KEY_TAG = "\uE002";
    private static final java.util.regex.Pattern KEY_MARKUP = java.util.regex.Pattern.compile("\\{key:([^}]*)}");
    private static final java.util.regex.Pattern LEADING_CODES =
            java.util.regex.Pattern.compile("^(?:&#[0-9a-fA-F]{6}|&[0-9a-fk-orA-FK-OR])+");

    /** Trasforma {key:...} in segnali che il "tiny text" non rovina. Va fatto PRIMA di tiny(). */
    public String keyMarks(String text) {
        if (text != null && background != null) {
            text = background.iconMarks(text);
        }
        if (text == null || !text.contains("{key:")) {
            return text;
        }
        return KEY_MARKUP.matcher(text).replaceAll(m ->
                java.util.regex.Matcher.quoteReplacement(KEY_OPEN + m.group(1) + KEY_CLOSE));
    }

    /**
     * Divide un testo in pezzi: il testo normale e i tasti (segnati con KEY_TAG). Il colore iniziale
     * del testo si ripete dopo ogni tasto. Se non ci sono tasti ritorna il testo cosi' com'e'.
     */
    public List<String> splitKeys(String legacy) {
        if (legacy == null || legacy.indexOf(KEY_OPEN) < 0) {
            return List.of(legacy);
        }
        var m = LEADING_CODES.matcher(legacy);
        String base = m.find() ? m.group() : "";
        String keyColor = getConfig().getString("background.key.color", "&f");
        List<String> out = new ArrayList<>();
        int i = 0;
        boolean first = true;
        while (i < legacy.length()) {
            int open = legacy.indexOf(KEY_OPEN, i);
            if (open < 0) {
                open = legacy.length();
            }
            String text = legacy.substring(i, open).strip();
            if (!text.isEmpty()) {
                out.add(first || text.startsWith("&") ? text : base + text);
                first = false;
            }
            if (open >= legacy.length()) {
                break;
            }
            int close = legacy.indexOf(KEY_CLOSE, open);
            if (close < 0) {
                close = legacy.length();
            }
            String label = legacy.substring(open + 1, close).strip();
            if (!label.isEmpty()) {
                out.add(KEY_TAG + keyColor + label);
                first = false;
            }
            i = close + 1;
        }
        return out.isEmpty() ? List.of("") : out;
    }

    /** Toglie i segnali dei tasti (per la chat e per quando lo sfondo e' spento). */
    private static String stripKeyMarks(String text) {
        return text.replace(KEY_TAG, "").replace(String.valueOf(KEY_OPEN), "").replace(String.valueOf(KEY_CLOSE), "");
    }

    /** Testo con lo sfondo dietro. Lo sfondo non ha l'ombra (se remove-shadow e' attivo), il testo si'. */
    public Component withBackground(String legacy) {
        if (legacy != null && legacy.indexOf(KEY_OPEN) >= 0) {
            return withBackgrounds(splitKeys(legacy), -1);
        }
        return withBackground(legacy, -1);
    }

    /** Come sopra, con in piu' una barra di avanzamento sotto lo sfondo (progress da 0 a 1; -1 = niente barra). */
    public Component withBackground(String legacy, double progress) {
        String[] parts = background.parts(legacy, progress);
        if (parts[0].isEmpty()) {
            return color(legacy);
        }
        if (!background.removeShadow()) {
            return color(parts[0] + legacy + parts[1]);
        }
        return Component.empty()
                .append(color(parts[0]).shadowColor(ShadowColor.none()))
                .append(color(legacy))
                .append(color(parts[1]).shadowColor(ShadowColor.none()));
    }

    /** Piu' testi, ognuno nel suo sfondo, messi in fila e centrati. progress >= 0 aggiunge la barra sotto tutti. */
    public Component withBackgrounds(List<String> segments, double progress) {
        return withBackgrounds(segments, progress, null, null, 0);
    }

    public Component withBackgrounds(List<String> segments, double progress, String fillChar, String emptyChar,
                                     int barWidth) {
        boolean noShadow = background.removeShadow();
        Component out = Component.empty();
        int totalAll = 0;
        boolean any = false;
        for (String seg : segments) {
            boolean isKey = seg.startsWith(KEY_TAG);
            boolean ownArt = isKey && getConfig().getBoolean("background.key.own-art", false);
            if (isKey) {
                seg = seg.substring(KEY_TAG.length());
            }
            String[] parts = background.parts(seg, -1, ownArt);
            int total = background.totalOf(seg, ownArt);
            if (parts[0].isEmpty() || total <= 0) {
                out = out.append(color(seg));
                continue;
            }
            if (any && background.gap() > 0) {
                Component gap = color("&f" + background.shift(background.gap()));
                out = out.append(noShadow ? gap.shadowColor(ShadowColor.none()) : gap);
                totalAll += background.gap();
            }
            Component before = color(parts[0]);
            Component after = color(parts[1]);
            out = out.append(noShadow ? before.shadowColor(ShadowColor.none()) : before)
                    .append(color(seg))
                    .append(noShadow ? after.shadowColor(ShadowColor.none()) : after);
            totalAll += total;
            any = true;
        }
        if (any && progress >= 0) {
            String bar = background.bar(totalAll, progress, fillChar, emptyChar, barWidth);
            if (!bar.isEmpty()) {
                Component b = color("&f" + bar);
                out = out.append(noShadow ? b.shadowColor(ShadowColor.none()) : b);
            }
        }
        return out;
    }

    /** Testo per le bossbar: come text(), con lo sfondo se attivo. */
    public Component bossText(String template, String... replacements) {
        return bossSegments(List.of(template), -1, replacements);
    }

    /** Come bossText, con una barra di avanzamento disegnata sotto lo sfondo (progress da 0 a 1). */
    public Component bossTextBar(String template, double progress, String... replacements) {
        return bossSegments(List.of(template), progress, replacements);
    }

    /** Bossbar fatta di piu' pezzi, ognuno con il suo sfondo. */
    public Component bossSegments(List<String> templates, double progress, String... replacements) {
        return bossSegmentsBar(templates, progress, null, null, 0, replacements);
    }

    /** Come bossSegments, con la barra disegnata con altri pezzi (fill/empty): vedi la stamina gialla. */
    public Component bossSegmentsBar(List<String> templates, double progress, String fillChar, String emptyChar,
                                     int barWidth, String... replacements) {
        List<String> legacies = new ArrayList<>();
        for (String template : templates) {
            String t = tiny(keyMarks(template));
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                t = t.replace(replacements[i], replacements[i + 1]);
            }
            legacies.addAll(splitKeys(t));
        }
        Component result;
        if (background != null && background.forBossbar()) {
            result = withBackgrounds(legacies, progress, fillChar, emptyChar, barWidth);
        } else {
            result = color(stripKeyMarks(String.join(" ", legacies)));
        }
        String font = getConfig().getString("bossbar.font", "");
        if (font != null && !font.isBlank()) {
            try {
                result = result.font(Key.key(font.trim()));
            } catch (Exception ex) {
                // nome del font non valido: resta il font normale
            }
        }
        return result;
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

    // ------------------------------------------------------------------
    //  Colori: codici &, esadecimali e PALETTE (colori tuoi con sfumatura)
    // ------------------------------------------------------------------

    private static final Map<String, Character> PALETTE_CODES = Map.ofEntries(
            Map.entry("black", '0'), Map.entry("dark_blue", '1'), Map.entry("dark_green", '2'),
            Map.entry("dark_aqua", '3'), Map.entry("dark_red", '4'), Map.entry("dark_purple", '5'),
            Map.entry("gold", '6'), Map.entry("gray", '7'), Map.entry("dark_gray", '8'),
            Map.entry("blue", '9'), Map.entry("green", 'a'), Map.entry("aqua", 'b'),
            Map.entry("red", 'c'), Map.entry("light_purple", 'd'), Map.entry("yellow", 'e'),
            Map.entry("white", 'f'));

    private static final String[] VANILLA_HEX = {
            "000000", "0000AA", "00AA00", "00AAAA", "AA0000", "AA00AA", "FFAA00", "AAAAAA",
            "555555", "5555FF", "55FF55", "55FFFF", "FF5555", "FF55FF", "FFFF55", "FFFFFF"};

    private static int[] parseHex(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        if (t.startsWith("#")) {
            t = t.substring(1);
        } else if (t.toLowerCase(Locale.ROOT).startsWith("&#")) {
            t = t.substring(2);
        }
        if (t.length() != 6) {
            return null;
        }
        try {
            int v = Integer.parseInt(t, 16);
            return new int[]{(v >> 16) & 255, (v >> 8) & 255, v & 255};
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Rilegge la sezione "palette" del config. Il bianco (&f) resta quello vero, a meno che tu non lo definisca. */
    public void reloadPalette() {
        palette.clear();
        var c = getConfig();
        if (!c.getBoolean("palette.enabled", true)) {
            return;
        }
        ConfigurationSection section = c.getConfigurationSection("palette.colors");
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            Character code = PALETTE_CODES.get(name.toLowerCase(Locale.ROOT));
            if (code == null) {
                continue;
            }
            List<String> values = section.isList(name) ? section.getStringList(name)
                    : List.of(section.getString(name, ""));
            if (values.isEmpty()) {
                continue;
            }
            int[] from = parseHex(values.get(0));
            int[] to = values.size() > 1 ? parseHex(values.get(1)) : from;
            if (from == null || to == null) {
                continue;
            }
            palette.put(code, new int[]{from[0], from[1], from[2], to[0], to[1], to[2]});
        }
    }

    private static int vanillaIndex(char code) {
        return Character.digit(code, 16);
    }

    /** Toglie dal testo tutti i codici colore e formato (&c, &#ff0000, &l ...). */
    public static String stripCodes(String text) {
        return text.replaceAll("(?i)&(#[0-9a-f]{6}|[0-9a-fk-or])", "");
    }

    private static Component styled(String text, TextColor color, boolean[] deco) {
        Style.Builder style = Style.style().decoration(TextDecoration.ITALIC, false);
        if (color != null) {
            style.color(color);
        }
        if (deco[0]) {
            style.decoration(TextDecoration.BOLD, true);
        }
        if (deco[1]) {
            style.decoration(TextDecoration.ITALIC, true);
        }
        if (deco[2]) {
            style.decoration(TextDecoration.UNDERLINED, true);
        }
        if (deco[3]) {
            style.decoration(TextDecoration.STRIKETHROUGH, true);
        }
        if (deco[4]) {
            style.decoration(TextDecoration.OBFUSCATED, true);
        }
        return Component.text(text, style.build());
    }

    private void flush(StringBuilder buffer, List<Component> out, TextColor color, int[] gradient, boolean[] deco) {
        if (buffer.length() == 0) {
            return;
        }
        String text = buffer.toString();
        buffer.setLength(0);
        boolean sloped = gradient != null && (gradient[0] != gradient[3] || gradient[1] != gradient[4]
                || gradient[2] != gradient[5]);
        if (!sloped) {
            TextColor tc = gradient != null ? TextColor.color(gradient[0], gradient[1], gradient[2]) : color;
            // le icone: sempre bianche (cosi' si vedono i colori veri del PNG) e senza ombra
            int from = 0;
            for (int i = 0; i < text.length(); i++) {
                if (background != null && background.isIcon(text.charAt(i))) {
                    if (i > from) {
                        out.add(styled(text.substring(from, i), tc, deco));
                    }
                    Component icon = styled(String.valueOf(text.charAt(i)), TextColor.color(255, 255, 255), new boolean[5]);
                    if (!getConfig().getBoolean("background.icon-shadow", true)) {
                        icon = icon.shadowColor(ShadowColor.none());
                    }
                    out.add(icon);
                    from = i + 1;
                }
            }
            if (from < text.length()) {
                out.add(styled(text.substring(from), tc, deco));
            }
            return;
        }
        int n = text.length();
        for (int i = 0; i < n; i++) {
            double t = n > 1 ? (double) i / (n - 1) : 0;
            int r = (int) Math.round(gradient[0] + (gradient[3] - gradient[0]) * t);
            int g = (int) Math.round(gradient[1] + (gradient[4] - gradient[1]) * t);
            int b = (int) Math.round(gradient[2] + (gradient[5] - gradient[2]) * t);
            out.add(styled(String.valueOf(text.charAt(i)), TextColor.color(r, g, b), deco));
        }
    }

    /**
     * Trasforma "&aTesto" (e "&#rrggbb") in un testo colorato, senza corsivo.
     * I colori che hai messo nella "palette" del config diventano la loro sfumatura.
     */
    public Component color(String legacy) {
        if (legacy == null || legacy.isEmpty()) {
            return Component.empty();
        }
        legacy = stripKeyMarks(legacy);
        List<Component> out = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        TextColor color = null;
        int[] gradient = null;
        boolean[] deco = new boolean[5];
        int len = legacy.length();
        for (int i = 0; i < len; i++) {
            char ch = legacy.charAt(i);
            if (ch == '&' && i + 1 < len) {
                char n = legacy.charAt(i + 1);
                char low = Character.toLowerCase(n);
                if (n == '#' && i + 7 < len + 0 && parseHex(legacy.substring(i + 2, i + 8)) != null) {
                    flush(buffer, out, color, gradient, deco);
                    int[] rgb = parseHex(legacy.substring(i + 2, i + 8));
                    color = TextColor.color(rgb[0], rgb[1], rgb[2]);
                    gradient = null;
                    deco = new boolean[5];
                    i += 7;
                    continue;
                }
                if (Character.digit(low, 16) >= 0) {
                    flush(buffer, out, color, gradient, deco);
                    int[] g = palette.get(low);
                    gradient = g;
                    int[] v = parseHex(VANILLA_HEX[vanillaIndex(low)]);
                    color = TextColor.color(v[0], v[1], v[2]);
                    deco = new boolean[5];
                    i++;
                    continue;
                }
                int decoIndex = switch (low) {
                    case 'l' -> 0;
                    case 'o' -> 1;
                    case 'n' -> 2;
                    case 'm' -> 3;
                    case 'k' -> 4;
                    default -> -1;
                };
                if (decoIndex >= 0) {
                    flush(buffer, out, color, gradient, deco);
                    deco = deco.clone();
                    deco[decoIndex] = true;
                    i++;
                    continue;
                }
                if (low == 'r') {
                    flush(buffer, out, color, gradient, deco);
                    color = null;
                    gradient = null;
                    deco = new boolean[5];
                    i++;
                    continue;
                }
            }
            buffer.append(ch);
        }
        flush(buffer, out, color, gradient, deco);
        return Component.empty().decoration(TextDecoration.ITALIC, false).children(out);
    }

    /** Nomi, lore e bossbar: tiny text, segnaposto sostituiti (coppie chiave, valore) e colori. */
    public Component text(String template, String... replacements) {
        String t = tiny(template);
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            t = t.replace(replacements[i], replacements[i + 1]);
        }
        return color(t);
    }

    /** True se il giocatore ha una GUI aperta (l'actionbar in quel caso e' coperta). */
    public boolean hasOpenGui(Player player) {
        return player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING;
    }

    /** Mostra un messaggio in una bossbar per qualche secondo (si vede anche con una GUI aperta). */
    public void showBossMessage(Player player, String legacy) {
        UUID id = player.getUniqueId();
        BukkitTask oldTask = messageTasks.remove(id);
        if (oldTask != null) {
            oldTask.cancel();
        }
        BossBar bar = BossBar.bossBar(bossSegments(List.of(legacy), -1), 1f,
                BossBar.Color.WHITE, BossBar.Overlay.PROGRESS);
        messageBars.put(id, bar);
        // priorita' massima dopo la principale; la principale perde la barra sotto cosi' il messaggio sta in 2a riga
        bars.set(player, "message", bar, 100, false);
        if (ammo != null) {
            ammo.refreshMain(player);
        }
        long ticks = Math.max(1, (long) (getConfig().getDouble("gui-messages.seconds", 3.0) * 20));
        messageTasks.put(id, Bukkit.getScheduler().runTaskLater(this, () -> {
            Player online = Bukkit.getPlayer(id);
            if (messageBars.get(id) == bar) {
                messageBars.remove(id);
                if (online != null) {
                    bars.remove(online, "message");
                    if (ammo != null) {
                        ammo.refreshMain(online);
                    }
                }
            }
            messageTasks.remove(id);
        }, ticks));
    }

    /** Messaggi legati a un comando: se e' un errore va sempre in chat (non in actionbar). */
    public void sendCommand(CommandSender to, String key, String... replacements) {
        String type = getConfig().getString("messages." + key + ".type", "error");
        deliver(to, key, type.equalsIgnoreCase("error"), replacements);
    }

    /**
     * Invia un messaggio standard preso da "messages.<chiave>" nel config.
     * Il tipo (error, success, info, warning) decide lo stile, il canale (chat o actionbar)
     * decide dove appare. Con una GUI aperta l'actionbar diventa una bossbar.
     */
    public void send(CommandSender to, String key, String... replacements) {
        deliver(to, key, false, replacements);
    }

    private void deliver(CommandSender to, String key, boolean forceChat, String... replacements) {
        ConfigurationSection m = getConfig().getConfigurationSection("messages." + key);
        String type = "error";
        String channel = "actionbar";
        List<String> lines;
        if (m != null) {
            type = m.getString("type", "info");
            channel = m.getString("channel", "chat");
            lines = m.isList("text") ? m.getStringList("text") : List.of(m.getString("text", key));
        } else {
            lines = List.of(getConfig().getString("messages." + key, key));
        }
        if (forceChat) {
            channel = "chat";
        }

        String base = "styles." + type;
        String baseColor = getConfig().getString(base + ".color", "&7");
        String highlight = getConfig().getString(base + ".highlight", "&f");
        String prefix = getConfig().getString(base + ".prefix", "");
        String suffix = getConfig().getString(base + ".suffix", "");
        boolean uniform = getConfig().getBoolean(base + ".uniform", false);
        boolean actionBar = channel.equalsIgnoreCase("actionbar") && to instanceof Player;

        // icona davanti al messaggio (solo actionbar/bossbar): quella del messaggio, oppure quella dello stile
        String icon = "";
        if (actionBar) {
            icon = m != null && m.contains("icon") ? m.getString("icon", "") : getConfig().getString(base + ".icon", "");
            if (icon == null || icon.equalsIgnoreCase("none")) {
                icon = "";
            } else if (!icon.isBlank()) {
                icon = icon + " ";
            }
        }

        for (String line : lines) {
            String t = tiny(keyMarks(icon + prefix + line + suffix));
            if (uniform) {
                t = stripCodes(t);
            }
            for (int i = 0; i + 1 < replacements.length; i += 2) {
                String value = uniform ? stripCodes(replacements[i + 1]) : replacements[i + 1];
                t = t.replace(replacements[i], uniform ? value : highlight + value + baseColor);
            }
            String legacy = baseColor + t;
            if (actionBar && hasOpenGui((Player) to)) {
                showBossMessage((Player) to, legacy);
                return;
            }
            Component component = actionBar && background != null && background.forActionbar()
                    ? withBackground(legacy) : color(legacy);
            if (actionBar) {
                markActionBarBusy((Player) to);
                ((Player) to).sendActionBar(component);
                return;
            }
            to.sendMessage(component);
        }
    }

    /** Icona della palla (bossbar.ball-icons) pronta da mettere nel testo; vuota se non esiste. */
    public String ballIcon(String type) {
        String name = getConfig().getString("bossbar.ball-icons." + type, type);
        String r = keyMarks("{icon:" + name + "}");
        return r.contains("{icon:") ? "" : r;
    }

    /** Testo fisso per la actionbar (tiny, tasti, icone) con lo sfondo, come i messaggi standard. */
    public Component actionBarText(String template, String... replacements) {
        String t = tiny(keyMarks(template));
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            t = t.replace(replacements[i], replacements[i + 1]);
        }
        if (background != null && background.forActionbar()) {
            return withBackground(t);
        }
        return color(stripKeyMarks(t));
    }

    /** Un messaggio e' appena apparso nella actionbar: per qualche secondo il testo fisso non lo copre. */
    public void markActionBarBusy(Player player) {
        long ms = (long) (getConfig().getDouble("gui-messages.seconds", 3.0) * 1000);
        actionBarBusy.put(player.getUniqueId(), System.currentTimeMillis() + ms);
    }

    public boolean isActionBarBusy(Player player) {
        Long until = actionBarBusy.get(player.getUniqueId());
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            actionBarBusy.remove(player.getUniqueId());
            return false;
        }
        return true;
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

    /** Suona tutti i suoni di una sezione del config (ogni voce ha sound / volume / pitch). */
    public void playLayers(Location location, String path) {
        var section = getConfig().getConfigurationSection(path);
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            playConfiguredSound(location, path + "." + key);
        }
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
        String ball = owner == null ? li.ammoType() : ammo.getSelected(owner, li.ammoType());
        int damage = owner == null ? ammo.getDamage(ball) : upgrades.damage(owner, ball);
        double speed = owner == null ? li.speed() : upgrades.speed(owner, li.speed());
        double cooldown = owner == null ? li.cooldownSeconds() : upgrades.cooldown(owner, li.cooldownSeconds());
        String[] r = {
                "%damage%", String.valueOf(damage),
                "%speed%", fmt(Math.round(speed * 100) / 100.0),
                "%cooldown%", fmt(Math.round(cooldown * 100) / 100.0),
                "%ammo%", tiny(ball)
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
        return createBlockItem(number, 1);
    }

    /** Il blocco con il modello di una sua variante (1-10): serve per mostrarle tutte nella GUI delle varianti. */
    public ItemStack createBlockItem(int number, int variant) {
        String[] r = {
                "%number%", String.valueOf(number),
                "%variant%", number + "-" + variant,
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
        if (getConfig().getBoolean("blocks.custom-model-data", true)) {
            meta.setCustomModelData(BlockManager.itemModelData(number, variant));
        }
        stack.setItemMeta(meta);
        return stack;
    }
}
