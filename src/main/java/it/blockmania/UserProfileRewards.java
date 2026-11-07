package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
 * Ricompense (e exp del pass) di un giocatore, viste dallo staff dal profilo (UserProfileGui).
 * Disegna la GUI ORIGINALE delle ricompense (la costruisce Rewards.staffView) di "target" con lo sfondo staff
 * (tasto info arancione -> RESET blu, glifi U+E117/E120..E123 del font minecraft:userprofile).
 * <p>
 * Click: tipologia -> percorso dei livelli; livello -> popup comandi [give=+1, remove=-1, set, full=maxa, reset=azzera];
 * giornaliera -> popup sui giorni; RESET (blu) -> conferma e azzera (tutto / la tipologia / la giornaliera).
 * Exp del pass: {@link #expLore(Player)} per la lore dell'icona e {@link #openExp} per il popup give/remove/set/reset.
 * <p>
 * Glifi configurabili (userprofile.staff-rewards.*: font, main, levels, pass, daily-ready, daily-wait, confirm-main)
 * ma i valori di default sono quelli di STAFF_GUIS.md di Sara.
 */
public final class UserProfileRewards implements Listener {

    /** Slot dei bottoni CONFERMA / ANNULLA (come UserProfilePowers.confirm: stessi pixel dei glifi userprofile-6/7). */
    private static final int[] SLOTS_YES = {19, 20, 21, 28, 29, 30};
    private static final int[] SLOTS_NO = {23, 24, 25, 32, 33, 34};

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        Session session;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Session {
        UUID target;
        String kind = "main";        // main | levels | daily
        String type = "";
        int mainPage;
        int page;
        Runnable back;
        Runnable confirmYes;         // conferma disegnata nell'immagine (solo pagina base)
        final Map<Integer, String> actions = new HashMap<>();
    }

    private final BlockMania plugin;
    private final UserProfilePowers ui;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> switching = new HashSet<>();

    public UserProfileRewards(BlockMania plugin, UserProfilePowers ui) {
        this.plugin = plugin;
        this.ui = ui;
    }

    private Rewards rewards() {
        return plugin.getRewards();
    }

    public boolean owns(Player p) {
        return p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder;
    }

    // ------------------------------------------------------------------
    //  API pubblica
    // ------------------------------------------------------------------

    /** Apre le ricompense di "target" (deve essere online). onBack = cosa fare con la X (di solito riaprire il profilo). */
    public void open(Player staff, Player target, Runnable onBack) {
        if (target == null || !target.isOnline()) {
            GuiMsg.send(plugin, staff, "&cIl giocatore deve essere online per gestire le sue ricompense.");
            return;
        }
        Session s = new Session();
        s.target = target.getUniqueId();
        s.back = onBack;
        show(staff, s);
    }

    /** Righe di lore per l'icona exp del profilo (exp totale, livello pass, quanto manca, ritiri). */
    public List<String> expLore(Player target) {
        if (target == null) {
            return List.of("&7Exp: &fgiocatore offline");
        }
        return rewards().staffExpLines(target);
    }

    /** Popup comandi sull'exp del pass: aggiungi, rimuovi, setta, azzera. */
    public void openExp(Player staff, Player target, Runnable onBack) {
        if (target == null || !target.isOnline()) {
            GuiMsg.send(plugin, staff, "&cIl giocatore deve essere online per gestire i suoi exp.");
            return;
        }
        final UUID id = target.getUniqueId();
        ui.openCommands(staff, "EXP", List.of("give", "remove", "set", "reset"),
                cmd -> later(() -> runExp(staff, id, cmd, onBack)),
                () -> {
                    if (onBack != null) {
                        later(onBack);
                    }
                }, false, null);
    }

    // ------------------------------------------------------------------
    //  Disegno della GUI ricompense
    // ------------------------------------------------------------------

    private String cfg(String key, String def) {
        String v = plugin.getConfig().getString("userprofile.staff-rewards." + key, def);
        return v == null || v.isEmpty() ? def : v;
    }

    private Map<String, String> images() {
        // vista staff = GUI originali + pulsante reset (lo disegna Rewards.newTitle): niente immagini alternative
        Map<String, String> m = new HashMap<>();
        m.put("staff", "1");
        return m;
    }

    private ItemStack pane(String name, List<String> lore) {
        Material m = Material.matchMaterial(plugin.getConfig().getString("rewards-gui.invisible.material", "GLASS_PANE"));
        ItemStack stack = new ItemStack(m == null || !m.isItem() ? Material.GLASS_PANE : m);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        meta.displayName(plugin.text(name));
        if (!lore.isEmpty()) {
            List<net.kyori.adventure.text.Component> lines = new ArrayList<>();
            for (String line : lore) {
                lines.add(plugin.text(line));
            }
            meta.lore(lines);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private Player targetOf(Player staff, Session s) {
        Player t = Bukkit.getPlayer(s.target);
        if (t == null) {
            GuiMsg.send(plugin, staff, "&cIl giocatore non e' piu' online.");
            sessions.remove(staff.getUniqueId());
            Runnable back = s.back;
            if (back != null) {
                later(back);
            } else {
                staff.closeInventory();
            }
        }
        return t;
    }

    private void show(Player staff, Session s) {
        Player target = targetOf(staff, s);
        if (target == null) {
            return;
        }
        String confirmImage = null; // le conferme usano ui.confirm (nessuna immagine di conferma nel pack v3)
        Rewards.StaffView v = rewards().staffView(target, s.kind, s.type, s.mainPage,
                s.kind.equals("main") ? s.mainPage : s.page, images(), confirmImage);
        if (s.kind.equals("main")) {
            s.mainPage = v.page();
        } else if (s.kind.equals("levels")) {
            s.page = v.page();
        }
        Holder h = new Holder();
        h.session = s;
        Inventory inv = Bukkit.createInventory(h, v.contents().length, v.title());
        h.inventory = inv;
        s.actions.clear();
        if (confirmImage != null) {
            // conferma nell'immagine: contano solo i due bottoni
            for (int slot : SLOTS_YES) {
                inv.setItem(slot, pane("&aconferma", List.of("&7Azzera tutte le ricompense di &f" + target.getName() + "&7.",
                        "", "&eclicca per confermare")));
                s.actions.put(slot, "yes");
            }
            for (int slot : SLOTS_NO) {
                inv.setItem(slot, pane("&cannulla", List.of("&7Torna indietro senza fare niente.", "", "&eclicca per annullare")));
                s.actions.put(slot, "no");
            }
        } else {
            inv.setContents(v.contents());
            s.actions.putAll(v.actions());
        }
        UUID id = staff.getUniqueId();
        switching.add(id);
        try {
            staff.openInventory(inv);
        } finally {
            switching.remove(id);
        }
        sessions.put(id, s);
    }

    // ------------------------------------------------------------------
    //  Click
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
        Session s = h.session;
        String act = s.actions.get(event.getRawSlot());
        if (act == null || act.equals("deny")) {
            return;
        }
        plugin.guiClick(p);
        if (act.equals("yes")) {
            Runnable yes = s.confirmYes;
            s.confirmYes = null;
            later(() -> {
                if (yes != null) {
                    yes.run();
                }
            });
        } else if (act.equals("no")) {
            s.confirmYes = null;
            later(() -> show(p, s));
        } else if (act.equals("close")) {
            sessions.remove(p.getUniqueId());
            if (s.back != null) {
                later(s.back);
            } else {
                p.closeInventory();
            }
        } else if (act.equals("back")) {
            s.kind = "main";
            s.type = "";
            later(() -> show(p, s));
        } else if (act.startsWith("page:")) {
            s.mainPage += parse(act.substring(5));
            later(() -> show(p, s));
        } else if (act.startsWith("scroll:")) {
            s.page += parse(act.substring(7));
            later(() -> show(p, s));
        } else if (act.startsWith("type:")) {
            // Cliccando una tipologia NON si apre piu' la GUI dei bauli/livelli: solo il popup comandi
            String type = act.substring(5);
            s.kind = "main";
            s.type = type;
            if (type.equals("daily")) {
                later(() -> dailyMenu(p, s));
            } else {
                later(() -> levelMenu(p, s, 0));
            }
        } else if (act.startsWith("lvl:")) {
            final int level = parse(act.substring(4));
            later(() -> levelMenu(p, s, level));
        } else if (act.equals("daily")) {
            later(() -> dailyMenu(p, s));
        } else if (act.equals("reset")) {
            later(() -> resetMenu(p, s));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    /** Conferma che lascia dietro, scurita, la GUI ricompense da cui si arriva (stessa pagina, stesso titolo). */
    private void confirmDim(Player staff, Session s, String text, Runnable onYes) {
        Player t = targetOf(staff, s);
        if (t == null) {
            return;
        }
        final Rewards.StaffView v = rewards().staffViewBackground(t, s.kind, s.type, s.mainPage,
                s.kind.equals("main") ? s.mainPage : s.page, images());
        // lo scurimento lo fa confirmOver; il nome nella barra e' quello della conferma
        ui.confirmOver(staff, text, g -> g.atComponent(0, v.title(), v.cursor()),
                onYes, () -> later(() -> show(staff, s)));
    }

    private void later(Runnable r) {
        Bukkit.getScheduler().runTask(plugin, () -> r.run());
    }

    // ------------------------------------------------------------------
    //  Livelli di una tipologia
    // ------------------------------------------------------------------

    private static String typeLabel(String type) {
        return Rewards.italianType(type).toUpperCase(Locale.ROOT);
    }

    private void levelMenu(Player staff, Session s, int level) {
        final String type = s.type;
        ui.openCommands(staff, typeLabel(type), List.of("give", "remove", "set", "full", "reset"),
                cmd -> later(() -> runLevel(staff, s, type, cmd)),
                () -> later(() -> show(staff, s)), false, null);
    }

    private void runLevel(Player staff, Session s, String type, String cmd) {
        Player t = targetOf(staff, s);
        if (t == null) {
            return;
        }
        if (!authorize(staff, t.getUniqueId(), verbOf(cmd))) {
            later(() -> show(staff, s));
            return;
        }
        final int max = rewards().staffMaxLevel(type);
        final int now = rewards().reached(t, type);
        final String label = typeLabel(type);
        switch (cmd) {
            case "give" -> applyLevel(staff, s, type, Math.min(max, now + 1));
            case "remove" -> applyLevel(staff, s, type, Math.max(0, now - 1));
            case "full" -> applyLevel(staff, s, type, max);
            case "reset" -> confirmDim(staff, s, "Azzerare " + Rewards.italianType(type) + " di " + t.getName() + "?",
                    () -> applyLevel(staff, s, type, 0));
            case "set" -> ui.askNumber(staff, "setta livello " + Rewards.italianType(type),
                    "SETTA " + label + " " + now + "/" + max, 0, max,
                    v -> later(() -> applyLevel(staff, s, type, (int) v)),
                    () -> later(() -> show(staff, s)));
            default -> later(() -> show(staff, s));
        }
    }

    private void applyLevel(Player staff, Session s, String type, int level) {
        Player t = targetOf(staff, s);
        if (t == null) {
            return;
        }
        rewards().setReached(t, type, level);
        GuiMsg.send(plugin, staff, "&a" + t.getName() + ": " + Rewards.italianType(type) + " ora al livello &f"
                + rewards().reached(t, type) + "&a/&f" + rewards().staffMaxLevel(type) + "&a.");
        show(staff, s);
    }

    // ------------------------------------------------------------------
    //  Giornaliera (giorni riscattati)
    // ------------------------------------------------------------------

    private void dailyMenu(Player staff, Session s) {
        ui.openCommands(staff, "GIORNALIERA", List.of("give", "remove", "set", "reset"),
                cmd -> later(() -> runDaily(staff, s, cmd)),
                () -> later(() -> show(staff, s)), false, null);
    }

    private void runDaily(Player staff, Session s, String cmd) {
        Player t = targetOf(staff, s);
        if (t == null) {
            return;
        }
        if (!authorize(staff, t.getUniqueId(), verbOf(cmd))) {
            later(() -> show(staff, s));
            return;
        }
        AmmoManager a = plugin.getAmmo();
        final int now = a.getCounter(t, "rw_days");
        switch (cmd) {
            case "give" -> setDays(staff, s, now + 1);
            case "remove" -> setDays(staff, s, Math.max(0, now - 1));
            case "set" -> ui.askNumber(staff, "setta giorni", "SETTA GIORNI " + now, 0, 100000,
                    v -> later(() -> setDays(staff, s, (int) v)), () -> later(() -> show(staff, s)));
            case "reset" -> confirmDim(staff, s, "Azzerare la giornaliera di " + t.getName() + "?",
                    () -> resetDaily(staff, s));
            default -> later(() -> show(staff, s));
        }
    }

    private void setDays(Player staff, Session s, int days) {
        Player t = targetOf(staff, s);
        if (t == null) {
            return;
        }
        plugin.getAmmo().setCounter(t, "rw_days", Math.max(0, days));
        GuiMsg.send(plugin, staff, "&a" + t.getName() + ": giorni di giornaliera ora &f" + Math.max(0, days) + "&a.");
        show(staff, s);
    }

    private void resetDaily(Player staff, Session s) {
        Player t = targetOf(staff, s);
        if (t == null) {
            return;
        }
        AmmoManager a = plugin.getAmmo();
        for (String k : new String[] {"rw_days", "rw_dayclaim", "rw_daydate", "rw_daymin"}) {
            a.setCounter(t, k, 0);
        }
        GuiMsg.send(plugin, staff, "&a" + t.getName() + ": giornaliera azzerata.");
        show(staff, s);
    }

    // ------------------------------------------------------------------
    //  Tasto RESET (blu)
    // ------------------------------------------------------------------

    private void resetMenu(Player staff, Session s) {
        Player t = targetOf(staff, s);
        if (t == null) {
            return;
        }
        if (!authorize(staff, t.getUniqueId(), "reset")) {
            return;
        }
        switch (s.kind) {
            case "levels" -> {
                final String type = s.type;
                confirmDim(staff, s, "Azzerare " + Rewards.italianType(type) + " di " + t.getName() + "?",
                        () -> applyLevel(staff, s, type, 0));
            }
            case "daily" -> confirmDim(staff, s, "Azzerare la giornaliera di " + t.getName() + "?",
                    () -> resetDaily(staff, s));
            default -> confirmDim(staff, s, "Azzerare TUTTE le ricompense di " + t.getName() + "?", () -> {
                Player tt = targetOf(staff, s);
                if (tt == null) {
                    return;
                }
                rewards().resetAll(tt);
                GuiMsg.send(plugin, staff, "&a" + tt.getName() + ": tutte le ricompense azzerate.");
                show(staff, s);
            });
        }
    }

    // ------------------------------------------------------------------
    //  Exp del pass
    // ------------------------------------------------------------------

    private void runExp(Player staff, UUID id, String cmd, Runnable onBack) {
        Player t = Bukkit.getPlayer(id);
        if (t == null) {
            GuiMsg.send(plugin, staff, "&cIl giocatore non e' piu' online.");
            if (onBack != null) {
                onBack.run();
            }
            return;
        }
        if (!authorize(staff, id, verbOf(cmd))) {
            if (onBack != null) {
                onBack.run();
            }
            return;
        }
        AmmoManager a = plugin.getAmmo();
        final int now = a.getCounter(t, "rw_exp");
        final Runnable again = () -> later(() -> openExp(staff, Bukkit.getPlayer(id), onBack));
        switch (cmd) {
            case "give" -> ui.askNumber(staff, "aggiungi exp", "AGGIUNGI EXP " + now, 1, 1_000_000_000L,
                    v -> later(() -> setExp(staff, id, (long) expOf(id) + v, again)), again);
            case "remove" -> ui.askNumber(staff, "rimuovi exp", "RIMUOVI EXP " + now, 1, 1_000_000_000L,
                    v -> later(() -> setExp(staff, id, (long) expOf(id) - v, again)), again);
            case "set" -> ui.askNumber(staff, "setta exp", "SETTA EXP " + now, 0, 2_000_000_000L,
                    v -> later(() -> setExp(staff, id, v, again)), again);
            case "reset" -> ui.confirm(staff, "Azzerare gli exp di " + t.getName() + "?",
                    () -> setExp(staff, id, 0, again), again);
            default -> again.run();
        }
    }

    private int expOf(UUID id) {
        Player t = Bukkit.getPlayer(id);
        return t == null ? 0 : plugin.getAmmo().getCounter(t, "rw_exp");
    }

    private void setExp(Player staff, UUID id, long value, Runnable after) {
        Player t = Bukkit.getPlayer(id);
        if (t == null) {
            GuiMsg.send(plugin, staff, "&cIl giocatore non e' piu' online.");
            return;
        }
        int v = (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
        plugin.getAmmo().setCounter(t, "rw_exp", v);
        GuiMsg.send(plugin, staff, "&a" + t.getName() + ": exp ora &f" + v + "&a (livello pass &f"
                + rewards().reached(t, "pass") + "&a).");
        after.run();
    }

    // ------------------------------------------------------------------
    //  Permessi (stessa logica ruoli di /adminmania, gruppo profile)
    // ------------------------------------------------------------------

    private static String verbOf(String cmd) {
        return switch (cmd) {
            case "give" -> "get";
            case "remove" -> "remove";
            case "reset" -> "reset";
            default -> "edit";   // set, full
        };
    }

    private boolean authorize(Player p, UUID target, String verb) {
        AdminCommand a = plugin.getAdminCommand();
        if (a == null) {
            return p.isOp();
        }
        boolean ok = a.authorize(p, "profile", verb, target, 0);
        if (ok) {
            a.record(p, "profile." + verb + " ricompense/exp " + target);
        }
        return ok;
    }
}
