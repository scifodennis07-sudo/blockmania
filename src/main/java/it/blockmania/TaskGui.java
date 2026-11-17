package it.blockmania;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * GUI delle task staff v2 (/adminmania task). Una task e' una CARTELLA per un player con dentro piu' azioni (step),
 * ordinarie o custom, anche sanzioni (banna/warna/muta con codice regola). Dati, permessi e metadati (verbi, cose,
 * specifiche, testo del valore) sono in StaffTasks (Giovanni): qui niente tabelle proprie.
 * <p>
 * Schermata principale = immagine task (U+E180, font minecraft:task) come lo sfondo di StaffProfileGui, con slot invisibili
 * sopra i bottoni. Tutti i popup hanno come sfondo la schermata task scurita (UserProfilePowers.drawDimmed / confirmOver):
 * - scelta con frecce: userprofile-30 (U+E11D, font sp_popup) = popup ruoli con la barra tra le frecce, opzione scritta
 *   dentro la barra (testo piccolo tiny2); slot come RoleGui;
 * - cartella: userprofile-31 (U+E11E, sp_popup) = pannello come le liste (X, barra titolo, pulsante verde "+", griglia 7x3,
 *   frecce pagina), stesso codice delle liste;
 * - liste (aperte, cestino, resoconto): copia di ProfileLists (U+E119) con il click sulle voci;
 * - player / valore / testo / codice regola / motivo: UserProfilePowers.askText / askNumber; conferme: confirmOver.
 */
public final class TaskGui implements Listener {

    // ---------- pack: schermata principale ----------
    private static final String FONT = "minecraft:task";
    private static final String IMAGE = "";
    private static final int SIZE = 54;
    private static final int IMG_ADV = 179;    // avanzamento dichiarato nel titolo normale (reale 186 - 7)
    private static final int IMG_REAL = 186;   // avanzamento reale (sfondo dei popup): ultima colonna opaca 184 + 2

    // slot della schermata principale (item dello slot (c,r) a (15+18c, 33+18r), ogni bottone copre gli slot che tocca)
    private static final int[] S_CLOSE = {0, 1};
    private static final int[] S_PREV = {2};
    private static final int[] S_BAR = {3, 4, 5};
    private static final int[] S_NEXT = {6};
    private static final int[] S_INFO = {7, 8};
    private static final int[] S_LIST = {9, 10, 11, 18, 19, 20, 27, 28, 29, 36};
    private static final int[] S_CREATE = {12, 13, 14, 21, 22, 23, 30, 31, 32};
    private static final int[] S_TRASH = {15, 16, 17, 24, 25, 26, 33, 34, 35, 44};
    private static final int[] S_LOG = {37, 38, 39, 40, 41, 42, 43, 46, 47, 48, 49, 50, 51, 52};

    // ---------- popup scelta con frecce (userprofile-30, Sara) ----------
    private static final String CH_IMAGE = "";
    private static final String CH_FONT = "minecraft:sp_popup";
    private static final int CH_ADV = 162;     // reale 169 - 7 (come RoleGui.ADV)
    private static final int[] CH_PREV = {10, 11};
    private static final int[] CH_BAR = {12, 13, 14};
    private static final int[] CH_NEXT = {15, 16};
    private static final int[] CH_YES = {28, 29, 30};
    private static final int[] CH_NO = {32, 33, 34};
    /** Opzione dentro la barra tra le frecce: set F500/F600 nel font tiny2 (= tiny3 18 px piu' in basso, riga delle frecce). */
    private static final String CH_BAR_FONT = "minecraft:tiny2";
    private static final int CH_BAR_CX = 95;   // centro della barra (x immagine)
    private static final int CH_BAR_MAX = 58;  // larghezza utile dentro la barra

    // ---------- popup lista (ProfileLists) e cartella (userprofile-31, stessa struttura) ----------
    private static final String L_IMAGE = "";
    private static final String L_FONT = "minecraft:userprofile3";
    private static final String F_IMAGE = "";
    private static final String F_FONT = "minecraft:sp_popup";
    private static final int L_IMG = -22;
    private static final int L_ADV = 162;
    private static final int F_ADV = 162;
    private static final int L_NAV_CX = 96;
    private static final char L_NAV = '';
    private static final char L_NAV_SH = '';
    private static final char BAR_BASE = '';
    private static final char BAR_SHADOW = '';
    private static final String L_BAR_FONT = "minecraft:tiny3";
    private static final int L_BAR_CX = 95;
    private static final int L_BAR_MAX = 70;
    private static final int L_PER_PAGE = 21;
    private static final int[] L_X = {1, 2};
    private static final int[] L_BTN = {6, 7};
    private static final int L_PREV = 38;
    private static final int L_NEXT = 42;

    private static final List<String> SANCTIONS = List.of("ban", "warn", "mute");

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ROME);

    // ---------- tipi interni ----------

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        boolean dead;   // gia' cliccata: il secondo click nello stesso tick non ripete l'azione
        final Map<Integer, Runnable> actions = new HashMap<>();
        final Map<Integer, Runnable> rightActions = new HashMap<>();   // click destro (se manca vale actions)

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Opzione del popup con frecce. */
    private record Opt(String key, String label, List<String> lore) {
    }

    /** Voce di una lista o di una cartella: item, click sinistro e click destro (null = niente). */
    private record Row(ItemStack item, Runnable left, Runnable right) {
    }

    private record Filter(String label, String key) {
    }

    /** Cartella in costruzione (CREA): un player e le azioni aggiunte, in memoria finche' non si salva. */
    private static final class Draft {
        UUID target;
        String targetName = "";
        final List<StaffTasks.Step> steps = new ArrayList<>();
    }

    /** Azione in costruzione ("+" della cartella). */
    private static final class StepDraft {
        StaffTasks.Kind kind = StaffTasks.Kind.ORDINARIA;
        String text;
        String verb;
        String what;
        String spec;
        long amount;
        String ruleCode;
    }

    /** Passi della selezione di un'azione, in ordine; quelli che non servono si saltano (ogni scelta cambia i successivi). */
    private enum Stage { KIND, TEXT, VERB, WHAT, SPEC, VALUE, RULE, CONFIRM }

    private final BlockMania plugin;
    private final Map<UUID, Draft> drafts = new HashMap<>();

    public TaskGui(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Permessi
    // ------------------------------------------------------------------

    private StaffTasks tasks() {
        return plugin.getStaffTasks();
    }

    /** Staff (grado, livello adminmania o OP) e non sospeso; altrimenti avvisa. */
    private boolean guard(Player p) {
        if (tasks() == null) {
            deny(p, "le task non sono disponibili.");
            return false;
        }
        if (!p.isOp()) {
            AdminCommand a = plugin.getAdminCommand();
            StaffRoles roles = plugin.getStaffRoles();
            boolean staff = (a != null && a.level(p) > 0) || (roles != null && roles.roleOf(p.getUniqueId()) != null);
            if (!staff) {
                deny(p, "le task sono solo per lo staff.");
                return false;
            }
        }
        StaffDiscipline disc = plugin.getStaffDiscipline();
        if (disc != null && disc.isSuspended(p.getUniqueId())) {
            deny(p, "sei sospeso: non puoi usare le task.");
            return false;
        }
        return true;
    }

    private void deny(Player p, String text) {
        GuiMsg.send(plugin, p, "&c" + text);
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.error");
    }

    // ------------------------------------------------------------------
    //  Schermata principale
    // ------------------------------------------------------------------

    /** Apre la schermata principale delle task. */
    public void open(Player p) {
        if (!guard(p)) {
            return;
        }
        StaffTasks t = tasks();
        Holder h = new Holder();
        GuiTitle g = new GuiTitle(plugin);
        g.at(-15, "&f" + IMAGE, FONT, IMG_ADV);
        UserProfilePowers.topTitle(plugin, g, "task");
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());

        UUID me = p.getUniqueId();
        List<StaffTasks.Task> open = safe(t.list(StaffTasks.Status.APERTA));
        int mine = 0;
        int canDo = 0;
        int openSteps = 0;
        for (StaffTasks.Task k : open) {
            if (me.equals(k.createdBy())) {
                mine++;
            }
            for (StaffTasks.Step s : safe(k.steps())) {
                if (s.status() == StaffTasks.Status.APERTA) {
                    openSteps++;
                    if (t.canExecute(p, s)) {
                        canDo++;
                    }
                }
            }
        }
        int doneToday = 0;
        int rejectedToday = 0;
        LocalDate today = LocalDate.now(ROME);
        for (StaffTasks.Task k : safe(t.list(null))) {
            for (StaffTasks.Step s : safe(k.steps())) {
                if (s.doneAt() > 0 && Instant.ofEpochMilli(s.doneAt()).atZone(ROME).toLocalDate().equals(today)) {
                    if (s.status() == StaffTasks.Status.ESEGUITA) {
                        doneToday++;
                    } else if (s.status() == StaffTasks.Status.RIFIUTATA) {
                        rejectedToday++;
                    }
                }
            }
        }
        boolean admin = t.isAdmin(p);
        boolean create = t.canCreate(p);

        put(h, S_CLOSE, pane("&cchiudi", List.of()), () -> {
            p.closeInventory();
        });
        put(h, S_PREV, pane("&8pagina precedente", List.of("&7C'e' una sola pagina.")), null);
        put(h, S_NEXT, pane("&8pagina successiva", List.of("&7C'e' una sola pagina.")), null);
        put(h, S_BAR, pane("&ftask staff", List.of(
                "&7task aperte: &f" + open.size(),
                "&7azioni aperte: &f" + openSteps,
                "&7azioni che puoi eseguire: &f" + canDo,
                "&7azioni eseguite oggi: &f" + doneToday,
                "&7azioni rifiutate oggi: &f" + rejectedToday)), null);
        put(h, S_INFO, pane("&6info", List.of(
                "&7Una task e' una cartella per un giocatore",
                "&7con dentro una o piu' azioni (dai, togli, setta,",
                "&7azzera, banna, warna, muta...).",
                "&fordinarie&7: le eseguono i mod in su.",
                "&fcustom&7 (con un messaggio da verificare): gli sr_mod in su.",
                "&7Le task le creano gli helper in su.",
                "&7Chi crea non puo' eseguire le proprie azioni.",
                "&7Chi puo' eseguire puo' anche rifiutare con un motivo.",
                "&7Gli admin possono eliminare e ripristinare (cestino).",
                "&7Tutto finisce nel resoconto.",
                "",
                "&eclicca per ricevere l'aiuto in chat")), () -> {
            help(p);
        });
        put(h, S_LIST, pane("&elista task", List.of(
                "&7task aperte: &f" + open.size(),
                "&7create da te: &f" + mine,
                "&7azioni che puoi eseguire: &f" + canDo,
                "",
                "&eclicca per aprire")), () -> {
            openList(p, 0, 0);
        });
        Draft d = drafts.get(me);
        if (!create) {
            put(h, S_CREATE, pane("&8crea task", List.of("&7Le task le creano gli helper in su.", "", "&cnon puoi creare task")), null);
        } else if (d != null) {
            put(h, S_CREATE, pane("&acrea task", List.of(
                    "&7Bozza aperta: &f" + d.targetName + " &8(" + d.steps.size() + " azioni)",
                    "",
                    "&eclick sinistro: riprendi la bozza",
                    "&eclick destro: nuova task (la bozza si perde)")), () -> {
                openDraft(p, 0);
            });
            putRight(h, S_CREATE, () -> {
                plugin.getProfileUi().confirmOver(p, "Buttare la bozza per " + d.targetName + " e crearne una nuova?", bg(), () -> {
                    drafts.remove(me);
                    startCreate(p);
                }, () -> {
                    open(p);
                });
            });
        } else {
            put(h, S_CREATE, pane("&acrea task", List.of(
                    "&7Crea una cartella per un giocatore",
                    "&7e aggiungi le azioni da fare.",
                    "",
                    "&eclicca per creare")), () -> {
                startCreate(p);
            });
        }
        if (admin) {
            put(h, S_TRASH, pane("&ccestino", List.of(
                    "&7task eliminate: &f" + safe(t.list(StaffTasks.Status.ELIMINATA)).size(),
                    "&7Clicca una task per ripristinarla.",
                    "",
                    "&eclicca per aprire")), () -> {
                openTrash(p, 0);
            });
        } else {
            put(h, S_TRASH, pane("&8cestino", List.of("&csolo admin")), null);
        }
        List<StaffTasks.LogEntry> log = safe(t.log(admin ? null : me));
        put(h, S_LOG, pane("&bresoconto task", List.of(
                "&7voci nel registro: &f" + log.size(),
                admin ? "&7Vedi tutto il registro." : "&7Vedi solo le voci delle tue task (create o eseguite da te).",
                "",
                "&eclicca per aprire")), () -> {
            openLog(p, 0, 0);
        });
        p.openInventory(h.inventory);
    }

    /** Sfondo della schermata task per i popup (avanzamento reale 186, non scurito: lo scurisce chi lo usa). */
    private void background(GuiTitle g) {
        g.at(-15, "&f" + IMAGE, FONT, IMG_REAL);
    }

    private Consumer<GuiTitle> bg() {
        return g -> {
            background(g);
        };
    }

    private void help(Player p) {
        GuiMsg.send(plugin, p, "&6task staff &8- &7come funzionano");
        GuiMsg.send(plugin, p, "&7crea: scegli il giocatore, poi nella cartella usa &a+&7 per aggiungere le azioni e click destro sul + per salvare.");
        GuiMsg.send(plugin, p, "&7azione: tipo (ordinaria/custom), azione, cosa, specifica, valore (o codice regola per ban/warn/mute).");
        GuiMsg.send(plugin, p, "&7lista: apri una cartella e clicca un'azione per eseguirla o rifiutarla (con motivo).");
        GuiMsg.send(plugin, p, "&7ordinarie = mod in su, custom = sr_mod in su; non puoi eseguire le azioni create da te.");
        GuiMsg.send(plugin, p, "&7cestino (admin): ripristina le task eliminate. resoconto: tutto quello che e' successo.");
    }

    // ------------------------------------------------------------------
    //  CREA: player -> cartella (bozza)
    // ------------------------------------------------------------------

    private void startCreate(Player p) {
        if (!guard(p)) {
            return;
        }
        if (!tasks().canCreate(p)) {
            deny(p, "non puoi creare task (servono helper o un grado piu' alto).");
            return;
        }
        askTarget(p, "");
    }

    private void askTarget(Player p, String initial) {
        plugin.getProfileUi().askText(p, "nome del giocatore", "NOME GIOCATORE", initial, name -> {
            UUID id = resolve(name);
            if (id == null) {
                deny(p, "giocatore sconosciuto: " + (name == null ? "" : name));
                askTarget(p, name == null ? "" : name);
                return;
            }
            if (id.equals(p.getUniqueId()) && !tasks().isAdmin(p)) {
                deny(p, "non puoi creare una task su te stesso.");
                askTarget(p, "");
                return;
            }
            Draft d = new Draft();
            d.target = id;
            String n = UserProfilePowers.nameOf(id);
            d.targetName = n.isEmpty() ? name.trim() : n;
            drafts.put(p.getUniqueId(), d);
            openDraft(p, 0);
        }, () -> {
            open(p);
        });
    }

    /** Cartella della bozza: step (click destro = togli), "+" = aggiungi azione, click destro sul "+" = salva. */
    private void openDraft(Player p, int page) {
        if (!guard(p)) {
            return;
        }
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) {
            open(p);
            return;
        }
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.Step s : d.steps) {
            List<String> lore = stepLore(s);
            lore.add("");
            lore.add("&eclick destro: togli dalla bozza");
            rows.add(new Row(stepItem(s, lore), null, () -> {
                d.steps.remove(s);
                GuiMsg.send(plugin, p, "&7azione tolta dalla bozza: &f" + StaffTasks.describe(s));
                openDraft(p, page);
            }));
        }
        int n = d.steps.size();
        List<String> btnLore = new ArrayList<>();
        btnLore.add("&7Giocatore: &f" + d.targetName);
        btnLore.add("&7Azioni nella bozza: &f" + n);
        btnLore.add("");
        btnLore.add("&eclick sinistro: aggiungi un'azione");
        btnLore.add(n > 0 ? "&aclick destro: salva la task" : "&8click destro: salva (serve almeno un'azione)");
        Runnable save = n > 0 ? () -> {
            confirmSave(p);
        } : () -> {
            deny(p, "aggiungi almeno un'azione prima di salvare.");
        };
        panel(p, "nuova task", d.targetName, F_IMAGE, F_FONT, F_ADV, rows, page, pg -> {
            openDraft(p, pg);
        }, () -> {
            open(p);
        }, "&cindietro", List.of("&7Torna alla schermata task.", "&7La bozza resta: CREA la riapre."),
                pane("&a+ aggiungi azione", btnLore), () -> {
                    stage(p, new StepDraft(), Stage.KIND);
                }, save);
    }

    private void confirmSave(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null || d.steps.isEmpty()) {
            openDraft(p, 0);
            return;
        }
        plugin.getProfileUi().confirmOver(p, "Salvare la task per " + d.targetName + " con " + d.steps.size() + " azioni?", bg(), () -> {
            if (!guard(p)) {
                return;
            }
            StaffTasks.Task t = tasks().create(p, d.target, new ArrayList<>(d.steps));
            if (t == null) {
                deny(p, "non e' stato possibile creare la task.");
                openDraft(p, 0);
                return;
            }
            drafts.remove(p.getUniqueId());
            GuiMsg.send(plugin, p, "&7task &f" + t.id() + "&7 creata per &f" + d.targetName + "&7 (" + d.steps.size() + " azioni).");
            open(p);
        }, () -> {
            openDraft(p, 0);
        });
    }

    // ------------------------------------------------------------------
    //  Selezione di un'azione (passi che si adattano alle scelte)
    // ------------------------------------------------------------------

    /** Il passo serve con le scelte fatte fin qui? */
    private boolean needed(StepDraft sd, Stage st) {
        return switch (st) {
            case KIND, VERB, CONFIRM -> true;
            case TEXT -> sd.kind == StaffTasks.Kind.CUSTOM;
            case WHAT -> !safe(StaffTasks.whatsFor(sd.verb)).isEmpty();
            case SPEC -> !specs(sd).isEmpty();
            case VALUE -> StaffTasks.valuePrompt(sd.verb, sd.what, sd.spec) != null;
            case RULE -> SANCTIONS.contains(sd.verb);
        };
    }

    /** Svuota il campo di un passo saltato (le scelte di prima possono averlo reso inutile). */
    private static void clear(StepDraft sd, Stage st) {
        switch (st) {
            case TEXT -> sd.text = null;
            case WHAT -> sd.what = null;
            case SPEC -> sd.spec = null;
            case VALUE -> sd.amount = 0;
            case RULE -> sd.ruleCode = null;
            default -> { }
        }
    }

    private List<StaffTasks.Spec> specs(StepDraft sd) {
        try {
            return safe(tasks().specsFor(sd.verb, sd.what));
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private void next(Player p, StepDraft sd, Stage from) {
        Stage[] all = Stage.values();
        for (int i = from.ordinal() + 1; i < all.length; i++) {
            if (needed(sd, all[i])) {
                stage(p, sd, all[i]);
                return;
            }
            clear(sd, all[i]);
        }
    }

    private void prev(Player p, StepDraft sd, Stage from) {
        Stage[] all = Stage.values();
        for (int i = from.ordinal() - 1; i >= 0; i--) {
            if (needed(sd, all[i])) {
                stage(p, sd, all[i]);
                return;
            }
        }
        openDraft(p, 0);
    }

    private void stage(Player p, StepDraft sd, Stage st) {
        if (!guard(p)) {
            return;
        }
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) {
            open(p);
            return;
        }
        Runnable back = () -> {
            prev(p, sd, st);
        };
        switch (st) {
            case KIND -> {
                List<Opt> opts = List.of(
                        new Opt("ORDINARIA", "ordinaria", List.of("&7Un'azione sul giocatore.", "&7La eseguono i mod in su.")),
                        new Opt("CUSTOM", "custom", List.of("&7Prima scrivi cosa verificare,", "&7poi scegli l'azione.", "&7La eseguono gli sr_mod in su.")));
                choose(p, "azione tipo", opts, sd.kind == StaffTasks.Kind.CUSTOM ? 1 : 0, key -> {
                    sd.kind = StaffTasks.Kind.valueOf(key);
                    next(p, sd, st);
                }, () -> {
                    openDraft(p, 0);
                });
            }
            case TEXT -> plugin.getProfileUi().askText(p, "messaggio (es. verifica se ...)", "MESSAGGIO TASK", sd.text == null ? "" : sd.text, text -> {
                if (text == null || text.isBlank()) {
                    deny(p, "scrivi il messaggio della task custom.");
                    stage(p, sd, st);
                    return;
                }
                sd.text = text.trim();
                next(p, sd, st);
            }, back);
            case VERB -> {
                List<Opt> opts = new ArrayList<>();
                List<String> verbs = safe(StaffTasks.verbs());
                for (String v : verbs) {
                    opts.add(new Opt(v, StaffTasks.label(v), List.of("&7Giocatore: &f" + d.targetName)));
                }
                choose(p, "azione", opts, sd.verb == null ? 0 : Math.max(0, verbs.indexOf(sd.verb)), key -> {
                    sd.verb = key;
                    next(p, sd, st);
                }, back);
            }
            case WHAT -> {
                List<Opt> opts = new ArrayList<>();
                List<String> whats = safe(StaffTasks.whatsFor(sd.verb));
                for (String w : whats) {
                    opts.add(new Opt(w, StaffTasks.labelWhat(w), List.of("&7" + StaffTasks.label(sd.verb) + " " + StaffTasks.labelWhat(w) + " a &f" + d.targetName)));
                }
                choose(p, StaffTasks.label(sd.verb) + " cosa", opts, sd.what == null ? 0 : Math.max(0, whats.indexOf(sd.what)), key -> {
                    if (!key.equals(sd.what)) {
                        sd.spec = null;
                    }
                    sd.what = key;
                    next(p, sd, st);
                }, back);
            }
            case SPEC -> {
                List<Opt> opts = new ArrayList<>();
                List<StaffTasks.Spec> specs = specs(sd);
                int start = 0;
                for (int i = 0; i < specs.size(); i++) {
                    StaffTasks.Spec s = specs.get(i);
                    opts.add(new Opt(s.id(), s.label(), List.of("&7" + StaffTasks.label(sd.verb) + " " + StaffTasks.labelWhat(sd.what) + ": &f" + s.label())));
                    if (s.id().equals(sd.spec)) {
                        start = i;
                    }
                }
                choose(p, StaffTasks.labelWhat(sd.what) + " quale", opts, start, key -> {
                    sd.spec = key;
                    next(p, sd, st);
                }, back);
            }
            case VALUE -> {
                String prompt = StaffTasks.valuePrompt(sd.verb, sd.what, sd.spec);
                long[] range = StaffTasks.valueRange(sd.verb, sd.what);
                long min = range == null || range.length < 2 ? 0 : range[0];
                long max = range == null || range.length < 2 ? Integer.MAX_VALUE : range[1];
                plugin.getProfileUi().askNumber(p, prompt, prompt.toUpperCase(Locale.ROOT), min, max, n -> {
                    sd.amount = n;
                    next(p, sd, st);
                }, back);
            }
            case RULE -> plugin.getProfileUi().askText(p, "codice regola (" + StaffTasks.label(sd.verb) + ")", "CODICE REGOLA",
                    sd.ruleCode == null ? "" : sd.ruleCode, code -> {
                        Rules rules = plugin.getRules();
                        Rules.Rule rule = rules == null ? null : rules.get(code == null ? "" : code.trim());
                        if (rule == null || rule.type() != ruleType(sd.verb)) {
                            deny(p, "regola inesistente o di altro tipo (serve una regola " + ruleType(sd.verb).name().toLowerCase(Locale.ROOT) + ").");
                            stage(p, sd, st);
                            return;
                        }
                        sd.ruleCode = rule.code();
                        next(p, sd, st);
                    }, back);
            case CONFIRM -> {
                StaffTasks.Step s = StaffTasks.newStep(sd.kind, sd.verb, sd.what, sd.spec, sd.amount, sd.ruleCode,
                        sd.kind == StaffTasks.Kind.CUSTOM ? sd.text : null);
                String text = "Aggiungere alla task di " + d.targetName + ": " + StaffTasks.describe(s)
                        + (sd.kind == StaffTasks.Kind.CUSTOM ? " (custom: " + cut(clean(sd.text), 40) + ")" : "") + "?";
                plugin.getProfileUi().confirmOver(p, text, bg(), () -> {
                    Draft cur = drafts.get(p.getUniqueId());
                    if (cur == null) {
                        open(p);
                        return;
                    }
                    cur.steps.add(s);
                    GuiMsg.send(plugin, p, "&7azione aggiunta: &f" + StaffTasks.describe(s));
                    openDraft(p, (cur.steps.size() - 1) / L_PER_PAGE);
                }, back);
            }
        }
    }

    private static Rules.Type ruleType(String verb) {
        return switch (verb == null ? "" : verb) {
            case "ban" -> Rules.Type.BAN;
            case "mute" -> Rules.Type.MUTE;
            default -> Rules.Type.WARN;
        };
    }

    private static UUID resolve(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String n = name.trim();
        Player online = Bukkit.getPlayerExact(n);
        if (online != null) {
            return online.getUniqueId();
        }
        OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(n);
        return off == null ? null : off.getUniqueId();
    }

    // ------------------------------------------------------------------
    //  LISTA e cartella di una task salvata
    // ------------------------------------------------------------------

    private static final List<Filter> LIST_FILTERS = List.of(
            new Filter("Tutte", ""),
            new Filter("Da fare", "CAN"),
            new Filter("Custom", "CUSTOM"),
            new Filter("Mie", "MIE"));

    private void openList(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        int fi = Math.max(0, Math.min(filterIdx, LIST_FILTERS.size() - 1));
        StaffTasks t = tasks();
        UUID me = p.getUniqueId();
        String key = LIST_FILTERS.get(fi).key();
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.Task k : safe(t.list(StaffTasks.Status.APERTA))) {
            List<StaffTasks.Step> steps = safe(k.steps());
            boolean anyCustom = false;
            int canDo = 0;
            int openN = 0;
            for (StaffTasks.Step s : steps) {
                if (s.kind() == StaffTasks.Kind.CUSTOM) {
                    anyCustom = true;
                }
                if (s.status() == StaffTasks.Status.APERTA) {
                    openN++;
                    if (t.canExecute(p, s)) {
                        canDo++;
                    }
                }
            }
            if ("CAN".equals(key) && canDo == 0) {
                continue;
            }
            if ("CUSTOM".equals(key) && !anyCustom) {
                continue;
            }
            if ("MIE".equals(key) && !me.equals(k.createdBy())) {
                continue;
            }
            List<String> lore = taskLore(k);
            lore.add("&7azioni aperte: &f" + openN + "&8/&f" + steps.size() + " &7(che puoi eseguire: &f" + canDo + "&7)");
            lore.add(Bukkit.getPlayer(k.target()) != null ? "&7giocatore online: &asi" : "&7giocatore online: &cno");
            lore.add("");
            lore.add("&eclicca per aprire la cartella");
            String id = k.id();
            rows.add(new Row(item(anyCustom ? Material.WRITABLE_BOOK : Material.BOOK,
                    "&e" + clean(k.targetName()) + " &7- &f" + steps.size() + " azioni", lore), () -> {
                viewTask(p, id, 0, () -> {
                    openList(p, fi, page);
                });
            }, null));
        }
        listPanel(p, "lista task", LIST_FILTERS, fi, page, rows, (f, pg) -> {
            openList(p, f, pg);
        }, () -> {
            open(p);
        });
    }

    /** Cartella di una task salvata: click su uno step = esegui / rifiuta (/ elimina task se admin). */
    private void viewTask(Player p, String taskId, int page, Runnable back) {
        if (!guard(p)) {
            return;
        }
        StaffTasks t = tasks();
        StaffTasks.Task k = t.get(taskId);
        if (k == null) {
            deny(p, "task non trovata.");
            back.run();
            return;
        }
        boolean admin = t.isAdmin(p);
        boolean deletable = admin && k.status() != StaffTasks.Status.ELIMINATA;
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.Step s : safe(k.steps())) {
            List<String> lore = stepLore(s);
            boolean exec = s.status() == StaffTasks.Status.APERTA && k.status() != StaffTasks.Status.ELIMINATA && t.canExecute(p, s);
            lore.add("");
            if (exec || deletable) {
                lore.add("&eclicca per " + (exec ? "eseguire o rifiutare" : "eliminare la task"));
            } else if (s.status() == StaffTasks.Status.APERTA) {
                lore.add(p.getUniqueId().equals(k.createdBy()) ? "&8l'hai creata tu: non puoi eseguirla" : "&8il tuo grado non puo' eseguirla");
            }
            String sid = s.id();
            rows.add(new Row(stepItem(s, lore), exec || deletable ? () -> {
                stepActions(p, taskId, sid, page, back);
            } : null, null));
        }
        List<String> btnLore = taskLore(k);
        btnLore.add("&7stato: &f" + statusLabel(k.status()));
        if (deletable) {
            btnLore.add("");
            btnLore.add("&cclick destro: elimina la task (va nel cestino)");
        }
        Runnable del = deletable ? () -> {
            confirmDelete(p, taskId, back, () -> {
                viewTask(p, taskId, page, back);
            });
        } : null;
        panel(p, "task " + clean(k.targetName()), k.targetName(), F_IMAGE, F_FONT, F_ADV, rows, page, pg -> {
            viewTask(p, taskId, pg, back);
        }, back, "&cindietro", List.of(), pane("&fcartella task", btnLore), null, del);
    }

    private void confirmDelete(Player p, String taskId, Runnable afterDelete, Runnable onNo) {
        plugin.getProfileUi().confirmOver(p, "Eliminare la task " + taskId + " (va nel cestino)?", bg(), () -> {
            if (!guard(p)) {
                return;
            }
            if (tasks().delete(p, taskId)) {
                GuiMsg.send(plugin, p, "&7task &f" + taskId + "&7 spostata nel cestino.");
                afterDelete.run();
            } else {
                deny(p, "non e' stato possibile eliminare la task.");
                onNo.run();
            }
        }, onNo);
    }

    /** Popup con frecce sullo step: esegui / rifiuta secondo i permessi, elimina task per gli admin. */
    private void stepActions(Player p, String taskId, String stepId, int page, Runnable listBack) {
        StaffTasks t = tasks();
        StaffTasks.Task k = t.get(taskId);
        StaffTasks.Step s = k == null ? null : findStep(k, stepId);
        Runnable back = () -> {
            viewTask(p, taskId, page, listBack);
        };
        if (s == null) {
            deny(p, "azione non trovata.");
            back.run();
            return;
        }
        String what = StaffTasks.describe(s) + " a " + clean(k.targetName());
        List<Opt> opts = new ArrayList<>();
        if (s.status() == StaffTasks.Status.APERTA && k.status() != StaffTasks.Status.ELIMINATA && t.canExecute(p, s)) {
            opts.add(new Opt("esegui", "esegui", List.of("&7Esegue davvero: &f" + what, "&7Chiede conferma.")));
            opts.add(new Opt("rifiuta", "rifiuta", List.of("&7Rifiuta questa azione con un motivo.")));
        }
        if (t.isAdmin(p) && k.status() != StaffTasks.Status.ELIMINATA) {
            opts.add(new Opt("elimina", "elimina task", List.of("&7Sposta tutta la task nel cestino.", "&7Chiede conferma.")));
        }
        if (opts.isEmpty()) {
            deny(p, "non puoi fare niente su questa azione.");
            back.run();
            return;
        }
        choose(p, "azione " + clean(k.targetName()), opts, 0, key -> {
            switch (key) {
                case "esegui" -> plugin.getProfileUi().confirmOver(p, "Eseguire: " + what + "?", bg(), () -> {
                    if (!guard(p)) {
                        return;
                    }
                    String err = tasks().executeStep(p, taskId, stepId);
                    if (err == null) {
                        GuiMsg.send(plugin, p, "&aazione eseguita: &f" + what + "&a.");
                    } else {
                        deny(p, err);
                    }
                    back.run();
                }, back);
                case "rifiuta" -> askReason(p, taskId, stepId, back);
                case "elimina" -> confirmDelete(p, taskId, listBack, back);
                default -> back.run();
            }
        }, back);
    }

    private void askReason(Player p, String taskId, String stepId, Runnable back) {
        plugin.getProfileUi().askText(p, "motivo del rifiuto", "MOTIVO RIFIUTO", "", reason -> {
            if (reason == null || reason.isBlank()) {
                deny(p, "scrivi il motivo del rifiuto.");
                askReason(p, taskId, stepId, back);
                return;
            }
            if (!guard(p)) {
                return;
            }
            String err = tasks().rejectStep(p, taskId, stepId, reason.trim());
            if (err == null) {
                GuiMsg.send(plugin, p, "&7azione rifiutata.");
            } else {
                deny(p, err);
            }
            back.run();
        }, back);
    }

    private static StaffTasks.Step findStep(StaffTasks.Task k, String stepId) {
        for (StaffTasks.Step s : safe(k.steps())) {
            if (s.id().equals(stepId)) {
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    //  CESTINO (admin)
    // ------------------------------------------------------------------

    private void openTrash(Player p, int page) {
        if (!guard(p)) {
            return;
        }
        if (!tasks().isAdmin(p)) {
            deny(p, "il cestino e' solo per gli admin.");
            return;
        }
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.Task k : safe(tasks().list(StaffTasks.Status.ELIMINATA))) {
            List<String> lore = taskLore(k);
            for (StaffTasks.Step s : safe(k.steps())) {
                lore.add(stepColor(s) + "- " + StaffTasks.describe(s));
            }
            lore.add("");
            lore.add("&eclick sinistro: ripristina");
            lore.add("&eclick destro: apri la cartella");
            String id = k.id();
            rows.add(new Row(item(Material.BARRIER, "&c" + clean(k.targetName()) + " &7- &f" + safe(k.steps()).size() + " azioni", lore), () -> {
                plugin.getProfileUi().confirmOver(p, "Ripristinare la task " + id + " di " + clean(k.targetName()) + "?", bg(), () -> {
                    if (!guard(p)) {
                        return;
                    }
                    if (tasks().restore(p, id)) {
                        GuiMsg.send(plugin, p, "&7task &f" + id + "&7 ripristinata.");
                    } else {
                        deny(p, "non e' stato possibile ripristinare la task.");
                    }
                    openTrash(p, page);
                }, () -> {
                    openTrash(p, page);
                });
            }, () -> {
                viewTask(p, id, 0, () -> {
                    openTrash(p, page);
                });
            }));
        }
        listPanel(p, "cestino task", List.of(new Filter("Eliminate", "")), 0, page, rows, (f, pg) -> {
            openTrash(p, pg);
        }, () -> {
            open(p);
        });
    }

    // ------------------------------------------------------------------
    //  RESOCONTO (registro)
    // ------------------------------------------------------------------

    private static final List<Filter> LOG_FILTERS = List.of(
            new Filter("Tutto", ""),
            new Filter("Create", "CREATA"),
            new Filter("Eseguite", "ESEGUITA"),
            new Filter("Rifiutate", "RIFIUTATA"),
            new Filter("Eliminate", "ELIMINATA"),
            new Filter("Ripristinate", "RIPRISTINATA"));

    private void openLog(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        int fi = Math.max(0, Math.min(filterIdx, LOG_FILTERS.size() - 1));
        String key = LOG_FILTERS.get(fi).key();
        StaffTasks t = tasks();
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.LogEntry e : safe(t.log(t.isAdmin(p) ? null : p.getUniqueId()))) {
            String ev = e.event() == null ? "" : e.event().toUpperCase(Locale.ROOT);
            if (!key.isEmpty() && !key.equals(ev)) {
                continue;
            }
            Material mat;
            String color;
            switch (ev) {
                case "CREATA" -> { mat = Material.YELLOW_DYE; color = "&e"; }
                case "ESEGUITA" -> { mat = Material.LIME_DYE; color = "&a"; }
                case "RIFIUTATA" -> { mat = Material.RED_DYE; color = "&c"; }
                case "ELIMINATA" -> { mat = Material.GRAY_DYE; color = "&4"; }
                case "RIPRISTINATA" -> { mat = Material.LIGHT_BLUE_DYE; color = "&b"; }
                default -> { mat = Material.PAPER; color = "&7"; }
            }
            StaffTasks.Task k = t.get(e.taskId());
            StaffTasks.Step s = k == null || e.stepId() == null ? null : findStep(k, e.stepId());
            List<String> lore = new ArrayList<>();
            lore.add("&7task: &f" + e.taskId() + (k == null ? "" : " &8(" + clean(k.targetName()) + ")"));
            if (s != null) {
                lore.add("&7azione: &f" + StaffTasks.describe(s));
                lore.add("&7tipo: &f" + kindLabel(s.kind()));
            } else if (k != null) {
                lore.add("&7azioni: &f" + safe(k.steps()).size());
            }
            lore.add("&7da: &f" + (e.byName() == null || e.byName().isBlank() ? "sistema" : clean(e.byName())));
            lore.add("&7quando: &f" + date(e.ts()));
            if (e.detail() != null && !e.detail().isBlank()) {
                for (String line : wrap(clean(e.detail()), 34)) {
                    lore.add("&f" + line);
                }
            }
            String what = s != null ? StaffTasks.describe(s) : k != null ? clean(k.targetName()) : e.taskId();
            rows.add(new Row(item(mat, color + ev.toLowerCase(Locale.ROOT) + " &7- &f" + what, lore), null, null));
        }
        listPanel(p, "resoconto task", LOG_FILTERS, fi, page, rows, (f, pg) -> {
            openLog(p, f, pg);
        }, () -> {
            open(p);
        });
    }

    // ------------------------------------------------------------------
    //  Testi e item
    // ------------------------------------------------------------------

    private List<String> taskLore(StaffTasks.Task k) {
        List<String> lore = new ArrayList<>();
        lore.add("&7id: &f" + k.id());
        lore.add("&7giocatore: &f" + clean(k.targetName()));
        lore.add("&7creata da: &f" + clean(k.createdByName()));
        lore.add("&7quando: &f" + date(k.createdAt()));
        return lore;
    }

    private List<String> stepLore(StaffTasks.Step s) {
        List<String> lore = new ArrayList<>();
        lore.add("&7tipo: &f" + kindLabel(s.kind()));
        lore.add("&7azione: &f" + StaffTasks.label(s.verb()));
        if (s.what() != null && !s.what().isBlank()) {
            lore.add("&7cosa: &f" + StaffTasks.labelWhat(s.what()));
        }
        if (s.spec() != null && !s.spec().isBlank()) {
            lore.add("&7specifica: &f" + clean(s.spec()));
        }
        if (StaffTasks.valuePrompt(s.verb(), s.what(), s.spec()) != null) {
            lore.add("&7valore: &f" + s.amount());
        }
        if (s.ruleCode() != null && !s.ruleCode().isBlank()) {
            lore.add("&7regola: &f" + clean(s.ruleCode()));
        }
        if (s.text() != null && !s.text().isBlank()) {
            lore.add("&7messaggio:");
            for (String line : wrap(clean(s.text()), 34)) {
                lore.add("&f" + line);
            }
        }
        if (s.status() != null) {
            lore.add("&7stato: " + stepColor(s) + statusLabel(s.status()));
        }
        if (s.doneAt() > 0 && s.doneByName() != null && !s.doneByName().isBlank()) {
            lore.add("&7" + (s.status() == StaffTasks.Status.RIFIUTATA ? "rifiutata" : "eseguita") + " da: &f" + clean(s.doneByName())
                    + " &8(" + date(s.doneAt()) + ")");
        }
        if (s.reason() != null && !s.reason().isBlank()) {
            lore.add("&7motivo:");
            for (String line : wrap(clean(s.reason()), 34)) {
                lore.add("&c" + line);
            }
        }
        return lore;
    }

    /** Item dello step: materiale dalla cosa/azione, nome = describe colorato per stato (verde eseguito, rosso rifiutato). */
    private ItemStack stepItem(StaffTasks.Step s, List<String> lore) {
        String base = s.status() == StaffTasks.Status.APERTA || s.status() == null
                ? (s.kind() == StaffTasks.Kind.CUSTOM ? "&d" : "&e") : stepColor(s);
        return item(stepMaterial(s), base + StaffTasks.describe(s), lore);
    }

    private static String stepColor(StaffTasks.Step s) {
        if (s.status() == StaffTasks.Status.ESEGUITA) {
            return "&a";
        }
        if (s.status() == StaffTasks.Status.RIFIUTATA) {
            return "&c";
        }
        return "&7";
    }

    private static Material stepMaterial(StaffTasks.Step s) {
        String v = s.verb() == null ? "" : s.verb();
        switch (v) {
            case "ban" -> {
                return Material.BARRIER;
            }
            case "warn" -> {
                return Material.BELL;
            }
            case "mute" -> {
                return Material.STRING;
            }
            default -> { }
        }
        String w = s.what() == null ? "" : s.what();
        return switch (w) {
            case "ball" -> Material.SNOWBALL;
            case "ricompense" -> Material.CHEST;
            case "coin" -> Material.GOLD_NUGGET;
            case "blockcoin" -> Material.GOLD_INGOT;
            case "blockshell" -> Material.PRISMARINE_SHARD;
            case "mine" -> Material.IRON_PICKAXE;
            case "upgrade" -> Material.ANVIL;
            case "power" -> Material.BLAZE_POWDER;
            case "stamina" -> Material.SUGAR;
            default -> Material.PAPER;
        };
    }

    private static String kindLabel(StaffTasks.Kind k) {
        return k == StaffTasks.Kind.CUSTOM ? "custom" : "ordinaria";
    }

    private static String statusLabel(StaffTasks.Status s) {
        if (s == null) {
            return "";
        }
        return switch (s) {
            case APERTA -> "aperta";
            case ESEGUITA -> "eseguita";
            case RIFIUTATA -> "rifiutata";
            case ELIMINATA -> "eliminata";
        };
    }

    // ------------------------------------------------------------------
    //  Popup scelta con frecce (come RoleGui, immagine con la barra)
    // ------------------------------------------------------------------

    private void choose(Player p, String title, List<Opt> opts, int index, Consumer<String> onPick, Runnable onBack) {
        int n = opts.size();
        if (n == 0) {
            onBack.run();
            return;
        }
        int idx = Math.floorMod(index, n);
        Opt cur = opts.get(idx);
        Holder h = new Holder();
        GuiTitle g = new GuiTitle(plugin);
        UserProfilePowers.drawDimmed(plugin, g, bg());
        g.at(-15, "&f" + CH_IMAGE, CH_FONT, CH_ADV);
        smallText(g, cur.label().toUpperCase(Locale.ROOT), CH_BAR_FONT, CH_BAR_CX, CH_BAR_MAX);
        UserProfilePowers.topTitle(plugin, g, title);
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());

        List<String> barLore = new ArrayList<>(cur.lore());
        barLore.add("");
        for (int i = 0; i < n; i++) {
            barLore.add(i == idx ? "&a» " + opts.get(i).label() : "&8" + opts.get(i).label());
        }
        barLore.add("");
        barLore.add("&eusa le frecce per cambiare");
        put(h, CH_PREV, pane("&7precedente", List.of("&eclicca per scorrere")), () -> {
            choose(p, title, opts, idx - 1, onPick, onBack);
        });
        put(h, CH_NEXT, pane("&7successivo", List.of("&eclicca per scorrere")), () -> {
            choose(p, title, opts, idx + 1, onPick, onBack);
        });
        put(h, CH_BAR, pane("&f" + cur.label(), barLore), null);
        String key = cur.key();
        put(h, CH_YES, pane("&aconferma", List.of("&7Scelta: &f" + cur.label(), "", "&eclicca per confermare")), () -> {
            onPick.accept(key);
        });
        put(h, CH_NO, pane("&cannulla", List.of("&7Torna indietro.", "", "&eclicca per annullare")), onBack);
        p.openInventory(h.inventory);
    }

    // ------------------------------------------------------------------
    //  Pannelli lista / cartella (copia di ProfileLists, con il click sulle voci)
    // ------------------------------------------------------------------

    private interface Reopen {
        void open(int filterIdx, int page);
    }

    /** Lista con filtro (pulsante blu = filtro successivo), immagine di ProfileLists. */
    private void listPanel(Player p, String title, List<Filter> filters, int fi, int page, List<Row> rows, Reopen reopen, Runnable onBack) {
        List<String> fl = new ArrayList<>();
        fl.add("&7scegli cosa vedere:");
        fl.add(" ");
        for (int i = 0; i < filters.size(); i++) {
            fl.add(i == fi ? "&a» " + filters.get(i).label() : "&8" + filters.get(i).label());
        }
        fl.add(" ");
        fl.add("&eclicca per cambiare");
        Runnable cycle = filters.size() > 1 ? () -> {
            reopen.open((fi + 1) % filters.size(), 0);
        } : null;
        panel(p, title, filters.get(fi).label(), L_IMAGE, L_FONT, L_ADV, rows, page, pg -> {
            reopen.open(fi, pg);
        }, onBack, "&cindietro", List.of(), pane("&bfiltra per", fl), cycle, cycle);
    }

    /**
     * Pannello 7x3 (lista o cartella): X = onBack, barra = barText, pulsante in alto a destra (btnLeft / btnRight),
     * frecce pagina in basso con il numero pagina.
     */
    private void panel(Player p, String title, String barText, String image, String font, int adv, List<Row> rows, int page,
                       IntConsumer reopenPage, Runnable onBack, String backName, List<String> backLore,
                       ItemStack button, Runnable btnLeft, Runnable btnRight) {
        int pages = Math.max(1, (rows.size() + L_PER_PAGE - 1) / L_PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder();
        GuiTitle g = new GuiTitle(plugin);
        UserProfilePowers.drawDimmed(plugin, g, bg());
        g.at(-15, "&f" + image, font, adv);
        smallText(g, barText, L_BAR_FONT, L_BAR_CX, L_BAR_MAX);
        navText(g, pg + 1, pages);
        UserProfilePowers.topTitle(plugin, g, title);
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());

        put(h, L_X, pane(backName, backLore), onBack);
        put(h, L_BTN, button, btnLeft);
        if (btnRight != null) {
            putRight(h, L_BTN, btnRight);
        }
        put(h, new int[]{L_PREV}, pane("&7pagina precedente", List.of("&7Pagina &f" + (pg + 1) + "&8/&f" + pages)), pg > 0 ? () -> {
            reopenPage.accept(pg - 1);
        } : null);
        put(h, new int[]{L_NEXT}, pane("&7pagina successiva", List.of("&7Pagina &f" + (pg + 1) + "&8/&f" + pages)), pg < pages - 1 ? () -> {
            reopenPage.accept(pg + 1);
        } : null);
        if (rows.isEmpty()) {
            h.inventory.setItem(gridSlot(10), pane("&7nessuna voce", List.of("&8La lista e' vuota.")));
        } else {
            for (int i = 0; i < L_PER_PAGE; i++) {
                int idx = pg * L_PER_PAGE + i;
                if (idx >= rows.size()) {
                    break;
                }
                Row r = rows.get(idx);
                int[] slot = {gridSlot(i)};
                put(h, slot, r.item(), r.left());
                if (r.right() != null) {
                    putRight(h, slot, r.right());
                }
            }
        }
        p.openInventory(h.inventory);
    }

    private static int gridSlot(int i) {
        return 10 + (i / 7) * 9 + (i % 7);
    }

    /** Larghezza di un testo dei font tiny2/tiny3 (come ProfileLists.tinyAdvance). */
    private static int tinyAdvance(String text) {
        int total = 0;
        for (char ch : text.toCharArray()) {
            total += ch == ' ' ? 4 : (ch == 'i' || ch == 'I' || ch == '1') ? 4 : 6;
        }
        return total;
    }

    private static String letters(String text, char base) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            sb.append(ch < 128 && Character.isLetterOrDigit(ch) ? (char) (base + ch) : ' ');
        }
        return sb.toString();
    }

    /** Testo piccolo centrato su cx (copia di ProfileLists.barText, con font e larghezza massima a scelta). */
    private void smallText(GuiTitle g, String raw, String font, int cx, int max) {
        StringBuilder safe = new StringBuilder();
        for (char ch : (raw == null ? "" : raw).toCharArray()) {
            safe.append(ch < 128 && Character.isLetterOrDigit(ch) ? ch : ' ');
        }
        String text = safe.toString().replaceAll(" +", " ").trim();
        while (tinyAdvance(text) > max && text.length() > 1) {
            text = text.substring(0, text.length() - 1).trim();
        }
        if (text.isEmpty()) {
            return;
        }
        int st = cx - Math.max(0, tinyAdvance(text) - 1) / 2;
        int off = 0;
        for (String w : text.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            int wa = tinyAdvance(w);
            g.at(st + off + 1 + L_IMG, GuiTitle.shadowCode(plugin) + letters(w, BAR_SHADOW), font, wa);
            g.at(st + off + L_IMG, "&f" + letters(w, BAR_BASE), font, wa);
            off += wa + 4;
        }
    }

    /** Numero pagina tra le frecce (copia di ProfileLists.navText). */
    private void navText(GuiTitle g, int page, int pages) {
        String plain = page + "/" + pages;
        StringBuilder n = new StringBuilder();
        StringBuilder sh = new StringBuilder();
        for (char ch : plain.toCharArray()) {
            int o = ch == '/' ? 10 : ch - '0';
            n.append((char) (L_NAV + o));
            sh.append((char) (L_NAV_SH + o));
        }
        int adv = plain.length() * 6;
        int start = L_NAV_CX - (adv - 1) / 2;
        g.at(start + 1 + L_IMG, GuiTitle.shadowCode(plugin) + sh, "", adv);
        g.at(start + L_IMG, "&f" + n, "", adv);
    }

    // ------------------------------------------------------------------
    //  Utilita'
    // ------------------------------------------------------------------

    private void put(Holder h, int[] slots, ItemStack stack, Runnable action) {
        for (int s : slots) {
            h.inventory.setItem(s, stack);
            if (action != null) {
                h.actions.put(s, action);
            }
        }
    }

    private void putRight(Holder h, int[] slots, Runnable action) {
        for (int s : slots) {
            h.rightActions.put(s, action);
        }
    }

    private ItemStack pane(String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        meta.displayName(plugin.text(name));
        if (lore != null && !lore.isEmpty()) {
            List<Component> out = new ArrayList<>();
            for (String raw : lore) {
                out.add(raw == null || raw.isBlank() ? plugin.color(" ") : plugin.text(raw));
            }
            meta.lore(out);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text(name));
        List<Component> l = new ArrayList<>();
        for (String line : lore) {
            l.add(line == null || line.isBlank() ? plugin.color(" ") : plugin.text(line));
        }
        meta.lore(l);
        stack.setItemMeta(meta);
        return stack;
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('&', ' ').replace('§', ' ').trim();
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 3) + "...";
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

    private static String date(long ms) {
        return DATE.format(Instant.ofEpochMilli(ms));
    }

    // ------------------------------------------------------------------
    //  Eventi
    // ------------------------------------------------------------------

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        Runnable action = event.isRightClick() && h.rightActions.containsKey(slot) ? h.rightActions.get(slot) : h.actions.get(slot);
        if (action == null || h.dead) {
            return;
        }
        h.dead = true;
        plugin.guiClick(p);
        // tutto al tick dopo: aprire o chiudere inventari dentro l'evento click lascia la gui in stato incerto
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                action.run();
            }
            // se l'azione non ha aperto un'altra schermata la GUI resta valida
            if (p.getOpenInventory().getTopInventory().getHolder() == h) {
                h.dead = false;
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drafts.remove(event.getPlayer().getUniqueId());
    }
}
