package it.blockmania;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Dettaglio di una regola (regole-3.png, glifo U+3E0D nel font minecraft:regole).
 * Barra alta = titolo della regola; striscia a destra della X = "regola 1.1 warn" (font tiny5);
 * quadrato 2x2 in alto a sinistra = icona grande (font rules_big); riquadro grande = "chat" come i ticket
 * con 2 messaggi (titolo e descrizione) e frecce su/giu' per scorrere; sotto il quadrato 4 icone gia' disegnate
 * nell'immagine: cestino (elimina), matita (modifica), i (info + cronologia), persone (player sanzionati).
 * Live: si ridisegna da sola a ogni Rules.onChange.
 *
 * Misure (pixel di regole-3.png; container = immagine - 7 in x, - 15 in y; slot x=15+18c, y=33+18r):
 * X (19..43, 30..43) = slot 0,1; striscia (50..140, 31..43); pulsante arancione (146..170, 30..43) = slot 7,8;
 * quadrato icona (14..49, 50..85) = slot 9,10,18,19; icone (cestino 15..31/103..122, matita 31..47/103..122,
 * i 15..31/125..144, persone 31..47/125..144) = slot 36,37,45,46; chat (interno 56..154, 51..144);
 * freccia su (162..176, 51..94) = slot 17,26,35; freccia giu' (162..176, 101..144) = slot 44,53.
 */
public final class RuleDetailGui implements Listener {

    // ---------- grafica del pack ----------
    private static final String FONT_RULES = "minecraft:regole";
    private static final String IMG_DETAIL = "㸍";   // regole-3.png
    private static final int IMG = -22;
    /** Dichiarato 179 (reale 186): stessa convenzione di Tickets/RulesGui, vale x_img + IMG. */
    private static final int IMG_ADV = 179;
    private static final int IMG_REAL = 186;

    // striscia a destra della X: testo piccolo (font tiny5 di Sara: stessi caratteri di tiny3)
    private static final String DEF_STRIP_FONT = "minecraft:tiny5";
    private static final char DEF_STRIP_BASE = '';
    private static final char DEF_STRIP_SHADOW = '';
    /** Centro x della striscia (50..140) e larghezza massima del testo. */
    private static final int STRIP_CX = 94;
    private static final int STRIP_MAX = 84;

    // icona grande (font rules_big di Sara): sfondo 32x32 = U+E000 + colore, cifra = U+E010 + d, punto = U+E01A
    private static final String FONT_BIG = "minecraft:rules_big";
    private static final char BIG_BG_BASE = '';
    private static final char BIG_DIGIT_BASE = '';
    private static final char BIG_DOT = '';
    private static final int BIG_COLORS = 8;
    /** Bordo sinistro dello sfondo 32x32 (quadrato 36x36 con bordo a x=14) e suo centro. */
    private static final int BIG_X = 16;
    private static final int BIG_CX = 32;
    /** Avanzamento reale dello sfondo (32 + 1), della cifra e del punto. */
    private static final int BIG_BG_ADV = 33;
    private static final int BIG_DIGIT_ADV = 8;
    private static final int BIG_DOT_ADV = 4;

    // chat (come Tickets, font tk<y>: le bolle stanno solo a y multiple di 3, 33..153)
    private static final int CHAT_TOP = 54;
    private static final int CHAT_CAP = 90;     // altezza utile (interno 51..144)
    private static final int ROW_H = 12;
    private static final int MSG_GAP = 3;
    private static final int BX0 = 58;          // bolle a sinistra
    private static final int BX1 = 155;         // bolle a destra: bordo destro = BX1 - 1
    private static final int MAX_TEXT = 84;     // testo max; bolla = testo + 4 (margine) + 6 (bordi) = 94 (interno chat 98)
    private static final int PAD = 2;           // margine interno extra per lato
    private static final int CHAT_L = 56;       // interno riquadro chat 56..154 (centro 105)
    private static final int CHAT_W = 98;
    private static final int[] TEXT_RGB = {0x2A2A2A, 0x0F2D46, 0x5A4600};

    // ---------- slot ----------
    private static final int SIZE = 54;
    private static final int[] SLOTS_X = {0, 1};
    private static final int[] SLOTS_ORANGE = {7, 8};
    private static final int[] SLOTS_SQUARE = {9, 10, 18, 19};
    private static final int[] SLOTS_UP = {17, 26, 35};
    private static final int[] SLOTS_DOWN = {44, 53};
    private static final int SLOT_DELETE = 36;
    private static final int SLOT_EDIT = 37;
    private static final int SLOT_INFO = 45;
    private static final int SLOT_HITS = 46;

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault());

    private record Line(int msg, int idx, int n, String text, int side) {
    }

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        Session session;
        final Map<Integer, Runnable> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class Session {
        Player staff;
        String code;
        Runnable onBack;
        int scroll;
        Holder holder;
        String titleKey = "";
    }

    private final BlockMania plugin;
    private final Rules rules;
    private final UserProfilePowers ui;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Set<UUID> switching = new HashSet<>();

    public RuleDetailGui(BlockMania plugin, Rules rules, UserProfilePowers ui) {
        this.plugin = plugin;
        this.rules = rules;
        this.ui = ui;
        rules.onChange(() -> Bukkit.getScheduler().runTask(plugin, () -> refreshAll()));
    }

    // ------------------------------------------------------------------
    //  API
    // ------------------------------------------------------------------

    /** Apre il dettaglio della regola; onBack (puo' essere null) e' chiamato dalla X. */
    public void open(Player staff, String code, Runnable onBack) {
        if (!authorize(staff, "see")) {
            return;
        }
        openNoAuth(staff, code, onBack, 0);
    }

    /** True se il giocatore ha aperto questa GUI. */
    public boolean owns(Player p) {
        return p != null && p.getOpenInventory().getTopInventory().getHolder() instanceof Holder;
    }

    private void openNoAuth(Player staff, String code, Runnable onBack, int scroll) {
        Rules.Rule r = rules.get(code);
        if (r == null) {
            GuiMsg.send(plugin, staff, "&cregola " + code + " non trovata");
            if (onBack != null) {
                onBack.run();
            }
            return;
        }
        Session s = new Session();
        s.staff = staff;
        s.code = r.code();
        s.onBack = onBack;
        s.scroll = scroll;
        render(s, r);
    }

    private boolean authorize(Player p, String verb) {
        AdminCommand a = plugin.getAdminCommand();
        if (a == null) {
            return p.isOp();
        }
        return a.authorize(p, "sanctions", verb, null, 0);
    }

    // ------------------------------------------------------------------
    //  Testi: pulizia, misura, a capo (stessi di Tickets, font ascii)
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
        return out.length() > 600 ? out.substring(0, 600) : out;
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

    // ------------------------------------------------------------------
    //  Chat: 2 messaggi (titolo e descrizione, entrambi centrati), dall'alto
    // ------------------------------------------------------------------

    private static List<Line> layout(Rules.Rule r) {
        List<Line> lines = new ArrayList<>();
        String title = clean(r.title());
        if (title.isEmpty()) {
            title = "Regola " + r.code();
        }
        String text = clean(r.text());
        if (text.isEmpty()) {
            text = "nessuna descrizione";
        }
        List<String> t = wrap(title, MAX_TEXT);
        for (int k = 0; k < t.size(); k++) {
            lines.add(new Line(0, k, t.size(), t.get(k), 1));
        }
        List<String> d = wrap(text, MAX_TEXT);
        for (int k = 0; k < d.size(); k++) {
            lines.add(new Line(1, k, d.size(), d.get(k), 0));
        }
        return lines;
    }

    /** Quanti righi stanno nella chat partendo dal rigo start. */
    private static int fitFrom(List<Line> lines, int start) {
        int used = 0;
        int n = 0;
        for (int i = start; i < lines.size(); i++) {
            int gap = i > start && lines.get(i).msg() != lines.get(i - 1).msg() ? MSG_GAP : 0;
            if (used + gap + ROW_H > CHAT_CAP) {
                break;
            }
            used += gap + ROW_H;
            n++;
        }
        return n;
    }

    /** Primo rigo da cui si vede la fine del testo (scroll massimo). */
    private static int maxScroll(List<Line> lines) {
        for (int s = 0; s < lines.size(); s++) {
            if (s + fitFrom(lines, s) >= lines.size()) {
                return s;
            }
        }
        return 0;
    }

    private static char bubbleChar(int scheme, int variant, int piece) {
        return (char) (0xE100 + scheme * 64 + variant * 16 + piece);
    }

    private static Key tk(int y) {
        return Key.key("minecraft", "tk" + y);
    }

    private static Component glyph(String text, Key font, int rgb) {
        return Component.text(text).font(font).color(TextColor.color(rgb)).shadowColor(ShadowColor.none())
                .decoration(TextDecoration.ITALIC, false);
    }

    private static Component bigGlyph(char ch) {
        return Component.text(String.valueOf(ch)).font(Key.key(FONT_BIG)).color(TextColor.color(0xFFFFFF))
                .shadowColor(ShadowColor.none()).decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------------
    //  Titolo
    // ------------------------------------------------------------------

    /** Colore dello sfondo grande = (primo numero del codice - 1) mod 8. */
    private static int bigColor(String code) {
        int dot = code.indexOf('.');
        String first = dot < 0 ? code : code.substring(0, dot);
        try {
            return Math.floorMod(Integer.parseInt(first.trim()) - 1, BIG_COLORS);
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static int bigAdvance(char ch) {
        if (ch >= '0' && ch <= '9') {
            return BIG_DIGIT_ADV;
        }
        return ch == '.' ? BIG_DOT_ADV : 0;
    }

    /**
     * Scena base: immagine, icona grande e chat. real = true quando serve come sfondo di confirmOver
     * (immagine con avanzamento reale 186: le posizioni x_img + IMG vanno spostate di 7).
     */
    private void scene(GuiTitle g, Rules.Rule r, int scroll, boolean real) {
        final int ox = IMG + (real ? 7 : 0);
        g.at(-15, "&f" + IMG_DETAIL, FONT_RULES, real ? IMG_REAL : IMG_ADV);

        // icona grande nel quadrato 2x2
        String code = r.code();
        g.atComponent(BIG_X + ox, bigGlyph((char) (BIG_BG_BASE + bigColor(code))), BIG_BG_ADV);
        int total = 0;
        for (char ch : code.toCharArray()) {
            total += bigAdvance(ch);
        }
        int x = BIG_CX - Math.max(0, total - 2) / 2;
        for (char ch : code.toCharArray()) {
            int adv = bigAdvance(ch);
            if (adv == 0) {
                continue;
            }
            char gl = ch == '.' ? BIG_DOT : (char) (BIG_DIGIT_BASE + (ch - '0'));
            g.atComponent(x + ox, bigGlyph(gl), adv);
            x += adv;
        }

        // chat
        List<Line> lines = layout(r);
        int start = Math.max(0, Math.min(scroll, lines.size() - 1));
        int count = fitFrom(lines, start);
        Map<Integer, Integer> maxW = new HashMap<>();
        for (Line l : lines) {
            maxW.merge(l.msg(), textWidth(l.text()), Math::max);
        }
        int used = 0;
        for (int k = 0; k < count; k++) {
            Line ln = lines.get(start + k);
            if (k > 0 && lines.get(start + k - 1).msg() != ln.msg()) {
                used += MSG_GAP;
            }
            int yb = CHAT_TOP + used;
            used += ROW_H;
            int variant = ln.n() == 1 ? 0 : ln.idx() == 0 ? 1 : ln.idx() == ln.n() - 1 ? 3 : 2;
            int tw = Math.max(8, maxW.getOrDefault(ln.msg(), 8)) + 2 * PAD;
            int own = textWidth(ln.text());
            int bw = tw + 6;
            int xb = CHAT_L + (CHAT_W - bw) / 2;
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
            g.atComponent(xb + ox, glyph(sb.toString(), tk(yb), 0xFFFFFF), bw);
            g.atComponent(xb + 3 + PAD + ox, glyph(ln.text(), tk(yb + 2), TEXT_RGB[ln.side()]), own);
        }
    }

    // ---------- testo piccolo nella striscia ----------

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
            sb.append(ch < 128 && Character.isLetterOrDigit(ch) ? (char) (base + ch) : ' ');
        }
        return sb.toString();
    }

    private String cfg(String key, String def) {
        String v = plugin.getConfig().getString("rules-gui." + key, def);
        return v == null ? def : v;
    }

    /**
     * "regola 1.1 warn" centrato nella striscia. Il font tiny non ha il punto: tra le cifre del codice resta
     * uno spazio stretto (2 px). Se non sta nella striscia si accorcia (senza "regola", poi senza tipo).
     */
    private void strip(GuiTitle g, Rules.Rule r) {
        strip(g, r, IMG);
    }

    private void strip(GuiTitle g, Rules.Rule r, int ox) {
        String[] codeParts = r.code().split("\\.");
        String type = Rules.typeLabel(r.type()).toLowerCase(Locale.ROOT);
        List<String[]> variants = new ArrayList<>();
        variants.add(new String[]{"regola", null, type});
        variants.add(new String[]{null, null, type});
        variants.add(new String[]{"regola", null, null});
        List<String> tokens = new ArrayList<>();
        List<Integer> gaps = new ArrayList<>();
        int best = -1;
        for (int v = 0; v < variants.size(); v++) {
            tokens.clear();
            gaps.clear();
            String[] vv = variants.get(v);
            if (vv[0] != null) {
                tokens.add(vv[0]);
                gaps.add(0);
            }
            for (int i = 0; i < codeParts.length; i++) {
                String part = tinySafe(codeParts[i]);
                if (part.isEmpty()) {
                    continue;
                }
                tokens.add(part);
                gaps.add(tokens.size() == 1 ? 0 : (i == 0 ? 4 : 2));
            }
            if (vv[2] != null && !vv[2].isEmpty()) {
                tokens.add(vv[2]);
                gaps.add(tokens.size() == 1 ? 0 : 4);
            }
            int total = 0;
            for (int i = 0; i < tokens.size(); i++) {
                total += gaps.get(i) + tinyAdvance(tokens.get(i));
            }
            best = total;
            if (total <= STRIP_MAX) {
                break;
            }
        }
        if (tokens.isEmpty()) {
            return;
        }
        String font = cfg("strip-font", DEF_STRIP_FONT);
        char base = DEF_STRIP_BASE;
        char shadow = DEF_STRIP_SHADOW;
        int st = STRIP_CX - Math.max(0, best - 1) / 2;
        int off = 0;
        for (int i = 0; i < tokens.size(); i++) {
            off += gaps.get(i);
            String w = tokens.get(i);
            int wa = tinyAdvance(w);
            g.at(st + off + 1 + ox, GuiTitle.shadowCode(plugin) + tinyLetters(w, shadow), font, wa);
            g.at(st + off + ox, "&f" + tinyLetters(w, base), font, wa);
            off += wa;
        }
    }

    private static String tinySafe(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            sb.append(ch < 128 && Character.isLetterOrDigit(ch) ? ch : ' ');
        }
        return sb.toString().replaceAll(" +", " ").trim();
    }

    private Component title(Rules.Rule r, int scroll) {
        GuiTitle g = new GuiTitle(plugin);
        scene(g, r, scroll, false);
        strip(g, r);
        UserProfilePowers.topTitle(plugin, g, r.title() == null || r.title().isBlank() ? "regola " + r.code() : r.title());
        return g.build();
    }

    /**
     * Sfondo per i popup (da scurire con MinesGui.tint, come in UserProfilePowers.confirmOver): immagine,
     * icona grande, testo della striscia e bolle della chat (scroll 0), tutto con avanzamento reale 186.
     */
    public void drawDimmedBackground(GuiTitle g, Rules.Rule r) {
        drawBackground(g, r, 0);
    }

    private void drawBackground(GuiTitle g, Rules.Rule r, int scroll) {
        scene(g, r, scroll, true);
        strip(g, r, IMG + 7);
    }

    /** Chiave del titolo: se cambia serve un inventario nuovo. */
    private static String key(Rules.Rule r, int scroll) {
        return r.code() + "|" + r.title() + "|" + r.text() + "|" + r.type() + "|" + scroll;
    }

    // ------------------------------------------------------------------
    //  Oggetti
    // ------------------------------------------------------------------

    private ItemStack pane(String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        meta.displayName(plugin.text(name));
        if (lore != null && !lore.isEmpty()) {
            List<Component> l = new ArrayList<>();
            for (String line : lore) {
                l.add(line == null || line.isBlank() ? plugin.color(" ") : plugin.text(line));
            }
            meta.lore(l);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private static String colorOf(Rules.Type t) {
        return switch (t) {
            case BAN -> "&c";
            case WARN -> "&e";
            case MUTE -> "&6";
            case REGOLAMENTO -> "&b";
        };
    }

    private static String date(long ms) {
        return DATE.format(Instant.ofEpochMilli(ms));
    }

    private void put(Holder h, int slot, ItemStack stack, Runnable action) {
        h.inventory.setItem(slot, stack);
        if (action != null) {
            h.actions.put(slot, action);
        }
    }

    private void fill(Session s, Rules.Rule r) {
        Holder h = s.holder;
        h.inventory.clear();
        h.actions.clear();
        final Player p = s.staff;
        final String code = r.code();
        List<Line> lines = layout(r);
        int max = maxScroll(lines);

        for (int slot : SLOTS_X) {
            put(h, slot, pane(s.onBack != null ? "&cindietro" : "&cchiudi", List.of()), () -> back(s));
        }
        List<String> orange = new ArrayList<>();
        orange.add("&7Tipo: " + colorOf(r.type()) + Rules.typeLabel(r.type()));
        orange.add("&7Tempo: &f" + Rules.timeLabel(r));
        for (int slot : SLOTS_ORANGE) {
            put(h, slot, pane("&6sanzione", orange), null);
        }
        List<String> sq = new ArrayList<>();
        sq.add("&7Regola &f" + code);
        sq.add("&7Tipo: " + colorOf(r.type()) + Rules.typeLabel(r.type()));
        for (int slot : SLOTS_SQUARE) {
            put(h, slot, pane("&f" + (r.title() == null || r.title().isBlank() ? "Regola " + code : r.title()), sq), null);
        }
        for (int slot : SLOTS_UP) {
            put(h, slot, pane("&7sali", List.of("&7testo piu' in alto")), s.scroll > 0 ? () -> scroll(s, -1) : null);
        }
        for (int slot : SLOTS_DOWN) {
            put(h, slot, pane("&7scendi", List.of("&7testo piu' in basso")), s.scroll < max ? () -> scroll(s, 1) : null);
        }

        put(h, SLOT_DELETE, pane("&celimina regola", List.of("&7Elimina la regola " + code + ".", "", "&eclicca per eliminare")),
                () -> askDelete(s));
        put(h, SLOT_EDIT, pane("&emodifica regola", List.of("&7Cambia codice, titolo, descrizione", "&7o sanzione della regola.", "", "&eclicca per modificare")),
                () -> edit(s));
        List<String> info = new ArrayList<>();
        info.add("&7Creata da: &f" + nameOr(r.createdByName()) + " &7il &f" + (r.createdAt() > 0 ? date(r.createdAt()) : "data sconosciuta"));
        if (r.editedAt() > 0) {
            info.add("&7Ultima modifica: &f" + nameOr(r.editedByName()) + " &7il &f" + date(r.editedAt()));
        } else {
            info.add("&7Ultima modifica: &fmai modificata");
        }
        info.add("");
        info.add("&eclicca per la cronologia");
        put(h, SLOT_INFO, pane("&binformazioni", info), () -> history(s));
        put(h, SLOT_HITS, pane("&6player sanzionati",
                List.of("&7Player sanzionati: &f" + rules.hitCount(code), "", "&eclicca per la lista")), () -> hits(s));
    }

    private static String nameOr(String n) {
        return n == null || n.isBlank() ? "sconosciuto" : n;
    }

    // ------------------------------------------------------------------
    //  Disegno e live
    // ------------------------------------------------------------------

    private void render(Session s, Rules.Rule r) {
        List<Line> lines = layout(r);
        s.scroll = Math.max(0, Math.min(s.scroll, maxScroll(lines)));
        s.code = r.code();
        s.titleKey = key(r, s.scroll);
        Holder h = new Holder();
        h.session = s;
        s.holder = h;
        h.inventory = Bukkit.createInventory(h, SIZE, title(r, s.scroll));
        fill(s, r);
        UUID id = s.staff.getUniqueId();
        switching.add(id);
        try {
            s.staff.openInventory(h.inventory);
        } finally {
            switching.remove(id);
        }
        sessions.put(id, s);
    }

    private void refreshAll() {
        for (Session s : new ArrayList<>(sessions.values())) {
            Player p = s.staff;
            if (!p.isOnline()) {
                sessions.remove(p.getUniqueId());
                continue;
            }
            if (p.getOpenInventory().getTopInventory().getHolder() != s.holder) {
                continue;
            }
            Rules.Rule r = rules.get(s.code);
            if (r == null) {
                // la regola non esiste piu' (eliminata o cambiata di codice da un altro staffer)
                sessions.remove(p.getUniqueId());
                p.closeInventory();
                continue;
            }
            int max = maxScroll(layout(r));
            if (s.scroll > max) {
                s.scroll = max;
            }
            if (key(r, s.scroll).equals(s.titleKey)) {
                fill(s, r);
            } else {
                render(s, r);
            }
        }
    }

    private void scroll(Session s, int delta) {
        Rules.Rule r = rules.get(s.code);
        if (r == null) {
            return;
        }
        s.scroll += delta;
        render(s, r);
    }

    // ------------------------------------------------------------------
    //  Azioni
    // ------------------------------------------------------------------

    private void back(Session s) {
        sessions.remove(s.staff.getUniqueId());
        if (s.onBack != null) {
            s.onBack.run();
        } else {
            s.staff.closeInventory();
        }
    }

    /** Riapre il dettaglio (dopo un popup). */
    private Runnable reopen(Player p, String code, Runnable onBack, int scroll) {
        return () -> Bukkit.getScheduler().runTask(plugin, () -> openNoAuth(p, code, onBack, scroll));
    }

    private void askDelete(Session s) {
        final Player p = s.staff;
        if (!authorize(p, "edit")) {
            return;
        }
        final String code = s.code;
        final Runnable onBack = s.onBack;
        final int scroll = s.scroll;
        Rules.Rule r = rules.get(code);
        if (r == null) {
            return;
        }
        ui.confirmOver(p, "Elimina la regola " + code, c -> drawBackground(c, r, scroll), () -> {
            if (!authorize(p, "edit")) {
                reopen(p, code, onBack, scroll).run();
                return;
            }
            if (rules.remove(code)) {
                AdminCommand a = plugin.getAdminCommand();
                if (a != null) {
                    a.record(p, "rules remove " + code);
                }
                GuiMsg.send(plugin, p, "&aregola " + code + " eliminata");
            } else {
                GuiMsg.send(plugin, p, "&cregola " + code + " non trovata");
            }
            if (onBack != null) {
                Bukkit.getScheduler().runTask(plugin, onBack);
            } else {
                p.closeInventory();
            }
        }, reopen(p, code, onBack, scroll));
    }

    private void edit(Session s) {
        final Player p = s.staff;
        if (!authorize(p, "edit")) {
            return;
        }
        RulePopups popups = plugin.getRulePopups();
        Rules.Rule r = rules.get(s.code);
        if (popups == null || r == null) {
            return;
        }
        final String oldCode = r.code();
        final long created = r.createdAt();
        final Runnable onBack = s.onBack;
        final int scroll = s.scroll;
        // dopo la modifica il codice puo' essere cambiato: si ritrova la regola dalla data di creazione
        Runnable after = () -> Bukkit.getScheduler().runTask(plugin, () -> {
            Rules.Rule cur = rules.get(oldCode);
            if (cur == null && created > 0) {
                for (Rules.Rule x : rules.all()) {
                    if (x.createdAt() == created) {
                        cur = x;
                        break;
                    }
                }
            }
            if (cur == null) {
                if (onBack != null) {
                    onBack.run();
                }
                return;
            }
            openNoAuth(p, cur.code(), onBack, scroll);
        });
        popups.editMenu(p, r, after, after);
    }

    private void history(Session s) {
        RulePopups popups = plugin.getRulePopups();
        Rules.Rule r = rules.get(s.code);
        if (popups == null || r == null) {
            return;
        }
        popups.historyList(s.staff, r, reopen(s.staff, s.code, s.onBack, s.scroll));
    }

    private void hits(Session s) {
        RulePopups popups = plugin.getRulePopups();
        Rules.Rule r = rules.get(s.code);
        if (popups == null || r == null) {
            return;
        }
        popups.hitsList(s.staff, r, reopen(s.staff, s.code, s.onBack, s.scroll));
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
        if (event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        Runnable a = h.actions.get(event.getRawSlot());
        if (a == null) {
            return;
        }
        plugin.guiClick(p);
        // l'azione apre altre GUI: nel tick dopo il click
        Bukkit.getScheduler().runTask(plugin, () -> a.run());
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
