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
 * Task scritte a frase: "dare 20 palle standard a DoppiaX togliendole a crack" -> step StaffTasks (nessuna GUI).
 * La logica di testo e' in TaskText (provata senza Bukkit); qui il dizionario task-words.yml (scritto coi default la
 * prima volta, si modifica senza ricompilare), i player conosciuti (online o gia' entrati), le regole e le specifiche
 * reali del config. Si scrive e si mostra sempre il NOME del player (l'id utente e' salvato di nascosto da StaffTasks).
 * Uno step incerto (field != null) ha le alternative: la GUI ne fa scegliere una con choose.
 */
public final class TaskParser {

    /** Alternativa: valore interno (id verbo/cosa/spec, nome player, numero, codice regola), nome da mostrare, distanza (-1 = lista completa). */
    public record Candidate(String value, String label, int distance) {
    }

    /** field = "verb" "what" "spec" "target" "amount" "rule" o null (step a posto); per amount la lista e' vuota: chiedere il numero. */
    public record Parsed(StaffTasks.Step step, boolean uncertain, String field, List<Candidate> alternatives, String sourceText) {
    }

    private final BlockMania plugin;
    private final File file;
    private TaskText text;
    /** Stato completo di ogni Parsed dato alla GUI (i pareggi degli altri campi), per choose. */
    private final Map<Parsed, TaskText.Action> state = new WeakHashMap<>();

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
        if (specs == null) {
            w.specs.putAll(def.specs);
        } else {
            for (String what : specs.getKeys(false)) {
                Map<String, List<String>> m = new LinkedHashMap<>();
                readMap(specs.getConfigurationSection(what), m, Map.of());
                w.specs.put(what, m);
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

    private static void readMap(ConfigurationSection sec, Map<String, List<String>> out, Map<String, List<String>> def) {
        if (sec == null) {
            out.putAll(def);
            return;
        }
        for (String k : sec.getKeys(false)) {
            out.put(k.toLowerCase(Locale.ROOT), new ArrayList<>(sec.getStringList(k)));
        }
    }

    private static void readList(YamlConfiguration y, String key, List<String> out, List<String> def) {
        out.addAll(y.isList(key) ? y.getStringList(key) : def);
    }

    // ------------------------------------------------------------------ parse

    /** Divide la frase in step (ordinari capiti, incerti con alternative, custom col testo originale). */
    public List<Parsed> parse(String sentence) {
        Map<String, UUID> names = knownPlayers();
        List<Parsed> out = new ArrayList<>();
        if (sentence == null || sentence.isBlank()) {
            return out;
        }
        for (TaskText.Action a : text.parse(sentence, names.keySet())) {
            out.add(toParsed(a, names));
        }
        return out;
    }

    /** Applica la scelta (c.value) al campo incerto di p e ricalcola (puo' restare incerto su un altro campo). */
    public Parsed choose(Parsed p, Candidate c) {
        if (p == null || c == null || p.field() == null) {
            return p;
        }
        Map<String, UUID> names = knownPlayers();
        TaskText.Action a;
        synchronized (state) {
            TaskText.Action saved = state.get(p);
            a = saved == null ? fromStep(p) : saved.copy();
        }
        text.set(a, p.field(), c.value(), names.keySet());
        return toParsed(a, names);
    }

    private static TaskText.Action fromStep(Parsed p) {
        StaffTasks.Step s = p.step();
        TaskText.Action a = new TaskText.Action();
        a.custom = s.kind() == StaffTasks.Kind.CUSTOM && (s.verb() == null || s.verb().isEmpty());
        a.verb = empty(s.verb());
        a.what = empty(s.what());
        a.spec = empty(s.spec());
        a.amount = "amount".equals(p.field()) || s.amount() <= 0 && !"set".equals(s.verb()) ? null : s.amount();
        a.rule = empty(s.ruleCode());
        a.player = s.target() == null ? null : s.targetName();
        a.playerQuery = s.target() == null ? empty(s.targetName()) : null;
        a.text = s.text() == null ? "" : s.text();
        a.source = p.sourceText();
        return a;
    }

    private static String empty(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    /** Azione di TaskText -> Parsed, con i controlli che servono il server (player, regole, specifiche, limiti). */
    private Parsed toParsed(TaskText.Action a, Map<String, UUID> names) {
        UUID target = a.player == null ? null : names.get(a.player);
        if (a.custom) {
            StaffTasks.Step s = StaffTasks.newStep(StaffTasks.Kind.CUSTOM, target, target == null ? "" : a.player, "", null, null,
                    0, null, a.text);
            return remember(new Parsed(s, false, null, List.of(), a.source), a);
        }
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
        StaffTasks.Step s = StaffTasks.newStep(StaffTasks.Kind.ORDINARIA, target, name, a.verb, a.what, a.spec,
                a.amount == null ? 0 : a.amount, a.rule, "");
        return remember(new Parsed(s, field != null, field, List.copyOf(alts), a.source), a);
    }

    private Parsed remember(Parsed p, TaskText.Action a) {
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
