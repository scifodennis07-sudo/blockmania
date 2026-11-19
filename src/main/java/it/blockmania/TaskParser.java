package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Task scritte a frase -> ordini StaffTasks (nessuna GUI): "dare 20 palle standard a DoppiaX togliendole a crack" = ordine
 * semplice con due azioni; "se doppiaX ha la miniera 5, toglietegli la miniera, se invece ..." = ordine condizionale
 * coi rami (+ "non fare nulla" in coda); il testo non capito = ordine custom.
 * La logica di testo e' in TaskText (provata senza Bukkit); qui il dizionario task-words.yml (scritto coi default la
 * prima volta, si modifica senza ricompilare), i player conosciuti (online o gia' entrati), le regole e le specifiche
 * reali del config. Si scrive e si mostra sempre il NOME del player (l'id utente e' salvato di nascosto da StaffTasks).
 * Un'azione incerta (field != null) ha le alternative: la GUI ne fa scegliere una con choose.
 * Le parole del file si AGGIUNGONO a quelle di default (cosi' le parole nuove delle versioni dopo arrivano lo stesso).
 */
public final class TaskParser {

    /** Alternativa: valore interno (id verbo/cosa/spec, nome player, numero, codice regola), nome da mostrare, distanza (-1 = lista completa). */
    public record Candidate(String value, String label, int distance) {
    }

    /** field = "verb" "what" "spec" "target" "amount" "rule" o null (azione a posto); per amount la lista e' vuota: chiedere il numero. */
    public record ParsedAction(StaffTasks.Action action, boolean uncertain, String field, List<Candidate> alternatives,
                               String sourceText) {
    }

    /** Ramo: condizione (null = ordine semplice / "non fare nulla" finale), azioni, nothing. */
    public record ParsedBranch(String condition, List<ParsedAction> actions, boolean nothing) {
    }

    public record ParsedOrder(String text, List<ParsedBranch> branches, StaffTasks.OrderType type) {
    }

    private final BlockMania plugin;
    private final File file;
    private TaskText text;
    /** Stato completo di ogni Parsed dato alla GUI (i pareggi degli altri campi), per choose. */
    private final Map<ParsedAction, TaskText.Action> state = new WeakHashMap<>();

    public TaskParser(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "task-words.yml");
        reloadWords();
    }

    // ------------------------------------------------------------------ dizionario

    /** Rilegge task-words.yml (lo crea coi default se manca). Le sezioni mancanti restano quelle di default. */
    public void reloadWords() {
        TaskText.Words def = TaskText.defaults();
        if (!file.exists()) {
            YamlConfiguration y = new YamlConfiguration();
            y.options().setHeader(List.of(
                    "Parole delle task scritte a frase (/adminmania task). Aggiungi sinonimi nelle liste, poi riapri la GUI",
                    "o ricarica il plugin. Si confrontano senza maiuscole, accenti e lettere doppie, con qualche errore ammesso.",
                    "verbs/whats/specs: id interni -> sinonimi (anche di piu' parole, es. \"imposta a\").",
                    "gerunds: forme col pronome (\"togliendole\" = togli le stesse cose dell'azione prima)."));
            for (Map.Entry<String, Object> e : TaskText.toMap(def).entrySet()) {
                y.set(e.getKey(), e.getValue());
            }
            try {
                plugin.getDataFolder().mkdirs();
                y.save(file);
            } catch (IOException ex) {
                plugin.getLogger().warning("task-words.yml: " + ex.getMessage());
            }
            text = new TaskText(def);
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        TaskText.Words w = new TaskText.Words();
        readMap(y.getConfigurationSection("verbs"), w.verbs, def.verbs);
        readMap(y.getConfigurationSection("whats"), w.whats, def.whats);
        ConfigurationSection specs = y.getConfigurationSection("specs");
        for (Map.Entry<String, Map<String, List<String>>> e : def.specs.entrySet()) {
            Map<String, List<String>> m = new LinkedHashMap<>();
            readMap(specs == null ? null : specs.getConfigurationSection(e.getKey()), m, e.getValue());
            w.specs.put(e.getKey(), m);
        }
        if (specs != null) {
            for (String what : specs.getKeys(false)) {
                if (!w.specs.containsKey(what)) {
                    Map<String, List<String>> m = new LinkedHashMap<>();
                    readMap(specs.getConfigurationSection(what), m, Map.of());
                    w.specs.put(what, m);
                }
            }
        }
        readMap(y.getConfigurationSection("gerunds"), w.gerunds, def.gerunds);
        readList(y, "same", w.same, def.same);
        readList(y, "fillers", w.fillers, def.fillers);
        readList(y, "separators", w.separators, def.separators);
        readList(y, "preps", w.preps, def.preps);
        readList(y, "articles", w.articles, def.articles);
        readList(y, "rule-words", w.ruleWords, def.ruleWords);
        readList(y, "level-words", w.levelWords, def.levelWords);
        readList(y, "custom-words", w.customWords, def.customWords);
        text = new TaskText(w);
    }

    /** Default + parole del file (unione). */
    private static void readMap(ConfigurationSection sec, Map<String, List<String>> out, Map<String, List<String>> def) {
        for (Map.Entry<String, List<String>> e : def.entrySet()) {
            out.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        if (sec == null) {
            return;
        }
        for (String k : sec.getKeys(false)) {
            List<String> l = out.computeIfAbsent(k.toLowerCase(Locale.ROOT), x -> new ArrayList<>());
            for (String w : sec.getStringList(k)) {
                if (!l.contains(w)) {
                    l.add(w);
                }
            }
        }
    }

    private static void readList(YamlConfiguration y, String key, List<String> out, List<String> def) {
        out.addAll(def);
        if (y.isList(key)) {
            for (String w : y.getStringList(key)) {
                if (!out.contains(w)) {
                    out.add(w);
                }
            }
        }
    }

    // ------------------------------------------------------------------ parse

    /** Divide la frase in ordini (semplici, condizionali coi rami, custom). */
    public List<ParsedOrder> parse(String sentence) {
        Map<String, UUID> names = knownPlayers();
        List<ParsedOrder> out = new ArrayList<>();
        if (sentence == null || sentence.isBlank()) {
            return out;
        }
        for (TaskText.OrderText o : text.parseOrders(sentence, names.keySet())) {
            List<ParsedBranch> branches = new ArrayList<>();
            for (TaskText.BranchText b : o.branches) {
                List<ParsedAction> acts = new ArrayList<>();
                for (TaskText.Action a : b.actions) {
                    acts.add(toParsed(a, names));
                }
                branches.add(new ParsedBranch(b.condition, List.copyOf(acts), b.nothing));
            }
            StaffTasks.OrderType type = switch (o.type) {
                case "conditional" -> StaffTasks.OrderType.CONDIZIONALE;
                case "custom" -> StaffTasks.OrderType.CUSTOM;
                default -> StaffTasks.OrderType.SEMPLICE;
            };
            out.add(new ParsedOrder(o.text, List.copyOf(branches), type));
        }
        return out;
    }

    /** true se un'azione dell'ordine e' ancora incerta (o un ramo non ha azioni): non si salva. */
    public static boolean uncertain(ParsedOrder o) {
        if (o == null) {
            return true;
        }
        for (ParsedBranch b : o.branches()) {
            if (!b.nothing() && b.actions().isEmpty() && o.type() != StaffTasks.OrderType.CUSTOM) {
                return true;
            }
            for (ParsedAction a : b.actions()) {
                if (a.uncertain()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Ordine pronto per StaffTasks.create (controlla prima uncertain). */
    public static StaffTasks.Order toOrder(ParsedOrder o) {
        List<StaffTasks.Branch> br = new ArrayList<>();
        for (ParsedBranch b : o.branches()) {
            List<StaffTasks.Action> acts = new ArrayList<>();
            for (ParsedAction a : b.actions()) {
                acts.add(a.action());
            }
            br.add(StaffTasks.newBranch(b.condition(), acts, b.nothing()));
        }
        return StaffTasks.newOrder(o.text(), br);
    }

    /** Applica la scelta (c.value) al campo incerto di p e ricalcola (puo' restare incerta su un altro campo). */
    public ParsedAction choose(ParsedAction p, Candidate c) {
        if (p == null || c == null || p.field() == null) {
            return p;
        }
        Map<String, UUID> names = knownPlayers();
        TaskText.Action a;
        synchronized (state) {
            TaskText.Action saved = state.get(p);
            a = saved == null ? fromAction(p) : saved.copy();
        }
        text.set(a, p.field(), c.value(), names.keySet());
        return toParsed(a, names);
    }

    private static TaskText.Action fromAction(ParsedAction p) {
        StaffTasks.Action s = p.action();
        TaskText.Action a = new TaskText.Action();
        a.verb = empty(s.verb());
        a.what = empty(s.what());
        a.spec = empty(s.spec());
        a.amount = "amount".equals(p.field()) || s.amount() <= 0 && !"set".equals(s.verb()) ? null : s.amount();
        a.rule = empty(s.ruleCode());
        a.player = s.target() == null ? null : s.targetName();
        a.playerQuery = s.target() == null ? empty(s.targetName()) : null;
        a.source = p.sourceText();
        return a;
    }

    private static String empty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Azione di TaskText -> ParsedAction, con i controlli del server (player, regole, specifiche, limiti). */
    private ParsedAction toParsed(TaskText.Action a, Map<String, UUID> names) {
        UUID target = a.player == null ? null : names.get(a.player);
        String field = a.field;
        List<Candidate> alts = new ArrayList<>();
        for (TaskText.Alt x : a.alts) {
            alts.add(new Candidate(x.value(), label(field, a, x), x.distance()));
        }
        if ("spec".equals(field) && allFull(a.alts) || field == null && a.spec != null && !specOk(a)) {
            field = "spec";
            alts = specCandidates(a);
        }
        if (field == null && a.player != null && target == null) {
            field = "target";
            alts = new ArrayList<>();
        }
        if (field == null && TaskText.isSanction(a.verb)) {
            Rules rules = plugin.getRules();
            Rules.Rule r = rules == null || a.rule == null ? null : rules.get(a.rule);
            if (r == null || !r.type().name().equalsIgnoreCase(a.verb)) {
                field = "rule";
                alts = ruleCandidates(a);
            }
        }
        if (field == null && a.amount != null) {
            long[] range = StaffTasks.valueRange(a.verb, a.what);
            if (a.amount < range[0] || a.amount > range[1]) {
                field = "amount";
                alts = new ArrayList<>();
            }
        }
        String name = target != null ? a.player : a.playerQuery != null ? a.playerQuery : "";
        StaffTasks.Action s = StaffTasks.newAction(a.verb, a.what, a.spec, a.amount == null ? 0 : a.amount, a.rule, target, name);
        return remember(new ParsedAction(s, field != null, field, List.copyOf(alts), a.source), a);
    }

    private ParsedAction remember(ParsedAction p, TaskText.Action a) {
        synchronized (state) {
            state.put(p, a.copy());
        }
        return p;
    }

    private static boolean allFull(List<TaskText.Alt> alts) {
        for (TaskText.Alt x : alts) {
            if (x.distance() >= 0) {
                return false;
            }
        }
        return true;
    }

    private boolean specOk(TaskText.Action a) {
        List<StaffTasks.Spec> specs = plugin.getStaffTasks() == null ? List.of() : plugin.getStaffTasks().specsFor(a.verb, a.what);
        if (specs.isEmpty()) {
            return true;
        }
        for (StaffTasks.Spec s : specs) {
            if (s.id().equals(a.spec)) {
                return true;
            }
        }
        return false;
    }

    private List<Candidate> specCandidates(TaskText.Action a) {
        List<Candidate> out = new ArrayList<>();
        if (plugin.getStaffTasks() != null) {
            for (StaffTasks.Spec s : plugin.getStaffTasks().specsFor(a.verb, a.what)) {
                out.add(new Candidate(s.id(), s.label(), -1));
            }
        }
        return out;
    }

    /** Regole del tipo giusto, le piu' simili al codice scritto prima. */
    private List<Candidate> ruleCandidates(TaskText.Action a) {
        List<Candidate> out = new ArrayList<>();
        Rules rules = plugin.getRules();
        if (rules == null) {
            return out;
        }
        Rules.Type type;
        try {
            type = Rules.Type.valueOf(a.verb.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return out;
        }
        String typed = a.rule == null ? "" : a.rule;
        for (Rules.Rule r : rules.byType(type)) {
            int d = typed.isEmpty() ? -1 : TaskText.levenshtein(typed, r.code());
            out.add(new Candidate(r.code(), r.code() + " " + r.title(), d));
        }
        if (!typed.isEmpty()) {
            out.sort((x, y) -> Integer.compare(x.distance(), y.distance()));
        }
        return out.size() > 12 ? new ArrayList<>(out.subList(0, 12)) : out;
    }

    private static String label(String field, TaskText.Action a, TaskText.Alt x) {
        if (field == null) {
            return x.label();
        }
        return switch (field) {
            case "verb" -> StaffTasks.label(x.value());
            case "what" -> StaffTasks.labelWhat(x.value());
            case "spec" -> StaffTasks.labelSpec(a.what, x.value());
            default -> x.label();
        };
    }

    /** Nome -> UUID dei player online e di quelli gia' entrati nel server. */
    private static Map<String, UUID> knownPlayers() {
        Map<String, UUID> out = new LinkedHashMap<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            out.put(p.getName(), p.getUniqueId());
        }
        for (OfflinePlayer o : Bukkit.getOfflinePlayers()) {
            String n = o.getName();
            if (n != null && !n.isEmpty()) {
                out.putIfAbsent(n, o.getUniqueId());
            }
        }
        return out;
    }
}
