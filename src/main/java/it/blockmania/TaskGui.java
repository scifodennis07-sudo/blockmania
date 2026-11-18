package it.blockmania;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
 * GUI delle task staff v3 (/adminmania task). La task si crea SCRIVENDO una frase (incudine, o chat per le frasi lunghe con lo
 * scambio di askText): TaskParser la trasforma in azioni (step), ognuna con il suo player. Prima di salvare c'e' l'ANTEPRIMA
 * (cartella U+E11E): verde = ordinaria capita, giallo = incerta (click = scegli tra le alternative con il popup frecce U+E11D),
 * viola = custom. Pulsante verde: click sinistro salva (conferma), click destro riscrive (incudine col testo di prima).
 * Le cartelle salvate mostrano id, data e conteggi (StaffTasks.Counts), mai il nome di un player; dentro ogni step mostra il
 * suo player. Eliminare task o step: solo StaffTasks.canDelete (creatore o admin+), con conferma.
 * <p>
 * Schermata principale = immagine task (U+E180, font minecraft:task) come lo sfondo di StaffProfileGui, con slot invisibili
 * sopra i bottoni. Tutti i popup hanno come sfondo la schermata task scurita (UserProfilePowers.drawDimmed / confirmOver).
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

    // ---------- popup scelta con frecce (userprofile-30, U+E11D: barra x62..127 y51..62, TASK_GLYPHS.md) ----------
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
    private static final int CH_BAR_CX = 95;   // centro della barra (x immagine 94.5)
    private static final int CH_BAR_MAX = 58;  // area testo 64 px

    // ---------- popup lista (ProfileLists, U+E119) e cartella (userprofile-31, U+E11E, stessa struttura) ----------
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

    // ---------- sfondo incudine del popup "continuare in chat?" (font task_anvil del pack) ----------
    /**
     * Stessa immagine dell'incudine custom (custom-gui/anvil/anvil_vanilla.png, U+3E08 nel font default) ma con ascent 0
     * invece di 28: la chest da 6 righe (alta 222) a schermo parte 28 px piu' in alto dell'incudine (166), quindi l'immagine
     * va 28 px piu' in basso. In x non serve lo spostamento tickets.anvil-shift (quello compensa il titolo dell'incudine a x 60).
     */
    private static final String ANVIL_IMAGE = "\ue190";
    private static final String ANVIL_FONT = "minecraft:task_anvil";
    private static final int ANVIL_REAL = 186;   // ultima colonna opaca 184 + 2
    /** Testo nella barra grigia in alto dell'incudine (interno x32..157, y9..19 + 28): set F500/F600 di tiny3 abbassato di 2 px. */
    private static final int ANVIL_BAR_CX = 95;
    private static final int ANVIL_BAR_MAX = 118;

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

    /** Anteprima di una task scritta (CREA), in memoria finche' non si salva o si annulla. */
    private static final class Draft {
        String text = "";
        final List<TaskParser.Parsed> parsed = new ArrayList<>();
    }

    private final BlockMania plugin;
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private TaskParser parser;
    /** Staffer che stanno finendo di scrivere la frase in chat (dopo il limite dell'incudine). */
    private final Map<UUID, Boolean> chatting = new java.util.concurrent.ConcurrentHashMap<>();

    public TaskGui(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Permessi
    // ------------------------------------------------------------------

    private StaffTasks tasks() {
        return plugin.getStaffTasks();
    }

    /** Parser delle frasi (di questa GUI: crea task-words.yml coi default al primo uso). */
    public TaskParser parser() {
        if (parser == null) {
            try {
                parser = new TaskParser(plugin);
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("TaskGui: impossibile creare TaskParser: " + ex);
            }
        }
        return parser;
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
                "&7Una task si crea scrivendo una frase, es.",
                "&f\"dai 20 palle standard a mario e toglile a luigi\"",
                "&7Il plugin la divide in azioni (anche su player diversi).",
                "&averde&7 = capita, &egiallo&7 = incerta (clicca e scegli),",
                "&dviola&7 = custom (testo da verificare).",
                "&fordinarie&7: le eseguono i mod in su; &fcustom&7: gli sr_mod in su.",
                "&7Chi crea non puo' eseguire le proprie azioni.",
                "&7Eliminare: solo chi l'ha creata o gli admin.",
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
                    "&7Anteprima aperta: &f" + d.parsed.size() + " azioni",
                    "",
                    "&eclick sinistro: riprendi l'anteprima",
                    "&eclick destro: scrivi una nuova task (l'anteprima si perde)")), () -> {
                openPreview(p, 0);
            });
            putRight(h, S_CREATE, () -> {
                drafts.remove(me);
                askSentence(p, "");
            });
        } else {
            put(h, S_CREATE, pane("&acrea task", List.of(
                    "&7Scrivi cosa fare in una frase, es.",
                    "&f\"dai 20 palle standard a mario\"",
                    "&7Frase lunga? clicca lo scambio e scrivila in chat.",
                    "",
                    "&eclicca per scrivere")), () -> {
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
        GuiMsg.send(plugin, p, "&7crea: scrivi una frase, es. &f\"dai 20 palle standard a mario e toglile a luigi\"&7 o &f\"warna luigi 1.2\"&7.");
        GuiMsg.send(plugin, p, "&7frasi lunghe: nell'incudine clicca lo scambio e scrivi in chat (annulla per uscire).");
        GuiMsg.send(plugin, p, "&7anteprima: verde capita, giallo incerta (clicca e scegli), viola custom. click sinistro sul pulsante verde = salva, destro = riscrivi.");
        GuiMsg.send(plugin, p, "&7lista: apri una cartella e clicca un'azione per eseguirla o rifiutarla (con motivo).");
        GuiMsg.send(plugin, p, "&7eliminare task o azioni: solo chi le ha create o gli admin. cestino (admin): ripristina.");
    }

    // ------------------------------------------------------------------
    //  CREA: frase -> anteprima -> salva
    // ------------------------------------------------------------------

    private void startCreate(Player p) {
        if (!guard(p)) {
            return;
        }
        if (!tasks().canCreate(p)) {
            deny(p, "non puoi creare task (servono helper o un grado piu' alto).");
            return;
        }
        // task-words.yml si rilegge a ogni nuova task: le parole aggiunte valgono subito ("riapri la GUI")
        if (parser() != null) {
            try {
                parser.reloadWords();
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("TaskGui: task-words.yml non valido: " + ex);
            }
        }
        askSentence(p, "");
    }

    /**
     * Incudine per la frase (askText; lo scambio passa comunque alla chat fino a 256 caratteri, "annulla" esce).
     * Quando il testo arriva al limite dell'incudine (50 caratteri) si apre la conferma "continuare in chat?" (askChat).
     * Annulla: torna all'anteprima se c'e', altrimenti alla schermata task.
     */
    private void askSentence(Player p, String initial) {
        if (parser() == null) {
            deny(p, "il lettore delle frasi non e' disponibile.");
            open(p);
            return;
        }
        GuiMsg.send(plugin, p, "&7scrivi la task, es. &f\"dai 20 palle standard a mario e toglile a luigi\"&7. frase lunga? continua in chat.");
        plugin.getProfileUi().askText(p, "scrivi la task (frase lunga: continua in chat)", "SCRIVI LA TASK", initial, text -> {
            handleSentence(p, text);
        }, () -> {
            cancelSentence(p);
        }, full -> {
            askChat(p, full);
        });
    }

    private void cancelSentence(Player p) {
        if (drafts.containsKey(p.getUniqueId())) {
            openPreview(p, 0);
        } else {
            open(p);
        }
    }

    /** Limite dell'incudine raggiunto: conferma (sfondo task) per continuare in chat; annulla = di nuovo l'incudine col testo. */
    private void askChat(Player p, String written) {
        plugin.getProfileUi().confirmOver(p, "L'incudine e' piena (50 caratteri). Vuoi continuare a scrivere la task in chat?", g -> {
            g.at(-15, "&f" + ANVIL_IMAGE, ANVIL_FONT, ANVIL_REAL);
        }, () -> {
            p.closeInventory();
            chatting.put(p.getUniqueId(), Boolean.TRUE);
            Component msg = plugin.color("&6[task] &e&nclicca qui per riprendere il testo&7, poi continua a scrivere e premi invio; scrivi &fannulla&7 per uscire.")
                    .clickEvent(ClickEvent.suggestCommand(written))
                    .hoverEvent(HoverEvent.showText(plugin.color("&7rimette nella chat: &f" + written)));
            p.sendMessage(msg);
        }, () -> {
            askSentence(p, written);
        }, g -> {
            anvilText(g, written);
        });
    }

    /** Testo gia' scritto, nella barra dell'incudine di sfondo (la parte finale se non ci sta, a colori pieni). */
    private void anvilText(GuiTitle g, String raw) {
        StringBuilder safe = new StringBuilder();
        for (char ch : (raw == null ? "" : raw).toCharArray()) {
            safe.append(ch < 128 && Character.isLetterOrDigit(ch) ? ch : ' ');
        }
        String text = safe.toString().replaceAll(" +", " ").trim();
        while (tinyAdvance(text) > ANVIL_BAR_MAX && text.length() > 1) {
            text = text.substring(1).trim();   // si vede la fine, cioe' dove si continua a scrivere
        }
        smallText(g, text, ANVIL_FONT, ANVIL_BAR_CX, ANVIL_BAR_MAX);
    }

    /** Frase completa (dall'incudine o dalla chat) -> TaskParser -> anteprima. */
    private void handleSentence(Player p, String text) {
        if (!guard(p)) {
            return;
        }
        if (text == null || text.isBlank()) {
            deny(p, "scrivi cosa deve fare la task.");
            askSentence(p, "");
            return;
        }
        List<TaskParser.Parsed> parsed;
        try {
            parsed = parser().parse(text.trim());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("TaskGui: errore nel leggere la frase \"" + text + "\": " + ex);
            parsed = null;
        }
        if (parsed == null || parsed.isEmpty()) {
            deny(p, "non ho capito nessuna azione: riscrivi la frase.");
            askSentence(p, text.trim().length() > 50 ? "" : text.trim());
            return;
        }
        Draft d = new Draft();
        d.text = text.trim();
        d.parsed.addAll(parsed);
        drafts.put(p.getUniqueId(), d);
        openPreview(p, 0);
    }

    private static boolean isCustom(TaskParser.Parsed x) {
        return x.step() != null && x.step().kind() == StaffTasks.Kind.CUSTOM;
    }

    /** Anteprima: step colorati (verde capita, giallo incerta, viola custom), giallo = scegli, destro = togli. */
    private void openPreview(Player p, int page) {
        if (!guard(p)) {
            return;
        }
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) {
            open(p);
            return;
        }
        int uncertain = 0;
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < d.parsed.size(); i++) {
            TaskParser.Parsed x = d.parsed.get(i);
            StaffTasks.Step s = x.step();
            boolean unsure = x.uncertain();
            if (unsure) {
                uncertain++;
            }
            String color = unsure ? "&e" : isCustom(x) ? "&d" : "&a";
            List<String> lore = new ArrayList<>();
            lore.add(unsure ? "&eincerta" + (x.field() == null ? "" : " &7(" + fieldLabel(x.field()) + ")")
                    : isCustom(x) ? "&dcustom &7(la eseguono gli sr_mod in su)" : "&aordinaria capita");
            if (s != null) {
                lore.addAll(stepLore(s, false));
            }
            if (x.sourceText() != null && !x.sourceText().isBlank()) {
                lore.add("&7scritto:");
                for (String line : wrap(clean(x.sourceText()), 34)) {
                    lore.add("&8" + line);
                }
            }
            List<TaskParser.Candidate> alts = safe(x.alternatives());
            if (unsure && !alts.isEmpty()) {
                lore.add("&7intendevi:");
                for (TaskParser.Candidate c : alts) {
                    lore.add("&8- &f" + clean(c.label()));
                }
            }
            lore.add("");
            if (unsure) {
                lore.add(alts.isEmpty() ? "&cnessuna alternativa: riscrivi la frase" : "&eclick sinistro: scegli cosa intendevi");
            }
            lore.add("&eclick destro: togli dall'anteprima");
            String name = color + (s == null ? clean(x.sourceText()) : stepTitle(s));
            int idx = i;
            Runnable pick = unsure && !alts.isEmpty() ? () -> {
                pickAlternative(p, idx, page);
            } : null;
            rows.add(new Row(item(s == null ? Material.PAPER : stepMaterial(s), name, lore), pick, () -> {
                Draft cur = drafts.get(p.getUniqueId());
                if (cur != null && idx < cur.parsed.size()) {
                    cur.parsed.remove(idx);
                }
                openPreview(p, page);
            }));
        }
        int n = d.parsed.size();
        List<String> btnLore = new ArrayList<>();
        btnLore.add("&7Azioni: &f" + n + (uncertain > 0 ? " &8(&e" + uncertain + " incerte&8)" : ""));
        btnLore.add("&7Frase:");
        for (String line : wrap(clean(d.text), 34)) {
            btnLore.add("&8" + line);
        }
        btnLore.add("");
        if (n == 0) {
            btnLore.add("&8click sinistro: salva (non ci sono azioni)");
        } else if (uncertain > 0) {
            btnLore.add("&8click sinistro: salva (prima scegli le azioni gialle)");
        } else {
            btnLore.add("&aclick sinistro: salva la task");
        }
        btnLore.add("&eclick destro: riscrivi la frase");
        int unsureCount = uncertain;
        Runnable save = () -> {
            if (n == 0) {
                deny(p, "non ci sono azioni da salvare: riscrivi la frase.");
            } else if (unsureCount > 0) {
                deny(p, "ci sono " + unsureCount + " azioni incerte (gialle): clicca e scegli cosa intendevi.");
            } else {
                confirmSave(p);
            }
        };
        panel(p, "anteprima task", "anteprima", F_IMAGE, F_FONT, F_ADV, rows, page, pg -> {
            openPreview(p, pg);
        }, () -> {
            drafts.remove(p.getUniqueId());
            GuiMsg.send(plugin, p, "&7task annullata.");
            open(p);
        }, "&cannulla", List.of("&7Butta l'anteprima e torna alla schermata task."),
                pane(uncertain == 0 && n > 0 ? "&asalva task" : "&8salva task", btnLore), save, () -> {
                    if (d.text.length() >= 50) {
                        askChat(p, d.text);   // non sta nell'incudine: si riscrive in chat partendo dal testo di prima
                    } else {
                        askSentence(p, d.text);
                    }
                });
    }

    /** Popup frecce con le alternative di uno step incerto ("intendevi ...?"); conferma = TaskParser.choose. */
    private void pickAlternative(Player p, int index, int page) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null || index >= d.parsed.size()) {
            openPreview(p, page);
            return;
        }
        TaskParser.Parsed x = d.parsed.get(index);
        List<TaskParser.Candidate> alts = safe(x.alternatives());
        List<Opt> opts = new ArrayList<>();
        for (int i = 0; i < alts.size(); i++) {
            TaskParser.Candidate c = alts.get(i);
            opts.add(new Opt(String.valueOf(i), clean(c.label()), List.of(
                    "&7intendevi: &f" + clean(c.label()) + "&7?",
                    "&7parte incerta: &f" + fieldLabel(x.field()),
                    "&7scritto: &8" + cut(clean(x.sourceText()), 40))));
        }
        choose(p, "intendevi", opts, 0, key -> {
            Draft cur = drafts.get(p.getUniqueId());
            int ci = Integer.parseInt(key);
            if (cur != null && index < cur.parsed.size() && cur.parsed.get(index) == x && ci < alts.size()) {
                try {
                    cur.parsed.set(index, parser().choose(x, alts.get(ci)));
                } catch (RuntimeException ex) {
                    plugin.getLogger().warning("TaskGui: errore nella scelta dell'alternativa: " + ex);
                    deny(p, "non sono riuscito ad applicare la scelta.");
                }
            }
            openPreview(p, page);
        }, () -> {
            openPreview(p, page);
        });
    }

    private void confirmSave(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null || d.parsed.isEmpty()) {
            open(p);
            return;
        }
        List<StaffTasks.Step> steps = new ArrayList<>();
        for (TaskParser.Parsed x : d.parsed) {
            if (x.uncertain() || x.step() == null) {
                deny(p, "ci sono azioni incerte: scegli cosa intendevi prima di salvare.");
                openPreview(p, 0);
                return;
            }
            steps.add(x.step());
        }
        plugin.getProfileUi().confirmOver(p, "Salvare la task con " + steps.size() + " azioni?", bg(), () -> {
            if (!guard(p)) {
                return;
            }
            StaffTasks.Task t = tasks().create(p, steps);
            if (t == null) {
                deny(p, "non e' stato possibile creare la task (controlla le azioni e i player).");
                openPreview(p, 0);
                return;
            }
            drafts.remove(p.getUniqueId());
            GuiMsg.send(plugin, p, "&7task &f#" + t.id() + "&7 creata (" + steps.size() + " azioni).");
            open(p);
        }, () -> {
            openPreview(p, 0);
        });
    }

    private static String fieldLabel(String field) {
        if (field == null) {
            return "";
        }
        return switch (field) {
            case "verb" -> "azione";
            case "what" -> "cosa";
            case "spec" -> "tipo";
            case "target" -> "giocatore";
            case "amount" -> "quantita'";
            case "rule" -> "codice regola";
            default -> field;
        };
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
            StaffTasks.Counts c = StaffTasks.counts(k);
            int canDo = 0;
            for (StaffTasks.Step s : safe(k.steps())) {
                if (s.status() == StaffTasks.Status.APERTA && t.canExecute(p, s)) {
                    canDo++;
                }
            }
            if ("CAN".equals(key) && canDo == 0) {
                continue;
            }
            if ("CUSTOM".equals(key) && c.custom() == 0) {
                continue;
            }
            if ("MIE".equals(key) && !me.equals(k.createdBy())) {
                continue;
            }
            List<String> lore = folderLore(k, c);
            lore.add("&7che puoi eseguire: &f" + canDo);
            lore.add("");
            lore.add("&eclicca per aprire la cartella");
            var id = k.id();
            rows.add(new Row(item(c.custom() > 0 ? Material.WRITABLE_BOOK : Material.BOOK, folderName(k, c), lore), () -> {
                viewTask(p, String.valueOf(id), 0, () -> {
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

    /** Nome della cartella: id e data, mai il nome di un player. */
    private String folderName(StaffTasks.Task k, StaffTasks.Counts c) {
        return "&ftask &e#" + k.id() + " &7- &f" + date(k.createdAt());
    }

    /** Lore della cartella: conteggi (StaffTasks.Counts), senza nomi di player. */
    private List<String> folderLore(StaffTasks.Task k, StaffTasks.Counts c) {
        List<String> lore = new ArrayList<>();
        lore.add("&7id: &f#" + k.id());
        lore.add("&7creata il: &f" + date(k.createdAt()));
        lore.add("&7stato: &f" + statusLabel(k.status()));
        lore.add("&7elementi: &f" + c.total() + " &8(&f" + c.ord() + "&8 ordinarie, &d" + c.custom() + "&8 custom)");
        lore.add("&7risolti: &a" + c.done() + "&8/&f" + c.total()
                + " &8(ordinarie &f" + c.doneOrd() + "&8/&f" + c.ord() + "&8, custom &f" + c.doneCustom() + "&8/&f" + c.custom() + "&8)");
        return lore;
    }

    /** Cartella di una task salvata: click su uno step = esegui / rifiuta / elimina elemento / elimina task. */
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
        boolean deletable = k.status() != StaffTasks.Status.ELIMINATA && t.canDelete(p, k);
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.Step s : safe(k.steps())) {
            List<String> lore = stepLore(s, true);
            boolean open = s.status() == StaffTasks.Status.APERTA && k.status() != StaffTasks.Status.ELIMINATA;
            boolean exec = open && t.canExecute(p, s);
            lore.add("");
            if (exec || deletable) {
                lore.add("&eclicca per " + (exec && deletable ? "eseguire, rifiutare o eliminare" : exec ? "eseguire o rifiutare" : "eliminare"));
            } else if (open) {
                lore.add(p.getUniqueId().equals(k.createdBy()) ? "&8l'hai creata tu: non puoi eseguirla" : "&8il tuo grado non puo' eseguirla");
            }
            String sid = s.id();
            rows.add(new Row(stepItem(s, lore), exec || deletable ? () -> {
                stepActions(p, taskId, sid, page, back);
            } : null, null));
        }
        List<String> btnLore = folderLore(k, StaffTasks.counts(k));
        btnLore.add("&7creata da: &f" + clean(k.createdByName()));
        if (deletable) {
            btnLore.add("");
            btnLore.add("&cclick destro: elimina la task (va nel cestino)");
        }
        Runnable del = deletable ? () -> {
            confirmDelete(p, taskId, back, () -> {
                viewTask(p, taskId, page, back);
            });
        } : null;
        panel(p, "task " + k.id(), "task " + k.id(), F_IMAGE, F_FONT, F_ADV, rows, page, pg -> {
            viewTask(p, taskId, pg, back);
        }, back, "&cindietro", List.of(), pane("&fcartella task &e#" + k.id(), btnLore), null, del);
    }

    private void confirmDelete(Player p, String taskId, Runnable afterDelete, Runnable onNo) {
        plugin.getProfileUi().confirmOver(p, "Eliminare la task #" + taskId + " (va nel cestino)?", bg(), () -> {
            if (!guard(p)) {
                return;
            }
            if (tasks().delete(p, taskId)) {
                GuiMsg.send(plugin, p, "&7task &f#" + taskId + "&7 spostata nel cestino.");
                afterDelete.run();
            } else {
                deny(p, "non e' stato possibile eliminare la task (solo chi l'ha creata o gli admin).");
                onNo.run();
            }
        }, onNo);
    }

    /** Popup con frecce sullo step: esegui / rifiuta (canExecute), elimina elemento / elimina task (canDelete). */
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
        String what = stepTitle(s);
        boolean open = s.status() == StaffTasks.Status.APERTA && k.status() != StaffTasks.Status.ELIMINATA;
        boolean deletable = k.status() != StaffTasks.Status.ELIMINATA && t.canDelete(p, k);
        List<Opt> opts = new ArrayList<>();
        if (open && t.canExecute(p, s)) {
            opts.add(new Opt("esegui", "esegui", List.of("&7Esegue davvero: &f" + what, "&7Chiede conferma.")));
            opts.add(new Opt("rifiuta", "rifiuta", List.of("&7Rifiuta questa azione con un motivo.")));
        }
        if (open && deletable) {
            opts.add(new Opt("elimina_step", "elimina azione", List.of("&7Toglie questa azione dalla task.", "&7Chiede conferma.")));
        }
        if (deletable) {
            opts.add(new Opt("elimina", "elimina task", List.of("&7Sposta tutta la task nel cestino.", "&7Chiede conferma.")));
        }
        if (opts.isEmpty()) {
            deny(p, "non puoi fare niente su questa azione.");
            back.run();
            return;
        }
        choose(p, "azione task " + k.id(), opts, 0, key -> {
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
                case "elimina_step" -> plugin.getProfileUi().confirmOver(p, "Eliminare l'azione: " + what + "?", bg(), () -> {
                    if (!guard(p)) {
                        return;
                    }
                    if (tasks().deleteStep(p, taskId, stepId)) {
                        GuiMsg.send(plugin, p, "&7azione eliminata: &f" + what);
                    } else {
                        deny(p, "non e' stato possibile eliminare l'azione (solo chi l'ha creata o gli admin, solo azioni aperte).");
                    }
                    back.run();
                }, back);
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
        StaffTasks t = tasks();
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.Task k : safe(t.list(StaffTasks.Status.ELIMINATA))) {
            StaffTasks.Counts c = StaffTasks.counts(k);
            List<String> lore = folderLore(k, c);
            lore.add("");
            lore.add("&eclick sinistro: ripristina");
            lore.add("&eclick destro: apri la cartella");
            String id = String.valueOf(k.id());
            rows.add(new Row(item(Material.BARRIER, "&c" + "task #" + k.id() + " &7- &f" + date(k.createdAt()), lore), () -> {
                plugin.getProfileUi().confirmOver(p, "Ripristinare la task #" + id + "?", bg(), () -> {
                    if (!guard(p)) {
                        return;
                    }
                    if (tasks().restore(p, id)) {
                        GuiMsg.send(plugin, p, "&7task &f#" + id + "&7 ripristinata.");
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
            lore.add("&7task: &f#" + e.taskId());
            if (s != null) {
                lore.add("&7azione: &f" + stepTitle(s));
                lore.add("&7tipo: &f" + kindLabel(s.kind()));
            } else if (k != null) {
                StaffTasks.Counts c = StaffTasks.counts(k);
                lore.add("&7elementi: &f" + c.total() + " &8(&f" + c.ord() + "&8 ordinarie, &d" + c.custom() + "&8 custom)");
            }
            lore.add("&7da: &f" + (e.byName() == null || e.byName().isBlank() ? "sistema" : clean(e.byName())));
            lore.add("&7quando: &f" + date(e.ts()));
            if (e.detail() != null && !e.detail().isBlank()) {
                for (String line : wrap(clean(e.detail()), 34)) {
                    lore.add("&f" + line);
                }
            }
            String what = s != null ? StaffTasks.describe(s) : "task #" + e.taskId();
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

    /** "dai 20 ball standard a mario" (describe + player dello step). */
    private static String stepTitle(StaffTasks.Step s) {
        String n = s.targetName() == null || s.targetName().isBlank() ? "" : " a " + clean(s.targetName());
        if (s.kind() == StaffTasks.Kind.CUSTOM && (s.verb() == null || s.verb().isBlank())) {
            return "custom" + n;
        }
        return StaffTasks.describe(s) + n;
    }

    /** Dettagli dello step; withStatus = anche stato, chi l'ha chiuso e motivo (non nell'anteprima). */
    private List<String> stepLore(StaffTasks.Step s, boolean withStatus) {
        List<String> lore = new ArrayList<>();
        lore.add("&7giocatore: &f" + (s.targetName() == null || s.targetName().isBlank() ? "&cnessuno" : clean(s.targetName())));
        lore.add("&7tipo: &f" + kindLabel(s.kind()));
        if (s.verb() != null && !s.verb().isBlank()) {
            lore.add("&7azione: &f" + StaffTasks.label(s.verb()));
        }
        if (s.what() != null && !s.what().isBlank()) {
            lore.add("&7cosa: &f" + StaffTasks.labelWhat(s.what()));
        }
        if (s.spec() != null && !s.spec().isBlank()) {
            lore.add("&7specifica: &f" + clean(StaffTasks.labelSpec(s.what(), s.spec())));
        }
        if (s.verb() != null && StaffTasks.valuePrompt(s.verb(), s.what(), s.spec()) != null) {
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
        if (!withStatus) {
            return lore;
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

    /** Item dello step salvato: aperto = giallo (ordinaria) / viola (custom); chiuso = verde eseguito, rosso rifiutato. */
    private ItemStack stepItem(StaffTasks.Step s, List<String> lore) {
        String base = s.status() == StaffTasks.Status.APERTA || s.status() == null
                ? (s.kind() == StaffTasks.Kind.CUSTOM ? "&d" : "&e") : stepColor(s);
        return item(stepMaterial(s), base + stepTitle(s), lore);
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
        if (s.kind() == StaffTasks.Kind.CUSTOM && (s.verb() == null || s.verb().isBlank())) {
            return Material.WRITABLE_BOOK;
        }
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
        chatting.remove(event.getPlayer().getUniqueId());
    }

    /** Frase finita in chat dopo il limite dell'incudine: va al parser come se venisse dall'incudine; "annulla" esce. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        if (chatting.remove(p.getUniqueId()) == null) {
            return;
        }
        event.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            if (msg.equalsIgnoreCase("annulla")) {
                GuiMsg.send(plugin, p, "&7scrittura della task annullata.");
                cancelSentence(p);
                return;
            }
            handleSentence(p, msg);
        });
    }
}
