package it.blockmania;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * GUI del profilo staff (/adminmania staffprofile <nome>, oppure click destro sulla testa dello userprofile di uno staffer).
 * Una sola pagina: scena disegnata nel titolo (immagine staffprofile del pack, testa 2D, tre barre di stelle con il valore
 * nel box VAL) e sotto gli slot invisibili con nome e lore. Orologio = info (lore), carta = carriera (popup lista),
 * mute / warn / ban / blacklist / ticket / assistenze vocali = conteggi (lore) e popup lista con le singole azioni.
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

    // testa: interno 34x34 a (15,51) -> faccia 8x8 a scala 4 = 32x32 centrata (come UserProfileGui)
    private static final int FACE_X = 16;
    private static final int FACE_Y = 52;
    private static final int FACE_SCALE = 4;

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

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ROME);

    // ---------- dati ----------

    /** Fotografia dei dati mostrati (letta dal thread principale). */
    private record Data(StaffStats.Info info, StaffStats.Ratings ratings, Map<StaffStats.Cat, StaffStats.Count> counts, int[] careerKinds) {
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private final UUID target;
        private String sig = "";
        private final Map<Integer, String> actions = new HashMap<>();

        Holder(UUID target) {
            this.target = target;
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
        UserProfileGui up = plugin.getUserProfile();
        if (up != null) {
            up.ensureFaceFor(target, () -> refresh(target));
        }
        Holder h = build(target);
        staff.openInventory(h.inventory);
    }

    /**
     * Sfondo dello staffprofile per i popup (da scurire come UserProfileGui.drawDimmedBackground):
     * immagine, testa e barre con avanzamento reale 186. Non scurisce.
     */
    public void drawDimmedBackground(GuiTitle g, UUID target) {
        scene(g, target, data(target), true);
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

    private String sig(UUID target, Data d) {
        UserProfileGui up = plugin.getUserProfile();
        StaffStats.Ratings r = d.ratings();
        return (up != null && up.loadedFace(target) != null ? "F" : "f")
                + fill(r.avg30(), BAR30_N) + "/" + fill(r.avg7(), BAR7_N) + "/" + fill(r.avgAll(), BARALL_N)
                + "/" + valText(r.avg30()) + "/" + valText(r.avg7()) + "/" + valText(r.avgAll());
    }

    // ------------------------------------------------------------------
    //  Titolo (disegno)
    // ------------------------------------------------------------------

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
                g.atComponent(x0 + k * BAR_STEP + ox, glyph(PX1.repeat(BAR_W), tk(y + row), c), BAR_W);
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
                    g.atComponent(x + i + d + ox, glyph(PX1.repeat(run), tk(y + j + d), rgb), run);
                    i += run;
                }
            }
        }
    }

    /** Disegno comune (immagine, testa, barre e numeri). real = avanzamento 186 per lo sfondo dei popup. */
    private void scene(GuiTitle g, UUID target, Data d, boolean real) {
        final int shift = real ? IMG_REAL - IMG_ADV : 0;
        final int ox = IMG + shift;
        g.at(-15, "&f" + IMAGE, FONT, real ? IMG_REAL : IMG_ADV);
        UserProfileGui up = plugin.getUserProfile();
        UserProfileGui.drawFaceAt(g, up == null ? null : up.loadedFace(target), FACE_X + shift, FACE_Y, FACE_SCALE);
        StaffStats.Ratings r = d.ratings();
        drawBar(g, BAR30_X, BAR30_Y, fill(r.avg30(), BAR30_N), C_STAR, ox);
        drawBar(g, BAR7_X, BAR7_Y, fill(r.avg7(), BAR7_N), C_STAR, ox);
        drawBar(g, BARALL_X, BARALL_Y, fill(r.avgAll(), BARALL_N), C_STAR, ox);
        drawMini(g, valText(r.avg30()), VAL30_X, VAL30_Y, ox);
        drawMini(g, valText(r.avg7()), VAL7_X, VAL7_Y, ox);
        drawMini(g, valText(r.avgAll()), VALALL_X, VALALL_Y, ox);
    }

    private Component title(UUID target, Data d) {
        GuiTitle g = new GuiTitle(plugin);
        scene(g, target, d, false);
        UserProfilePowers.topTitle(plugin, g, "staff " + UserProfilePowers.nameOf(target));
        return g.build();
    }

    // ------------------------------------------------------------------
    //  Costruzione e aggiornamento
    // ------------------------------------------------------------------

    private Holder build(UUID target) {
        Data d = data(target);
        Holder h = new Holder(target);
        h.sig = sig(target, d);
        h.inventory = Bukkit.createInventory(h, SIZE, title(target, d));
        fill(h, target, d);
        return h;
    }

    private void update(Player p, Holder h) {
        Data d = data(h.target);
        String sg = sig(h.target, d);
        if (!sg.equals(h.sig)) {
            p.openInventory(build(h.target).inventory);   // il titolo (barre, numeri, testa) cambia: inventario nuovo
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
        setAll(h, S_INFO, pane("&6informazioni", List.of(
                "&7Profilo staff: attivita' e valutazioni.", "",
                "&fStelle: &7media dei voti dei ticket gestiti",
                "&7(ultimi 30 giorni, ultimi 7 giorni, generale).",
                "&fOrologio: &7primo accesso, playtime, grado.",
                "&fCarta: &7click = carriera staff.",
                "&fMute, warn, ban, blacklist, ticket, cuffie:",
                "&7click = elenco delle azioni.")), null);

        setAll(h, S_HEAD, headItem(target, d), null);

        StaffStats.Ratings r = d.ratings();
        setAll(h, S_STAR30, starItem("ultimi 30 giorni", r.avg30(), r.n30()), null);
        setAll(h, S_STAR7, starItem("ultimi 7 giorni", r.avg7(), r.n7()), null);
        setAll(h, S_STARALL, starItem("generale", r.avgAll(), r.nAll()), null);

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
        plugin.guiClick(p);
        final UUID target = h.target;
        // tutto al tick dopo: aprire o chiudere inventari dentro l'evento click lascia la gui in stato incerto
        Bukkit.getScheduler().runTask(plugin, () -> {
            handle(p, target, action);
        });
    }

    private void handle(Player p, UUID target, String action) {
        if (!p.isOnline()) {
            return;
        }
        if (action.equals("close")) {
            p.closeInventory();
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
}
