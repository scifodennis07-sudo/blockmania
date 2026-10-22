package it.blockmania;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;
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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Potenziamenti del lanciapalle (danno, velocita', ricarica) e la GUI a 6 righe per comprarli.
 * I livelli sono salvati sul giocatore e valgono per tutti i suoi lanciapalle.
 */
public final class UpgradeManager implements Listener {

    public static final List<String> IDS = List.of("damage", "cooldown", "speed", "capacity");
    private static final int SIZE = 54;

    /** Serve a riconoscere la nostra GUI tra tutti gli inventari. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final String launcherId;
        private int page;
        /** slot -> azione: close, prev, next oppure buy:<potenziamento>. */
        private final Map<Integer, String> actions = new HashMap<>();

        Holder(String launcherId, int page) {
            this.launcherId = launcherId;
            this.page = page;
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

    /** Per i comandi: imposta il livello (tra 0 e il massimo) e aggiorna i lanciapalle del giocatore. */
    public void setLevelAdmin(Player player, String id, int level) {
        setLevel(player, id, Math.min(level, maxLevel(id)));
        refreshLaunchers(player);
    }

    /** Per i comandi: riporta tutti i potenziamenti a 0. */
    public void resetLevels(Player player) {
        for (String id : IDS) {
            setLevel(player, id, 0);
        }
        refreshLaunchers(player);
    }

    public int maxLevel(String id) {
        return Math.max(0, plugin.getConfig().getInt("upgrades." + id + ".max-level", 10));
    }

    private double perLevel(String id) {
        return plugin.getConfig().getDouble("upgrades." + id + ".per-level", 0);
    }

    /** Costo per passare dal livello "level" al successivo (si puo' forzare con upgrades.<id>.costs.<livello-nuovo>). */
    private int cost(String id, int level) {
        int forced = plugin.getConfig().getInt("upgrades." + id + ".costs." + (level + 1), -1);
        if (forced >= 0) {
            return forced;
        }
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

    /** Palle in piu' che questo giocatore puo' tenere grazie al potenziamento capienza. */
    public int capacityBonus(Player player) {
        return (int) Math.round(getLevel(player, "capacity") * perLevel("capacity"));
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
            case "capacity" -> String.valueOf(plugin.getAmmo().getAmmoBaseMax(li.ammoType())
                    + (int) Math.round(level * perLevel("capacity")));
            default -> BlockMania.fmt(Math.round(speedAt(level, li.speed()) * 100) / 100.0);
        };
    }

    // ---------- miniere ----------

    /** Livello massimo raggiungibile con la miniera attuale del giocatore (upgrades.mines). */
    private int levelCap(Player player) {
        ConfigurationSection mines = plugin.getConfig().getConfigurationSection("upgrades.mines");
        if (mines == null || mines.getKeys(false).isEmpty()) {
            return Integer.MAX_VALUE;
        }
        int mine = plugin.getAmmo().getMine(player);
        int bestMine = -1;
        int cap = Integer.MAX_VALUE;
        for (String key : mines.getKeys(false)) {
            try {
                int m = Integer.parseInt(key);
                if (m <= mine && m > bestMine) {
                    bestMine = m;
                    cap = mines.getInt(key);
                }
            } catch (NumberFormatException ignored) {
                // chiave non numerica: la salto
            }
        }
        return bestMine < 0 ? 0 : cap;
    }

    private enum State { AVAILABLE, LOCKED, NONE, MAX }

    private State stateOf(Player player, String id) {
        int level = getLevel(player, id);
        if (level >= maxLevel(id)) {
            return State.MAX;
        }
        if (level >= levelCap(player)) {
            return State.LOCKED;
        }
        return State.AVAILABLE;
    }

    // ---------- oggetti della GUI ----------

    private static final String GUI = "upgrade-gui.";

    private Material material(String path, Material fallback) {
        Material m = Material.matchMaterial(plugin.getConfig().getString(path + ".material", fallback.name()));
        return m == null || !m.isItem() ? fallback : m;
    }

    /** Pannello di vetro con il modello (custom-model-data), il nome e la lore presi dal config. */
    private ItemStack pane(String path, int fallbackCmd, String name, List<String> lore, String[] r, boolean hideTooltip) {
        ItemStack stack = new ItemStack(material(path, Material.GLASS_PANE));
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt(path + ".cmd", fallbackCmd));
        if (hideTooltip) {
            meta.setHideTooltip(true);
        } else {
            meta.displayName(plugin.text(name, r));
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (String line : lore) {
                    lines.add(plugin.text(line, r));
                }
                meta.lore(lines);
            }
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** Pannello invisibile (modello 999). Con name/lore mostra il testo quando ci passi sopra. */
    private ItemStack invisible(String name, List<String> lore, String[] r, boolean hideTooltip) {
        return pane(GUI + "invisible", 999, name, lore, r, hideTooltip);
    }

    private List<Integer> slotList(String path) {
        List<Integer> out = new ArrayList<>();
        var c = plugin.getConfig();
        if (c.isList(path)) {
            for (Integer i : c.getIntegerList(path)) {
                if (i >= 0 && i < SIZE) {
                    out.add(i);
                }
            }
        } else if (c.contains(path)) {
            int i = c.getInt(path);
            if (i >= 0 && i < SIZE) {
                out.add(i);
            }
        }
        return out;
    }

    private List<String> orderedUpgrades() {
        List<String> out = new ArrayList<>();
        for (String id : plugin.getConfig().getStringList(GUI + "order")) {
            String low = id.toLowerCase(Locale.ROOT);
            if (IDS.contains(low) && !out.contains(low)) {
                out.add(low);
            }
        }
        return out.isEmpty() ? new ArrayList<>(IDS) : out;
    }

    private record Position(int icon, int button, List<Integer> extra) {
    }

    private List<Position> positions() {
        List<Position> out = new ArrayList<>();
        for (Map<?, ?> map : plugin.getConfig().getMapList(GUI + "slots.positions")) {
            try {
                int icon = Integer.parseInt(String.valueOf(map.get("icon")));
                int button = Integer.parseInt(String.valueOf(map.get("button")));
                List<Integer> extra = new ArrayList<>();
                Object e = map.get("extra");
                if (e instanceof List<?> list) {
                    for (Object o : list) {
                        extra.add(Integer.parseInt(String.valueOf(o)));
                    }
                }
                out.add(new Position(icon, button, extra));
            } catch (NumberFormatException ignored) {
                // riga non valida: la salto
            }
        }
        return out;
    }

    private int pageCount() {
        int perPage = Math.max(1, positions().size());
        return Math.max(1, (orderedUpgrades().size() + perPage - 1) / perPage);
    }

    private void put(Inventory inv, Holder holder, int slot, ItemStack item, String action) {
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        inv.setItem(slot, item);
        if (action != null) {
            holder.actions.put(slot, action);
        } else {
            holder.actions.remove(slot);
        }
    }

    private String[] general(Player player, int page, int pages) {
        AmmoManager ammo = plugin.getAmmo();
        String ammoType = plugin.getConfig().getString("bossbar.ammo-type", "standardball");
        return new String[]{
                "%coin%", String.valueOf(ammo.getCoin(player)),
                "%blockcoin%", String.valueOf(ammo.getBlockCoin(player)),
                "%shell%", String.valueOf(ammo.getShell(player)),
                "%shell_max%", String.valueOf(ammo.getShellMax()),
                "%ammo%", String.valueOf(ammo.getAmmo(player, ammoType)),
                "%mine%", String.valueOf(ammo.getMine(player)),
                "%page%", String.valueOf(page + 1),
                "%pages%", String.valueOf(pages)
        };
    }

    private String[] upgradeValues(Player player, String id, LauncherItem li, String[] general) {
        int level = getLevel(player, id);
        int max = maxLevel(id);
        boolean maxed = level >= max;
        String path = GUI + "icons." + id;
        List<String> r = new ArrayList<>(List.of(general));
        r.addAll(List.of(
                "%label%", plugin.tiny(plugin.getConfig().getString("upgrades." + id + ".label", id)),
                "%description%", plugin.tiny(plugin.getConfig().getString(path + ".description", "")),
                "%level%", String.valueOf(level),
                "%max%", String.valueOf(max),
                "%current%", valueText(id, level, li),
                "%next%", maxed ? "-" : valueText(id, level + 1, li),
                "%next_level%", String.valueOf(maxed ? level : level + 1),
                "%cost%", maxed ? "-" : String.valueOf(cost(id, level)),
                "%currency%", plugin.tiny(currency()),
                "%balance%", String.valueOf(plugin.getAmmo().getBalance(player, currency()))));
        return r.toArray(new String[0]);
    }

    private void fill(Inventory inv, Holder holder, Player player, LauncherItem li) {
        var c = plugin.getConfig();
        List<Position> positions = positions();
        List<String> upgrades = orderedUpgrades();
        int pages = pageCount();
        holder.page = Math.max(0, Math.min(holder.page, pages - 1));
        String[] general = general(player, holder.page, pages);

        holder.actions.clear();
        ItemStack filler = invisible(" ", List.of(), general, true);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, filler);
        }

        // chiudi, pagine, info
        for (int slot : slotList(GUI + "slots.close")) {
            put(inv, holder, slot, invisible(c.getString(GUI + "close.name", "&cchiudi"),
                    c.getStringList(GUI + "close.lore"), general, false), "close");
        }
        for (int slot : slotList(GUI + "slots.previous")) {
            put(inv, holder, slot, invisible(c.getString(GUI + "previous.name", "&7pagina precedente"),
                    c.getStringList(GUI + "previous.lore"), general, false), "prev");
        }
        for (int slot : slotList(GUI + "slots.next")) {
            put(inv, holder, slot, invisible(c.getString(GUI + "next.name", "&7pagina successiva"),
                    c.getStringList(GUI + "next.lore"), general, false), "next");
        }
        for (int slot : slotList(GUI + "slots.info")) {
            put(inv, holder, slot, invisible(c.getString(GUI + "info.name", "&6informazioni"),
                    c.getStringList(GUI + "info.lore"), general, false), null);
        }
        // gli slot della pagina (in mezzo alle frecce) restano invisibili e senza testo

        // potenziamenti
        for (int i = 0; i < positions.size(); i++) {
            Position pos = positions.get(i);
            int index = holder.page * positions.size() + i;
            if (index >= upgrades.size()) {
                // non esiste ancora un potenziamento: tasto grigio e icona vuota
                String[] r = general;
                String bp = GUI + "buttons.none";
                ItemStack none = pane(bp, 3, c.getString(bp + ".name", "&8in arrivo"), c.getStringList(bp + ".lore"), r, false);
                put(inv, holder, pos.button(), none, null);
                for (int extra : pos.extra()) {
                    put(inv, holder, extra, invisible(c.getString(bp + ".name", "&8in arrivo"),
                            c.getStringList(bp + ".lore"), r, false), null);
                }
                put(inv, holder, pos.icon(), invisible(" ", List.of(), r, true), null);
                continue;
            }

            String id = upgrades.get(index);
            String[] r = upgradeValues(player, id, li, general);

            // icona del potenziamento: tutte le caratteristiche al livello che hai ora
            String ip = GUI + "icons." + id;
            List<String> iconLore = c.isList(ip + ".lore") ? c.getStringList(ip + ".lore")
                    : c.getStringList(GUI + "icon-lore");
            put(inv, holder, pos.icon(), pane(ip, 10, c.getString(ip + ".name", id), iconLore, r, false), null);

            // tasto: verde / rosso / grigio
            State state = stateOf(player, id);
            String key = switch (state) {
                case AVAILABLE -> "available";
                case LOCKED -> "locked";
                case MAX -> "maxed";
                default -> "none";
            };
            String bp = GUI + "buttons." + key;
            int defaultCmd = state == State.AVAILABLE ? 1 : (state == State.LOCKED || state == State.MAX) ? 2 : 3;
            String name = c.getString(bp + ".name", "livello %level%");
            List<String> lore = c.getStringList(bp + ".lore");
            String action = state == State.AVAILABLE || state == State.LOCKED || state == State.MAX ? "buy:" + id : null;
            put(inv, holder, pos.button(), pane(bp, defaultCmd, name, lore, r, false), action);
            for (int extra : pos.extra()) {
                put(inv, holder, extra, invisible(name, lore, r, false), action);
            }
        }
    }

    // ---------- titolo (con i numeri delle pagine) ----------

    /**
     * Titolo della GUI: title.text (qui di solito va lo sfondo disegnato del pack) e, dopo, i numeri
     * delle pagine (title.page.*) in una posizione fissa, con la loro ombra.
     */
    private Component title(int page, int pages) {
        return buildTitle(page, pages).build();
    }

    private GuiTitle buildTitle(int page, int pages) {
        var c = plugin.getConfig();
        GuiTitle t = new GuiTitle(plugin);
        t.text(c.getString(GUI + "title.text", "&8potenziamenti"), c.getString(GUI + "title.font", ""),
                GUI + "title");
        String pageRaw = c.getString(GUI + "title.page.text", "");
        if (pageRaw != null && !pageRaw.isBlank()) {
            String plain = BlockMania.stripCodes(pageRaw)
                    .replace("%page%", String.valueOf(page + 1))
                    .replace("%pages%", String.valueOf(pages))
                    .replace("%slash%", "/");
            String set = c.getString(GUI + "title.page.set", "main");
            String shadowSet = c.getString(GUI + "title.page.shadow-set", "main-shadow");
            int center = c.getInt(GUI + "title.page.center", 73);
            int start = center - GuiTitle.widthOf(plugin, set, plain) / 2;
            t.number(start, set, shadowSet, c.getBoolean(GUI + "title.page.shadow", true), plain,
                    c.getString(GUI + "title.page.font", ""));
        }
        return t;
    }

    private static String codes(String text) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch < 32 || ch > 126) {
                sb.append(String.format("\\u%04x", (int) ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Per /bm guidebug: mostra i valori che il plugin calcola per mettere i numeri delle pagine. */
    public List<String> debugTitle() {
        var c = plugin.getConfig();
        List<String> out = new ArrayList<>();
        String raw = c.getString(GUI + "title.text", "");
        ConfigurationSection w = c.getConfigurationSection(GUI + "title.widths");
        out.add("&etitle.text = &f" + codes(raw));
        out.add("&ewidths = &f" + (w == null ? "NON TROVATO" : w.getKeys(false).stream()
                .map(k -> codes(k) + "=" + w.getInt(k)).toList()));
        GuiTitle t = new GuiTitle(plugin).text(raw, "", GUI + "title");
        out.add("&eavanzamento del titolo = &f" + t.cursor());
        out.add("&egruppo glyphs.main = &f" + codes(c.getString("glyphs.main", "")));
        out.add("&ecenter = &f" + c.getInt(GUI + "title.page.center", 73));
        return out;
    }

    // ---------- aprire e usare la GUI ----------

    public void open(Player player, LauncherItem li) {
        open(player, li, 0);
    }

    private void open(Player player, LauncherItem li, int page) {
        Holder holder = new Holder(li.id(), page);
        int pages = pageCount();
        holder.page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(holder, SIZE, title(holder.page, pages));
        holder.inventory = inv;
        fill(inv, holder, player, li);
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

        String action = holder.actions.get(event.getSlot());
        if (action == null) {
            return;
        }
        int pages = pageCount();
        switch (action) {
            case "close" -> player.closeInventory();
            case "prev" -> {
                if (holder.page > 0) {
                    plugin.playConfiguredSound(player.getLocation(), "upgrades.gui.sounds.page");
                    open(player, li, holder.page - 1);
                }
            }
            case "next" -> {
                if (holder.page < pages - 1) {
                    plugin.playConfiguredSound(player.getLocation(), "upgrades.gui.sounds.page");
                    open(player, li, holder.page + 1);
                }
            }
            default -> {
                if (action.startsWith("buy:")) {
                    buy(player, action.substring(4), li, holder);
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    private void buy(Player player, String id, LauncherItem li, Holder holder) {
        String label = plugin.tiny(plugin.getConfig().getString("upgrades." + id + ".label", id));
        int level = getLevel(player, id);

        if (level >= maxLevel(id)) {
            plugin.send(player, "upgrade-max", "%upgrade%", label);
            plugin.playConfiguredSound(player.getLocation(), "upgrades.gui.sounds.error");
            return;
        }
        if (level >= levelCap(player)) {
            plugin.send(player, "upgrade-locked", "%upgrade%", label, "%level%", String.valueOf(level));
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

        fill(holder.inventory, holder, player, li);
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
            if (player.isSneaking() && i == inv.getHeldItemSlot()) {
                plugin.applyModel(fresh, li, true);
            }
            inv.setItem(i, fresh);
        }
    }
}
