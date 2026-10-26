package it.blockmania;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Le varianti di un note block: si apre con il clic destro su un blocco nella lista (/bm items).
 * Mostra le 10 varianti del blocco, con le frecce si passa al blocco prima o dopo e al centro c'e' il numero (1/21).
 */
public final class BlockVariantsGui implements Listener {

    private static final String P = "variants-gui.";
    private static final int SIZE = 54;

    /** Serve a riconoscere la nostra GUI tra tutti gli inventari. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private int number;
        private final Map<Integer, String> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BlockMania plugin;

    public BlockVariantsGui(BlockMania plugin) {
        this.plugin = plugin;
    }

    private List<Integer> slots(String name) {
        return plugin.getConfig().getIntegerList(P + "slots." + name);
    }

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

    private void put(Holder holder, int slot, ItemStack item, String action) {
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        holder.inventory.setItem(slot, item);
        if (action != null) {
            holder.actions.put(slot, action);
        }
    }

    // ---------- titolo ----------

    private Component title(int number, int max) {
        var c = plugin.getConfig();
        GuiTitle t = new GuiTitle(plugin);
        t.text(c.getString(P + "title.text", "&8varianti"), c.getString(P + "title.font", ""), P + "title");
        String plain = number + "/" + max;
        String set = c.getString(P + "title.number.set", "bar");
        String shadowSet = c.getString(P + "title.number.shadow-set", "bar-shadow");
        int center = c.getInt(P + "title.number.center", 73);
        int start = center - GuiTitle.widthOf(plugin, set, plain) / 2;
        t.number(start, set, shadowSet, c.getBoolean(P + "title.number.shadow", true), plain,
                c.getString(P + "title.number.font", ""));
        return t.build();
    }

    // ---------- aprire e riempire ----------

    public void open(Player player, int number) {
        int max = plugin.getMaxBlock();
        Holder holder = new Holder();
        holder.number = Math.max(1, Math.min(max, number));
        Inventory inv = Bukkit.createInventory(holder, SIZE, title(holder.number, max));
        holder.inventory = inv;
        fill(holder, max);
        player.openInventory(inv);
        plugin.playConfiguredSound(player.getLocation(), "variants-gui.sounds.open");
    }

    private void fill(Holder holder, int max) {
        var c = plugin.getConfig();
        int n = holder.number;
        String[] r = {"%number%", String.valueOf(n), "%max%", String.valueOf(max),
                "%health%", String.valueOf(plugin.getBlocks().maxHealth(n))};

        for (int slot : slots("close")) {
            put(holder, slot, invisible(c.getString(P + "close.name", "&cindietro"),
                    c.getStringList(P + "close.lore"), r), "close");
        }
        for (int slot : slots("info")) {
            put(holder, slot, invisible(c.getString(P + "info.name", "&fblocco %number%"),
                    c.getStringList(P + "info.lore"), r), null);
        }
        for (int slot : slots("previous")) {
            put(holder, slot, invisible(c.getString(P + "previous.name", "&7blocco precedente"),
                    c.getStringList(P + "previous.lore"), r), n > 1 ? "previous" : null);
        }
        for (int slot : slots("next")) {
            put(holder, slot, invisible(c.getString(P + "next.name", "&7blocco successivo"),
                    c.getStringList(P + "next.lore"), r), n < max ? "next" : null);
        }
        for (int slot : slots("page")) {
            put(holder, slot, invisible(c.getString(P + "page.name", "&7blocco &f%number%&8/&f%max%"),
                    c.getStringList(P + "page.lore"), r), null);
        }

        // le varianti (una per slot di "grid", dalla 1 alla 10)
        List<Integer> grid = slots("grid");
        List<String> hint = c.getStringList(P + "item-lore");
        for (int i = 0; i < grid.size() && i < BlockManager.VARIANTS; i++) {
            ItemStack give = plugin.createBlockItem(n, i + 1);
            ItemStack show = give.clone();
            ItemMeta meta = show.getItemMeta();
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            for (String line : hint) {
                lore.add(plugin.text(line));
            }
            meta.lore(lore);
            show.setItemMeta(meta);
            put(holder, grid.get(i), show, "give:" + (i + 1));
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
        int max = plugin.getMaxBlock();
        if (action.equals("close")) {
            plugin.playConfiguredSound(player.getLocation(), "variants-gui.sounds.page");
            plugin.getItemsGui().openBlocks(player, holder.number);
        } else if (action.equals("previous") && holder.number > 1) {
            plugin.playConfiguredSound(player.getLocation(), "variants-gui.sounds.page");
            open(player, holder.number - 1);
        } else if (action.equals("next") && holder.number < max) {
            plugin.playConfiguredSound(player.getLocation(), "variants-gui.sounds.page");
            open(player, holder.number + 1);
        } else if (action.startsWith("give:")) {
            int variant = Integer.parseInt(action.substring(5));
            plugin.getItemsGui().give(player, plugin.createBlockItem(holder.number, variant), event.isShiftClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
