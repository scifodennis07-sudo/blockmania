package it.blockmania;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Logica di testo delle task scritte a frase (nessuna dipendenza da Bukkit, si prova con TaskTextCheck):
 * normalizzazione (minuscolo, accenti, 3->e 0->o 1->i 4->a 5->s 7->t, lettere ripetute), distanza di Levenshtein con
 * soglia in base alla lunghezza, dizionario dei sinonimi (task-words.yml), frasi di piu' parole, parole attaccate,
 * numeri in cifre e in lettere, divisione in azioni, pronomi ("togliendole", "le stesse") e player conosciuti.
 * Una parola con due candidati alla stessa distanza non si indovina: l'azione resta incerta con le alternative.
 */
public final class TaskText {

    /** Alternativa per un campo incerto: valore interno, nome da mostrare, distanza (-1 = campo mancante, tutte le scelte). */
    public record Alt(String value, String label, int distance) {
    }

    /** Un'azione capita dal testo (o custom). I campi null mancano; ties = campi con piu' candidati alla pari. */
    public static final class Action {
        public boolean custom;
        public String verb;
        public String what;
        public String spec;
        public Long amount;
        public String rule;
        /** Nome del player conosciuto (con le maiuscole giuste) o null. */
        public String player;
        /** Parola scritta per il player quando non si e' trovato (o era incerto). */
        public String playerQuery;
        public String text = "";
        public String source = "";
        public final Map<String, List<Alt>> ties = new LinkedHashMap<>();
        /** Calcolati da check(): primo campo da sistemare e le sue alternative. */
        public String field;
        public List<Alt> alts = new ArrayList<>();

        public Action copy() {
            Action a = new Action();
            a.custom = custom;
            a.verb = verb;
            a.what = what;
            a.spec = spec;
            a.amount = amount;
            a.rule = rule;
            a.player = player;
            a.playerQuery = playerQuery;
            a.text = text;
            a.source = source;
            for (Map.Entry<String, List<Alt>> e : ties.entrySet()) {
                a.ties.put(e.getKey(), new ArrayList<>(e.getValue()));
            }
            a.field = field;
            a.alts = new ArrayList<>(alts);
            return a;
        }

        @Override
        public String toString() {
            if (custom) {
                return "CUSTOM \"" + text + "\"" + (player == null ? "" : " target=" + player);
            }
            StringBuilder sb = new StringBuilder();
            sb.append(verb == null ? "?" : verb);
            if (amount != null) {
                sb.append(' ').append(amount);
            }
            sb.append(' ').append(what == null ? (isSanction(verb) ? "" : "?") : what);
            if (spec != null) {
                sb.append(':').append(spec);
            }
            if (rule != null) {
                sb.append(" regola ").append(rule);
            }
            sb.append(" -> ").append(player == null ? (playerQuery == null ? "?" : "?" + playerQuery) : player);
            if (field != null) {
                sb.append("   [INCERTO ").append(field).append(": ");
                List<String> l = new ArrayList<>();
                for (Alt a : alts) {
                    l.add(a.value());
                }
                sb.append(l.size() > 8 ? String.join(", ", l.subList(0, 8)) + ", ..." : String.join(", ", l)).append(']');
            }
            return sb.toString().replaceAll(" +", " ");
        }
    }

    // ------------------------------------------------------------------ dizionario

    public static final List<String> VERBS = List.of("give", "remove", "set", "reset", "ban", "warn", "mute");
    public static final List<String> WHATS = List.of("ball", "ricompense", "coin", "blockcoin", "blockshell", "mine", "upgrade",
            "power", "stamina");

    /** Dizionario: sezioni come task-words.yml. */
    public static final class Words {
        public final Map<String, List<String>> verbs = new LinkedHashMap<>();
        public final Map<String, List<String>> whats = new LinkedHashMap<>();
        /** what -> spec -> sinonimi. */
        public final Map<String, Map<String, List<String>>> specs = new LinkedHashMap<>();
        /** verbo -> forme col pronome ("togliendole" = togli le stesse). */
        public final Map<String, List<String>> gerunds = new LinkedHashMap<>();
        public final List<String> same = new ArrayList<>();
        public final List<String> fillers = new ArrayList<>();
        public final List<String> separators = new ArrayList<>();
        public final List<String> preps = new ArrayList<>();
        public final List<String> articles = new ArrayList<>();
        public final List<String> ruleWords = new ArrayList<>();
        public final List<String> levelWords = new ArrayList<>();
        public final List<String> customWords = new ArrayList<>();
    }

    private static List<String> l(String... s) {
        return new ArrayList<>(List.of(s));
    }

    /** Dizionario di default (quello che si scrive in task-words.yml la prima volta). */
    public static Words defaults() {
        Words w = new Words();
        w.verbs.put("give", l("dai", "dare", "dagli", "dalle", "daglieli", "dagliele", "givva", "givvare", "givvagli", "regala",
                "regalare", "regalagli", "aggiungi", "aggiungere", "aggiungigli", "metti", "mettere", "mettigli", "manda",
                "mandare", "mandagli", "passa", "passare", "passagli", "give", "add", "dagliene", "dategliene", "date", "dategli", "datele", "dategliele",
                "regalate", "regalategli", "aggiungete", "aggiungetegli", "mettete", "mettetegli", "mandate", "mandategli",
                "passate", "passategli"));
        w.verbs.put("remove", l("togli", "togliere", "toglili", "togligli", "toglile", "rimuovi", "rimuovere", "rimuovigli",
                "leva", "levare", "levagli", "sottrai", "sottrarre", "prendi", "prendere", "remove", "take", "togligliene",
                "toglietegliene", "levagliene", "togliete",
                "toglietegli", "toglietele", "levate", "levategli", "rimuovete", "rimuovetegli", "sottraete", "prendete"));
        w.verbs.put("set", l("setta", "settare", "settagli", "imposta", "impostare", "impostagli", "imposta a", "porta a",
                "metti a", "setta a", "set", "settate", "settategli", "impostate", "impostategli"));
        w.verbs.put("reset", l("azzera", "azzerare", "azzeragli", "resetta", "resettare", "resettagli", "reset", "azzerate", "azzerategli", "resettate"));
        w.verbs.put("ban", l("banna", "bannare", "bannalo", "bannala", "ban", "bannate", "bannatelo"));
        w.verbs.put("warn", l("warna", "warnare", "warnalo", "warnala", "ammonisci", "ammonire", "warn", "warnate", "warnatelo"));
        w.verbs.put("mute", l("muta", "mutare", "mutalo", "mutala", "silenzia", "mute", "mutate", "mutatelo"));

        w.whats.put("ball", l("palle", "palla", "pall", "ball", "balls", "palline", "pallina", "munizioni"));
        w.whats.put("ricompense", l("ricompense", "ricompensa", "premi", "premio", "rewards", "reward"));
        w.whats.put("coin", l("coin", "coins", "monete", "moneta", "soldi"));
        w.whats.put("blockcoin", l("blockcoin", "blockcoins", "bc", "block coin"));
        w.whats.put("blockshell", l("blockshell", "blockshells", "conchiglie", "conchiglia", "shell", "shells", "block shell"));
        w.whats.put("mine", l("miniere", "miniera", "mine", "mines"));
        w.whats.put("upgrade", l("potenziamenti", "potenziamento", "upgrade", "upgrades"));
        w.whats.put("power", l("poteri", "potere", "power", "colpi potenziati", "colpo potenziato", "potenziato", "potenziati",
                "cariche"));
        w.whats.put("stamina", l("stamina", "energia"));

        Map<String, List<String>> ball = new LinkedHashMap<>();
        ball.put("standardball", l("standard", "standardball", "normale", "normali", "std"));
        ball.put("doubleball", l("double", "doubleball", "doppia", "doppie", "doppio"));
        ball.put("ghostball", l("ghost", "ghostball", "fantasma", "fantasmi"));
        ball.put("explosionball", l("explosion", "explosionball", "esplosiva", "esplosive", "esplosione", "explosive"));
        ball.put("all", l("tutte", "tutti"));
        w.specs.put("ball", ball);
        Map<String, List<String>> rw = new LinkedHashMap<>();
        rw.put("broken", l("rotti", "spaccati", "blocchi spaccati", "blocchi rotti", "broken"));
        rw.put("hits", l("colpi", "colpiti", "blocchi colpiti", "hits"));
        rw.put("bc_earned", l("blockcoin guadagnati", "bc guadagnati"));
        rw.put("bc_spent", l("blockcoin spesi", "bc spesi"));
        rw.put("coin_earned", l("coin guadagnati", "monete guadagnate"));
        rw.put("coin_spent", l("coin spesi", "monete spese"));
        rw.put("platinum", l("platino", "platinum", "platinate", "miniere platinate"));
        rw.put("play", l("tempo", "tempo di gioco", "play"));
        rw.put("pass", l("battlepass", "battle pass", "pass", "bp"));
        w.specs.put("ricompense", rw);
        Map<String, List<String>> up = new LinkedHashMap<>();
        up.put("damage", l("danno", "damage"));
        up.put("cooldown", l("ricarica", "cooldown"));
        up.put("speed", l("velocita", "speed"));
        up.put("capacity", l("capienza", "capacity", "capienza blockshell"));
        up.put("capacity_standardball", l("capienza standard"));
        up.put("capacity_doubleball", l("capienza doppia", "capienza double"));
        up.put("capacity_ghostball", l("capienza ghost", "capienza fantasma"));
        up.put("capacity_explosionball", l("capienza esplosiva", "capienza explosion"));
        up.put("all", l("tutti", "tutte"));
        w.specs.put("upgrade", up);

        w.gerunds.put("remove", l("togliendole", "togliendoli", "togliendogli", "togliendogliele", "togliendola", "togliendolo",
                "levandole", "levandoli", "levandogli", "rimuovendole", "rimuovendoli", "sottraendole", "prendendole"));
        w.gerunds.put("give", l("dandole", "dandoli", "dandogli", "dandogliele", "regalandole", "regalandoli", "passandole",
                "mandandole", "aggiungendole"));
        w.gerunds.put("set", l("settandole", "impostandole"));
        w.gerunds.put("reset", l("azzerandole", "resettandole"));
        w.same.addAll(l("stesse", "stessi", "stessa", "stesso", "uguali", "le stesse", "gli stessi", "la stessa", "lo stesso"));
        w.fillers.addAll(l("il", "lo", "la", "i", "gli", "le", "l", "di", "del", "dello", "della", "dei", "degli", "delle", "d",
                "con", "col", "anche", "quantita", "valore", "player", "giocatore", "utente", "pg", "x"));
        w.separators.addAll(l("e", "ed", "poi", "inoltre", "infine", "dopo"));
        w.preps.addAll(l("a", "al", "allo", "alla", "ai", "agli", "alle", "ad", "da", "dal", "dallo", "dalla", "per", "su", "sul",
                "sulla", "verso"));
        w.articles.addAll(l("un", "una"));
        w.ruleWords.addAll(l("regola", "regolamento", "codice", "art", "articolo"));
        w.levelWords.addAll(l("livelli", "livello", "lvl", "lv"));
        w.customWords.addAll(l("verifica", "verificare", "controlla", "controllare", "guarda", "guardare", "chiedi", "chiedere",
                "vedi", "vedere", "assicurati", "accertati", "se", "spiega", "avvisa", "avvisare", "parla", "parlare", "scrivi",
                "scrivere", "indaga"));
        return w;
    }

    /** Il dizionario come mappa annidata (per scrivere task-words.yml). */
    public static Map<String, Object> toMap(Words w) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verbs", w.verbs);
        m.put("whats", w.whats);
        m.put("specs", w.specs);
        m.put("gerunds", w.gerunds);
        m.put("same", w.same);
        m.put("fillers", w.fillers);
        m.put("separators", w.separators);
        m.put("preps", w.preps);
        m.put("articles", w.articles);
        m.put("rule-words", w.ruleWords);
        m.put("level-words", w.levelWords);
        m.put("custom-words", w.customWords);
        return m;
    }

    // ------------------------------------------------------------------ voci del dizionario

    private enum Cat { VERB, WHAT, SPEC, GERUND, SAME, FILLER, SEP, PREP, ARTICLE, RULEWORD, LEVELWORD, CUSTOM }

    /** Una forma del dizionario: words = chiavi delle parole (normalizzate e senza doppie). */
    private record Entry(Cat cat, String value, String what, String[] words, String text) {
        String key() {
            return String.join("", words);
        }
    }

    private final List<Entry> single = new ArrayList<>();
    private final List<Entry> phrases = new ArrayList<>();
    /** Solo uguaglianza esatta (parole corte e funzionali). */
    private final Map<String, Entry> exact = new LinkedHashMap<>();

    public TaskText(Words w) {
        for (Map.Entry<String, List<String>> e : w.verbs.entrySet()) {
            add(Cat.VERB, e.getKey(), null, e.getValue());
        }
        for (Map.Entry<String, List<String>> e : w.whats.entrySet()) {
            add(Cat.WHAT, e.getKey(), null, e.getValue());
        }
        for (Map.Entry<String, Map<String, List<String>>> e : w.specs.entrySet()) {
            for (Map.Entry<String, List<String>> s : e.getValue().entrySet()) {
                add(Cat.SPEC, s.getKey(), e.getKey(), s.getValue());
            }
        }
        for (Map.Entry<String, List<String>> e : w.gerunds.entrySet()) {
            add(Cat.GERUND, e.getKey(), null, e.getValue());
        }
        add(Cat.SAME, "same", null, w.same);
        addExact(Cat.FILLER, w.fillers);
        addExact(Cat.SEP, w.separators);
        addExact(Cat.PREP, w.preps);
        addExact(Cat.ARTICLE, w.articles);
        addExact(Cat.RULEWORD, w.ruleWords);
        addExact(Cat.LEVELWORD, w.levelWords);
        add(Cat.CUSTOM, "custom", null, w.customWords);
    }

    private void add(Cat cat, String value, String what, Collection<String> forms) {
        if (forms == null) {
            return;
        }
        for (String f : forms) {
            if (f == null || f.isBlank()) {
                continue;
            }
            String[] parts = f.trim().split("\\s+");
            String[] keys = new String[parts.length];
            for (int i = 0; i < parts.length; i++) {
                keys[i] = key(parts[i]);
            }
            Entry e = new Entry(cat, value.toLowerCase(Locale.ROOT), what == null ? null : what.toLowerCase(Locale.ROOT), keys, f);
            (keys.length > 1 ? phrases : single).add(e);
        }
    }

    private void addExact(Cat cat, Collection<String> forms) {
        if (forms == null) {
            return;
        }
        for (String f : forms) {
            if (f != null && !f.isBlank()) {
                exact.putIfAbsent(plain(f.trim()), new Entry(cat, cat.name().toLowerCase(Locale.ROOT), null,
                        new String[]{key(f.trim())}, f));
            }
        }
    }

    // ------------------------------------------------------------------ normalizzazione

    /** Minuscolo senza accenti. */
    public static String plain(String s) {
        String n = Normalizer.normalize(s == null ? "" : s, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return n.toLowerCase(Locale.ROOT);
    }

    /** plain + cifre "leet" dentro le parole (3->e 0->o 1->i 4->a 5->s 7->t). Le parole di sole cifre restano uguali. */
    public static String normalize(String s) {
        String p = plain(s);
        if (!p.matches(".*\\p{L}.*")) {
            return p;
        }
        StringBuilder sb = new StringBuilder();
        for (char c : p.toCharArray()) {
            sb.append(switch (c) {
                case '3' -> 'e';
                case '0' -> 'o';
                case '1' -> 'i';
                case '4' -> 'a';
                case '5' -> 's';
                case '7' -> 't';
                default -> c;
            });
        }
        return sb.toString();
    }

    /** Chiave di confronto: normalize senza le lettere ripetute ("palle" -> "pale", "standerdepall" -> "standerdepal"). */
    public static String key(String s) {
        String n = normalize(s);
        StringBuilder sb = new StringBuilder();
        char last = 0;
        for (char c : n.toCharArray()) {
            if (c != last) {
                sb.append(c);
            }
            last = c;
        }
        return sb.toString();
    }

    /** Levenshtein con lo scambio di due lettere vicine che conta 1 ("crakc" -> "crack"). */
    public static int levenshtein(String a, String b) {
        int n = a.length();
        int m = b.length();
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) {
            d[i][0] = i;
        }
        for (int j = 0; j <= m; j++) {
            d[0][j] = j;
        }
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                int v = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
                if (i > 1 && j > 1 && a.charAt(i - 1) == b.charAt(j - 2) && a.charAt(i - 2) == b.charAt(j - 1)) {
                    v = Math.min(v, d[i - 2][j - 2] + 1);
                }
                d[i][j] = v;
            }
        }
        return d[n][m];
    }

    /** Errori ammessi in base alla lunghezza: fino a 3 lettere 0, 4-5 una, 6-8 due, poi tre. */
    public static int threshold(int len) {
        return len <= 3 ? 0 : len <= 5 ? 1 : len <= 8 ? 2 : 3;
    }

    // ------------------------------------------------------------------ numeri

    private static final String[] UNITS = {"zero", "uno", "due", "tre", "quattro", "cinque", "sei", "sette", "otto", "nove",
            "dieci", "undici", "dodici", "tredici", "quattordici", "quindici", "sedici", "diciassette", "diciotto", "diciannove"};
    private static final String[] TENS = {"", "", "venti", "trenta", "quaranta", "cinquanta", "sessanta", "settanta", "ottanta",
            "novanta"};

    /** Numero in cifre ("20", "1.000", "20k") o in lettere ("venti", "centocinquanta", "duemila"); null se non lo e'. */
    public static Long number(String raw) {
        String s = plain(raw).trim();
        if (s.matches("\\d{1,12}")) {
            return Long.parseLong(s);
        }
        if (s.matches("\\d{1,3}(\\.\\d{3})+")) {
            return Long.parseLong(s.replace(".", ""));
        }
        if (s.matches("\\d{1,9}k")) {
            return Long.parseLong(s.substring(0, s.length() - 1)) * 1000L;
        }
        if (!s.matches("[a-z]+")) {
            return null;
        }
        Long n = words(s);
        return n == null || n <= 0 && !s.equals("zero") ? null : n;
    }

    private static Long words(String s) {
        if (s.isEmpty()) {
            return 0L;
        }
        int k = s.indexOf("mila");
        if (k > 0) {
            Long left = below1000(s.substring(0, k));
            Long right = below1000(s.substring(k + 4));
            return left == null || right == null || left < 2 ? null : left * 1000 + right;
        }
        if (s.startsWith("mille")) {
            Long right = below1000(s.substring(5));
            return right == null ? null : 1000 + right;
        }
        Long n = below1000(s);
        return n == null || (n == 0 && !s.equals("zero")) ? null : n;
    }

    private static Long below1000(String s) {
        if (s.isEmpty()) {
            return 0L;
        }
        int k = s.indexOf("cento");
        if (k >= 0) {
            long h = 1;
            if (k > 0) {
                Long left = below100(s.substring(0, k));
                if (left == null || left < 2 || left > 9) {
                    return null;
                }
                h = left;
            }
            Long right = below100(s.substring(k + 5));
            return right == null ? null : h * 100 + right;
        }
        return below100(s);
    }

    private static Long below100(String s) {
        if (s.isEmpty()) {
            return 0L;
        }
        for (int i = 0; i < UNITS.length; i++) {
            if (UNITS[i].equals(s)) {
                return (long) i;
            }
        }
        for (int t = 2; t < TENS.length; t++) {
            String full = TENS[t];
            String cut = full.substring(0, full.length() - 1); // vent, trent ... (ventuno, ventotto)
            if (s.equals(full)) {
                return t * 10L;
            }
            for (String pre : new String[]{full, cut}) {
                if (s.startsWith(pre) && s.length() > pre.length()) {
                    String rest = s.substring(pre.length());
                    for (int u = 1; u <= 9; u++) {
                        if (UNITS[u].equals(rest)) {
                            return t * 10L + u;
                        }
                    }
                    if (rest.equals("tre")) {
                        return t * 10L + 3;
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ parole del testo

    private record Tok(String raw, String plain, String key, int start, int end, boolean punct) {
    }

    private static List<Tok> tokenize(String text) {
        List<Tok> out = new ArrayList<>();
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                int j = i;
                while (j < n) {
                    char d = text.charAt(j);
                    boolean dotInNumber = d == '.' && j > i && Character.isDigit(text.charAt(j - 1)) && j + 1 < n
                            && Character.isDigit(text.charAt(j + 1));
                    if (Character.isLetterOrDigit(d) || d == '_' || dotInNumber) {
                        j++;
                    } else {
                        break;
                    }
                }
                String raw = text.substring(i, j);
                String p = plain(raw);
                // "20palle" -> 20 + palle (ma "20k" resta un numero)
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)([a-z]{2,})$").matcher(p);
                if (m.matches()) {
                    int cut = i + m.group(1).length();
                    out.add(new Tok(text.substring(i, cut), m.group(1), m.group(1), i, cut, false));
                    out.add(new Tok(text.substring(cut, j), m.group(2), key(m.group(2)), cut, j, false));
                } else {
                    out.add(new Tok(raw, p, key(raw), i, j, false));
                }
                i = j;
            } else if (c == ',' || c == ';' || c == '.' || c == '!' || c == '?' || c == '\n') {
                out.add(new Tok(String.valueOf(c), String.valueOf(c), String.valueOf(c), i, i + 1, true));
                i++;
            } else {
                i++;
            }
        }
        return out;
    }

    private enum IType { VERB, WHAT, SPEC, GERUND, SAME, NUM, RULE, PLAYER, BADPLAYER, SEP, PREP, FILLER, ARTICLE, RULEWORD,
        LEVELWORD, CUSTOM, UNKNOWN }

    /**
     * Elemento capito. value = id (verbo, cosa, spec, player...), whats = per le SPEC le cose possibili,
     * ties = candidati alla pari (stesso tipo), raw = testo.
     */
    private static final class Item {
        IType type;
        String value;
        List<String> whats = new ArrayList<>();
        List<Alt> ties = new ArrayList<>();
        String raw;
        int start;
        int end;
        Long num;

        Item(IType type, String value, String raw, int start, int end) {
            this.type = type;
            this.value = value;
            this.raw = raw;
            this.start = start;
            this.end = end;
        }
    }

    private record Match(Entry entry, int distance) {
    }

    /** Migliori voci (stessa distanza minima) per una chiave, fra le voci singole. */
    private List<Match> best(String key, boolean needZeroOk) {
        int bestD = Integer.MAX_VALUE;
        List<Match> out = new ArrayList<>();
        int thr = threshold(key.length());
        for (Entry e : single) {
            String ek = e.key();
            if (Math.abs(ek.length() - key.length()) > thr) {
                continue;
            }
            int d = levenshtein(key, ek);
            if (d > thr || d > threshold(ek.length()) && d > 0) {
                continue;
            }
            if (d < bestD) {
                bestD = d;
                out.clear();
            }
            if (d == bestD) {
                out.add(new Match(e, d));
            }
        }
        return out;
    }

    private static IType typeOf(Cat c) {
        return switch (c) {
            case VERB -> IType.VERB;
            case WHAT -> IType.WHAT;
            case SPEC -> IType.SPEC;
            case GERUND -> IType.GERUND;
            case SAME -> IType.SAME;
            case FILLER -> IType.FILLER;
            case SEP -> IType.SEP;
            case PREP -> IType.PREP;
            case ARTICLE -> IType.ARTICLE;
            case RULEWORD -> IType.RULEWORD;
            case LEVELWORD -> IType.LEVELWORD;
            case CUSTOM -> IType.CUSTOM;
        };
    }

    /** Item da un gruppo di voci alla pari: se hanno valori diversi (stesso tipo) l'item resta incerto. */
    private Item fromMatches(List<Match> ms, String raw, int start, int end) {
        // tipo: quello della prima voce; le altre di tipo diverso si ignorano (vince l'ordine del dizionario)
        Cat cat = ms.get(0).entry().cat();
        Item it = new Item(typeOf(cat), ms.get(0).entry().value(), raw, start, end);
        Set<String> values = new LinkedHashSet<>();
        for (Match m : ms) {
            if (m.entry().cat() != cat) {
                continue;
            }
            if (cat == Cat.SPEC) {
                if (!it.whats.contains(m.entry().what())) {
                    it.whats.add(m.entry().what());
                }
            }
            if (values.add(m.entry().value())) {
                it.ties.add(new Alt(m.entry().value(), labelOf(cat, m.entry().value(), m.entry().what()), m.distance()));
            }
        }
        if (values.size() <= 1) {
            it.ties.clear();
        } else {
            it.value = null;
        }
        return it;
    }

    private record PMatch(String name, int distance) {
    }

    /**
     * Player piu' vicini: il migliore e quelli a una lettera da lui (i nomi sono liberi, meglio chiedere che sbagliare).
     * Un nome che inizia con quello scritto (almeno 3 lettere) conta distanza 1.
     */
    private static List<PMatch> bestPlayers(Tok t, Collection<String> players, int maxD) {
        List<PMatch> all = new ArrayList<>();
        int bestD = Integer.MAX_VALUE;
        for (String name : players) {
            String lp = plain(name);
            int d = Math.min(levenshtein(t.plain(), lp), levenshtein(t.key(), key(name)));
            if (d > 0 && t.plain().length() >= 3 && lp.startsWith(t.plain())) {
                d = 1;
            }
            if (d > Math.max(maxD, 1) || d > maxD && !lp.startsWith(t.plain())) {
                continue;
            }
            all.add(new PMatch(name, d));
            bestD = Math.min(bestD, d);
        }
        List<PMatch> out = new ArrayList<>();
        for (PMatch p : all) {
            if (p.distance() == bestD || bestD > 0 && p.distance() <= bestD + 1) {
                out.add(p);
            }
        }
        out.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        return out;
    }

    /** I player conosciuti piu' simili (per le alternative quando non si trova). */
    private static List<Alt> nearestPlayers(String query, Collection<String> players, int max) {
        List<Alt> all = new ArrayList<>();
        String q = plain(query);
        for (String name : players) {
            all.add(new Alt(name, name, levenshtein(q, plain(name))));
        }
        all.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        return new ArrayList<>(all.subList(0, Math.min(max, all.size())));
    }

    private List<Item> items(List<Tok> toks, Collection<String> players) {
        List<Item> out = new ArrayList<>();
        Map<String, String> exactPlayers = new LinkedHashMap<>();
        for (String p : players) {
            exactPlayers.putIfAbsent(plain(p), p);
        }
        int i = 0;
        while (i < toks.size()) {
            Tok t = toks.get(i);
            boolean afterPrep = !out.isEmpty() && out.get(out.size() - 1).type == IType.PREP;
            if (t.punct()) {
                out.add(new Item(IType.SEP, ",", t.raw(), t.start(), t.end()));
                i++;
                continue;
            }
            // codice regola (1.2) o numero
            if (t.plain().matches("\\d+\\.\\d+") && !t.plain().matches("\\d{1,3}(\\.\\d{3})+")) {
                out.add(new Item(IType.RULE, t.plain(), t.raw(), t.start(), t.end()));
                i++;
                continue;
            }
            Long num = number(t.raw());
            if (num != null && !(afterPrep && exactPlayers.containsKey(t.plain()))) {
                Item it = new Item(IType.NUM, String.valueOf(num), t.raw(), t.start(), t.end());
                it.num = num;
                if (t.plain().matches("\\d{1,3}(\\.\\d{3})+")) {
                    it.value = t.plain(); // potrebbe essere anche un codice regola
                }
                out.add(it);
                i++;
                continue;
            }
            // player esatto dopo una preposizione
            String exactP = exactPlayers.get(t.plain());
            if (exactP != null && afterPrep) {
                out.add(new Item(IType.PLAYER, exactP, t.raw(), t.start(), t.end()));
                i++;
                continue;
            }
            // parole corte esatte (preposizioni, articoli...)
            Entry ex = exact.get(t.plain());
            if (ex != null && exactP == null) {
                out.add(new Item(typeOf(ex.cat()), ex.value(), t.raw(), t.start(), t.end()));
                i++;
                continue;
            }
            // frasi di 3 e 2 parole
            Item phrase = phrase(toks, i);
            if (phrase != null) {
                out.add(phrase);
                i += (int) phrase.num.longValue();
                phrase.num = null;
                continue;
            }
            List<Match> ms = best(t.key(), true);
            int dictD = ms.isEmpty() ? Integer.MAX_VALUE : ms.get(0).distance();
            if (dictD == 0) {
                out.add(fromMatches(ms, t.raw(), t.start(), t.end()));
                i++;
                continue;
            }
            if (exactP != null) {
                out.add(new Item(IType.PLAYER, exactP, t.raw(), t.start(), t.end()));
                i++;
                continue;
            }
            List<PMatch> pm = bestPlayers(t, players, threshold(t.plain().length()));
            int playD = pm.isEmpty() ? Integer.MAX_VALUE : pm.get(0).distance();
            boolean preferPlayer = afterPrep ? playD <= dictD : playD < dictD;
            if (!pm.isEmpty() && preferPlayer) {
                Item it = new Item(IType.PLAYER, pm.get(0).name(), t.raw(), t.start(), t.end());
                if (pm.size() > 1) {
                    it.value = null;
                    for (PMatch p : pm) {
                        it.ties.add(new Alt(p.name(), p.name(), p.distance()));
                    }
                }
                out.add(it);
                i++;
                continue;
            }
            if (!ms.isEmpty()) {
                out.add(fromMatches(ms, t.raw(), t.start(), t.end()));
                i++;
                continue;
            }
            // parole attaccate: "standerdepall" = standard + pall
            List<Item> split = split(t);
            if (split != null) {
                out.addAll(split);
                i++;
                continue;
            }
            if (afterPrep && t.plain().matches("[a-z0-9_]{3,16}")) {
                Item it = new Item(IType.BADPLAYER, t.raw(), t.raw(), t.start(), t.end());
                it.ties.addAll(nearestPlayers(t.raw(), players, 5));
                out.add(it);
            } else {
                out.add(new Item(IType.UNKNOWN, t.plain(), t.raw(), t.start(), t.end()));
            }
            i++;
        }
        return out;
    }

    /** Frase del dizionario (2-3 parole) che parte da i; item.num = quante parole usa. */
    private Item phrase(List<Tok> toks, int i) {
        for (int n = 3; n >= 2; n--) {
            if (i + n > toks.size()) {
                continue;
            }
            boolean ok = true;
            for (int k = i; k < i + n; k++) {
                Tok t = toks.get(k);
                if (t.punct() || t.plain().matches("\\d.*")) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                continue;
            }
            List<Match> found = new ArrayList<>();
            int bestD = Integer.MAX_VALUE;
            for (Entry e : phrases) {
                if (e.words().length != n) {
                    continue;
                }
                int sum = 0;
                for (int k = 0; k < n && sum <= 99; k++) {
                    String tk = toks.get(i + k).key();
                    int d = levenshtein(tk, e.words()[k]);
                    sum += d <= threshold(Math.min(tk.length(), e.words()[k].length())) ? d : 100;
                }
                if (sum >= 100) {
                    continue;
                }
                // "metti a" / "porta a": solo se dopo c'e' un numero
                String lastWord = e.text().trim().substring(e.text().trim().lastIndexOf(' ') + 1);
                if (exact.containsKey(plain(lastWord)) && exact.get(plain(lastWord)).cat() == Cat.PREP) {
                    if (i + n >= toks.size() || number(toks.get(i + n).raw()) == null) {
                        continue;
                    }
                }
                if (sum < bestD) {
                    bestD = sum;
                    found.clear();
                }
                if (sum == bestD) {
                    found.add(new Match(e, sum));
                }
            }
            if (!found.isEmpty()) {
                Tok a = toks.get(i);
                Tok b = toks.get(i + n - 1);
                Item it = fromMatches(found, a.raw() + "..." + b.raw(), a.start(), b.end());
                it.num = (long) n;
                return it;
            }
        }
        return null;
    }

    /** Parola fatta da due voci attaccate (una delle due esatta). */
    private List<Item> split(Tok t) {
        String k = t.key();
        if (k.length() < 6) {
            return null;
        }
        List<Item> bestSplit = null;
        int bestSum = Integer.MAX_VALUE;
        for (int p = 3; p <= k.length() - 3; p++) {
            String a = k.substring(0, p);
            String b = k.substring(p);
            List<Match> ma = best(a, false);
            List<Match> mb = best(b, false);
            if (ma.isEmpty() || mb.isEmpty()) {
                continue;
            }
            int da = ma.get(0).distance();
            int db = mb.get(0).distance();
            if (da != 0 && db != 0) {
                continue;
            }
            if (isFunctional(ma) || isFunctional(mb)) {
                continue;
            }
            if (da + db < bestSum) {
                bestSum = da + db;
                bestSplit = List.of(fromMatches(ma, t.raw(), t.start(), t.end()), fromMatches(mb, t.raw(), t.start(), t.end()));
            }
        }
        return bestSplit;
    }

    private static boolean isFunctional(List<Match> ms) {
        Cat c = ms.get(0).entry().cat();
        return c == Cat.CUSTOM || c == Cat.SAME;
    }

    // ------------------------------------------------------------------ azioni

    /** Divide il testo in azioni e le completa (con check). players = nomi dei player conosciuti. */
    public List<Action> parse(String text, Collection<String> players) {
        return parse(text, players, null);
    }

    /** Come parse; defaultPlayer = player sottinteso ("toglietegli") quando l'azione non ne nomina uno. */
    public List<Action> parse(String text, Collection<String> players, String defaultPlayer) {
        String src = text == null ? "" : text;
        List<Item> items = items(tokenize(src), players);
        // segmenti
        List<List<Item>> segs = new ArrayList<>();
        List<Item> cur = new ArrayList<>();
        for (Item it : items) {
            boolean curCustom = has(cur, IType.CUSTOM);
            boolean curVerb = has(cur, IType.VERB) || has(cur, IType.GERUND);
            boolean punct = it.type == IType.SEP && it.value.equals(",");
            boolean cut;
            if (it.type == IType.SEP) {
                cut = punct || !curCustom || it.raw.equalsIgnoreCase("poi");
            } else if (it.type == IType.VERB || it.type == IType.GERUND) {
                cut = curVerb && !curCustom;
            } else {
                cut = false;
            }
            if (cut && !cur.isEmpty()) {
                segs.add(cur);
                cur = new ArrayList<>();
            }
            if (it.type != IType.SEP) {
                cur.add(it);
            }
        }
        if (!cur.isEmpty()) {
            segs.add(cur);
        }
        List<Action> out = new ArrayList<>();
        Action prev = null;
        for (List<Item> seg : segs) {
            List<Action> made = build(seg, src, prev, players, defaultPlayer);
            for (Action a : made) {
                check(a, players);
                out.add(a);
            }
            for (Action a : made) {
                if (!a.custom) {
                    prev = a;
                }
            }
        }
        return out;
    }

    private static boolean has(List<Item> seg, IType t) {
        for (Item it : seg) {
            if (it.type == t) {
                return true;
            }
        }
        return false;
    }

    private static int count(List<Item> seg, IType t) {
        int n = 0;
        for (Item it : seg) {
            if (it.type == t) {
                n++;
            }
        }
        return n;
    }

    private List<Action> build(List<Item> seg, String src, Action prev, Collection<String> players, String defaultPlayer) {
        int from = seg.get(0).start;
        int to = seg.get(seg.size() - 1).end;
        String source = src.substring(from, Math.min(to, src.length())).trim();
        boolean hasVerb = has(seg, IType.VERB) || has(seg, IType.GERUND);
        boolean ref = has(seg, IType.GERUND) || has(seg, IType.SAME);
        int unknown = count(seg, IType.UNKNOWN);
        boolean actionBits = has(seg, IType.WHAT) || has(seg, IType.SPEC) || has(seg, IType.NUM) || ref
                || has(seg, IType.PLAYER) || has(seg, IType.BADPLAYER);
        List<String> segPlayers = new ArrayList<>();
        for (Item it : seg) {
            if (it.type == IType.PLAYER && it.value != null && !segPlayers.contains(it.value)) {
                segPlayers.add(it.value);
            }
        }
        boolean custom = has(seg, IType.CUSTOM) || (!hasVerb && (unknown >= 2 || !actionBits))
                || (hasVerb && unknown >= 2 && !has(seg, IType.WHAT) && !has(seg, IType.SPEC) && !sanctionVerb(seg));
        if (custom) {
            Action a = new Action();
            a.custom = true;
            a.text = source;
            a.source = source;
            a.player = segPlayers.isEmpty() ? null : segPlayers.get(0);
            return List.of(a);
        }
        Action a = new Action();
        a.source = source;
        Item specItem = null;
        Item badPlayer = null;
        Item tiedPlayer = null;
        boolean ruleWord = false;
        boolean article = false;
        boolean mineSingular = false;
        Long amount = null;
        String dottedNum = null;
        for (Item it : seg) {
            switch (it.type) {
                case VERB, GERUND -> {
                    if (a.verb == null && !a.ties.containsKey("verb")) {
                        if (it.value == null) {
                            a.ties.put("verb", it.ties);
                        } else {
                            a.verb = it.value;
                        }
                    }
                }
                case WHAT -> {
                    if (a.what == null && !a.ties.containsKey("what")) {
                        if (it.value == null) {
                            a.ties.put("what", it.ties);
                        } else {
                            a.what = it.value;
                            mineSingular = it.value.equals("mine") && plain(it.raw).equals("miniera");
                        }
                    }
                }
                case SPEC -> {
                    if (specItem == null) {
                        specItem = it;
                    }
                }
                case NUM -> {
                    if (ruleWord && a.rule == null) {
                        a.rule = it.raw;
                    } else if (amount == null) {
                        amount = it.num;
                        dottedNum = it.value != null && it.value.contains(".") ? it.value : null;
                    }
                }
                case RULE -> {
                    if (a.rule == null) {
                        a.rule = it.value;
                    }
                }
                case RULEWORD -> ruleWord = true;
                case ARTICLE -> article = true;
                case BADPLAYER -> {
                    if (badPlayer == null) {
                        badPlayer = it;
                    }
                }
                case PLAYER -> {
                    if (it.value == null && tiedPlayer == null) {
                        tiedPlayer = it;
                    }
                }
                default -> {
                }
            }
        }
        if (a.verb == null && !a.ties.containsKey("verb") && prev != null) {
            a.verb = prev.verb;
        }
        boolean sanction = isSanction(a.verb);
        if (sanction) {
            a.what = null;
            a.ties.remove("what");
            if (a.rule == null && dottedNum != null) {
                a.rule = dottedNum;
            }
            if (a.rule == null && !hasVerb && prev != null) {
                a.rule = prev.rule;
            }
        } else {
            a.rule = null;
            // spec: filtra per cosa
            if (specItem != null) {
                List<String> whats = specItem.whats;
                if (a.what == null && !a.ties.containsKey("what")) {
                    if (whats.size() == 1) {
                        a.what = whats.get(0);
                    } else if (whats.size() > 1) {
                        List<Alt> alts = new ArrayList<>();
                        for (String w : whats) {
                            alts.add(new Alt(w, labelWhat(w), 0));
                        }
                        a.ties.put("what", alts);
                    }
                }
                if (a.what != null && whats.contains(a.what)) {
                    if (specItem.value == null) {
                        List<Alt> alts = new ArrayList<>();
                        for (Alt t : specItem.ties) {
                            alts.add(t);
                        }
                        a.ties.put("spec", alts);
                    } else {
                        a.spec = specItem.value;
                    }
                } else if (a.what == null && whats.size() > 1 && specItem.value != null) {
                    a.spec = specItem.value; // es. "tutte": la cosa e' incerta ma la spec e' la stessa
                }
            }
            if (prev != null && !prev.custom && !isSanction(prev.verb)) {
                if (a.what == null && !a.ties.containsKey("what") && specItem == null) {
                    a.what = prev.what;
                    a.spec = prev.spec;
                } else if (a.spec == null && ref && a.what != null && a.what.equals(prev.what)) {
                    a.spec = prev.spec;
                }
            }
            if (amount == null && article) {
                amount = 1L;
            }
            // "dagli la miniera 4" = portalo alla miniera 4 ; "togligli la miniera" = una miniera in meno
            if ("mine".equals(a.what) && mineSingular && !article) {
                if (amount != null && "give".equals(a.verb)) {
                    a.verb = "set";
                } else if (amount == null && ("give".equals(a.verb) || "remove".equals(a.verb))) {
                    amount = 1L;
                }
            }
            if (amount == null && prev != null && !prev.custom && (ref || !hasVerb)) {
                amount = prev.amount;
            }
            a.amount = "reset".equals(a.verb) ? null : amount;
        }
        if (badPlayer != null) {
            a.playerQuery = badPlayer.raw;
            a.ties.put("target", new ArrayList<>(badPlayer.ties));
        } else if (tiedPlayer != null && segPlayers.isEmpty()) {
            a.playerQuery = tiedPlayer.raw;
            a.ties.put("target", new ArrayList<>(tiedPlayer.ties));
        }
        if (segPlayers.isEmpty() && badPlayer == null && tiedPlayer == null && prev != null && !prev.custom) {
            a.player = prev.player;
        }
        if (a.player == null && segPlayers.isEmpty() && badPlayer == null && tiedPlayer == null && defaultPlayer != null) {
            a.player = defaultPlayer;
        }
        if (segPlayers.size() <= 1) {
            a.player = segPlayers.isEmpty() ? a.player : segPlayers.get(0);
            return List.of(a);
        }
        // piu' player nella stessa azione: una per player
        List<Action> out = new ArrayList<>();
        for (String p : segPlayers) {
            Action c = a.copy();
            c.player = p;
            out.add(c);
        }
        return out;
    }

    private static boolean sanctionVerb(List<Item> seg) {
        for (Item it : seg) {
            if ((it.type == IType.VERB || it.type == IType.GERUND) && isSanction(it.value)) {
                return true;
            }
        }
        return false;
    }

    static boolean isSanction(String verb) {
        return "ban".equals(verb) || "warn".equals(verb) || "mute".equals(verb);
    }

    /** Ordine dei campi: verbo, cosa, specifica, player, quantita', regola. */
    public static final List<String> FIELDS = List.of("verb", "what", "spec", "target", "amount", "rule");

    /** Calcola il primo campo da sistemare (field) e le alternative; field null = azione completa. */
    public void check(Action a, Collection<String> players) {
        a.field = null;
        a.alts = new ArrayList<>();
        if (a.custom) {
            return;
        }
        for (String f : FIELDS) {
            if (!needed(a, f) || value(a, f) != null) {
                continue;
            }
            a.field = f;
            List<Alt> tie = a.ties.get(f);
            if (tie != null) {
                a.alts = new ArrayList<>(tie);
            } else {
                a.alts = all(a, f, players);
            }
            return;
        }
    }

    private static boolean needed(Action a, String f) {
        boolean sanction = isSanction(a.verb);
        return switch (f) {
            case "verb", "target" -> true;
            case "what" -> a.verb != null && !sanction;
            case "spec" -> !sanction && ("ball".equals(a.what) || "ricompense".equals(a.what) || "upgrade".equals(a.what));
            case "amount" -> "give".equals(a.verb) || "remove".equals(a.verb) || "set".equals(a.verb);
            case "rule" -> sanction;
            default -> false;
        };
    }

    private static Object value(Action a, String f) {
        return switch (f) {
            case "verb" -> a.verb;
            case "what" -> a.what;
            case "spec" -> a.spec;
            case "target" -> a.player;
            case "amount" -> a.amount;
            case "rule" -> a.rule;
            default -> null;
        };
    }

    /** Applica una scelta a un campo (value = id interno, nome player, numero, codice regola) e ricalcola. */
    public void set(Action a, String field, String value, Collection<String> players) {
        a.ties.remove(field);
        switch (field) {
            case "verb" -> {
                a.verb = value;
                if (isSanction(value)) {
                    a.what = null;
                    a.spec = null;
                    a.amount = null;
                } else {
                    a.rule = null;
                }
            }
            case "what" -> {
                if (!value.equals(a.what)) {
                    String keep = a.spec;
                    a.what = value;
                    a.spec = null;
                    if ("all".equals(keep) && ("ball".equals(value) || "upgrade".equals(value))) {
                        a.spec = keep;
                    }
                }
            }
            case "spec" -> a.spec = value;
            case "target" -> {
                a.player = value;
                a.playerQuery = null;
            }
            case "amount" -> {
                Long n = number(value);
                a.amount = n;
            }
            case "rule" -> a.rule = value;
            default -> {
            }
        }
        check(a, players);
    }

    private List<Alt> all(Action a, String f, Collection<String> players) {
        List<Alt> out = new ArrayList<>();
        switch (f) {
            case "verb" -> {
                for (String v : VERBS) {
                    out.add(new Alt(v, labelVerb(v), -1));
                }
            }
            case "what" -> {
                for (String w : WHATS) {
                    out.add(new Alt(w, labelWhat(w), -1));
                }
            }
            case "spec" -> {
                Set<String> seen = new LinkedHashSet<>();
                for (Entry e : single) {
                    if (e.cat() == Cat.SPEC && e.what().equals(a.what) && seen.add(e.value())) {
                        out.add(new Alt(e.value(), labelOf(Cat.SPEC, e.value(), e.what()), -1));
                    }
                }
                for (Entry e : phrases) {
                    if (e.cat() == Cat.SPEC && e.what().equals(a.what) && seen.add(e.value())) {
                        out.add(new Alt(e.value(), labelOf(Cat.SPEC, e.value(), e.what()), -1));
                    }
                }
            }
            case "target" -> {
                if (a.playerQuery != null) {
                    out.addAll(nearestPlayers(a.playerQuery, players, 5));
                }
            }
            default -> {
                // amount e rule: niente lista, la GUI chiede il valore
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ ordini (semplici, condizionali, custom)

    /** Un ramo: condizione (testo, null = nessuna), azioni capite, nothing = "non fare nulla". */
    public static final class BranchText {
        public String condition;
        public boolean nothing;
        public final List<Action> actions = new ArrayList<>();

        @Override
        public String toString() {
            if (nothing) {
                return (condition == null ? "" : "[" + condition + "] ") + "non fare nulla";
            }
            List<String> l = new ArrayList<>();
            for (Action a : actions) {
                l.add(a.toString());
            }
            return (condition == null ? "" : "[" + condition + "] ") + String.join(" + ", l);
        }
    }

    /** Un ordine: testo originale, tipo (simple / conditional / custom) e rami. */
    public static final class OrderText {
        public String text = "";
        public String type = "simple";
        public final List<BranchText> branches = new ArrayList<>();

        @Override
        public String toString() {
            List<String> l = new ArrayList<>();
            for (BranchText b : branches) {
                l.add(b.toString());
            }
            return type.toUpperCase(Locale.ROOT) + (type.equals("custom") ? " \"" + text + "\"" : "") + " | "
                    + String.join(" | ", l);
        }
    }

    private static final java.util.regex.Pattern COND_START = java.util.regex.Pattern.compile(
            "(?iu)^\\s*(se|nel caso|in caso|qualora|casomai)\\b");
    /**
     * Inizio di un ramo: a inizio frase o dopo , ; : (anche "e se", "ma se") qualunque marcatore; "se invece",
     * "altrimenti", "se no" anche senza punteggiatura prima.
     */
    private static final java.util.regex.Pattern BRANCH = java.util.regex.Pattern.compile(
            "(?iu)(?:(?:^|[,;:]\\s*(?:e\\s+|ma\\s+|mentre\\s+)?)\\s*(se invece|invece se|se no|sennò|senno|altrimenti|"
                    + "nel caso in cui|nel caso|in caso|qualora|casomai|se)|\\s(se invece|invece se|se no|sennò|senno|altrimenti))\\b");
    private static final java.util.regex.Pattern NOTHING = java.util.regex.Pattern.compile(
            "(?iu)\\b(non (fare|fate|fai|faccia|si fa) (niente|nulla)|lascia(te)? (stare|perdere)|niente da fare|"
                    + "nessuna azione|non serve (niente|nulla)|non fare|ignora)\\b");

    /**
     * Divide il testo in ordini (frasi separate da . ! ? o a capo). Una frase che inizia con "se" / "nel caso" e' un
     * ordine condizionale: un ramo per ogni "se ...", "se invece ...", "altrimenti" (dopo una virgola), la condizione e'
     * il testo fino al primo verbo, poi le azioni del ramo ("non fare niente" = ramo nulla). In coda c'e' sempre il ramo
     * "non fare nulla". Le altre frasi: le azioni capite fanno un ordine semplice, i pezzi non capiti un ordine custom.
     */
    public List<OrderText> parseOrders(String text, Collection<String> players) {
        List<OrderText> out = new ArrayList<>();
        for (String chunk : sentences(text == null ? "" : text)) {
            if (COND_START.matcher(chunk).find()) {
                out.add(conditional(chunk, players));
                continue;
            }
            List<Action> acts = parse(chunk, players);
            OrderText simple = null;
            for (Action a : acts) {
                if (a.custom) {
                    OrderText c = new OrderText();
                    c.type = "custom";
                    c.text = a.text;
                    BranchText b = new BranchText();
                    c.branches.add(b);
                    out.add(c);
                } else {
                    if (simple == null) {
                        simple = new OrderText();
                        simple.text = chunk;
                        simple.branches.add(new BranchText());
                        out.add(simple);
                    }
                    simple.branches.get(0).actions.add(a);
                }
            }
        }
        return out;
    }

    /** Frasi: divide su . ! ? e a capo (non sul punto dentro un numero: 1.2, 1.000). Il ; separa i rami. */
    static List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean dotInNumber = c == '.' && i > 0 && i + 1 < text.length() && Character.isDigit(text.charAt(i - 1))
                    && Character.isDigit(text.charAt(i + 1));
            if ((c == '.' && !dotInNumber) || c == '!' || c == '?' || c == '\n') {
                if (!cur.toString().isBlank()) {
                    out.add(cur.toString().trim());
                }
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().trim());
        }
        return out;
    }

    private OrderText conditional(String chunk, Collection<String> players) {
        OrderText o = new OrderText();
        o.type = "conditional";
        o.text = chunk;
        java.util.regex.Matcher m = BRANCH.matcher(chunk);
        List<int[]> marks = new ArrayList<>(); // {inizio del ramo, fine del marcatore}
        List<String> words = new ArrayList<>();
        while (m.find()) {
            marks.add(new int[]{m.start(), m.end()});
            words.add(plain(m.group(1) != null ? m.group(1) : m.group(2)));
        }
        String orderPlayer = null;
        for (int k = 0; k < marks.size(); k++) {
            int bodyStart = marks.get(k)[1];
            int bodyEnd = k + 1 < marks.size() ? marks.get(k + 1)[0] : chunk.length();
            String body = chunk.substring(bodyStart, bodyEnd).trim();
            boolean otherwise = words.get(k).equals("altrimenti") || words.get(k).equals("se no") || words.get(k).startsWith("senn");
            BranchText b = new BranchText();
            // dove finisce la condizione: primo verbo o "non fare niente"
            int cut = body.length();
            List<Item> its = items(tokenize(body), players);
            for (Item it : its) {
                if (it.type == IType.VERB || it.type == IType.GERUND) {
                    cut = it.start;
                    break;
                }
            }
            java.util.regex.Matcher nm = NOTHING.matcher(body);
            boolean nothing = nm.find() && nm.start() <= cut;
            if (nothing) {
                cut = nm.start();
            }
            String cond = body.substring(0, cut).trim().replaceAll("(?iu)[\\s,:;]*(allora|->|=>)?[\\s,:;]*$", "").trim();
            b.condition = cond.isEmpty() ? (otherwise ? "altrimenti" : null) : (otherwise ? "altrimenti " + cond : cond);
            List<String> condPlayers = playersIn(cond, players);
            if (orderPlayer == null && !condPlayers.isEmpty()) {
                orderPlayer = condPlayers.get(0);
            }
            String def = condPlayers.isEmpty() ? orderPlayer : condPlayers.get(0);
            if (nothing) {
                b.nothing = true;
            } else {
                String condWhat = firstWhat(cond, players);
                for (Action a : parse(body.substring(cut), players, def)) {
                    if (a.custom) {
                        continue;
                    }
                    // "togligliene 500": la cosa e' quella della condizione ("se ha piu' di 1000 coin")
                    if (a.what == null && !a.ties.containsKey("what") && condWhat != null && !isSanction(a.verb) && a.verb != null) {
                        set(a, "what", condWhat, players);
                    }
                    b.actions.add(a);
                }
            }
            o.branches.add(b);
        }
        BranchText last = o.branches.isEmpty() ? null : o.branches.get(o.branches.size() - 1);
        if (last == null || !(last.nothing && (last.condition == null || last.condition.equals("altrimenti")))) {
            BranchText n = new BranchText();
            n.nothing = true;
            o.branches.add(n);
        } else {
            last.condition = null;
        }
        return o;
    }

    /** Prima cosa (coin, ball...) nominata nel testo, o null. */
    private String firstWhat(String text, Collection<String> players) {
        for (Item it : items(tokenize(text == null ? "" : text), players)) {
            if (it.type == IType.WHAT && it.value != null) {
                return it.value;
            }
        }
        return null;
    }

    /** Player nominati nel testo (per le condizioni: "se doppiaX ha ..."). */
    public List<String> playersIn(String text, Collection<String> players) {
        List<String> out = new ArrayList<>();
        for (Item it : items(tokenize(text == null ? "" : text), players)) {
            if (it.type == IType.PLAYER && it.value != null && !out.contains(it.value)) {
                out.add(it.value);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ nomi

    public static String labelVerb(String v) {
        return switch (v == null ? "" : v) {
            case "give" -> "dai";
            case "remove" -> "togli";
            case "set" -> "setta";
            case "reset" -> "azzera";
            case "ban" -> "banna";
            case "warn" -> "warna";
            case "mute" -> "muta";
            default -> v;
        };
    }

    public static String labelWhat(String w) {
        return switch (w == null ? "" : w) {
            case "mine" -> "miniere";
            case "upgrade" -> "potenziamenti";
            case "power" -> "poteri";
            default -> w;
        };
    }

    private static String labelOf(Cat cat, String value, String what) {
        return switch (cat) {
            case VERB, GERUND -> labelVerb(value);
            case WHAT -> labelWhat(value);
            case SPEC -> "all".equals(value) ? ("ball".equals(what) ? "tutte" : "tutti")
                    : "ball".equals(what) && value.endsWith("ball") ? value.substring(0, value.length() - 4) : value;
            default -> value;
        };
    }
}
