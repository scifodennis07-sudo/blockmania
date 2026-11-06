package it.blockmania;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.scheduler.BukkitTask;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Sistema ticket: /ticket (player) e /adminmania ticket (staff).
 * Le gui sono ticket.png, ticket-2.png e ticket-3.png del pack (nomi ticket-1/2/3):
 * la pagina principale, la lista dei miei ticket e la chat. Tutto quello che cambia (tacchetta, testo,
 * teste, chip, fumetti) e' scritto nel titolo della gui con i caratteri del pack.
 */
public final class Tickets implements Listener {

    // ---------- misure del pack (pixel dell'immagine; nel titolo si sottrae IMG) ----------
    private static final int IMG = -22;
    private static final String IMG_MAIN = "㸀";
    private static final String IMG_LIST = "㸁";
    private static final String IMG_CHAT = "㸂";
    private static final String IMG_CAT = "\u3e03";          // ticket-4: cambia sezione
    private static final String IMG_ITEMS = "\u3e04";        // ticket-5: popup con gli oggetti
    private static final String IMG_CONF_STAFF = "\u3e05";   // ticket-6: conferma (staff)
    private static final String IMG_PCHAT = "\u3e06";        // ticket-7: chat del player
    private static final String IMG_CONF_PLAYER = "\u3e07";  // ticket-8: conferma (player)
    private static final String IMG_ANVIL = "\u3e08";        // incudine personalizzata
    private static final int SIZE = 54;

    private static final String[] CATS = {"assistenza", "bug", "report"};
    private static final String[] CAT_LABEL = {"assistenza generale", "segnala bug", "segnala player"};
    private static final String[] CAT_SHORT = {"assistenza", "bug", "report"};
    private static final String[] STATUS_NAME = {"aperto", "in attesa", "chiuso"};
    private static final char[] LTICK = {'㼂', '㼁', '㼀'};     // verde, giallo, rosso
    private static final int[] CHIP_W = {41, 56, 38, 62, 26, 40};           // aperto, attesa, chiuso, assist., bug, report
    private static final String[] FILTERS = {"tutti", "aperti", "chiusi", "assistenza", "bug", "report"};

    // popup ticket-5: testo della barra in alto e numero pagina, scritti con i set del pack per i popup
    private static final char BAR = '\uf500';
    private static final char BAR_SH = '\uf600';
    /** Font separato per le barre dei popup (i troppi glifi nel font default rompevano il client). */
    private static final String FONT_TINY2 = "minecraft:tiny2";
    private static final char NAV2 = '\uF700';
    private static final char NAV2_SH = '\uF710';
    /** Larghezza massima (pixel) del testo nella barra dei popup: oltre esce dal riquadro. */
    private static final int BAR_MAX = 58;
    /** Centro (pixel immagine) di barra e numero pagina nei popup ticket-5. */
    private static final int POPUP_CX = 94;

    // stella piena (disegnata a pixel come le facce): 1 = dentro, 2 = bordo interno
    private static final String[] STAR_MASK = {
            ".............", "......22.....", "......22.....", "......11.....", ".222221122222",
            "..2111111112.", "...21111112..", "....211112...", "....211112...", "....112211...",
            "...21....12..", "...2......2..", ".............", "............."};
    private static final int STAR_X0 = 70;
    private static final int STAR_Y0 = 182;
    private static final int STAR_PITCH = 18;
    private static final int STAR_IN = 0xFFD83A;
    private static final int STAR_EDGE = 0xE0A100;

    // chat
    private static final int ROW_Y0 = 57;
    private static final int ROW_H = 12;
    private static final int ROWS = 9;
    private static final int VX0 = 17;
    private static final int VX1 = 155;
    private static final int MAX_TEXT = 104;
    private static final int[] TEXT_RGB = {0x2A2A2A, 0x0F2D46, 0x5A4600};

    // slot della chat (rawSlot: 0-53 sopra, 54-80 inventario, 81-89 hotbar)
    private static final int[] SLOTS_UP = {17, 26, 35};
    private static final int[] SLOTS_DOWN = {44, 53, 62};
    private static final int[] SLOTS_WRITE = {81, 82, 83, 84, 85, 86, 87, 88, 89};
    private static final String[] CANNED = {"risolto", "accettata", "chiusura", "non_valida", "prove", "attendi"};

    private static final String[] STATUS_MODEL = {"ticket_open", "ticket_wait", "ticket_closed"};

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM HH:mm").withZone(ZoneId.systemDefault());

    // ---------- dati ----------
    private static final class Msg {
        UUID author;
        String name = "";
        boolean staff;
        String text = "";
        long time;
        String kind = "msg";      // msg, sys, canned, note (solo staff)
    }

    private static final class Ticket {
        int id;
        String code = "";
        UUID owner;
        String ownerName = "";
        String category = "assistenza";
        boolean open = true;
        long created;
        long updated;
        long closedAt;
        String closedBy = "";
        UUID closedByUuid;
        UUID lastStaff;
        String lastStaffName = "";
        long autoClose;
        int rating;              // 0 = da dare, 1-5 = voto, -1 = non richiesto (ticket vecchi)
        long rateDeadline;       // entro quando va dato il voto
        boolean ratedAuto;
        final Set<UUID> staff = new LinkedHashSet<>();
        final Map<UUID, Integer> read = new HashMap<>();
        final List<Msg> msgs = new ArrayList<>();
    }

    private record Line(int msg, int idx, int n, String text, int side, Msg m) {
    }

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private String kind;
        private int ticketId;
        private int page;
        private int filter;
        private int scroll;
        private Player viewer;
        private List<String> links = new ArrayList<>();
        private String mode = "open";
        private final Map<Integer, String> actions = new HashMap<>();

        Holder(String kind) {
            this.kind = kind;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class AnvilSession {
        AnvilView view;
        Consumer<String> onSend;
        Runnable onCancel;
        String text = "";
        boolean done;
    }

    private static final class ChatInput {
        Consumer<String> onSend;
        Runnable onCancel;
    }

    private final BlockMania plugin;
    private final File file;
    private final File invFolder;
    private final Map<Integer, Ticket> tickets = new LinkedHashMap<>();
    private int nextId = 1;
    private final Map<UUID, int[]> faces = new HashMap<>();
    private final Map<UUID, String> staffSeen = new LinkedHashMap<>();
    private final Map<UUID, ItemStack[]> hidden = new HashMap<>();
    private final Set<UUID> switching = new HashSet<>();
    private final Map<UUID, AnvilSession> anvils = new HashMap<>();
    private final Map<UUID, ChatInput> awaiting = new HashMap<>();
    private final Set<UUID> faceAsked = new HashSet<>();
    private final Map<UUID, Integer> stars = new HashMap<>();
    private final Set<Integer> reminded = new HashSet<>();
    private BukkitTask task;

    public Tickets(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "tickets.yml");
        this.invFolder = new File(plugin.getDataFolder(), "ticket-inv");
    }

    // ------------------------------------------------------------------
    //  Avvio / stop / salvataggio
    // ------------------------------------------------------------------

    public void start() {
        load();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1200L, 1200L);
        for (Player p : Bukkit.getOnlinePlayers()) {
            restoreInventory(p);
            if (isStaff(p)) {
                seeStaff(p);
            }
        }
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            restoreInventory(p);
        }
        save();
    }

    private void load() {
        tickets.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        nextId = Math.max(1, y.getInt("next-id", 1));
        ConfigurationSection fs = y.getConfigurationSection("faces");
        if (fs != null) {
            for (String k : fs.getKeys(false)) {
                try {
                    String hex = fs.getString(k, "");
                    if (hex.length() != 64 * 6) {
                        continue;
                    }
                    int[] f = new int[64];
                    for (int i = 0; i < 64; i++) {
                        f[i] = Integer.parseInt(hex.substring(i * 6, i * 6 + 6), 16);
                    }
                    faces.put(UUID.fromString(k), f);
                } catch (Exception ignored) {
                    // faccia rovinata: si riscarica
                }
            }
        }
        ConfigurationSection ss = y.getConfigurationSection("staff-seen");
        if (ss != null) {
            for (String k : ss.getKeys(false)) {
                try {
                    staffSeen.put(UUID.fromString(k), ss.getString(k, "?"));
                } catch (Exception ignored) {
                    // uuid non valido
                }
            }
        }
        ConfigurationSection ts = y.getConfigurationSection("tickets");
        if (ts == null) {
            return;
        }
        for (String key : ts.getKeys(false)) {
            try {
                ConfigurationSection s = ts.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                Ticket t = new Ticket();
                t.id = Integer.parseInt(key);
                t.owner = UUID.fromString(s.getString("owner", ""));
                t.ownerName = s.getString("owner-name", "?");
                t.code = s.getString("code", "");
                if (t.code.isEmpty()) {
                    t.code = newCode();
                }
                t.category = s.getString("category", "assistenza");
                t.open = s.getBoolean("open", true);
                t.created = s.getLong("created");
                t.updated = s.getLong("updated");
                t.closedAt = s.getLong("closed-at");
                t.closedBy = s.getString("closed-by", "");
                String cb = s.getString("closed-by-uuid", "");
                t.closedByUuid = cb.isEmpty() ? null : UUID.fromString(cb);
                String ls = s.getString("last-staff", "");
                t.lastStaff = ls.isEmpty() ? null : UUID.fromString(ls);
                t.lastStaffName = s.getString("last-staff-name", "");
                t.autoClose = s.getLong("auto-close");
                t.rating = s.contains("rating") ? s.getInt("rating") : (t.open ? 0 : -1);
                t.rateDeadline = s.getLong("rate-deadline");
                t.ratedAuto = s.getBoolean("rated-auto");
                if (t.category.equals("segnalazione")) {
                    t.category = "report";
                }
                for (String u : s.getStringList("staff")) {
                    t.staff.add(UUID.fromString(u));
                }
                ConfigurationSection rs = s.getConfigurationSection("read");
                if (rs != null) {
                    for (String u : rs.getKeys(false)) {
                        t.read.put(UUID.fromString(u), rs.getInt(u));
                    }
                }
                for (Map<?, ?> raw : s.getMapList("messages")) {
                    Msg m = new Msg();
                    Object a = raw.get("a");
                    m.author = a == null ? null : UUID.fromString(String.valueOf(a));
                    m.name = String.valueOf(raw.get("n") == null ? "" : raw.get("n"));
                    m.staff = Boolean.parseBoolean(String.valueOf(raw.get("s")));
                    m.text = String.valueOf(raw.get("t") == null ? "" : raw.get("t"));
                    m.time = raw.get("d") == null ? 0L : Long.parseLong(String.valueOf(raw.get("d")));
                    m.kind = String.valueOf(raw.get("k") == null ? "msg" : raw.get("k"));
                    t.msgs.add(m);
                }
                tickets.put(t.id, t);
                nextId = Math.max(nextId, t.id + 1);
            } catch (Exception e) {
                plugin.getLogger().warning("tickets.yml: ticket " + key + " non letto (" + e.getMessage() + ")");
            }
        }
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-id", nextId);
        for (Map.Entry<UUID, int[]> e : faces.entrySet()) {
            StringBuilder sb = new StringBuilder();
            for (int c : e.getValue()) {
                sb.append(String.format("%06x", c & 0xFFFFFF));
            }
            y.set("faces." + e.getKey(), sb.toString());
        }
        for (Map.Entry<UUID, String> e : staffSeen.entrySet()) {
            y.set("staff-seen." + e.getKey(), e.getValue());
        }
        for (Ticket t : tickets.values()) {
            String p = "tickets." + t.id + ".";
            y.set(p + "owner", t.owner.toString());
            y.set(p + "owner-name", t.ownerName);
            y.set(p + "code", t.code);
            y.set(p + "category", t.category);
            y.set(p + "open", t.open);
            y.set(p + "created", t.created);
            y.set(p + "updated", t.updated);
            y.set(p + "closed-at", t.closedAt);
            y.set(p + "closed-by", t.closedBy);
            y.set(p + "closed-by-uuid", t.closedByUuid == null ? "" : t.closedByUuid.toString());
            y.set(p + "last-staff", t.lastStaff == null ? "" : t.lastStaff.toString());
            y.set(p + "last-staff-name", t.lastStaffName);
            y.set(p + "auto-close", t.autoClose);
            y.set(p + "rating", t.rating);
            y.set(p + "rate-deadline", t.rateDeadline);
            y.set(p + "rated-auto", t.ratedAuto);
            List<String> st = new ArrayList<>();
            for (UUID u : t.staff) {
                st.add(u.toString());
            }
            y.set(p + "staff", st);
            for (Map.Entry<UUID, Integer> e : t.read.entrySet()) {
                y.set(p + "read." + e.getKey(), e.getValue());
            }
            List<Map<String, Object>> list = new ArrayList<>();
            for (Msg m : t.msgs) {
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("a", m.author == null ? null : m.author.toString());
                raw.put("n", m.name);
                raw.put("s", m.staff);
                raw.put("t", m.text);
                raw.put("d", m.time);
                raw.put("k", m.kind);
                list.add(raw);
            }
            y.set(p + "messages", list);
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("tickets.yml: " + e.getMessage());
        }
    }

    /** Ogni minuto: chiude i ticket dove il player non ha risposto in tempo al messaggio di chiusura. */
    private void tick() {
        long now = System.currentTimeMillis();
        for (Ticket t : new ArrayList<>(tickets.values())) {
            if (t.open && t.autoClose > 0 && now >= t.autoClose) {
                closeTicket(t, null, "sistema", true);
            } else if (!t.open && t.rating == 0 && t.rateDeadline > 0) {
                if (now >= t.rateDeadline) {
                    autoRate(t);
                } else if (now >= t.rateDeadline - 3600_000L && reminded.add(t.id)) {
                    Player o = Bukkit.getPlayer(t.owner);
                    if (o != null) {
                        notifyRate(o, t);
                    }
                }
            }
        }
    }

    private int rateHours() {
        return Math.max(1, plugin.getConfig().getInt("tickets.rate-hours", 6));
    }

    /** Ticket chiuso del player che non e ancora stato valutato (finche c'e non puo aprirne un altro). */
    private Ticket pendingRating(UUID owner) {
        Ticket best = null;
        for (Ticket t : tickets.values()) {
            if (!t.open && t.rating == 0 && t.owner.equals(owner) && (best == null || t.closedAt >= best.closedAt)) {
                best = t;
            }
        }
        return best;
    }

    /** Title + messaggio: ricorda al player di valutare il ticket entro la scadenza. */
    private void notifyRate(Player p, Ticket t) {
        long left = Math.max(0, t.rateDeadline - System.currentTimeMillis());
        long h = left / 3600_000L;
        long m = (left % 3600_000L) / 60_000L;
        String when = h > 0 ? h + " ore e " + m + " min" : m + " minuti";
        p.showTitle(Title.title(plugin.color("&6&lvaluta il ticket"),
                plugin.color("&fhai ancora &e" + when + "&f: scrivi &e/ticket &fe dai le stelle"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(5), Duration.ofMillis(800))));
        p.sendMessage(plugin.color("&6[ticket] &fDevi valutare il ticket &e#" + t.id + "&f entro &e" + when
                + "&f, altrimenti ricevi un warn e il voto sara 5 stelle. ")
                .append(plugin.color("&b&n[valuta ora]").clickEvent(ClickEvent.runCommand("/ticket " + t.id))));
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
    }

    private void rateTicket(Player p, Ticket t, int n) {
        if (t.open || t.rating != 0 || !t.owner.equals(p.getUniqueId())) {
            return;
        }
        t.rating = Math.max(1, Math.min(5, n));
        t.rateDeadline = 0;
        stars.remove(p.getUniqueId());
        addMsg(t, p, false, "sys", "Valutazione del ticket: " + t.rating + " su 5 stelle", true);
        save();
        ok(p, "grazie! hai valutato il ticket con " + t.rating + (t.rating == 1 ? " stella" : " stelle"));
    }

    /** Scaduto il tempo: 5 stelle in automatico e warn (comando in tickets.warn-command). */
    private void autoRate(Ticket t) {
        if (t.open || t.rating != 0) {
            return;
        }
        t.rating = 5;
        t.ratedAuto = true;
        t.rateDeadline = 0;
        stars.remove(t.owner);
        addMsg(t, null, false, "sys", "Valutazione assegnata in automatico: 5 stelle (il player non ha valutato in tempo)", false);
        save();
        String cmd = plugin.getConfig().getString("tickets.warn-command", "");
        OfflinePlayer off = Bukkit.getOfflinePlayer(t.owner);
        if (cmd != null && !cmd.isBlank() && (off.isOnline() || off.hasPlayedBefore())) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", t.ownerName));
        }
        Player o = Bukkit.getPlayer(t.owner);
        if (o != null) {
            o.sendMessage(plugin.color("&6[ticket] &cNon hai valutato il ticket &e#" + t.id
                    + "&c in tempo: hai ricevuto un warn e il ticket e stato valutato 5 stelle."));
        }
        for (Player pl : Bukkit.getOnlinePlayers()) {
            refreshChat(pl, t);
        }
    }

    // ------------------------------------------------------------------
    //  Config e testi
    // ------------------------------------------------------------------

    private int maxStaffOpen() {
        return Math.max(1, plugin.getConfig().getInt("tickets.max-staff-open", 9));
    }

    private int autoCloseHours() {
        return Math.max(1, plugin.getConfig().getInt("tickets.auto-close-hours", 6));
    }

    private String cannedText(String key) {
        String def = switch (key) {
            case "risolto" -> "Ciao {player}, il tuo problema dovrebbe essere risolto. Fammi sapere se va tutto bene!";
            case "accettata" -> "Ciao {player}, abbiamo preso in carico la tua segnalazione. La stiamo verificando, grazie per averci avvisato!";
            case "chiusura" -> "Ciao {player}, se non hai altro da aggiungere chiuderemo il ticket tra {ore} ore. Se ti serve ancora aiuto scrivici qui.";
            case "non_valida" -> "Ciao {player}, dopo un controllo la tua segnalazione non risulta valida. Se pensi sia un errore scrivici qui.";
            case "prove" -> "Ciao {player}, per verificare ci servono delle prove (screenshot o video). Caricale su https://imgur.com/upload e mandaci il link qui.";
            default -> "Ciao {player}, ci stiamo lavorando. Ti chiediamo ancora un po' di pazienza, ti aggiorniamo appena possibile.";
        };
        return plugin.getConfig().getString("tickets.canned." + key, def);
    }

    private String cannedName(String key) {
        return switch (key) {
            case "risolto" -> "&amessaggio: risolto";
            case "accettata" -> "&9messaggio: segnalazione accettata";
            case "chiusura" -> "&6messaggio: chiusura ticket";
            case "non_valida" -> "&cmessaggio: segnalazione non valida";
            case "prove" -> "&7messaggio: servono prove";
            default -> "&bmessaggio: attendi";
        };
    }

    private String fillCanned(String key, Ticket t) {
        return cannedText(key).replace("{player}", t.ownerName).replace("{ore}", String.valueOf(autoCloseHours()));
    }

    private boolean isStaff(CommandSender s) {
        if (s.isOp()) {
            return true;
        }
        ConfigurationSection roles = plugin.getConfig().getConfigurationSection("adminmania.roles");
        if (roles == null) {
            return false;
        }
        for (String r : roles.getKeys(false)) {
            if (s.hasPermission("blockmania.role." + r)) {
                return true;
            }
        }
        return false;
    }

    private void seeStaff(Player p) {
        if (!p.getName().equals(staffSeen.get(p.getUniqueId()))) {
            staffSeen.put(p.getUniqueId(), p.getName());
            save();
        }
        fetchFace(p);
    }

    private void err(Player p, String text) {
        p.sendMessage(plugin.color("&c" + text));
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.error");
    }

    private void ok(Player p, String text) {
        p.sendMessage(plugin.color("&a" + text));
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
    }

    // ------------------------------------------------------------------
    //  Testi: pulizia, misura, a capo
    // ------------------------------------------------------------------

    private static String clean(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case 'à', 'á', 'â', 'ä' -> sb.append('a');
                case 'è', 'é', 'ê', 'ë' -> sb.append('e');
                case 'ì', 'í', 'î', 'ï' -> sb.append('i');
                case 'ò', 'ó', 'ô', 'ö' -> sb.append('o');
                case 'ù', 'ú', 'û', 'ü' -> sb.append('u');
                case 'À', 'Á', 'Â', 'Ä' -> sb.append('A');
                case 'È', 'É', 'Ê', 'Ë' -> sb.append('E');
                case 'Ì', 'Í', 'Î', 'Ï' -> sb.append('I');
                case 'Ò', 'Ó', 'Ô', 'Ö' -> sb.append('O');
                case 'Ù', 'Ú', 'Û', 'Ü' -> sb.append('U');
                case '’', '‘' -> sb.append('\'');
                case '“', '”' -> sb.append('"');
                default -> {
                    if (ch >= 32 && ch <= 126) {
                        sb.append(ch);
                    } else if (Character.isWhitespace(ch)) {
                        sb.append(' ');
                    } else {
                        sb.append('?');
                    }
                }
            }
        }
        String out = sb.toString().replaceAll(" +", " ").trim();
        return out.length() > 400 ? out.substring(0, 400) : out;
    }

    private static int textWidth(String s) {
        int w = 0;
        for (char ch : s.toCharArray()) {
            w += Math.max(1, FontMetrics.width(ch));
        }
        return w;
    }

    private static List<String> wrap(String text, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int cw = 0;
        int space = textWidth(" ");
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            int ww = textWidth(word);
            int sp = cur.length() == 0 ? 0 : space;
            if (cw + sp + ww <= max) {
                if (cur.length() > 0) {
                    cur.append(' ');
                }
                cur.append(word);
                cw += sp + ww;
                continue;
            }
            if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
                cw = 0;
            }
            while (textWidth(word) > max) {
                int k = 1;
                while (k < word.length() - 1 && textWidth(word.substring(0, k + 1)) <= max) {
                    k++;
                }
                out.add(word.substring(0, k));
                word = word.substring(k);
            }
            cur.append(word);
            cw = textWidth(word);
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        if (out.isEmpty()) {
            out.add("");
        }
        return out;
    }

    private static List<String> wrapLore(String text, int chars, String prefix) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : text.split(" ")) {
            if (cur.length() + w.length() + 1 > chars && cur.length() > 0) {
                out.add(prefix + cur);
                cur.setLength(0);
            }
            if (cur.length() > 0) {
                cur.append(' ');
            }
            cur.append(w);
        }
        if (cur.length() > 0) {
            out.add(prefix + cur);
        }
        return out;
    }

    // ------------------------------------------------------------------
    //  Facce (testa 2D dalla skin)
    // ------------------------------------------------------------------

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

    private int[] faceOf(UUID id) {
        int[] f = id == null ? null : faces.get(id);
        return f == null ? DEFAULT_FACE : f;
    }

    private void fetchFace(Player p) {
        final UUID id = p.getUniqueId();
        if (!faceAsked.add(id)) {
            return;
        }
        URL skin = null;
        try {
            skin = p.getPlayerProfile().getTextures().getSkin();
        } catch (Throwable ignored) {
            // profilo senza skin
        }
        if (skin == null) {
            return;
        }
        final URL url = skin;
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
                    save();
                });
            } catch (Exception e) {
                // la skin non si scarica: resta la faccia di default
            }
        });
    }

    // ------------------------------------------------------------------
    //  Ticket: ricerche
    // ------------------------------------------------------------------

    private Ticket lastTicketOf(UUID owner) {
        Ticket best = null;
        for (Ticket t : tickets.values()) {
            if (t.owner.equals(owner) && (best == null || t.created >= best.created)) {
                best = t;
            }
        }
        return best;
    }

    private Ticket openTicketOf(UUID owner) {
        for (Ticket t : tickets.values()) {
            if (t.open && t.owner.equals(owner)) {
                return t;
            }
        }
        return null;
    }

    private int staffOpenCount(UUID staff) {
        int n = 0;
        for (Ticket t : tickets.values()) {
            if (t.open && t.staff.contains(staff)) {
                n++;
            }
        }
        return n;
    }

    private static int catIndex(String cat) {
        for (int i = 0; i < CATS.length; i++) {
            if (CATS[i].equals(cat)) {
                return i;
            }
        }
        return 0;
    }

    private static int statusOf(Ticket t) {
        if (!t.open) {
            return 2;
        }
        return (t.staff.isEmpty() || t.autoClose > 0) ? 1 : 0;
    }

    private boolean visible(Msg m, boolean staffView) {
        return staffView || !m.kind.equals("note");
    }

    private int unread(Ticket t, UUID viewer, boolean staffView) {
        int from = t.read.getOrDefault(viewer, 0);
        int n = 0;
        for (int i = Math.max(0, from); i < t.msgs.size(); i++) {
            Msg m = t.msgs.get(i);
            if (visible(m, staffView) && (m.author == null || !m.author.equals(viewer))) {
                n++;
            }
        }
        return n;
    }

    // ------------------------------------------------------------------
    //  Oggetti delle gui
    // ------------------------------------------------------------------

    private static final java.util.regex.Pattern CODE = java.util.regex.Pattern.compile("&(#[0-9a-fA-F]{6}|[0-9a-fk-orA-FK-OR])");

    private static int visibleLen(String w) {
        return CODE.matcher(w).replaceAll("").length();
    }

    /** Spezza una riga di lore a capo (circa max lettere) tenendo il colore anche nelle righe dopo. */
    private static List<String> wrapColored(String raw, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int len = 0;
        String active = "";
        for (String w : raw.split(" ", -1)) {
            int wl = visibleLen(w);
            if (len > 0 && len + 1 + wl > max) {
                out.add(line.toString());
                line = new StringBuilder(active);
                len = 0;
            }
            if (len > 0) {
                line.append(' ');
                len++;
            }
            line.append(w);
            len += wl;
            java.util.regex.Matcher m = CODE.matcher(w);
            while (m.find()) {
                active = m.group().equalsIgnoreCase("&r") ? "" : m.group();
            }
        }
        out.add(line.toString());
        return out;
    }

    private List<Component> loreComponents(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String raw : lines) {
            if (raw == null || raw.isBlank()) {
                out.add(plugin.color(" "));
                continue;
            }
            for (String part : wrapColored(raw, 30)) {
                out.add(plugin.text(part));
            }
        }
        return out;
    }

    private ItemStack pane(String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        if (name == null) {
            meta.setHideTooltip(true);
        } else {
            meta.displayName(plugin.text(name));
            if (lore != null && !lore.isEmpty()) {
                meta.lore(loreComponents(lore));
            }
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text(name));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(loreComponents(lore));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    /** Oggetto vero con il modello blockmania:ticket_open / ticket_wait / ticket_closed (al posto delle icone nel titolo). */
    private ItemStack ticketItem(Ticket t, String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text(name));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(loreComponents(lore));
        }
        meta.setItemModel(new NamespacedKey(plugin, STATUS_MODEL[statusOf(t)]));
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack linkItem(String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text(name));
        meta.lore(loreComponents(lore));
        meta.setItemModel(new NamespacedKey(plugin, "ticket_link"));
        stack.setItemMeta(meta);
        return stack;
    }

    private void put(Holder h, int raw, ItemStack stack, String action) {
        if (raw < SIZE) {
            h.inventory.setItem(raw, stack);
        } else if (h.viewer != null) {
            putBottom(h.viewer, raw, stack);
        }
        if (action != null) {
            h.actions.put(raw, action);
        }
    }

    private void putBottom(Player p, int raw, ItemStack stack) {
        if (raw >= 81) {
            p.getInventory().setItem(raw - 81, stack);
        } else if (raw >= 54) {
            p.getInventory().setItem(raw - 45, stack);
        }
    }

    // ------------------------------------------------------------------
    //  Inventario del player nascosto nella chat
    // ------------------------------------------------------------------

    private void hideInventory(Player p) {
        UUID id = p.getUniqueId();
        if (hidden.containsKey(id)) {
            p.getInventory().setStorageContents(new ItemStack[36]);
            return;
        }
        ItemStack[] snap = p.getInventory().getStorageContents().clone();
        hidden.put(id, snap);
        try {
            invFolder.mkdirs();
            YamlConfiguration y = new YamlConfiguration();
            y.set("items", new ArrayList<>(Arrays.asList(snap)));
            y.save(new File(invFolder, id + ".yml"));
        } catch (IOException e) {
            plugin.getLogger().warning("ticket-inv: " + e.getMessage());
        }
        p.getInventory().setStorageContents(new ItemStack[36]);
    }

    private void restoreInventory(Player p) {
        UUID id = p.getUniqueId();
        ItemStack[] snap = hidden.remove(id);
        File f = new File(invFolder, id + ".yml");
        if (snap == null && f.exists()) {
            try {
                List<?> list = YamlConfiguration.loadConfiguration(f).getList("items");
                if (list != null) {
                    snap = new ItemStack[36];
                    for (int i = 0; i < Math.min(36, list.size()); i++) {
                        Object o = list.get(i);
                        snap[i] = o instanceof ItemStack s ? s : null;
                    }
                }
            } catch (Exception e) {
                snap = null;
            }
        }
        if (snap != null) {
            p.getInventory().setStorageContents(snap);
        }
        if (f.exists() && !f.delete()) {
            plugin.getLogger().warning("ticket-inv: non riesco a cancellare " + f.getName());
        }
    }

    // ------------------------------------------------------------------
    //  Titolo: pezzi di costruzione
    // ------------------------------------------------------------------

    private GuiTitle newTitle(String image) {
        return newTitle(image, 0);
    }

    /** dx sposta l'immagine (serve per l'incudine, il cui titolo parte piu a destra). */
    private GuiTitle newTitle(String image, int dx) {
        GuiTitle t = new GuiTitle(plugin);
        if (image != null && !image.isEmpty()) {
            t.at(-15 + dx, "&f" + image, "", 179);
        } else {
            t.at(-15, "&f", "", 0);
        }
        return t;
    }

    /** Colore con cui si scurisce la GUI di sfondo sotto un popup. */
    private static final String DIM = "&#555555";

    /**
     * Titolo di un popup: prima l'immagine della schermata chiamante scurita (bg), poi il popup a colori pieni.
     * Le immagini hanno stesse posizioni, quindi basta tornare indietro di 179 pixel.
     */
    private GuiTitle popupTitle(String bg, String image) {
        GuiTitle t = new GuiTitle(plugin);
        if (bg != null && !bg.isEmpty()) {
            // avanzamento REALE delle immagini: 185 px di larghezza + 1 = 186 (le immagini ticket*.png finiscono alla colonna 184).
            // Con 179 il popup risultava spostato di 7 px a destra; il cursore dopo il popup resta calcolato come in newTitle (179),
            // cosi i testi (IMG = -22) restano calibrati come nelle altre schermate.
            t.at(-15, DIM + bg, "", 186);
        }
        t.at(-15, "&f" + image, "", 179);
        return t;
    }

    private static Key tk(int y) {
        return Key.key("minecraft", "tk" + y);
    }

    private static Component glyph(String text, Key font, int rgb) {
        return Component.text(text).font(font).color(TextColor.color(rgb)).shadowColor(ShadowColor.none())
                .decoration(TextDecoration.ITALIC, false);
    }

    /** Faccia 8x8 (scala 1) o 16x16 (scala 2) con la parte alta a y (pixel dell'immagine). */
    private void drawFace(GuiTitle g, int[] face, int x, int y, int scale) {
        for (int j = 0; j < 8; j++) {
            int i = 0;
            while (i < 8) {
                int c = face[j * 8 + i];
                int n = 1;
                while (i + n < 8 && face[j * 8 + i + n] == c) {
                    n++;
                }
                String s = ((scale == 1 ? "" : "") + "").repeat(n);
                g.atComponent(x + i * scale + IMG, glyph(s, tk(y + j * scale), c & 0xFFFFFF), n * scale);
                i += n;
            }
        }
    }

    private static int tinyAdvance(String text) {
        int total = 0;
        for (char ch : text.toCharArray()) {
            total += ch == ' ' ? 4 : (ch == 'i' || ch == 'I' || ch == '1') ? 4 : 6;
        }
        return total;
    }

    private static String tinyLetters(String text, char base) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch < 128 && Character.isLetterOrDigit(ch)) {
                sb.append((char) (base + ch));
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }

    private static String tinySafe(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            sb.append(ch < 128 && Character.isLetterOrDigit(ch) ? ch : ' ');
        }
        return sb.toString().replaceAll(" +", " ").trim();
    }

    /** Testo piccolo con ombra. center = true: xImg e' il centro, altrimenti l'inizio. */
    private void tinyText(GuiTitle g, String text, int xImg, boolean center, char base, char shadowBase) {
        text = tinySafe(text);
        if (text.isEmpty()) {
            return;
        }
        if (base == BAR) {
            // il font tiny2 non ha lo spazio (usciva un tofu): ogni parola e' scritta a parte, e il testo non deve uscire dalla barra
            while (tinyAdvance(text) > BAR_MAX && text.length() > 1) {
                text = text.substring(0, text.length() - 1).trim();
            }
            int total = tinyAdvance(text);
            int st = center ? xImg - Math.max(0, total - 1) / 2 : xImg;
            int off = 0;
            for (String w : text.split(" ")) {
                if (w.isEmpty()) {
                    continue;
                }
                int wa = tinyAdvance(w);
                g.at(st + off + 1 + IMG, GuiTitle.shadowCode(plugin) + tinyLetters(w, shadowBase), FONT_TINY2, wa);
                g.at(st + off + IMG, "&f" + tinyLetters(w, base), FONT_TINY2, wa);
                off += wa + 4;
            }
            return;
        }
        int adv = tinyAdvance(text);
        int start = center ? xImg - Math.max(0, adv - 1) / 2 : xImg;
        String fnt = "";
        g.at(start + 1 + IMG, GuiTitle.shadowCode(plugin) + tinyLetters(text, shadowBase), fnt, adv);
        g.at(start + IMG, "&f" + tinyLetters(text, base), fnt, adv);
    }

    private static final char NAV = '\uE4A0';
    private static final char NAV_SH = '\uE4B0';

    /** Numero pagina (1/3) con i numeri disegnati del pack, alla quota della barra dei ticket. */
    private void navText(GuiTitle g, int page, int pages, int centerImg) {
        navText(g, page, pages, centerImg, NAV, NAV_SH);
    }

    private void navText(GuiTitle g, int page, int pages, int centerImg, char nav, char navSh) {
        String plain = page + "/" + pages;
        StringBuilder n = new StringBuilder();
        StringBuilder sh = new StringBuilder();
        for (char ch : plain.toCharArray()) {
            int o = ch == '/' ? 10 : ch - '0';
            n.append((char) (nav + o));
            sh.append((char) (navSh + o));
        }
        int adv = plain.length() * 6;
        int start = centerImg - (adv - 1) / 2;
        String fnt = nav == NAV2 ? FONT_TINY2 : "";
        g.at(start + 1 + IMG, GuiTitle.shadowCode(plugin) + sh, fnt, adv);
        g.at(start + IMG, "&f" + n, fnt, adv);
    }

    /** Stelle piene (giallo) sopra le 5 caselle del ticket-7: n = quante riempire. */
    private void drawStars(GuiTitle g, int n) {
        for (int k = 0; k < Math.min(5, n); k++) {
            int x0 = STAR_X0 + STAR_PITCH * k;
            for (int j = 0; j < STAR_MASK.length; j++) {
                String row = STAR_MASK[j];
                int i = 0;
                while (i < row.length()) {
                    char c = row.charAt(i);
                    if (c == '.') {
                        i++;
                        continue;
                    }
                    int run = 1;
                    while (i + run < row.length() && row.charAt(i + run) == c) {
                        run++;
                    }
                    String s = ("\ue000\ue002").repeat(run);
                    g.atComponent(x0 + i + IMG, glyph(s, tk(STAR_Y0 + j), c == '1' ? STAR_IN : STAR_EDGE), run);
                    i += run;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  Pagina principale (ticket-1)
    // ------------------------------------------------------------------

    public void openMain(Player p) {
        restoreInventory(p);
        seeFace(p);
        UUID id = p.getUniqueId();
        Holder h = new Holder("main");
        Ticket last = lastTicketOf(id);

        GuiTitle g = newTitle(IMG_MAIN);
        if (last != null) {
            int st = statusOf(last);
            g.at(36 + IMG, "&f" + LTICK[st], "", 28);
            tinyText(g, CAT_SHORT[catIndex(last.category)], 72, false, '', '');
            if (last.lastStaff != null) {
                drawFace(g, faceOf(last.lastStaff), 159, 105, 2);
            }
            if (unread(last, id, false) > 0) {
                g.at(23 + IMG, "&f㼐", "", 10);
            }
        }
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());

        put(h, 0, pane("&cchiudi", List.of()), "close");
        put(h, 1, pane("&cchiudi", List.of()), "close");
        List<String> info = new ArrayList<>(List.of("&7Qui puoi chiedere aiuto allo staff.", "",
                "&fAssistenza generale: &7domande e dubbi",
                "&fSegnala bug: &7errori e problemi di acquisto",
                "&fSegnala player: &7chi infrange le regole", "",
                "&7Puoi avere &fun solo ticket aperto&7 alla volta.",
                "&7Per contestare un ban scrivi sul nostro Discord o Telegram."));
        put(h, 7, pane("&6informazioni", info), null);
        put(h, 8, pane("&6informazioni", info), null);

        String[] names = {"&bassistenza generale", "&dsegnala bug", "&6segnala player"};
        String[][] lores = {
                {"&7Hai una domanda o non sai come fare qualcosa?", "&7Chiedi aiuto allo staff.", "", "&eclicca per aprire un ticket"},
                {"&7Hai trovato un errore o un problema con un acquisto?", "&7Raccontacelo.", "", "&eclicca per aprire un ticket"},
                {"&7Un giocatore sta infrangendo le regole?", "&7Scrivi chi e cosa ha fatto.", "", "&eclicca per aprire un ticket"}};
        for (int c = 0; c < 3; c++) {
            for (int r = 1; r <= 3; r++) {
                for (int k = 0; k < 3; k++) {
                    put(h, r * 9 + c * 3 + k, pane(names[c], Arrays.asList(lores[c])), "create:" + CATS[c]);
                }
            }
        }
        if (last != null) {
            List<String> lore = new ArrayList<>();
            lore.add("&7stato: &f" + STATUS_NAME[statusOf(last)]);
            lore.add("&7sezione: &f" + CAT_LABEL[catIndex(last.category)]);
            lore.add("&7ultimo messaggio: &f" + DATE.format(Instant.ofEpochMilli(Math.max(last.updated, last.created))));
            int un = unread(last, id, false);
            if (un > 0) {
                lore.add("");
                lore.add("&e" + un + (un == 1 ? " messaggio non letto" : " messaggi non letti"));
            }
            if (!last.open && last.rating == 0) {
                lore.add("");
                lore.add("&6devi ancora valutare il ticket");
            }
            lore.add("");
            lore.add("&eclicca per aprire la chat");
            for (int s = 36; s <= 44; s++) {
                put(h, s, pane("&fil tuo ultimo ticket &7#" + last.id, lore), "last");
            }
        }
        List<String> mine = List.of("&7Tutti i ticket che hai aperto.", "", "&eclicca per vederli");
        for (int s = 47; s <= 51; s++) {
            put(h, s, pane("&bi miei ticket", mine), "list");
        }
        open(p, h);
    }

    private void seeFace(Player p) {
        fetchFace(p);
    }

    private void open(Player p, Holder h) {
        UUID id = p.getUniqueId();
        switching.add(id);
        try {
            p.openInventory(h.inventory);
        } finally {
            switching.remove(id);
        }
    }

    // ------------------------------------------------------------------
    //  I miei ticket (ticket-2)
    // ------------------------------------------------------------------

    private List<Ticket> listFor(UUID owner, int filter) {
        List<Ticket> out = new ArrayList<>();
        for (Ticket t : tickets.values()) {
            if (!t.owner.equals(owner)) {
                continue;
            }
            boolean okay = switch (filter) {
                case 1 -> t.open;
                case 2 -> !t.open;
                case 3 -> t.category.equals("assistenza");
                case 4 -> t.category.equals("bug");
                case 5 -> t.category.equals("report");
                default -> true;
            };
            if (okay) {
                out.add(t);
            }
        }
        out.sort((a, b) -> Long.compare(b.created, a.created));
        return out;
    }

    public void openList(Player p, int page, int filter) {
        restoreInventory(p);
        List<Ticket> list = listFor(p.getUniqueId(), filter);
        int pages = Math.max(1, (list.size() + 20) / 21);
        page = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder("list");
        h.page = page;
        h.filter = filter;

        GuiTitle g = newTitle(IMG_LIST);
        tinyText(g, FILTERS[filter], 95, true, '', '');
        navText(g, page + 1, pages, 96);
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());

        put(h, 1, pane("&cindietro", List.of()), "back");
        List<String> fl = new ArrayList<>();
        fl.add("&7scegli cosa vedere:");
        fl.add(" ");
        for (int i = 0; i < FILTERS.length; i++) {
            fl.add(i == filter ? "&a» " + FILTERS[i] : "&8" + FILTERS[i]);
        }
        fl.add(" ");
        fl.add("&eclicca per cambiare");
        put(h, 7, pane("&bfiltra per", fl), "filter");
        put(h, 38, pane("&7pagina precedente", List.of()), "page:-1");
        put(h, 42, pane("&7pagina successiva", List.of()), "page:1");
        for (int i = 0; i < 21; i++) {
            int idx = page * 21 + i;
            if (idx >= list.size()) {
                break;
            }
            Ticket t = list.get(idx);
            List<String> lore = new ArrayList<>();
            lore.add("&7stato: &f" + STATUS_NAME[statusOf(t)]);
            lore.add("&7sezione: &f" + CAT_LABEL[catIndex(t.category)]);
            lore.add("&7aperto il: &f" + DATE.format(Instant.ofEpochMilli(t.created)));
            lore.add("&7messaggi: &f" + t.msgs.size());
            lore.add("");
            lore.add("&eclicca per aprire la chat");
            put(h, 10 + (i / 7) * 9 + (i % 7), ticketItem(t, "&f#" + t.id + " &7- " + CAT_SHORT[catIndex(t.category)], lore), "open:" + t.id);
        }
        open(p, h);
    }

    // ------------------------------------------------------------------
    //  Chat (ticket-3)
    // ------------------------------------------------------------------

    private boolean canView(Player p, Ticket t) {
        return t.owner.equals(p.getUniqueId()) || isStaff(p);
    }

    private boolean staffSide(Player p, Ticket t) {
        return isStaff(p) && !t.owner.equals(p.getUniqueId());
    }

    /** Codice univoco del ticket (6 caratteri, senza lettere che si confondono). */
    private String newCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.util.concurrent.ThreadLocalRandom r = java.util.concurrent.ThreadLocalRandom.current();
        while (true) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
            }
            String c = sb.toString();
            boolean used = false;
            for (Ticket o : tickets.values()) {
                if (c.equals(o.code)) {
                    used = true;
                    break;
                }
            }
            if (!used) {
                return c;
            }
        }
    }

    private List<Line> layout(Ticket t, UUID viewer, boolean staffView) {
        List<Line> lines = new ArrayList<>();
        for (int i = 0; i < t.msgs.size(); i++) {
            Msg m = t.msgs.get(i);
            if (!visible(m, staffView)) {
                continue;
            }
            int side = (m.kind.equals("sys") || m.kind.equals("note")) ? 2
                    : (m.author != null && m.author.equals(viewer)) ? 1 : 0;
            String cleaned = clean(m.text);
            List<String> parts;
            int paren = m.kind.equals("sys") ? cleaned.indexOf(" (da ") : -1;
            if (paren > 0) {
                // a capo forzato prima di "(da ...)" nei messaggi di sistema
                parts = new ArrayList<>(wrap(cleaned.substring(0, paren), MAX_TEXT));
                parts.addAll(wrap(cleaned.substring(paren + 1), MAX_TEXT));
            } else {
                parts = wrap(cleaned, MAX_TEXT);
            }
            for (int k = 0; k < parts.size(); k++) {
                lines.add(new Line(i, k, parts.size(), parts.get(k), side, m));
            }
        }
        return lines;
    }

    private static char bubbleChar(int scheme, int variant, int piece) {
        return (char) (0xE100 + scheme * 64 + variant * 16 + piece);
    }

    private static final int MSG_GAP = 3;

    /** Primo rigo visibile quando l'ultimo e' end-1: si riempie dal basso, 12px a riga e 3px tra un messaggio e l'altro. */
    private static int firstVisible(List<Line> lines, int end) {
        int h = 0;
        int i = end - 1;
        while (i >= 0) {
            int need = ROW_H + (i < end - 1 && lines.get(i).msg() != lines.get(i + 1).msg() ? MSG_GAP : 0);
            if (h + need > ROWS * ROW_H) {
                break;
            }
            h += need;
            i--;
        }
        return i + 1;
    }

    /** Quanti righi stanno nella chat partendo dal primo messaggio (serve per sapere fin dove si puo salire). */
    private static int fitFromTop(List<Line> lines) {
        int h = 0;
        int n = 0;
        for (int i = 0; i < lines.size(); i++) {
            int need = ROW_H + (i > 0 && lines.get(i).msg() != lines.get(i - 1).msg() ? MSG_GAP : 0);
            if (h + need > ROWS * ROW_H) {
                break;
            }
            h += need;
            n++;
        }
        return n;
    }

    private Component chatTitle(Ticket t, Player viewer, boolean staffView, int scroll) {
        return chatTitleG(t, viewer, staffView, scroll).build();
    }

    /** Moltiplica i colori del titolo per #555555: stessa schermata, scurita. */
    private static Component tint(Component c, TextColor inherited) {
        TextColor col = c.color() != null ? c.color() : inherited;
        TextColor nc = col == null ? TextColor.color(0x555555)
                : TextColor.color(col.red() * 85 / 255, col.green() * 85 / 255, col.blue() * 85 / 255);
        List<Component> kids = new ArrayList<>();
        for (Component k : c.children()) {
            kids.add(tint(k, col));
        }
        return c.color(nc).children(kids);
    }

    /**
     * Titolo di un popup aperto dalla chat: tutta la chat (barra, tag, bolle, testi) scurita, poi il popup a colori pieni.
     * Dopo la prima immagine il cursore calcolato e' 7 px indietro rispetto a quello reale (179 contro 186):
     * per tornare a -15 reale il popup va messo a IMG (-22).
     */
    private GuiTitle popupOverChat(Player p, Ticket t, int scroll, String image) {
        GuiTitle c = chatTitleG(t, p, staffSide(p, t), scroll);
        GuiTitle g = new GuiTitle(plugin);
        g.atComponent(0, tint(c.build(), null), c.cursor());
        g.at(IMG, "&f" + image, "", 179);
        return g;
    }

    private GuiTitle chatTitleG(Ticket t, Player viewer, boolean staffView, int scroll) {
        String image = staffView ? IMG_CHAT : IMG_PCHAT;
        GuiTitle g = newTitle(image);

        // barra in alto: stato e sezione
        int si = statusOf(t);
        int ci = 3 + catIndex(t.category);
        g.at(37 + IMG, "&f" + (char) (0x3f20 + si), "", CHIP_W[si] + 1);
        g.at(37 + CHIP_W[si] + 2 + IMG, "&f" + (char) (0x3f20 + ci), "", CHIP_W[ci] + 1);
        tinyText(g, t.code, 94, true, '', '');

        List<Line> lines = layout(t, viewer.getUniqueId(), staffView);
        // faccia 2D del player nel primo slot della barra
        drawFace(g, faceOf(t.owner), 15, 33, 2);
        if (!staffView && !t.open) {
            int filled = t.rating > 0 ? t.rating : (t.rating == 0 ? stars.getOrDefault(viewer.getUniqueId(), 0) : 0);
            drawStars(g, filled);
        }

        int total = lines.size();
        int end = total - scroll;
        int start = firstVisible(lines, end);
        int count = end - start;
        int[] ys = new int[count];
        int cy = ROW_Y0 + ROWS * ROW_H;
        for (int k = count - 1; k >= 0; k--) {
            if (k < count - 1 && lines.get(start + k).msg() != lines.get(start + k + 1).msg()) {
                cy -= MSG_GAP;
            }
            cy -= ROW_H;
            ys[k] = cy;
        }
        Map<Integer, Integer> maxW = new HashMap<>();
        for (Line l : lines) {
            maxW.merge(l.msg(), textWidth(l.text()), Math::max);
        }
        for (int k = 0; k < count; k++) {
            Line ln = lines.get(start + k);
            if (ln.side() == 3) {
                continue;
            }
            int yb = ys[k];
            int variant = ln.n() == 1 ? 0 : ln.idx() == 0 ? 1 : ln.idx() == ln.n() - 1 ? 3 : 2;
            int tw = Math.max(8, maxW.getOrDefault(ln.msg(), 8));
            int own = textWidth(ln.text());
            int bw = tw + 6;
            int xb = ln.side() == 1 ? VX1 - bw - 1 : ln.side() == 2 ? (VX0 + VX1) / 2 - bw / 2 : VX0 + 11;
            StringBuilder sb = new StringBuilder();
            sb.append(bubbleChar(ln.side(), variant, 0)).append('');
            int remaining = tw;
            for (int i = 6; i >= 0; i--) {
                int w = 1 << i;
                while (remaining >= w) {
                    sb.append(bubbleChar(ln.side(), variant, 2 + i)).append('');
                    remaining -= w;
                }
            }
            sb.append(bubbleChar(ln.side(), variant, 1)).append('');
            g.atComponent(xb + IMG, glyph(sb.toString(), tk(yb), 0xFFFFFF), bw);
            g.atComponent(xb + 3 + IMG, glyph(ln.text(), tk(yb + 2), TEXT_RGB[ln.side()]), own);
            if (ln.side() == 0 && ln.idx() == 0) {
                drawFace(g, faceOf(ln.m().author), VX0 + 1, yb + 2, 1);
            }
        }
        return g;
    }

    public void openChat(Player p, int ticketId, int scroll) {
        Ticket t = tickets.get(ticketId);
        if (t == null || !canView(p, t)) {
            err(p, "ticket non trovato");
            return;
        }
        seeFace(p);
        boolean staffView = staffSide(p, t);
        UUID id = p.getUniqueId();
        List<Line> lines = layout(t, id, staffView);
        int maxScroll = Math.max(0, lines.size() - fitFromTop(lines));
        scroll = Math.max(0, Math.min(scroll, maxScroll));
        t.read.put(id, t.msgs.size());
        chatScroll.put(id, scroll);

        Holder h = new Holder("chat");
        h.ticketId = ticketId;
        h.scroll = scroll;
        h.viewer = p;
        h.inventory = Bukkit.createInventory(h, SIZE, chatTitle(t, p, staffView, scroll));

        hideInventory(p);
        put(h, 8, pane("&cindietro", List.of()), "back");
        put(h, 0, pane("&f" + t.ownerName, List.of("&eclicca per aprire il profilo")), "profile");
        for (int s : SLOTS_UP) {
            put(h, s, pane("&7sali", List.of("&7messaggi piu vecchi")), "scroll:1");
        }
        for (int s : SLOTS_DOWN) {
            put(h, s, pane("&7scendi", List.of("&7messaggi piu recenti")), "scroll:-1");
        }
        List<String> wl = List.of("&eclick sinistro:", t.open ? "&fscrivi un messaggio" : "&cticket chiuso",
                "&eclick destro:", "&flink del ticket");
        for (int s : SLOTS_WRITE) {
            put(h, s, pane(t.open ? "&fscrivi messaggio" : "&7ticket chiuso", wl), "write");
        }
        if (staffView) {
            // funzioni dello staff: ogni icona occupa 2 slot (uno sopra e uno sotto)
            List<String> closeLore = List.of("&7Chiude il ticket.", "&7Il player viene avvisato e deve valutarlo.", "", "&eclicca per chiudere");
            placeBottom(h, p, 0, pane("&cchiudi ticket", closeLore), t.open ? "closeticket" : null);
            placeBottom(h, p, 1, pane("&dcambia sezione", List.of("&7Sposta il ticket in un'altra sezione,", "&7per esempio da Bug ad Assistenza.", "", "&eclicca per scegliere")), "cat");
            placeBottom(h, p, 2, pane("&6tagga staffer", List.of("&7Dai accesso al ticket a un altro staffer.", "&7Devi scrivergli un messaggio.", "", "&eclicca per scegliere")), "tag");
            for (int i = 0; i < CANNED.length; i++) {
                String key = CANNED[i];
                List<String> lore = new ArrayList<>();
                lore.add("&7messaggio che viene inviato:");
                lore.add(" ");
                lore.add("&f" + fillCanned(key, t));
                if (key.equals("chiusura")) {
                    lore.add("");
                    lore.add("&7Se il player non risponde entro &f" + autoCloseHours() + "h&7 il ticket si chiude da solo.");
                }
                lore.add("");
                lore.add("&eclicca per inviare");
                placeBottom(h, p, 3 + i, pane(cannedName(key), lore), t.open ? "canned:" + key : null);
            }
        } else {
            playerFunctions(h, p, t);
        }
        open(p, h);
    }

    /** Parte bassa della chat del player (ticket-7): lucchetto, 5 stelle, spunta verde. */
    private void playerFunctions(Holder h, Player p, Ticket t) {
        boolean pending = !t.open && t.rating == 0 && t.owner.equals(p.getUniqueId());
        int sel = stars.getOrDefault(p.getUniqueId(), 0);

        List<String> lockLore = t.open
                ? List.of("&7Chiude il ticket.", "&7Dopo dovrai valutarlo da 1 a 5 stelle", "&7prima di poterne aprire un altro.", "", "&eclicca per chiudere")
                : List.of("&7Questo ticket e chiuso.");
        ItemStack lock = pane(t.open ? "&cchiudi ticket" : "&7ticket chiuso", lockLore);
        for (int s : new int[]{63, 64, 72, 73}) {
            put(h, s, lock, t.open ? "closeticket" : null);
        }
        for (int k = 0; k < 5; k++) {
            ItemStack star;
            if (pending) {
                star = pane("&e" + (k + 1) + (k == 0 ? " stella" : " stelle"),
                        List.of("&7Valuta il ticket con &e" + (k + 1) + "&7 su 5.", "", "&eclicca per scegliere"));
            } else if (t.open) {
                star = pane("&7valutazione", List.of("&7Potrai valutare il ticket", "&7quando sara chiuso."));
            } else if (t.rating > 0) {
                star = pane("&7valutazione: &e" + t.rating + " su 5", List.of(t.ratedAuto ? "&7assegnata in automatico" : "&7grazie per aver valutato!"));
            } else {
                star = pane("&7valutazione", List.of("&7non richiesta per questo ticket"));
            }
            String action = pending ? "star:" + k : null;
            put(h, 66 + k, star, action);
            put(h, 75 + k, star, action);
        }
        ItemStack check;
        if (pending) {
            check = pane("&aconferma valutazione", sel > 0
                    ? List.of("&7Stelle scelte: &e" + sel + " su 5", "", "&eclicca per confermare")
                    : List.of("&7Scegli prima le stelle da 1 a 5."));
        } else {
            check = pane("&7valutazione", List.of(t.open ? "&7Disponibile dopo la chiusura." : "&7Gia valutato."));
        }
        for (int s : new int[]{71, 80}) {
            put(h, s, check, pending ? "rate" : null);
        }
    }

    private void placeBottom(Holder h, Player p, int col, ItemStack stack, String action) {
        put(h, 63 + col, stack, action);
        put(h, 72 + col, stack, action);
        putBottom(p, 63 + col, stack);
        putBottom(p, 72 + col, stack);
    }

    /** Aggiorna al volo la chat aperta da questo player (messaggio nuovo o ticket chiuso). */
    private void refreshChat(Player p, Ticket t) {
        InventoryView view = p.getOpenInventory();
        if (view.getTopInventory().getHolder() instanceof Holder h && h.kind.equals("chat") && h.ticketId == t.id) {
            openChat(p, t.id, h.scroll);
        }
    }

    // ------------------------------------------------------------------
    //  Creare, scrivere, chiudere
    // ------------------------------------------------------------------

    private void createTicket(Player p, String cat, String text) {
        Ticket pend = pendingRating(p.getUniqueId());
        if (pend != null) {
            err(p, "prima devi valutare il ticket #" + pend.id);
            openChat(p, pend.id, 0);
            return;
        }
        if (openTicketOf(p.getUniqueId()) != null) {
            err(p, "hai gia un ticket aperto: chiudilo prima di aprirne un altro");
            return;
        }
        String msg = clean(text);
        if (msg.isEmpty()) {
            err(p, "il messaggio e vuoto");
            return;
        }
        Ticket t = new Ticket();
        t.id = nextId++;
        t.code = newCode();
        t.owner = p.getUniqueId();
        t.ownerName = p.getName();
        t.category = cat;
        t.created = System.currentTimeMillis();
        t.updated = t.created;
        tickets.put(t.id, t);
        addMsg(t, p, false, "sys", "Ticket aperto: " + CAT_LABEL[catIndex(cat)], false);
        addMsg(t, p, false, "msg", msg, false);
        save();
        ok(p, "ticket #" + t.id + " aperto, lo staff ti rispondera appena possibile");
        Component alert = plugin.color("&6[ticket] &fnuovo ticket &e#" + t.id + " &7(" + CAT_SHORT[catIndex(cat)] + ") di &f" + p.getName() + " &7- clicca per aprirlo")
                .clickEvent(ClickEvent.runCommand("/ticket " + t.id));
        for (Player s : Bukkit.getOnlinePlayers()) {
            if (isStaff(s) && !s.equals(p)) {
                s.sendMessage(alert);
                plugin.playConfiguredSound(s.getLocation(), "message-sounds.success");
            }
        }
        openChat(p, t.id, 0);
    }

    private Msg addMsg(Ticket t, Player author, boolean staff, String kind, String text, boolean notify) {
        Msg m = new Msg();
        m.author = author == null ? null : author.getUniqueId();
        m.name = author == null ? "sistema" : author.getName();
        m.staff = staff;
        m.text = text;
        m.time = System.currentTimeMillis();
        m.kind = kind;
        t.msgs.add(m);
        t.updated = m.time;
        if (author != null) {
            t.read.put(author.getUniqueId(), t.msgs.size());
        }
        if (notify) {
            save();
            deliver(t, m);
        }
        return m;
    }

    /** Avvisa chi deve sapere del messaggio e aggiorna al volo le chat aperte. */
    private void deliver(Ticket t, Msg m) {
        Set<UUID> recipients = new LinkedHashSet<>();
        recipients.add(t.owner);
        recipients.addAll(t.staff);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            InventoryView view = pl.getOpenInventory();
            boolean viewing = view.getTopInventory().getHolder() instanceof Holder h && h.kind.equals("chat") && h.ticketId == t.id;
            boolean isAuthor = m.author != null && m.author.equals(pl.getUniqueId());
            if (viewing) {
                if (m.kind.equals("note") && !isStaff(pl)) {
                    continue;
                }
                refreshChat(pl, t);
                continue;
            }
            if (isAuthor || !recipients.contains(pl.getUniqueId())) {
                continue;
            }
            if (m.kind.equals("note") && t.owner.equals(pl.getUniqueId())) {
                continue;
            }
            Component alert = plugin.color("&6[ticket] &fnuovo messaggio nel ticket &e#" + t.id + " &7- clicca per aprirlo")
                    .clickEvent(ClickEvent.runCommand("/ticket " + t.id));
            pl.sendMessage(alert);
            plugin.playConfiguredSound(pl.getLocation(), "message-sounds.success");
        }
    }

    private boolean postMessage(Player p, Ticket t, String text, String kind) {
        if (!t.open) {
            err(p, "il ticket e chiuso");
            return false;
        }
        String msg = clean(text);
        if (msg.isEmpty()) {
            err(p, "il messaggio e vuoto");
            return false;
        }
        boolean staff = staffSide(p, t);
        UUID id = p.getUniqueId();
        if (staff) {
            if (!t.staff.contains(id)) {
                if (staffOpenCount(id) >= maxStaffOpen()) {
                    err(p, "hai gia " + maxStaffOpen() + " ticket aperti: chiudine uno prima di prenderne altri");
                    return false;
                }
                t.staff.add(id);
            }
            t.lastStaff = id;
            t.lastStaffName = p.getName();
        } else {
            t.autoClose = 0;
        }
        addMsg(t, p, staff, kind, msg, true);
        return true;
    }

    private void sendCanned(Player p, Ticket t, String key) {
        if (!t.open) {
            err(p, "il ticket e chiuso");
            return;
        }
        long before = t.autoClose;
        if (key.equals("chiusura")) {
            t.autoClose = System.currentTimeMillis() + autoCloseHours() * 3600_000L;
        }
        if (!postMessage(p, t, fillCanned(key, t), "canned")) {
            t.autoClose = before;
            return;
        }
        save();
        if (key.equals("prove")) {
            Player owner = Bukkit.getPlayer(t.owner);
            if (owner != null) {
                owner.sendMessage(plugin.color("&6[ticket] &fPer il ticket &e#" + t.id + " &fservono delle prove. ")
                        .append(plugin.color("&b&n[apri imgur]").clickEvent(ClickEvent.openUrl("https://imgur.com/upload"))));
            }
        }
    }

    private void closeTicket(Ticket t, Player by, String name, boolean auto) {
        if (!t.open) {
            return;
        }
        t.open = false;
        t.closedAt = System.currentTimeMillis();
        t.closedBy = name;
        t.closedByUuid = by == null ? null : by.getUniqueId();
        t.autoClose = 0;
        t.rating = 0;
        t.ratedAuto = false;
        t.rateDeadline = t.closedAt + rateHours() * 3600_000L;
        reminded.remove(t.id);
        String text = auto ? "Ticket chiuso in automatico: nessuna risposta entro " + autoCloseHours() + " ore"
                : "Ticket chiuso da " + name;
        Msg m = addMsg(t, by, false, "sys", text, false);
        m.name = name;
        save();
        Player owner = Bukkit.getPlayer(t.owner);
        if (owner != null) {
            if (by == null || !by.getUniqueId().equals(owner.getUniqueId())) {
                owner.sendMessage(plugin.color("&6[ticket] &fIl tuo ticket &e#" + t.id + " &fe stato chiuso."));
            }
            notifyRate(owner, t);
        }
        for (Player pl : Bukkit.getOnlinePlayers()) {
            refreshChat(pl, t);
        }
    }

    /** Conferma (ticket-6 per lo staff, ticket-8 per il player): what = close oppure rate. */
    private void openConfirm(Player p, Ticket t, String what, int scroll) {
        UUID id = p.getUniqueId();
        boolean staffView = staffSide(p, t);
        Holder h = new Holder("confirm");
        h.ticketId = t.id;
        h.mode = what;
        h.scroll = scroll;
        h.viewer = p;
        h.inventory = Bukkit.createInventory(h, SIZE, popupOverChat(p, t, scroll, staffView ? IMG_CONF_STAFF : IMG_CONF_PLAYER).build());
        hideInventory(p);
        List<String> yes = what.equals("close")
                ? List.of("&7Il ticket viene chiuso.", staffView ? "&7Il player viene avvisato e deve valutarlo." : "&7Dopo dovrai valutarlo con le stelle.", "", "&eclicca per confermare")
                : List.of("&7Valutazione: &e" + stars.getOrDefault(id, 0) + " su 5 stelle", "&7Dopo non potrai cambiarla.", "", "&eclicca per confermare");
        List<String> no = List.of("&7Torna indietro senza fare niente.", "", "&eclicca per annullare");
        for (int s : new int[]{46, 47, 48}) {
            put(h, s, pane("&aconferma", yes), "yes");
        }
        for (int s : new int[]{50, 51, 52}) {
            put(h, s, pane("&cannulla", no), "no");
        }
        open(p, h);
    }

    // ------------------------------------------------------------------
    //  Cambia sezione e tagga staffer (solo staff)
    // ------------------------------------------------------------------

    private void openCategory(Player p, Ticket t) {
        Holder h = new Holder("cat");
        h.ticketId = t.id;
        h.viewer = p;
        h.inventory = Bukkit.createInventory(h, SIZE, popupOverChat(p, t, chatScroll.getOrDefault(p.getUniqueId(), 0), IMG_CAT).build());
        hideInventory(p);
        put(h, 19, pane("&cindietro", List.of()), "chat");
        put(h, 25, pane("&6filtro", List.of("&7Sposta il ticket in un'altra sezione,",
                "&7per esempio da Bug ad Assistenza.", "&7Nella chat compare un avviso.")), null);
        int[][] cols = {{1, 2}, {3, 4, 5}, {6, 7}};
        for (int i = 0; i < 3; i++) {
            boolean cur = CATS[i].equals(t.category);
            for (int r = 3; r <= 5; r++) {
                for (int c : cols[i]) {
                    put(h, r * 9 + c, pane((cur ? "&a" : "&f") + CAT_LABEL[i],
                            List.of(cur ? "&7sezione attuale" : "&eclicca per spostare qui")), cur ? null : "setcat:" + CATS[i]);
                }
            }
        }
        open(p, h);
    }

    private static int gridSlot(int i) {
        return 19 + (i / 7) * 9 + (i % 7);
    }

    /** Popup ticket-5: X (slot 10), i (16), griglia 7x3, frecce (47 e 51), barra del titolo e numero pagina. */
    private Holder popup(Player p, String kind, int ticketId, String bar, int page, int pages, String backAction,
                         String backName, List<String> info, boolean hide, String bg) {
        Holder h = new Holder(kind);
        h.ticketId = ticketId;
        h.page = page;
        h.viewer = p;
        Ticket chatTicket = ticketId > 0 ? tickets.get(ticketId) : null;
        GuiTitle g = chatTicket != null && bg != null
                ? popupOverChat(p, chatTicket, chatScroll.getOrDefault(p.getUniqueId(), 0), IMG_ITEMS)
                : popupTitle(bg, IMG_ITEMS);
        tinyText(g, bar, POPUP_CX, true, BAR, BAR_SH);
        navText(g, page + 1, pages, POPUP_CX + 1, NAV2, NAV2_SH);
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());
        if (hide) {
            hideInventory(p);
        } else {
            restoreInventory(p);
        }
        put(h, 10, pane(backName, List.of()), backAction);
        put(h, 11, pane(backName, List.of()), backAction);   // la X disegnata sborda sullo slot accanto
        put(h, 16, pane("&6informazioni", info), null);
        put(h, 47, pane("&7pagina precedente", List.of()), "page:-1");
        put(h, 51, pane("&7pagina successiva", List.of()), "page:1");
        return h;
    }

    private void setCategory(Player p, Ticket t, String cat) {
        if (!t.open || t.category.equals(cat)) {
            openChat(p, t.id, 0);
            return;
        }
        t.category = cat;
        addMsg(t, p, false, "sys", "Sezione cambiata in: " + CAT_LABEL[catIndex(cat)] + " (da " + p.getName() + ")", true);
        openChat(p, t.id, 0);
    }

    private static final java.util.regex.Pattern URL = java.util.regex.Pattern.compile("https?://[^\\s]+");

    private static final String[] LINK_FILTERS = {"tutti", "mandati dal player", "mandati dallo staff"};

    private void openLinks(Player p, Ticket t, int page) {
        openLinks(p, t, page, 0);
    }

    private void openLinks(Player p, Ticket t, int page, int filter) {
        boolean staffView = staffSide(p, t);
        java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
        for (Msg m : t.msgs) {
            if (!visible(m, staffView)) {
                continue;
            }
            boolean system = m.kind.equals("sys");
            if (filter == 1 && (m.staff || system)) {
                continue;
            }
            if (filter == 2 && (!m.staff || system)) {
                continue;
            }
            java.util.regex.Matcher mt = URL.matcher(m.text);
            while (mt.find()) {
                found.add(mt.group());
            }
        }
        List<String> all = new ArrayList<>(found);
        int pages = Math.max(1, (all.size() + 20) / 21);
        page = Math.max(0, Math.min(page, pages - 1));
        Holder h = popup(p, "links", t.id, "link", page, pages, "chat", "&cindietro",
                List.of("&7I link scritti nei messaggi del ticket.", "&7Clicca un link per aprirlo."), true, staffView ? IMG_CHAT : IMG_PCHAT);
        h.links = all;
        h.filter = filter;
        List<String> fl = new ArrayList<>();
        fl.add("&7scegli cosa vedere:");
        fl.add(" ");
        for (int i = 0; i < LINK_FILTERS.length; i++) {
            fl.add(i == filter ? "&a» " + LINK_FILTERS[i] : "&8" + LINK_FILTERS[i]);
        }
        fl.add(" ");
        fl.add("&eclicca per cambiare");
        put(h, 15, pane("&bfiltra per", fl), "linkfilter");
        for (int i = 0; i < 21; i++) {
            int idx = page * 21 + i;
            if (idx >= all.size()) {
                break;
            }
            String url = all.get(idx);
            List<String> lore = new ArrayList<>();
            for (int k = 0; k < url.length(); k += 28) {
                lore.add("&7" + url.substring(k, Math.min(url.length(), k + 28)).replace('&', ' '));
            }
            lore.add(" ");
            lore.add("&eclicca per aprirlo");
            put(h, gridSlot(i), linkItem("&blink &7#" + (idx + 1), lore), "openlink:" + idx);
        }
        open(p, h);
    }

    private void openTag(Player p, Ticket t, int page) {
        List<UUID> cand = new ArrayList<>();
        for (UUID u : staffSeen.keySet()) {
            if (u.equals(p.getUniqueId()) || t.staff.contains(u) || u.equals(t.owner)) {
                continue;
            }
            cand.add(u);
        }
        int pages = Math.max(1, (cand.size() + 20) / 21);
        page = Math.max(0, Math.min(page, pages - 1));
        Holder h = popup(p, "tag", t.id, "tagga staffer", page, pages, "chat", "&cindietro",
                List.of("&7Dai accesso al ticket a un altro staffer.", "&7Poi gli scrivi un messaggio."), true, IMG_CHAT);
        for (int i = 0; i < 21; i++) {
            int idx = page * 21 + i;
            if (idx >= cand.size()) {
                break;
            }
            UUID u = cand.get(idx);
            int cnt = staffOpenCount(u);
            boolean full = cnt >= maxStaffOpen();
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            OfflinePlayer off = Bukkit.getOfflinePlayer(u);
            meta.setOwningPlayer(off);
            meta.displayName(plugin.text((full ? "&c" : "&f") + staffSeen.get(u)));
            meta.lore(loreComponents(List.of("&7ticket aperti: &f" + cnt + "/" + maxStaffOpen(),
                    Bukkit.getPlayer(u) != null ? "&aonline" : "&7offline", " ",
                    full ? "&cha gia troppi ticket aperti" : "&eclicca per taggarlo")));
            head.setItemMeta(meta);
            put(h, gridSlot(i), head, full ? "deny" : "tagpick:" + u);
        }
        open(p, h);
    }

    private void doTag(Player p, Ticket t, UUID target, String note) {
        if (!t.open) {
            return;
        }
        if (staffOpenCount(target) >= maxStaffOpen()) {
            err(p, "quello staffer ha gia " + maxStaffOpen() + " ticket aperti");
            openChat(p, t.id, 0);
            return;
        }
        String name = staffSeen.getOrDefault(target, "staffer");
        t.staff.add(target);
        t.staff.add(p.getUniqueId());
        addMsg(t, p, false, "note", p.getName() + " ha taggato " + name + ": " + clean(note), false);
        save();
        Player tp = Bukkit.getPlayer(target);
        if (tp != null) {
            tp.sendMessage(plugin.color("&6[ticket] &f" + p.getName() + " ti ha taggato nel ticket &e#" + t.id + "&f: &7" + clean(note))
                    .clickEvent(ClickEvent.runCommand("/ticket " + t.id)));
            plugin.playConfiguredSound(tp.getLocation(), "message-sounds.success");
        }
        ok(p, "hai taggato " + name);
        for (Player pl : Bukkit.getOnlinePlayers()) {
            refreshChat(pl, t);
        }
        openChat(p, t.id, 0);
    }

    // ------------------------------------------------------------------
    //  Liste dello staff (senza texture)
    // ------------------------------------------------------------------

    public void openStaffList(Player p, String mode, int page) {
        List<Ticket> list = new ArrayList<>();
        UUID me = p.getUniqueId();
        for (Ticket t : tickets.values()) {
            boolean okay = switch (mode) {
                case "mine" -> t.open && t.staff.contains(me);
                case "closed" -> !t.open && me.equals(t.closedByUuid);
                default -> t.open;
            };
            if (okay) {
                list.add(t);
            }
        }
        list.sort((a, b) -> Long.compare(b.updated, a.updated));
        int pages = Math.max(1, (list.size() + 20) / 21);
        page = Math.max(0, Math.min(page, pages - 1));
        String bar = switch (mode) {
            case "mine" -> "tuoi";
            case "closed" -> "chiusi";
            default -> "aperti";
        };
        Holder h = popup(p, "stafflist", 0, bar, page, pages, "close", "&cchiudi",
                List.of("&7Ticket: &f" + list.size(), "&7Max &f" + maxStaffOpen() + "&7 ticket tuoi alla volta."), true, IMG_CHAT);
        h.mode = mode;
        String next = mode.equals("open") ? "mine" : mode.equals("mine") ? "closed" : "open";
        List<String> modes = List.of("&7scegli cosa vedere:", " ",
                (mode.equals("open") ? "&a» " : "&8") + "tutti gli aperti",
                (mode.equals("mine") ? "&a» " : "&8") + "i tuoi aperti",
                (mode.equals("closed") ? "&a» " : "&8") + "chiusi da te", " ",
                "&7ticket: &f" + list.size(), " ", "&eclicca per cambiare");
        for (int s = 12; s <= 16; s++) {
            put(h, s, pane("&bfiltra per", modes), "mode:" + next);
        }
        for (int i = 0; i < 21; i++) {
            int idx = page * 21 + i;
            if (idx >= list.size()) {
                break;
            }
            Ticket t = list.get(idx);
            int st = statusOf(t);
            List<String> lore = new ArrayList<>();
            lore.add("&7player: &f" + t.ownerName);
            lore.add("&7sezione: &f" + CAT_LABEL[catIndex(t.category)]);
            lore.add("&7stato: &f" + STATUS_NAME[st]);
            lore.add("&7ultimo messaggio: &f" + DATE.format(Instant.ofEpochMilli(t.updated)));
            if (!t.staff.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (UUID u : t.staff) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(staffSeen.getOrDefault(u, "?"));
                }
                lore.add("&7staff: &f" + sb);
            }
            int un = unread(t, me, true);
            if (un > 0) {
                lore.add("&e" + un + " non letti");
            }
            lore.add("");
            lore.add("&eclicca per aprire");
            put(h, gridSlot(i), ticketItem(t, "&f#" + t.id + " &7- " + CAT_SHORT[catIndex(t.category)], lore), "open:" + t.id);
        }
        open(p, h);
    }

    // ------------------------------------------------------------------
    //  Scrivere: incudine classica o chat
    // ------------------------------------------------------------------

    private void askText(Player p, String title, String chatHint, Consumer<String> onSend, Runnable onCancel) {
        restoreInventory(p);
        UUID id = p.getUniqueId();
        AnvilSession s = new AnvilSession();
        s.onSend = onSend;
        s.onCancel = onCancel;
        GuiTitle at = newTitle(IMG_ANVIL, plugin.getConfig().getInt("tickets.anvil-shift", -52));
        AnvilView view = MenuType.ANVIL.builder().title(at.build()).build(p);
        s.view = view;
        // oggetti invisibili: l'immagine dell'incudine disegna le icone (casa, scambio, invia)
        view.setItem(0, pane(" ", List.of("&ctorna indietro")));
        view.setItem(1, pane("&escrivi in chat",
                List.of("&7Per i messaggi lunghi: scrivi in chat", "&7(oppure scrivi &fannulla&7 per tornare indietro).")));
        anvils.put(id, s);
        switching.add(id);
        try {
            p.openInventory(view);
        } finally {
            switching.remove(id);
        }
        p.sendMessage(plugin.color("&7Scrivi il messaggio nella barra e clicca sul tasto verde per inviare. L'incudine accetta massimo 50 caratteri: per messaggi piu lunghi usa lo scambio e scrivi in chat."));
    }

    private void finishAnvil(Player p, AnvilSession s, String text) {
        s.done = true;
        p.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> s.onSend.accept(text));
    }

    private void anvilToChat(Player p, AnvilSession s) {
        s.done = true;
        UUID id = p.getUniqueId();
        p.closeInventory();
        ChatInput ci = new ChatInput();
        ci.onSend = s.onSend;
        ci.onCancel = s.onCancel;
        awaiting.put(id, ci);
        p.sendMessage(plugin.color("&6[ticket] &fScrivi il messaggio qui in chat, oppure scrivi &eannulla&f per annullare."));
    }

    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player p)) {
            return;
        }
        AnvilSession s = anvils.get(p.getUniqueId());
        if (s == null || event.getView().getTopInventory() != s.view.getTopInventory()) {
            return;
        }
        AnvilView av = (AnvilView) event.getView();
        String txt = av.getRenameText();
        s.text = txt == null ? "" : txt;
        event.setResult(pane("&ainvia", List.of("&7" + clean(s.text))));
        av.setRepairCost(0);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        ChatInput ci = awaiting.get(p.getUniqueId());
        if (ci == null) {
            return;
        }
        event.setCancelled(true);
        awaiting.remove(p.getUniqueId());
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (msg.equalsIgnoreCase("annulla")) {
                p.sendMessage(plugin.color("&7annullato"));
                ci.onCancel.run();
            } else {
                ci.onSend.accept(msg);
            }
        });
    }

    // ------------------------------------------------------------------
    //  Click, trascinamento, chiusura
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        AnvilSession s = anvils.get(p.getUniqueId());
        if (s != null && event.getView().getTopInventory() == s.view.getTopInventory()) {
            event.setCancelled(true);
            if (event.getRawSlot() == 2) {
                String txt = clean(s.view.getRenameText());
                if (txt.isEmpty()) {
                    err(p, "scrivi prima il messaggio");
                    return;
                }
                plugin.guiClick(p);
                finishAnvil(p, s, txt);
            } else if (event.getRawSlot() == 1) {
                plugin.guiClick(p);
                anvilToChat(p, s);
            } else if (event.getRawSlot() == 0) {
                plugin.guiClick(p);
                s.done = true;
                p.closeInventory();
                Bukkit.getScheduler().runTask(plugin, s.onCancel);
            }
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) {
            return;
        }
        event.setCancelled(true);
        String action = h.actions.get(event.getRawSlot());
        if (action == null) {
            return;
        }
        if (action.equals("deny")) {
            plugin.playConfiguredSound(p.getLocation(), "rewards-gui.sounds.error");
            return;
        }
        plugin.guiClick(p);
        if (action.equals("write") && event.isRightClick()) {
            action = "links";
        }
        handle(p, h, action);
    }

    private void handle(Player p, Holder h, String action) {
        UUID id = p.getUniqueId();
        Ticket t = tickets.get(h.ticketId);
        if (action.equals("close")) {
            // chiusura al tick dopo: chiudere dentro l'evento click puo' lasciare la gui aperta lato client
            Bukkit.getScheduler().runTask(plugin, () -> p.closeInventory());
        } else if (action.equals("back")) {
            if (h.kind.equals("list")) {
                openMain(p);
            } else if (h.kind.equals("chat")) {
                if (t != null && staffSide(p, t) && lastStaffListMode.containsKey(id)) {
                    openStaffList(p, lastStaffListMode.get(id), 0);
                } else {
                    openMain(p);
                }
            } else {
                openMain(p);
            }
        } else if (action.startsWith("create:")) {
            String cat = action.substring(7);
            Ticket pend = pendingRating(id);
            if (pend != null) {
                err(p, "prima devi valutare il ticket #" + pend.id);
                openChat(p, pend.id, 0);
                return;
            }
            if (openTicketOf(id) != null) {
                err(p, "hai gia un ticket aperto: chiudilo prima di aprirne un altro");
                return;
            }
            askText(p, "descrivi il problema", "", txt -> createTicket(p, cat, txt), () -> openMain(p));
        } else if (action.equals("last")) {
            Ticket last = lastTicketOf(id);
            if (last != null) {
                openChat(p, last.id, 0);
            }
        } else if (action.equals("list")) {
            openList(p, 0, 0);
        } else if (action.equals("filter")) {
            openList(p, 0, (h.filter + 1) % FILTERS.length);
        } else if (action.startsWith("page:")) {
            int d = Integer.parseInt(action.substring(5));
            if (h.kind.equals("stafflist")) {
                openStaffList(p, h.mode, h.page + d);
            } else if (h.kind.equals("links") && t != null) {
                openLinks(p, t, h.page + d, h.filter);
            } else if (h.kind.equals("tag") && t != null) {
                openTag(p, t, h.page + d);
            } else {
                openList(p, h.page + d, h.filter);
            }
        } else if (action.startsWith("open:")) {
            int tid = Integer.parseInt(action.substring(5));
            if (h.kind.equals("stafflist")) {
                lastStaffListMode.put(id, h.mode);
            } else {
                lastStaffListMode.remove(id);
            }
            openChat(p, tid, 0);
        } else if (action.startsWith("mode:")) {
            openStaffList(p, action.substring(5), 0);
        } else if (action.startsWith("scroll:")) {
            if (t != null) {
                openChat(p, t.id, h.scroll + Integer.parseInt(action.substring(7)));
            }
        } else if (t == null) {
            return;
        } else if (action.equals("profile")) {
            p.sendMessage(plugin.text("&7Il profilo del giocatore non e ancora disponibile."));
        } else if (action.equals("links")) {
            openLinks(p, t, 0);
        } else if (action.equals("linkfilter")) {
            openLinks(p, t, 0, (h.filter + 1) % LINK_FILTERS.length);
        } else if (action.startsWith("openlink:")) {
            int li = Integer.parseInt(action.substring(9));
            if (li >= 0 && li < h.links.size()) {
                String url = h.links.get(li);
                int pg = h.page;
                plugin.getLogger().info("[Tickets] openlink " + li + " -> " + url);
                p.closeInventory();
                // finestra 1.21 con il link; "annulla" riporta alla lista dei link
                Bukkit.getScheduler().runTaskLater(plugin, () -> TicketDialog.open(p,
                        List.of(new TicketDialog.Link(url.replaceFirst("^https?://", ""), url)), () -> openLinks(p, t, pg), isStaff(p)), 2L);
            }
        } else if (action.equals("write")) {
            if (!t.open) {
                return;
            }
            askText(p, "scrivi il messaggio", "", txt -> {
                postMessage(p, t, txt, "msg");
                openChat(p, t.id, 0);
            }, () -> openChat(p, t.id, h.scroll));
        } else if (action.equals("closeticket")) {
            if (t.open) {
                openConfirm(p, t, "close", h.scroll);
            }
        } else if (action.startsWith("canned:")) {
            sendCanned(p, t, action.substring(7));
        } else if (action.equals("cat")) {
            openCategory(p, t);
        } else if (action.startsWith("setcat:")) {
            setCategory(p, t, action.substring(7));
        } else if (action.equals("tag")) {
            openTag(p, t, 0);
        } else if (action.equals("chat")) {
            openChat(p, t.id, 0);
        } else if (action.startsWith("tagpick:")) {
            UUID target = UUID.fromString(action.substring(8));
            askText(p, "messaggio per lo staffer", "", txt -> doTag(p, t, target, txt), () -> openChat(p, t.id, 0));
        } else if (action.startsWith("star:")) {
            if (!t.open && t.rating == 0 && t.owner.equals(id)) {
                stars.put(id, Integer.parseInt(action.substring(5)) + 1);
                openChat(p, t.id, h.scroll);
            }
        } else if (action.equals("rate")) {
            if (stars.getOrDefault(id, 0) < 1) {
                err(p, "scegli prima quante stelle dare");
            } else {
                openConfirm(p, t, "rate", h.scroll);
            }
        } else if (action.equals("yes")) {
            if (h.mode.equals("close")) {
                if (t.open) {
                    closeTicket(t, p, p.getName(), false);
                }
            } else if (h.mode.equals("rate")) {
                rateTicket(p, t, stars.getOrDefault(id, 0));
            }
            openChat(p, t.id, 0);
        } else if (action.equals("no")) {
            openChat(p, t.id, h.scroll);
        }
    }

    private final Map<UUID, String> lastStaffListMode = new HashMap<>();
    /** Scroll della chat che il player stava guardando: serve a ridisegnarla scurita sotto i popup. */
    private final Map<UUID, Integer> chatScroll = new HashMap<>();

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player p && anvils.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** Gui aperte dalla chat: l'inventario del player resta nascosto finche non le chiude. */
    private static boolean hidesInventory(String kind) {
        return kind.equals("stafflist") || kind.equals("chat") || kind.equals("cat") || kind.equals("links") || kind.equals("tag") || kind.equals("confirm");
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) {
            return;
        }
        UUID id = p.getUniqueId();
        AnvilSession s = anvils.get(id);
        if (s != null && event.getView().getTopInventory() == s.view.getTopInventory()) {
            event.getView().setItem(0, null);
            event.getView().setItem(1, null);
            event.getView().setItem(2, null);
            if (switching.contains(id)) {
                return;
            }
            anvils.remove(id);
            if (!s.done) {
                Bukkit.getScheduler().runTask(plugin, s.onCancel);
            }
            return;
        }
        if (switching.contains(id)) {
            return;
        }
        if (event.getInventory().getHolder() instanceof Holder h && hidesInventory(h.kind)) {
            restoreInventory(p);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        restoreInventory(p);
        if (isStaff(p)) {
            seeStaff(p);
        }
        final Ticket pend = pendingRating(p.getUniqueId());
        if (pend != null) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!p.isOnline() || pend.rating != 0) {
                    return;
                }
                if (pend.rateDeadline > 0 && System.currentTimeMillis() >= pend.rateDeadline) {
                    autoRate(pend);
                } else {
                    notifyRate(p, pend);
                }
            }, 60L);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        awaiting.remove(id);
        stars.remove(id);
        AnvilSession s = anvils.remove(id);
        if (s != null) {
            s.done = true;
        }
        restoreInventory(p);
        faceAsked.remove(id);
        lastStaffListMode.remove(id);
    }

    // ------------------------------------------------------------------
    //  Comandi
    // ------------------------------------------------------------------

    /** /ticket [numero] */
    public boolean playerCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("solo i giocatori possono usare i ticket");
            return true;
        }
        if (!p.hasPermission("blockmania.use")) {
            return true;
        }
        if (args.length >= 1) {
            try {
                openChat(p, Integer.parseInt(args[0]), 0);
                return true;
            } catch (NumberFormatException ignored) {
                // non e un numero: apre la pagina principale
            }
        }
        openMain(p);
        return true;
    }

    /** /adminmania ticket [aperti|miei|chiusi|numero] */
    public boolean staffCommand(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("solo i giocatori possono usare i ticket");
            return true;
        }
        if (!isStaff(p)) {
            err(p, "questo comando e solo per lo staff");
            return true;
        }
        seeStaff(p);
        String sub = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "aperti";
        switch (sub) {
            case "miei", "mine" -> openStaffList(p, "mine", 0);
            case "chiusi", "closed" -> openStaffList(p, "closed", 0);
            case "aperti", "open" -> openStaffList(p, "open", 0);
            case "test" -> {
                Ticket t = new Ticket();
                t.id = nextId++;
                t.code = newCode();
                t.owner = UUID.randomUUID();
                t.ownerName = "GiocatoreTest";
                t.category = "bug";
                t.created = System.currentTimeMillis();
                t.updated = t.created;
                String[] texts = {"ciao, non riesco ad aprire la miniera", "mi dice che non ho abbastanza blockcoin ma ne ho 500",
                        "puoi aiutarmi? grazie mille, e un problema che ho da ieri sera"};
                for (String x : texts) {
                    Msg m = new Msg();
                    m.author = t.owner;
                    m.name = t.ownerName;
                    m.text = x;
                    m.time = t.created;
                    t.msgs.add(m);
                }
                tickets.put(t.id, t);
                save();
                ok(p, "ticket di prova #" + t.id + " creato: lo vedi come staff");
                openChat(p, t.id, 0);
            }
            default -> {
                try {
                    openChat(p, Integer.parseInt(sub), 0);
                } catch (NumberFormatException e) {
                    err(p, "usa: /adminmania ticket aperti | miei | chiusi | <numero>");
                }
            }
        }
        return true;
    }

    public List<String> staffComplete(String[] args) {
        if (args.length == 2) {
            return List.of("aperti", "miei", "chiusi", "test");
        }
        return List.of();
    }
}
