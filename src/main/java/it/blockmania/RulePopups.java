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
        int page;
        Runnable onBack;
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
        openList(staff, title, entries, onBack, 0);
    }

    private void openList(Player staff, String title, List<ItemStack> entries, Runnable onBack, int page) {
        Session s = new Session();
        s.staff = staff;
        s.title = title == null ? "" : title;
        s.entries = entries == null ? new ArrayList<>() : entries;
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
    }

    /** Cronologia modifiche della regola, dalla piu' recente. */
    public void historyList(Player staff, Rules.Rule r, Runnable onBack) {
        List<ItemStack> list = new ArrayList<>();
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
                list.add(item(Material.PAPER, "&f" + field, lore));
            }
        }
        openList(staff, "cronologia " + r.code(), list, onBack);
    }

    /** Player sanzionati con questa regola, dal piu' recente. */
    public void hitsList(Player staff, Rules.Rule r, Runnable onBack) {
        List<ItemStack> list = new ArrayList<>();
        List<Rules.Hit> hits = rules.hits(r.code());
        if (hits != null) {
            for (Rules.Hit h : hits) {
                List<String> lore = new ArrayList<>();
                lore.add("&7sanzione: &f" + (h.kind() == null ? "-" : Rules.typeLabel(h.kind())));
                lore.add("&7da: &f" + name(h.byName()));
                lore.add("&7il: &f" + date(h.time()));
                lore.add("&7durata: &f" + minutesLabel(h.minutes()));
                list.add(item(kindMaterial(h.kind()), "&f" + name(h.targetName()), lore));
            }
        }
        openList(staff, "sanzionati " + r.code(), list, onBack);
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

    private static String minutesLabel(long m) {
        if (m == Rules.PERMANENT) {
            return "permanente";
        }
        if (m == Rules.BLACKLIST) {
            return "blacklist";
        }
        if (m <= 0) {
            return "-";
        }
        long d = m / 1440;
        long h = (m % 1440) / 60;
        long mi = m % 60;
        StringBuilder sb = new StringBuilder();
        if (d > 0) {
            sb.append(d).append("g");
        }
        if (h > 0) {
            sb.append(sb.length() > 0 ? " " : "").append(h).append("h");
        }
        if (mi > 0 && d == 0) {
            sb.append(sb.length() > 0 ? " " : "").append(mi).append("m");
        }
        return sb.length() == 0 ? "-" : sb.toString();
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
        }, onBack, false, null);
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
            ui.confirm(staff, "Salvare: " + (v.length() > 40 ? v.substring(0, 40) + "..." : v) + "?", () -> {
                String err = edit.apply(v);
                if (err != null) {
                    GuiMsg.send(plugin, staff, "&c" + err);
                } else {
                    GuiMsg.send(plugin, staff, "&aRegola modificata.");
                }
                if (onDone != null) {
                    onDone.run();
                }
            }, () -> Bukkit.getScheduler().runTask(plugin, onBack));
        }, () -> Bukkit.getScheduler().runTask(plugin, onBack));
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
        }, onBack, false, null);
    }

    private void askMinutes(Player staff, String code, Rules.Type t, Runnable onBack, Runnable onDone) {
        String hint = t == Rules.Type.BAN ? "numero di minuti, permanente o blacklist" : "numero di minuti o permanente";
        ui.askText(staff, "tempo (" + hint + ")", "TEMPO", "", in -> {
            String err = Rules.parseTimeError(t, in);
            if (err != null) {
                GuiMsg.send(plugin, staff, "&c" + err);
                Bukkit.getScheduler().runTask(plugin, () -> askMinutes(staff, code, t, onBack, onDone));
                return;
            }
            applyType(staff, code, t, Rules.parseTime(t, in), onDone);
        }, () -> Bukkit.getScheduler().runTask(plugin, onBack));
    }

    private void applyType(Player staff, String code, Rules.Type t, long minutes, Runnable onDone) {
        String err = rules.editType(code, t, minutes, staff);
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
        g.at(-15, "&f" + DEF_IMAGE, DEF_IMAGE_FONT, DEF_ADV);
        navText(g, s.page + 1, pages);
        UserProfilePowers.topTitle(plugin, g, s.title);
        return g.build();
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
        if (s.entries.isEmpty()) {
            inv.setItem(gridSlot(10), pane("&7nessuna voce", List.of("&8La lista e' vuota.")));
            return;
        }
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = s.page * PER_PAGE + i;
            if (idx >= s.entries.size()) {
                break;
            }
            inv.setItem(gridSlot(i), s.entries.get(idx));
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
        if (slot == SLOT_PREV || slot == SLOT_NEXT) {
            plugin.guiClick(p);
            int pages = pageCount(s.entries.size());
            int np = Math.max(0, Math.min(s.page + (slot == SLOT_NEXT ? 1 : -1), pages - 1));
            if (np != s.page) {
                // il numero pagina e' nel titolo: serve un inventario nuovo
                Bukkit.getScheduler().runTask(plugin, () -> openList(p, s.title, s.entries, s.onBack, np));
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
