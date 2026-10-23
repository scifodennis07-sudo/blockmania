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
 * GUI delle miniere (/mines). Ogni miniera e' un quadrato 2x2: il pannello col modello sta in uno
 * slot solo, ma nome e lore valgono per tutti e 4. Il modello cambia a ogni volta che la miniera
 * viene rifatta (platinatura).
 */
public final class MinesGui implements Listener {

    private static final int SIZE = 54;
    private static final String GUI = "mines-gui.";

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private int page;
        private final Map<Integer, String> actions = new HashMap<>();

        Holder(int page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Position(int icon, List<Integer> extra) {
    }

    private final BlockMania plugin;

    public MinesGui(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ---------- dati delle miniere ----------

    private int mineCount() {
        return Math.max(1, plugin.getConfig().getInt(GUI + "count", 21));
    }

    private int maxRuns() {
        return Math.max(1, plugin.getConfig().getInt(GUI + "max-runs", 10));
    }

    /** Quanti blocchi ci sono da scavare in una miniera. */
    public int blocksOf(int mine) {
        var c = plugin.getConfig();
        return Math.max(0, c.getInt(GUI + "blocks." + mine, c.getInt(GUI + "blocks.default", 3000)));
    }

    /** Quante volte il giocatore ha finito la miniera (0..max-runs). */
    public int runs(Player player, int mine) {
        return plugin.getAmmo().getCounter(player, "mine_runs_" + mine);
    }

    public int progress(Player player, int mine) {
        return plugin.getAmmo().getCounter(player, "mine_progress_" + mine);
    }

    public int total(Player player, int mine) {
        return plugin.getAmmo().getCounter(player, "mine_total_" + mine);
    }

    // ---------- elementi ----------

    private List<Position> positions() {
        List<Position> out = new ArrayList<>();
        for (Map<?, ?> map : plugin.getConfig().getMapList(GUI + "slots.positions")) {
            try {
                int icon = Integer.parseInt(String.valueOf(map.get("icon")));
                List<Integer> extra = new ArrayList<>();
                if (map.get("extra") instanceof List<?> list) {
                    for (Object o : list) {
                        extra.add(Integer.parseInt(String.valueOf(o)));
                    }
                }
                out.add(new Position(icon, extra));
            } catch (NumberFormatException ignored) {
                // riga non valida: la salto
            }
        }
        return out;
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

    private int pageCount() {
        int perPage = Math.max(1, positions().size());
        return Math.max(1, (mineCount() + perPage - 1) / perPage);
    }

    private Material material(String path) {
        Material m = Material.matchMaterial(plugin.getConfig().getString(path + ".material", "GLASS_PANE"));
        return m == null || !m.isItem() ? Material.GLASS_PANE : m;
    }

    private ItemStack pane(String path, int cmd, String name, List<String> lore, String[] r, boolean hide) {
        ItemStack stack = new ItemStack(material(path));
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(cmd);
        if (hide) {
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

    private ItemStack invisible(String name, List<String> lore, String[] r, boolean hide) {
        return pane(GUI + "invisible", plugin.getConfig().getInt(GUI + "invisible.cmd", 999), name, lore, r, hide);
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

    /**
     * Modello del pannello. Ogni miniera ha stages+1 modelli di fila: prima il grigio (bloccata),
     * poi le fasi 1..stages (la fase sale a ogni volta che rifai la miniera).
     * Con cmd-start 101 e 10 fasi: miniera 1 = 101 grigio, 102 = 1-1 ... 111 = 1-10; miniera 2 = 112 ...
     */
    private int modelOf(int mine, int runs, boolean locked) {
        var c = plugin.getConfig();
        int stages = Math.max(1, c.getInt(GUI + "stages", 10));
        int grey = c.getInt(GUI + "cmd-start", 101) + (mine - 1) * (stages + 1);
        return locked ? grey : grey + Math.min(stages, runs + 1);
    }

    private String[] general(Player player, int page, int pages) {
        AmmoManager ammo = plugin.getAmmo();
        return new String[]{
                "%coin%", String.valueOf(ammo.getCoin(player)),
                "%blockcoin%", String.valueOf(ammo.getBlockCoin(player)),
                "%mine%", String.valueOf(ammo.getMine(player)),
                "%page%", String.valueOf(page + 1),
                "%pages%", String.valueOf(pages)
        };
    }

    private void fill(Inventory inv, Holder holder, Player player) {
        var c = plugin.getConfig();
        List<Position> positions = positions();
        int pages = pageCount();
        holder.page = Math.max(0, Math.min(holder.page, pages - 1));
        String[] general = general(player, holder.page, pages);

        holder.actions.clear();
        ItemStack filler = invisible(" ", List.of(), general, true);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, filler);
        }
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

        int owned = plugin.getAmmo().getMine(player);
        int max = maxRuns();
        for (int i = 0; i < positions.size(); i++) {
            int mine = holder.page * positions.size() + i + 1;
            Position pos = positions.get(i);
            if (mine > mineCount()) {
                // quadrato senza miniera: modello "non disponibile"
                String ep = GUI + "states.empty";
                String ename = c.getString(ep + ".name", "&8miniera non disponibile");
                List<String> elore = c.getStringList(ep + ".lore");
                put(inv, holder, pos.icon(), pane(ep, c.getInt(GUI + "empty-cmd", 332), ename, elore, general, false), null);
                for (int slot : pos.extra()) {
                    put(inv, holder, slot, invisible(ename, elore, general, false), null);
                }
                continue;
            }
            int runs = Math.min(max, runs(player, mine));
            int blocks = blocksOf(mine);
            int progress = Math.min(blocks, progress(player, mine));
            String key = runs >= max ? "platinum" : mine > owned ? "locked" : "owned";
            String bp = GUI + "states." + key;
            String[] r = new String[general.length + 18];
            System.arraycopy(general, 0, r, 0, general.length);
            String[] extra = {
                    "%number%", String.valueOf(mine),
                    "%blocks%", String.valueOf(blocks),
                    "%broken%", String.valueOf(progress),
                    "%left%", String.valueOf(Math.max(0, blocks - progress)),
                    "%total%", String.valueOf(total(player, mine)),
                    "%runs%", String.valueOf(runs),
                    "%max_runs%", String.valueOf(max),
                    "%price%", String.valueOf(c.getInt(GUI + "prices." + mine, blocks)),
                    "%redo_price%", String.valueOf(c.getInt(GUI + "redo-prices." + mine, blocks))
            };
            System.arraycopy(extra, 0, r, general.length, extra.length);
            r = java.util.Arrays.copyOf(r, general.length + extra.length);

            String name = c.getString(bp + ".name", "&fminiera %number%");
            List<String> lore = c.getStringList(bp + ".lore");
            put(inv, holder, pos.icon(), pane(bp, modelOf(mine, runs, key.equals("locked")), name, lore, r, false), null);
            for (int slot : pos.extra()) {
                put(inv, holder, slot, invisible(name, lore, r, false), null);
            }
        }
    }

    // ---------- titolo ----------

    private Component title(int page, int pages) {
        var c = plugin.getConfig();
        GuiTitle t = new GuiTitle(plugin);
        t.text(c.getString(GUI + "title.text", "&8miniere"), c.getString(GUI + "title.font", ""), GUI + "title");
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
        return t.build();
    }

    // ---------- aprire e usare ----------

    public void open(Player player) {
        open(player, 0);
    }

    private void open(Player player, int page) {
        Holder holder = new Holder(page);
        int pages = pageCount();
        holder.page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(holder, SIZE, title(holder.page, pages));
        holder.inventory = inv;
        fill(inv, holder, player);
        player.openInventory(inv);
        plugin.playConfiguredSound(player.getLocation(), "mines-gui.sounds.open");
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !(clicked.getHolder() instanceof Holder)) {
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
                    plugin.playConfiguredSound(player.getLocation(), "mines-gui.sounds.page");
                    open(player, holder.page - 1);
                }
            }
            case "next" -> {
                if (holder.page < pages - 1) {
                    plugin.playConfiguredSound(player.getLocation(), "mines-gui.sounds.page");
                    open(player, holder.page + 1);
                }
            }
            default -> {
                // le miniere per ora non fanno niente al clic
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
