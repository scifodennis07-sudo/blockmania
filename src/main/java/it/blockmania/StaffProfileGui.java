package it.blockmania;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * GUI del profilo staff (/adminmania staffprofile <nome>, oppure click destro sulla testa dello userprofile di uno staffer).
 * Due pagine (frecce in alto come UserProfileGui, numero pagina 1/2 e 2/2). Pagina 1: scena disegnata nel titolo (immagine staffprofile del pack, testa 2D, tre barre di stelle con il valore
 * nel box VAL) e sotto gli slot invisibili con nome e lore. Orologio = info (lore), carta = carriera (popup lista),
 * mute / warn / ban / blacklist / ticket / assistenze vocali = conteggi (lore) e popup lista con le singole azioni.
 * Pagina 2: stessa parte alta, barra del grado staff (glifo colorato del ruolo), pezzetti dei ruoli aggiuntivi
 * (dev, texture, builder) e pannello azioni: depex, warn, aggiungi/modifica ruolo, sospendi, tutor, promuovi/degrada.
 * Warn/depex/sospendi usano le regole staff (/adminmania staffrules) e StaffDiscipline; click destro = elenco con reset.
 * Schema copiato da UserProfileGui (titolo con avanzamento dichiarato/reale, holder, azioni, refresh) e da ProfileLists (popup).
 * <p>
 * Misure: pixel dell'immagine staffprofile (256x256); nel titolo si sottrae IMG come in UserProfileGui.
 */
public final class StaffProfileGui implements Listener {

    // ---------- pack ----------
    private static final int IMG = -22;
    private static final String FONT = "minecraft:staffprofile";
    private static final String IMAGE = "";
    private static final int SIZE = 54;
    private static final int IMG_ADV = 179;    // avanzamento dichiarato nel titolo normale
    private static final int IMG_REAL = 186;   // avanzamento reale (sfondo dei popup): le x vanno spostate di 7

    /** Pixel 1x1 (+ spazio negativo) e 2x2 dei font tk<y> (come UserProfileGui / Tickets.drawFace). */
    private static final String PX1 = "";

    // testa: interno 34x34 a (15,51) = slot 9,10,18,19 -> item Heads.head2d big nello slot 9 (vedi setHead)

    // barre stelle: tacca interna (x0 della prima, passo, larghezza, y, numero), altezza 4
    private static final int BAR_STEP = 4;
    private static final int BAR_W = 3;
    private static final int BAR_H = 4;
    private static final int BAR30_X = 76;
    private static final int BAR30_Y = 57;
    private static final int BAR30_N = 4;
    private static final int BAR7_X = 137;
    private static final int BAR7_Y = 57;
    private static final int BAR7_N = 4;
    private static final int BARALL_X = 77;
    private static final int BARALL_Y = 75;
    private static final int BARALL_N = 19;
    private static final int C_STAR = 0xFFD83A;

    // box VAL: x del bordo sinistro dell'interno (18 px di larghezza) e y della prima riga del numero (sotto la scritta VAL)
    private static final int VAL_W = 18;
    private static final int VAL30_X = 97;
    private static final int VAL30_Y = 60;
    private static final int VAL7_X = 158;
    private static final int VAL7_Y = 60;
    private static final int VALALL_X = 158;
    private static final int VALALL_Y = 77;
    private static final int C_NUM = 0xFFFFFF;
    private static final int C_NUM_SHADOW = 0x2B2B2B;

    // mini font 3x5 per i numeri dei box VAL (cifre 3 px, punto e trattino, 1 px di spazio tra i caratteri)
    private static final String[][] MINI = {
            {"111", "101", "101", "101", "111"},   // 0
            {"010", "110", "010", "010", "111"},   // 1
            {"111", "001", "111", "100", "111"},   // 2
            {"111", "001", "111", "001", "111"},   // 3
            {"101", "101", "111", "001", "001"},   // 4
            {"111", "100", "111", "001", "111"},   // 5
            {"111", "100", "111", "101", "111"},   // 6
            {"111", "001", "001", "001", "001"},   // 7
            {"111", "101", "111", "101", "111"},   // 8
            {"111", "101", "111", "001", "111"}    // 9
    };
    private static final String[] MINI_DOT = {"0", "0", "0", "0", "1"};
    private static final String[] MINI_DASH = {"000", "000", "111", "000", "000"};

    // slot (rawSlot): griglia 9x6, container = immagine - (7,15)
    private static final int[] S_CLOSE = {0, 1};
    private static final int[] S_PREV = {2};
    private static final int[] S_NEXT = {6};
    private static final int[] S_INFO = {7, 8};
    private static final int[] S_HEAD = {9, 10, 18, 19};
    private static final int[] S_STAR30 = {11, 12, 13, 14};
    private static final int[] S_STAR7 = {15, 16, 17};
    private static final int[] S_STARALL = {20, 21, 22, 23, 24, 25, 26};
    private static final int[] S_CLOCK = {36};
    private static final int[] S_CAREER = {37};
    private static final int[] S_TICKET = {38};
    private static final int[] S_VOICE = {39};
    private static final int[] S_MUTE = {41};
    private static final int[] S_WARN = {42};
    private static final int[] S_BAN = {43};
    private static final int[] S_BLACKLIST = {44};

    // pagina 2: barra ruolo (riga 3, colonne 0..5), pezzetti ruoli aggiuntivi (riga 4, colonne 0..5), pannello icone 3x2
    private static final int PAGES = 2;
    private static final int[] S_ROLE = {27, 28, 29, 30, 31, 32};
    private static final int[] S_SUB = {36, 37, 38, 39, 40, 41};
    private static final int[] S_DEPEX = {33};
    private static final int[] S_DWARN = {34};
    private static final int[] S_SPECIAL = {35};
    private static final int[] S_SUSPEND = {42};
    private static final int[] S_TUTOR = {43};
    private static final int[] S_PROMOTE = {44};
    /** Ordine dei pezzetti dei ruoli aggiuntivi (come i glifi di StaffPage2Glyphs). */
    private static final List<String> SUB_ORDER = List.of("dev", "t_maker", "builder");

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ROME);

    // ---------- dati ----------

    /** Fotografia dei dati mostrati (letta dal thread principale). */
    private record Data(StaffStats.Info info, StaffStats.Ratings ratings, Map<StaffStats.Cat, StaffStats.Count> counts, int[] careerKinds) {
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
    private BukkitTask task;

    public StaffProfileGui(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Avvio / stop
    // ------------------------------------------------------------------

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Inventory top = p.getOpenInventory().getTopInventory();
                if (top.getHolder() instanceof Holder h) {
                    update(p, h);
                }
            }
        }, 40L, 40L);
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

    /** Apre il profilo staff di target allo staffer (target deve essere staff, anche se offline). */
    public void open(Player staff, UUID target) {
        open(staff, target, 0);
    }

    /** Come sopra, sulla pagina indicata (0 = pagina 1, 1 = pagina 2). */
    public void open(Player staff, UUID target, int page) {
        AdminCommand a = plugin.getAdminCommand();
        if (a == null ? !staff.isOp() : (a.level(staff) <= 0 && !staff.isOp())) {
            GuiMsg.send(plugin, staff, "&cSolo lo staff puo' aprire lo staffprofile.");
            return;
        }
        if (!authorize(staff, target)) {
            return;
        }
        if (!isStaff(target)) {
            GuiMsg.send(plugin, staff, "&c" + nameOf(target) + " non e' uno staffer.");
            return;
        }
        Heads.whenReady(target, () -> refresh(target));
        Holder h = build(target, Math.max(0, Math.min(PAGES - 1, page)));
        staff.openInventory(h.inventory);
    }

    /**
     * Sfondo dello staffprofile per i popup (da scurire come UserProfileGui.drawDimmedBackground):
     * immagine, testa e barre con avanzamento reale 186. Non scurisce.
     */
    public void drawDimmedBackground(GuiTitle g, UUID target) {
        drawDimmedBackground(g, target, 0);
    }

    /** Come sopra, per la pagina indicata (0 = pagina 1, 1 = pagina 2). */
    public void drawDimmedBackground(GuiTitle g, UUID target, int page) {
        scene(g, target, data(target), Math.max(0, Math.min(PAGES - 1, page)), true);
    }

    /** Aggiorna le GUI aperte su questo target (es. faccia caricata). */
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
    //  Permessi
    // ------------------------------------------------------------------

    private boolean authorize(Player p, UUID target) {
        AdminCommand a = plugin.getAdminCommand();
        if (a == null) {
            return p.isOp();
        }
        return a.authorize(p, "profile", "see", target, 0);
    }

    /** Vero se il player ha un grado staff (ruolo salvato, ruolo speciale, OP o permesso di ruolo). */
    private boolean isStaff(UUID id) {
        StaffRoles roles = plugin.getStaffRoles();
        if (roles != null && (roles.roleOf(id) != null || !roles.specialsOf(id).isEmpty())) {
            return true;
        }
        Player online = Bukkit.getPlayer(id);
        if (online == null) {
            return Bukkit.getOfflinePlayer(id).isOp();
        }
        if (online.isOp()) {
            return true;
        }
        ConfigurationSection cfg = plugin.getConfig().getConfigurationSection("adminmania.roles");
        if (cfg == null) {
            return false;
        }
        for (String r : cfg.getKeys(false)) {
            if (online.hasPermission("blockmania.role." + r)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    //  Dati
    // ------------------------------------------------------------------

    private Data data(UUID target) {
        StaffStats st = plugin.getStaffStats();
        Map<StaffStats.Cat, StaffStats.Count> counts = new EnumMap<>(StaffStats.Cat.class);
        int[] kinds = new int[4];   // PEX, CAMBIO, DEPEX, REPEX
        if (st == null) {
            for (StaffStats.Cat c : StaffStats.Cat.values()) {
                counts.put(c, new StaffStats.Count(0, 0, 0));
            }
            return new Data(new StaffStats.Info(0, -1, 0, ""), new StaffStats.Ratings(-1, 0, -1, 0, -1, 0), counts, kinds);
        }
        for (StaffStats.Cat c : StaffStats.Cat.values()) {
            counts.put(c, st.count(target, c));
        }
        for (StaffStats.CareerEntry e : st.career(target)) {
            int i = kindIndex(e.kind());
            if (i >= 0) {
                kinds[i]++;
            }
        }
        return new Data(st.info(target), st.ratings(target), counts, kinds);
    }

    private static int kindIndex(String kind) {
        if (kind == null) {
            return -1;
        }
        return switch (kind) {
            case "PEX" -> 0;
            case "CAMBIO" -> 1;
            case "DEPEX" -> 2;
            case "REPEX" -> 3;
            default -> -1;
        };
    }

    /** Tacche piene di una barra: proporzionali a media/5, almeno una se c'e' una media. */
    private static int fill(double avg, int n) {
        if (avg <= 0) {
            return 0;
        }
        int k = (int) Math.round(Math.min(5.0, avg) / 5.0 * n);
        return Math.max(1, Math.min(n, k));
    }

    /** Testo del box VAL: media con un decimale, "-" se nessuna valutazione. */
    private static String valText(double avg) {
        return avg < 0 ? "-" : String.format(Locale.ROOT, "%.1f", avg);
    }

    private String sig(UUID target, Data d, int page) {
        StaffStats.Ratings r = d.ratings();
        String base = fill(r.avg30(), BAR30_N) + "/" + fill(r.avg7(), BAR7_N) + "/" + fill(r.avgAll(), BARALL_N)
                + "/" + valText(r.avg30()) + "/" + valText(r.avg7()) + "/" + valText(r.avgAll());
        if (page == 1) {
            // pagina 2: nel titolo ci sono anche la barra del grado e i pezzetti dei ruoli aggiuntivi
            base += "/r" + roleIndex(target) + "/" + String.join(",", subsOf(target));
        }
        return base;
    }

    /** Ruoli aggiuntivi del target nell'ordine dei pezzetti (dev, t_maker, builder). */
    private List<String> subsOf(UUID target) {
        List<String> out = new ArrayList<>();
        StaffRoles roles = plugin.getStaffRoles();
        if (roles == null) {
            return out;
        }
        java.util.Set<String> sp = roles.specialsOf(target);
        for (String id : SUB_ORDER) {
            if (sp.contains(id)) {
                out.add(id);
            }
        }
        return out;
    }

    /** Indice in StaffRoles.ORDER del glifo della barra: grado della scala, altrimenti il primo ruolo speciale; -1 = nessuno. */
    private int roleIndex(UUID target) {
        StaffRoles roles = plugin.getStaffRoles();
        String r = roles == null ? null : roles.roleOf(target);
        if (r != null) {
            return StaffRoles.ORDER.indexOf(r);
        }
        List<String> subs = subsOf(target);
        return subs.isEmpty() ? -1 : StaffRoles.ORDER.indexOf(subs.get(0));
    }

    // ------------------------------------------------------------------
    //  Titolo (disegno)
    // ------------------------------------------------------------------

    /** Font unico per i pixel (pxall): un carattere per riga y, 1x1 (U+F000+y) e 2x2 (U+F200+y), ognuno seguito dallo spazio -1. */
    private static final Key PXF = Key.key("minecraft", "pxall");

    private static String px1(int y, int n) {
        return pxRun((char) (0xF000 + y), n);
    }

    private static String px2(int y, int n) {
        return pxRun((char) (0xF200 + y), n);
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

    /** Tacche piene di una barra (le vuote sono gia' nell'immagine): riga alta piu' chiara, bassa piu' scura (come UserProfileGui.drawBar). */
    private void drawBar(GuiTitle g, int x0, int y, int count, int rgb, int ox) {
        for (int row = 0; row < BAR_H; row++) {
            int c = row == 0 ? shade(rgb, 1.35) : row == BAR_H - 1 ? shade(rgb, 0.75) : rgb;
            for (int k = 0; k < count; k++) {
                g.atComponent(x0 + k * BAR_STEP + ox, glyph(px1(y + row, BAR_W), PXF, c), BAR_W);
            }
        }
    }

    /** Righe (5) del mini font 3x5 per un testo: caratteri separati da 1 px. */
    private static String[] miniRows(String text) {
        String[] rows = new String[5];
        for (int j = 0; j < 5; j++) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                String[] gl = ch == '.' ? MINI_DOT : ch == '-' ? MINI_DASH : (ch >= '0' && ch <= '9') ? MINI[ch - '0'] : null;
                if (gl == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('0');
                }
                sb.append(gl[j]);
            }
            rows[j] = sb.toString();
        }
        return rows;
    }

    /** Disegna un testo in mini font 3x5 con la sua ombra (+1,+1), centrato nel box interno largo VAL_W che inizia a boxX. */
    private void drawMini(GuiTitle g, String text, int boxX, int y, int ox) {
        String[] rows = miniRows(text);
        int w = rows[0].length();
        if (w == 0) {
            return;
        }
        int x = boxX + (VAL_W - w) / 2;
        for (int pass = 0; pass < 2; pass++) {
            int d = pass == 0 ? 1 : 0;
            int rgb = pass == 0 ? C_NUM_SHADOW : C_NUM;
            for (int j = 0; j < 5; j++) {
                String row = rows[j];
                int i = 0;
                while (i < row.length()) {
                    if (row.charAt(i) != '1') {
                        i++;
                        continue;
                    }
                    int run = 1;
                    while (i + run < row.length() && row.charAt(i + run) == '1') {
                        run++;
                    }
                    g.atComponent(x + i + d + ox, glyph(px1(y + j + d, run), PXF, rgb), run);
                    i += run;
                }
            }
        }
    }

    /** Disegno comune (immagine, testa, barre e numeri, numero pagina). real = avanzamento reale per lo sfondo dei popup. */
    private void scene(GuiTitle g, UUID target, Data d, int page, boolean real) {
        final int shift = real ? IMG_REAL - IMG_ADV : 0;
        final int ox = IMG + shift;
        if (page == 1) {
            // sfondo pagina 2: stesso schema della pagina 1 (a -15, avanzamento reale o dichiarato = reale - 7)
            g.at(-15, "&f" + StaffPage2Glyphs.BG, StaffPage2Glyphs.BG_FONT, real ? StaffPage2Glyphs.BG_REAL : StaffPage2Glyphs.BG_REAL - 7);
        } else {
            g.at(-15, "&f" + IMAGE, FONT, real ? IMG_REAL : IMG_ADV);
        }
        // la testa non e' piu' nel titolo: e' l'item Heads.head2d nello slot 9 (setHead)
        StaffStats.Ratings r = d.ratings();
        drawBar(g, BAR30_X, BAR30_Y, fill(r.avg30(), BAR30_N), C_STAR, ox);
        drawBar(g, BAR7_X, BAR7_Y, fill(r.avg7(), BAR7_N), C_STAR, ox);
        drawBar(g, BARALL_X, BARALL_Y, fill(r.avgAll(), BARALL_N), C_STAR, ox);
        drawMini(g, valText(r.avg30()), VAL30_X, VAL30_Y, ox);
        drawMini(g, valText(r.avg7()), VAL7_X, VAL7_Y, ox);
        drawMini(g, valText(r.avgAll()), VALALL_X, VALALL_Y, ox);
        if (page == 1) {
            drawRoleAndSubs(g, target, real);
        }
        var num = page == 1 ? StaffPage2Glyphs.PAGE_2_2 : StaffPage2Glyphs.PAGE_1_2;
        overlay(g, num.ch(), num.font(), num.real(), real);
    }

    /**
     * Overlay 256x256 del pack: titolo normale a -22, sfondo dei popup a -15, sempre con avanzamento reale.
     */
    private static void overlay(GuiTitle g, String ch, String font, int real, boolean realPath) {
        if (ch == null || ch.isEmpty()) {
            return;
        }
        if (realPath) {
            g.at(-15, "&f" + ch, font, real);
        } else {
            // a -22 (recupera i 7 px dello sfondo dichiarato 179) ma con avanzamento REALE: con quello dichiarato ogni
            // overlay spostava di 7 px a destra tutto quello che veniva dopo (pezzetti, numero pagina, nome).
            g.at(-15 - 7, "&f" + ch, font, real);
        }
    }

    /** Pagina 2: barra del grado (glifo colorato del ruolo) e pezzetti dei ruoli aggiuntivi (0 = nessuno, 1 intero, 2 meta', 3 terzi). */
    private void drawRoleAndSubs(GuiTitle g, UUID target, boolean real) {
        int ri = roleIndex(target);
        if (ri >= 0) {
            overlay(g, String.valueOf(StaffPage2Glyphs.roleChar(ri)), StaffPage2Glyphs.ROLE_FONT, StaffPage2Glyphs.roleReal(ri), real);
        }
        List<String> subs = subsOf(target);
        if (subs.isEmpty()) {
            var gl = StaffPage2Glyphs.subFull(null);
            overlay(g, gl.ch(), gl.font(), gl.real(), real);
        } else if (subs.size() == 1) {
            var gl = StaffPage2Glyphs.subFull(subs.get(0));
            overlay(g, gl.ch(), gl.font(), gl.real(), real);
        } else if (subs.size() == 2) {
            for (int i = 0; i < 2; i++) {
                var gl = StaffPage2Glyphs.subHalf(subs.get(i), i);
                overlay(g, gl.ch(), gl.font(), gl.real(), real);
            }
        } else {
            for (int i = 0; i < 3; i++) {
                var gl = StaffPage2Glyphs.subThird(subs.get(i), i);
                overlay(g, gl.ch(), gl.font(), gl.real(), real);
            }
        }
    }

    private Component title(UUID target, Data d, int page) {
        GuiTitle g = new GuiTitle(plugin);
        scene(g, target, d, page, false);
        UserProfilePowers.topTitle(plugin, g, "staff " + UserProfilePowers.nameOf(target));
        return g.build();
    }

    // ------------------------------------------------------------------
    //  Costruzione e aggiornamento
    // ------------------------------------------------------------------

    private Holder build(UUID target, int page) {
        Data d = data(target);
        Holder h = new Holder(target, page);
        h.sig = sig(target, d, page);
        h.inventory = Bukkit.createInventory(h, SIZE, title(target, d, page));
        fill(h, target, d);
        return h;
    }

    private void update(Player p, Holder h) {
        Data d = data(h.target);
        String sg = sig(h.target, d, h.page);
        if (!sg.equals(h.sig)) {
            p.openInventory(build(h.target, h.page).inventory);   // il titolo (barre, numeri, testa) cambia: inventario nuovo
        } else {
            fill(h, h.target, d);   // solo lore: si aggiorna al volo senza richiudere
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

    private void fill(Holder h, UUID target, Data d) {
        setAll(h, S_CLOSE, pane("&cchiudi", List.of()), "close");
        boolean hasPrev = h.page > 0;
        boolean hasNext = h.page < PAGES - 1;
        setAll(h, S_PREV, pane(hasPrev ? "&epagina precedente" : "&8pagina precedente", List.of("&7Pagina &f" + (h.page + 1) + "&8/&f" + PAGES)),
                hasPrev ? "pg:prev" : null);
        setAll(h, S_NEXT, pane(hasNext ? "&epagina successiva" : "&8pagina successiva", List.of("&7Pagina &f" + (h.page + 1) + "&8/&f" + PAGES)),
                hasNext ? "pg:next" : null);
        // riquadro testa: slot 9 = testa 2D grande (sborda su 10/18/19), gli altri pane invisibili con lo stesso nome/lore
        ItemStack headPane = headItem(target, d);
        set(h, S_HEAD[0], Heads.head2dLike(target, true, headPane), null);
        for (int i = 1; i < S_HEAD.length; i++) {
            set(h, S_HEAD[i], headPane, null);
        }
        StaffStats.Ratings r0 = d.ratings();
        setAll(h, S_STAR30, starItem("ultimi 30 giorni", r0.avg30(), r0.n30()), null);
        setAll(h, S_STAR7, starItem("ultimi 7 giorni", r0.avg7(), r0.n7()), null);
        setAll(h, S_STARALL, starItem("generale", r0.avgAll(), r0.nAll()), null);
        if (h.page == 1) {
            fillPage2(h, target, d);
            return;
        }
        setAll(h, S_INFO, pane("&6informazioni", List.of(
                "&7Profilo staff: attivita' e valutazioni.", "",
                "&fStelle: &7media dei voti dei ticket gestiti",
                "&7(ultimi 30 giorni, ultimi 7 giorni, generale).",
                "&fOrologio: &7primo accesso, playtime, grado.",
                "&fCarta: &7click = carriera staff.",
                "&fMute, warn, ban, blacklist, ticket, cuffie:",
                "&7click = elenco delle azioni.",
                "&fFreccia destra: &7pagina 2 (grado, ruoli, warn,", "&7depex, sospensioni, tutor).")), null);

        setAll(h, S_CLOCK, infoItem(d.info()), null);
        setAll(h, S_CAREER, careerItem(d.careerKinds()), "career");
        setAll(h, S_TICKET, catItem(StaffStats.Cat.TICKET, d, "&b"), "cat:TICKET");
        setAll(h, S_VOICE, catItem(StaffStats.Cat.VOICE, d, "&9"), "cat:VOICE");
        setAll(h, S_MUTE, catItem(StaffStats.Cat.MUTE, d, "&6"), "cat:MUTE");
        setAll(h, S_WARN, catItem(StaffStats.Cat.WARN, d, "&e"), "cat:WARN");
        setAll(h, S_BAN, catItem(StaffStats.Cat.BAN, d, "&c"), "cat:BAN");
        setAll(h, S_BLACKLIST, catItem(StaffStats.Cat.BLACKLIST, d, "&5"), "cat:BLACKLIST");
    }

    // ------------------------------------------------------------------
    //  Item (slot invisibili con nome e lore)
    // ------------------------------------------------------------------

    private ItemStack pane(String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        meta.displayName(plugin.text(name));
        if (lore != null && !lore.isEmpty()) {
            List<Component> out = new ArrayList<>();
            for (String raw : lore) {
                if (raw == null || raw.isBlank()) {
                    out.add(plugin.color(" "));
                } else {
                    out.add(plugin.text(raw));
                }
            }
            meta.lore(out);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack headItem(UUID target, Data d) {
        boolean online = Bukkit.getPlayer(target) != null;
        List<String> lore = new ArrayList<>();
        if (plugin.getPlayerIds() != null) {
            lore.add("&7id utente: &f" + plugin.getPlayerIds().userId(target));
            String sid = plugin.getPlayerIds().staffId(target);
            if (sid != null) {
                lore.add("&7id staffer: &f" + sid);
            }
        }
        lore.add("&7stato: " + (online ? "&aonline" : "&8offline"));
        String role = d.info().role();
        lore.add("&7grado: &f" + (role == null || role.isEmpty() ? "nessuno" : role));
        return pane("&f" + nameOf(target), lore);
    }

    private ItemStack infoItem(StaffStats.Info i) {
        List<String> lore = new ArrayList<>();
        lore.add("&7primo accesso: &f" + (i.firstJoin() > 0 ? date(i.firstJoin()) : "n/d"));
        lore.add("&7playtime totale: &f" + (i.playMs() < 0 ? "n/d" : minutes(i.playMs() / 60000L)));
        if (i.staffSince() > 0) {
            lore.add("&7staffer da: &f" + date(i.staffSince()) + " &8(" + dur(System.currentTimeMillis() - i.staffSince()) + ")");
        } else {
            lore.add("&7staffer da: &8mai");
        }
        lore.add("&7grado attuale: &f" + (i.role() == null || i.role().isEmpty() ? "nessuno" : i.role()));
        return pane("&binfo staffer", lore);
    }

    private ItemStack careerItem(int[] k) {
        List<String> lore = new ArrayList<>();
        lore.add("&7pex: &f" + k[0]);
        lore.add("&7cambi ruolo: &f" + k[1]);
        lore.add("&7depex: &f" + k[2]);
        lore.add("&7ripex: &f" + k[3]);
        lore.add("");
        lore.add("&eclick: apre la carriera");
        return pane("&6carriera staff", lore);
    }

    private ItemStack catItem(StaffStats.Cat cat, Data d, String color) {
        StaffStats.Count c = d.counts().get(cat);
        List<String> lore = new ArrayList<>();
        lore.add("&7fatti: &f" + c.done());
        lore.add("&7annullati: &f" + c.undone() + undoneHint(cat));
        lore.add("&7modificati: &f" + c.modified());
        if (cat == StaffStats.Cat.VOICE) {
            lore.add("");
            lore.add("&8funzione non ancora disponibile");
        }
        lore.add("");
        lore.add("&eclick: apre l'elenco");
        return pane(color + cat.label().toLowerCase(Locale.ROOT), lore);
    }

    private static String undoneHint(StaffStats.Cat cat) {
        return switch (cat) {
            case BAN -> " &8(sban)";
            case WARN -> " &8(unwarn)";
            case MUTE -> " &8(unmute)";
            case BLACKLIST -> " &8(tolti)";
            default -> "";
        };
    }

    private ItemStack starItem(String label, double avg, int n) {
        List<String> lore = new ArrayList<>();
        lore.add("&7valutazione media dei ticket gestiti");
        if (avg < 0) {
            lore.add("&7media: &8nessuna valutazione");
        } else {
            lore.add("&7media: &f" + valText(avg) + "&8/&f5");
        }
        lore.add("&7ticket valutati: &f" + n);
        return pane("&e" + label, lore);
    }

    private static String nameOf(UUID id) {
        String n = Bukkit.getOfflinePlayer(id).getName();
        return n == null ? id.toString().substring(0, 8) : n;
    }

    // ------------------------------------------------------------------
    //  Formati
    // ------------------------------------------------------------------

    private static String date(long ms) {
        return DATE.format(Instant.ofEpochMilli(ms));
    }

    private static String dur(long ms) {
        long s = Math.max(0, ms / 1000);
        long d = s / 86400;
        long h = (s % 86400) / 3600;
        long m = (s % 3600) / 60;
        if (d > 0) {
            return d + "g " + h + "h";
        }
        if (h > 0) {
            return h + "h " + m + "m";
        }
        return m + "m";
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

    // ------------------------------------------------------------------
    //  Click
    // ------------------------------------------------------------------

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder
                || event.getView().getTopInventory().getHolder() instanceof PopupHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof PopupHolder ph) {
            popupClick(event, ph);
            return;
        }
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
        plugin.guiClick(p);
        final UUID target = h.target;
        final boolean right = event.isRightClick();
        final int page = h.page;
        // tutto al tick dopo: aprire o chiudere inventari dentro l'evento click lascia la gui in stato incerto
        Bukkit.getScheduler().runTask(plugin, () -> {
            handle(p, target, action, right, page);
        });
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
        } else if (action.startsWith("d:")) {
            StaffDiscipline.Kind kind = StaffDiscipline.Kind.valueOf(action.substring(2));
            if (right) {
                openDiscipline(p, target, kind, 0);
            } else {
                disciplineLeft(p, target, kind);
            }
        } else if (action.equals("tutor")) {
            openTutor(p, target, 0);
        } else if (action.equals("special")) {
            openSpecial(p, target);
        } else if (action.equals("promote")) {
            openPromote(p, target);
        } else if (action.equals("career")) {
            openCareer(p, target);
        } else if (action.startsWith("cat:")) {
            openCategory(p, target, StaffStats.Cat.valueOf(action.substring(4)));
        }
    }

    // ------------------------------------------------------------------
    //  Popup lista (ProfileLists)
    // ------------------------------------------------------------------

    private void openCategory(Player p, UUID target, StaffStats.Cat cat) {
        StaffStats st = plugin.getStaffStats();
        if (st == null) {
            return;
        }
        List<ProfileLists.Filter> filters = new ArrayList<>();
        for (StaffStats.Act a : StaffStats.Act.values()) {
            filters.add(new ProfileLists.Filter(a.label(), a.name()));
        }
        String title = cat.label().toLowerCase(Locale.ROOT) + " di " + nameOf(target);
        plugin.getProfileLists().open(p, title, filters,
                key -> categoryRows(target, cat, key),
                g -> {
                    drawDimmedBackground(g, target);
                },
                () -> {
                    open(p, target);
                });
    }

    private List<ProfileLists.Row> categoryRows(UUID target, StaffStats.Cat cat, String key) {
        List<ProfileLists.Row> out = new ArrayList<>();
        StaffStats st = plugin.getStaffStats();
        if (st == null) {
            return out;
        }
        StaffStats.Act act = null;
        if (key != null && !key.isEmpty()) {
            try {
                act = StaffStats.Act.valueOf(key);
            } catch (IllegalArgumentException ex) {
                act = null;
            }
        }
        String icon = switch (cat) {
            case BAN -> "ban";
            case WARN -> "warn";
            case MUTE -> "mute";
            case TICKET -> "ticket";
            case BLACKLIST -> "sanction";
            default -> "other";
        };
        for (StaffStats.Entry e : st.entries(target, cat, act)) {
            String color = e.act() == StaffStats.Act.DONE ? "&a" : e.act() == StaffStats.Act.UNDONE ? "&c" : "&e";
            String singular = e.act() == StaffStats.Act.DONE ? "fatto" : e.act() == StaffStats.Act.UNDONE ? "annullato" : "modificato";
            List<String> lore = new ArrayList<>();
            lore.add("&7" + (cat == StaffStats.Cat.TICKET ? "ticket di" : "giocatore") + ": &f" + clean(e.who()));
            lore.add("&7azione: &f" + singular);
            lore.add("&7quando: &f" + date(e.ts()));
            for (String line : wrap(clean(e.text()), 34)) {
                lore.add("&f" + line);
            }
            out.add(row(color + cat.label().toLowerCase(Locale.ROOT) + " &7- " + color + singular + " &7- &f" + date(e.ts()), lore, icon));
        }
        if (out.isEmpty() && cat == StaffStats.Cat.VOICE) {
            // funzione non ancora esistente: una voce fissa al posto della lista vuota
            ItemStack none = new ItemStack(Material.BARRIER);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(plugin.text("&7Nessuna assistenza vocale"));
            meta.lore(List.of(plugin.text("&8Funzione non ancora disponibile.")));
            none.setItemMeta(meta);
            out.add(new ProfileLists.Row(none));
        }
        return out;
    }

    private void openCareer(Player p, UUID target) {
        StaffStats st = plugin.getStaffStats();
        if (st == null) {
            return;
        }
        List<ProfileLists.Filter> filters = List.of(
                new ProfileLists.Filter("Pex", "PEX"),
                new ProfileLists.Filter("Cambio", "CAMBIO"),
                new ProfileLists.Filter("Depex", "DEPEX"),
                new ProfileLists.Filter("Ripex", "REPEX"));
        plugin.getProfileLists().open(p, "carriera di " + nameOf(target), filters,
                key -> careerRows(target, key),
                g -> {
                    drawDimmedBackground(g, target);
                },
                () -> {
                    open(p, target);
                });
    }

    private List<ProfileLists.Row> careerRows(UUID target, String key) {
        List<ProfileLists.Row> out = new ArrayList<>();
        StaffStats st = plugin.getStaffStats();
        if (st == null) {
            return out;
        }
        for (StaffStats.CareerEntry e : st.career(target)) {
            if (key != null && !key.isEmpty() && !key.equals(e.kind())) {
                continue;
            }
            String color;
            String label;
            List<String> lore = new ArrayList<>();
            switch (e.kind() == null ? "" : e.kind()) {
                case "PEX" -> {
                    color = "&a";
                    label = "pex";
                    lore.add("&7ruolo: &f" + roleLabel(e.roleTo()));
                }
                case "CAMBIO" -> {
                    color = "&b";
                    label = "cambio ruolo";
                    lore.add("&7da: &f" + roleLabel(e.roleFrom()));
                    lore.add("&7a: &f" + roleLabel(e.roleTo()));
                }
                case "DEPEX" -> {
                    color = "&c";
                    label = "depex";
                    lore.add("&7ruolo tolto: &f" + roleLabel(e.roleFrom()));
                }
                case "REPEX" -> {
                    color = "&e";
                    label = "ripex";
                    lore.add("&7ruolo: &f" + roleLabel(e.roleTo()));
                }
                default -> {
                    color = "&7";
                    label = clean(e.kind()).toLowerCase(Locale.ROOT);
                    lore.add("&7ruolo: &f" + roleLabel(e.roleTo()));
                }
            }
            lore.add("&7da: &f" + (e.byName() == null || e.byName().isBlank() ? "sistema" : clean(e.byName())));
            lore.add("&7quando: &f" + date(e.ts()));
            out.add(row(color + label + " &7- &f" + date(e.ts()), lore, "staff"));
        }
        return out;
    }

    private String roleLabel(String id) {
        if (id == null || id.isEmpty()) {
            return "-";
        }
        StaffRoles roles = plugin.getStaffRoles();
        return roles == null ? id : roles.label(id);
    }

    /** Riga del popup: l'icona e' nel titolo (ProfileLists la disegna), l'item porta solo nome e lore. */
    private ProfileLists.Row row(String name, List<String> lore, String iconName) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text(name));
        List<Component> l = new ArrayList<>();
        for (String line : lore) {
            l.add(plugin.text(line));
        }
        meta.lore(l);
        stack.setItemMeta(meta);
        return new ProfileLists.Row(stack, ProfileLists.iconIndex(iconName));
    }

    // ------------------------------------------------------------------
    //  Pagina 2: item
    // ------------------------------------------------------------------

    private void fillPage2(Holder h, UUID target, Data d) {
        setAll(h, S_INFO, pane("&6informazioni", List.of(
                "&7Profilo staff: pagina &f2&8/&f" + PAGES + "&7.", "",
                "&fBarra: &7grado staff (colore del ruolo).",
                "&fPezzetti: &7ruoli aggiuntivi (dev, texture, builder).",
                "&fWarn, depex, sospendi: &7click sinistro = azione",
                "&7con una regola staff (/adminmania staffrules),",
                "&7click destro = elenco con reset e cancella voce.",
                "&fTutor: &7assegna o togli il tutor.",
                "&fRuolo / promuovi: &7popup con le frecce.")), null);

        StaffRoles roles = plugin.getStaffRoles();
        String role = roles == null ? null : roles.roleOf(target);
        List<String> subs = subsOf(target);
        StaffDiscipline disc = plugin.getStaffDiscipline();
        boolean suspended = disc != null && disc.isSuspended(target);

        // barra del grado
        List<String> rl = new ArrayList<>();
        rl.add("&7grado staff: &f" + (role == null ? "nessuno" : roleLabel(role)));
        if (role == null && !subs.isEmpty()) {
            rl.add("&8nessun grado della scala: si vede il ruolo aggiuntivo");
        }
        rl.add("&7ruoli aggiuntivi: &f" + (subs.isEmpty() ? "nessuno" : subsText(subs)));
        if (suspended) {
            rl.add("");
            rl.add("&csospeso &7fino al &f" + date(disc.suspendedUntil(target)));
        }
        setAll(h, S_ROLE, pane("&f" + (role == null ? (subs.isEmpty() ? "nessun grado" : roleLabel(subs.get(0))) : roleLabel(role)), rl), null);

        // pezzetti dei ruoli aggiuntivi: 0 = nessuno (riga intera grigia), 1 = riga intera, 2 = meta', 3 = terzi
        if (subs.isEmpty()) {
            setAll(h, S_SUB, pane("&7nessun ruolo aggiuntivo", List.of("&7Ruoli aggiuntivi: dev, texture, builder.", "",
                    "&eusa 'aggiungi/modifica ruolo' per darne uno")), null);
        } else {
            int n = subs.size();
            int per = S_SUB.length / n;
            for (int i = 0; i < S_SUB.length; i++) {
                String id = subs.get(Math.min(n - 1, i / per));
                set(h, S_SUB[i], pane("&b" + roleLabel(id), List.of("&7ruolo aggiuntivo di &f" + nameOf(target),
                        "&7ruoli aggiuntivi: &f" + subsText(subs), "",
                        "&eusa 'aggiungi/modifica ruolo' per cambiarli")), null);
            }
        }

        int nWarn = disc == null ? 0 : disc.entries(target, StaffDiscipline.Kind.WARN).size();
        int nDepex = disc == null ? 0 : disc.entries(target, StaffDiscipline.Kind.DEPEX).size();
        int nSusp = disc == null ? 0 : disc.entries(target, StaffDiscipline.Kind.SUSPEND).size();

        setAll(h, S_DEPEX, pane("&cdepex staff", List.of(
                "&7depex ricevuti: &f" + nDepex,
                "&7grado attuale: &f" + (role == null ? "nessuno" : roleLabel(role)),
                "&8toglie il grado staff, i ruoli aggiuntivi restano", "",
                "&esinistro: depexa con una regola staff",
                "&edestro: elenco dei depex (reset e cancella voce)")), "d:DEPEX");
        setAll(h, S_DWARN, pane("&ewarn staff", List.of(
                "&7warn ricevuti: &f" + nWarn, "",
                "&esinistro: warna con una regola staff",
                "&edestro: elenco dei warn (reset e cancella voce)")), "d:WARN");
        setAll(h, S_SPECIAL, pane("&baggiungi/modifica ruolo", List.of(
                "&7ruoli aggiuntivi: &f" + (subs.isEmpty() ? "nessuno" : subsText(subs)), "",
                "&esinistro: scegli dev, texture o builder con le frecce",
                "&8se ce l'ha gia', confermando lo togli")), "special");
        List<String> sl = new ArrayList<>();
        sl.add("&7sospensioni ricevute: &f" + nSusp);
        if (suspended) {
            sl.add("&7stato: &csospeso &7fino al &f" + date(disc.suspendedUntil(target)));
            sl.add("&8niente permessi staff finche' e' sospeso");
            sl.add("");
            sl.add("&esinistro: togli la sospensione");
        } else {
            sl.add("&7stato: &anon sospeso");
            sl.add("");
            sl.add("&esinistro: sospendi con una regola staff");
        }
        sl.add("&edestro: elenco delle sospensioni (reset e cancella voce)");
        setAll(h, S_SUSPEND, pane("&6sospendi staff", sl), "d:SUSPEND");

        UUID tutor = disc == null ? null : disc.tutorOf(target);
        List<UUID> pupils = disc == null ? List.of() : disc.pupilsOf(target);
        List<String> tl = new ArrayList<>();
        tl.add("&7tutor assegnato: " + (tutor == null ? "&8nessuno" : "&f" + nameOf(tutor)));
        tl.add("&8(un solo tutor per staffer)");
        tl.add("&7allievi: &f" + pupils.size());
        for (int i = 0; i < Math.min(5, pupils.size()); i++) {
            tl.add("&8- &f" + nameOf(pupils.get(i)));
        }
        if (pupils.size() > 5) {
            tl.add("&8... e altri " + (pupils.size() - 5));
        }
        tl.add("");
        tl.add(tutor == null ? "&eclicca per assegnare un tutor" : "&eclicca per cambiare o togliere il tutor");
        setAll(h, S_TUTOR, pane("&dassegna tutor", tl), "tutor");

        setAll(h, S_PROMOTE, pane("&apromuovi/degrada staff", List.of(
                "&7grado attuale: &f" + (role == null ? "nessuno" : roleLabel(role)), "",
                "&esinistro: scegli il grado con le frecce",
                "&8si parte dal grado attuale")), "promote");
    }

    private String subsText(List<String> subs) {
        List<String> names = new ArrayList<>();
        for (String id : subs) {
            names.add(roleLabel(id));
        }
        return String.join(", ", names);
    }

    // ------------------------------------------------------------------
    //  Pagina 2: permessi
    // ------------------------------------------------------------------

    private boolean authorize(Player p, UUID target, String group, String verb) {
        AdminCommand a = plugin.getAdminCommand();
        if (a == null) {
            return p.isOp();
        }
        return a.authorize(p, group, verb, target, 0);
    }

    private void audit(Player p, String text) {
        AdminCommand a = plugin.getAdminCommand();
        if (a != null) {
            a.record(p, text);
        }
    }

    /** Livello staff del target: grado salvato, permessi se online (anche OP); OP offline = massimo. */
    private int targetLevel(UUID target) {
        int lv = 0;
        StaffRoles roles = plugin.getStaffRoles();
        String r = roles == null ? null : roles.roleOf(target);
        if (r != null) {
            lv = StaffRoles.SCALE.indexOf(r) + 1;
        }
        AdminCommand a = plugin.getAdminCommand();
        Player tp = Bukkit.getPlayer(target);
        if (tp != null) {
            if (a != null) {
                lv = Math.max(lv, a.level(tp));
            } else if (tp.isOp()) {
                lv = Integer.MAX_VALUE;
            }
        } else if (Bukkit.getOfflinePlayer(target).isOp()) {
            lv = Integer.MAX_VALUE;
        }
        return lv;
    }

    /** Solo chi ha un grado piu' alto del target (OP sempre); mai su se stessi. */
    private boolean canAct(Player p, UUID target) {
        if (p.isOp()) {
            return true;
        }
        if (p.getUniqueId().equals(target)) {
            deny(p, "non puoi farlo su te stesso.");
            return false;
        }
        AdminCommand a = plugin.getAdminCommand();
        if (a == null || a.level(p) <= targetLevel(target)) {
            deny(p, "puoi agire solo su staffer con un grado piu' basso del tuo.");
            return false;
        }
        return true;
    }

    private void deny(Player p, String text) {
        GuiMsg.send(plugin, p, "&c" + text);
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.error");
    }

    /** Verbo di AdminCommand (gruppo sanctions) per il tipo: warn, ban (= depex), mute (= sospensione). */
    private static String verbOf(StaffDiscipline.Kind kind) {
        return switch (kind) {
            case WARN -> "warn";
            case DEPEX -> "ban";
            case SUSPEND -> "mute";
        };
    }

    /** Tipo della regola staff per il tipo di disciplina: WARN, BAN (= depex), MUTE (= sospensione). */
    private static Rules.Type ruleType(StaffDiscipline.Kind kind) {
        return switch (kind) {
            case WARN -> Rules.Type.WARN;
            case DEPEX -> Rules.Type.BAN;
            case SUSPEND -> Rules.Type.MUTE;
        };
    }

    private static String kindLabel(StaffDiscipline.Kind kind) {
        return switch (kind) {
            case WARN -> "warn";
            case DEPEX -> "depex";
            case SUSPEND -> "sospensione";
        };
    }

    private static String kindPlural(StaffDiscipline.Kind kind) {
        return switch (kind) {
            case WARN -> "warn";
            case DEPEX -> "depex";
            case SUSPEND -> "sospensioni";
        };
    }

    /** Sfondo dei popup della pagina 2. */
    private Consumer<GuiTitle> bg2(UUID target) {
        return g -> {
            drawDimmedBackground(g, target, 1);
        };
    }

    /** Riapre la pagina 2 al tick dopo. */
    private void back2(Player p, UUID target) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                open(p, target, 1);
            }
        });
    }

    // ------------------------------------------------------------------
    //  Pagina 2: warn / depex / sospensione (copia di UserProfileGui.askRuleCode / doRuleSanction)
    // ------------------------------------------------------------------

    private void disciplineLeft(Player p, UUID target, StaffDiscipline.Kind kind) {
        StaffDiscipline disc = plugin.getStaffDiscipline();
        if (disc == null || plugin.getStaffRules() == null) {
            GuiMsg.send(plugin, p, "&7non ancora disponibile.");
            return;
        }
        if (!authorize(p, target, "sanctions", kind == StaffDiscipline.Kind.SUSPEND && disc.isSuspended(target) ? "undo" : verbOf(kind))
                || !canAct(p, target)) {
            return;
        }
        if (kind == StaffDiscipline.Kind.SUSPEND && disc.isSuspended(target)) {
            plugin.getProfileUi().confirmOver(p, "Togliere la sospensione a " + nameOf(target) + "?", bg2(target), () -> {
                if (!p.isOnline()) {
                    return;
                }
                if (!authorize(p, target, "sanctions", "undo") || !canAct(p, target)) {
                    back2(p, target);
                    return;
                }
                if (disc.unsuspend(target, p)) {
                    audit(p, "staff.unsuspend " + nameOf(target));
                    GuiMsg.send(plugin, p, "&asospensione di " + nameOf(target) + " tolta");
                    plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
                } else {
                    GuiMsg.send(plugin, p, "&c" + nameOf(target) + " non e' sospeso");
                }
                back2(p, target);
            }, () -> {
                back2(p, target);
            });
            return;
        }
        if (kind == StaffDiscipline.Kind.DEPEX && (plugin.getStaffRoles() == null || plugin.getStaffRoles().roleOf(target) == null)) {
            deny(p, nameOf(target) + " non ha un grado staff da togliere.");
            return;
        }
        askStaffRule(p, target, kind);
    }

    /** Flusso con regola staff: codice -> messaggio -> conferma -> StaffDiscipline. */
    private void askStaffRule(Player p, UUID target, StaffDiscipline.Kind kind) {
        UserProfilePowers ui = plugin.getProfileUi();
        Runnable cancel = () -> {
            back2(p, target);
        };
        ui.askText(p, "codice regola staff (" + kindLabel(kind) + ")", "CODICE REGOLA", "", code -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            Rules rules = plugin.getStaffRules();
            Rules.Rule rule = rules == null ? null : rules.get(code == null ? "" : code.trim());
            if (rule == null || rule.type() != ruleType(kind)) {
                GuiMsg.send(plugin, p, "&cregola staff inesistente o di altro tipo (serve una regola " + kindLabel(kind)
                        + ", vedi /adminmania staffrules)");
                back2(p, target);
                return;
            }
            if (kind == StaffDiscipline.Kind.SUSPEND && rule.durationMs() <= 0) {
                GuiMsg.send(plugin, p, "&cla regola " + rule.code() + " non ha una durata valida per la sospensione");
                back2(p, target);
                return;
            }
            ui.askText(p, "messaggio per lo staffer", "MESSAGGIO STAFF", "null", msg -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) {
                    return;
                }
                String verb = switch (kind) {
                    case WARN -> "Warnare";
                    case DEPEX -> "Depexare";
                    case SUSPEND -> "Sospendere";
                };
                ui.confirmOver(p, verb + " " + nameOf(target) + " con la regola " + rule.code() + "?", bg2(target), () -> {
                    doDiscipline(p, target, kind, rule, msg);
                }, cancel);
            }), cancel);
        }), cancel);
    }

    /** Applica la regola staff scelta, con permessi, audit e messaggio allo staff. */
    private void doDiscipline(Player p, UUID target, StaffDiscipline.Kind kind, Rules.Rule rule, String msg) {
        if (!p.isOnline()) {
            return;
        }
        StaffDiscipline disc = plugin.getStaffDiscipline();
        if (disc == null || !authorize(p, target, "sanctions", verbOf(kind)) || !canAct(p, target)) {
            back2(p, target);
            return;
        }
        String m = msg == null || msg.trim().equalsIgnoreCase("null") ? "" : msg.trim();
        StaffDiscipline.Entry e = switch (kind) {
            case WARN -> disc.warn(p, target, rule, m);
            case DEPEX -> disc.depex(p, target, rule, m);
            case SUSPEND -> disc.suspend(p, target, rule, m);
        };
        if (e != null) {
            audit(p, "staff." + kindLabel(kind) + " " + nameOf(target) + " regola " + rule.code());
            GuiMsg.send(plugin, p, "&a" + kindLabel(kind) + " eseguito su " + nameOf(target) + " (regola " + rule.code() + ")");
            plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
        } else {
            GuiMsg.send(plugin, p, "&cnessuna azione eseguita (regola non applicabile)");
        }
        back2(p, target);
    }

    // ------------------------------------------------------------------
    //  Pagina 2: elenco warn / depex / sospensioni (come la cronologia sanzioni: reset totale e Q = cancella voce)
    // ------------------------------------------------------------------

    private void openDiscipline(Player p, UUID target, StaffDiscipline.Kind kind, int page) {
        StaffDiscipline disc = plugin.getStaffDiscipline();
        if (disc == null) {
            GuiMsg.send(plugin, p, "&7non ancora disponibile.");
            return;
        }
        Rules rules = plugin.getStaffRules();
        String icon = kind == StaffDiscipline.Kind.WARN ? "warn" : kind == StaffDiscipline.Kind.DEPEX ? "ban" : "mute";
        long now = System.currentTimeMillis();
        List<PRow> rows = new ArrayList<>();
        for (StaffDiscipline.Entry e : disc.entries(target, kind)) {
            boolean live = e.kind() == StaffDiscipline.Kind.SUSPEND && e.active() && e.until() > now;
            String color = kind == StaffDiscipline.Kind.WARN ? "&e" : kind == StaffDiscipline.Kind.DEPEX ? "&c" : "&6";
            List<String> lore = new ArrayList<>();
            Rules.Rule r = rules == null || e.ruleCode() == null ? null : rules.get(e.ruleCode());
            lore.add("&7regola: &f" + clean(e.ruleCode()) + (r == null || r.title() == null ? "" : " &8(" + clean(r.title()) + ")"));
            lore.add("&7da: &f" + (e.byName() == null || e.byName().isBlank() ? "sistema" : clean(e.byName())));
            lore.add("&7quando: &f" + date(e.ts()));
            if (e.kind() == StaffDiscipline.Kind.SUSPEND) {
                lore.add("&7durata: &f" + dur(e.durationMs()));
                if (live) {
                    lore.add("&7stato: &cin corso &7fino al &f" + date(e.until()));
                } else {
                    lore.add("&7stato: &afinita" + (e.endedAt() > 0 ? " &7il &f" + date(e.endedAt()) : ""));
                }
            }
            if (e.message() != null && !e.message().isBlank()) {
                lore.add("&7messaggio:");
                for (String line : wrap(clean(e.message()), 34)) {
                    lore.add("&f" + line);
                }
            }
            lore.add("");
            lore.add("&etasto Q per eliminare la voce");
            ItemStack it = new ItemStack(Material.PAPER);
            ItemMeta meta = it.getItemMeta();
            meta.displayName(plugin.text(color + kindLabel(kind) + " &7- &f" + date(e.ts()) + (live ? " &c(in corso)" : "")));
            List<Component> l = new ArrayList<>();
            for (String line : lore) {
                l.add(line.isEmpty() ? plugin.color(" ") : plugin.text(line));
            }
            meta.lore(l);
            it.setItemMeta(meta);
            rows.add(new PRow(it, ProfileLists.iconIndex(icon), null, e.id()));
        }
        Popup s = new Popup();
        s.staff = p;
        s.title = kindPlural(kind) + " di " + nameOf(target);
        s.bar = kindPlural(kind);
        s.rows = rows;
        s.reset = true;
        s.buttonName = "&breset " + kindPlural(kind);
        s.buttonLore = List.of("&7azzera tutto: " + kindPlural(kind) + " di " + nameOf(target), "&eclicca per resettare (chiede conferma)");
        s.background = bg2(target);
        s.onBack = () -> {
            back2(p, target);
        };
        s.onButton = () -> {
            resetDiscipline(p, target, kind);
        };
        s.onRow = (key, click, pg) -> {
            if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
                deleteDiscipline(p, target, kind, key, pg);
            }
        };
        showPopup(s, page);
    }

    private void resetDiscipline(Player p, UUID target, StaffDiscipline.Kind kind) {
        if (!authorize(p, target, "sanctions", "undo") || !canAct(p, target)) {
            return;
        }
        plugin.getProfileUi().confirmOver(p, "Resettare tutti i " + kindPlural(kind) + " di " + nameOf(target) + "?", bg2(target), () -> {
            if (!p.isOnline()) {
                return;
            }
            StaffDiscipline disc = plugin.getStaffDiscipline();
            if (disc != null && authorize(p, target, "sanctions", "undo") && canAct(p, target)) {
                int n = disc.resetAll(target, kind);
                audit(p, "staff.reset " + kindPlural(kind) + " " + nameOf(target) + " (" + n + ")");
                GuiMsg.send(plugin, p, "&a" + kindPlural(kind) + " di " + nameOf(target) + " resettati (" + n + " voci)");
                plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
            }
            openDiscipline(p, target, kind, 0);
        }, () -> {
            openDiscipline(p, target, kind, 0);
        });
    }

    private void deleteDiscipline(Player p, UUID target, StaffDiscipline.Kind kind, String entryId, int page) {
        if (!authorize(p, target, "sanctions", "undo") || !canAct(p, target)) {
            return;
        }
        plugin.getProfileUi().confirmOver(p, "Eliminare questa voce (" + kindLabel(kind) + ")?", bg2(target), () -> {
            if (!p.isOnline()) {
                return;
            }
            StaffDiscipline disc = plugin.getStaffDiscipline();
            if (disc != null && authorize(p, target, "sanctions", "undo") && canAct(p, target)) {
                if (disc.removeEntry(target, entryId)) {
                    audit(p, "staff.delete " + kindLabel(kind) + " " + nameOf(target));
                    GuiMsg.send(plugin, p, "&avoce eliminata");
                    plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
                }
            }
            openDiscipline(p, target, kind, page);
        }, () -> {
            openDiscipline(p, target, kind, page);
        });
    }

    // ------------------------------------------------------------------
    //  Pagina 2: tutor (lista degli staffer con le facce, come RulePopups.openList)
    // ------------------------------------------------------------------

    private void openTutor(Player p, UUID target, int page) {
        StaffDiscipline disc = plugin.getStaffDiscipline();
        if (disc == null) {
            GuiMsg.send(plugin, p, "&7non ancora disponibile.");
            return;
        }
        if (!canAct(p, target)) {
            return;
        }
        UUID current = disc.tutorOf(target);
        List<PRow> rows = new ArrayList<>();
        {
            ItemStack none = new ItemStack(Material.BARRIER);
            ItemMeta meta = none.getItemMeta();
            meta.displayName(plugin.text("&cnessun tutor"));
            meta.lore(List.of(plugin.text("&7Toglie il tutor a &f" + nameOf(target) + "&7."),
                    plugin.text("&7tutor attuale: &f" + (current == null ? "nessuno" : nameOf(current))),
                    plugin.color(" "), plugin.text("&eclicca per togliere")));
            none.setItemMeta(meta);
            rows.add(new PRow(none, -1, null, "none"));
        }
        for (UUID id : staffers(target)) {
            boolean cur = id.equals(current);
            StaffRoles roles = plugin.getStaffRoles();
            String r = roles == null ? null : roles.roleOf(id);
            List<String> lore = new ArrayList<>();
            lore.add("&7grado: &f" + (r == null ? "nessuno" : roleLabel(r)));
            lore.add("&7allievi: &f" + disc.pupilsOf(id).size());
            lore.add("&7stato: " + (Bukkit.getPlayer(id) != null ? "&aonline" : "&8offline"));
            lore.add("");
            lore.add(cur ? "&ae' gia' il tutor" : "&eclicca per sceglierlo come tutor");
            rows.add(new PRow(pane((cur ? "&a" : "&f") + nameOf(id) + (cur ? " &a(attuale)" : ""), lore), -1, id, id.toString()));
        }
        Popup s = new Popup();
        s.staff = p;
        s.title = "tutor di " + nameOf(target);
        s.bar = "scegli tutor";
        s.rows = rows;
        s.reset = false;
        s.buttonName = "&einformazioni";
        s.buttonLore = List.of("&7tutor attuale: &f" + (current == null ? "nessuno" : nameOf(current)),
                "&7clicca uno staffer per assegnarlo,", "&7oppure 'nessun tutor' per toglierlo.");
        s.background = bg2(target);
        s.onBack = () -> {
            back2(p, target);
        };
        s.onButton = null;
        s.onRow = (key, click, pg) -> {
            if (click == ClickType.LEFT || click == ClickType.RIGHT || click == ClickType.SHIFT_LEFT) {
                pickTutor(p, target, key.equals("none") ? null : UUID.fromString(key), pg);
            }
        };
        showPopup(s, page);
    }

    private void pickTutor(Player p, UUID target, UUID tutor, int page) {
        if (!canAct(p, target)) {
            return;
        }
        String text = tutor == null ? "Togliere il tutor a " + nameOf(target) + "?"
                : "Assegnare " + nameOf(tutor) + " come tutor di " + nameOf(target) + "?";
        plugin.getProfileUi().confirmOver(p, text, bg2(target), () -> {
            if (!p.isOnline()) {
                return;
            }
            StaffDiscipline disc = plugin.getStaffDiscipline();
            if (disc != null && canAct(p, target)) {
                disc.setTutor(target, tutor, p);
                audit(p, "staff.tutor " + nameOf(target) + " -> " + (tutor == null ? "nessuno" : nameOf(tutor)));
                GuiMsg.send(plugin, p, tutor == null ? "&atutor di " + nameOf(target) + " tolto"
                        : "&a" + nameOf(tutor) + " ora e' il tutor di " + nameOf(target));
                plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
            }
            back2(p, target);
        }, () -> {
            openTutor(p, target, page);
        });
    }

    /** Staffer conosciuti (roles.yml: gradi e ruoli speciali, piu' OP e staff online), escluso uno; dal grado piu' alto. */
    private List<UUID> staffers(UUID exclude) {
        LinkedHashSet<UUID> ids = new LinkedHashSet<>();
        File f = new File(plugin.getDataFolder(), "roles.yml");
        if (f.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
            for (String sec : List.of("roles", "special")) {
                ConfigurationSection cs = y.getConfigurationSection(sec);
                if (cs == null) {
                    continue;
                }
                for (String k : cs.getKeys(false)) {
                    try {
                        ids.add(UUID.fromString(k));
                    } catch (IllegalArgumentException ex) {
                        // chiave non valida: ignorata
                    }
                }
            }
        }
        for (OfflinePlayer op : Bukkit.getOperators()) {
            ids.add(op.getUniqueId());
        }
        for (Player on : Bukkit.getOnlinePlayers()) {
            ids.add(on.getUniqueId());
        }
        ids.remove(exclude);
        List<UUID> out = new ArrayList<>();
        Map<UUID, Integer> levels = new HashMap<>();
        for (UUID id : ids) {
            if (isStaff(id)) {
                out.add(id);
                levels.put(id, targetLevel(id));
            }
        }
        out.sort(Comparator.comparingInt((UUID id) -> -Math.min(Integer.MAX_VALUE - 1, levels.getOrDefault(id, 0)))
                .thenComparing((UUID id) -> nameOf(id).toLowerCase(Locale.ROOT)));
        return out;
    }

    // ------------------------------------------------------------------
    //  Pagina 2: ruoli (RoleGui con le frecce, come il pexa dello userprofile)
    // ------------------------------------------------------------------

    /** Aggiungi/modifica ruolo aggiuntivo: solo i ruoli speciali (toggle, conferma nel popup). */
    private void openSpecial(Player p, UUID target) {
        if (!canAct(p, target)) {
            return;
        }
        List<String> subs = subsOf(target);
        int start = subs.isEmpty() ? StaffRoles.SCALE.size() : StaffRoles.ORDER.indexOf(subs.get(0));
        plugin.getRoleGui().open(p, target, bg2(target), () -> {
            back2(p, target);
        }, StaffRoles.SCALE.size(), StaffRoles.ORDER.size(), start);
    }

    /** Promuovi/degrada: solo la scala, partendo dal grado attuale. */
    private void openPromote(Player p, UUID target) {
        if (!canAct(p, target)) {
            return;
        }
        StaffRoles roles = plugin.getStaffRoles();
        String r = roles == null ? null : roles.roleOf(target);
        int start = r == null ? 0 : Math.max(0, StaffRoles.SCALE.indexOf(r));
        plugin.getRoleGui().open(p, target, bg2(target), () -> {
            back2(p, target);
        }, 0, StaffRoles.SCALE.size(), start);
    }

    // ------------------------------------------------------------------
    //  Popup lista della pagina 2 (copia di ProfileLists / RulePopups: pannello 7x3 sopra la pagina scurita)
    // ------------------------------------------------------------------

    /** userprofile-28 (pulsante arancione col cestino in alto a destra = reset, font sp_popup) e userprofile-23 (pulsante arancione = info): solo pannello. */
    private static final String P_IMG_RESET = "\ue11b";
    private static final String P_FONT_RESET = "minecraft:sp_popup";
    private static final String P_IMG_INFO = "";
    private static final String P_FONT_INFO = "minecraft:userprofile2";
    private static final int P_IMG = -22;
    /** Avanzamento dichiarato del pannello (reale 169 - 7), come ProfileLists.ADV / RulePopups.DEF_ADV. */
    private static final int P_ADV = 162;
    private static final char P_ICON_BASE = '';
    private static final int P_ICON_ADV = 17;
    private static final int P_ICON_X0 = 15;
    private static final int P_ICON_STEP = 18;
    private static final char P_NAV = '';
    private static final char P_NAV_SH = '';
    private static final int P_NAV_CX = 96;
    private static final char P_BAR = '';
    private static final char P_BAR_SH = '';
    private static final String P_BAR_FONT = "minecraft:tiny3";
    private static final int P_BAR_CX = 95;
    private static final int P_BAR_MAX = 70;
    private static final int P_PER_PAGE = 21;
    private static final int[] P_X = {1, 2};
    private static final int[] P_BTN = {6, 7};
    private static final int P_PREV = 38;
    private static final int P_NEXT = 42;

    /** Riga: item (nome/lore), icona custom nel titolo (-1 = nessuna), testa 2D item del player (null = nessuna), chiave. */
    private record PRow(ItemStack item, int icon, UUID face, String key) {
    }

    private interface RowClick {
        void click(String key, ClickType click, int page);
    }

    private static final class PopupHolder implements InventoryHolder {
        private Inventory inventory;
        private Popup popup;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Popup {
        Player staff;
        String title;
        String bar;
        List<PRow> rows;
        boolean reset;
        String buttonName;
        List<String> buttonLore;
        Consumer<GuiTitle> background;
        Runnable onBack;
        Runnable onButton;
        RowClick onRow;
        int page;
        boolean refreshQueued;
        PopupHolder holder;
    }

    private static int pGridSlot(int i) {
        return 10 + (i / 7) * 9 + (i % 7);
    }

    private static int pPageCount(int n) {
        return Math.max(1, (n + P_PER_PAGE - 1) / P_PER_PAGE);
    }

    private void showPopup(Popup s, int page) {
        int pages = pPageCount(s.rows.size());
        s.page = Math.max(0, Math.min(page, pages - 1));
        PopupHolder h = new PopupHolder();
        h.popup = s;
        s.holder = h;
        h.inventory = Bukkit.createInventory(h, SIZE, popupTitle(s, pages));
        Inventory inv = h.inventory;
        String back = s.onBack != null ? "&cindietro" : "&cchiudi";
        for (int x : P_X) {
            inv.setItem(x, pane(back, List.of()));
        }
        for (int b : P_BTN) {
            inv.setItem(b, pane(s.buttonName, s.buttonLore));
        }
        inv.setItem(P_PREV, pane("&7pagina precedente", List.of()));
        inv.setItem(P_NEXT, pane("&7pagina successiva", List.of()));
        if (s.rows.isEmpty()) {
            inv.setItem(pGridSlot(10), pane("&7nessuna voce", List.of("&8La lista e' vuota.")));
        } else {
            for (int i = 0; i < P_PER_PAGE; i++) {
                int idx = s.page * P_PER_PAGE + i;
                if (idx >= s.rows.size()) {
                    break;
                }
                PRow r = s.rows.get(idx);
                inv.setItem(pGridSlot(i), popupRowItem(r));
            }
        }
        s.staff.openInventory(inv);
        requestFaces(s);
    }

    /** Item della voce: faccia = testa 2D piccola (item), icona = pane invisibile (icona nel titolo), altrimenti l'item. */
    private ItemStack popupRowItem(PRow r) {
        if (r.face() != null) {
            return Heads.head2dLike(r.face(), false, r.item());
        }
        return r.icon() >= 0 ? invisible(r.item()) : r.item().clone();
    }

    private Component popupTitle(Popup s, int pages) {
        GuiTitle g = new GuiTitle(plugin);
        if (s.background != null) {
            UserProfilePowers.drawDimmed(plugin, g, s.background);
        }
        g.at(-15, "&f" + (s.reset ? P_IMG_RESET : P_IMG_INFO), s.reset ? P_FONT_RESET : P_FONT_INFO, P_ADV);
        for (int i = 0; i < P_PER_PAGE; i++) {
            int idx = s.page * P_PER_PAGE + i;
            if (idx >= s.rows.size()) {
                break;
            }
            PRow r = s.rows.get(idx);
            // le facce sono item (Heads.head2d, popupRowItem): nel titolo solo le icone custom
            if (r.face() == null && r.icon() >= 0) {
                // icone custom come ProfileLists.drawIcons (font per riga della griglia)
                int slot = pGridSlot(i);
                Component glyph = Component.text(String.valueOf((char) (P_ICON_BASE + r.icon())))
                        .font(Key.key("minecraft", "ico_r" + (slot / 9)))
                        .color(TextColor.color(0xFFFFFF))
                        .shadowColor(ShadowColor.none())
                        .decoration(TextDecoration.ITALIC, false);
                g.atComponent(P_ICON_X0 + P_ICON_STEP * (slot % 9) + P_IMG, glyph, P_ICON_ADV);
            }
        }
        popupBar(g, s.bar);
        popupNav(g, s.page + 1, pages);
        UserProfilePowers.topTitle(plugin, g, s.title);
        return g.build();
    }

    /** Larghezza di un testo del font tiny3 (come ProfileLists.tinyAdvance). */
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

    /** Testo nella barra grigia in alto (copia di ProfileLists.barText). */
    private void popupBar(GuiTitle g, String raw) {
        StringBuilder safe = new StringBuilder();
        for (char ch : (raw == null ? "" : raw).toCharArray()) {
            safe.append(ch < 128 && Character.isLetterOrDigit(ch) ? ch : ' ');
        }
        String text = safe.toString().replaceAll(" +", " ").trim();
        while (tinyAdvance(text) > P_BAR_MAX && text.length() > 1) {
            text = text.substring(0, text.length() - 1).trim();
        }
        if (text.isEmpty()) {
            return;
        }
        int st = P_BAR_CX - Math.max(0, tinyAdvance(text) - 1) / 2;
        int off = 0;
        for (String w : text.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            int wa = tinyAdvance(w);
            g.at(st + off + 1 + P_IMG, GuiTitle.shadowCode(plugin) + letters(w, P_BAR_SH), P_BAR_FONT, wa);
            g.at(st + off + P_IMG, "&f" + letters(w, P_BAR), P_BAR_FONT, wa);
            off += wa + 4;
        }
    }

    /** Numero pagina tra le frecce (copia di ProfileLists.navText). */
    private void popupNav(GuiTitle g, int page, int pages) {
        String plain = page + "/" + pages;
        StringBuilder n = new StringBuilder();
        StringBuilder sh = new StringBuilder();
        for (char ch : plain.toCharArray()) {
            int o = ch == '/' ? 10 : ch - '0';
            n.append((char) (P_NAV + o));
            sh.append((char) (P_NAV_SH + o));
        }
        int adv = plain.length() * 6;
        int start = P_NAV_CX - (adv - 1) / 2;
        g.at(start + 1 + P_IMG, GuiTitle.shadowCode(plugin) + sh, "", adv);
        g.at(start + P_IMG, "&f" + n, "", adv);
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
        stack.setItemMeta(meta);
        return stack;
    }

    /** Skin delle teste della pagina non ancora note: a ogni profilo pronto aggiorna gli item (coalescendo), come RulePopups.requestFaces. */
    private void requestFaces(Popup s) {
        for (int i = 0; i < P_PER_PAGE; i++) {
            int idx = s.page * P_PER_PAGE + i;
            if (idx >= s.rows.size()) {
                break;
            }
            UUID f = s.rows.get(idx).face();
            if (f != null) {
                Heads.whenReady(f, () -> {
                    queuePopupRefresh(s);
                });
            }
        }
    }

    private void queuePopupRefresh(Popup s) {
        if (s.refreshQueued) {
            return;
        }
        s.refreshQueued = true;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            s.refreshQueued = false;
            Player p = s.staff;
            if (p == null || !p.isOnline() || p.getOpenInventory().getTopInventory().getHolder() != s.holder) {
                return;
            }
            // le teste sono item: si aggiornano al volo, senza riaprire l'inventario
            Inventory inv = s.holder.inventory;
            for (int i = 0; i < P_PER_PAGE; i++) {
                int idx = s.page * P_PER_PAGE + i;
                if (idx >= s.rows.size()) {
                    break;
                }
                PRow r = s.rows.get(idx);
                if (r.face() != null) {
                    inv.setItem(pGridSlot(i), popupRowItem(r));
                }
            }
        }, 5L);
    }

    private static boolean in(int[] arr, int slot) {
        for (int a : arr) {
            if (a == slot) {
                return true;
            }
        }
        return false;
    }

    private void popupClick(InventoryClickEvent event, PopupHolder ph) {
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        Popup s = ph.popup;
        int slot = event.getRawSlot();
        if (in(P_X, slot)) {
            plugin.guiClick(p);
            Runnable back = s.onBack;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (back != null) {
                    back.run();
                } else {
                    p.closeInventory();
                }
            });
            return;
        }
        if (in(P_BTN, slot)) {
            if (s.onButton != null) {
                plugin.guiClick(p);
                Runnable btn = s.onButton;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    btn.run();
                });
            }
            return;
        }
        if (slot == P_PREV || slot == P_NEXT) {
            plugin.guiClick(p);
            int pages = pPageCount(s.rows.size());
            int np = Math.max(0, Math.min(s.page + (slot == P_NEXT ? 1 : -1), pages - 1));
            if (np != s.page) {
                // il numero pagina e' nel titolo: serve un inventario nuovo
                Bukkit.getScheduler().runTask(plugin, () -> {
                    showPopup(s, np);
                });
            }
            return;
        }
        for (int i = 0; i < P_PER_PAGE; i++) {
            if (pGridSlot(i) != slot) {
                continue;
            }
            int idx = s.page * P_PER_PAGE + i;
            if (idx >= s.rows.size() || s.onRow == null) {
                return;
            }
            String key = s.rows.get(idx).key();
            ClickType click = event.getClick();
            int page = s.page;
            RowClick rc = s.onRow;
            plugin.guiClick(p);
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline()) {
                    rc.click(key, click, page);
                }
            });
            return;
        }
    }
}
