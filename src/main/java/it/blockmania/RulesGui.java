package it.blockmania;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
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
 * (l'eliminazione si fa solo dalla GUI di dettaglio).
 * Grafica: regole.png (glifo U+3E0B), font minecraft:regole.
 * Live: si ridisegna da sola a ogni Rules.onChange.
 */
public final class RulesGui implements Listener {

    // ---------- grafica del pack ----------
    private static final String FONT_RULES = "minecraft:regole";
    private static final String IMG_BASE = "\u3e0b";  // regole.png (TODO: allineare a chars di regole.json)
    private static final int IMG = -22;
    private static final int IMG_ADV = 179;   // dichiarato 179 (reale 186): convenzione Tickets/ItemsGui, vale x_img + IMG
    /** Barra frecce: su questa immagine sta in alto (container y 15..28); font e quote configurabili (rules-gui.*). */
    private static final String DEF_NAV_FONT = "minecraft:tiny5";
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
    private static final int WRAP = 35;

    // ---------- targhette delle regole disegnate a font (contratto col pack di Sara) ----------
    // Costanti facili da cambiare se ascent/advance reali del pack differiscono.
    /** Font per riga chest 1..5: "minecraft:rules_r1".."rules_r5". */
    private static final String RULE_FONT_PREFIX = "rules_r";
    /** Sfondo = U+E000 + colore (0..7). */
    private static final char RULE_BG_BASE = '';
    private static final int RULE_COLORS = 8;
    /** Cifra d = U+E010 + d, punto = U+E01A. */
    private static final char RULE_DIGIT_BASE = '';
    private static final char RULE_DOT = '';
    /** Avanzamento reale del glifo sfondo (come i cubi noteblock: 17). */
    private static final int RULE_BG_ADV = 17;
    /** Avanzamento di una cifra e del punto. */
    private static final int RULE_DIGIT_ADV = 4;
    private static final int RULE_DOT_ADV = 2;
    /** Scostamento delle cifre dallo sfondo (dati reali di Sara): x_cifre = x_sfondo + 6 + (16 - larghezzaTesto)/2. */
    private static final int RULE_DIGIT_DX = 0;
    /** Larghezza visibile dello sfondo. */
    private static final int RULE_CELL = 16;
    /** Bordo sinistro dello slot in pixel immagine = 15 + 18*colonna (come ItemsGui). */
    private static final int RULE_CELL_X0 = 15;
    private static final int RULE_CELL_STEP = 18;

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        Session session;

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
        s.titleKey = key(s, pages, all);
        h.inventory = Bukkit.createInventory(h, SIZE, title(s, pages, all));
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

    /** Regole visibili nella pagina corrente. */
    private static List<Rules.Rule> pageRules(Session s, List<Rules.Rule> all) {
        int from = Math.min(all.size(), s.page * PER_PAGE);
        int to = Math.min(all.size(), from + PER_PAGE);
        return all.subList(from, to);
    }

    /** Chiave del titolo: le targhette fanno parte del titolo, quindi contano anche i codici in pagina. */
    private static String key(Session s, int pages, List<Rules.Rule> all) {
        StringBuilder k = new StringBuilder(s.filter + ":" + s.page + "/" + pages);
        for (Rules.Rule r : pageRules(s, all)) {
            k.append('|').append(r.code());
        }
        return k.toString();
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

    private net.kyori.adventure.text.Component title(Session s, int pages, List<Rules.Rule> all) {
        GuiTitle g = new GuiTitle(plugin);
        g.at(-15, "&f" + IMG_BASE, FONT_RULES, IMG_ADV);
        navText(g, s.page + 1, pages);
        drawRuleTags(g, s, all);
        UserProfilePowers.topTitle(plugin, g, "regole " + filterName(s.filter));
        return g.build();
    }

    /** Glifo bianco (nessuna tinta, nessuna ombra) nel font della riga chest indicata. */
    private static Component ruleGlyph(char ch, int row) {
        return Component.text(String.valueOf(ch))
                .font(Key.key("minecraft", RULE_FONT_PREFIX + row))
                .color(TextColor.color(0xFFFFFF))
                .shadowColor(ShadowColor.none())
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Colore della targhetta = (primo numero del codice - 1) mod 8. */
    private static int tagColor(String code) {
        int dot = code.indexOf('.');
        String first = dot < 0 ? code : code.substring(0, dot);
        try {
            return Math.floorMod(Integer.parseInt(first.trim()) - 1, RULE_COLORS);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Avanzamento di un carattere del codice (0 = non disegnabile). */
    private static int tagAdvance(char ch) {
        if (ch >= '0' && ch <= '9') {
            return RULE_DIGIT_ADV;
        }
        return ch == '.' ? RULE_DOT_ADV : 0;
    }

    /** Sfondo + cifre di ogni regola della pagina, cella per cella (stessa x dei cubi noteblock di ItemsGui). */
    private void drawRuleTags(GuiTitle g, Session s, List<Rules.Rule> all) {
        List<Rules.Rule> page = pageRules(s, all);
        for (int i = 0; i < page.size(); i++) {
            int slot = GRID_START + i;
            int row = slot / 9;
            if (row < 1 || row > 5) {
                continue;
            }
            String code = page.get(i).code();
            int cellX = RULE_CELL_X0 + RULE_CELL_STEP * (slot % 9) + IMG;
            g.atComponent(cellX, ruleGlyph((char) (RULE_BG_BASE + tagColor(code)), row), RULE_BG_ADV);
            int width = -1;
            for (char ch : code.toCharArray()) {
                width += tagAdvance(ch);
            }
            int x = cellX + RULE_DIGIT_DX + (RULE_CELL - width) / 2;
            for (char ch : code.toCharArray()) {
                int adv = tagAdvance(ch);
                if (adv == 0) {
                    continue;
                }
                char glyph = ch == '.' ? RULE_DOT : (char) (RULE_DIGIT_BASE + (ch - '0'));
                g.atComponent(x, ruleGlyph(glyph, row), adv);
                x += adv;
            }
        }
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
        // l'aspetto lo disegna il font nel titolo: qui solo l'item trasparente con nome e descrizione
        List<String> lore = new ArrayList<>();
        // titolo in bianco in cima, poi descrizione, tipo, tempo
        lore.add("&f" + r.title());
        lore.add(" ");
        for (String line : wrap(r.text())) {
            lore.add("&7" + line);
        }
        lore.add(" ");
        lore.add("&7Tipo: " + colorOf(r.type()) + Rules.typeLabel(r.type()));
        lore.add("&7Tempo: &f" + Rules.timeLabel(r));
        lore.add(" ");
        lore.add("&eclicca per aprire");
        return pane("&f" + r.code(), lore);
    }

    private void fill(Session s, List<Rules.Rule> all) {
        Inventory inv = s.holder.inventory;
        inv.clear();
        s.bySlot.clear();
        int pages = pageCount(all.size());
        for (int slot : SLOTS_X) {
            inv.setItem(slot, pane("&cchiudi", List.of()));
        }
        // stesso stile dei filtri dei ticket: opzioni con » verde sulla corrente
        List<String> blue = new ArrayList<>();
        blue.add("&7scegli cosa vedere:");
        blue.add(" ");
        Rules.Type[] opts = {null, Rules.Type.BAN, Rules.Type.WARN, Rules.Type.MUTE};
        for (Rules.Type o : opts) {
            blue.add(o == s.filter ? "&a» " + filterName(o) : "&8" + filterName(o));
        }
        blue.add(" ");
        blue.add("&eclicca per cambiare");
        blue.add("&eclick destro: crea una regola");
        for (int slot : SLOTS_BLUE) {
            inv.setItem(slot, pane("&bfiltra per", blue));
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
        if (key(s, pages, all).equals(s.titleKey)) {
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
            Bukkit.getScheduler().runTask(plugin, () -> askTitle(p, currentFilter, c));
        }, cancel);
    }

    private void askTitle(Player p, Rules.Type currentFilter, String code) {
        Runnable cancel = () -> reopen(p, currentFilter);
        ui.askText(p, "titolo della regola", "TITOLO", "", tit -> {
            String title = tit == null ? "" : tit.trim();
            if (title.isEmpty()) {
                GuiMsg.send(plugin, p, "&cil titolo della regola non puo' essere vuoto");
                reopen(p, currentFilter);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> askContent(p, currentFilter, code, title));
        }, cancel);
    }

    private void askContent(Player p, Rules.Type currentFilter, String code, String title) {
        Runnable cancel = () -> reopen(p, currentFilter);
        ui.askText(p, "contenuto della regola", "CONTENUTO", "", txt -> {
            String text = txt == null ? "" : txt.trim();
            if (text.isEmpty()) {
                GuiMsg.send(plugin, p, "&cil contenuto della regola non puo' essere vuoto");
                reopen(p, currentFilter);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> askType(p, currentFilter, code, title, text));
        }, cancel);
    }

    private void askType(Player p, Rules.Type currentFilter, String code, String title, String text) {
        ui.openCommands(p, "tipo regola", List.of("ban", "warn", "mute", "regolamento"), cmd -> {
            Rules.Type t;
            try {
                t = Rules.Type.valueOf(cmd.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                reopen(p, currentFilter);
                return;
            }
            if (t == Rules.Type.BAN || t == Rules.Type.MUTE) {
                Bukkit.getScheduler().runTask(plugin, () -> askTime(p, currentFilter, code, title, text, t));
            } else {
                finishCreate(p, code, title, text, t, 0L);
            }
        }, () -> reopen(p, currentFilter), false, null, g -> drawDimmedBackground(g));
    }

    /** Sfondo della lista (regole.png) a colori pieni, advance reale 186: lo scurimento lo applica chi lo usa (MinesGui.tint). */
    public void drawDimmedBackground(GuiTitle g) {
        g.at(-15, "&f" + IMG_BASE, FONT_RULES, 186);
    }

    private void askTime(Player p, Rules.Type currentFilter, String code, String title, String text, Rules.Type t) {
        String hint = t == Rules.Type.BAN ? "permanente (e blacklist per i ban)" : "permanente";
        ui.askText(p, "scrivi il tempo: 10s 10m 10h 10d, anche somme come 10h+23m+16s; " + hint, "TEMPO", "", in -> {
            String v = in == null ? "" : in.trim();
            String err = Rules.parseTimeError(t, v);
            if (err != null) {
                GuiMsg.send(plugin, p, "&c" + err);
                Bukkit.getScheduler().runTask(plugin, () -> askTime(p, currentFilter, code, title, text, t));
                return;
            }
            finishCreate(p, code, title, text, t, Rules.parseTime(t, v));
        }, () -> reopen(p, currentFilter));
    }

    private void finishCreate(Player p, String code, String title, String text, Rules.Type t, long durationMs) {
        Rules.Rule r = Rules.Rule.created(code, title, text, t, durationMs, p);
        if (rules.add(r)) {
            AdminCommand a = plugin.getAdminCommand();
            if (a != null) {
                a.record(p, "rules add " + code + " " + Rules.typeLabel(t) + " " + Rules.timeLabel(r));
            }
            GuiMsg.send(plugin, p, "&aregola " + code + " creata (" + Rules.typeLabel(t) + ")");
        } else {
            GuiMsg.send(plugin, p, "&cesiste gia' una regola con il codice " + code);
        }
        // riapre sul filtro del tipo creato (regolamento non e' un filtro: si vede in "tutte")
        reopen(p, t == Rules.Type.REGOLAMENTO ? null : t);
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
        if (ct == ClickType.LEFT || ct == ClickType.SHIFT_LEFT) {
            // apre il dettaglio; "indietro" riapre la lista con lo stesso filtro e pagina
            plugin.guiClick(p);
            final Rules.Type backFilter = s.filter;
            final int backPage = s.page;
            Bukkit.getScheduler().runTask(plugin, () -> plugin.getRuleDetailGui().open(p, code,
                    () -> Bukkit.getScheduler().runTask(plugin, () -> open(p, backFilter, backPage))));
        }
    }

    /** TUTTE -> BAN -> WARN -> MUTE -> TUTTE (regolamento non e' un filtro: si vede solo in "tutte"). */
    private static Rules.Type nextFilter(Rules.Type t) {
        if (t == null) {
            return Rules.Type.BAN;
        }
        return switch (t) {
            case BAN -> Rules.Type.WARN;
            case WARN -> Rules.Type.MUTE;
            case MUTE, REGOLAMENTO -> null;
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
