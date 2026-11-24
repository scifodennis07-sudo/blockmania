package it.blockmania;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * GUI principale del profilo di un player (/adminmania userprofile <nome>, oppure dal ticket).
 * Disegno nel titolo (immagine userprofile del pack + barre a tacche MUTE / WARN / BAN), testa = item Heads.head2d big
 * nello slot 9 (sborda sul riquadro 2x2), sotto gli slot invisibili (pane) con nome e lore per i click. Si aggiorna da sola a GUI aperta:
 * task ogni 20 tick + SanctionManager.onChange. Le tacche (titolo) si ridisegnano riaprendo l'inventario
 * (come Tickets.open), le lore si aggiornano al volo senza richiuderlo.
 * <p>
 * Misure: PACK_USERPROFILE.md (pixel dell'immagine; nel titolo si sottrae IMG come nei ticket).
 */
public final class UserProfileGui implements Listener {

    // ---------- pack ----------
    private static final int IMG = -22;
    private static final String FONT = "minecraft:userprofile";
    private static final String IMG_MAIN = "";
    private static final int SIZE = 54;

    // pagine 2/3 (font userprofile3: E117 = pagina 2, E118 = pagina 3)
    private static final String FONT3 = "minecraft:userprofile3";
    private static final String IMG_PAGE2 = "\uE117";
    private static final String IMG_PAGE3 = "\uE118";
    private static final int PAGES = 3;
    private static final int IMG_ADV = 179;    // avanzamento dichiarato nel titolo normale
    private static final int IMG_REAL = 186;   // avanzamento reale (sfondi di confirmOver / popup): le x vanno spostate di 7

    /** Frecce della testata (riga 0): pagina precedente / successiva. */
    private static final int[] S_PREV = {2};
    private static final int[] S_NEXT = {6};
    /** Bottoni 2x2 delle pagine 2/3: sinistra alto, sinistra basso, destra alto, destra basso. */
    private static final int[] S_BTN_LT = {36, 37, 38, 39};
    private static final int[] S_BTN_LB = {45, 46, 47, 48};
    private static final int[] S_BTN_RT = {41, 42, 43, 44};
    private static final int[] S_BTN_RB = {50, 51, 52, 53};

    /** Pixel 1x1 (+ spazio negativo) e 2x2 dei font tk<y> (come Tickets.drawFace / drawStars). */
    private static final String PX1 = "";
    private static final String PX2 = "";

    // barre: tacca interna (x0 della prima, passo, larghezza, y, numero)
    private static final int MUTE_N = 9;
    private static final int MUTE_X = 76;
    private static final int MUTE_STEP = 4;
    private static final int MUTE_W = 3;
    private static final int MUTE_Y = 57;
    private static final int WARN_N = 4;
    private static final int WARN_X = 137;
    private static final int WARN_STEP = 10;
    private static final int WARN_W = 6;
    private static final int WARN_Y = 57;
    private static final int BAN_N = 24;
    private static final int BAN_X = 77;
    private static final int BAN_STEP = 4;
    private static final int BAN_W = 3;
    private static final int BAN_Y = 75;
    private static final int TACCA_H = 4;

    private static final int C_RED = 0xE53935;
    private static final int C_YELLOW = 0xFFD83A;
    private static final int C_ORANGE = 0xFF9A1F;
    private static final int C_REDORANGE = 0xF05A24;
    private static final int C_PURPLE = 0xA23CDC;

    // slot (rawSlot): griglia 9x6, container = immagine - (7,15)
    private static final int[] S_CLOSE = {0, 1};
    private static final int[] S_INFO = {7, 8};
    private static final int[] S_HEAD = {9, 10, 18, 19};
    private static final int[] S_MUTE = {11, 12, 13, 14};
    private static final int[] S_WARN = {15, 16, 17};
    private static final int[] S_BAN = {20, 21, 22, 23, 24, 25, 26};
    private static final int[] S_UPG_A = {45, 46};
    private static final int[] S_UPG_B = {47, 48};
    private static final int[] S_RW_A = {50, 51};
    private static final int[] S_RW_B = {52, 53};
    private static final int S_POWER = 44;

    // barra stamina: fascia libera tra le barre sanzioni (finiscono a y=85) e i box oggetti (iniziano a y=101), pixel dell'immagine
    private static final int STA_X = 18;       // bordo sinistro (contorno)
    private static final int STA_W = 148;      // larghezza totale col contorno -> x 18..165
    private static final int STA_Y = 91;       // bordo alto (contorno)
    private static final int STA_H = 5;        // altezza totale col contorno -> y 91..95 (contorno 1 px + riempimento 3 px)
    private static final int C_STA_FILL = 0xFFD83A;
    private static final int C_STA_EDGE = 0x2B2B2B;
    private static final int C_STA_EMPTY = 0x555555;
    /** Riga 3 della griglia (slot 27..35 = y 86..103): copre tutta la fascia della stamina e nient'altro. */
    private static final int[] S_STAMINA = {27, 28, 29, 30, 31, 32, 33, 34, 35};

    /** Icone palla / oggetto della riga alta: slot, oggetto, nome, colore, ha le "sparate in totale". */
    private record Icon(int slot, PlayerCounters.Item item, String name, String color, boolean fired) {
    }

    private static final List<Icon> ICONS = List.of(
            new Icon(36, PlayerCounters.Item.STANDARDBALL, "standardball", "&9", true),
            new Icon(37, PlayerCounters.Item.DOUBLEBALL, "doubleball", "&a", true),
            new Icon(38, PlayerCounters.Item.GHOSTBALL, "ghostball", "&b", true),
            new Icon(39, PlayerCounters.Item.EXPLOSIONBALL, "explosionball", "&c", true),
            new Icon(41, PlayerCounters.Item.BLOCKSHELL, "blockshell", "&d", true),
            new Icon(42, PlayerCounters.Item.BLOCKCOIN, "blockcoin", "&6", false),
            new Icon(43, PlayerCounters.Item.COIN, "coin", "&e", false));

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    // ---------- dati ----------

    private record Snap(SanctionManager.Status mute, SanctionManager.Status ban, int warns, boolean banFromWarns, String warnNote) {
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final UUID target;
        private final int page;
        private String sig = "";
        private final Map<Integer, String> actions = new HashMap<>();

        Holder(UUID target, int page) {
            this.target = target;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final BlockMania plugin;
    /** Ultima pagina aperta da ogni staffer: back() ci riporta li'. */
    private final Map<UUID, Integer> lastPage = new HashMap<>();
    private BukkitTask task;

    public UserProfileGui(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Avvio / stop
    // ------------------------------------------------------------------

    public void start() {
        plugin.getSanctions().onChange(id -> refresh(id));
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Inventory top = p.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof Holder h) {
                    update(p, h);
                }
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
                p.closeInventory();
            }
        }
    }

    // ------------------------------------------------------------------
    //  API
    // ------------------------------------------------------------------

    /** Apre il profilo di target allo staffer (anche se target e' offline). */
    public void open(Player staff, UUID target) {
        open(staff, target, 0);
    }

    /** Apre la pagina indicata (0 = principale, 1 = pagina 2, 2 = pagina 3). */
    public void open(Player staff, UUID target, int page) {
        if (!authorize(staff, target, "profile", "see", 0)) {
            return;
        }
        int pg = Math.max(0, Math.min(PAGES - 1, page));
        lastPage.put(staff.getUniqueId(), pg);
        Heads.whenReady(target, () -> refresh(target));
        openHolder(staff, build(staff, target, pg));
    }

    /**
     * Sfondo del profilo per i popup (da scurire come in UserProfilePowers.confirmOver / RuleDetailGui.drawDimmedBackground):
     * immagine della pagina, testa e barre sanzioni (e stamina in pagina 0), tutto con avanzamento reale 186, e il titolo
     * "PROFILO nome" nella barra in alto (lo scurisce il popup insieme al resto: i popup non scrivono un loro titolo). Non scurisce.
     */
    public void drawDimmedBackground(GuiTitle g, UUID target, int page) {
        scene(g, target, snap(target), Math.max(0, Math.min(PAGES - 1, page)), true);
        UserProfilePowers.topTitleDim(plugin, g, "profilo " + UserProfilePowers.nameOf(target));
        UserProfilePowers.invVariant(g, UserProfilePowers.INV_LOW);   // userprofile*: inventario 1 px piu' in basso
    }

    /** Aggiorna le GUI aperte su questo target (sanzione cambiata o scaduta). */
    public void refresh(UUID target) {
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> refresh(target));
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h && h.target.equals(target)) {
                update(p, h);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Permessi (stessa logica ruoli di /adminmania)
    // ------------------------------------------------------------------

    private boolean authorize(Player p, UUID target, String group, String verb, long amount) {
        AdminCommand a = plugin.getAdminCommand();
        if (a == null) {
            return p.isOp();
        }
        return a.authorize(p, group, verb, target, amount);
    }

    private void audit(Player p, String text) {
        AdminCommand a = plugin.getAdminCommand();
        if (a != null) {
            a.record(p, text);
        }
    }

    private boolean isStaff(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online == null) {
            return Bukkit.getOfflinePlayer(id).isOp();
        }
        if (online.isOp()) {
            return true;
        }
        ConfigurationSection roles = plugin.getConfig().getConfigurationSection("adminmania.roles");
        if (roles == null) {
            return false;
        }
        for (String r : roles.getKeys(false)) {
            if (online.hasPermission("blockmania.role." + r)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Stato
    // ------------------------------------------------------------------

    private Snap snap(UUID target) {
        SanctionManager sm = plugin.getSanctions();
        SanctionManager.Status none = new SanctionManager.Status(false, 0, 0, 0);
        SanctionManager.Status mute = sm.mute(target);
        SanctionManager.Status ban = sm.ban(target);
        String note = sm.warnNote(target);
        return new Snap(mute == null ? none : mute, ban == null ? none : ban, Math.max(0, Math.min(WARN_N, sm.warns(target))),
                sm.banFromWarns(target), note == null ? "" : note);
    }

    /** Quante tacche sono piene: tutte all'inizio, poi scompaiono (da destra) col tempo che passa. */
    private static int notches(SanctionManager.Status st, int n) {
        if (st == null || !st.active()) {
            return 0;
        }
        if (st.endMs() <= 0 || st.totalMs() <= 0) {
            return n;   // permanente / senza scadenza
        }
        long left = st.endMs() - System.currentTimeMillis();
        if (left <= 0) {
            return 0;
        }
        double frac = Math.min(1.0, left / (double) st.totalMs());
        return Math.max(1, Math.min(n, (int) Math.ceil(frac * n)));
    }

    private static int warnColor(int warns, boolean banFromWarns) {
        return switch (warns) {
            case 0 -> C_YELLOW;
            case 1 -> C_YELLOW;
            case 2 -> C_ORANGE;
            case 3 -> C_REDORANGE;
            default -> banFromWarns ? C_PURPLE : C_RED;
        };
    }

    private String sig(UUID target, Snap s) {
        return (Bukkit.getPlayer(target) != null ? "O" : "o")
                + notches(s.mute(), MUTE_N) + "/" + notches(s.ban(), BAN_N) + "/" + s.warns() + (s.banFromWarns() ? "P" : "n")
                + "/S" + staminaPercent(target);   // la barra stamina si ridisegna quando cambia la percentuale
    }

    /** Stamina del target in % (0-100), oppure -1 se e' offline (barra grigia vuota). */
    private int staminaPercent(UUID target) {
        Player tp = Bukkit.getPlayer(target);
        Power pw = plugin.getPower();
        if (tp == null || pw == null) {
            return -1;
        }
        return (int) Math.round(Math.max(0.0, Math.min(1.0, pw.stamina(tp))) * 100.0);
    }

    // ------------------------------------------------------------------
    //  Titolo (disegno)
    // ------------------------------------------------------------------

    /** Font unico per i pixel (pxall): un carattere per riga y, 1x1 (U+F000+y) e 2x2 (U+F200+y), ognuno seguito dallo spazio -1. */
    private static final Key PXF = Key.key("minecraft", "pxall");

    private static String px1(int y, int n) {
        return pxRun((char) (0xF000 + y), n);
    }

    private static String pxRun(char c, int n) {
        StringBuilder b = new StringBuilder(n * 2);
        for (int i = 0; i < n; i++) {
            b.append(c).append('\uE002');
        }
        return b.toString();
    }

    private static Key tk(int y) {
        return Key.key("minecraft", "tk" + y);
    }

    private static Component glyph(String text, Key font, int rgb) {
        return Component.text(text).font(font).color(TextColor.color(rgb)).shadowColor(ShadowColor.none())
                .decoration(TextDecoration.ITALIC, false);
    }

    private static int shade(int rgb, double f) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        if (f >= 1.0) {
            double k = f - 1.0;
            r = (int) Math.min(255, r + (255 - r) * k);
            g = (int) Math.min(255, g + (255 - g) * k);
            b = (int) Math.min(255, b + (255 - b) * k);
        } else {
            r = (int) (r * f);
            g = (int) (g * f);
            b = (int) (b * f);
        }
        return (r << 16) | (g << 8) | b;
    }

    /** Tacche piene di una barra (le vuote sono gia' nell'immagine): riga alta piu' chiara, bassa piu' scura. */
    private void drawBar(GuiTitle g, int x0, int step, int w, int y, int count, int rgb, int ox) {
        for (int row = 0; row < TACCA_H; row++) {
            int c = row == 0 ? shade(rgb, 1.35) : row == TACCA_H - 1 ? shade(rgb, 0.75) : rgb;
            for (int k = 0; k < count; k++) {
                g.atComponent(x0 + k * step + ox, glyph(px1(y + row, w), PXF, c), w);
            }
        }
    }

    /** Barra stamina: contorno scuro, sfondo grigio e riempimento giallo proporzionale (riga alta chiara, bassa scura). */
    private void drawStamina(GuiTitle g, int percent, int ox) {
        int inX = STA_X + 1;
        int inW = STA_W - 2;
        int inH = STA_H - 2;
        int filled = percent < 0 ? 0 : (int) Math.round(inW * Math.min(100, percent) / 100.0);
        for (int row = 0; row < STA_H; row++) {
            int y = STA_Y + row;
            if (row == 0 || row == STA_H - 1) {
                g.atComponent(STA_X + ox, glyph(px1(y, STA_W), PXF, C_STA_EDGE), STA_W);
                continue;
            }
            g.atComponent(STA_X + ox, glyph(px1(y, 1), PXF, C_STA_EDGE), 1);
            int r = row - 1;
            int fc = r == 0 ? shade(C_STA_FILL, 1.35) : r == inH - 1 ? shade(C_STA_FILL, 0.75) : C_STA_FILL;
            if (filled > 0) {
                g.atComponent(inX + ox, glyph(px1(y, filled), PXF, fc), filled);
            }
            if (inW - filled > 0) {
                g.atComponent(inX + filled + ox, glyph(px1(y, inW - filled), PXF, C_STA_EMPTY), inW - filled);
            }
            g.atComponent(inX + inW + ox, glyph(px1(y, 1), PXF, C_STA_EDGE), 1);
        }
    }

    private static String imageOf(int page) {
        return page == 1 ? IMG_PAGE2 : page == 2 ? IMG_PAGE3 : IMG_MAIN;
    }

    /** Disegno comune (immagine, barre, stamina; la testa e' l'item nello slot 9). real = avanzamento 186 per gli sfondi dei popup. */
    private void scene(GuiTitle g, UUID target, Snap s, int page, boolean real) {
        final int ox = IMG + (real ? IMG_REAL - IMG_ADV : 0);
        g.at(-15, "&f" + imageOf(page), page == 0 ? FONT : FONT3, real ? IMG_REAL : IMG_ADV);
        drawBar(g, MUTE_X, MUTE_STEP, MUTE_W, MUTE_Y, notches(s.mute(), MUTE_N), C_RED, ox);
        drawBar(g, WARN_X, WARN_STEP, WARN_W, WARN_Y, s.warns(), warnColor(s.warns(), s.banFromWarns()), ox);
        drawBar(g, BAN_X, BAN_STEP, BAN_W, BAN_Y, notches(s.ban(), BAN_N), C_RED, ox);
        drawStamina(g, staminaPercent(target), ox);
        drawPageNumber(g, page + 1, real);
    }

    // numero pagina "n/3": overlay 256x256 del pack (font minecraft:pagnum, U+E130..E132 = pagnum-1..3.png)
    // ultima colonna opaca 103 -> advance reale 105 (= ultima + 2, come 184 -> 186 degli sfondi), dichiarato 98 (= reale - 7).
    // Con 104/97 il cursore restava 1 px indietro: titolo spostato di 1 px e, nei popup, inventario e popup 1 px a destra.
    private static final String FONT_PAGNUM = "minecraft:pagnum";
    private static final char PAGNUM_BASE = '\uE130';
    private static final int PAGNUM_REAL = 105;

    /**
     * Titolo normale: il cursore e' 7 px indietro rispetto al reale (immagine dichiarata 179), quindi x = -22 per cadere a -15;
     * l'avanzamento resta quello REALE (come StaffProfileGui.overlay), altrimenti i 7 px si tolgono due volte e il titolo
     * scritto dopo finisce 7 px a destra. Sfondo dei popup (cursore reale): x = -15.
     */
    private void drawPageNumber(GuiTitle g, int page, boolean real) {
        g.at(real ? -15 : -22, "&f" + (char) (PAGNUM_BASE + page - 1), FONT_PAGNUM, PAGNUM_REAL);
    }

    private Component title(UUID target, Snap s, int page) {
        GuiTitle g = new GuiTitle(plugin);
        scene(g, target, s, page, false);
        UserProfilePowers.topTitle(plugin, g, "profilo " + UserProfilePowers.nameOf(target));
        return g.build();
    }

    // ------------------------------------------------------------------
    //  Costruzione e aggiornamento
    // ------------------------------------------------------------------

    private Holder build(Player staff, UUID target, int page) {
        Snap s = snap(target);
        Holder h = new Holder(target, page);
        h.sig = sig(target, s);
        h.inventory = Bukkit.createInventory(h, SIZE, title(target, s, page));
        fill(h, target, s);
        return h;
    }

    /** Riapre l'inventario con il nuovo titolo (pattern di Tickets.open: la GUI non "lampeggia" per il resto del gioco). */
    private void openHolder(Player p, Holder h) {
        p.openInventory(h.inventory);
    }

    private void update(Player p, Holder h) {
        Snap s = snap(h.target);
        String sg = sig(h.target, s);
        if (!sg.equals(h.sig)) {
            openHolder(p, build(p, h.target, h.page));
        } else {
            fill(h, h.target, s);   // solo lore: si aggiorna al volo senza richiudere
        }
    }

    private void set(Holder h, int slot, ItemStack stack, String action) {
        ItemStack old = h.inventory.getItem(slot);
        if (old == null || !old.isSimilar(stack)) {
            h.inventory.setItem(slot, stack);
        }
        if (action != null) {
            h.actions.put(slot, action);
        } else {
            h.actions.remove(slot);
        }
    }

    private void setAll(Holder h, int[] slots, ItemStack stack, String action) {
        for (int s : slots) {
            set(h, s, stack, action);
        }
    }

    private void fill(Holder h, UUID target, Snap s) {
        if (h.page > 0) {
            fillPage(h, target, s);
            return;
        }
        boolean online = Bukkit.getPlayer(target) != null;
        PlayerCounters pc = plugin.getCounters();

        setAll(h, S_CLOSE, pane("&cchiudi", List.of()), "close");
        setAll(h, S_INFO, pane("&6informazioni", List.of(
                "&7pagina: &f" + (h.page + 1) + "&8/&f" + PAGES,
                "",
                "&esinistro comandi &8| &edestro cronologia")), null);

        // testa
        setHead(h, target, online);

        // sanzioni
        long now = System.currentTimeMillis();
        setAll(h, S_MUTE, timedItem("mute", s.mute(), now, false, ""), "sanc:MUTE");
        setAll(h, S_BAN, timedItem("ban", s.ban(), now, s.banFromWarns(), ""), "sanc:BAN");
        setAll(h, S_WARN, warnItem(s), "sanc:WARN");

        // palle e oggetti
        for (Icon ic : ICONS) {
            set(h, ic.slot(), counterItem(ic, target, pc, online), "item:" + ic.item().name());
        }
        set(h, S_POWER, powerItem(target, pc, online), "item:" + PlayerCounters.Item.POWER_CHARGES.name());

        // stamina: click = comandi (set, refill, infinite, empty, normal)
        setAll(h, S_STAMINA, staminaItem(target), "stamina");
        fillArrows(h);

        // miniere, potenziamenti, ricompense, exp: funzionano anche offline (dati salvati, modifiche al prossimo accesso)
        List<String> openLore = online ? List.of("&eclicca per aprire") : List.of(OFF_EDIT, "", "&eclicca per aprire");
        setAll(h, S_UPG_A, pane("&6miniere", openLore), "mines");
        setAll(h, S_UPG_B, pane("&6potenziamenti", openLore), "upg");
        setAll(h, S_RW_A, pane("&dricompense", openLore), "rw");
        List<String> expLore = new ArrayList<>();
        if (plugin.getProfileRewards() != null) {
            expLore.addAll(plugin.getProfileRewards().expLore(target));
        }
        if (!online) {
            expLore.add(OFF_EDIT);
        }
        expLore.add("");
        expLore.add("&eclicca per i comandi");
        setAll(h, S_RW_B, pane("&5exp", expLore), "exp");
    }

    /** Riga per il player offline: l'azione funziona lo stesso, si applica al suo prossimo accesso (R-OFF). */
    private static final String OFF_EDIT = "&7modifica: &fal prossimo accesso";

    /** Frecce della testata: non cicliche (a sinistra della prima e a destra dell'ultima pagina non fanno nulla). */
    private void fillArrows(Holder h) {
        boolean hasPrev = h.page > 0;
        boolean hasNext = h.page < PAGES - 1;
        setAll(h, S_PREV, pane(hasPrev ? "&epagina precedente" : "&8pagina precedente", List.of("&7pagina: &f" + (h.page + 1) + "&8/&f" + PAGES)),
                hasPrev ? "pg:prev" : null);
        setAll(h, S_NEXT, pane(hasNext ? "&epagina successiva" : "&8pagina successiva", List.of("&7pagina: &f" + (h.page + 1) + "&8/&f" + PAGES)),
                hasNext ? "pg:next" : null);
    }

    /** Pagine 2 e 3: stessa testata (testa, barre, X, info) + 4 bottoni 2x2 al posto di stamina e oggetti. */
    private void fillPage(Holder h, UUID target, Snap s) {
        boolean online = Bukkit.getPlayer(target) != null;
        setAll(h, S_CLOSE, pane("&cchiudi", List.of()), "close");
        setAll(h, S_INFO, pane("&6informazioni", List.of(
                "&7pagina: &f" + (h.page + 1) + "&8/&f" + PAGES,
                "",
                "&esinistro comandi &8| &edestro cronologia")), null);
        setHead(h, target, online);
        long now = System.currentTimeMillis();
        setAll(h, S_MUTE, timedItem("mute", s.mute(), now, false, ""), "sanc:MUTE");
        setAll(h, S_BAN, timedItem("ban", s.ban(), now, s.banFromWarns(), ""), "sanc:BAN");
        setAll(h, S_WARN, warnItem(s), "sanc:WARN");
        setAll(h, S_STAMINA, staminaItem(target), "stamina");
        fillArrows(h);
        if (h.page == 1) {
            // resettare = eliminare: solo Q, poi la conferma
            setAll(h, S_BTN_LT, pane("&bresetta player", List.of("&7azzera: &ftutto tranne sanzioni", "", "&eQ per resettare")), "reset_player");
            setAll(h, S_BTN_LB, pane("&eresetta sanzioni", List.of("&7azzera: &fmute, ban, warn", "&7e anche: &fla cronologia", "", "&eQ per resettare")), "reset_sanc");
            setAll(h, S_BTN_RT, pane("&9cronologia movimenti", List.of("&7contiene: &fminiere, negozi, ticket...", "", "&eclicca per aprire")), "hist_moves");
            setAll(h, S_BTN_RB, pane("&6cronologia sanzioni", List.of("&7contiene: &fwarn, mute e ban", "", "&eclicca per aprire")), "hist_sanc");
        } else {
            setAll(h, S_BTN_LT, pane("&6cronologia acquisti", List.of("&eclicca per aprire")), "hist_buys");
            setAll(h, S_BTN_LB, pane("&bcambia ruolo", List.of("&7stato: &8in arrivo")), null);   // niente azione, niente chat
            setAll(h, S_BTN_RT, pane("&dpexa staff", List.of("&eclicca per scegliere il grado")), "pexa");
            setAll(h, S_BTN_RB, pane("&6aggiungi vip", List.of("&7stato: &8in arrivo")), null);
        }
    }

    private static List<String> linesOf(String a, String b) {
        List<String> l = new ArrayList<>();
        l.add(a);
        if (!b.isEmpty()) {
            l.add(b);
        }
        return l;
    }

    // ------------------------------------------------------------------
    //  Item (slot invisibili con nome e lore)
    // ------------------------------------------------------------------

    private ItemStack pane(String name, List<String> lore) {
        return pane(plugin.text(name), lore);
    }

    private ItemStack pane(Component name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        meta.displayName(name);
        if (lore != null && !lore.isEmpty()) {
            meta.lore(loreOf(lore));
        }
        LoreWrap.fix(meta);   // lore a capo, nome non troppo lungo
        stack.setItemMeta(meta);
        return stack;
    }

    private List<Component> loreOf(List<String> lore) {
        List<Component> out = new ArrayList<>();
        for (String raw : lore) {
            if (raw == null || raw.isBlank()) {
                out.add(plugin.color(" "));
            } else {
                out.add(plugin.text(raw));
            }
        }
        return out;
    }

    private static String nameOf(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        String n = op.getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    private long playMinutes(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return plugin.getAmmo().getCounter(online, "rw_play");
        }
        try {
            return Bukkit.getOfflinePlayer(id).getStatistic(Statistic.PLAY_ONE_MINUTE) / 1200L;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** Riquadro testa: slot 9 = testa 2D grande (sborda sugli altri 3), 10/18/19 = pane invisibili, stesso nome e lore. */
    private void setHead(Holder h, UUID target, boolean online) {
        List<String> lore = headLore(target, online);
        Component name = plugin.color("&f" + nameOf(target));
        set(h, S_HEAD[0], Heads.head2d(target, true, name, loreOf(lore)), "head");
        ItemStack p = pane(name, lore);
        for (int i = 1; i < S_HEAD.length; i++) {
            set(h, S_HEAD[i], p, "head");
        }
    }

    private List<String> headLore(UUID target, boolean online) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(target);
        List<String> lore = new ArrayList<>();
        if (plugin.getPlayerIds() != null) {
            lore.add("&7id utente: &f" + plugin.getPlayerIds().userId(target));
            String sid = plugin.getPlayerIds().staffId(target);
            if (sid != null) {
                lore.add("&7id staffer: &f" + sid);
            }
        }
        if (online) {
            lore.add("&7stato: &aonline");
        } else {
            long seen = op.getLastSeen();
            lore.add("&7stato: &8offline");
            if (seen > 0) {
                lore.add("&7visto: &f" + DATE.format(Instant.ofEpochMilli(seen)));
            }
        }
        long mins = playMinutes(target);
        lore.add("&7playtime: &f" + (mins < 0 ? "n/d" : minutes(mins)));
        long login = op.getLastLogin();
        lore.add("&7ultimo login: &f" + (login > 0 ? DATE.format(Instant.ofEpochMilli(login)) : "mai"));
        if (isStaff(target)) {
            lore.add("");
            lore.add("&7staff: &asì");
            lore.add("");
            lore.add("&edestro per lo staffprofile");
        }
        return lore;
    }

    private ItemStack timedItem(String label, SanctionManager.Status st, long now, boolean fromWarns, String extra) {
        List<String> lore = new ArrayList<>();
        String color = fromWarns && st.active() ? "&5" : st.active() ? "&c" : "&7";
        if (st.active()) {
            lore.add("&7stato: " + color + "attivo");
            if (st.endMs() <= 0 || st.totalMs() <= 0) {
                lore.add("&7mancano: &fpermanente");
            } else {
                lore.add("&7mancano: &f" + dur(Math.max(0, st.endMs() - now)));
                lore.add("&7durata: &f" + dur(st.totalMs()));
            }
            if (fromWarns) {
                lore.add("&7causa: &54 warn");
            }
        } else {
            lore.add("&7stato: &anon sanzionato");
        }
        lore.add("");
        lore.add("&esinistro comandi &8| &edestro storico");
        return pane(color + label, lore);
    }

    private ItemStack warnItem(Snap s) {
        List<String> lore = new ArrayList<>();
        int w = s.warns();
        lore.add("&7warn: &f" + w + "&8/&f" + WARN_N);
        if (s.banFromWarns()) {
            lore.add("&7ban: &5per i 4 warn");
        }
        if (!s.warnNote().isBlank()) {
            lore.add("");
            for (String part : wrap(s.warnNote(), LoreWrap.LIMIT)) {
                lore.add("&d" + part);
            }
        }
        lore.add("");
        lore.add("&esinistro comandi &8| &edestro storico");
        String color = s.banFromWarns() && w >= WARN_N ? "&5" : w == 0 ? "&7" : w == 1 ? "&e" : w == 2 ? "&6" : "&c";
        return pane(color + "warn", lore);
    }

    private ItemStack counterItem(Icon ic, UUID target, PlayerCounters pc, boolean online) {
        List<String> lore = new ArrayList<>();
        lore.add("&7disponibili: &f" + amount(pc, target, ic.item()));
        if (ic.fired()) {
            lore.add("&7sparate: &f" + pc.totalFired(target, ic.item()));
        }
        if (!online) {
            lore.add(OFF_EDIT);   // i comandi funzionano anche offline (coda al login)
        }
        lore.add("");
        lore.add("&eclicca per i comandi");
        return pane(ic.color() + ic.name(), lore);
    }

    private ItemStack staminaItem(UUID target) {
        int pct = staminaPercent(target);
        Player tp = Bukkit.getPlayer(target);
        Power pw = plugin.getPower();
        List<String> lore = new ArrayList<>();
        if (pct >= 0 && tp != null && pw != null) {
            lore.add("&7livello: &f" + pct + "%");
            lore.add("&7modalità: &f" + pw.mode(tp));
        } else {
            lore.add(OFF_EDIT);   // la stamina sta nel player: offline si modifica al suo prossimo accesso
        }
        lore.add("");
        lore.add("&eclicca per i comandi");
        return pane("&estamina", lore);
    }

    private ItemStack powerItem(UUID target, PlayerCounters pc, boolean online) {
        int charges = pc.value(target, PlayerCounters.Item.POWER_CHARGES);
        List<String> lore = new ArrayList<>();
        lore.add("&7attivo: " + (charges > 0 ? "&asì" : "&cno") + " &8(&f" + amount(pc, target, PlayerCounters.Item.POWER_CHARGES) + "&8)");
        lore.add("&7utilizzi: &f" + amount(pc, target, PlayerCounters.Item.POWER_USES));
        lore.add("&7sparati: &f" + pc.totalFired(target, PlayerCounters.Item.POWER_CHARGES));
        if (!online) {
            lore.add(OFF_EDIT);
        }
        lore.add("");
        lore.add("&eclicca per i comandi");
        return pane("&6colpo potenziato", lore);
    }

    private static String amount(PlayerCounters pc, UUID target, PlayerCounters.Item it) {
        int v = pc.value(target, it);
        int max = pc.max(target, it);
        return max == Integer.MAX_VALUE ? String.valueOf(v) : v + "&8/&f" + max;
    }

    // ------------------------------------------------------------------
    //  Formati
    // ------------------------------------------------------------------

    private static String dur(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        if (d > 0) {
            return d + "g " + h + "h";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        if (m > 0) {
            return m + "m " + sec + "s";
        }
        return sec + "s";
    }

    private static String minutes(long m) {
        long d = m / 1440;
        long h = (m % 1440) / 60;
        long mm = m % 60;
        if (d > 0) {
            return d + "g " + h + "h " + mm + "m";
        }
        return h + "h " + mm + "m";
    }

    private static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split(" ")) {
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

    // ------------------------------------------------------------------
    //  Click
    // ------------------------------------------------------------------

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        String action = h.actions.get(event.getRawSlot());
        if (action == null) {
            return;
        }
        org.bukkit.event.inventory.ClickType ct = event.getClick();
        boolean drop = ct == org.bukkit.event.inventory.ClickType.DROP || ct == org.bukkit.event.inventory.ClickType.CONTROL_DROP;
        // i reset (= eliminazioni) solo con Q; tutto il resto non risponde a Q
        if (action.startsWith("reset_") != drop) {
            return;
        }
        plugin.guiClick(p);
        final boolean right = event.isRightClick();
        final UUID target = h.target;
        // tutto al tick dopo: aprire o chiudere inventari dentro l'evento click lascia la gui in stato incerto
        final int page = h.page;
        Bukkit.getScheduler().runTask(plugin, () -> handle(p, target, action, right, page));
    }

    private void handle(Player p, UUID target, String action, boolean right, int page) {
        if (!p.isOnline()) {
            return;
        }
        if (action.equals("close")) {
            p.closeInventory();
        } else if (action.equals("pg:prev")) {
            open(p, target, Math.max(0, page - 1));
        } else if (action.equals("pg:next")) {
            open(p, target, Math.min(PAGES - 1, page + 1));
        } else if (action.startsWith("reset_") || action.startsWith("hist_")
                || action.equals("chg_role") || action.equals("pexa") || action.equals("vip")) {
            pageAction(p, target, action, page);
        } else if (action.equals("head")) {
            if (right && isStaff(target)) {
                plugin.getStaffProfile().open(p, target);
            }
        } else if (action.startsWith("sanc:")) {
            SanctionManager.Kind kind = SanctionManager.Kind.valueOf(action.substring(5));
            if (right) {
                openHistory(p, target, kind);
            } else {
                sanctionMenu(p, target, kind, page);
            }
        } else if (action.equals("stamina")) {
            if (!right) {
                staminaMenu(p, target, page);
            }
        } else if (action.startsWith("item:")) {
            PlayerCounters.Item it = PlayerCounters.Item.valueOf(action.substring(5));
            if (right) {
                return;
            }
            itemMenu(p, target, it, page);
        } else if (action.equals("mines") || action.equals("upg") || action.equals("rw") || action.equals("exp")) {
            if (right) {
                return;
            }
            // anche offline (R-OFF): le GUI lavorano per UUID sui dati salvati (metodi di Giovanni)
            Runnable back = () -> back(p, target);
            switch (action) {
                case "mines" -> plugin.getProfileMines().open(p, target, back);
                case "upg" -> plugin.getProfileUpgrades().open(p, target, back);
                case "rw" -> plugin.getProfileRewards().open(p, target, back);
                default -> plugin.getProfileRewards().openExp(p, target, back, popupBg(target, page));
            }
        }
    }

    private void back(Player p, UUID target) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                open(p, target, lastPage.getOrDefault(p.getUniqueId(), 0));
            }
        });
    }

    // ------------------------------------------------------------------
    //  Azioni delle pagine 2/3
    // ------------------------------------------------------------------

    private void pageAction(Player p, UUID target, String action, int page) {
        Runnable again = () -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                open(p, target, page);
            }
        });
        java.util.function.Consumer<GuiTitle> bg = g -> drawDimmedBackground(g, target, page);
        switch (action) {
            case "reset_player" -> {
                if (!authorize(p, target, "profile", "reset", 0)) {
                    return;
                }
                plugin.getProfileUi().confirmOver(p, "Resettare il player " + nameOf(target) + "?", bg, () -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) {
                        return;
                    }
                    if (!authorize(p, target, "profile", "reset", 0)) {
                        again.run();
                        return;
                    }
                    // reset totale: mai in una task, quindi sotto admin non si puo' (fuori incarico)
                    if (plugin.getStaffTasks() != null
                            && !plugin.getStaffTasks().allows(p, "reset", "player_all", null, target, 0)) {
                        again.run();
                        return;
                    }
                    String summary = plugin.getPlayerReset().resetPlayer(target, p);
                    plugin.getMovements().record(target, MovementLog.Type.STAFF, "Reset del player da " + p.getName());
                    audit(p, "profile.reset player " + nameOf(target));
                    GuiMsg.send(plugin, p, "&aplayer " + nameOf(target) + " resettato" + (summary == null || summary.isBlank() ? "" : ": &7" + summary));
                    plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
                    again.run();
                }), again);
            }
            case "reset_sanc" -> {
                if (!authorize(p, target, "sanctions", "undo", 0)) {
                    return;
                }
                plugin.getProfileUi().confirmOver(p, "Resettare le sanzioni di " + nameOf(target) + "?", bg, () -> Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!p.isOnline()) {
                        return;
                    }
                    if (!authorize(p, target, "sanctions", "undo", 0)) {
                        again.run();
                        return;
                    }
                    plugin.getSanctions().resetAll(target);
                    plugin.getMovements().record(target, MovementLog.Type.STAFF, "Reset delle sanzioni da " + p.getName());
                    audit(p, "sanctions.undo resetall " + nameOf(target));
                    GuiMsg.send(plugin, p, "&asanzioni di " + nameOf(target) + " resettate");
                    plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
                    again.run();
                }), again);
            }
            case "hist_moves" -> {
                if (authorize(p, target, "profile", "see", 0)) {
                    plugin.getProfileLists().openMovements(p, target, bg, again);
                }
            }
            case "hist_sanc" -> {
                if (authorize(p, target, "sanctions", "see", 0)) {
                    plugin.getProfileLists().openSanctions(p, target, bg, again);
                }
            }
            case "hist_buys" -> {
                if (authorize(p, target, "profile", "see", 0)) {
                    plugin.getProfileLists().openPurchases(p, target, bg, again);
                }
            }
            case "pexa" -> plugin.getRoleGui().open(p, target, bg, again);
            default -> { }   // cambia ruolo / aggiungi vip: in arrivo, niente messaggi in chat
        }
    }

    // ------------------------------------------------------------------
    //  Stamina
    // ------------------------------------------------------------------

    /**
     * Sfondo dei popup comandi aperti dalla pagina indicata: la pagina (anche la 1) scurita con il titolo del profilo
     * (stesso sfondo dei popup di pageAction).
     */
    private java.util.function.Consumer<GuiTitle> popupBg(UUID target, int page) {
        return g -> drawDimmedBackground(g, target, page);
    }

    private void staminaMenu(Player p, UUID target, int page) {
        if (!authorize(p, target, "profile", "edit", 0)) {
            return;
        }
        // anche offline tutti i comandi (in coda, applicati al login); etichetta senza nick: il nick e' gia' nel titolo
        List<String> cmds = List.of("set", "full", "infinite", "reset");
        plugin.getProfileUi().openCommands(p, "stamina", cmds,
                cmd -> Bukkit.getScheduler().runTask(plugin, () -> staminaPick(p, target, cmd)),
                () -> back(p, target), false, null, popupBg(target, page));
    }

    private void staminaPick(Player p, UUID target, String cmd) {
        if (!p.isOnline()) {
            return;
        }
        if (!authorize(p, target, "profile", "edit", 0)) {
            back(p, target);
            return;
        }
        UserProfilePowers ui = plugin.getProfileUi();
        Runnable cancel = () -> back(p, target);
        if (cmd.equals("set")) {
            ui.askNumber(p, "stamina %", "SETTA STAMINA 0 100", 0, 100, v -> applyStamina(p, target, "set", (int) v), cancel);
        } else {
            ui.confirm(p, (cmd.equals("full") ? "Riempire la stamina di " : cmd.equals("infinite") ? "Rendere infinita la stamina di "
                    : "Svuotare la stamina di ") + nameOf(target) + "?", () -> applyStamina(p, target, cmd, 0), cancel);
        }
    }

    private void applyStamina(Player p, UUID target, String cmd, int percent) {
        if (!p.isOnline()) {
            return;
        }
        Player tp = Bukkit.getPlayer(target);
        Power pw = plugin.getPower();
        StaffTasks sto = plugin.getStaffTasks();
        if (tp == null && sto != null) {
            // offline: in coda, applicata al login (infinita = modalita' della stamina)
            if (!authorize(p, target, "profile", "edit", percent)) {
                back(p, target);
                return;
            }
            boolean inf = cmd.equals("infinite");
            String qv = cmd.equals("reset") ? "reset" : "set";
            String qw = inf ? "stamina_mode" : "stamina";
            String qs = inf ? "infinite" : null;
            long qa = cmd.equals("set") ? percent : cmd.equals("full") ? 100 : 0;
            if (!sto.allows(p, qv, qw, null, target, qa)) {
                back(p, target);
                return;
            }
            String err = sto.applyOrQueue(p, target, qv, qw, qs, qa);
            if (err == null) {
                sto.done(p, qv, qw, null, target, qa);
                audit(p, "profile.edit stamina " + cmd + " " + nameOf(target) + " (offline)");
                GuiMsg.send(plugin, p, "&astamina " + cmd + " in attesa del login di " + nameOf(target));
            } else {
                GuiMsg.send(plugin, p, "&c" + err);
            }
            back(p, target);
            return;
        }
        if (tp == null || pw == null) {
            GuiMsg.send(plugin, p, "&cStamina non disponibile (errore interno).");
            back(p, target);
            return;
        }
        if (!authorize(p, target, "profile", "edit", percent)) {
            back(p, target);
            return;
        }
        // task: sotto admin solo se e' un'azione del suo incarico (infinita non e' mai in una task)
        StaffTasks st = plugin.getStaffTasks();
        String tVerb = cmd.equals("reset") ? "reset" : "set";
        String tWhat = cmd.equals("infinite") ? "stamina_mode" : "stamina";
        long tAmount = cmd.equals("set") ? percent : cmd.equals("full") ? 100 : 0;
        if (st != null && !st.allows(p, tVerb, tWhat, null, target, tAmount)) {
            back(p, target);
            return;
        }
        switch (cmd) {
            case "set" -> pw.setPercent(tp, percent);
            case "full" -> pw.refill(tp);
            case "infinite" -> pw.setMode(tp, "infinite");
            default -> pw.setPercent(tp, 0);   // reset: stamina a 0
        }
        if (st != null) {
            st.done(p, tVerb, tWhat, null, target, tAmount);
        }
        audit(p, "profile.edit stamina " + cmd + " " + nameOf(target) + (cmd.equals("set") ? " " + percent : ""));
        GuiMsg.send(plugin, p, "&astamina " + cmd + " eseguito su " + nameOf(target));
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
        back(p, target);
    }

    // ------------------------------------------------------------------
    //  Sanzioni
    // ------------------------------------------------------------------

    private void openHistory(Player p, UUID target, SanctionManager.Kind kind) {
        if (!authorize(p, target, "sanctions", "see", 0)) {
            return;
        }
        plugin.getProfileHistory().open(p, target, kind, 0, () -> back(p, target));
    }

    private void sanctionMenu(Player p, UUID target, SanctionManager.Kind kind, int page) {
        if (!authorize(p, target, "sanctions", "see", 0)) {
            return;
        }
        // mute e ban: solo add/remove/reduce (il reset si fa dalla cronologia); warn: add/remove/reset (reset sempre ultimo)
        List<String> cmds = kind == SanctionManager.Kind.WARN ? List.of("add", "remove", "reset") : List.of("add", "remove", "reduce");
        String label = kind.name().toLowerCase(java.util.Locale.ROOT);   // senza nick (e' gia' nel titolo): mai tagliato
        plugin.getProfileUi().openCommands(p, label, cmds,
                cmd -> Bukkit.getScheduler().runTask(plugin, () -> sanctionPick(p, target, kind, cmd, page)),
                () -> back(p, target), true, () -> Bukkit.getScheduler().runTask(plugin, () -> openHistory(p, target, kind)),
                popupBg(target, page));
    }

    private void sanctionPick(Player p, UUID target, SanctionManager.Kind kind, String cmd, int page) {
        if (!p.isOnline()) {
            return;
        }
        String k = kind.name().toLowerCase(java.util.Locale.ROOT);
        String verb = cmd.equals("add") ? k : "undo";
        if (!authorize(p, target, "sanctions", verb, 0)) {
            back(p, target);
            return;
        }
        UserProfilePowers ui = plugin.getProfileUi();
        Runnable cancel = () -> back(p, target);
        switch (cmd) {
            case "add" -> askRuleCode(p, target, kind, page);
            case "reduce" -> {
                long max = plugin.getConfig().getLong("sanctions.max-minutes." + k, kind == SanctionManager.Kind.BAN ? 525600L : 43200L);
                ui.askNumber(p, "minuti da togliere", "RIDUCI " + k.toUpperCase(java.util.Locale.ROOT) + " MIN", 1, max, v -> doSanction(p, target, kind, cmd, v), cancel);
            }
            case "remove", "reset" -> ui.confirm(p, (cmd.equals("remove") ? "Togliere il " + k + " a " : "Azzerare i " + k + " di ")
                    + nameOf(target) + "?", () -> doSanction(p, target, kind, cmd, 0), cancel);
            default -> back(p, target);
        }
    }

    /** Flusso "add" con regola: codice -> messaggio staff -> conferma -> SanctionManager.applyRule. */
    private void askRuleCode(Player p, UUID target, SanctionManager.Kind kind, int page) {
        UserProfilePowers ui = plugin.getProfileUi();
        Runnable cancel = () -> back(p, target);
        ui.askText(p, "codice regola", "CODICE REGOLA", "", code -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            Rules.Rule rule = plugin.getRules().get(code == null ? "" : code.trim());
            if (rule == null || rule.type() == Rules.Type.REGOLAMENTO || !rule.type().name().equals(kind.name())) {
                GuiMsg.send(plugin, p, "&cregola inesistente o di altro tipo");
                sanctionMenu(p, target, kind, page); // torna al popup
                return;
            }
            ui.askText(p, "messaggio per il player", "MESSAGGIO STAFF", "null", msg -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) {
                    return;
                }
                String text = "Dare un " + kind.name().toLowerCase(java.util.Locale.ROOT) + " a " + nameOf(target) + " con la regola " + rule.code() + "?";
                Runnable yes = () -> Bukkit.getScheduler().runTask(plugin, () -> doRuleSanction(p, target, kind, rule, msg));
                // dopo le incudini il popup comandi non e' piu' aperto: sfondo = la pagina da cui si e' partiti
                ui.confirm(p, text, popupBg(target, page), yes, cancel);
            }), cancel);
        }), cancel);
    }

    /** Applica la regola scelta, con permessi, audit e messaggio allo staff. */
    private void doRuleSanction(Player p, UUID target, SanctionManager.Kind kind, Rules.Rule rule, String msg) {
        String k = kind.name().toLowerCase(java.util.Locale.ROOT);
        if (!authorize(p, target, "sanctions", k, 0)) {
            back(p, target);
            return;
        }
        boolean ok = plugin.getSanctions().applyRule(p, target, rule, msg);
        if (ok && plugin.getStaffTasks() != null) {
            // spunta la sanzione se e' nell'incarico (le sanzioni non si bloccano)
            plugin.getStaffTasks().done(p, k, null, rule.code(), target, 0);
        }
        if (ok) {
            audit(p, "sanctions." + k + " add " + nameOf(target) + " regola " + rule.code());
            GuiMsg.send(plugin, p, "&a" + k + " add eseguito su " + nameOf(target) + " (regola " + rule.code() + ")");
            plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
        } else {
            GuiMsg.send(plugin, p, "&cnessuna sanzione applicata (regola non applicabile o limite warn raggiunto)");
        }
        back(p, target);
    }

    private void doSanction(Player p, UUID target, SanctionManager.Kind kind, String cmd, long minutes) {
        String k = kind.name().toLowerCase(java.util.Locale.ROOT);
        String verb = cmd.equals("add") ? k : "undo";
        if (!authorize(p, target, "sanctions", verb, 0)) {
            back(p, target);
            return;
        }
        SanctionManager sm = plugin.getSanctions();
        UUID by = p.getUniqueId();
        long ms = minutes * 60_000L;
        switch (kind) {
            case MUTE -> {
                switch (cmd) {
                    case "add" -> sm.addMute(target, ms, by, "profilo staff");
                    case "reduce" -> sm.reduceMute(target, ms, by);
                    case "remove" -> sm.removeMute(target, by);
                    default -> sm.resetMute(target, by);
                }
            }
            case BAN -> {
                switch (cmd) {
                    case "add" -> sm.addBan(target, ms, by, "profilo staff");
                    case "reduce" -> sm.reduceBan(target, ms, by);
                    case "remove" -> sm.removeBan(target, by);
                    default -> sm.resetBan(target, by);
                }
            }
            default -> {
                switch (cmd) {
                    case "add" -> sm.addWarn(target, by, "profilo staff");
                    case "remove" -> sm.removeWarn(target, by);
                    default -> sm.resetWarns(target, by);
                }
            }
        }
        audit(p, "sanctions." + k + " " + cmd + " " + nameOf(target) + (minutes > 0 ? " " + minutes + "m" : ""));
        GuiMsg.send(plugin, p, "&a" + k + " " + cmd + " eseguito su " + nameOf(target));
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
        back(p, target);
    }

    // ------------------------------------------------------------------
    //  Palle e oggetti
    // ------------------------------------------------------------------

    private static String verbOf(String cmd) {
        return switch (cmd) {
            case "give" -> "get";
            case "remove" -> "remove";
            case "reset" -> "reset";
            default -> "edit";   // set, full
        };
    }

    private void itemMenu(Player p, UUID target, PlayerCounters.Item it, int page) {
        PlayerCounters pc = plugin.getCounters();
        // offline: si puo' modificare lo stesso, la modifica si applica al suo prossimo login (StaffTasks.applyOrQueue)
        if (!authorize(p, target, "profile", "see", 0)) {
            return;
        }
        List<String> cmds = new ArrayList<>(pc.commands(it));
        if (cmds.isEmpty()) {
            return;
        }
        if (it == PlayerCounters.Item.POWER_CHARGES && !cmds.contains("setuses")) {
            // pulsante per settare i colpi sparabili con un colpo potenziato; il reset resta sempre ultimo
            int ri = cmds.indexOf("reset");
            cmds.add(ri < 0 ? cmds.size() : ri, "setuses");
        }
        if (cmds.remove("reset")) {
            cmds.add("reset");
        }
        String label = popupLabelOf(it);
        plugin.getProfileUi().openCommands(p, label, cmds,
                cmd -> Bukkit.getScheduler().runTask(plugin, () -> itemPick(p, target, it, cmd)),
                () -> back(p, target), false, null, popupBg(target, page));
    }

    /** Etichetta corta per la barra del popup (deve stare in ~60 px). */
    private static String popupLabelOf(PlayerCounters.Item it) {
        return switch (it) {
            case POWER_CHARGES -> "potenziato";
            case POWER_USES -> "usi colpo";
            default -> it.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String actionWord(String cmd) {
        return switch (cmd) {
            case "give" -> "AGGIUNGI";
            case "remove" -> "RIMUOVI";
            case "set", "setuses" -> "SETTA";
            default -> cmd.toUpperCase(java.util.Locale.ROOT);
        };
    }

    private static String labelOf(PlayerCounters.Item it) {
        return switch (it) {
            case POWER_CHARGES -> "colpo potenziato (sbloccati)";
            case POWER_USES -> "colpo potenziato (utilizzi)";
            default -> it.name().toLowerCase(java.util.Locale.ROOT);
        };
    }

    private void itemPick(Player p, UUID target, PlayerCounters.Item it, String cmd) {
        if (!p.isOnline()) {
            return;
        }
        String verb = verbOf(cmd);
        if (!authorize(p, target, "profile", verb, 0)) {
            back(p, target);
            return;
        }
        UserProfilePowers ui = plugin.getProfileUi();
        PlayerCounters pc = plugin.getCounters();
        Runnable cancel = () -> back(p, target);
        if (cmd.equals("setuses")) {
            // colpi sparabili con un colpo potenziato = POWER_USES
            PlayerCounters.Item ui2 = PlayerCounters.Item.POWER_USES;
            String bar = "SETTA COLPI POT " + pc.value(target, ui2);
            ui.askNumber(p, "set " + labelOf(ui2), bar, 1, Integer.MAX_VALUE,
                    v -> applyItem(p, target, ui2, "set", (int) Math.min(Integer.MAX_VALUE, v)), cancel);
            return;
        }
        switch (cmd) {
            case "give", "remove", "set" -> {
                int max = pc.max(target, it);
                long hi = max == Integer.MAX_VALUE ? Integer.MAX_VALUE : max;
                AdminCommand a = plugin.getAdminCommand();
                if (a != null && !cmd.equals("set")) {
                    long cap = a.capFor(p, "profile", verb);
                    if (cap >= 0) {
                        hi = Math.min(hi, cap);
                    }
                }
                long lo = cmd.equals("set") ? 0 : 1;
                String shortName = popupLabelOf(it).toUpperCase(java.util.Locale.ROOT);
                String bar = actionWord(cmd) + " " + shortName + " " + pc.value(target, it)
                        + (max == Integer.MAX_VALUE ? "" : "/" + max);
                ui.askNumber(p, cmd + " " + labelOf(it), bar, lo, Math.max(lo, hi),
                        v -> applyItem(p, target, it, cmd, (int) Math.min(Integer.MAX_VALUE, v)), cancel);
            }
            case "full", "reset" -> ui.confirm(p, (cmd.equals("full") ? "Portare al massimo " : "Azzerare ") + labelOf(it).toLowerCase(java.util.Locale.ROOT)
                    + " di " + nameOf(target) + "?", () -> applyItem(p, target, it, cmd, 0), cancel);
            default -> back(p, target);
        }
    }

    private void applyItem(Player p, UUID target, PlayerCounters.Item it, String cmd, int n) {
        if (!p.isOnline()) {
            return;
        }
        if (!authorize(p, target, "profile", verbOf(cmd), n)) {
            back(p, target);
            return;
        }
        StaffTasks st = plugin.getStaffTasks();
        long tAmount = st == null ? n : st.itemAmountOf(target, it, cmd, n);
        if (st != null && !st.allowsItem(p, target, it, cmd, n)) {
            back(p, target);
            return;
        }
        String err;
        if (Bukkit.getPlayer(target) == null) {
            // player offline: modifica in coda, applicata al login
            String[] w = StaffTasks.itemWhatOf(it);
            String qv = cmd.equals("give") || cmd.equals("remove") || cmd.equals("reset") ? cmd : "set";
            // anche colpi per potenziato (setuses / power_uses): in coda come gli altri contatori
            String qw = cmd.equals("setuses") ? "power_uses" : w[0];
            err = st == null ? "coda offline non disponibile (errore interno)" : st.applyOrQueue(p, target, qv, qw, w[1], tAmount);
        } else {
            err = plugin.getCounters().apply(target, it, cmd, n, p);
        }
        if (err == null && st != null) {
            st.doneItem(p, target, it, cmd, tAmount);
        }
        if (err != null) {
            GuiMsg.send(plugin, p, "&c" + err);
            plugin.playConfiguredSound(p.getLocation(), "message-sounds.error");
        } else {
            audit(p, "profile." + verbOf(cmd) + " " + cmd + " " + it.name().toLowerCase(java.util.Locale.ROOT) + " " + nameOf(target) + (n > 0 ? " " + n : ""));
            GuiMsg.send(plugin, p, "&a" + cmd + " " + labelOf(it) + " eseguito su " + nameOf(target));
            plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
        }
        back(p, target);
    }
}
