package it.blockmania;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Miniere nel profilo staff: stessa GUI di /mines (MinesGui) ma sul giocatore TARGET e con lo sfondo
 * "staff" (tasto reset blu al posto dell'info). Click su miniera posseduta = lo staffer ci va,
 * click su grigia = la da' (solo la prossima), Q = la toglie (solo l'ultima, con conferma),
 * reset = toglie tutte (con conferma).
 *
 * Sfondi staff / conferma: lista per numero di collegamenti verdi (0..5) configurabile
 * (userprofile.mines.backgrounds, userprofile.mines.confirm.backgrounds); se vuota si usano gli sfondi
 * originali e la conferma generica di UserProfilePowers.
 */
public final class UserProfileMines implements Listener {

    private static final int SIZE = 54;
    private static final String GUI = "mines-gui.";
    private static final String CFG = "userprofile.mines.";

    /** Sfondi staff per 0..5 collegamenti verdi (da STAFF_GUIS.md di Sara). Vuoto = originali. */
    private static final List<String> STAFF_BACKGROUNDS =
            List.of("\uE110", "\uE111", "\uE112", "\uE113", "\uE114", "\uE115");
    /** Sfondi con popup di conferma per 0..5 collegamenti verdi. Vuoto = conferma generica. */
    private static final List<String> CONFIRM_BACKGROUNDS =
            List.of("\uE118", "\uE119", "\uE11A", "\uE11B", "\uE11C", "\uE11D");
    /** Slot dei bottoni CONFERMA (verde) e ANNULLA (rosso) nel popup di conferma (riga 3, come userprofile-6). */
    private static final List<Integer> CONFIRM_YES = List.of(28, 29, 30);
    private static final List<Integer> CONFIRM_NO = List.of(32, 33, 34);
    private static final String FONT_UP = "minecraft:userprofile";

    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private int page;
        private UUID target;
        private Runnable onBack;
        private boolean confirm;
        private Runnable onYes;
        private Runnable onNo;
        private final Map<Integer, String> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Position(int icon, List<Integer> extra) {
    }

    private final BlockMania plugin;
    private final UserProfilePowers ui;

    public UserProfileMines(BlockMania plugin, UserProfilePowers ui) {
        this.plugin = plugin;
        this.ui = ui;
    }

    public boolean owns(Player p) {
        return p.getOpenInventory().getTopInventory().getHolder() instanceof Holder;
    }

    // ---------- dati (come MinesGui) ----------

    private int mineCount() {
        return Math.max(1, plugin.getConfig().getInt(GUI + "count", 21));
    }

    private int maxRuns() {
        return Math.max(1, plugin.getConfig().getInt(GUI + "max-runs", 10));
    }

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

    private int blocksOf(int mine) {
        var c = plugin.getConfig();
        return Math.max(0, c.getInt(GUI + "blocks." + mine, c.getInt(GUI + "blocks.default", 3000)));
    }

    private Material material(String path) {
        Material m = Material.matchMaterial(plugin.getConfig().getString(path + ".material", "GLASS_PANE"));
        return m == null || !m.isItem() ? Material.GLASS_PANE : m;
    }

    private ItemStack pane(String path, int cmd, String name, List<String> lore, String[] r) {
        ItemStack stack = new ItemStack(material(path));
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(cmd);
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

    private ItemStack invisible(String name, List<String> lore, String[] r) {
        return pane(GUI + "invisible", plugin.getConfig().getInt(GUI + "invisible.cmd", 999), name, lore, r);
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

    private int modelOf(int mine, int runs, boolean locked) {
        var c = plugin.getConfig();
        int stages = Math.max(1, c.getInt(GUI + "stages", 10));
        int grey = c.getInt(GUI + "cmd-start", 101) + (mine - 1) * (stages + 1);
        return locked ? grey : grey + Math.min(stages, runs + 1);
    }

    private int owned(Player target) {
        return plugin.getAmmo().getMine(target);
    }

    private void msg(Player staff, String text) {
        staff.sendMessage(plugin.color(text));
    }

    /** Stessi controlli ruolo di /adminmania (gruppo profile). */
    private boolean authorize(Player p, UUID target, String verb) {
        AdminCommand a = plugin.getAdminCommand();
        if (a == null) {
            return p.isOp();
        }
        return a.authorize(p, "profile", verb, target, 0);
    }

    private void audit(Player p, String text) {
        AdminCommand a = plugin.getAdminCommand();
        if (a != null) {
            a.record(p, text);
        }
    }

    private Player targetOf(Player staff, Holder holder) {
        Player t = Bukkit.getPlayer(holder.target);
        if (t == null || !t.isOnline()) {
            msg(staff, "&cIl giocatore deve essere online per gestire le sue miniere.");
            staff.closeInventory();
            return null;
        }
        return t;
    }

    // ---------- disegno ----------

    private void fill(Inventory inv, Holder holder, Player target) {
        var c = plugin.getConfig();
        List<Position> positions = positions();
        int pages = pageCount();
        holder.page = Math.max(0, Math.min(holder.page, pages - 1));
        AmmoManager ammo = plugin.getAmmo();
        String[] general = {
                "%coin%", String.valueOf(ammo.getCoin(target)),
                "%blockcoin%", String.valueOf(ammo.getBlockCoin(target)),
                "%mine%", String.valueOf(ammo.getMine(target)),
                "%page%", String.valueOf(holder.page + 1),
                "%pages%", String.valueOf(pages)
        };

        holder.actions.clear();
        inv.clear();
        for (int slot : slotList(GUI + "slots.close")) {
            put(inv, holder, slot, invisible(c.getString(GUI + "close.name", "&cchiudi"),
                    c.getStringList(GUI + "close.lore"), general), "close");
        }
        for (int slot : slotList(GUI + "slots.previous")) {
            put(inv, holder, slot, invisible(c.getString(GUI + "previous.name", "&7pagina precedente"),
                    c.getStringList(GUI + "previous.lore"), general), "prev");
        }
        for (int slot : slotList(GUI + "slots.next")) {
            put(inv, holder, slot, invisible(c.getString(GUI + "next.name", "&7pagina successiva"),
                    c.getStringList(GUI + "next.lore"), general), "next");
        }
        // il tasto "info" dell'originale qui e' il RESET (azzera tutte le miniere)
        for (int slot : slotList(GUI + "slots.info")) {
            put(inv, holder, slot, invisible("&cReset miniere",
                    List.of("&7Toglie TUTTE le miniere a " + target.getName() + ".", "", "&eclicca per resettare"), general), "reset");
        }

        int owned = owned(target);
        int max = maxRuns();
        for (int i = 0; i < positions.size(); i++) {
            int mine = holder.page * positions.size() + i + 1;
            Position pos = positions.get(i);
            if (mine > mineCount()) {
                String ep = GUI + "states.empty";
                String ename = c.getString(ep + ".name", "&8miniera non disponibile");
                List<String> elore = c.getStringList(ep + ".lore");
                put(inv, holder, pos.icon(), pane(ep, c.getInt(GUI + "empty-cmd", 332), ename, elore, general), null);
                for (int slot : pos.extra()) {
                    put(inv, holder, slot, invisible(ename, elore, general), null);
                }
                continue;
            }
            int runs = Math.min(max, ammo.getCounter(target, "mine_runs_" + mine));
            int blocks = blocksOf(mine);
            int progress = Math.min(blocks, ammo.getCounter(target, "mine_progress_" + mine));
            String key = runs >= max ? "platinum" : mine > owned ? "locked" : "owned";
            String bp = GUI + "states." + key;
            String[] r = new String[general.length + 18];
            System.arraycopy(general, 0, r, 0, general.length);
            String[] extra = {
                    "%number%", String.valueOf(mine),
                    "%blocks%", String.valueOf(blocks),
                    "%broken%", String.valueOf(progress),
                    "%left%", String.valueOf(Math.max(0, blocks - progress)),
                    "%total%", String.valueOf(ammo.getCounter(target, "mine_total_" + mine)),
                    "%runs%", String.valueOf(runs),
                    "%max_runs%", String.valueOf(max),
                    "%price%", String.valueOf(c.getInt(GUI + "prices." + mine, blocks)),
                    "%redo_price%", String.valueOf(c.getInt(GUI + "redo-prices." + mine, blocks))
            };
            System.arraycopy(extra, 0, r, general.length, extra.length);
            r = java.util.Arrays.copyOf(r, general.length + extra.length);

            boolean have = mine <= owned;
            String action;
            String actionText;
            if (have) {
                action = "enter:" + mine;
                actionText = "&eclicca per visitarla";
            } else if (mine == owned + 1) {
                action = "give:" + mine;
                actionText = "&eclicca per darla al giocatore";
            } else {
                action = "far:" + mine;
                actionText = "&cdagli prima la miniera " + (owned + 1);
            }
            String name = c.getString(bp + ".name", "&fminiera %number%");
            List<String> lore = new ArrayList<>();
            for (String line : c.getStringList(bp + ".lore")) {
                if (line.contains("%action%")) {
                    line = line.replace("%action%", actionText);
                }
                lore.add(line);
            }
            if (have && mine == owned && owned > 1) {
                lore.add("&7Q: elimina questa miniera (con conferma)");
            } else if (have && mine < owned) {
                lore.add("&8Per eliminarla togli prima la " + owned);
            }
            put(inv, holder, pos.icon(), pane(bp, modelOf(mine, runs, key.equals("locked")), name, lore, r), action);
            for (int slot : pos.extra()) {
                put(inv, holder, slot, invisible(name, lore, r), action);
            }
        }
    }

    private int greens(Player target, int page) {
        int perPage = Math.max(1, positions().size());
        int onPage = Math.max(0, Math.min(perPage, owned(target) - page * perPage));
        return Math.max(0, onPage - 1);
    }

    /** Sostituisce il carattere di sfondo del titolo originale con quello dato (per 0..5 verdi). */
    private String swapBackground(String raw, int greens, List<String> wanted) {
        List<String> original = plugin.getConfig().getStringList(GUI + "backgrounds");
        if (wanted.isEmpty()) {
            wanted = original;
        }
        if (greens >= wanted.size()) {
            return raw;
        }
        String target = wanted.get(greens);
        String shown = raw;
        for (String bg : original) {
            if (!bg.isEmpty() && !bg.equals(target)) {
                shown = shown.replace(bg, target);
            }
        }
        return shown;
    }

    private List<String> staffBackgrounds() {
        List<String> cfg = plugin.getConfig().getStringList(CFG + "backgrounds");
        return cfg.isEmpty() ? STAFF_BACKGROUNDS : cfg;
    }

    private List<String> confirmBackgrounds() {
        List<String> cfg = plugin.getConfig().getStringList(CFG + "confirm.backgrounds");
        return cfg.isEmpty() ? CONFIRM_BACKGROUNDS : cfg;
    }

    private Component title(Player target, int page, int pages, boolean confirm) {
        var c = plugin.getConfig();
        String raw = c.getString(GUI + "title.text", "&8miniere");
        int g = greens(target, page);
        String shown = swapBackground(raw, g, confirm ? confirmBackgrounds() : staffBackgrounds());
        GuiTitle t = new GuiTitle(plugin);
        List<String> staff = confirm ? confirmBackgrounds() : staffBackgrounds();
        if (!staff.isEmpty() && g < staff.size()) {
            // glifo staff (font userprofile, advance 186 come le altre GUI del profilo)
            t.at(-15, "&f" + staff.get(g), FONT_UP, 186);
        } else {
            t.text(shown, c.getString(GUI + "title.font", ""), GUI + "title", raw);
        }
        String pageRaw = c.getString(GUI + "title.page.text", "");
        if (!confirm && pageRaw != null && !pageRaw.isBlank()) {
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

    // ---------- aprire ----------

    public void open(Player staff, Player target, Runnable onBack) {
        if (target == null || !target.isOnline()) {
            msg(staff, "&cIl giocatore deve essere online per gestire le sue miniere.");
            return;
        }
        render(staff, target, 0, onBack, true);
    }

    private void render(Player staff, Player target, int page, Runnable onBack, boolean sound) {
        Holder holder = new Holder();
        holder.target = target.getUniqueId();
        holder.onBack = onBack;
        int pages = pageCount();
        holder.page = Math.max(0, Math.min(page, pages - 1));
        Inventory inv = Bukkit.createInventory(holder, SIZE, title(target, holder.page, pages, false));
        holder.inventory = inv;
        fill(inv, holder, target);
        staff.openInventory(inv);
        if (sound) {
            plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.open");
        }
    }

    private void later(Runnable r) {
        if (r != null) {
            Bukkit.getScheduler().runTask(plugin, () -> r.run());
        }
    }

    // ---------- conferma ----------

    private void ask(Player staff, Player target, Holder from, String text, Runnable onYes) {
        int page = from.page;
        Runnable back = from.onBack;
        UUID tid = target.getUniqueId();
        Runnable reopen = () -> {
            Player t = Bukkit.getPlayer(tid);
            if (t != null && staff.isOnline()) {
                render(staff, t, page, back, false);
            }
        };
        List<Integer> yes = slotList(CFG + "confirm.yes-slots");
        List<Integer> no = slotList(CFG + "confirm.no-slots");
        yes = yes.isEmpty() ? CONFIRM_YES : yes;
        no = no.isEmpty() ? CONFIRM_NO : no;
        if (confirmBackgrounds().isEmpty()) {
            // conferma generica (userprofile-6/7)
            ui.confirm(staff, text, onYes, reopen);
            return;
        }
        Holder h = new Holder();
        h.target = tid;
        h.page = page;
        h.onBack = back;
        h.confirm = true;
        h.onYes = onYes;
        h.onNo = reopen;
        Inventory inv = Bukkit.createInventory(h, SIZE, title(target, page, pageCount(), true));
        h.inventory = inv;
        String[] none = new String[0];
        for (int s : yes) {
            put(inv, h, s, invisible("&aconferma", List.of("&7" + text, "", "&eclicca per confermare"), none), "yes");
        }
        for (int s : no) {
            put(inv, h, s, invisible("&cannulla", List.of("&7Torna indietro senza fare niente.", "", "&eclicca per annullare"), none), "no");
        }
        staff.openInventory(inv);
    }

    // ---------- azioni sulle miniere ----------

    private void resetAll(Player target) {
        AmmoManager a = plugin.getAmmo();
        a.setMine(target, 1);
        a.setCounter(target, "mine_max", 1);
        for (int m = 1; m <= mineCount(); m++) {
            a.setCounter(target, "mine_runs_" + m, 0);
            a.setCounter(target, "mine_progress_" + m, 0);
            a.setCounter(target, "mine_total_" + m, 0);
        }
        a.refreshMain(target);
    }

    private void removeLast(Player target, int mine) {
        AmmoManager a = plugin.getAmmo();
        a.setMine(target, Math.max(1, mine - 1));
        a.setCounter(target, "mine_runs_" + mine, 0);
        a.setCounter(target, "mine_progress_" + mine, 0);
        a.setCounter(target, "mine_total_" + mine, 0);
        a.refreshMain(target);
    }

    // ---------- eventi ----------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player staff)) {
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
        plugin.guiClick(staff);
        if (holder.confirm) {
            Runnable r = action.equals("yes") ? holder.onYes : holder.onNo;
            holder.confirm = false;
            later(r);
            return;
        }
        Player target = targetOf(staff, holder);
        if (target == null) {
            return;
        }
        int pages = pageCount();
        boolean drop = event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP;
        switch (action) {
            case "close" -> {
                staff.closeInventory();
                later(holder.onBack);
            }
            case "prev" -> {
                if (holder.page > 0) {
                    plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.page");
                    render(staff, target, holder.page - 1, holder.onBack, false);
                }
            }
            case "next" -> {
                if (holder.page < pages - 1) {
                    plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.page");
                    render(staff, target, holder.page + 1, holder.onBack, false);
                }
            }
            case "reset" -> ask(staff, target, holder, "Togliere TUTTE le miniere a " + target.getName() + "?", () -> {
                Player t = Bukkit.getPlayer(holder.target);
                if (t == null) {
                    msg(staff, "&cIl giocatore non e' piu' online.");
                    return;
                }
                if (!authorize(staff, t.getUniqueId(), "reset")) {
                    return;
                }
                resetAll(t);
                audit(staff, "profile.reset mines " + t.getName());
                msg(staff, "&aMiniere di " + t.getName() + " azzerate.");
                render(staff, t, 0, holder.onBack, false);
            });
            default -> mineAction(staff, target, holder, action, drop);
        }
    }

    private void mineAction(Player staff, Player target, Holder holder, String action, boolean drop) {
        int idx = action.indexOf(':');
        if (idx < 0) {
            return;
        }
        int mine;
        try {
            mine = Integer.parseInt(action.substring(idx + 1));
        } catch (NumberFormatException ex) {
            return;
        }
        int owned = owned(target);
        String kind = action.substring(0, idx);
        if (drop) {
            // Q: elimina, ma solo l'ultima posseduta
            if (!kind.equals("enter")) {
                return;
            }
            if (mine != owned) {
                msg(staff, "&cDevi eliminare prima la miniera " + owned + " (si toglie dall'ultima).");
                plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.error");
                return;
            }
            if (mine <= 1) {
                msg(staff, "&cLa miniera 1 non si elimina: usa il tasto reset.");
                plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.error");
                return;
            }
            ask(staff, target, holder, "Eliminare la miniera " + mine + " a " + target.getName() + "?", () -> {
                Player t = Bukkit.getPlayer(holder.target);
                if (t == null) {
                    msg(staff, "&cIl giocatore non e' piu' online.");
                    return;
                }
                if (owned(t) != mine) {
                    msg(staff, "&cLe miniere sono cambiate: riprova.");
                    render(staff, t, holder.page, holder.onBack, false);
                    return;
                }
                if (!authorize(staff, t.getUniqueId(), "remove")) {
                    return;
                }
                removeLast(t, mine);
                audit(staff, "profile.remove mine " + mine + " " + t.getName());
                msg(staff, "&aMiniera " + mine + " tolta a " + t.getName() + ".");
                render(staff, t, holder.page, holder.onBack, false);
            });
            return;
        }
        switch (kind) {
            case "enter" -> {
                staff.closeInventory();
                plugin.getMineTemplates().visit(staff, target.getUniqueId(), mine);
            }
            case "give" -> {
                if (mine != owned + 1) {
                    msg(staff, "&cDagli prima la miniera " + (owned + 1) + ".");
                    plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.error");
                    return;
                }
                if (!authorize(staff, target.getUniqueId(), "get")) {
                    return;
                }
                plugin.getAmmo().setMine(target, mine);
                plugin.getAmmo().refreshMain(target);
                audit(staff, "profile.get mine " + mine + " " + target.getName());
                plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.buy");
                msg(staff, "&aMiniera " + mine + " data a " + target.getName() + ".");
                render(staff, target, holder.page, holder.onBack, false);
            }
            case "far" -> {
                msg(staff, "&cDagli prima la miniera " + (owned + 1) + ".");
                plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.error");
            }
            default -> {
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        // niente stato da pulire: ogni Holder vive con il suo inventario
    }
}
