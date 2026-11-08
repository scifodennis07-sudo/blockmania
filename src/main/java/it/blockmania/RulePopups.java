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
import org.bukkit.inventory.meta.SkullMeta;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Popup del dettaglio regola: lista generica a griglia 7x3 (cronologia modifiche, player sanzionati) e menu "cosa modifichi".
 * La lista usa la stessa grafica di UserProfileHistory (userprofile-8) ma senza pulsante reset e con frecce NON circolari.
 * Non e' live: i dati sono quelli del momento dell'apertura.
 */
public final class RulePopups implements Listener {

    /** Immagine della lista = userprofile-8 (cornice vuota). */
    private static final String DEF_IMAGE = "";
    private static final String DEF_IMAGE_FONT = "minecraft:userprofile";
    private static final char DEF_NAV_BASE = '\uF700';
    private static final char DEF_NAV_SHADOW = '\uF710';
    private static final String DEF_NAV_FONT = "minecraft:tiny3";
    private static final int DEF_NAV_CX = 94;
    private static final int DEF_IMG = -22;
    private static final int DEF_ADV = 179;

    private static final int SIZE = 54;
    private static final int PER_PAGE = 21;
    private static final int SLOT_X = 1;
    private static final int SLOT_X2 = 2;
    private static final int[] INFO_SLOTS = {6, 7};
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
        String title;
        List<ItemStack> entries;
        /** UUID (anche null) per voce: se la lista non e' null le teste sono 2D nel titolo. */
        List<UUID> faces;
        boolean refreshQueued;
        int page;
        Runnable onBack;
        java.util.function.Consumer<GuiTitle> background;
        Holder holder;
    }

    private final BlockMania plugin;
    private final Rules rules;
    private final UserProfilePowers ui;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> switching = new HashSet<>();

    public RulePopups(BlockMania plugin, Rules rules, UserProfilePowers ui) {
        this.plugin = plugin;
        this.rules = rules;
        this.ui = ui;
    }

    /** True se il giocatore ha aperto una lista di questa classe. */
    public boolean owns(Player p) {
        return p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder;
    }

    // ------------------------------------------------------------------
    //  Liste
    // ------------------------------------------------------------------

    /** Lista a griglia 7x3 con il titolo nella barra; X torna (onBack) o chiude. */
    public void openList(Player staff, String title, List<ItemStack> entries, Runnable onBack) {
        openList(staff, title, entries, null, onBack, 0, null);
    }

    /** Come sopra, ma con una faccia 2D (nel titolo) per voce: faces.get(i) puo' essere null (sagoma grigia). */
    public void openList(Player staff, String title, List<ItemStack> entries, List<UUID> faces, Runnable onBack) {
        openList(staff, title, entries, faces, onBack, 0, null);
    }

    /** Come sopra, con sfondo personalizzato (gia' scurito) al posto del profilo. */
    public void openList(Player staff, String title, List<ItemStack> entries, List<UUID> faces, Runnable onBack,
                         java.util.function.Consumer<GuiTitle> background) {
        openList(staff, title, entries, faces, onBack, 0, background);
    }

    private void openList(Player staff, String title, List<ItemStack> entries, List<UUID> faces, Runnable onBack, int page,
                          java.util.function.Consumer<GuiTitle> background) {
        Session s = new Session();
        s.background = background;
        s.staff = staff;
        s.title = title == null ? "" : title;
        s.entries = entries == null ? new ArrayList<>() : entries;
        s.faces = faces;
        s.onBack = onBack;
        int pages = pageCount(s.entries.size());
        s.page = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder();
        h.session = s;
        s.holder = h;
        h.inventory = Bukkit.createInventory(h, SIZE, titleOf(s, pages));
        fill(s);
        UUID id = staff.getUniqueId();
        switching.add(id);
        try {
            staff.openInventory(h.inventory);
        } finally {
            switching.remove(id);
        }
        sessions.put(id, s);
        requestFaces(s);
    }

    private UUID faceId(Session s, int idx) {
        return s.faces == null || idx < 0 || idx >= s.faces.size() ? null : s.faces.get(idx);
    }

    /** Avvia il caricamento delle facce della pagina; a ogni faccia pronta ridisegna il titolo (coalescendo). */
    private void requestFaces(Session s) {
        UserProfileGui up = plugin.getUserProfile();
        if (s.faces == null || up == null) {
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = s.page * PER_PAGE + i;
            if (idx >= s.entries.size()) {
                break;
            }
            UUID f = faceId(s, idx);
            if (f != null && up.loadedFace(f) == null) {
                up.ensureFaceFor(f, () -> queueRefresh(s));
            }
        }
    }

    private void queueRefresh(Session s) {
        if (s.refreshQueued) {
            return;
        }
        s.refreshQueued = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            s.refreshQueued = false;
            Player p = s.staff;
            if (p == null || !p.isOnline() || sessions.get(p.getUniqueId()) != s
                    || p.getOpenInventory().getTopInventory().getHolder() != s.holder) {
                return;
            }
            // il titolo non si cambia a inventario aperto: si riapre (switching evita di far scattare onClose)
            openList(p, s.title, s.entries, s.faces, s.onBack, s.page, s.background);
        }, 5L);
    }

    /** Elemento senza testa 3D: pane trasparente con nome e lore dell'originale. */
    private ItemStack flat(ItemStack src) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        ItemMeta sm = src.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        if (sm != null) {
            meta.displayName(sm.displayName());
            meta.lore(sm.lore());
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** Cronologia modifiche della regola, dalla piu' recente. */
    public void historyList(Player staff, Rules.Rule r, Runnable onBack) {
        List<ItemStack> list = new ArrayList<>();
        List<UUID> faces = new ArrayList<>();
        List<Rules.Change> hist = rules.history(r.code());
        if (hist != null) {
            for (Rules.Change c : hist) {
                String field = c.field() == null ? "modifica" : c.field();
                boolean created = field.equals("creata");
                List<String> lore = new ArrayList<>();
                if (!created) {
                    lore.add("&7da: &f" + clean(c.oldValue()));
                    lore.add("&7a: &f" + clean(c.newValue()));
                }
                lore.add((created ? "&7creata da: &f" : "&7di: &f") + name(c.byName()));
                lore.add("&7il: &f" + date(c.time()));
                list.add(pane("&f" + field, lore));
                faces.add(resolve(c.by(), c.byName()));
            }
        }
        openList(staff, "cronologia " + r.code(), list, faces, onBack,
                g -> plugin.getRuleDetailGui().drawDimmedBackground(g, r));
    }

    /** Player sanzionati con questa regola, dal piu' recente. */
    public void hitsList(Player staff, Rules.Rule r, Runnable onBack) {
        List<ItemStack> list = new ArrayList<>();
        List<UUID> faces = new ArrayList<>();
        List<Rules.Hit> hits = rules.hits(r.code());
        if (hits != null) {
            for (Rules.Hit h : hits) {
                List<String> lore = new ArrayList<>();
                lore.add("&7sanzione: &f" + (h.kind() == null ? "-" : Rules.typeLabel(h.kind())));
                lore.add("&7da: &f" + name(h.byName()));
                lore.add("&7il: &f" + date(h.time()));
                lore.add("&7durata: &f" + durationLabel(h.durationMs()));
                list.add(pane("&f" + name(h.targetName()), lore));
                faces.add(resolve(h.target(), h.targetName()));
            }
        }
        openList(staff, "sanzionati " + r.code(), list, faces, onBack,
                g -> plugin.getRuleDetailGui().drawDimmedBackground(g, r));
    }

    /** UUID noto, altrimenti dal nome solo se in cache (nessun lookup di rete sul main thread); null se sconosciuto. */
    private static UUID resolve(UUID uuid, String playerName) {
        if (uuid != null) {
            return uuid;
        }
        if (playerName == null || playerName.isBlank()) {
            return null;
        }
        org.bukkit.OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(playerName);
        return off == null ? null : off.getUniqueId();
    }

    private static Material kindMaterial(Rules.Type t) {
        if (t == null) {
            return Material.PAPER;
        }
        return switch (t) {
            case BAN -> Material.RED_CONCRETE;
            case WARN -> Material.YELLOW_CONCRETE;
            case MUTE -> Material.ORANGE_CONCRETE;
            case REGOLAMENTO -> Material.LIGHT_BLUE_CONCRETE;
        };
    }

    private static String durationLabel(long ms) {
        if (ms == Rules.PERMANENT) {
            return "permanente";
        }
        if (ms == Rules.BLACKLIST) {
            return "blacklist";
        }
        if (ms <= 0) {
            return "-";
        }
        return SanctionManager.fmt(ms).trim();
    }

    private static String name(String n) {
        return n == null || n.isBlank() ? "sconosciuto" : n.replace('&', ' ');
    }

    private static String clean(String v) {
        return v == null || v.isBlank() ? "-" : v.replace('&', ' ');
    }

    private static String date(long ms) {
        synchronized (DATE) {
            return DATE.format(new Date(ms));
        }
    }

    // ------------------------------------------------------------------
    //  Menu modifica
    // ------------------------------------------------------------------

    /** Popup "cosa modifichi": codice, titolo, descrizione, sanzione. Dopo ogni modifica onDone; annulla = onBack. */
    public void editMenu(Player staff, Rules.Rule r, Runnable onBack, Runnable onDone) {
        final String code = r.code();
        ui.openCommands(staff, "modifica " + code, List.of("code", "title", "text", "type"), cmd -> {
            Rules.Rule cur = rules.get(code);
            if (cur == null) {
                GuiMsg.send(plugin, staff, "&cLa regola non esiste piu'.");
                if (onDone != null) {
                    onDone.run();
                }
                return;
            }
            switch (cmd) {
                case "code" -> askField(staff, cur, "codice della regola", "CODICE", cur.code(), onBack, onDone,
                        (v) -> rules.editCode(code, v, staff));
                case "title" -> askField(staff, cur, "titolo della regola", "TITOLO", cur.title(), onBack, onDone,
                        (v) -> rules.editTitle(code, v, staff));
                case "text" -> askField(staff, cur, "descrizione della regola", "DESCRIZIONE", cur.text(), onBack, onDone,
                        (v) -> rules.editText(code, v, staff));
                case "type" -> askType(staff, cur, onBack, onDone);
                default -> onBack.run();
            }
        }, onBack, false, null, g -> plugin.getRuleDetailGui().drawDimmedBackground(g, rules.get(code) != null ? rules.get(code) : r));
    }

    private interface Edit {
        String apply(String value);
    }

    private void askField(Player staff, Rules.Rule r, String title, String bar, String initial,
                          Runnable onBack, Runnable onDone, Edit edit) {
        ui.askText(staff, title, bar, initial, in -> {
            String v = in == null ? "" : in.trim();
            if (v.isEmpty()) {
                GuiMsg.send(plugin, staff, "&cScrivi qualcosa: il valore non puo' essere vuoto.");
                Bukkit.getScheduler().runTask(plugin, () -> askField(staff, r, title, bar, initial, onBack, onDone, edit));
                return;
            }
            Rules.Rule bgRule = rules.get(r.code()) != null ? rules.get(r.code()) : r;
            Runnable backToMenu = () -> Bukkit.getScheduler().runTask(plugin, () -> editMenu(staff, bgRule, onBack, onDone));
            ui.confirmOver(staff, "Salvare: " + (v.length() > 40 ? v.substring(0, 40) + "..." : v) + "?",
                    g -> plugin.getRuleDetailGui().drawDimmedBackground(g, bgRule), () -> {
                String err = edit.apply(v);
                if (err != null) {
                    GuiMsg.send(plugin, staff, "&c" + err);
                } else {
                    GuiMsg.send(plugin, staff, "&aRegola modificata.");
                }
                if (onDone != null) {
                    onDone.run();
                }
            }, backToMenu);
        }, () -> Bukkit.getScheduler().runTask(plugin, () -> editMenu(staff, rules.get(r.code()) != null ? rules.get(r.code()) : r, onBack, onDone)));
    }

    private void askType(Player staff, Rules.Rule r, Runnable onBack, Runnable onDone) {
        final String code = r.code();
        ui.openCommands(staff, "modifica sanzione " + code, List.of("ban", "warn", "mute", "regolamento"), cmd -> {
            Rules.Type t;
            try {
                t = Rules.Type.valueOf(cmd.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                onBack.run();
                return;
            }
            if (t == Rules.Type.BAN || t == Rules.Type.MUTE) {
                askMinutes(staff, code, t, onBack, onDone);
            } else {
                applyType(staff, code, t, 0, onDone);
            }
        }, () -> Bukkit.getScheduler().runTask(plugin, () -> editMenu(staff, rules.get(code) != null ? rules.get(code) : r, onBack, onDone)),
                false, null, g -> plugin.getRuleDetailGui().drawDimmedBackground(g, rules.get(code) != null ? rules.get(code) : r));
    }

    private void askMinutes(Player staff, String code, Rules.Type t, Runnable onBack, Runnable onDone) {
        String hint = t == Rules.Type.BAN ? "permanente (e blacklist per i ban)" : "permanente";
        ui.askText(staff, "scrivi il tempo: 10s 10m 10h 10d, anche somme come 10h+23m+16s; " + hint, "TEMPO", "", in -> {
            String err = Rules.parseTimeError(t, in);
            if (err != null) {
                GuiMsg.send(plugin, staff, "&c" + err);
                Bukkit.getScheduler().runTask(plugin, () -> askMinutes(staff, code, t, onBack, onDone));
                return;
            }
            applyType(staff, code, t, Rules.parseTime(t, in), onDone);
        }, () -> Bukkit.getScheduler().runTask(plugin, () -> {
            Rules.Rule cur = rules.get(code);
            if (cur == null) {
                onBack.run();
            } else {
                askType(staff, cur, onBack, onDone);
            }
        }));
    }

    private void applyType(Player staff, String code, Rules.Type t, long durationMs, Runnable onDone) {
        String err = rules.editType(code, t, durationMs, staff);
        if (err != null) {
            GuiMsg.send(plugin, staff, "&c" + err);
        } else {
            GuiMsg.send(plugin, staff, "&aSanzione della regola modificata.");
        }
        if (onDone != null) {
            onDone.run();
        }
    }

    // ------------------------------------------------------------------
    //  Disegno lista
    // ------------------------------------------------------------------

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
        // base lista solo-pannello userprofile-23 (U+E116, font userprofile2): lo sfondo custom resta visibile
        g.at(-15, "&f", "minecraft:userprofile2", DEF_ADV);
        drawFaces(g, s);
        navText(g, s.page + 1, pages);
        UserProfilePowers.topTitle(plugin, g, s.title);
        return g.build();
    }

    /** Facce 2D 16x16 (scala 2) dentro le celle 18x18 della griglia 7x3: cella i = (32+18*(i%7), 50+18*(i/7)) + 1 pixel di margine. */
    private void drawFaces(GuiTitle g, Session s) {
        if (s.faces == null) {
            return;
        }
        UserProfileGui up = plugin.getUserProfile();
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = s.page * PER_PAGE + i;
            if (idx >= s.entries.size()) {
                break;
            }
            UUID f = faceId(s, idx);
            int[] px = up == null || f == null ? null : up.loadedFace(f);
            UserProfileGui.drawFaceAt(g, px, 33 + 18 * (i % 7), 51 + 18 * (i / 7), 2);
        }
    }

    private void navText(GuiTitle g, int page, int pages) {
        String plain = page + "/" + pages;
        StringBuilder n = new StringBuilder();
        StringBuilder sh = new StringBuilder();
        for (char ch : plain.toCharArray()) {
            int o = ch == '/' ? 10 : ch - '0';
            n.append((char) (DEF_NAV_BASE + o));
            sh.append((char) (DEF_NAV_SHADOW + o));
        }
        int adv = plain.length() * 6;
        int start = DEF_NAV_CX - (adv - 1) / 2;
        g.at(start + 1 + DEF_IMG, GuiTitle.shadowCode(plugin) + sh, DEF_NAV_FONT, adv);
        g.at(start + DEF_IMG, "&f" + n, DEF_NAV_FONT, adv);
    }

    private void fill(Session s) {
        Inventory inv = s.holder.inventory;
        String back = s.onBack != null ? "&cindietro" : "&cchiudi";
        inv.setItem(SLOT_X, pane(back, List.of()));
        inv.setItem(SLOT_X2, pane(back, List.of()));
        inv.setItem(SLOT_PREV, pane("&7pagina precedente", List.of()));
        inv.setItem(SLOT_NEXT, pane("&7pagina successiva", List.of()));
        for (int b : INFO_SLOTS) {
            inv.setItem(b, pane("&einformazioni", List.of()));
        }
        if (s.entries.isEmpty()) {
            inv.setItem(gridSlot(10), pane("&7nessuna voce", List.of("&8La lista e' vuota.")));
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = s.page * PER_PAGE + i;
            if (idx >= s.entries.size()) {
                break;
            }
            ItemStack e = s.entries.get(idx);
            inv.setItem(gridSlot(i), s.faces == null ? e : flat(e));
        }
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

    /** Testa del player (uuid se noto, altrimenti per nome) con nome e lore. */
    private ItemStack head(UUID uuid, String playerName, String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta im = stack.getItemMeta();
        if (!(im instanceof SkullMeta meta)) {
            return item(Material.PAPER, name, lore);
        }
        org.bukkit.OfflinePlayer off = uuid != null ? Bukkit.getOfflinePlayer(uuid)
                : (playerName != null && !playerName.isBlank() ? Bukkit.getOfflinePlayer(playerName) : null);
        if (off != null) {
            meta.setOwningPlayer(off);
        }
        meta.displayName(plugin.text(name));
        List<net.kyori.adventure.text.Component> l = new ArrayList<>();
        for (String line : lore) {
            l.add(plugin.text(line));
        }
        meta.lore(l);
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
        stack.setItemMeta(meta);
        return stack;
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
        if (slot == INFO_SLOTS[0] || slot == INFO_SLOTS[1]) {
            plugin.guiClick(p);
            int pg = pageCount(s.entries.size());
            GuiMsg.send(plugin, p, "&e" + s.title + ": " + s.entries.size() + " voci, pagina " + (s.page + 1) + "/" + pg
                    + ", frecce per scorrere, X per tornare");
            return;
        }
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            plugin.guiClick(p);
            int pages = pageCount(s.entries.size());
            int np = Math.max(0, Math.min(s.page + (slot == SLOT_NEXT ? 1 : -1), pages - 1));
            if (np != s.page) {
                // il numero pagina e' nel titolo: serve un inventario nuovo
                Bukkit.getScheduler().runTask(plugin, () -> openList(p, s.title, s.entries, s.faces, s.onBack, np, s.background));
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
        if (event.getInventory().getHolder() instanceof Holder h && sessions.get(id) == h.session) {
            sessions.remove(id);
        }
    }
}
