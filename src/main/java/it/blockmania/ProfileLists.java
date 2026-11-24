package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Popup lista generico delle pagine 2/3 del profilo (cronologia movimenti, sanzioni, acquisti).
 * Grafica: userprofile-26 (U+E119, font minecraft:userprofile3), griglia 7x3 come RulePopups/UserProfileHistory,
 * frecce sotto NON circolari, X = onBack, barra titolo = filtro corrente (pulsante filtro: sinistro = precedente, destro = successivo), info blu = aiuto in chat.
 * Non e' live: i dati sono quelli del momento dell'apertura (o del cambio filtro).
 */
public final class ProfileLists implements Listener {

    /** Una riga: item (nome/lore, cliccabile ma invisibile se icon >= 0) e indice dell'icona custom nel titolo (-1 = nessuna: si vede l'item). */
    public record Row(ItemStack item, int icon) {
        public Row(ItemStack item) {
            this(item, -1);
        }
    }

    public record Filter(String label, String key) {}

    // ------------------------------------------------------------------
    //  GEOMETRIA E FONT: tutte le costanti di posizione/ascent stanno qui (pixel immagine 256x256 di userprofile-26)
    // ------------------------------------------------------------------
    private static final String IMAGE = "\ue119";
    private static final String IMAGE_FONT = "minecraft:userprofile3";
    /** Convenzione del titolo: x_titolo = x_immagine + IMG (come ItemsGui). */
    private static final int IMG = -22;
    /**
     * Avanzamento DICHIARATO dell'immagine. Quello reale di E119 e' 169 (ultima colonna opaca 167, +1, +1);
     * con la convenzione IMG = -22 (immagine disegnata a -15) l'avanzamento dichiarato deve essere reale - 7 = 162.
     * (179 era quello di userprofile-8: con E119 spostava i testi di 17 px a sinistra.)
     */
    private static final int ADV = 162;
    /** Centro (pixel immagine) del numero pagina tra le frecce = Tickets.POPUP_CX + 1. */
    private static final int NAV_CX = 96;
    /** Icone custom (U+E300 + indice), font minecraft:ico_r1..3 (uno per riga della griglia: ascent -23/-41/-59 nel pack). */
    private static final char ICON_BASE = '\ue300';
    private static final String ICON_FONT_PREFIX = "ico_r";
    private static final int ICON_ADVANCE = 17;
    /** Bordo sinistro dello slot (colonna c = slot % 9) a x_img = ICON_X0 + ICON_STEP * c; riga = slot / 9 (1..3). */
    private static final int ICON_X0 = 15;
    private static final int ICON_STEP = 18;
    private static final String[] ICON_NAMES = {"warn", "mute", "ban", "mine", "shop", "ticket", "reward", "upgrade", "items", "staff", "other", "sanction"};
    private static final char NAV_BASE = '';
    private static final char NAV_SHADOW = '';
    /** Numero pagina = Tickets.NAV/NAV_SH (font default), centro 96 come Tickets.openList. */
    private static final char NAV = '\ue4a0';
    private static final char NAV_SH = '\ue4b0';
    /** Testo del filtro nella barra in alto: set piccolo F500/F600 nel font tiny3 (quota del layout userprofile, come la vecchia UserProfileHistory). */
    private static final char BAR_BASE = '\uf500';
    private static final char BAR_SHADOW = '\uf600';
    private static final String BAR_FONT = "minecraft:tiny3";
    private static final int BAR_CX = 95;
    private static final int BAR_MAX = 64;   // barra allargata U+E119 (BAR_WIDTH.md)

    private static final int SIZE = 54;
    private static final int PER_PAGE = 21;
    private static final int SLOT_X = 1;
    /** Bottone blu = Filtra. */
    private static final int SLOT_FILTER = 7;
    private static final int SLOT_PREV = 38;
    private static final int SLOT_NEXT = 42;

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        Session session;
        /** Premuto un pulsante che porta via (X, cerca): la chiusura e' voluta (R-ESC). */
        boolean chosen;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Session {
        Player staff;
        String title;
        List<Filter> filters;
        Function<String, List<Row>> rowsFor;
        Consumer<GuiTitle> background;
        Runnable onBack;
        int filterIdx;
        int page;
        List<Row> rows = new ArrayList<>();
        Holder holder;
        /** Filtro avanzato (Q sul filtro): testo cercato in nome e lore delle voci; vale finche' la lista resta aperta. */
        String search;
    }

    private final BlockMania plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> switching = new HashSet<>();

    public ProfileLists(BlockMania plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------------
    //  API
    // ------------------------------------------------------------------

    public void open(Player staff, String title, List<Filter> filters, Function<String, List<Row>> rowsFor,
                     Consumer<GuiTitle> background, Runnable onBack) {
        List<Filter> all = new ArrayList<>();
        if (filters == null || filters.isEmpty() || !"".equals(filters.get(0).key())) {
            all.add(new Filter("Tutti", ""));
        }
        if (filters != null) {
            all.addAll(filters);
        }
        Session s = new Session();
        s.staff = staff;
        s.title = title == null ? "" : title;
        s.filters = all;
        s.rowsFor = rowsFor;
        s.background = background;
        s.onBack = onBack;
        show(s, 0, 0);
    }

    public void openMovements(Player staff, UUID target, Consumer<GuiTitle> background, Runnable back) {
        List<Filter> f = new ArrayList<>();
        for (MovementLog.Type t : MovementLog.Type.values()) {
            f.add(new Filter(t.label(), t.name()));
        }
        open(staff, "cronologia movimenti", f, key -> movementRows(target, key), background, back);
    }

    public void openSanctions(Player staff, UUID target, Consumer<GuiTitle> background, Runnable back) {
        List<Filter> f = List.of(new Filter("Warn", "WARN"), new Filter("Mute", "MUTE"), new Filter("Ban", "BAN"));
        open(staff, "cronologia sanzioni", f, key -> sanctionRows(target, key), background, back);
    }

    public void openPurchases(Player staff, UUID target, Consumer<GuiTitle> background, Runnable back) {
        open(staff, "cronologia acquisti", List.of(),
                key -> List.of(new Row(item(Material.BARRIER, "&7nessun acquisto", List.of("&7stato: &8in arrivo")))),
                background, back);
    }

    // ------------------------------------------------------------------
    //  Dati
    // ------------------------------------------------------------------

    private List<Row> movementRows(UUID target, String key) {
        MovementLog log = plugin.getMovements();
        List<Row> out = new ArrayList<>();
        if (log == null) {
            return out;
        }
        MovementLog.Type type = null;
        if (key != null && !key.isEmpty()) {
            try {
                type = MovementLog.Type.valueOf(key);
            } catch (IllegalArgumentException ex) {
                type = null;
            }
        }
        List<MovementLog.Entry> list = log.list(target, type);
        if (list == null) {
            return out;
        }
        for (MovementLog.Entry e : list) {
            Material m = Material.matchMaterial(e.type().icon());
            List<String> lore = new ArrayList<>();
            lore.add("&7quando: &f" + date(e.ts()));
            for (String line : wrap(clean(e.text()), LoreWrap.LIMIT)) {
                lore.add("&f" + line);
            }
            out.add(new Row(item(m == null ? Material.PAPER : m, "&e" + e.type().label(), lore),
                    iconIndex(e.type().name())));
        }
        return out;
    }

    private List<Row> sanctionRows(UUID target, String key) {
        SanctionManager sm = plugin.getSanctions();
        List<Row> out = new ArrayList<>();
        if (sm == null) {
            return out;
        }
        List<SanctionManager.Entry> all = new ArrayList<>();
        for (SanctionManager.Kind k : SanctionManager.Kind.values()) {
            if (key != null && !key.isEmpty() && !k.name().equals(key)) {
                continue;
            }
            List<SanctionManager.Entry> l = sm.history(target, k);
            if (l != null) {
                all.addAll(l);
            }
        }
        all.sort(Comparator.comparingLong(SanctionManager.Entry::time).reversed());
        for (SanctionManager.Entry e : all) {
            out.add(new Row(sanctionItem(e), iconIndex(e.kind().name())));
        }
        return out;
    }

    private ItemStack sanctionItem(SanctionManager.Entry e) {
        String action = e.action() == null ? "" : e.action().toUpperCase(java.util.Locale.ROOT);
        Material mat;
        String color;
        String label;
        switch (action) {
            case "ADD" -> { mat = Material.RED_DYE; color = "&c"; label = "aggiunto"; }
            case "AUTO_BAN" -> { mat = Material.PURPLE_DYE; color = "&5"; label = "ban automatico (4 warn)"; }
            case "EXTEND" -> { mat = Material.ORANGE_DYE; color = "&6"; label = "ban esteso (4 warn)"; }
            case "REDUCE" -> { mat = Material.YELLOW_DYE; color = "&e"; label = "ridotto"; }
            case "REMOVE" -> { mat = Material.LIME_DYE; color = "&a"; label = "rimosso"; }
            case "UNBAN" -> { mat = Material.GREEN_DYE; color = "&a"; label = "sban"; }
            case "RESET" -> { mat = Material.LIGHT_BLUE_DYE; color = "&b"; label = "azzerato"; }
            default -> { mat = Material.PAPER; color = "&7"; label = action.toLowerCase(java.util.Locale.ROOT); }
        }
        String kind = e.kind().name().toLowerCase(java.util.Locale.ROOT);
        List<String> lore = new ArrayList<>();
        lore.add("&7azione: &f" + label);
        lore.add("&7da: &f" + (e.byName() == null || e.byName().isBlank() ? "sistema" : clean(e.byName())));
        lore.add("&7quando: &f" + date(e.time()));
        if (e.durationMs() > 0) {
            lore.add("&7durata: &f" + duration(e.durationMs()));
        }
        if (e.reason() != null && !e.reason().isBlank()) {
            lore.add("&7motivo: &f" + clean(e.reason()));
        }
        if (e.note() != null && !e.note().isBlank()) {
            lore.add("&7nota: &f" + clean(e.note()));
        }
        return item(mat, color + kind + " &7- " + color + label, lore);
    }

    // ------------------------------------------------------------------
    //  Disegno
    // ------------------------------------------------------------------

    private void show(Session s, int filterIdx, int page) {
        s.filterIdx = Math.max(0, Math.min(filterIdx, s.filters.size() - 1));
        List<Row> rows = null;
        try {
            rows = s.rowsFor == null ? null : s.rowsFor.apply(s.filters.get(s.filterIdx).key());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("ProfileLists: errore nel caricare le righe: " + ex);
        }
        s.rows = rows == null ? new ArrayList<>() : rows;
        if (s.search != null && !s.search.isBlank()) {
            String q = s.search.toLowerCase(java.util.Locale.ROOT);
            List<Row> kept = new ArrayList<>();
            for (Row r : s.rows) {
                if (r != null && plainOf(r.item()).contains(q)) {
                    kept.add(r);
                }
            }
            s.rows = kept;
        }
        redraw(s, page);
    }

    /** Nome e lore della voce in testo semplice minuscolo (per il filtro avanzato). */
    private static String plainOf(ItemStack it) {
        if (it == null || !it.hasItemMeta()) {
            return "";
        }
        var ser = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();
        StringBuilder sb = new StringBuilder();
        ItemMeta m = it.getItemMeta();
        if (m.displayName() != null) {
            sb.append(ser.serialize(m.displayName())).append(' ');
        }
        if (m.lore() != null) {
            for (net.kyori.adventure.text.Component c : m.lore()) {
                sb.append(ser.serialize(c)).append(' ');
            }
        }
        return untinyAll(sb.toString()).toLowerCase(java.util.Locale.ROOT);
    }

    /** Il testo delle lore puo' essere in tiny text: lo si riporta a lettere normali per la ricerca. */
    private static String untinyAll(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            sb.append(Tiny.untiny(s.charAt(i)));
        }
        return sb.toString();
    }

    private void redraw(Session s, int page) {
        int pages = pageCount(s.rows.size());
        s.page = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder();
        h.session = s;
        s.holder = h;
        h.inventory = Bukkit.createInventory(h, SIZE, titleOf(s, pages));
        fill(s);
        UUID id = s.staff.getUniqueId();
        switching.add(id);
        try {
            s.staff.openInventory(h.inventory);
        } finally {
            switching.remove(id);
        }
        sessions.put(id, s);
    }

    private static int pageCount(int n) {
        return Math.max(1, (n + PER_PAGE - 1) / PER_PAGE);
    }

    private static int gridSlot(int i) {
        return 10 + (i / 7) * 9 + (i % 7);
    }

    private net.kyori.adventure.text.Component titleOf(Session s, int pages) {
        GuiTitle g = new GuiTitle(plugin);
        if (s.background != null) {
            UserProfilePowers.drawDimmed(plugin, g, s.background);
        }
        g.at(-15, "&f" + IMAGE, IMAGE_FONT, ADV);
        drawIcons(g, s);
        barText(g, s.filters.get(s.filterIdx).label());
        navText(g, s.page + 1, pages);
        if (s.background == null) {
            UserProfilePowers.topTitle(plugin, g, s.title);   // es. "cronologia sanzioni", solo se non e' un popup
        }
        // popup: nella barra in alto resta il titolo della GUI di sotto, scurito dentro s.background
        return g.build();
    }

    /** Indice dell'icona dal nome (warn, mine, ...), -1 se sconosciuto. */
    static int iconIndex(String name) {
        if (name == null) {
            return -1;
        }
        String n = name.toLowerCase(java.util.Locale.ROOT);
        for (int i = 0; i < ICON_NAMES.length; i++) {
            if (ICON_NAMES[i].equals(n)) {
                return i;
            }
        }
        return -1;
    }

    /** Icone delle righe della pagina corrente, disegnate nel titolo sopra la cella (font per riga). */
    private void drawIcons(GuiTitle g, Session s) {
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = s.page * PER_PAGE + i;
            if (idx >= s.rows.size()) {
                break;
            }
            Row r = s.rows.get(idx);
            if (r == null || r.icon() < 0 || r.icon() >= ICON_NAMES.length) {
                continue;
            }
            int slot = gridSlot(i);
            int row = slot / 9;
            int col = slot % 9;
            net.kyori.adventure.text.Component glyph = net.kyori.adventure.text.Component.text(String.valueOf((char) (ICON_BASE + r.icon())))
                    .font(net.kyori.adventure.key.Key.key("minecraft", ICON_FONT_PREFIX + row))
                    .color(net.kyori.adventure.text.format.TextColor.color(0xFFFFFF))
                    .shadowColor(net.kyori.adventure.text.format.ShadowColor.none())
                    .decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false);
            g.atComponent(ICON_X0 + ICON_STEP * col + IMG, glyph, ICON_ADVANCE);
        }
    }

    /**
     * Larghezza in pixel di un testo del font tiny3 (6 px a glifo, 4 per spazio e i/I/1): i glifi privati del pack
     * non sono in FontMetrics (che copre solo il font vanilla), quindi la misura e' questa, usata per centrare.
     */
    private static int tinyAdvance(String text) {
        int total = 0;
        for (char ch : text.toCharArray()) {
            total += ch == ' ' ? 4 : (ch == 'i' || ch == 'I' || ch == '1') ? 4 : 6;
        }
        return total;
    }

    private static String letters(String text, char base) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            sb.append(ch < 128 && Character.isLetterOrDigit(ch) ? (char) (base + ch) : ' ');
        }
        return sb.toString();
    }

    /** Numero pagina: copia di Tickets.navText (lista ticket): glifi NAV/NAV_SH nel font default, centro 96, stessa quota. */
    private void navText(GuiTitle g, int page, int pages) {
        String plain = page + "/" + pages;
        StringBuilder n = new StringBuilder();
        StringBuilder sh = new StringBuilder();
        for (char ch : plain.toCharArray()) {
            int o = ch == '/' ? 10 : ch - '0';
            n.append((char) (NAV + o));
            sh.append((char) (NAV_SH + o));
        }
        int adv = plain.length() * 6;
        int start = NAV_CX - (adv - 1) / 2;
        g.at(start + 1 + IMG, GuiTitle.shadowCode(plugin) + sh, "", adv);
        g.at(start + IMG, "&f" + n, "", adv);
    }

    /** Filtro selezionato nella barra grigia in alto (testo piccolo del pack come tinyText di Tickets), centrato su BAR_CX. */
    private void barText(GuiTitle g, String raw) {
        StringBuilder safe = new StringBuilder();
        for (char ch : (raw == null ? "" : raw).toCharArray()) {
            safe.append(ch < 128 && Character.isLetterOrDigit(ch) ? ch : ' ');
        }
        String text = safe.toString().replaceAll(" +", " ").trim();
        while (tinyAdvance(text) > BAR_MAX && text.length() > 1) {
            text = text.substring(0, text.length() - 1).trim();
        }
        if (text.isEmpty()) {
            return;
        }
        int st = BAR_CX - Math.max(0, tinyAdvance(text) - 1) / 2;
        int off = 0;
        for (String w : text.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            int wa = tinyAdvance(w);
            g.at(st + off + 1 + IMG, GuiTitle.shadowCode(plugin) + letters(w, BAR_SHADOW), BAR_FONT, wa);
            g.at(st + off + IMG, "&f" + letters(w, BAR_BASE), BAR_FONT, wa);
            off += wa + 4;
        }
    }

    private void fill(Session s) {
        Inventory inv = s.holder.inventory;
        String back = s.onBack != null ? "&cindietro" : "&cchiudi";
        inv.setItem(SLOT_X, pane(back, List.of()));
        Filter f = s.filters.get(s.filterIdx);
        {
            // stesso formato di Tickets.openList: "filtra per" / "scegli cosa vedere:" / opzioni (selezionata "&a» ") / sinistro-destro
            List<String> fl = new ArrayList<>();
            for (int i = 0; i < s.filters.size(); i++) {
                fl.add(i == s.filterIdx ? "&a» " + s.filters.get(i).label() : "&8" + s.filters.get(i).label());
            }
            if (s.search != null && !s.search.isBlank()) {
                fl.add("&7cerca: &f" + s.search);
            }
            fl.add("");
            // sinistro/destro solo se c'e' piu' di un filtro (R-FIL); Q = cerca (R-Q)
            fl.add(s.filters.size() > 1 ? "&esinistro su &8| &edestro giù &8| &eQ cerca" : "&eQ cerca");
            inv.setItem(SLOT_FILTER, pane("&bfiltra per", fl));
        }
        inv.setItem(SLOT_PREV, pane("&7pagina precedente", List.of()));
        inv.setItem(SLOT_NEXT, pane("&7pagina successiva", List.of()));
        if (s.rows.isEmpty()) {
            inv.setItem(gridSlot(10), pane("&7nessuna voce", List.of()));
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = s.page * PER_PAGE + i;
            if (idx >= s.rows.size()) {
                break;
            }
            Row r = s.rows.get(idx);
            if (r != null && r.item() != null) {
                inv.setItem(gridSlot(i), r.icon() >= 0 ? invisible(r.item()) : r.item().clone());
            }
        }
    }

    // ------------------------------------------------------------------
    //  Item e testi
    // ------------------------------------------------------------------

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
        LoreWrap.fix(meta);   // lore a capo, nome non troppo lungo
        stack.setItemMeta(meta);
        return stack;
    }

    /** Pane trasparente con nome e lore dell'item (l'icona e' nel titolo). */
    private ItemStack invisible(ItemStack src) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        ItemMeta sm = src.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        if (sm != null) {
            meta.displayName(sm.displayName());
            meta.lore(sm.lore());
        }
        LoreWrap.fix(meta);   // lore a capo, nome non troppo lungo
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text(name));
        List<net.kyori.adventure.text.Component> l = new ArrayList<>();
        for (String line : lore) {
            l.add(plugin.text(line));
        }
        meta.lore(l);
        LoreWrap.fix(meta);   // lore a capo, nome non troppo lungo
        stack.setItemMeta(meta);
        return stack;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('&', ' ').replace('§', ' ').trim();
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split("\\s+")) {
            if (w.isEmpty()) {
                continue;
            }
            if (cur.length() > 0 && cur.length() + 1 + w.length() > width) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(w);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    private static String date(long ms) {
        return DATE.format(Instant.ofEpochMilli(ms).atZone(ROME));
    }

    private static String duration(long ms) {
        long s = ms / 1000;
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) sb.append(d).append("g ");
        if (h > 0) sb.append(h).append("h ");
        if (m > 0) sb.append(m).append("m ");
        if (sb.length() == 0 || (d == 0 && h == 0 && m == 0 && sec > 0)) sb.append(sec).append("s");
        return sb.toString().trim();
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
        Session s = h.session;
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot == SLOT_X) {
            plugin.guiClick(p);
            h.chosen = true;
            sessions.remove(p.getUniqueId());
            Runnable back = s.onBack;
            if (back != null) {
                Bukkit.getScheduler().runTask(plugin, () -> back.run());
            } else {
                p.closeInventory();
            }
            return;
        }
        if (slot == SLOT_FILTER) {
            org.bukkit.event.inventory.ClickType fc = event.getClick();
            if (fc == org.bukkit.event.inventory.ClickType.DROP || fc == org.bukkit.event.inventory.ClickType.CONTROL_DROP) {
                // Q = filtro avanzato: testo da cercare (vuoto = toglie la ricerca); resta finche' la lista e' aperta
                plugin.guiClick(p);
                h.chosen = true;
                final int fi = s.filterIdx;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getProfileUi().askText(p, "cerca nella lista (testo o nick, vuoto = tutto)", "CERCA",
                            s.search == null ? "" : s.search, txt -> {
                                s.search = txt == null || txt.isBlank() ? null : txt.trim();
                                Bukkit.getScheduler().runTask(plugin, () -> show(s, fi, 0));
                            }, () -> {
                                Bukkit.getScheduler().runTask(plugin, () -> show(s, fi, s.page));
                            });
                });
                return;
            }
            if (s.filters.size() > 1 && (fc.isLeftClick() || fc.isRightClick())) {
                plugin.guiClick(p);
                // sinistro = filtro precedente (su), destro = filtro successivo (giu')
                int n = s.filters.size();
                int next = fc.isLeftClick() ? (s.filterIdx - 1 + n) % n : (s.filterIdx + 1) % n;
                Bukkit.getScheduler().runTask(plugin, () -> show(s, next, 0));
            }
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            plugin.guiClick(p);
            int pages = pageCount(s.rows.size());
            int np = Math.max(0, Math.min(s.page + (slot == SLOT_NEXT ? 1 : -1), pages - 1));
            if (np != s.page) {
                // il numero pagina e' nel titolo: serve un inventario nuovo
                Bukkit.getScheduler().runTask(plugin, () -> redraw(s, np));
            }
        }
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
        if (!(event.getInventory().getHolder() instanceof Holder h)) {
            return;
        }
        if (!h.chosen) {
            // chiusa con ESC: si riapre (R-ESC), la lista resta quella
            UserProfilePowers.reopenOnEscape(plugin, p, h.inventory, () -> h.chosen);
            return;
        }
        if (sessions.get(id) == h.session) {
            sessions.remove(id);
        }
    }
}
