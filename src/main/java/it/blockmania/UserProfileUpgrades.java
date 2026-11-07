package it.blockmania;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Potenziamenti del player visti dallo staff (profilo staff): stessa GUI dei potenziamenti, ma sul
 * target e con il tasto reset al posto dell'info. Click su un potenziamento = popup con aggiungi 1 livello,
 * rimuovi 1 livello, setta, maxa, azzera. La GUI e' live (si aggiorna da sola).
 * Sfondo staff: glifo U+E116 del font minecraft:userprofile (STAFF_GUIS.md di Sara).
 */
public final class UserProfileUpgrades implements Listener {

    private static final int SIZE = 54;
    private static final String IMG_STAFF = "\ue116";

    private final BlockMania plugin;
    private final UserProfilePowers ui;

    public UserProfileUpgrades(BlockMania plugin, UserProfilePowers ui) {
        this.plugin = plugin;
        this.ui = ui;
    }

    /** Serve a riconoscere la nostra GUI tra tutti gli inventari. */
    private static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final UUID staff;
        private final UUID target;
        private final Runnable onBack;
        private int page;
        /** slot -> azione: close, prev, next, reset oppure up:<potenziamento>. */
        private final Map<Integer, String> actions = new HashMap<>();

        Holder(UUID staff, UUID target, int page, Runnable onBack) {
            this.staff = staff;
            this.target = target;
            this.page = page;
            this.onBack = onBack;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public boolean owns(Player p) {
        return p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder;
    }

    // ------------------------------------------------------------------
    //  Aprire
    // ------------------------------------------------------------------

    public void open(Player staff, Player target, Runnable onBack) {
        if (staff == null || target == null) {
            return;
        }
        if (!target.isOnline()) {
            staff.sendMessage(plugin.color("&cIl giocatore deve essere online per gestire i potenziamenti."));
            return;
        }
        if (!authorize(staff, target.getUniqueId(), "see")) {
            return;
        }
        open(staff, target, 0, onBack);
    }

    private void open(Player staff, Player target, int page, Runnable onBack) {
        UpgradeManager um = plugin.getUpgrades();
        LauncherItem li = anyLauncher();
        if (um == null || li == null) {
            staff.sendMessage(plugin.color("&cPotenziamenti non disponibili."));
            return;
        }
        int pages = um.pageCount();
        Holder h = new Holder(staff.getUniqueId(), target.getUniqueId(), Math.max(0, Math.min(page, pages - 1)), onBack);
        Inventory inv = Bukkit.createInventory(h, SIZE, title(h.page, pages));
        h.inventory = inv;
        fill(inv, h, target, li);
        staff.openInventory(inv);
        startLive(h, staff);
    }

    private LauncherItem anyLauncher() {
        Map<String, LauncherItem> all = plugin.getItems();
        if (all == null || all.isEmpty()) {
            return null;
        }
        String preferred = plugin.getConfig().getString("bossbar.ammo-type", "standardball");
        for (LauncherItem li : all.values()) {
            if (preferred.equalsIgnoreCase(li.ammoType())) {
                return li;
            }
        }
        return all.values().iterator().next();
    }

    // ------------------------------------------------------------------
    //  Live
    // ------------------------------------------------------------------

    private void startLive(Holder h, Player staff) {
        new BukkitRunnable() {
            @Override
            public void run() {
                Player s = Bukkit.getPlayer(h.staff);
                if (s == null || !s.isOnline() || s.getOpenInventory().getTopInventory().getHolder() != h) {
                    cancel();
                    return;
                }
                Player t = Bukkit.getPlayer(h.target);
                if (t == null) {
                    cancel();
                    s.closeInventory();
                    s.sendMessage(plugin.color("&cIl giocatore e' uscito dal server."));
                    return;
                }
                LauncherItem li = anyLauncher();
                if (li != null) {
                    fill(h.inventory, h, t, li);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // ------------------------------------------------------------------
    //  Disegno (copia del rendering dei potenziamenti, sul target)
    // ------------------------------------------------------------------

    private Component title(int page, int pages) {
        var c = plugin.getConfig();
        GuiTitle t = new GuiTitle(plugin);
        // sfondo staff (tasto reset al posto dell'info): U+E116 nel font minecraft:userprofile, advance 186
        t.at(-15, "&f" + IMG_STAFF, "minecraft:userprofile", 186);
        String pg = UpgradeManager.GUI + "title.page.";
        String pageRaw = c.getString(pg + "text", "");
        if (pageRaw != null && !pageRaw.isBlank()) {
            String plain = BlockMania.stripCodes(pageRaw)
                    .replace("%page%", String.valueOf(page + 1))
                    .replace("%pages%", String.valueOf(pages))
                    .replace("%slash%", "/");
            String set = c.getString(pg + "set", "main");
            String shadowSet = c.getString(pg + "shadow-set", "main-shadow");
            int center = c.getInt(pg + "center", 73);
            int start = center - GuiTitle.widthOf(plugin, set, plain) / 2;
            t.number(start, set, shadowSet, c.getBoolean(pg + "shadow", true), plain, c.getString(pg + "font", ""));
        }
        return t.build();
    }

    private void put(Inventory inv, Holder h, int slot, ItemStack item, String action) {
        if (slot < 0 || slot >= SIZE) {
            return;
        }
        inv.setItem(slot, item);
        if (action != null) {
            h.actions.put(slot, action);
        } else {
            h.actions.remove(slot);
        }
    }

    private void fill(Inventory inv, Holder h, Player target, LauncherItem li) {
        UpgradeManager um = plugin.getUpgrades();
        var c = plugin.getConfig();
        String GUI = UpgradeManager.GUI;
        List<UpgradeManager.Position> positions = um.positions();
        List<String> upgrades = um.orderedUpgrades();
        int pages = um.pageCount();
        h.page = Math.max(0, Math.min(h.page, pages - 1));
        String[] general = um.general(target, h.page, pages);

        h.actions.clear();
        inv.clear();

        for (int slot : um.slotList(GUI + "slots.close")) {
            put(inv, h, slot, um.invisible(c.getString(GUI + "close.name", "&cchiudi"),
                    c.getStringList(GUI + "close.lore"), general, false), "close");
        }
        for (int slot : um.slotList(GUI + "slots.previous")) {
            put(inv, h, slot, um.invisible(c.getString(GUI + "previous.name", "&7pagina precedente"),
                    c.getStringList(GUI + "previous.lore"), general, false), "prev");
        }
        for (int slot : um.slotList(GUI + "slots.next")) {
            put(inv, h, slot, um.invisible(c.getString(GUI + "next.name", "&7pagina successiva"),
                    c.getStringList(GUI + "next.lore"), general, false), "next");
        }
        // al posto del tasto info c'e' il tasto reset (stessi slot)
        List<String> resetLore = List.of("", "&7Riporta tutti i potenziamenti di",
                "&f" + target.getName() + " &7a livello 0.", "", "&eclicca per resettare");
        for (int slot : um.slotList(GUI + "slots.info")) {
            put(inv, h, slot, um.invisible("&creset potenziamenti", resetLore, general, false), "reset");
        }

        for (int i = 0; i < positions.size(); i++) {
            UpgradeManager.Position pos = positions.get(i);
            int index = h.page * positions.size() + i;
            if (index >= upgrades.size()) {
                String bp = GUI + "buttons.none";
                put(inv, h, pos.button(), um.pane(bp, 3, c.getString(bp + ".name", "&8in arrivo"),
                        c.getStringList(bp + ".lore"), general, false), null);
                for (int extra : pos.extra()) {
                    put(inv, h, extra, um.invisible(c.getString(bp + ".name", "&8in arrivo"),
                            c.getStringList(bp + ".lore"), general, false), null);
                }
                continue;
            }

            String id = upgrades.get(index);
            String[] r = um.upgradeValues(target, id, li, general);
            String action = "up:" + id;

            String ip = GUI + "icons." + id;
            List<String> iconLore = c.isList(ip + ".lore") ? c.getStringList(ip + ".lore")
                    : c.getStringList(GUI + "icon-lore");
            put(inv, h, pos.icon(), um.pane(ip, 10, c.getString(ip + ".name", id), iconLore, r, false), action);

            // il tasto verde/rosso/grigio resta come nel gioco, ma qui serve a gestire il livello
            UpgradeManager.State state = um.stateOf(target, id);
            String key = switch (state) {
                case AVAILABLE -> "available";
                case LOCKED -> "locked";
                case MAX -> "maxed";
                default -> "none";
            };
            String bp = GUI + "buttons." + key;
            int defaultCmd = state == UpgradeManager.State.AVAILABLE ? 1
                    : (state == UpgradeManager.State.LOCKED || state == UpgradeManager.State.MAX) ? 2 : 3;
            String name = "&alivello &f%level%&8/&f%max%";
            List<String> lore = List.of("",
                    "&8• &7%label% &f%current%",
                    "&8• &7livello &f%level%&8/&f%max%",
                    "",
                    "&eclicca per gestire il livello");
            put(inv, h, pos.button(), um.pane(bp, defaultCmd, name, lore, r, false), action);
            for (int extra : pos.extra()) {
                put(inv, h, extra, um.invisible(name, lore, r, false), action);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Click
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder h)) {
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
        if (event.getClick() == ClickType.DOUBLE_CLICK) {
            return;
        }
        String action = h.actions.get(event.getSlot());
        if (action == null) {
            return;
        }
        Player target = Bukkit.getPlayer(h.target);
        if (target == null) {
            staff.closeInventory();
            staff.sendMessage(plugin.color("&cIl giocatore e' uscito dal server."));
            return;
        }
        plugin.guiClick(staff);
        UpgradeManager um = plugin.getUpgrades();
        int pages = um.pageCount();
        switch (action) {
            case "close" -> {
                Runnable back = h.onBack;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (back != null) {
                        back.run();
                    } else {
                        staff.closeInventory();
                    }
                });
            }
            case "prev" -> {
                if (h.page > 0) {
                    int p = h.page - 1;
                    Bukkit.getScheduler().runTask(plugin, () -> open(staff, target, p, h.onBack));
                }
            }
            case "next" -> {
                if (h.page < pages - 1) {
                    int p = h.page + 1;
                    Bukkit.getScheduler().runTask(plugin, () -> open(staff, target, p, h.onBack));
                }
            }
            case "reset" -> Bukkit.getScheduler().runTask(plugin, () -> askReset(staff, h));
            default -> {
                if (action.startsWith("up:")) {
                    String id = action.substring(3);
                    Bukkit.getScheduler().runTask(plugin, () -> askLevelCommand(staff, h, id));
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    //  Azioni
    // ------------------------------------------------------------------

    private void reopen(Player staff, Holder h) {
        if (!staff.isOnline()) {
            return;
        }
        Player target = Bukkit.getPlayer(h.target);
        if (target == null) {
            staff.sendMessage(plugin.color("&cIl giocatore e' uscito dal server."));
            return;
        }
        open(staff, target, h.page, h.onBack);
    }

    private void askReset(Player staff, Holder h) {
        Runnable back = () -> reopen(staff, h);
        if (!authorize(staff, h.target, "reset")) {
            back.run();
            return;
        }
        ui.confirm(staff, "reset potenziamenti", () -> {
            Player target = Bukkit.getPlayer(h.target);
            if (target == null) {
                staff.sendMessage(plugin.color("&cIl giocatore e' uscito dal server."));
                return;
            }
            if (!authorize(staff, h.target, "reset")) {
                back.run();
                return;
            }
            plugin.getUpgrades().resetLevels(target);
            audit(staff, "profile.reset upgrades " + target.getName());
            done(staff, "&aPotenziamenti di " + target.getName() + " azzerati.");
            back.run();
        }, back);
    }

    private static String verbOf(String cmd) {
        return switch (cmd) {
            case "give" -> "get";
            case "remove" -> "remove";
            case "reset" -> "reset";
            default -> "edit";   // set, full
        };
    }

    private String labelOf(String id) {
        String raw = plugin.getConfig().getString("upgrades." + id + ".short",
                plugin.getConfig().getString("upgrades." + id + ".label", id));
        return BlockMania.stripCodes(raw == null ? id : raw).toUpperCase(Locale.ROOT);
    }

    /** Popup comandi sul livello: aggiungi 1, rimuovi 1, setta, maxa, azzera (reset sempre ultimo). */
    private void askLevelCommand(Player staff, Holder h, String id) {
        Runnable back = () -> reopen(staff, h);
        ui.openCommands(staff, labelOf(id), List.of("give", "remove", "set", "full", "reset"),
                cmd -> pick(staff, h, id, cmd), back, false, null);
    }

    private void pick(Player staff, Holder h, String id, String cmd) {
        if (!staff.isOnline()) {
            return;
        }
        Runnable back = () -> reopen(staff, h);
        Player target = Bukkit.getPlayer(h.target);
        if (target == null) {
            staff.sendMessage(plugin.color("&cIl giocatore e' uscito dal server."));
            return;
        }
        if (!authorize(staff, h.target, verbOf(cmd))) {
            back.run();
            return;
        }
        UpgradeManager um = plugin.getUpgrades();
        int max = um.maxLevel(id);
        int cur = um.getLevel(target, id);
        switch (cmd) {
            case "give" -> {
                if (cur >= max) {
                    fail(staff, "&c" + labelOf(id) + " e' gia' al livello massimo (" + max + ").");
                } else {
                    apply(staff, h, id, cur + 1, "give");
                }
                back.run();
            }
            case "remove" -> {
                if (cur <= 0) {
                    fail(staff, "&c" + labelOf(id) + " e' gia' a livello 0.");
                } else {
                    apply(staff, h, id, cur - 1, "remove");
                }
                back.run();
            }
            case "set" -> ui.askNumber(staff, "set " + labelOf(id).toLowerCase(Locale.ROOT),
                    "SETTA " + labelOf(id) + " " + cur + "/" + max, 0, max,
                    v -> {
                        apply(staff, h, id, (int) Math.min(max, v), "set");
                        back.run();
                    }, back);
            case "full" -> ui.confirm(staff, "maxa " + labelOf(id).toLowerCase(Locale.ROOT), () -> {
                apply(staff, h, id, max, "full");
                back.run();
            }, back);
            case "reset" -> ui.confirm(staff, "azzera " + labelOf(id).toLowerCase(Locale.ROOT), () -> {
                apply(staff, h, id, 0, "reset");
                back.run();
            }, back);
            default -> back.run();
        }
    }

    private void apply(Player staff, Holder h, String id, int level, String cmd) {
        Player target = Bukkit.getPlayer(h.target);
        if (target == null) {
            staff.sendMessage(plugin.color("&cIl giocatore e' uscito dal server."));
            return;
        }
        if (!authorize(staff, h.target, verbOf(cmd))) {
            return;
        }
        plugin.getUpgrades().setLevelAdmin(target, id, level);
        audit(staff, "profile." + verbOf(cmd) + " upgrade " + id + " " + target.getName() + " livello " + level);
        done(staff, "&a" + labelOf(id) + " di " + target.getName() + ": livello " + level + ".");
    }

    // ------------------------------------------------------------------
    //  Permessi e messaggi
    // ------------------------------------------------------------------

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

    private void done(Player p, String text) {
        p.sendMessage(plugin.color(text));
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
    }

    private void fail(Player p, String text) {
        p.sendMessage(plugin.color(text));
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.error");
    }
}
