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
                put(inv, holder, pos.icon(), invisible(ename, elore, general, false), null);
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
            String actionKey = key.equals("locked") ? (mine == owned + 1 ? "buy" : "far") : key;
            if (actionKey.equals("platinum") || actionKey.equals("owned")) {
                actionKey = key;
            }
            String actionText = c.getString(GUI + "actions." + actionKey,
                    actionKey.equals("buy") ? "&eclicca per acquistare"
                            : actionKey.equals("owned") || actionKey.equals("platinum") ? "&eclicca per entrare" : "");
            List<String> lore = new ArrayList<>();
            for (String line : c.getStringList(bp + ".lore")) {
                if (line.contains("%action%")) {
                    if (actionText.isEmpty()) {
                        continue; // nessuna azione: la riga sparisce
                    }
                    line = line.replace("%action%", actionText);
                }
                lore.add(line);
            }
            String click = actionKey.equals("buy") ? "buy:" + mine : actionKey.equals("far") ? "far:" + mine
                    : "enter:" + mine;
            put(inv, holder, pos.icon(), invisible(name, lore, r, false), click);
            for (int slot : pos.extra()) {
                put(inv, holder, slot, invisible(name, lore, r, false), click);
            }
        }
    }

    // ---------- titolo ----------

    // ---------- titolo (pack v3: tutto a font, una sola GUI base) ----------

    /** Fonts del pack v3: immagini intere / cubetti riga alta / cubetti riga bassa (vedi PACK_V3.md). */
    static final String F_MINES = "minecraft:mines";
    static final String F_R1 = "minecraft:mines_r1";
    static final String F_R2 = "minecraft:mines_r2";
    private static final char G_BASE = '\uE200';
    private static final char G_RESET = '\uE201';       // pulsante reset blu (solo staff)
    private static final char G_LINK = '\uE201';        // E202..E206 = barre verdi 1..5 (G_LINK + i)
    private static final char G_CONFIRM = '\uE207';     // pannello "conferma o annulla"
    private static final char G_CUBE = '\uE300';        // + indice cubo
    /** Colonna (shift = X-15) e riga (1 = alta, 2 = bassa) dei 6 quadrati della pagina, nell'ordine di slots.positions. */
    private static final int[] POS_X = {3, 3, 57, 57, 111, 111};
    private static final int[] POS_ROW = {1, 2, 2, 1, 1, 2};
    /** Tinta dello sfondo scurito sotto i popup (stessa dei ticket: x0.6). */
    private static final int DIM_FACTOR = 154;

    /** Indice del cubo nell'atlante: (N-1)*11+(k-1), k=1..10 fasi colorate, 11 = grigio; 231 = quadrato senza miniera. */
    static int cubeIndex(int mine, int runs, boolean locked) {
        int k = locked ? 11 : Math.max(1, Math.min(10, runs + 1));
        return (mine - 1) * 11 + (k - 1);
    }

    /** Un cubo per ogni posizione della pagina (-1 = niente), calcolato sul giocatore. */
    int[] cubes(Player player, int page) {
        int perPage = Math.max(1, positions().size());
        int owned = plugin.getAmmo().getMine(player);
        int max = maxRuns();
        int[] out = new int[Math.min(perPage, POS_X.length)];
        for (int i = 0; i < out.length; i++) {
            int mine = page * perPage + i + 1;
            if (mine > mineCount()) {
                out[i] = 231;
            } else {
                out[i] = cubeIndex(mine, Math.min(max, runs(player, mine)), mine > owned);
            }
        }
        return out;
    }

    /** Quanti collegamenti verdi: (miniere sbloccate in questa pagina) - 1. */
    int greens(Player player, int page) {
        int perPage = Math.max(1, positions().size());
        int onPage = Math.max(0, Math.min(perPage, plugin.getAmmo().getMine(player) - page * perPage));
        return Math.max(0, onPage - 1);
    }

    /** Scurisce i colori di un titolo gia' composto (x0.6), come Tickets.tint. */
    static Component tint(Component c, net.kyori.adventure.text.format.TextColor inherited) {
        net.kyori.adventure.text.format.TextColor col = c.color() != null ? c.color() : inherited;
        net.kyori.adventure.text.format.TextColor nc = col == null
                ? net.kyori.adventure.text.format.TextColor.color(0x999999)
                : net.kyori.adventure.text.format.TextColor.color(col.red() * DIM_FACTOR / 255,
                        col.green() * DIM_FACTOR / 255, col.blue() * DIM_FACTOR / 255);
        List<Component> kids = new ArrayList<>();
        for (Component k : c.children()) {
            kids.add(tint(k, col));
        }
        return c.color(nc).children(kids);
    }

    /**
     * Titolo completo della GUI miniere. staffReset: pulsante reset blu al posto dell'info; dim: sfondo scurito
     * e pannello di conferma sopra; top: nome nella barra in alto; showPage: numero pagina.
     * Tutte le immagini a immagine intera avanzano 186 (reali): dopo l'ultima si mette il cursore 7 px indietro
     * (convenzione dei ticket: IMG = -22), cosi' titolo e numero pagina cadono dove cadevano nelle GUI originali.
     */
    static Component fontTitle(BlockMania plugin, int greens, int[] cubes, boolean staffReset, boolean dim,
                               String top, int page, int pages, boolean showPage) {
        GuiTitle content = new GuiTitle(plugin);
        content.at(-15, "&f" + G_BASE, F_MINES, 186);
        for (int i = 1; i <= Math.min(5, greens); i++) {
            content.at(-15, "&f" + (char) (G_LINK + i), F_MINES, 186);
        }
        if (staffReset) {
            content.at(-15, "&f" + G_RESET, F_MINES, 186);
        }
        for (int i = 0; i < cubes.length && i < POS_X.length; i++) {
            if (cubes[i] < 0) {
                continue;
            }
            content.at(POS_X[i], "&f" + (char) (G_CUBE + cubes[i]), POS_ROW[i] == 1 ? F_R1 : F_R2, 30);
        }
        GuiTitle g = new GuiTitle(plugin);
        Component body = content.build();
        g.atComponent(0, dim ? tint(body, null) : body, content.cursor());
        if (dim) {
            g.at(-15, "&f" + G_CONFIRM, F_MINES, 186);
        }
        g.atComponent(g.cursor(), Component.empty(), -7);   // cursore 7 px indietro rispetto al reale
        if (top != null && !top.isBlank()) {
            UserProfilePowers.topTitle(plugin, g, top);
        }
        var c = plugin.getConfig();
        String pageRaw = c.getString(GUI + "title.page.text", "");
        if (showPage && pageRaw != null && !pageRaw.isBlank()) {
            String plain = BlockMania.stripCodes(pageRaw)
                    .replace("%page%", String.valueOf(page + 1))
                    .replace("%pages%", String.valueOf(pages))
                    .replace("%slash%", "/");
            String set = c.getString(GUI + "title.page.set", "main");
            String shadowSet = c.getString(GUI + "title.page.shadow-set", "main-shadow");
            int center = c.getInt(GUI + "title.page.center", 73);
            int start = center - GuiTitle.widthOf(plugin, set, plain) / 2;
            g.number(start, set, shadowSet, c.getBoolean(GUI + "title.page.shadow", true), plain,
                    c.getString(GUI + "title.page.font", ""));
        }
        return g.build();
    }

    private Component title(Player player, int page, int pages) {
        return fontTitle(plugin, greens(player, page), cubes(player, page), false, false, "miniere", page, pages, true);
    }

    // ---------- aprire e usare ----------

    public void open(Player player) {
        open(player, 0);
    }

    private void open(Player player, int page) {
        open(player, page, true);
    }

    private void open(Player player, int page, boolean sound) {
        Holder holder = new Holder(page);
        int pages = pageCount();
        holder.page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(holder, SIZE, title(player, holder.page, pages));
        holder.inventory = inv;
        fill(inv, holder, player);
        player.openInventory(inv);
        if (sound) {
            plugin.playConfiguredSound(player.getLocation(), "mines-gui.sounds.open");
        }
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
        plugin.guiClick(player);
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
                if (action.startsWith("enter:")) {
                    try {
                        int mine = Integer.parseInt(action.substring(6));
                        player.closeInventory();
                        plugin.getMineTemplates().enter(player, mine);
                    } catch (NumberFormatException ignored) {
                        // azione rovinata
                    }
                } else if (action.startsWith("buy:") || action.startsWith("far:")) {
                    int mine;
                    try {
                        mine = Integer.parseInt(action.substring(4));
                    } catch (NumberFormatException ex) {
                        return;
                    }
                    buy(player, holder, mine);
                }
            }
        }
    }

    /** Compra la miniera successiva a quella che hai, pagando in blockcoin. */
    private void buy(Player player, Holder holder, int mine) {
        AmmoManager ammo = plugin.getAmmo();
        int owned = ammo.getMine(player);
        if (mine != owned + 1) {
            plugin.send(player, "mine-far", "%mine%", String.valueOf(mine));
            plugin.playConfiguredSound(player.getLocation(), "mines-gui.sounds.error");
            return;
        }
        var c = plugin.getConfig();
        int price = c.getInt(GUI + "prices." + mine, blocksOf(mine));
        int have = ammo.getBlockCoin(player);
        if (have < price) {
            plugin.send(player, "mine-no-funds", "%mine%", String.valueOf(mine),
                    "%price%", String.valueOf(price), "%blockcoin%", String.valueOf(have));
            plugin.playConfiguredSound(player.getLocation(), "mines-gui.sounds.error");
            return;
        }
        ammo.setBlockCoin(player, have - price);
        ammo.setMine(player, mine);
        plugin.playConfiguredSound(player.getLocation(), "mines-gui.sounds.buy");
        plugin.send(player, "mine-bought", "%mine%", String.valueOf(mine), "%price%", String.valueOf(price));
        ammo.refreshMain(player);
        // riapre la stessa pagina: cosi' il titolo (i collegamenti verdi) si aggiorna
        open(player, holder.page, false);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }
}
