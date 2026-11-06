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

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Cronologia sanzioni (userprofile-8): popup a griglia 7x3 con frecce pagina, X chiudi/torna e titolo nella barra.
 * Una voce di SanctionManager.Entry per item; tasto Q = elimina la voce (dopo conferma).
 * Live: si ridisegna da sola a ogni sm.onChange.
 *
 * Grafica: costanti sotto, sovrascrivibili da config (userprofile.history.*) finche' il pack di Sara non le fissa.
 */
public final class UserProfileHistory implements Listener {

    // ---------- grafica del pack (valori provvisori, configurabili) ----------
    /** Glifo dell'immagine userprofile-8 (immagine intera del popup). */
    private static final String DEF_IMAGE = "\ue107";
    /** Font del glifo immagine ("" = default). */
    private static final String DEF_IMAGE_FONT = "minecraft:userprofile";
    /** Base dei glifi del testo piccolo della barra (come Tickets.BAR) e relativo font. */
    private static final char DEF_BAR_BASE = '';
    private static final char DEF_BAR_SHADOW = '';
    private static final String DEF_BAR_FONT = "minecraft:tiny2";
    /** Base dei numeri pagina (come Tickets.NAV2). */
    private static final char DEF_NAV_BASE = '';
    private static final char DEF_NAV_SHADOW = '';
    /** Centro (pixel immagine) di barra e numero pagina; IMG = offset immagine/titolo, ADV = avanzamento immagine. */
    private static final int DEF_BAR_CX = 95;
    private static final int DEF_NAV_CX = 95;
    private static final int DEF_IMG = -22;
    private static final int DEF_ADV = 179;
    private static final int BAR_MAX = 58;

    private static final int SIZE = 54;
    private static final int PER_PAGE = 21;
    private static final int SLOT_X = 1;
    private static final int SLOT_X2 = 2;
    private static final int SLOT_PREV = 38;
    private static final int SLOT_NEXT = 42;

    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM/yyyy HH:mm");

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
        UUID target;
        SanctionManager.Kind kind;
        int page;
        Runnable onBack;
        Holder holder;
        String titleKey;
        /** id delle voci mostrate, per slot grezzo. */
        final Map<Integer, Long> bySlot = new HashMap<>();
    }

    private final BlockMania plugin;
    private final SanctionManager sm;
    private final UserProfilePowers ui;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> switching = new HashSet<>();

    public UserProfileHistory(BlockMania plugin, SanctionManager sm, UserProfilePowers ui) {
        this.plugin = plugin;
        this.sm = sm;
        this.ui = ui;
        sm.onChange(target -> {
            // puo' arrivare da un thread async (chat/login): torna sul principale
            Bukkit.getScheduler().runTask(plugin, () -> refreshTarget(target));
        });
    }

    // ------------------------------------------------------------------
    //  API
    // ------------------------------------------------------------------

    public void open(Player staff, UUID target, SanctionManager.Kind kind, int page, Runnable onBack) {
        Session s = new Session();
        s.staff = staff;
        s.target = target;
        s.kind = kind;
        s.page = Math.max(0, page);
        s.onBack = onBack;
        Holder h = new Holder();
        h.session = s;
        s.holder = h;
        List<SanctionManager.Entry> all = entries(s);
        int pages = pageCount(all.size());
        s.page = Math.min(s.page, pages - 1);
        s.titleKey = s.page + "/" + pages;
        h.inventory = Bukkit.createInventory(h, SIZE, title(s, pages));
        fill(s, all, pages);
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
    //  Disegno
    // ------------------------------------------------------------------

    private List<SanctionManager.Entry> entries(Session s) {
        List<SanctionManager.Entry> l = sm.history(s.target, s.kind);
        return l == null ? new ArrayList<>() : l;
    }

    private static int pageCount(int n) {
        return Math.max(1, (n + PER_PAGE - 1) / PER_PAGE);
    }

    private static int gridSlot(int i) {
        return 10 + (i / 7) * 9 + (i % 7);   // pack: griglia container (26+18c, 36+18r) = righe 1..3, colonne 1..7
    }

    private String cfg(String key, String def) {
        String v = plugin.getConfig().getString("userprofile.history." + key, def);
        return v == null ? def : v;
    }

    private char cfgChar(String key, char def) {
        String v = cfg(key, String.valueOf(def));
        return v.isEmpty() ? def : v.charAt(0);
    }

    private net.kyori.adventure.text.Component title(Session s, int pages) {
        int img = plugin.getConfig().getInt("userprofile.history.img-offset", DEF_IMG);
        int adv = plugin.getConfig().getInt("userprofile.history.image-advance", DEF_ADV);
        GuiTitle g = new GuiTitle(plugin);
        g.at(-15, "&f" + cfg("image", DEF_IMAGE), cfg("image-font", DEF_IMAGE_FONT), adv);
        String name = Bukkit.getOfflinePlayer(s.target).getName();
        String bar = "cronologia " + kindName(s.kind) + (name != null ? " " + name : "");
        barText(g, bar, plugin.getConfig().getInt("userprofile.history.bar-x", DEF_BAR_CX), img);
        navText(g, s.page + 1, pages, plugin.getConfig().getInt("userprofile.history.nav-x", DEF_NAV_CX), img);
        return g.build();
    }

    private static String kindName(SanctionManager.Kind k) {
        return switch (k) {
            case MUTE -> "mute";
            case WARN -> "warn";
            case BAN -> "ban";
        };
    }

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

    private void barText(GuiTitle g, String raw, int cx, int img) {
        StringBuilder safe = new StringBuilder();
        for (char ch : raw.toCharArray()) {
            safe.append(ch < 128 && Character.isLetterOrDigit(ch) ? ch : ' ');
        }
        String text = safe.toString().replaceAll(" +", " ").trim();
        while (tinyAdvance(text) > BAR_MAX && text.length() > 1) {
            text = text.substring(0, text.length() - 1).trim();
        }
        if (text.isEmpty()) {
            return;
        }
        char base = cfgChar("bar-base", DEF_BAR_BASE);
        char sh = cfgChar("bar-shadow", DEF_BAR_SHADOW);
        String font = cfg("bar-font", DEF_BAR_FONT);
        int st = cx - Math.max(0, tinyAdvance(text) - 1) / 2;
        int off = 0;
        for (String w : text.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            int wa = tinyAdvance(w);
            g.at(st + off + 1 + img, GuiTitle.shadowCode(plugin) + letters(w, sh), font, wa);
            g.at(st + off + img, "&f" + letters(w, base), font, wa);
            off += wa + 4;
        }
    }

    private void navText(GuiTitle g, int page, int pages, int cx, int img) {
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
        int start = cx - (adv - 1) / 2;
        String font = cfg("nav-font", DEF_BAR_FONT);
        g.at(start + 1 + img, GuiTitle.shadowCode(plugin) + sh, font, adv);
        g.at(start + img, "&f" + n, font, adv);
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

    private void fill(Session s, List<SanctionManager.Entry> all, int pages) {
        Inventory inv = s.holder.inventory;
        inv.clear();
        s.bySlot.clear();
        inv.setItem(SLOT_X, pane(s.onBack != null ? "&cindietro" : "&cchiudi", List.of()));
        inv.setItem(SLOT_X2, pane(s.onBack != null ? "&cindietro" : "&cchiudi", List.of()));
        inv.setItem(SLOT_PREV, pane("&7pagina precedente", List.of()));
        inv.setItem(SLOT_NEXT, pane("&7pagina successiva", List.of()));
        if (all.isEmpty()) {
            inv.setItem(gridSlot(10), pane("&7nessuna voce", List.of("&8La cronologia e' vuota.")));
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = s.page * PER_PAGE + i;
            if (idx >= all.size()) {
                break;
            }
            SanctionManager.Entry e = all.get(idx);
            int slot = gridSlot(i);
            inv.setItem(slot, entryItem(e));
            s.bySlot.put(slot, e.id());
        }
    }

    private ItemStack entryItem(SanctionManager.Entry e) {
        String action = e.action() == null ? "" : e.action().toUpperCase();
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
            default -> { mat = Material.PAPER; color = "&7"; label = action.toLowerCase(); }
        }
        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text(color + kindName(e.kind()) + " &7- " + color + label));
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>();
        lore.add(plugin.text("&7azione: &f" + label));
        lore.add(plugin.text("&7da: &f" + (e.byName() == null || e.byName().isBlank() ? "sistema" : e.byName())));
        lore.add(plugin.text("&7quando: &f" + date(e.time())));
        if (e.durationMs() > 0) {
            lore.add(plugin.text("&7durata: &f" + duration(e.durationMs())));
        }
        if (e.reason() != null && !e.reason().isBlank()) {
            lore.add(plugin.text("&7motivo: &f" + e.reason().replace('&', ' ')));
        }
        if (e.note() != null && !e.note().isBlank()) {
            lore.add(plugin.text("&7nota: &f" + e.note().replace('&', ' ')));
        }
        lore.add(plugin.text(" "));
        lore.add(plugin.text("&etasto Q per eliminare la voce"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private static String date(long ms) {
        synchronized (DATE) {
            return DATE.format(new Date(ms));
        }
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
    //  Live
    // ------------------------------------------------------------------

    private void refreshTarget(UUID target) {
        for (Session s : new ArrayList<>(sessions.values())) {
            if (!s.target.equals(target) || !s.staff.isOnline()) {
                continue;
            }
            if (s.staff.getOpenInventory().getTopInventory().getHolder() != s.holder) {
                continue;
            }
            List<SanctionManager.Entry> all = entries(s);
            int pages = pageCount(all.size());
            s.page = Math.min(s.page, pages - 1);
            String key = s.page + "/" + pages;
            if (key.equals(s.titleKey)) {
                fill(s, all, pages);
            } else {
                // il titolo (numero pagina) cambia: serve un inventario nuovo
                open(s.staff, s.target, s.kind, s.page, s.onBack);
            }
        }
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
        if (slot == SLOT_X || slot == SLOT_X2) {
            plugin.guiClick(p);
            sessions.remove(p.getUniqueId());
            Runnable back = s.onBack;
            if (back != null) {
                Bukkit.getScheduler().runTask(plugin, () -> back.run());
            } else {
                p.closeInventory();
            }
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            plugin.guiClick(p);
            int pages = pageCount(entries(s).size());
            int np = Math.max(0, Math.min(pages - 1, s.page + (slot == SLOT_NEXT ? 1 : -1)));
            if (np != s.page) {
                s.page = np;
                refreshTarget(s.target);
            }
            return;
        }
        Long id = s.bySlot.get(slot);
        if (id == null) {
            return;
        }
        ClickType ct = event.getClick();
        if (ct != ClickType.DROP && ct != ClickType.CONTROL_DROP) {
            return;
        }
        plugin.guiClick(p);
        final long entryId = id;
        final UUID target = s.target;
        final SanctionManager.Kind kind = s.kind;
        final int page = s.page;
        final Runnable back = s.onBack;
        Bukkit.getScheduler().runTask(plugin, () -> ui.confirm(p, "Eliminare questa voce della cronologia?", () -> {
            sm.deleteEntry(entryId, p.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> open(p, target, kind, page, back));
        }, () -> Bukkit.getScheduler().runTask(plugin, () -> open(p, target, kind, page, back))));
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
