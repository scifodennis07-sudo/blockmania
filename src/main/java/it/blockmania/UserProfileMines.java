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
import org.bukkit.event.inventory.InventoryType;
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
 * Q sul reset = toglie tutte (con conferma). Funziona anche col player OFFLINE (R-OFF): dati salvati e modifiche in
 * coda fino al login (OfflinePdc). La conferma si chiude solo con i suoi pulsanti (ESC la riapre).
 *
 * Sfondi staff / conferma: lista per numero di collegamenti verdi (0..5) configurabile
 * (userprofile.mines.backgrounds, userprofile.mines.confirm.backgrounds); se vuota si usano gli sfondi
 * originali e la conferma generica di UserProfilePowers.
 */
public final class UserProfileMines implements Listener {

    private static final int SIZE = 54;
    private static final String GUI = "mines-gui.";
    private static final String CFG = "userprofile.mines.";

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
        /** Conferma: quante volte e' stata riaperta dopo ESC (limite di sicurezza). */
        private int reopens;
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
        LoreWrap.fix(meta);   // lore a capo, nome non troppo lungo
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

    // ---- dati del target per UUID (anche offline: OfflinePdc) ----

    private OfflinePdc pdc() {
        return OfflinePdc.get(plugin);
    }

    private int owned(UUID target) {
        return Math.max(1, pdc().getInt(target, "mine"));
    }

    private int counter(UUID target, String key) {
        return pdc().getInt(target, key);
    }

    /** Come AmmoManager.setMine (con mine_max) + bossbar: subito se online, altrimenti in coda fino al login. */
    private void setMine(UUID target, int value) {
        Player p = Bukkit.getPlayer(target);
        if (p != null) {
            plugin.getAmmo().setMine(p, value);
            return;
        }
        pdc().setInt(target, "mine", value);
        if (value > counter(target, "mine_max")) {
            pdc().setInt(target, "mine_max", value);
        }
    }

    private void refresh(UUID target) {
        Player p = Bukkit.getPlayer(target);
        if (p != null) {
            plugin.getAmmo().refreshMain(p);
        }
    }

    /** Offline la modifica vale al prossimo accesso: lo si dice nel messaggio. */
    private static String when(UUID id) {
        return Bukkit.getPlayer(id) != null ? "" : " &7(offline: al prossimo accesso)";
    }

    private static String nameOf(UUID id) {
        return UserProfilePowers.nameOf(id);
    }

    private void msg(Player staff, String text) {
        GuiMsg.send(plugin, staff, text);
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

    // ---------- disegno ----------

    private void fill(Inventory inv, Holder holder, UUID target) {
        var c = plugin.getConfig();
        List<Position> positions = positions();
        int pages = pageCount();
        holder.page = Math.max(0, Math.min(holder.page, pages - 1));
        String[] general = {
                "%coin%", String.valueOf(counter(target, "coin")),
                "%blockcoin%", String.valueOf(counter(target, "blockcoin")),
                "%mine%", String.valueOf(owned(target)),
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
        // il tasto "info" dell'originale qui e' il RESET (azzera tutte le miniere): azzerare = eliminare, solo con Q
        List<String> resetLore = new ArrayList<>(List.of("&7azzera: &ftutte le miniere", "&7di: &f" + nameOf(target)));
        if (Bukkit.getPlayer(target) == null) {
            resetLore.add("&7modifica: &fal prossimo accesso");
        }
        resetLore.add("");
        resetLore.add("&cQ per azzerare");
        for (int slot : slotList(GUI + "slots.info")) {
            put(inv, holder, slot, invisible("&creset miniere", resetLore, general), "reset");
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
                put(inv, holder, pos.icon(), invisible(ename, elore, general), null);
                for (int slot : pos.extra()) {
                    put(inv, holder, slot, invisible(ename, elore, general), null);
                }
                continue;
            }
            int runs = Math.min(max, counter(target, "mine_runs_" + mine));
            int blocks = blocksOf(mine);
            int progress = Math.min(blocks, counter(target, "mine_progress_" + mine));
            String key = runs >= max ? "platinum" : mine > owned ? "locked" : "owned";
            String bp = GUI + "states." + key;
            String[] r = new String[general.length + 18];
            System.arraycopy(general, 0, r, 0, general.length);
            String[] extra = {
                    "%number%", String.valueOf(mine),
                    "%blocks%", String.valueOf(blocks),
                    "%broken%", String.valueOf(progress),
                    "%left%", String.valueOf(Math.max(0, blocks - progress)),
                    "%total%", String.valueOf(counter(target, "mine_total_" + mine)),
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
                actionText = mine == owned && owned > 1 ? "&eclicca per visitare &8| &cQ elimina" : "&eclicca per visitarla";
            } else if (mine == owned + 1) {
                action = "give:" + mine;
                actionText = "&eclicca per darla";
            } else {
                action = "far:" + mine;
                actionText = "&7serve: &cminiera " + (owned + 1);
            }
            String name = c.getString(bp + ".name", "&fminiera %number%");
            List<String> lore = new ArrayList<>();
            for (String line : c.getStringList(bp + ".lore")) {
                if (line.contains("%action%")) {
                    line = line.replace("%action%", actionText);
                }
                lore.add(line);
            }
            put(inv, holder, pos.icon(), invisible(name, lore, r), action);
            for (int slot : pos.extra()) {
                put(inv, holder, slot, invisible(name, lore, r), action);
            }
        }
    }

    private int greens(UUID target, int page) {
        int perPage = Math.max(1, positions().size());
        int onPage = Math.max(0, Math.min(perPage, owned(target) - page * perPage));
        return Math.max(0, onPage - 1);
    }

    /** Un cubo (glifo del pack v3) per ogni posizione della pagina, calcolato sul giocatore target. */
    private int[] cubes(UUID target, int page) {
        int perPage = Math.max(1, positions().size());
        int owned = owned(target);
        int max = maxRuns();
        int[] out = new int[Math.min(perPage, 6)];
        for (int i = 0; i < out.length; i++) {
            int mine = page * perPage + i + 1;
            if (mine > mineCount()) {
                out[i] = 231;
            } else {
                int runs = Math.min(max, counter(target, "mine_runs_" + mine));
                out[i] = MinesGui.cubeIndex(mine, runs, mine > owned);
            }
        }
        return out;
    }

    /**
     * Titolo a font (pack v3): base + barre verdi + pulsante reset + cubi, tutto scurito sotto il popup di conferma
     * (il pannello conferma sta sopra, non scurito), nome nella barra in alto e numero pagina come nelle GUI originali.
     */
    private Component title(UUID target, int page, int pages, boolean confirm) {
        return title(target, page, pages, confirm, null);
    }

    /** confirmText = descrizione scritta nel pannello di conferma (solo con confirm). */
    private Component title(UUID target, int page, int pages, boolean confirm, String confirmText) {
        return MinesGui.fontTitle(plugin, greens(target, page), cubes(target, page), true, confirm,
                "miniere " + nameOf(target), page, pages, true, confirmText);
    }

    // ---------- aprire ----------

    public void open(Player staff, Player target, Runnable onBack) {
        open(staff, target == null ? null : target.getUniqueId(), onBack);
    }

    /** Miniere di target (anche offline). */
    public void open(Player staff, UUID target, Runnable onBack) {
        if (staff == null || target == null) {
            return;
        }
        render(staff, target, 0, onBack, true);
    }

    private void render(Player staff, UUID target, int page, Runnable onBack, boolean sound) {
        Holder holder = new Holder();
        holder.target = target;
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

    private void ask(Player staff, UUID target, Holder from, String text, Runnable onYes) {
        int page = from.page;
        Runnable back = from.onBack;
        UUID tid = target;
        Runnable reopen = () -> {
            if (staff.isOnline()) {
                render(staff, tid, page, back, false);
            }
        };
        List<Integer> yes = slotList(CFG + "confirm.yes-slots");
        List<Integer> no = slotList(CFG + "confirm.no-slots");
        yes = yes.isEmpty() ? CONFIRM_YES : yes;
        no = no.isEmpty() ? CONFIRM_NO : no;
        Holder h = new Holder();
        h.target = tid;
        h.page = page;
        h.onBack = back;
        h.confirm = true;
        h.onYes = onYes;
        h.onNo = reopen;
        Inventory inv = Bukkit.createInventory(h, SIZE, title(target, page, pageCount(), true, text));
        h.inventory = inv;
        String[] none = new String[0];
        for (int s : yes) {
            put(inv, h, s, invisible("&aconferma", List.of("&7" + text, "", "&eclicca per confermare"), none), "yes");
        }
        for (int s : no) {
            put(inv, h, s, invisible("&cannulla", List.of("&eclicca per annullare"), none), "no");
        }
        staff.openInventory(inv);
    }

    // ---------- azioni sulle miniere ----------

    private void resetAll(UUID target) {
        setMine(target, 1);
        pdc().setInt(target, "mine_max", 1);
        for (int m = 1; m <= mineCount(); m++) {
            pdc().setInt(target, "mine_runs_" + m, 0);
            pdc().setInt(target, "mine_progress_" + m, 0);
            pdc().setInt(target, "mine_total_" + m, 0);
        }
        refresh(target);
    }

    private void removeLast(UUID target, int mine) {
        setMine(target, Math.max(1, mine - 1));
        pdc().setInt(target, "mine_runs_" + mine, 0);
        pdc().setInt(target, "mine_progress_" + mine, 0);
        pdc().setInt(target, "mine_total_" + mine, 0);
        refresh(target);
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
        final UUID target = holder.target;   // anche offline
        int pages = pageCount();
        boolean drop = event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP;
        if (action.equals("reset") && !drop) {
            return;   // azzerare = eliminare: solo con Q
        }
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
            case "reset" -> ask(staff, target, holder, "Togliere tutte le miniere a " + nameOf(target) + "?", () -> {
                if (!authorize(staff, target, "reset")) {
                    return;
                }
                StaffTasks st = plugin.getStaffTasks();
                if (st != null && !st.allowsMine(staff, target, owned(target), 1)) {
                    return;
                }
                resetAll(target);
                if (st != null) {
                    st.doneMine(staff, target);
                }
                audit(staff, "profile.reset mines " + nameOf(target));
                msg(staff, "&aMiniere di " + nameOf(target) + " azzerate." + when(target));
                render(staff, target, 0, holder.onBack, false);
            });
            default -> mineAction(staff, target, holder, action, drop);
        }
    }

    private void mineAction(Player staff, UUID target, Holder holder, String action, boolean drop) {
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
                msg(staff, "&cLa miniera 1 non si elimina: usa Q sul tasto reset.");
                plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.error");
                return;
            }
            ask(staff, target, holder, "Eliminare la miniera " + mine + " a " + nameOf(target) + "?", () -> {
                if (owned(target) != mine) {
                    msg(staff, "&cLe miniere sono cambiate: riprova.");
                    render(staff, target, holder.page, holder.onBack, false);
                    return;
                }
                if (!authorize(staff, target, "remove")) {
                    return;
                }
                StaffTasks st = plugin.getStaffTasks();
                if (st != null && !st.allowsMine(staff, target, mine, Math.max(1, mine - 1))) {
                    return;
                }
                removeLast(target, mine);
                if (st != null) {
                    st.doneMine(staff, target);
                }
                audit(staff, "profile.remove mine " + mine + " " + nameOf(target));
                msg(staff, "&aMiniera " + mine + " tolta a " + nameOf(target) + "." + when(target));
                render(staff, target, holder.page, holder.onBack, false);
            });
            return;
        }
        switch (kind) {
            case "enter" -> {
                staff.closeInventory();
                plugin.getMineTemplates().visit(staff, target, mine);
            }
            case "give" -> {
                if (mine != owned + 1) {
                    msg(staff, "&cDagli prima la miniera " + (owned + 1) + ".");
                    plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.error");
                    return;
                }
                if (!authorize(staff, target, "get")) {
                    return;
                }
                StaffTasks st = plugin.getStaffTasks();
                if (st != null && !st.allowsMine(staff, target, owned, mine)) {
                    return;
                }
                setMine(target, mine);
                if (st != null) {
                    st.doneMine(staff, target);
                }
                refresh(target);
                audit(staff, "profile.get mine " + mine + " " + nameOf(target));
                plugin.playConfiguredSound(staff.getLocation(), "mines-gui.sounds.buy");
                msg(staff, "&aMiniera " + mine + " data a " + nameOf(target) + "." + when(target));
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

    /**
     * La conferma si chiude solo con CONFERMA / ANNULLA (R-ESC): chiusa dal player (ESC, tasto inventario) si riapre al tick
     * dopo, se nel frattempo non si e' aperta un'altra GUI. Le chiusure del plugin (closeInventory, cambio GUI) non contano.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder h) || !h.confirm || h.reopens >= 40
                || event.getReason() != InventoryCloseEvent.Reason.PLAYER || !(event.getPlayer() instanceof Player p)) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline() && h.confirm && p.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING) {
                h.reopens++;
                p.openInventory(h.inventory);
            }
        });
    }
}
