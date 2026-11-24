package it.blockmania;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Icone pixel dei tasti e del mouse (font DEFAULT del pack, Sara: maniablock5/docs/KEY_ICONS.md).
 * <ul>
 *     <li>LORE (U+E940 + indice, 9 px): lore, chat, nomi.</li>
 *     <li>GRANDE (U+E960 + indice, 12 px): actionbar, bossbar, titoli (nella lore tocca le righe vicine).</li>
 * </ul>
 * Le icone prendono il colore del testo: vanno scritte in bianco (&f) e dopo si rimette il colore della riga.
 * <p>
 * {@link #iconize(String)}: nelle righe di ISTRUZIONE delle lore ({@link LoreWrap#isInstruction(String)}) le parole
 * dei tasti diventano icone, es. "&eclicca per aprire &8| &cQ per eliminare" -> "&f[L]&e apri &8| &f[Q]&c elimina".
 * Si toglie "click"/"clicca per"/"per"/":" ridondanti e il verbo dopo "per" passa all'imperativo (aprire -> apri).
 * {@link #iconizeBig(String)}: stessa cosa con le icone grandi per bossbar/actionbar (anche i {key:...}).
 * Le due funzioni sono idempotenti (la seconda volta non cambiano niente).
 */
public final class KeyIcons {

    /** Primo carattere delle icone LORE (9 px) e GRANDI (12 px): stesso indice, stessa icona. */
    public static final char LORE_BASE = '\uE940';
    public static final char BIG_BASE = '\uE960';
    public static final int COUNT = 29;

    // indici (tabella di KEY_ICONS.md)
    private static final int I_MOUSE = 0;
    private static final int I_LEFT = 1;
    private static final int I_RIGHT = 2;
    private static final int I_MIDDLE = 3;
    private static final int I_SCROLL = 4;
    private static final int I_DOUBLE_LEFT = 5;
    private static final int I_DOUBLE_RIGHT = 6;
    private static final int I_DRAG = 7;
    private static final int I_SHIFT = 8;
    private static final int I_SHIFT_LEFT = 9;
    private static final int I_SHIFT_RIGHT = 10;
    private static final int I_Q = 11;
    private static final int I_E = 12;
    private static final int I_F = 13;
    private static final int I_ESC = 14;
    private static final int I_TAB = 15;
    private static final int I_ALT = 16;
    private static final int I_CTRL = 17;
    private static final int I_KEY_1 = 18;   // 1..9 = 18..26
    private static final int I_EMPTY = 27;
    private static final int I_PLUS = 28;

    // ---- LORE (9 px) ----
    public static final String MOUSE = "\uE940";
    public static final String LEFT = "\uE941";
    public static final String RIGHT = "\uE942";
    public static final String MIDDLE = "\uE943";
    public static final String SCROLL = "\uE944";
    public static final String DOUBLE_LEFT = "\uE945";
    public static final String DOUBLE_RIGHT = "\uE946";
    public static final String DRAG = "\uE947";
    public static final String SHIFT = "\uE948";
    public static final String SHIFT_LEFT = "\uE949";
    public static final String SHIFT_RIGHT = "\uE94A";
    public static final String Q = "\uE94B";
    public static final String E = "\uE94C";
    public static final String F = "\uE94D";
    public static final String ESC = "\uE94E";
    public static final String TAB = "\uE94F";
    public static final String ALT = "\uE950";
    public static final String CTRL = "\uE951";
    public static final String KEY_1 = "\uE952";
    public static final String KEY_2 = "\uE953";
    public static final String KEY_3 = "\uE954";
    public static final String KEY_4 = "\uE955";
    public static final String KEY_5 = "\uE956";
    public static final String KEY_6 = "\uE957";
    public static final String KEY_7 = "\uE958";
    public static final String KEY_8 = "\uE959";
    public static final String KEY_9 = "\uE95A";
    public static final String EMPTY = "\uE95B";
    public static final String PLUS = "\uE95C";

    // ---- GRANDI (12 px): actionbar, bossbar, titoli ----
    public static final String BIG_MOUSE = "\uE960";
    public static final String BIG_LEFT = "\uE961";
    public static final String BIG_RIGHT = "\uE962";
    public static final String BIG_MIDDLE = "\uE963";
    public static final String BIG_SCROLL = "\uE964";
    public static final String BIG_DOUBLE_LEFT = "\uE965";
    public static final String BIG_DOUBLE_RIGHT = "\uE966";
    public static final String BIG_DRAG = "\uE967";
    public static final String BIG_SHIFT = "\uE968";
    public static final String BIG_SHIFT_LEFT = "\uE969";
    public static final String BIG_SHIFT_RIGHT = "\uE96A";
    public static final String BIG_Q = "\uE96B";
    public static final String BIG_E = "\uE96C";
    public static final String BIG_F = "\uE96D";
    public static final String BIG_ESC = "\uE96E";
    public static final String BIG_TAB = "\uE96F";
    public static final String BIG_ALT = "\uE970";
    public static final String BIG_CTRL = "\uE971";
    public static final String BIG_KEY_1 = "\uE972";
    public static final String BIG_KEY_2 = "\uE973";
    public static final String BIG_KEY_3 = "\uE974";
    public static final String BIG_KEY_4 = "\uE975";
    public static final String BIG_KEY_5 = "\uE976";
    public static final String BIG_KEY_6 = "\uE977";
    public static final String BIG_KEY_7 = "\uE978";
    public static final String BIG_KEY_8 = "\uE979";
    public static final String BIG_KEY_9 = "\uE97A";
    public static final String BIG_EMPTY = "\uE97B";
    public static final String BIG_PLUS = "\uE97C";

    /** Avanzamento in pixel (larghezza + 1) per indice: LORE e GRANDI (KEY_ICONS.md). */
    private static final int[] ADV_LORE = {10, 10, 10, 10, 15, 13, 13, 19, 34, 50, 50, 12, 12, 12, 24, 24, 24, 30,
            10, 12, 12, 12, 12, 12, 12, 12, 12, 10, 6};
    private static final int[] ADV_BIG = {10, 10, 10, 10, 17, 14, 14, 21, 29, 47, 47, 13, 13, 13, 24, 24, 19, 25,
            13, 13, 13, 13, 13, 13, 13, 13, 13, 13, 8};

    private KeyIcons() {
    }

    // ------------------------------------------------------------------
    //  Caratteri
    // ------------------------------------------------------------------

    public static boolean isIcon(char c) {
        return (c >= LORE_BASE && c < LORE_BASE + COUNT) || (c >= BIG_BASE && c < BIG_BASE + COUNT);
    }

    public static boolean hasIcon(String s) {
        if (s == null) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (isIcon(s.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /** Pixel di avanzamento dell'icona (-1 se c non e' un'icona dei tasti). */
    public static int advance(char c) {
        if (c >= LORE_BASE && c < LORE_BASE + COUNT) {
            return ADV_LORE[c - LORE_BASE];
        }
        if (c >= BIG_BASE && c < BIG_BASE + COUNT) {
            return ADV_BIG[c - BIG_BASE];
        }
        return -1;
    }

    /** Quante lettere "vale" l'icona per le misure a caratteri di LoreWrap (una lettera ~6 px). */
    static int chars(char c) {
        int adv = advance(c);
        return adv <= 0 ? 1 : Math.max(1, Math.round(adv / 6f));
    }

    // ------------------------------------------------------------------
    //  Frasi riconosciute
    // ------------------------------------------------------------------

    /**
     * need: 0 sempre, 1 solo a inizio frase (o dopo "tasto"/"premi"), 2 solo dopo "tasto"/"premi"/"shift +".
     * safe: frase che e' SEMPRE un tasto (c'e' click/clicca, "shift + ...", "doppio click"): vale anche nelle frasi
     * descrittive e in chat; le altre ("destro", "Q", "shift" da soli) solo nelle righe di istruzione.
     */
    private record Pat(String[] words, int[] icons, int need, boolean safe) {
    }

    private static final List<Pat> PATS = new ArrayList<>();
    /** Parole davanti al tasto che spariscono ("premi Q", "tasto destro"). */
    private static final Set<String> PREFIX = Set.of("premi", "tasto", "tasti", "pulsante");
    /** Dopo queste parole "destro"/"sinistro" da soli contano come tasti ("click destro o sinistro"). */
    private static final Set<String> START_WORDS = Set.of("o", "oppure", "e", "poi");
    private static final String START_PUNCT = "|:=,;/(-";
    /** Niente spazio tra l'icona e questi segni. */
    private static final String NO_SPACE_BEFORE = ")],.;:!?";

    private static final Set<String> CLICKS = Set.of("click", "clicca", "clic", "clicchi", "cliccando", "cliccare");

    private static void pat(String words, int need, int... icons) {
        String[] w = words.trim().split("\\s+");
        boolean safe = false;
        for (String x : w) {
            safe |= CLICKS.contains(x);
        }
        safe |= w.length > 1 && (w[0].equals("shift") || w[0].equals("ctrl") || w[0].equals("doppio"));
        PATS.add(new Pat(w, icons, need, safe));
    }

    static {
        String[] clicks = {"click", "clicca", "clic", "clicchi", "cliccando", "cliccare"};
        String[] rights = {"destro", "dx"};
        String[] lefts = {"sinistro", "sx"};
        for (String c : clicks) {
            for (String r : rights) {
                pat("shift + " + c + " " + r, 0, I_SHIFT_RIGHT);
                pat("shift " + c + " " + r, 0, I_SHIFT_RIGHT);
                pat("doppio " + c + " " + r, 0, I_DOUBLE_RIGHT);
                pat(c + " " + r, 0, I_RIGHT);
                pat(c + " - " + r, 0, I_RIGHT);
            }
            for (String l : lefts) {
                pat("shift + " + c + " " + l, 0, I_SHIFT_LEFT);
                pat("shift " + c + " " + l, 0, I_SHIFT_LEFT);
                pat("doppio " + c + " " + l, 0, I_DOUBLE_LEFT);
                pat(c + " " + l, 0, I_LEFT);
                pat(c + " - " + l, 0, I_LEFT);
            }
            pat("shift + " + c, 0, I_SHIFT_LEFT);
            pat("shift " + c, 0, I_SHIFT_LEFT);
            pat("doppio " + c, 0, I_DOUBLE_LEFT);
            pat(c + " e trascina*", 0, I_DRAG);
            pat(c + " centrale", 0, I_MIDDLE);
            pat(c + " con la rotella", 0, I_MIDDLE);
            pat(c + " rotella", 0, I_MIDDLE);
            pat(c, 0, I_LEFT);
        }
        for (String r : rights) {
            pat("shift + tasto " + r, 0, I_SHIFT_RIGHT);
            pat("shift + " + r, 0, I_SHIFT_RIGHT);
            pat("shift " + r, 0, I_SHIFT_RIGHT);
            pat("doppio " + r, 0, I_DOUBLE_RIGHT);
            pat(r, 1, I_RIGHT);
        }
        for (String l : lefts) {
            pat("shift + tasto " + l, 0, I_SHIFT_LEFT);
            pat("shift + " + l, 0, I_SHIFT_LEFT);
            pat("shift " + l, 0, I_SHIFT_LEFT);
            pat("doppio " + l, 0, I_DOUBLE_LEFT);
            pat(l, 1, I_LEFT);
        }
        pat("scorri con la rotella", 0, I_SCROLL);
        pat("rotella", 0, I_SCROLL);
        pat("rotellina", 0, I_SCROLL);
        pat("scroll", 0, I_SCROLL);
        pat("trascina*", 0, I_DRAG);
        pat("centrale", 2, I_MIDDLE);
        // tasti: q/esc/tab/ctrl anche da soli; e/f/alt/numeri solo dopo "tasto"/"premi"/"shift +"
        String[][] keys = {{"q", "0"}, {"esc", "0"}, {"tab", "0"}, {"ctrl", "0"}, {"e", "2"}, {"f", "2"}, {"alt", "2"}};
        int[] idx = {I_Q, I_ESC, I_TAB, I_CTRL, I_E, I_F, I_ALT};
        for (int k = 0; k < keys.length; k++) {
            pat("shift + " + keys[k][0], 0, I_SHIFT, I_PLUS, idx[k]);
            pat("ctrl + " + keys[k][0], 0, I_CTRL, I_PLUS, idx[k]);
            pat(keys[k][0], Integer.parseInt(keys[k][1]), idx[k]);
        }
        for (int n = 1; n <= 9; n++) {
            pat("shift + " + n, 0, I_SHIFT, I_PLUS, I_KEY_1 + n - 1);
            pat(String.valueOf(n), 2, I_KEY_1 + n - 1);
        }
        pat("shift", 0, I_SHIFT);
        pat("maiusc", 2, I_SHIFT);
        // le frasi piu' lunghe prima (sort stabile: a parita' resta l'ordine scritto sopra)
        PATS.sort((a, b) -> b.words().length - a.words().length);
    }

    // ------------------------------------------------------------------
    //  Verbo dopo "per": infinito -> imperativo (aprire -> apri, eliminare -> elimina, prenderlo -> prendilo)
    // ------------------------------------------------------------------

    private static final Map<String, String> IRREGULAR = new HashMap<>();
    /** Imperativi di una sillaba: con il pronome raddoppiano (dalla, fallo, dillo). */
    private static final Map<String, String> MONO = Map.of("dare", "da", "fare", "fa", "dire", "di", "stare", "sta");
    private static final Set<String> ISC = Set.of("gestire", "chiarire", "finire", "pulire", "costruire", "capire",
            "inserire", "preferire", "spedire", "unire", "colpire", "fornire", "bandire", "punire", "ammonire",
            "definire", "sostituire", "restituire", "distribuire", "attribuire", "impedire", "garantire", "stabilire",
            "suggerire", "trasferire", "riferire", "favorire", "agire", "reagire", "arricchire", "abbellire",
            "obbedire", "smaltire", "ripulire", "riunire", "custodire");
    private static final Pattern ENCLITIC = Pattern.compile("^(.+?)(ar|er|ir)(lo|la|li|le|ne|ci|vi|si|mi|ti|gli)$");

    static {
        IRREGULAR.put("dare", "dai");
        IRREGULAR.put("fare", "fai");
        IRREGULAR.put("dire", "di'");
        IRREGULAR.put("disdire", "disdici");
        IRREGULAR.put("ridire", "ridici");
        IRREGULAR.put("predire", "predici");
        IRREGULAR.put("benedire", "benedici");
        IRREGULAR.put("stare", "stai");
        IRREGULAR.put("andare", "vai");
        IRREGULAR.put("uscire", "esci");
        IRREGULAR.put("riuscire", "riesci");
        IRREGULAR.put("venire", "vieni");
        IRREGULAR.put("tenere", "tieni");
        IRREGULAR.put("ottenere", "ottieni");
        IRREGULAR.put("mantenere", "mantieni");
        IRREGULAR.put("essere", "sii");
        IRREGULAR.put("avere", "abbi");
        IRREGULAR.put("sapere", "sappi");
        IRREGULAR.put("bere", "bevi");
        IRREGULAR.put("porre", "poni");
        IRREGULAR.put("comporre", "componi");
        IRREGULAR.put("disporre", "disponi");
        IRREGULAR.put("proporre", "proponi");
        IRREGULAR.put("tradurre", "traduci");
        IRREGULAR.put("ridurre", "riduci");
        IRREGULAR.put("produrre", "produci");
        IRREGULAR.put("condurre", "conduci");
    }

    /** Imperativo (tu) di un infinito senza pronome; null se non e' un infinito. */
    private static String imperative(String w) {
        String ir = IRREGULAR.get(w);
        if (ir != null) {
            return ir;
        }
        if (w.length() < 5) {
            return null;
        }
        String stem = w.substring(0, w.length() - 3);
        if (w.endsWith("are")) {
            return stem + "a";
        }
        if (w.endsWith("ere")) {
            return stem.endsWith("i") ? stem : stem + "i";   // scegliere -> scegli, togliere -> togli
        }
        if (w.endsWith("ire")) {
            return ISC.contains(w) ? stem + "isci" : stem + "i";
        }
        return null;
    }

    /** Imperativo di un infinito, anche con il pronome attaccato (prenderlo -> prendilo); null se non e' un verbo. */
    static String conjugate(String w) {
        if (w == null || w.length() < 4) {
            return null;
        }
        String direct = imperative(w);
        if (direct != null) {
            return direct;
        }
        Matcher m = ENCLITIC.matcher(w);
        if (m.matches()) {
            String inf = m.group(1) + m.group(2) + "e";
            String clitic = m.group(3).equals("si") ? "ti" : m.group(3);   // spostarsi -> spostati
            String mono = MONO.get(inf);
            if (mono != null) {
                return clitic.startsWith("gl") ? mono + clitic : mono + clitic.charAt(0) + clitic;   // dalla, dagli
            }
            if (m.group(1).length() >= 2) {
                String imp = imperative(inf);
                if (imp != null && !imp.endsWith("'")) {
                    return imp + clitic;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  Token
    // ------------------------------------------------------------------

    private static final int CODE = 0;
    private static final int SPACE = 1;
    private static final int WORD = 2;
    private static final int PUNCT = 3;

    private record Tok(int type, String text, String norm) {
    }

    private static boolean isPlaceholder(String s) {
        if (s.isEmpty() || s.length() > 24) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    /** Parola normalizzata: niente tiny text, minuscola, senza accenti. */
    private static String norm(String w) {
        StringBuilder sb = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = Character.toLowerCase(Tiny.untiny(w.charAt(i)));
            int a = "\u00E0\u00E1\u00E8\u00E9\u00EC\u00ED\u00F2\u00F3\u00F9\u00FA".indexOf(c);
            sb.append(a >= 0 ? "aaeeiioouu".charAt(a) : c);
        }
        return sb.toString();
    }

    private static List<Tok> tokenize(String s) {
        List<Tok> out = new ArrayList<>();
        int n = s.length();
        int i = 0;
        while (i < n) {
            int cl = LoreWrap.codeLength(s, i);
            if (cl > 0) {
                out.add(new Tok(CODE, s.substring(i, i + cl), ""));
                i += cl;
                continue;
            }
            char c = s.charAt(i);
            if (LoreWrap.isMark(c)) {
                out.add(new Tok(CODE, String.valueOf(c), ""));
                i++;
                continue;
            }
            if (Character.isWhitespace(c)) {
                int j = i;
                while (j < n && Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
                out.add(new Tok(SPACE, s.substring(i, j), " "));
                i = j;
                continue;
            }
            if (c == '{') {   // {icon:...}, {shift:-15}, {key:...}: segnaposto, non si toccano
                int end = s.indexOf('}', i);
                if (end > i) {
                    out.add(new Tok(WORD, s.substring(i, end + 1), "{}"));
                    i = end + 1;
                    continue;
                }
            }
            if (c == '%') {   // %price%, %action%
                int end = s.indexOf('%', i + 1);
                if (end > i + 1 && isPlaceholder(s.substring(i + 1, end))) {
                    out.add(new Tok(WORD, s.substring(i, end + 1), "%%"));
                    i = end + 1;
                    continue;
                }
            }
            if (Character.isLetterOrDigit(c)) {
                // una parola puo' avere codici colore in mezzo (sfumature della palette: un colore per lettera)
                int j = i;
                StringBuilder letters = new StringBuilder();
                while (j < n) {
                    char cj = s.charAt(j);
                    if (Character.isLetterOrDigit(cj)) {
                        letters.append(cj);
                        j++;
                        continue;
                    }
                    int cl2 = LoreWrap.codeLength(s, j);
                    if (cl2 > 0 && j + cl2 < n && Character.isLetterOrDigit(s.charAt(j + cl2))) {
                        j += cl2;
                        continue;
                    }
                    break;
                }
                out.add(new Tok(WORD, s.substring(i, j), norm(letters.toString())));
                i = j;
                continue;
            }
            out.add(new Tok(PUNCT, String.valueOf(c), String.valueOf(c)));
            i++;
        }
        return out;
    }

    /** Primo token "vero" (parola o segno) da from in poi, saltando spazi e codici colore; -1 se non c'e'. */
    private static int nextSig(List<Tok> t, int from) {
        for (int i = from; i < t.size(); i++) {
            int ty = t.get(i).type();
            if (ty == WORD || ty == PUNCT) {
                return i;
            }
        }
        return -1;
    }

    private static boolean matches(Tok tok, String w) {
        if (tok.type() != WORD && tok.type() != PUNCT) {
            return false;
        }
        if (w.endsWith("*")) {
            return tok.type() == WORD && tok.norm().startsWith(w.substring(0, w.length() - 1));
        }
        return tok.norm().equals(w);
    }

    /** Frase riconosciuta: token da start (incluso) a end (escluso) e icone. */
    private record Hit(int end, int[] icons) {
    }

    private static Hit matchAt(List<Tok> t, int start, boolean atStart, boolean prefixed, boolean strict) {
        for (Pat p : PATS) {
            if (strict && !p.safe() && !prefixed) {
                continue;   // frase descrittiva: solo le frasi che sono sicuramente tasti
            }
            if (p.need() == 1 && !atStart && !prefixed) {
                continue;
            }
            if (p.need() == 2 && !prefixed) {
                continue;
            }
            int pos = start;
            boolean ok = true;
            for (int k = 0; k < p.words().length; k++) {
                if (k > 0) {
                    pos = nextSig(t, pos);
                }
                if (pos < 0 || !matches(t.get(pos), p.words()[k])) {
                    ok = false;
                    break;
                }
                pos++;
            }
            if (ok) {
                return new Hit(pos, p.icons());
            }
        }
        return null;
    }

    private static Hit match(List<Tok> t, int i, boolean atStart, boolean strict) {
        Tok tok = t.get(i);
        if (tok.type() == WORD && PREFIX.contains(tok.norm())) {
            int nx = nextSig(t, i + 1);
            if (nx >= 0) {
                Hit h = matchAt(t, nx, atStart, true, strict);
                if (h != null) {
                    return h;   // "premi"/"tasto" spariscono insieme al tasto
                }
            }
            return null;
        }
        return matchAt(t, i, atStart, false, strict);
    }

    // ------------------------------------------------------------------
    //  Trasformazione
    // ------------------------------------------------------------------

    /** Pezzi di parole dei tasti: se il testo (normalizzato) non ne contiene nessuno non c'e' niente da fare. */
    private static final String[] STEMS = {"clic", "destr", "sinistr", "shift", "tast", "prem", "trascin", "rotell",
            "scroll", "doppi", "esc", "tab", "ctrl", "alt", "maiusc", "dx", "sx", "q"};

    /** Controllo veloce (color() lo chiama di continuo, anche per le bossbar a ogni tick). */
    private static boolean mayHaveKeys(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); ) {
            int cl = LoreWrap.codeLength(s, i);   // i codici colore (anche in mezzo alle parole) non contano
            if (cl > 0) {
                i += cl;
                continue;
            }
            sb.append(Character.toLowerCase(Tiny.untiny(s.charAt(i))));
            i++;
        }
        String low = sb.toString();
        for (String stem : STEMS) {
            if (low.contains(stem)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Testi per i giocatori con icone LORE (9 px): lore, nomi item, chat, ologrammi. Lo chiama plugin.color() per
     * tutti i testi e LoreWrap per le lore.
     * <ul>
     *     <li>righe di ISTRUZIONE ({@link LoreWrap#isInstruction}): tutte le parole dei tasti, anche "destro", "Q",
     *     "shift" da soli;</li>
     *     <li>altre frasi (descrittive, chat): solo le frasi che sono sicuramente tasti ("click destro", "clicca",
     *     "shift + F", "tasto Q", "premi E", "doppio click").</li>
     * </ul>
     * Funziona sia con i codici & (prima di plugin.color) sia con \u00A7, sfumature e tiny text (dopo).
     */
    public static String iconize(String legacyLine) {
        if (legacyLine == null || legacyLine.isEmpty() || !mayHaveKeys(legacyLine)) {
            return legacyLine;
        }
        // gia' passata (color() e poi LoreWrap): il resto della riga si guarda solo con le frasi sicure
        boolean strict = hasIcon(legacyLine) || !LoreWrap.isInstruction(legacyLine);
        return run(legacyLine, false, strict);
    }

    /**
     * Bossbar/actionbar/titoli (icone GRANDI 12 px): le parole dei tasti diventano icone ovunque nel testo, e i
     * riquadri {key:...} (gia' passati da keyMarks) diventano icone se sono fatti solo di tasti.
     */
    public static String iconizeBig(String legacy) {
        if (legacy == null || legacy.isEmpty() || !mayHaveKeys(legacy)) {
            return legacy;
        }
        boolean marked = legacy.indexOf('\uE000') >= 0;
        // riquadri dei tasti (KEY_OPEN \uE000 ... KEY_CLOSE \uE001): se il testo e' tutto tasti tolgo il riquadro
        if (legacy.indexOf('\uE000') >= 0) {
            StringBuilder sb = new StringBuilder();
            int i = 0;
            while (i < legacy.length()) {
                int open = legacy.indexOf('\uE000', i);
                if (open < 0) {
                    sb.append(legacy, i, legacy.length());
                    break;
                }
                int close = legacy.indexOf('\uE001', open);
                if (close < 0) {
                    sb.append(legacy, i, legacy.length());
                    break;
                }
                sb.append(legacy, i, open);
                String label = legacy.substring(open + 1, close);
                if (onlyKeys(label)) {
                    sb.append(label);
                } else {
                    sb.append(legacy, open, close + 1);
                }
                i = close + 1;
            }
            legacy = sb.toString();
        }
        return run(legacy, true, !marked && !LoreWrap.isInstruction(legacy));
    }

    /** True se il testo e' fatto solo di tasti (dopo la trasformazione non resta nessuna lettera/numero). */
    private static boolean onlyKeys(String label) {
        String r = run(label, true, false);
        if (r.equals(label)) {
            return false;
        }
        for (Tok tok : tokenize(r)) {
            if (tok.type() == WORD) {
                return false;
            }
        }
        return true;
    }

    /** Stato colore dopo tutti i codici del testo (anche quelli in mezzo alle parole). */
    private static String advanceAll(String state, String text) {
        for (int i = 0; i < text.length(); ) {
            int cl = LoreWrap.codeLength(text, i);
            if (cl > 0) {
                state = LoreWrap.advance(state, text.substring(i, i + cl));
                i += cl;
            } else {
                i++;
            }
        }
        return state;
    }

    private static String run(String line, boolean big, boolean strict) {
        List<Tok> t = tokenize(line);
        String cc = line.indexOf('\u00A7') >= 0 ? "\u00A7" : "&";
        char base = big ? BIG_BASE : LORE_BASE;
        StringBuilder out = new StringBuilder(line.length() + 8);
        String state = "";
        boolean changed = false;
        boolean atStart = true;
        boolean needSpace = false;
        int conjAt = -1;
        int i = 0;
        while (i < t.size()) {
            Tok tok = t.get(i);
            if (tok.type() == CODE) {
                out.append(tok.text());
                if (tok.text().length() >= 2) {
                    state = LoreWrap.advance(state, tok.text());
                }
                i++;
                continue;
            }
            if (tok.type() == SPACE) {
                out.append(tok.text());
                needSpace = false;
                i++;
                continue;
            }
            Hit h = tok.type() == WORD && i != conjAt ? match(t, i, atStart, strict) : null;
            if (h == null) {
                // token normale (eventualmente il verbo dopo "per", messo all'imperativo)
                if (needSpace && !(tok.type() == PUNCT && NO_SPACE_BEFORE.indexOf(tok.text().charAt(0)) >= 0)) {
                    out.append(' ');
                }
                needSpace = false;
                String text = tok.text();
                if (i == conjAt) {
                    String imp = conjugate(tok.norm());
                    if (imp != null && !imp.startsWith("trascin")) {   // "trascina" ridiventerebbe un'icona
                        boolean tiny = false;
                        for (int k = 0; k < text.length(); k++) {
                            tiny |= Tiny.isTiny(text.charAt(k));
                        }
                        text = tiny ? Tiny.convert(imp) : imp;
                        changed = true;
                    }
                }
                out.append(text);
                state = advanceAll(state, tok.text());   // sfumature: codici colore dentro la parola
                if (tok.type() == PUNCT) {
                    char c = text.charAt(0);
                    atStart = START_PUNCT.indexOf(c) >= 0;
                } else {
                    atStart = START_WORDS.contains(tok.norm());
                }
                i++;
                continue;
            }
            // icona: i codici colore dentro la frase tolta contano per il colore da rimettere dopo
            int end = h.end();
            // ":" / "=" subito dopo ("click destro: link", "tasto sinistro = pos1")
            int nx = nextSig(t, end);
            if (nx >= 0 && t.get(nx).type() == PUNCT && ":=".indexOf(t.get(nx).text().charAt(0)) >= 0) {
                end = nx + 1;
            }
            // "per" (o "qui per") ridondante: il verbo dopo va all'imperativo
            nx = nextSig(t, end);
            int conj = -1;
            if (nx >= 0 && t.get(nx).type() == WORD) {
                int perAt = -1;
                if (t.get(nx).norm().equals("per")) {
                    perAt = nx;
                } else if (t.get(nx).norm().equals("qui")) {
                    int n2 = nextSig(t, nx + 1);
                    if (n2 >= 0 && t.get(n2).type() == WORD && t.get(n2).norm().equals("per")) {
                        perAt = n2;
                    }
                }
                if (perAt >= 0) {
                    int verb = nextSig(t, perAt + 1);
                    if (verb >= 0) {   // "per" in fondo alla riga resta
                        end = perAt + 1;
                        if (t.get(verb).type() == WORD) {
                            conj = verb;
                        }
                    }
                }
            }
            for (int k = i; k < end; k++) {
                Tok c = t.get(k);
                if ((c.type() == CODE && c.text().length() >= 2) || c.type() == WORD) {
                    state = advanceAll(state, c.text());
                }
            }
            if (needSpace) {
                out.append(' ');
            }
            out.append(cc).append('f');
            for (int icon : h.icons()) {
                out.append((char) (base + icon));
            }
            out.append(state.isEmpty() ? cc + "r" : state);
            changed = true;
            needSpace = true;
            atStart = false;
            conjAt = conj;
            i = end;
        }
        return changed ? out.toString() : line;
    }
}
