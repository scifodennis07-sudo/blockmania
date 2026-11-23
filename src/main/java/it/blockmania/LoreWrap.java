package it.blockmania;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Lore corte: spezza le righe troppo lunghe (oltre {@link #LIMIT} caratteri VISIBILI) andando a capo tra le parole,
 * cosi' il riquadro dell'item non diventa largo un chilometro. I codici colore (&a, §a, &#rrggbb, §x§r§r§g§g§b§b)
 * non contano e la riga successiva riprende il colore/formato attivo. Le parole piu' lunghe del limite vengono
 * spezzate a meta' (unico caso).
 * <p>
 * Le righe di ISTRUZIONE (tasti/click: "&eclicca per aprire  &8|  &cQ per eliminare", "&eclick destro: ...")
 * non vengono MAI spezzate, anche se piu' lunghe del limite: vedi {@link #isInstruction(String)}. In queste righe le
 * parole dei tasti diventano icone del pack ({@link KeyIcons#iconize(String)}): "&f[L] &eapri &8| &f[Q] &celimina".
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
    static boolean isMark(char c) {
        return c == '' || c == '' || c == '';
    }

    private static boolean isHexDigit(char c) {
        return Character.digit(c, 16) >= 0;
    }

    /**
     * Lunghezza del codice colore che inizia in i (0 = non e' un codice): &a / §a (2), &#rrggbb (8),
     * §x§r§r§g§g§b§b (14).
     */
    static int codeLength(String s, int i) {
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
    static String advance(String state, String code) {
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
            char ch = s.charAt(i);
            if (KeyIcons.isIcon(ch)) {
                count += KeyIcons.chars(ch);   // icona dei tasti: conta quanto e' larga (circa 6 px a lettera)
            } else if (!isMark(ch)) {
                count++;
            }
            i++;
        }
        return count;
    }

    // ------------------------------------------------------------------
    //  Righe di istruzione (mai spezzate)
    // ------------------------------------------------------------------

    /** Parole dei tasti/click: clicca, click, premi, tasto, shift, trascina, "Q per/su", "destro:"/"sinistro:". */
    private static final Pattern KEYWORD = Pattern.compile(
            "\\b(clicca\\w*|click\\w*|premi|tasto|tasti|shift|trascina\\w*|q\\s+(per|su))\\b"
                    + "|\\b(destro|sinistro)\\s*:");
    /** Riga che COMINCIA con un'istruzione ("clicca per...", "click destro: ...", "tasto Q per ...", "Q su ...",
     *  "sinistro apre ...", "destro per ..."). */
    private static final Pattern STARTS = Pattern.compile(
            "^[^a-z0-9]*(clicca\\w*|click\\w*|premi|tasto|tasti|shift|trascina\\w*|q\\s+(per|su)"
                    + "|destro\\b|sinistro\\b|doppio\\s+click|rotella\\b)");
    /** Legenda dei tasti a meta' riga: "Testa: click destro = ...", "Carta: click: ...". */
    private static final Pattern LEGEND = Pattern.compile(
            "\\b(clicca|click|tasto|shift)(\\s+(destro|sinistro|centrale|q))?\\s*[=:]"
                    + "|[:=]\\s*(destro|sinistro)\\b");   // "sanzioni: destro cronologia"

    /** Testo visibile (senza codici colore e segni chiave). */
    private static String plain(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cl = codeLength(s, i);
            if (cl > 0) {
                i += cl;
                continue;
            }
            char ch = s.charAt(i);
            if (!isMark(ch)) {
                sb.append(ch);
            }
            i++;
        }
        return sb.toString();
    }

    /**
     * Riga di istruzione (tasti/click) da tenere SEMPRE intera su una riga:
     * <ul>
     *     <li>contiene il separatore " | " e una parola dei tasti (es. "clicca per aprire | Q per eliminare");</li>
     *     <li>il primo testo visibile e' giallo (&e) - colore delle istruzioni;</li>
     *     <li>e' rossa (&c) e contiene una parola dei tasti (es. "&cclick destro o Q per eliminare");</li>
     *     <li>comincia con una parola dei tasti (clicca/click/premi/tasto/shift/trascina/Q per/destro:/sinistro:)
     *     o e' una legenda ("Testa: click destro = ...").</li>
     * </ul>
     * Le frasi descrittive (grigie) che nominano "clicca" a meta' ("Nella cartella clicca prendi...") vanno a capo.
     */
    public static boolean isInstruction(String legacy) {
        if (legacy == null) {
            return false;
        }
        String raw = plain(legacy);
        if (KeyIcons.hasIcon(raw)) {
            return true;   // gia' con le icone dei tasti (KeyIcons.iconize)
        }
        // il testo delle GUI arriva gia' in "tiny text" (piccole maiuscole): si confronta con le lettere normali
        StringBuilder un = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            un.append(Tiny.untiny(raw.charAt(i)));
        }
        String text = un.toString().toLowerCase(Locale.ROOT);
        if (text.isBlank()) {
            return false;
        }
        boolean keyword = KEYWORD.matcher(text).find();
        if (keyword && text.contains(" | ")) {
            return true;   // "clicca per aprire | Q per eliminare" (un motivo "[R1] testo | staff: ..." va a capo)
        }
        // colore attivo sul primo carattere visibile
        String state = "";
        for (int i = 0; i < legacy.length(); ) {
            int cl = codeLength(legacy, i);
            if (cl > 0) {
                state = advance(state, legacy.substring(i, i + cl));
                i += cl;
                continue;
            }
            char ch = legacy.charAt(i);
            if (!isMark(ch) && !Character.isWhitespace(ch)) {
                break;
            }
            i++;
        }
        char color = state.length() >= 2 ? Character.toLowerCase(state.charAt(1)) : ' ';
        if (color == 'e') {
            return true;
        }
        if (color == 'c' && keyword) {
            return true;
        }
        return STARTS.matcher(text.trim()).find() || LEGEND.matcher(text).find();
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
        line = KeyIcons.iconize(line);   // righe di istruzione: parole dei tasti -> icone
        if (visibleLength(line) <= limit || isInstruction(line)) {   // istruzioni: sempre intere
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
        if (unsafe(line)) {
            out.add(line);
            return out;
        }
        String legacy = LEGACY.serialize(line);
        String icons = KeyIcons.iconize(legacy);   // righe di istruzione: parole dei tasti -> icone
        if (!icons.equals(legacy)) {
            legacy = icons;
            line = fromLegacy(icons);
        }
        if (visibleLength(legacy) <= limit) {
            out.add(line);
            return out;
        }
        if (isInstruction(legacy) && !legacy.contains("\n")) {   // istruzione: resta identica
            out.add(line);
            return out;
        }
        for (String s : wrap(legacy, limit)) {
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
            if (c == null || unsafe(c)) {
                continue;
            }
            String legacy = LEGACY.serialize(c);
            if (PLAIN.serialize(c).length() > LIMIT && !isInstruction(legacy)) {
                return true;
            }
            if (!KeyIcons.iconize(legacy).equals(legacy)) {
                return true;   // parole dei tasti da trasformare in icone
            }
        }
        return false;
    }

    /** Solo lore (nome gia' messo): spezza e imposta. */
    public static void lore(ItemMeta meta, List<Component> lore) {
        meta.lore(wrapComponents(lore));
    }
}
