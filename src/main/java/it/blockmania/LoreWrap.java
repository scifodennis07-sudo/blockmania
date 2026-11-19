package it.blockmania;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * Lore corte: spezza le righe troppo lunghe (oltre {@link #LIMIT} caratteri VISIBILI) andando a capo tra le parole,
 * cosi' il riquadro dell'item non diventa largo un chilometro. I codici colore (&a, §a, &#rrggbb, §x§r§r§g§g§b§b)
 * non contano e la riga successiva riprende il colore/formato attivo. Le parole piu' lunghe del limite vengono
 * spezzate a meta' (unico caso).
 * <p>
 * Versione stringa (legacy, prima di plugin.text) e versione Component (dopo plugin.text: passa dal testo legacy §).
 * Nomi: {@link #name(String)} / {@link #name(Component)} accorciano con "..." oltre {@link #NAME_LIMIT}.
 * {@link #apply(ItemMeta, Component, List)} fa tutto: nome accorciato (e se serve il nome intero in cima alla lore)
 * e lore spezzata.
 */
public final class LoreWrap {

    /** Massimo di caratteri visibili per riga di lore. */
    public static final int LIMIT = 32;
    /** Massimo di caratteri visibili per il nome dell'item. */
    public static final int NAME_LIMIT = 34;

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('§').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    private LoreWrap() {
    }

    // ------------------------------------------------------------------
    //  Codici colore
    // ------------------------------------------------------------------

    /** Segni "chiave" di BlockMania (tolti da plugin.color): invisibili. */
    private static boolean isMark(char c) {
        return c == '' || c == '' || c == '';
    }

    private static boolean isHexDigit(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /**
     * Lunghezza del codice colore che inizia in i (0 = non e' un codice): &a / §a (2), &#rrggbb (8),
     * §x§r§r§g§g§b§b (14).
     */
    private static int codeLength(String s, int i) {
        int n = s.length();
        char c = s.charAt(i);
        if ((c != '&' && c != '§') || i + 1 >= n) {
            return 0;
        }
        char k = Character.toLowerCase(s.charAt(i + 1));
        if (k == '#' && i + 8 <= n) {
            for (int j = i + 2; j < i + 8; j++) {
                if (!isHexDigit(s.charAt(j))) {
                    return 0;
                }
            }
            return 8;
        }
        if (k == 'x' && i + 14 <= n) {
            boolean ok = true;
            for (int j = i + 2; j < i + 14; j += 2) {
                char m = s.charAt(j);
                if ((m != '&' && m != '§') || !isHexDigit(s.charAt(j + 1))) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return 14;
            }
        }
        if (isHexDigit(k) || "klmnor".indexOf(k) >= 0) {
            return 2;
        }
        return 0;
    }

    /** Nuovo stato (colore + formati attivi) dopo il codice. */
    private static String advance(String state, String code) {
        char k = Character.toLowerCase(code.charAt(1));
        if (k == 'r') {
            return "";
        }
        if (code.length() > 2 || isHexDigit(k)) {
            return code;   // colore: azzera i formati
        }
        return state.contains(code) ? state : state + code;   // formato: si aggiunge al colore
    }

    /** Caratteri visibili (senza codici colore e segni chiave). */
    public static int visibleLength(String s) {
        if (s == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < s.length(); ) {
            int cl = codeLength(s, i);
            if (cl > 0) {
                i += cl;
                continue;
            }
            if (!isMark(s.charAt(i))) {
                count++;
            }
            i++;
        }
        return count;
    }

    // ------------------------------------------------------------------
    //  Stringhe legacy (&a...)
    // ------------------------------------------------------------------

    /** Spezza ogni riga della lore (righe gia' corte restano uguali). null -> lista vuota. */
    public static List<String> wrap(List<String> lore) {
        return wrap(lore, LIMIT);
    }

    public static List<String> wrap(List<String> lore, int limit) {
        List<String> out = new ArrayList<>();
        if (lore == null) {
            return out;
        }
        for (String line : lore) {
            out.addAll(wrap(line, limit));
        }
        return out;
    }

    /** Spezza una riga in piu' righe da max {@link #LIMIT} caratteri visibili (anche sui "\n"). */
    public static List<String> wrap(String line) {
        return wrap(line, LIMIT);
    }

    public static List<String> wrap(String line, int limit) {
        List<String> out = new ArrayList<>();
        if (line == null) {
            out.add("");
            return out;
        }
        limit = Math.max(4, limit);
        String state = "";
        for (String part : line.split("\n", -1)) {
            // ogni pezzo dopo un "\n" riprende il colore attivo
            state = wrapOne(state + part, limit, out);
        }
        return out;
    }

    /** Spezza una riga senza "\n"; ritorna lo stato colore finale. */
    private static String wrapOne(String line, int limit, List<String> out) {
        if (visibleLength(line) <= limit) {
            out.add(line);
            return stateAfter("", line);
        }
        String state = "";
        StringBuilder cur = new StringBuilder();
        int curVis = 0;
        boolean hasWord = false;
        for (String word : line.split(" ", -1)) {
            int wv = visibleLength(word);
            if (hasWord && curVis + 1 + wv <= limit) {
                cur.append(' ').append(word);
                curVis += 1 + wv;
                state = stateAfter(state, word);
                continue;
            }
            if (hasWord) {
                out.add(cur.toString());
                cur = new StringBuilder(state);
                curVis = 0;
            }
            hasWord = true;
            if (wv <= limit) {
                cur.append(word);
                curVis = wv;
                state = stateAfter(state, word);
                continue;
            }
            // parola piu' lunga del limite: unico caso in cui si spezza a meta'
            for (int i = 0; i < word.length(); ) {
                int cl = codeLength(word, i);
                if (cl > 0) {
                    String code = word.substring(i, i + cl);
                    cur.append(code);
                    state = advance(state, code);
                    i += cl;
                    continue;
                }
                char ch = word.charAt(i);
                boolean visible = !isMark(ch);
                if (visible && curVis >= limit) {
                    out.add(cur.toString());
                    cur = new StringBuilder(state);
                    curVis = 0;
                }
                cur.append(ch);
                if (visible) {
                    curVis++;
                }
                i++;
            }
        }
        out.add(cur.toString());
        return state;
    }

    private static String stateAfter(String state, String s) {
        for (int i = 0; i < s.length(); ) {
            int cl = codeLength(s, i);
            if (cl > 0) {
                state = advance(state, s.substring(i, i + cl));
                i += cl;
            } else {
                i++;
            }
        }
        return state;
    }

    /** Nome item: se oltre {@link #NAME_LIMIT} visibili lo accorcia con "..." (il testo intero va messo in lore). */
    public static String name(String name) {
        return name(name, NAME_LIMIT);
    }

    public static String name(String name, int limit) {
        if (name == null || visibleLength(name) <= limit) {
            return name;
        }
        int keep = Math.max(1, limit - 3);
        StringBuilder sb = new StringBuilder();
        int vis = 0;
        for (int i = 0; i < name.length(); ) {
            int cl = codeLength(name, i);
            if (cl > 0) {
                sb.append(name, i, i + cl);
                i += cl;
                continue;
            }
            char ch = name.charAt(i);
            if (!isMark(ch)) {
                if (vis >= keep) {
                    break;
                }
                vis++;
            }
            sb.append(ch);
            i++;
        }
        // niente spazio prima dei puntini
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == ' ') {
            sb.setLength(sb.length() - 1);
        }
        return sb.append("...").toString();
    }

    // ------------------------------------------------------------------
    //  Component (Adventure): per lore gia' colorate con plugin.text / plugin.color
    // ------------------------------------------------------------------

    /** Righe che non si possono rifare dal testo legacy senza perdere qualcosa (font, click, hover). */
    private static boolean unsafe(Component c) {
        if (c.font() != null || c.clickEvent() != null || c.hoverEvent() != null || c.insertion() != null) {
            return true;
        }
        for (Component child : c.children()) {
            if (unsafe(child)) {
                return true;
            }
        }
        return false;
    }

    private static Component fromLegacy(String legacy) {
        return LEGACY.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }

    /** Spezza una riga gia' colorata. Righe corte (o con font/click/hover) restano identiche. */
    public static List<Component> wrap(Component line) {
        return wrap(line, LIMIT);
    }

    public static List<Component> wrap(Component line, int limit) {
        List<Component> out = new ArrayList<>();
        if (line == null) {
            out.add(Component.empty());
            return out;
        }
        if (PLAIN.serialize(line).length() <= limit || unsafe(line)) {
            out.add(line);
            return out;
        }
        for (String s : wrap(LEGACY.serialize(line), limit)) {
            out.add(fromLegacy(s));
        }
        return out;
    }

    /** Spezza tutte le righe di una lore gia' colorata. null -> null (cosi' meta.lore(null) resta uguale). */
    public static List<Component> wrapComponents(List<Component> lore) {
        if (lore == null) {
            return null;
        }
        List<Component> out = new ArrayList<>();
        for (Component c : lore) {
            out.addAll(wrap(c));
        }
        return out;
    }

    public static boolean tooLong(Component name) {
        return name != null && PLAIN.serialize(name).length() > NAME_LIMIT;
    }

    /** Nome gia' colorato: se troppo lungo lo accorcia con "...". */
    public static Component name(Component name) {
        if (!tooLong(name) || unsafe(name)) {
            return name;
        }
        return fromLegacy(name(LEGACY.serialize(name)));
    }

    /**
     * Mette nome e lore sull'item: nome accorciato se troppo lungo (e se il testo intero non e' gia' nella lore lo
     * aggiunge in cima, spezzato), lore spezzata. lore null = la lore non si tocca (resta quella che c'era).
     */
    public static void apply(ItemMeta meta, Component name, List<Component> lore) {
        List<Component> lines = lore == null ? null : new ArrayList<>(lore);
        if (name != null && tooLong(name) && !unsafe(name)) {
            String full = PLAIN.serialize(name);
            List<Component> current = lines != null ? lines : meta.lore();
            boolean present = false;
            if (current != null) {
                StringBuilder all = new StringBuilder();
                for (Component c : current) {
                    all.append(PLAIN.serialize(c)).append(' ');
                }
                present = all.toString().replace("  ", " ").contains(full);
            }
            if (!present) {
                List<Component> withName = new ArrayList<>(wrap(name));
                if (current != null && !current.isEmpty()) {
                    withName.add(Component.empty());
                    withName.addAll(current);
                }
                lines = withName;
            }
            name = name(name);
        }
        if (name != null) {
            meta.displayName(name);
        }
        if (lines != null) {
            meta.lore(wrapComponents(lines));
        }
    }

    /**
     * Sistema nome e lore gia' messi sul meta (da chiamare subito prima di stack.setItemMeta(meta)).
     * Si puo' chiamare piu' volte: la seconda non cambia niente.
     */
    public static ItemMeta fix(ItemMeta meta) {
        if (meta == null) {
            return null;
        }
        Component name = meta.hasDisplayName() ? meta.displayName() : null;
        List<Component> lore = meta.hasLore() ? meta.lore() : null;
        if (!tooLong(name) && !needsWrap(lore)) {
            return meta;   // niente da fare: non tocca il meta
        }
        apply(meta, name, lore);
        return meta;
    }

    private static boolean needsWrap(List<Component> lore) {
        if (lore == null) {
            return false;
        }
        for (Component c : lore) {
            if (c != null && PLAIN.serialize(c).length() > LIMIT && !unsafe(c)) {
                return true;
            }
        }
        return false;
    }

    /** Solo lore (nome gia' messo): spezza e imposta. */
    public static void lore(ItemMeta meta, List<Component> lore) {
        meta.lore(wrapComponents(lore));
    }
}
