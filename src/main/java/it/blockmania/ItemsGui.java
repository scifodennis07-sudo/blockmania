package it.blockmania;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * La GUI con tutti gli oggetti custom (/bm items): in alto le categorie, al centro gli oggetti
 * (clicca per riceverli), a destra le frecce per scorrere le pagine.
 */
public final class ItemsGui implements Listener {

    private static final String P = "items-gui.";
    private static final int SIZE = 54;

    private record Entry(ItemStack give, ItemStack show) {
    }

    /** Serve a riconoscere la nostra GUI tra tutti gli inventari. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private int category;
        private int page;
        private final Map<Integer, String> actions = new HashMap<>();
        private final List<Entry> entries = new ArrayList<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BlockMania plugin;

    public ItemsGui(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ---------- lettura del config ----------

    private List<Integer> slots(String name) {
        return plugin.getConfig().getIntegerList(P + "slots." + name);
    }

    private List<Map<?, ?>> categories() {
        return plugin.getConfig().getMapList(P + "categories");
    }

    private static String str(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                out.add(String.valueOf(o));
            }
        } else if (value != null) {
            out.add(String.valueOf(value));
        }
        return out;
    }

    /** Legge una lista di numeri: [1, 2, 5] oppure un testo come "1-53, 60, 70-72". */
    private static List<Integer> numbers(Object value) {
        List<Integer> out = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                parts.add(String.valueOf(o));
            }
        } else if (value != null) {
            parts.addAll(List.of(String.valueOf(value).split(",")));
        }
        for (String part : parts) {
            String p = part.trim();
            try {
                int dash = p.indexOf('-', 1);
                if (dash > 0) {
                    int from = Integer.parseInt(p.substring(0, dash).trim());
                    int to = Integer.parseInt(p.substring(dash + 1).trim());
                    for (int i = from; i <= to && out.size() < 5000; i++) {
                        out.add(i);
                    }
                } else if (!p.isEmpty()) {
                    out.add(Integer.parseInt(p));
                }
            } catch (NumberFormatException ignored) {
                // voce non valida: la salto
            }
        }
        return out;
    }

    // ---------- oggetti della GUI ----------

    private ItemStack invisible(String name, List<String> lore, String[] r) {
        var c = plugin.getConfig();
        Material m = Material.matchMaterial(c.getString(P + "invisible.material", "GLASS_PANE"));
        ItemStack stack = new ItemStack(m == null || !m.isItem() ? Material.GLASS_PANE : m);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(c.getInt(P + "invisible.cmd", 999));
        meta.displayName(plugin.text(name, r));
        if (!lore.isEmpty()) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(plugin.text(line, r));
            }
            meta.lore(lines);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private void put(Inventory inv, Holder holder, int slot, ItemStack item, String action) {
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        inv.setItem(slot, item);
        if (action != null) {
            holder.actions.put(slot, action);
        }
    }

    /** Gli oggetti di una categoria: quello da dare e quello da mostrare (con il suggerimento nella lore). */
    private List<Entry> entries(Player player, Map<?, ?> category) {
        var c = plugin.getConfig();
        List<Entry> out = new ArrayList<>();
        String type = str(category.get("type"), "items").toLowerCase(Locale.ROOT);
        List<String> hint = c.getStringList(P + "item-lore");

        if (type.equals("blocks")) {
            for (int n = 1; n <= plugin.getMaxBlock(); n++) {
                ItemStack give = plugin.createBlockItem(n);
                out.add(new Entry(give, withHint(give, hint)));
            }
            return out;
        }
        if (!type.equals("items")) {
            return out;
        }
        Material material = Material.matchMaterial(str(category.get("material"), "STICK"));
        if (material == null || !material.isItem()) {
            return out;
        }
        for (int cmd : numbers(category.get("cmds"))) {
            LauncherItem launcher = null;
            for (LauncherItem li : plugin.getItems().values()) {
                if (li.material() == material && li.customModelData() != null && li.customModelData() == cmd) {
                    launcher = li;
                    break;
                }
            }
            if (launcher != null) {
                ItemStack give = plugin.createItem(launcher, player);
                out.add(new Entry(give, withHint(give, hint)));
                continue;
            }
            ItemStack give = new ItemStack(material);
            ItemMeta meta = give.getItemMeta();
            meta.setCustomModelData(cmd);
            give.setItemMeta(meta);

            String[] r = {"%material%", material.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                    "%cmd%", String.valueOf(cmd)};
            ItemStack show = give.clone();
            ItemMeta showMeta = show.getItemMeta();
            showMeta.displayName(plugin.text(c.getString(P + "item-name", "&f%material% &8cmd &f%cmd%"), r));
            List<Component> lore = new ArrayList<>();
            for (String line : hint) {
                lore.add(plugin.text(line, r));
            }
            if (!lore.isEmpty()) {
                showMeta.lore(lore);
            }
            show.setItemMeta(showMeta);
            out.add(new Entry(give, show));
        }
        return out;
    }

    private ItemStack withHint(ItemStack give, List<String> hint) {
        ItemStack show = give.clone();
        ItemMeta meta = show.getItemMeta();
        List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
        for (String line : hint) {
            lore.add(plugin.text(line));
        }
        meta.lore(lore);
        show.setItemMeta(meta);
        return show;
    }

    private int perPage() {
        return Math.max(1, slots("grid").size());
    }

    // ---------- titolo ----------

    private Component title(int page, int pages) {
        var c = plugin.getConfig();
        GuiTitle t = new GuiTitle(plugin);
        t.text(c.getString(P + "title.text", "&8oggetti"), c.getString(P + "title.font", ""), P + "title");
        if (c.getBoolean(P + "title.numbers.enabled", true)) {
            int center = c.getInt(P + "title.numbers.center", 156);
            boolean shadow = c.getBoolean(P + "title.numbers.shadow", true);
            String font = c.getString(P + "title.numbers.font", "");
            String current = String.valueOf(page + 1);
            String total = String.valueOf(pages);
            String topSet = c.getString(P + "title.numbers.current-set", "top");
            String topShadow = c.getString(P + "title.numbers.current-shadow-set", "top-shadow");
            String bottomSet = c.getString(P + "title.numbers.total-set", "bottom");
            String bottomShadow = c.getString(P + "title.numbers.total-shadow-set", "bottom-shadow");
            t.number(center - GuiTitle.widthOf(plugin, topSet, current) / 2, topSet, topShadow, shadow, current, font);
            t.number(center - GuiTitle.widthOf(plugin, bottomSet, total) / 2, bottomSet, bottomShadow, shadow,
                    total, font);
        }
        return t.build();
    }

    // ---------- aprire e riempire ----------

    public void open(Player player) {
        open(player, 0, 0);
    }

    private void open(Player player, int category, int page) {
        List<Map<?, ?>> cats = categories();
        Holder holder = new Holder();
        holder.category = Math.max(0, Math.min(category, Math.max(0, cats.size() - 1)));
        holder.page = Math.max(0, page);
        if (!cats.isEmpty()) {
            holder.entries.addAll(entries(player, cats.get(holder.category)));
        }
        int pages = Math.max(1, (holder.entries.size() + perPage() - 1) / perPage());
        holder.page = Math.min(holder.page, pages - 1);

        Inventory inv = Bukkit.createInventory(holder, SIZE, title(holder.page, pages));
        holder.inventory = inv;
        fill(holder, cats, pages);
        player.openInventory(inv);
        plugin.playConfiguredSound(player.getLocation(), "items-gui.sounds.open");
    }

    private void fill(Holder holder, List<Map<?, ?>> cats, int pages) {
        var c = plugin.getConfig();
        Inventory inv = holder.inventory;
        inv.clear();
        holder.actions.clear();
        String categoryName = cats.isEmpty() ? "" : BlockMania.stripCodes(str(cats.get(holder.category).get("name"), ""));
        String[] r = {"%page%", String.valueOf(holder.page + 1), "%pages%", String.valueOf(pages),
                "%category%", categoryName, "%count%", String.valueOf(holder.entries.size())};

        for (int slot : slots("close")) {
            put(inv, holder, slot, invisible(c.getString(P + "close.name", "&cchiudi"),
                    c.getStringList(P + "close.lore"), r), "close");
        }

        // categorie (la barra in alto)
        List<Integer> catSlots = slots("categories");
        for (int i = 0; i < catSlots.size() && i < cats.size(); i++) {
            Map<?, ?> cat = cats.get(i);
            boolean empty = str(cat.get("type"), "items").equalsIgnoreCase("none");
            List<String> lore = new ArrayList<>(strings(cat.get("lore")));
            if (!empty) {
                lore.add(i == holder.category ? c.getString(P + "selected-line", "&a► aperta")
                        : c.getString(P + "click-line", "&eclicca per aprire"));
            }
            put(inv, holder, catSlots.get(i), invisible(str(cat.get("name"), "categoria"), lore, r),
                    empty ? null : "cat:" + i);
        }
        for (int i = cats.size(); i < catSlots.size(); i++) {
            put(inv, holder, catSlots.get(i), invisible(c.getString(P + "empty-category.name", "&8in arrivo"),
                    c.getStringList(P + "empty-category.lore"), r), null);
        }

        // frecce e numero di pagina (colonna a destra)
        for (int slot : slots("up")) {
            put(inv, holder, slot, invisible(c.getString(P + "up.name", "&7pagina precedente"),
                    c.getStringList(P + "up.lore"), r), "up");
        }
        for (int slot : slots("page")) {
            put(inv, holder, slot, invisible(c.getString(P + "page.name", "&7pagina &f%page%&8/&f%pages%"),
                    c.getStringList(P + "page.lore"), r), null);
        }
        for (int slot : slots("down")) {
            put(inv, holder, slot, invisible(c.getString(P + "down.name", "&7pagina successiva"),
                    c.getStringList(P + "down.lore"), r), "down");
        }

        // oggetti
        List<Integer> grid = slots("grid");
        int start = holder.page * grid.size();
        for (int i = 0; i < grid.size(); i++) {
            int index = start + i;
            if (index >= holder.entries.size()) {
                break;
            }
            put(inv, holder, grid.get(i), holder.entries.get(index).show().clone(), "give:" + index);
        }
    }

    // ---------- click ----------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || clicked != holder.inventory) {
            return;
        }
        String action = holder.actions.get(event.getSlot());
        if (action == null) {
            return;
        }
        List<Map<?, ?>> cats = categories();
        int pages = Math.max(1, (holder.entries.size() + perPage() - 1) / perPage());

        if (action.equals("close")) {
            player.closeInventory();
        } else if (action.startsWith("cat:")) {
            int index = Integer.parseInt(action.substring(4));
            if (index != holder.category) {
                plugin.playConfiguredSound(player.getLocation(), "items-gui.sounds.page");
                open(player, index, 0);
            }
        } else if (action.equals("up")) {
            if (holder.page > 0) {
                plugin.playConfiguredSound(player.getLocation(), "items-gui.sounds.page");
                open(player, holder.category, holder.page - 1);
            }
        } else if (action.equals("down")) {
            if (holder.page < pages - 1) {
                plugin.playConfiguredSound(player.getLocation(), "items-gui.sounds.page");
                open(player, holder.category, holder.page + 1);
            }
        } else if (action.startsWith("give:")) {
            int index = Integer.parseInt(action.substring(5));
            if (index < 0 || index >= holder.entries.size() || cats.isEmpty()) {
                return;
            }
            give(player, holder.entries.get(index).give(), event.isShiftClick());
        }
    }

    private void give(Player player, ItemStack template, boolean stack) {
        ItemStack item = template.clone();
        item.setAmount(stack ? Math.max(1, item.getMaxStackSize()) : 1);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack extra : leftover.values()) {
            player.getWorld().dropItem(player.getLocation(), extra);
        }
        ItemMeta meta = template.getItemMeta();
        String name;
        if (meta != null && meta.displayName() != null) {
            name = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        } else {
            name = plugin.tiny(template.getType().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        }
        plugin.send(player, "given", "%item%", name);
        plugin.playConfiguredSound(player.getLocation(), "items-gui.sounds.give");
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
