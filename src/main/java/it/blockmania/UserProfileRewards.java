package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
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
 * Click: tipologia -> popup comandi [give=+1, remove=-1, set, full=maxa, reset=azzera];
 * giornaliera -> popup sui giorni; RESET (blu) -> solo con Q, conferma e azzera (tutto / la tipologia / la giornaliera).
 * Exp del pass: {@link #expLore(UUID)} per la lore dell'icona e {@link #openExp} per il popup give/remove/set/reset.
 * Funziona anche col player OFFLINE (R-OFF): si leggono i dati salvati e le modifiche restano in coda fino al login
 * (Rewards per UUID su OfflinePdc).
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

    /** Apre le ricompense di "target" (anche offline). onBack = cosa fare con la X (di solito riaprire il profilo). */
    public void open(Player staff, UUID target, Runnable onBack) {
        if (staff == null || target == null) {
            return;
        }
        Session s = new Session();
        s.target = target;
        s.back = onBack;
        show(staff, s);
    }

    public void open(Player staff, Player target, Runnable onBack) {
        open(staff, target == null ? null : target.getUniqueId(), onBack);
    }

    /** Righe di lore per l'icona exp del profilo (exp totale, livello pass, quanto manca, ritiri): offline i dati salvati. */
    public List<String> expLore(UUID target) {
        if (target == null) {
            return List.of();
        }
        return rewards().staffExpLines(target);
    }

    public List<String> expLore(Player target) {
        return expLore(target == null ? null : target.getUniqueId());
    }

    /** Popup comandi sull'exp del pass: aggiungi, rimuovi, setta, azzera. */
    public void openExp(Player staff, Player target, Runnable onBack) {
        openExp(staff, target == null ? null : target.getUniqueId(), onBack, null);
    }

    public void openExp(Player staff, Player target, Runnable onBack, java.util.function.Consumer<GuiTitle> background) {
        openExp(staff, target == null ? null : target.getUniqueId(), onBack, background);
    }

    /** Come sopra (anche offline); background = sfondo della GUI da cui si apre (con il suo titolo), scurito sotto il popup. */
    public void openExp(Player staff, UUID target, Runnable onBack, java.util.function.Consumer<GuiTitle> background) {
        if (staff == null || target == null) {
            return;
        }
        final UUID id = target;
        ui.openCommands(staff, "EXP", List.of("give", "remove", "set", "reset"),
                cmd -> later(() -> runExp(staff, id, cmd, onBack, background)),
                () -> {
                    if (onBack != null) {
                        later(onBack);
                    }
                }, false, null, background);
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
        LoreWrap.fix(meta);   // lore a capo, nome non troppo lungo
        stack.setItemMeta(meta);
        return stack;
    }

    private static String nameOf(UUID id) {
        return UserProfilePowers.nameOf(id);
    }

    /** Coda offline: la modifica vale al prossimo accesso del player (da dire nel messaggio allo staff). */
    private static String when(UUID id) {
        return Bukkit.getPlayer(id) != null ? "" : " &7(offline: al prossimo accesso)";
    }

    private void show(Player staff, Session s) {
        if (!staff.isOnline()) {
            return;
        }
        UUID target = s.target;
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
                inv.setItem(slot, pane("&aconferma", List.of("&7azzera: &ftutte le ricompense",
                        "&7di: &f" + nameOf(target), "", "&eclicca per confermare")));
                s.actions.put(slot, "yes");
            }
            for (int slot : SLOTS_NO) {
                inv.setItem(slot, pane("&cannulla", List.of("&eclicca per annullare")));
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
        boolean q = event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP;
        if (q != act.equals("reset")) {
            return;   // Q solo sul reset; il reset solo con Q
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
            // azzerare = eliminare: solo con Q (poi conferma)
            if (event.getClick() == ClickType.DROP || event.getClick() == ClickType.CONTROL_DROP) {
                later(() -> resetMenu(p, s));
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

    /**
     * Sfondo (a colori pieni, lo scurisce chi lo usa) della GUI ricompense da cui si arriva: stessa vista e pagina.
     * Per i popup comandi (livelli, giornaliera) al posto del profilo che UserProfilePowers disegnerebbe con null.
     */
    /** Il titolo della GUI ricompense ha l'immagine dichiarata 179 (reale 186): il suo cursore e' 7 px indietro rispetto al reale. */
    private static final int REAL_SHIFT = 7;

    private java.util.function.Consumer<GuiTitle> rewardsBg(UUID t, Session s) {
        final Rewards.StaffView v = rewards().staffViewBackground(t, s.kind, s.type, s.mainPage,
                s.kind.equals("main") ? s.mainPage : s.page, images());
        final Map<String, String> imgs = images();
        final String kind = s.kind;
        final String type = s.type;
        return g -> {
            // con il nome nella barra in alto: resta uguale, scurito. v.cursor() e' quello del titolo normale (immagine
            // dichiarata 179 = reale - 7): + 7 per lasciare il cursore REALE, come vogliono inventario e popup sopra
            g.atComponent(0, v.title(), v.cursor() + REAL_SHIFT);
            UserProfilePowers.invVariant(g, UserProfilePowers.INV_LOW);   // ricompense
            // i popup comandi a pagina intera coprono la barra in alto: il nome si riscrive sopra (scurito)
            UserProfilePowers.titleRedraw(g, tt -> {
                rewards().staffBarTitle(tt, t, kind, type, imgs);
            });
        };
    }

    /** Conferma che lascia dietro, scurita, la GUI ricompense da cui si arriva (stessa pagina, stesso titolo). */
    private void confirmDim(Player staff, Session s, String text, Runnable onYes) {
        UUID t = s.target;
        final Rewards.StaffView v = rewards().staffViewBackground(t, s.kind, s.type, s.mainPage,
                s.kind.equals("main") ? s.mainPage : s.page, images());
        // lo scurimento lo fa confirmOver; nella barra in alto resta (scurito) il nome della GUI ricompense
        ui.confirmOver(staff, text, g -> {
            g.atComponent(0, v.title(), v.cursor() + REAL_SHIFT);   // cursore reale (vedi rewardsBg)
            UserProfilePowers.invVariant(g, UserProfilePowers.INV_LOW);   // ricompense
        }, onYes, () -> later(() -> show(staff, s)));
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
        UUID t = s.target;
        ui.openCommands(staff, typeLabel(type), List.of("give", "remove", "set", "full", "reset"),
                cmd -> later(() -> runLevel(staff, s, type, cmd)),
                () -> later(() -> show(staff, s)), false, null, rewardsBg(t, s));
    }

    private void runLevel(Player staff, Session s, String type, String cmd) {
        UUID t = s.target;
        if (!authorize(staff, t, verbOf(cmd))) {
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
            // dal popup comandi: sfondo = ricompense + popup comandi scuriti (confirm li prende dal popup aperto)
            case "reset" -> ui.confirm(staff, "Azzerare " + Rewards.italianType(type) + " di " + nameOf(t) + "?",
                    rewardsBg(t, s), () -> applyLevel(staff, s, type, 0), () -> later(() -> show(staff, s)));
            case "set" -> ui.askNumber(staff, "setta livello " + Rewards.italianType(type),
                    "SETTA " + label + " " + now + "/" + max, 0, max,
                    v -> later(() -> applyLevel(staff, s, type, (int) v)),
                    () -> later(() -> show(staff, s)));
            default -> later(() -> show(staff, s));
        }
    }

    private void applyLevel(Player staff, Session s, String type, int level) {
        UUID t = s.target;
        StaffTasks st = plugin.getStaffTasks();
        if (st != null && !st.allowsLevel(staff, t, "ricompense", type, rewards().reached(t, type), level)) {
            show(staff, s);
            return;
        }
        rewards().setReached(t, type, level);
        if (st != null) {
            st.doneLevel(staff, t);
        }
        GuiMsg.send(plugin, staff, "&a" + nameOf(t) + ": " + Rewards.italianType(type) + " ora al livello &f"
                + rewards().reached(t, type) + "&a/&f" + rewards().staffMaxLevel(type) + "&a." + when(t));
        show(staff, s);
    }

    // ------------------------------------------------------------------
    //  Giornaliera (giorni riscattati)
    // ------------------------------------------------------------------

    private void dailyMenu(Player staff, Session s) {
        UUID t = s.target;
        ui.openCommands(staff, "GIORNALIERA", List.of("give", "remove", "set", "reset"),
                cmd -> later(() -> runDaily(staff, s, cmd)),
                () -> later(() -> show(staff, s)), false, null, rewardsBg(t, s));
    }

    private void runDaily(Player staff, Session s, String cmd) {
        UUID t = s.target;
        if (!authorize(staff, t, verbOf(cmd))) {
            later(() -> show(staff, s));
            return;
        }
        final int now = rewards().days(t);
        switch (cmd) {
            case "give" -> setDays(staff, s, now + 1);
            case "remove" -> setDays(staff, s, Math.max(0, now - 1));
            case "set" -> ui.askNumber(staff, "setta giorni", "SETTA GIORNI " + now, 0, 100000,
                    v -> later(() -> setDays(staff, s, (int) v)), () -> later(() -> show(staff, s)));
            // dal popup comandi: sfondo = ricompense + popup comandi scuriti (confirm li prende dal popup aperto)
            case "reset" -> ui.confirm(staff, "Azzerare la giornaliera di " + nameOf(t) + "?",
                    rewardsBg(t, s), () -> resetDaily(staff, s), () -> later(() -> show(staff, s)));
            default -> later(() -> show(staff, s));
        }
    }

    private void setDays(Player staff, Session s, int days) {
        UUID t = s.target;
        if (plugin.getStaffTasks() != null && !plugin.getStaffTasks().allows(staff, "set", "ricompense_daily", null, t, days)) {
            show(staff, s);
            return;
        }
        rewards().setDays(t, Math.max(0, days));
        GuiMsg.send(plugin, staff, "&a" + nameOf(t) + ": giorni di giornaliera ora &f" + Math.max(0, days) + "&a." + when(t));
        show(staff, s);
    }

    private void resetDaily(Player staff, Session s) {
        UUID t = s.target;
        if (plugin.getStaffTasks() != null && !plugin.getStaffTasks().allows(staff, "reset", "ricompense_daily", null, t, 0)) {
            show(staff, s);
            return;
        }
        rewards().resetDaily(t);
        GuiMsg.send(plugin, staff, "&a" + nameOf(t) + ": giornaliera azzerata." + when(t));
        show(staff, s);
    }

    // ------------------------------------------------------------------
    //  Tasto RESET (blu)
    // ------------------------------------------------------------------

    private void resetMenu(Player staff, Session s) {
        UUID t = s.target;
        if (!authorize(staff, t, "reset")) {
            return;
        }
        switch (s.kind) {
            case "levels" -> {
                final String type = s.type;
                confirmDim(staff, s, "Azzerare " + Rewards.italianType(type) + " di " + nameOf(t) + "?",
                        () -> applyLevel(staff, s, type, 0));
            }
            case "daily" -> confirmDim(staff, s, "Azzerare la giornaliera di " + nameOf(t) + "?",
                    () -> resetDaily(staff, s));
            default -> confirmDim(staff, s, "Azzerare tutte le ricompense di " + nameOf(t) + "?", () -> {
                if (plugin.getStaffTasks() != null
                        && !plugin.getStaffTasks().allows(staff, "reset", "ricompense_all", null, t, 0)) {
                    show(staff, s);
                    return;
                }
                rewards().resetAll(t);
                GuiMsg.send(plugin, staff, "&a" + nameOf(t) + ": tutte le ricompense azzerate." + when(t));
                show(staff, s);
            });
        }
    }

    // ------------------------------------------------------------------
    //  Exp del pass
    // ------------------------------------------------------------------

    private void runExp(Player staff, UUID id, String cmd, Runnable onBack, java.util.function.Consumer<GuiTitle> background) {
        if (!authorize(staff, id, verbOf(cmd))) {
            if (onBack != null) {
                onBack.run();
            }
            return;
        }
        final int now = expOf(id);
        final Runnable again = () -> later(() -> openExp(staff, id, onBack, background));
        switch (cmd) {
            case "give" -> ui.askNumber(staff, "aggiungi exp", "AGGIUNGI EXP " + now, 1, 1_000_000_000L,
                    v -> later(() -> setExp(staff, id, (long) expOf(id) + v, again)), again);
            case "remove" -> ui.askNumber(staff, "rimuovi exp", "RIMUOVI EXP " + now, 1, 1_000_000_000L,
                    v -> later(() -> setExp(staff, id, (long) expOf(id) - v, again)), again);
            case "set" -> ui.askNumber(staff, "setta exp", "SETTA EXP " + now, 0, 2_000_000_000L,
                    v -> later(() -> setExp(staff, id, v, again)), again);
            // dal popup comandi: sfondo = GUI di partenza + popup comandi scuriti (confirm li prende dal popup aperto)
            case "reset" -> ui.confirm(staff, "Azzerare gli exp di " + nameOf(id) + "?", background,
                    () -> setExp(staff, id, 0, again), again);
            default -> again.run();
        }
    }

    private int expOf(UUID id) {
        return rewards().exp(id);
    }

    private void setExp(Player staff, UUID id, long value, Runnable after) {
        int v = (int) Math.max(0, Math.min(Integer.MAX_VALUE, value));
        if (plugin.getStaffTasks() != null && !plugin.getStaffTasks().allows(staff, "set", "ricompense_exp", null, id, v)) {
            after.run();
            return;
        }
        rewards().setExp(id, v);
        GuiMsg.send(plugin, staff, "&a" + nameOf(id) + ": exp ora &f" + v + "&a (livello pass &f"
                + rewards().reached(id, "pass") + "&a)." + when(id));
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
