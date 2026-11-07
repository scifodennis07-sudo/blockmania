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
import org.bukkit.profile.PlayerProfile;
import org.bukkit.scheduler.BukkitTask;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * GUI principale del profilo di un player (/adminmania userprofile <nome>, oppure dal ticket).
 * Disegno nel titolo (immagine userprofile del pack + testa 2D + barre a tacche MUTE / WARN / BAN),
 * sotto gli slot invisibili (pane) con nome e lore per i click. Si aggiorna da sola a GUI aperta:
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

    /** Pixel 1x1 (+ spazio negativo) e 2x2 dei font tk<y> (come Tickets.drawFace / drawStars). */
    private static final String PX1 = "";
    private static final String PX2 = "";

    // testa: interno 34x34 a (15,51) -> faccia 8x8 a scala 4 = 32x32 centrata
    private static final int FACE_X = 16;
    private static final int FACE_Y = 52;
    private static final int FACE_SCALE = 4;

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
    private static final int STA_Y = 90;       // bordo alto (contorno)
    private static final int STA_H = 8;        // altezza totale col contorno -> y 90..97
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
    private final Map<UUID, int[]> faces = new HashMap<>();
    private final Set<UUID> faceAsked = new HashSet<>();
    private BukkitTask task;

    private static final int[] DEFAULT_FACE = new int[64];

    static {
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                DEFAULT_FACE[y * 8 + x] = y < 2 ? 0x4A3222 : 0xC8946A;
            }
        }
        for (int x : new int[]{1, 2, 5, 6}) {
            DEFAULT_FACE[4 * 8 + x] = 0xFFFFFF;
        }
        DEFAULT_FACE[4 * 8 + 2] = 0x3755AA;
        DEFAULT_FACE[4 * 8 + 5] = 0x3755AA;
        DEFAULT_FACE[6 * 8 + 3] = 0x96594A;
        DEFAULT_FACE[6 * 8 + 4] = 0x96594A;
    }

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
        if (!authorize(staff, target, "profile", "see", 0)) {
            return;
        }
        ensureFace(target);
        openHolder(staff, build(staff, target));
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
        return (faces.containsKey(target) ? "F" : "f") + (Bukkit.getPlayer(target) != null ? "O" : "o")
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

    /** Faccia 8x8: scala 1, 2 o 4 (la 4 usa quadrati 2x2 su due righe). y = parte alta in pixel dell'immagine. */
    private void drawFace(GuiTitle g, int[] face, int x, int y, int scale) {
        int sub = Math.max(1, scale / 2);
        for (int j = 0; j < 8; j++) {
            for (int s = 0; s < sub; s++) {
                int yy = y + j * scale + (scale == 1 ? 0 : s * 2);
                int i = 0;
                while (i < 8) {
                    int c = face[j * 8 + i];
                    int n = 1;
                    while (i + n < 8 && face[j * 8 + i + n] == c) {
                        n++;
                    }
                    String px = scale == 1 ? PX1.repeat(n) : PX2.repeat(n * scale / 2);
                    g.atComponent(x + i * scale + IMG, glyph(px, tk(yy), c & 0xFFFFFF), n * scale);
                    i += n;
                }
            }
        }
    }

    /** Tacche piene di una barra (le vuote sono gia' nell'immagine): riga alta piu' chiara, bassa piu' scura. */
    private void drawBar(GuiTitle g, int x0, int step, int w, int y, int count, int rgb) {
        for (int row = 0; row < TACCA_H; row++) {
            int c = row == 0 ? shade(rgb, 1.35) : row == TACCA_H - 1 ? shade(rgb, 0.75) : rgb;
            for (int k = 0; k < count; k++) {
                g.atComponent(x0 + k * step + IMG, glyph(PX1.repeat(w), tk(y + row), c), w);
            }
        }
    }

    /** Barra stamina: contorno scuro, sfondo grigio e riempimento giallo proporzionale (riga alta chiara, bassa scura). */
    private void drawStamina(GuiTitle g, int percent) {
        int inX = STA_X + 1;
        int inW = STA_W - 2;
        int inH = STA_H - 2;
        int filled = percent < 0 ? 0 : (int) Math.round(inW * Math.min(100, percent) / 100.0);
        for (int row = 0; row < STA_H; row++) {
            int y = STA_Y + row;
            if (row == 0 || row == STA_H - 1) {
                g.atComponent(STA_X + IMG, glyph(PX1.repeat(STA_W), tk(y), C_STA_EDGE), STA_W);
                continue;
            }
            g.atComponent(STA_X + IMG, glyph(PX1, tk(y), C_STA_EDGE), 1);
            int r = row - 1;
            int fc = r == 0 ? shade(C_STA_FILL, 1.35) : r == inH - 1 ? shade(C_STA_FILL, 0.75) : C_STA_FILL;
            if (filled > 0) {
                g.atComponent(inX + IMG, glyph(PX1.repeat(filled), tk(y), fc), filled);
            }
            if (inW - filled > 0) {
                g.atComponent(inX + filled + IMG, glyph(PX1.repeat(inW - filled), tk(y), C_STA_EMPTY), inW - filled);
            }
            g.atComponent(inX + inW + IMG, glyph(PX1, tk(y), C_STA_EDGE), 1);
        }
    }

    private Component title(UUID target, Snap s) {
        GuiTitle g = new GuiTitle(plugin);
        g.at(-15, "&f" + IMG_MAIN, FONT, 179);
        drawFace(g, faceOf(target), FACE_X, FACE_Y, FACE_SCALE);
        drawBar(g, MUTE_X, MUTE_STEP, MUTE_W, MUTE_Y, notches(s.mute(), MUTE_N), C_RED);
        drawBar(g, WARN_X, WARN_STEP, WARN_W, WARN_Y, s.warns(), warnColor(s.warns(), s.banFromWarns()));
        drawBar(g, BAN_X, BAN_STEP, BAN_W, BAN_Y, notches(s.ban(), BAN_N), C_RED);
        drawStamina(g, staminaPercent(target));
        UserProfilePowers.topTitle(plugin, g, "profilo " + UserProfilePowers.nameOf(target));
        return g.build();
    }

    // ------------------------------------------------------------------
    //  Facce (testa 2D dalla skin)
    // ------------------------------------------------------------------

    private int[] faceOf(UUID id) {
        int[] f = faces.get(id);
        return f == null ? DEFAULT_FACE : f;
    }

    private static URL skinOf(PlayerProfile profile) {
        try {
            return profile == null ? null : profile.getTextures().getSkin();
        } catch (Throwable t) {
            return null;
        }
    }

    private void ensureFace(UUID id) {
        if (faces.containsKey(id) || !faceAsked.add(id)) {
            return;
        }
        Player online = Bukkit.getPlayer(id);
        URL skin = null;
        if (online != null) {
            skin = skinOf(online.getPlayerProfile());
            if (skin == null) {
                return;
            }
            loadFace(id, skin);
            return;
        }
        try {
            PlayerProfile prof = Bukkit.getOfflinePlayer(id).getPlayerProfile();
            skin = skinOf(prof);
            if (skin != null) {
                loadFace(id, skin);
                return;
            }
            prof.update().thenAccept(updated -> {
                URL u = skinOf(updated);
                if (u != null) {
                    loadFace(id, u);
                }
            });
        } catch (Throwable ignored) {
            // nessuna skin disponibile: resta la faccia di default
        }
    }

    private void loadFace(UUID id, URL url) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                BufferedImage img = ImageIO.read(url);
                if (img == null || img.getWidth() < 64 || img.getHeight() < 16) {
                    return;
                }
                int[] face = new int[64];
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        int base = img.getRGB(8 + x, 8 + y);
                        int hat = img.getHeight() >= 64 ? img.getRGB(40 + x, 8 + y) : 0;
                        int c = ((hat >>> 24) > 127) ? hat : base;
                        face[y * 8 + x] = c & 0xFFFFFF;
                    }
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    faces.put(id, face);
                    refresh(id);
                });
            } catch (Exception e) {
                // la skin non si scarica: resta la faccia di default
            }
        });
    }

    // ------------------------------------------------------------------
    //  Costruzione e aggiornamento
    // ------------------------------------------------------------------

    private Holder build(Player staff, UUID target) {
        Snap s = snap(target);
        Holder h = new Holder(target);
        h.sig = sig(target, s);
        h.inventory = Bukkit.createInventory(h, SIZE, title(target, s));
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
            openHolder(p, build(p, h.target));
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
        boolean online = Bukkit.getPlayer(target) != null;
        PlayerCounters pc = plugin.getCounters();

        setAll(h, S_CLOSE, pane("&cchiudi", List.of()), "close");
        setAll(h, S_INFO, pane("&6informazioni", List.of(
                "&7Profilo del giocatore: sanzioni e contatori.", "",
                "&fMUTE / WARN / BAN: &7click sinistro = comandi,",
                "&7click destro = cronologia.",
                "&fPalle e oggetti: &7click sinistro = comandi",
                "&7(give, remove, set, full, reset).",
                "&fTesta: &7click destro su uno staffer = staffprofile.")), null);

        // testa
        setAll(h, S_HEAD, headItem(target, online), "head");

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

        // miniere, potenziamenti, ricompense, exp: aprono le GUI originali sul player (devono essere online)
        String off = online ? "" : "&cIl giocatore deve essere online.";
        setAll(h, S_UPG_A, pane("&6miniere", linesOf("&7Click: apre le miniere del giocatore.", off)), "mines");
        setAll(h, S_UPG_B, pane("&6potenziamenti", linesOf("&7Click: apre i potenziamenti del giocatore.", off)), "upg");
        setAll(h, S_RW_A, pane("&dricompense", linesOf("&7Click: apre le ricompense del giocatore.", off)), "rw");
        List<String> expLore = new ArrayList<>();
        Player tp = Bukkit.getPlayer(target);
        if (tp != null && plugin.getProfileRewards() != null) {
            expLore.addAll(plugin.getProfileRewards().expLore(tp));
        }
        expLore.add("");
        expLore.add("&7Click: comandi exp (aggiungi, rimuovi, setta, reset).");
        if (!online) {
            expLore.add(off);
        }
        setAll(h, S_RW_B, pane("&5exp", expLore), "exp");
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

    private ItemStack headItem(UUID target, boolean online) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(target);
        List<String> lore = new ArrayList<>();
        if (online) {
            lore.add("&7stato: &aonline");
        } else {
            long seen = op.getLastSeen();
            lore.add("&7stato: &8offline" + (seen > 0 ? " &7(visto " + DATE.format(Instant.ofEpochMilli(seen)) + ")" : ""));
        }
        long mins = playMinutes(target);
        lore.add("&7playtime: &f" + (mins < 0 ? "n/d" : minutes(mins)));
        long login = op.getLastLogin();
        lore.add("&7ultimo login: &f" + (login > 0 ? DATE.format(Instant.ofEpochMilli(login)) : "mai"));
        if (isStaff(target)) {
            lore.add("");
            lore.add("&6e' uno staffer");
            lore.add("&eclick destro: staffprofile");
        }
        return pane(plugin.color("&f" + nameOf(target)), lore);
    }

    private ItemStack timedItem(String label, SanctionManager.Status st, long now, boolean fromWarns, String extra) {
        List<String> lore = new ArrayList<>();
        String color = fromWarns && st.active() ? "&5" : st.active() ? "&c" : "&7";
        if (st.active()) {
            lore.add("&7stato: " + color + "attivo");
            if (st.endMs() <= 0 || st.totalMs() <= 0) {
                lore.add("&7tempo mancante: &fpermanente");
            } else {
                lore.add("&7tempo mancante: &f" + dur(Math.max(0, st.endMs() - now)));
                lore.add("&7durata totale: &f" + dur(st.totalMs()));
            }
            if (fromWarns) {
                lore.add("&5causato dai 4 warn");
            }
        } else {
            lore.add("&7stato: &anon sanzionato");
        }
        lore.add("");
        lore.add("&eclick sinistro: comandi");
        lore.add("&eclick destro: cronologia");
        return pane(color + label, lore);
    }

    private ItemStack warnItem(Snap s) {
        List<String> lore = new ArrayList<>();
        int w = s.warns();
        lore.add("&7warn: &f" + w + "&8/&f" + WARN_N);
        if (s.banFromWarns()) {
            lore.add("&5bannato per i warn");
        }
        if (!s.warnNote().isBlank()) {
            lore.add("");
            for (String part : wrap(s.warnNote(), 34)) {
                lore.add("&d" + part);
            }
        }
        lore.add("");
        lore.add("&eclick sinistro: comandi");
        lore.add("&eclick destro: cronologia");
        String color = s.banFromWarns() && w >= WARN_N ? "&5" : w == 0 ? "&7" : w == 1 ? "&e" : w == 2 ? "&6" : "&c";
        return pane(color + "warn", lore);
    }

    private ItemStack counterItem(Icon ic, UUID target, PlayerCounters pc, boolean online) {
        List<String> lore = new ArrayList<>();
        lore.add("&7disponibili: &f" + amount(pc, target, ic.item()));
        if (ic.fired()) {
            lore.add("&7sparate in totale: &f" + pc.totalFired(target, ic.item()));
        }
        lore.add("");
        if (online) {
            lore.add("&eclick sinistro: comandi (" + String.join(", ", pc.commands(ic.item())) + ")");
        } else {
            lore.add("&8il player e' offline: dati salvati all'uscita,");
            lore.add("&8si possono solo guardare");
        }
        return pane(ic.color() + ic.name(), lore);
    }

    private ItemStack staminaItem(UUID target) {
        int pct = staminaPercent(target);
        Player tp = Bukkit.getPlayer(target);
        Power pw = plugin.getPower();
        List<String> lore = new ArrayList<>();
        if (pct < 0 || tp == null || pw == null) {
            lore.add("&8il player e' offline: stamina non disponibile");
        } else {
            lore.add("&7livello: &f" + pct + "%");
            lore.add("&7modalita': &f" + pw.mode(tp));
            lore.add("");
            lore.add("&eclick: comandi stamina");
        }
        return pane("&eSTAMINA", lore);
    }

    private ItemStack powerItem(UUID target, PlayerCounters pc, boolean online) {
        int charges = pc.value(target, PlayerCounters.Item.POWER_CHARGES);
        List<String> lore = new ArrayList<>();
        lore.add("&7attivo: " + (charges > 0 ? "&asi" : "&cno") + " &8(&f" + amount(pc, target, PlayerCounters.Item.POWER_CHARGES) + "&8)");
        lore.add("&7utilizzi: &f" + amount(pc, target, PlayerCounters.Item.POWER_USES));
        lore.add("&7colpi potenziati sparati in totale: &f" + pc.totalFired(target, PlayerCounters.Item.POWER_CHARGES));
        lore.add("");
        if (online) {
            lore.add("&eclick sinistro: comandi sbloccati");
            lore.add("&eclick destro: comandi utilizzi");
        } else {
            lore.add("&8il player e' offline: dati salvati all'uscita,");
            lore.add("&8si possono solo guardare");
        }
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
        plugin.guiClick(p);
        final boolean right = event.isRightClick();
        final UUID target = h.target;
        // tutto al tick dopo: aprire o chiudere inventari dentro l'evento click lascia la gui in stato incerto
        Bukkit.getScheduler().runTask(plugin, () -> handle(p, target, action, right));
    }

    private void handle(Player p, UUID target, String action, boolean right) {
        if (!p.isOnline()) {
            return;
        }
        if (action.equals("close")) {
            p.closeInventory();
        } else if (action.equals("head")) {
            if (right && isStaff(target)) {
                GuiMsg.send(plugin, p, "&7staffprofile non ancora disponibile.");
            }
        } else if (action.startsWith("sanc:")) {
            SanctionManager.Kind kind = SanctionManager.Kind.valueOf(action.substring(5));
            if (right) {
                openHistory(p, target, kind);
            } else {
                sanctionMenu(p, target, kind);
            }
        } else if (action.equals("stamina")) {
            if (!right) {
                staminaMenu(p, target);
            }
        } else if (action.startsWith("item:")) {
            PlayerCounters.Item it = PlayerCounters.Item.valueOf(action.substring(5));
            if (right) {
                return;
            }
            itemMenu(p, target, it);
        } else if (action.equals("mines") || action.equals("upg") || action.equals("rw") || action.equals("exp")) {
            if (right) {
                return;
            }
            Player tp = Bukkit.getPlayer(target);
            if (tp == null) {
                GuiMsg.send(plugin, p, "&cIl giocatore deve essere online.");
                return;
            }
            Runnable back = () -> back(p, target);
            switch (action) {
                case "mines" -> plugin.getProfileMines().open(p, tp, back);
                case "upg" -> plugin.getProfileUpgrades().open(p, tp, back);
                case "rw" -> plugin.getProfileRewards().open(p, tp, back);
                default -> plugin.getProfileRewards().openExp(p, tp, back);
            }
        }
    }

    private void back(Player p, UUID target) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                open(p, target);
            }
        });
    }

    // ------------------------------------------------------------------
    //  Stamina
    // ------------------------------------------------------------------

    private void staminaMenu(Player p, UUID target) {
        if (Bukkit.getPlayer(target) == null) {
            GuiMsg.send(plugin, p, "&cIl giocatore e' offline: stamina non disponibile.");
            return;
        }
        if (!authorize(p, target, "profile", "edit", 0)) {
            return;
        }
        plugin.getProfileUi().openCommands(p, "stamina di " + nameOf(target), List.of("set", "refill", "infinite", "empty", "normal"),
                cmd -> Bukkit.getScheduler().runTask(plugin, () -> staminaPick(p, target, cmd)),
                () -> back(p, target), false, null);
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
            ui.confirm(p, cmd + " stamina", () -> applyStamina(p, target, cmd, 0), cancel);
        }
    }

    private void applyStamina(Player p, UUID target, String cmd, int percent) {
        if (!p.isOnline()) {
            return;
        }
        Player tp = Bukkit.getPlayer(target);
        Power pw = plugin.getPower();
        if (tp == null || pw == null) {
            GuiMsg.send(plugin, p, "&cIl giocatore e' offline: stamina non disponibile.");
            back(p, target);
            return;
        }
        if (!authorize(p, target, "profile", "edit", percent)) {
            back(p, target);
            return;
        }
        switch (cmd) {
            case "set" -> pw.setPercent(tp, percent);
            case "refill" -> pw.refill(tp);
            case "infinite" -> pw.setMode(tp, "infinite");
            case "empty" -> pw.setMode(tp, "empty");
            default -> pw.setMode(tp, "normal");
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

    private void sanctionMenu(Player p, UUID target, SanctionManager.Kind kind) {
        if (!authorize(p, target, "sanctions", "see", 0)) {
            return;
        }
        // mute e ban: solo add/remove/reduce (il reset si fa dalla cronologia); warn: add/remove/reset (reset sempre ultimo)
        List<String> cmds = kind == SanctionManager.Kind.WARN ? List.of("add", "remove", "reset") : List.of("add", "remove", "reduce");
        String label = kind.name().toLowerCase(java.util.Locale.ROOT) + " di " + nameOf(target);
        plugin.getProfileUi().openCommands(p, label, cmds,
                cmd -> Bukkit.getScheduler().runTask(plugin, () -> sanctionPick(p, target, kind, cmd)),
                () -> back(p, target), true, () -> Bukkit.getScheduler().runTask(plugin, () -> openHistory(p, target, kind)));
    }

    private void sanctionPick(Player p, UUID target, SanctionManager.Kind kind, String cmd) {
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
            case "add" -> askRuleCode(p, target, kind);
            case "reduce" -> {
                long max = plugin.getConfig().getLong("sanctions.max-minutes." + k, kind == SanctionManager.Kind.BAN ? 525600L : 43200L);
                ui.askNumber(p, "minuti da togliere", "RIDUCI " + k.toUpperCase(java.util.Locale.ROOT) + " MIN", 1, max, v -> doSanction(p, target, kind, cmd, v), cancel);
            }
            case "remove", "reset" -> ui.confirm(p, cmd + " " + k, () -> doSanction(p, target, kind, cmd, 0), cancel);
            default -> back(p, target);
        }
    }

    /** Flusso "add" con regola: codice -> messaggio staff -> conferma -> SanctionManager.applyRule. */
    private void askRuleCode(Player p, UUID target, SanctionManager.Kind kind) {
        UserProfilePowers ui = plugin.getProfileUi();
        Runnable cancel = () -> back(p, target);
        ui.askText(p, "codice regola", "CODICE REGOLA", "", code -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            Rules.Rule rule = plugin.getRules().get(code == null ? "" : code.trim());
            if (rule == null || rule.type() == Rules.Type.REGOLAMENTO || !rule.type().name().equals(kind.name())) {
                GuiMsg.send(plugin, p, "&cregola inesistente o di altro tipo");
                sanctionMenu(p, target, kind); // torna al popup
                return;
            }
            ui.askText(p, "messaggio per il player", "MESSAGGIO STAFF", "null", msg -> Bukkit.getScheduler().runTask(plugin, () -> {
                if (!p.isOnline()) {
                    return;
                }
                ui.confirm(p, "sanzionare con la regola " + rule.code(),
                        () -> Bukkit.getScheduler().runTask(plugin, () -> doRuleSanction(p, target, kind, rule, msg)), cancel);
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

    private void itemMenu(Player p, UUID target, PlayerCounters.Item it) {
        PlayerCounters pc = plugin.getCounters();
        if (!pc.isOnline(target)) {
            GuiMsg.send(plugin, p, "&cIl player e' offline: i contatori si possono solo guardare.");
            plugin.playConfiguredSound(p.getLocation(), "message-sounds.error");
            return;
        }
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
                () -> back(p, target), false, null);
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
            case "full", "reset" -> ui.confirm(p, cmd + " " + labelOf(it), () -> applyItem(p, target, it, cmd, 0), cancel);
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
        String err = plugin.getCounters().apply(target, it, cmd, n, p);
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
