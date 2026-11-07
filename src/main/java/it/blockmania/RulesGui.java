package it.blockmania;

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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GUI delle regole (/adminmania rules): elenco a pagine di tutte le regole (ban, warn, mute, regolamento)
 * con filtro per tipo (click sinistro sul pulsante blu), creazione (click destro sul pulsante blu)
 * e eliminazione con conferma (tasto Q sulla regola).
 * Grafica: regole.png (glifo U+3E0B) e regole-2.png con il popup di conferma (glifo U+3E0C), stesso font default dei ticket.
 * Live: si ridisegna da sola a ogni Rules.onChange.
 */
public final class RulesGui implements Listener {

    // ---------- grafica del pack ----------
    private static final String FONT_RULES = "minecraft:regole";
    private static final String IMG_BASE = "\u3e0b";  // regole.png (TODO: allineare a chars di regole.json)
    private static final String IMG_CONF = "\u3e0c";  // regole-2.png (conferma)
    private static final int IMG = -22;
    private static final int IMG_ADV = 186;
    /** Barra frecce: su questa immagine sta in alto (container y 15..28); font e quote configurabili (rules-gui.*). */
    private static final String DEF_NAV_FONT = "minecraft:tiny3";
    private static final char DEF_NAV_BASE = '';
    private static final char DEF_NAV_SHADOW = '';
    private static final int NAV_CX = 94;

    // ---------- slot (pixel di regole.png: container = immagine - 7 in x, - 15 in y; slot x=8+18c, y=18+18r) ----------
    private static final int SIZE = 54;
    private static final int PER_PAGE = 45;
    private static final int GRID_START = 9;
    /** X rossa: immagine (20,30,23,13) -> container (13,15): slot 0 e 1. */
    private static final int[] SLOTS_X = {0, 1};
    /** Pulsante blu: immagine (147,30,23,13) -> container (140,15): slot 7 e 8. */
    private static final int[] SLOTS_BLUE = {7, 8};
    /** Frecce: sinistra immagine (50..69) -> slot 2; destra (120..139) -> slot 6. */
    private static final int SLOT_PREV = 2;
    private static final int SLOT_NEXT = 6;
    /** Popup conferma (riga 3): CONFERMA a sinistra, ANNULLA a destra. */
    private static final int[] CONFIRM_YES = {28, 29, 30};
    private static final int[] CONFIRM_NO = {32, 33, 34};

    private static final int WRAP = 35;

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        Session session;
        boolean confirm;
        final Map<Integer, Runnable> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Session {
        Player staff;
        Rules.Type filter;   // null = tutte
        int page;
        Holder holder;
        String titleKey;
        /** codice regola per slot grezzo. */
        final Map<Integer, String> bySlot = new HashMap<>();
    }

    private final BlockMania plugin;
    private final Rules rules;
    private final UserProfilePowers ui;
    private final Map<UUID, Session> sessions = new HashMap<>();
    /** Filtro ricordato per ogni staffer (null = tutte). */
    private final Map<UUID, Rules.Type> filters = new HashMap<>();
    private final Set<UUID> switching = new HashSet<>();

    public RulesGui(BlockMania plugin, Rules rules, UserProfilePowers ui) {
        this.plugin = plugin;
        this.rules = rules;
        this.ui = ui;
        rules.onChange(() -> Bukkit.getScheduler().runTask(plugin, () -> refreshAll()));
    }

    // ------------------------------------------------------------------
    //  API
    // ------------------------------------------------------------------

    /** Apre la GUI sul filtro ricordato, pagina 1. */
    public void open(Player staff) {
        if (!authorize(staff, "see")) {
            return;
        }
        open(staff, filters.get(staff.getUniqueId()), 0);
    }

    /** True se il giocatore ha aperta una GUI delle regole. */
    public boolean owns(Player p) {
        return p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder;
    }

    private void open(Player staff, Rules.Type filter, int page) {
        Session s = new Session();
        s.staff = staff;
        s.filter = filter;
        filters.put(staff.getUniqueId(), filter);
        Holder h = new Holder();
        h.session = s;
        s.holder = h;
        List<Rules.Rule> all = list(s);
        int pages = pageCount(all.size());
        s.page = Math.max(0, Math.min(page, pages - 1));
        s.titleKey = key(s, pages);
        h.inventory = Bukkit.createInventory(h, SIZE, title(s, pages));
        fill(s, all);
        show(staff, h, s);
    }

    private void show(Player staff, Holder h, Session s) {
        UUID id = staff.getUniqueId();
        switching.add(id);
        try {
            staff.openInventory(h.inventory);
        } finally {
            switching.remove(id);
        }
        sessions.put(id, s);
    }

    // ------------------------------------------------------------------
    //  Permessi
    // ------------------------------------------------------------------

    private boolean authorize(Player p, String verb) {
        AdminCommand a = plugin.getAdminCommand();
        if (a == null) {
            return p.isOp();
        }
        return a.authorize(p, "sanctions", verb, null, 0);
    }

    // ------------------------------------------------------------------
    //  Disegno
    // ------------------------------------------------------------------

    private List<Rules.Rule> list(Session s) {
        List<Rules.Rule> l = rules.byType(s.filter);
        return l == null ? new ArrayList<>() : l;
    }

    private static int pageCount(int n) {
        return Math.max(1, (n + PER_PAGE - 1) / PER_PAGE);
    }

    private static String key(Session s, int pages) {
        return s.filter + ":" + s.page + "/" + pages;
    }

    private static String filterName(Rules.Type t) {
        return t == null ? "tutte" : Rules.typeLabel(t).toLowerCase(Locale.ROOT);
    }

    private String cfg(String key, String def) {
        String v = plugin.getConfig().getString("rules-gui." + key, def);
        return v == null ? def : v;
    }

    private char cfgChar(String key, char def) {
        String v = cfg(key, String.valueOf(def));
        return v.isEmpty() ? def : v.charAt(0);
    }

    private net.kyori.adventure.text.Component title(Session s, int pages) {
        GuiTitle g = new GuiTitle(plugin);
        g.at(-15, "&f" + IMG_BASE, FONT_RULES, IMG_ADV);
        navText(g, s.page + 1, pages);
        UserProfilePowers.topTitle(plugin, g, "regole " + filterName(s.filter));
        return g.build();
    }

    /** Numero pagina nel riquadro frecce (stessi glifi dei popup, font e centro configurabili). */
    private void navText(GuiTitle g, int page, int pages) {
        char nav = cfgChar("nav-base", DEF_NAV_BASE);
        char navSh = cfgChar("nav-shadow", DEF_NAV_SHADOW);
        String plain = page + "/" + pages;
        StringBuilder n = new StringBuilder();
        StringBuilder sh = new StringBuilder();
        for (char ch : plain.toCharArray()) {
            int o = ch == '/' ? 10 : ch - '0';
            n.append((char) (nav + o));
            sh.append((char) (navSh + o));
        }
        int adv = plain.length() * 6;
        int cx = plugin.getConfig().getInt("rules-gui.nav-x", NAV_CX);
        int start = cx - (adv - 1) / 2;
        String font = cfg("nav-font", DEF_NAV_FONT);
        g.at(start + 1 + IMG, GuiTitle.shadowCode(plugin) + sh, font, adv);
        g.at(start + IMG, "&f" + n, font, adv);
    }

    private ItemStack pane(String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        meta.displayName(plugin.text(name));
        if (lore != null && !lore.isEmpty()) {
            List<net.kyori.adventure.text.Component> l = new ArrayList<>();
            for (String line : lore) {
                l.add(plugin.text(line));
            }
            meta.lore(l);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static Material materialOf(Rules.Type t) {
        return switch (t) {
            case BAN -> Material.RED_CONCRETE;
            case WARN -> Material.YELLOW_CONCRETE;
            case MUTE -> Material.ORANGE_CONCRETE;
            case REGOLAMENTO -> Material.PAPER;
        };
    }

    private static String colorOf(Rules.Type t) {
        return switch (t) {
            case BAN -> "&c";
            case WARN -> "&e";
            case MUTE -> "&6";
            case REGOLAMENTO -> "&b";
        };
    }

    /** Spezza il testo a capo ogni ~35 caratteri (sulle parole). */
    private static List<String> wrap(String text) {
        List<String> out = new ArrayList<>();
        String clean = text == null ? "" : text.replace('&', ' ').trim();
        StringBuilder line = new StringBuilder();
        for (String w : clean.split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (line.length() > 0 && line.length() + 1 + w.length() > WRAP) {
                out.add(line.toString());
                line.setLength(0);
            }
            if (line.length() > 0) {
                line.append(' ');
            }
            line.append(w);
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out;
    }

    private ItemStack ruleItem(Rules.Rule r) {
        ItemStack stack = new ItemStack(materialOf(r.type()));
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text("&f" + r.code()));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        for (String line : wrap(r.text())) {
            lore.add(plugin.text("&7" + line));
        }
        lore.add(plugin.text(" "));
        lore.add(plugin.text("&7Tipo: " + colorOf(r.type()) + Rules.typeLabel(r.type())));
        lore.add(plugin.text("&7Tempo: &f" + Rules.timeLabel(r)));
        lore.add(plugin.text(" "));
        lore.add(plugin.text("&eQ = elimina"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private void fill(Session s, List<Rules.Rule> all) {
        Inventory inv = s.holder.inventory;
        inv.clear();
        s.bySlot.clear();
        int pages = pageCount(all.size());
        for (int slot : SLOTS_X) {
            inv.setItem(slot, pane("&cchiudi", List.of()));
        }
        List<String> blue = List.of("&7Filtro: &f" + filterName(s.filter),
                "&eclick sinistro: cambia filtro",
                "&eclick destro: crea una regola");
        for (int slot : SLOTS_BLUE) {
            inv.setItem(slot, pane("&9filtro regole", blue));
        }
        inv.setItem(SLOT_PREV, pane("&7pagina precedente", List.of()));
        inv.setItem(SLOT_NEXT, pane("&7pagina successiva", List.of()));
        if (all.isEmpty()) {
            inv.setItem(GRID_START + 22, pane("&7nessuna regola", List.of("&8Click destro sul pulsante blu per crearne una.")));
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = s.page * PER_PAGE + i;
            if (idx >= all.size()) {
                break;
            }
            Rules.Rule r = all.get(idx);
            int slot = GRID_START + i;
            inv.setItem(slot, ruleItem(r));
            s.bySlot.put(slot, r.code());
        }
    }

    // ------------------------------------------------------------------
    //  Live
    // ------------------------------------------------------------------

    private void refreshAll() {
        for (Session s : new ArrayList<>(sessions.values())) {
            if (!s.staff.isOnline()) {
                sessions.remove(s.staff.getUniqueId());
                continue;
            }
            if (s.staff.getOpenInventory().getTopInventory().getHolder() != s.holder) {
                continue;
            }
            redraw(s);
        }
    }

    private void redraw(Session s) {
        List<Rules.Rule> all = list(s);
        int pages = pageCount(all.size());
        s.page = Math.max(0, Math.min(s.page, pages - 1));
        if (key(s, pages).equals(s.titleKey)) {
            fill(s, all);
        } else {
            // il titolo (filtro o numero pagina) cambia: serve un inventario nuovo
            open(s.staff, s.filter, s.page);
        }
    }

    // ------------------------------------------------------------------
    //  Creazione regola
    // ------------------------------------------------------------------

    private void reopen(Player p, Rules.Type filter) {
        Bukkit.getScheduler().runTask(plugin, () -> open(p, filter, 0));
    }

    private void startCreate(Player p, Rules.Type currentFilter) {
        if (!authorize(p, "edit")) {
            return;
        }
        Runnable cancel = () -> reopen(p, currentFilter);
        ui.askText(p, "codice regola (es. 1.2)", "CODICE REGOLA", "", code -> {
            String c = code == null ? "" : code.trim();
            if (!Rules.validCode(c)) {
                GuiMsg.send(plugin, p, "&ccodice non valido: usa numero.numero, per esempio 1.2");
                reopen(p, currentFilter);
                return;
            }
            if (rules.get(c) != null) {
                GuiMsg.send(plugin, p, "&cesiste gia' una regola con il codice " + c);
                reopen(p, currentFilter);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> askContent(p, currentFilter, c));
        }, cancel);
    }

    private void askContent(Player p, Rules.Type currentFilter, String code) {
        Runnable cancel = () -> reopen(p, currentFilter);
        ui.askText(p, "contenuto della regola", "CONTENUTO", "", txt -> {
            String text = txt == null ? "" : txt.trim();
            if (text.isEmpty()) {
                GuiMsg.send(plugin, p, "&cil contenuto della regola non puo' essere vuoto");
                reopen(p, currentFilter);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> askType(p, currentFilter, code, text));
        }, cancel);
    }

    private void askType(Player p, Rules.Type currentFilter, String code, String text) {
        ui.openCommands(p, "tipo regola", List.of("ban", "warn", "mute", "regolamento"), cmd -> {
            Rules.Type t;
            try {
                t = Rules.Type.valueOf(cmd.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                reopen(p, currentFilter);
                return;
            }
            if (t == Rules.Type.BAN || t == Rules.Type.MUTE) {
                Bukkit.getScheduler().runTask(plugin, () -> askTime(p, currentFilter, code, text, t));
            } else {
                finishCreate(p, code, text, t, 0L);
            }
        }, () -> reopen(p, currentFilter), false, null);
    }

    private void askTime(Player p, Rules.Type currentFilter, String code, String text, Rules.Type t) {
        String hint = t == Rules.Type.BAN ? "numero di minuti, permanente o blacklist" : "numero di minuti o permanente";
        ui.askText(p, "tempo (" + hint + ")", "TEMPO", "", in -> {
            String v = in == null ? "" : in.trim();
            String err = Rules.parseTimeError(t, v);
            if (err != null) {
                GuiMsg.send(plugin, p, "&c" + err);
                Bukkit.getScheduler().runTask(plugin, () -> askTime(p, currentFilter, code, text, t));
                return;
            }
            finishCreate(p, code, text, t, Rules.parseTime(t, v));
        }, () -> reopen(p, currentFilter));
    }

    private void finishCreate(Player p, String code, String text, Rules.Type t, long minutes) {
        Rules.Rule r = new Rules.Rule(code, text, t, minutes);
        if (rules.add(r)) {
            AdminCommand a = plugin.getAdminCommand();
            if (a != null) {
                a.record(p, "rules add " + code + " " + Rules.typeLabel(t) + " " + Rules.timeLabel(r));
            }
            GuiMsg.send(plugin, p, "&aregola " + code + " creata (" + Rules.typeLabel(t) + ")");
        } else {
            GuiMsg.send(plugin, p, "&cesiste gia' una regola con il codice " + code);
        }
        // riapre sul filtro del tipo creato
        reopen(p, t);
    }

    // ------------------------------------------------------------------
    //  Conferma eliminazione (regole-2.png)
    // ------------------------------------------------------------------

    private void openConfirm(Player p, Session s, String code) {
        Holder h = new Holder();
        h.session = s;
        h.confirm = true;
        GuiTitle g = new GuiTitle(plugin);
        g.at(-15, "&f" + IMG_CONF, FONT_RULES, IMG_ADV);
        UserProfilePowers.topTitle(plugin, g, "conferma");
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());
        List<String> yes = List.of("&7Elimina la regola " + code + ".", "", "&eclicca per confermare");
        List<String> no = List.of("&7Torna indietro senza fare niente.", "", "&eclicca per annullare");
        final Rules.Type filter = s.filter;
        final int page = s.page;
        Runnable doYes = () -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!authorize(p, "edit")) {
                open(p, filter, page);
                return;
            }
            if (rules.remove(code)) {
                AdminCommand a = plugin.getAdminCommand();
                if (a != null) {
                    a.record(p, "rules remove " + code);
                }
                GuiMsg.send(plugin, p, "&aregola " + code + " eliminata");
            } else {
                GuiMsg.send(plugin, p, "&cregola " + code + " non trovata");
            }
            open(p, filter, page);
        });
        Runnable doNo = () -> Bukkit.getScheduler().runTask(plugin, () -> open(p, filter, page));
        for (int slot : CONFIRM_YES) {
            h.inventory.setItem(slot, pane("&aconferma", yes));
            h.actions.put(slot, doYes);
        }
        for (int slot : CONFIRM_NO) {
            h.inventory.setItem(slot, pane("&cannulla", no));
            h.actions.put(slot, doNo);
        }
        show(p, h, s);
    }

    // ------------------------------------------------------------------
    //  Eventi
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (h.confirm) {
            Runnable a = h.actions.get(slot);
            if (a != null) {
                plugin.guiClick(p);
                a.run();
            }
            return;
        }
        Session s = h.session;
        for (int x : SLOTS_X) {
            if (slot == x) {
                plugin.guiClick(p);
                sessions.remove(p.getUniqueId());
                p.closeInventory();
                return;
            }
        }
        for (int b : SLOTS_BLUE) {
            if (slot == b) {
                plugin.guiClick(p);
                if (event.getClick().isRightClick()) {
                    final Rules.Type cur = s.filter;
                    Bukkit.getScheduler().runTask(plugin, () -> startCreate(p, cur));
                } else {
                    s.filter = nextFilter(s.filter);
                    s.page = 0;
                    filters.put(p.getUniqueId(), s.filter);
                    redraw(s);
                }
                return;
            }
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            plugin.guiClick(p);
            int pages = pageCount(list(s).size());
            // nessun giro: ai limiti non succede niente
            int np = Math.max(0, Math.min(s.page + (slot == SLOT_NEXT ? 1 : -1), pages - 1));
            if (np != s.page) {
                s.page = np;
                redraw(s);
            }
            return;
        }
        String code = s.bySlot.get(slot);
        if (code == null) {
            return;
        }
        ClickType ct = event.getClick();
        if (ct != ClickType.DROP && ct != ClickType.CONTROL_DROP) {
            return;
        }
        plugin.guiClick(p);
        if (!authorize(p, "edit")) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> openConfirm(p, s, code));
    }

    /** TUTTE -> BAN -> WARN -> MUTE -> REGOLAMENTO -> TUTTE. */
    private static Rules.Type nextFilter(Rules.Type t) {
        if (t == null) {
            return Rules.Type.BAN;
        }
        return switch (t) {
            case BAN -> Rules.Type.WARN;
            case WARN -> Rules.Type.MUTE;
            case MUTE -> Rules.Type.REGOLAMENTO;
            case REGOLAMENTO -> null;
        };
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) {
            return;
        }
        UUID id = p.getUniqueId();
        if (switching.contains(id)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof Holder h && sessions.get(id) == h.session) {
            sessions.remove(id);
        }
    }
}
