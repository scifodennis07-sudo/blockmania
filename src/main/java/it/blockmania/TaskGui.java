package it.blockmania;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Instant;
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
 * GUI delle task staff v4 (/adminmania task). Una task e' una cartella di ORDINI scritti a frase (TaskParser), con la
 * MOTIVAZIONE. Nella cartella ogni ordine e' un blocco di slot uniti su una riga (barra del font task_row dietro):
 * [testo][prendi] per l'ordine semplice / custom, [testo][ramo 1]..[ramo N][non fare nulla] per il condizionale; gli
 * ordini sono separati da uno slot vuoto, uno piu' lungo di 7 slot continua sulla riga sotto. Il "+" verde aggiunge ordini
 * (solo nell'anteprima: una task salvata non si allunga, si possono solo togliere ordini non completati). Cliccando "prendi" o un ramo lo staffer PRENDE IN CARICO l'ordine
 * (StaffTasks.take: bossbar con timer 5 minuti, le azioni le fa a mano con userprofile / adminmania).
 * Tutti gli item usano le icone del pack (blockmania:task_*, ItemMeta.setItemModel); nomi e lore passano da LoreWrap.
 * Popup: si chiudono solo con conferma/annulla (gli altri click non fanno niente). ELIMINARE si fa SOLO col tasto Q (sempre
 * con conferma): Q su una task nella lista o sul pulsante della cartella = elimina la task (solo creatore o admin+), Q su un
 * ordine = toglilo, Q sul "+" dell'anteprima o su "crea task" = butta l'anteprima. Niente altri tasti/pulsanti per eliminare.
 * Sfondo dei popup = schermata task scurita, col titolo "TASK" (scurito) nella barra in alto: i popup non scrivono il
 * proprio titolo. Il popup "continuare in chat?" quando
 * l'incudine e' piena e' quello generico di UserProfilePowers.askText. Niente istruzioni in chat: i suggerimenti sono
 * nella GUI (nome/lore dell'incudine e degli item).
 */
public final class TaskGui implements Listener {

    // ---------- pack: schermata principale ----------
    private static final String FONT = "minecraft:task";
    private static final String IMAGE = "\ue180";
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
    /**
     * Numero pagina nella barra frecce della schermata principale (sempre, anche "1/1"): stessi glifi di RulesGui (font tiny5,
     * U+F700 cifre / U+F70A "/", ombra U+F710, ascent -5 = y33 come pagnum-*.png), centro 94 come pagnum (x86..103).
     */
    private static final String M_NAV_FONT = "minecraft:tiny5";
    private static final char M_NAV = '\uf700';
    private static final char M_NAV_SH = '\uf710';
    private static final int M_NAV_CX = 94;

    // ---------- popup scelta con frecce (userprofile-30, U+E11D: barra x62..127 y51..62) ----------
    /** U+E11F = popup frecce con barra SENZA la scritta fissa (POPUP_GLYPHS.md): la descrizione la scrive choose. */
    private static final String CH_IMAGE = "\ue11f";
    /** Descrizione del popup frecce: 1 riga a y73 (area x28..161, centro 94.5), font tiny_y73 del pack. */
    private static final String CH_DESC_FONT = "minecraft:tiny_y73";
    private static final int CH_DESC_MAX = 122;
    private static final String CH_FONT = "minecraft:sp_popup";
    private static final int CH_ADV = 162;     // reale 169 - 7 (come RoleGui.ADV)
    private static final int[] CH_PREV = {10, 11};
    private static final int[] CH_BAR = {12, 13, 14};
    private static final int[] CH_NEXT = {15, 16};
    private static final int[] CH_YES = {28, 29, 30};
    private static final int[] CH_NO = {32, 33, 34};
    private static final String CH_BAR_FONT = "minecraft:tiny2";
    private static final int CH_BAR_CX = 95;
    private static final int CH_BAR_MAX = 62;   // BAR_WIDTH.md: interno 62..127 (66 px), max = interno - 4

    // ---------- popup lista (ProfileLists, U+E119) e cartella (userprofile-31, U+E11E, stessa struttura) ----------
    private static final String L_IMAGE = "\ue119";
    private static final String L_FONT = "minecraft:userprofile3";
    private static final String F_IMAGE = "\ue11e";
    private static final String F_FONT = "minecraft:sp_popup";
    private static final int L_IMG = -22;
    private static final int L_ADV = 162;
    private static final int F_ADV = 162;
    private static final int L_NAV_CX = 96;
    private static final char L_NAV = '\ue4a0';
    private static final char L_NAV_SH = '\ue4b0';
    /** Larghezza massima del numero pagina tra le frecce (cifre da 6 px): oltre, solo il numero della pagina. */
    private static final int L_NAV_MAX = 40;
    private static final char BAR_BASE = '\uf500';
    private static final char BAR_SHADOW = '\uf600';
    private static final String L_BAR_FONT = "minecraft:tiny3";
    /** Barra descrittiva di U+E119 / U+E11E (BAR_WIDTH.md: interno 61..128 = 68 px, centro 95, max = interno - 4). */
    private static final int L_BAR_CX = 95;
    private static final int L_BAR_MAX = 64;
    private static final int L_PER_PAGE = 21;
    private static final int GRID_COLS = 7;
    private static final int GRID_ROWS = 3;
    private static final int[] L_X = {1, 2};
    private static final int[] L_BTN = {6, 7};
    private static final int L_PREV = 38;
    private static final int L_NEXT = 42;
    /** Slot liberi della cartella ai lati delle frecce pagina: nell'anteprima motivazione (37) e riscrivi (43). */
    private static final int F_LEFT = 37;
    private static final int F_RIGHT = 43;

    // ---------- barre delle righe unite nella cartella (font task_row, Sara) ----------
    private static final String ROW_FONT = "minecraft:task_row";
    private static final char ROW_BASE = '\ue200';
    private static final int ROW_L = 0;
    private static final int ROW_M = 1;
    private static final int ROW_R = 2;

    // ---------- pulsanti disegnati dell'anteprima ai lati delle frecce pagina (font task_btn, Sara: docs/TASK_BTN.md) ----------
    // Glifi a pagina intera (256x256, ascent 28) come le barre task_row (TASK_BTN.md): stessa x del popup U+E11E, cioe' -15
    // col cursore reale = L_IMG (-22) nel titolo normale (come drawSpan), advance REALE 48 / 156. Centrati nelle celle 37/43
    // e sull'interno della barra pagina (y 107..122).
    private static final String BTN_FONT = "minecraft:task_btn";
    private static final String BTN_MOT = "\ue000";    // motivazione (fumetto), slot F_LEFT
    private static final String BTN_SAVE = "\ue001";   // salva (dischetto), slot F_RIGHT
    private static final int BTN_MOT_X = L_IMG;
    private static final int BTN_MOT_ADV = 48;
    private static final int BTN_SAVE_X = L_IMG;
    private static final int BTN_SAVE_ADV = 156;

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ROME);
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ROME);   // sempre con l'anno

    // ---------- tipi interni ----------

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        boolean dead;   // gia' cliccata: il secondo click nello stesso tick non ripete l'azione
        final Map<Integer, Runnable> actions = new HashMap<>();
        final Map<Integer, Runnable> rightActions = new HashMap<>();   // click destro (se manca vale actions)
        final Map<Integer, Runnable> dropActions = new HashMap<>();    // tasto Q: solo dove previsto
        /** Sfondo (colori pieni, advance reale) dei popup aperti da questo pannello: schermata task + pannello. null = solo la schermata task. */
        Consumer<GuiTitle> under;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Opt(String key, String label, List<String> lore) {
    }

    /** Voce di una lista o cella di una cartella: item, click sinistro, destro e Q (null = niente). */
    private record Row(ItemStack item, Runnable left, Runnable right, Runnable drop) {
        Row(ItemStack item, Runnable left, Runnable right) {
            this(item, left, right, null);
        }
    }

    private record Filter(String label, String key) {
    }

    /**
     * Filtro avanzato (Q sul pulsante filtro): una task (taskId) oppure un player (uuid e/o nome, quello piu' simile a
     * quanto scritto). Vive finche' la GUI task resta aperta (i popup non contano), poi sparisce.
     */
    private record Adv(String label, String taskId, UUID player, String name) {
    }

    /** Prefisso della chiave dei filtri avanzati nella lista dei filtri: "ADV" + indice in adv(p, kind). */
    private static final String ADV = "ADV";

    /** Pezzo di barra di una riga della cartella: riga 0..2, colonna di partenza 0..6, quante celle, continua da/su altre righe. */
    private record Span(int row, int col, int len, boolean fromPrev, boolean toNext) {
    }

    /** Una pagina di cartella: celle per slot e barre da disegnare dietro. */
    private record FolderPage(Map<Integer, Row> cells, List<Span> spans) {
    }

    /** Task scritta (CREA), in memoria finche' non si salva o si annulla. */
    private static final class Draft {
        String reason = "";
        /** Ordine da riscrivere con la prossima frase (-1 = la frase aggiunge ordini in coda). */
        int replace = -1;
        final List<TaskParser.ParsedOrder> orders = new ArrayList<>();
    }

    private final BlockMania plugin;
    private final Map<UUID, Draft> drafts = new HashMap<>();
    /** Filtri avanzati per player e per lista ("list", "trash", "log"), in ordine di creazione. */
    private final Map<UUID, Map<String, List<Adv>>> advFilters = new HashMap<>();

    public TaskGui(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Permessi
    // ------------------------------------------------------------------

    private StaffTasks tasks() {
        return plugin.getStaffTasks();
    }

    private TaskParser parser() {
        StaffTasks t = tasks();
        return t == null ? null : t.parser();
    }

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

    public void open(Player p) {
        if (!guard(p)) {
            return;
        }
        StaffTasks t = tasks();
        Holder h = new Holder();
        GuiTitle g = new GuiTitle(plugin);
        g.at(-15, "&f" + IMAGE, FONT, IMG_ADV);
        UserProfilePowers.topTitle(plugin, g, "task");
        navText(g, 1, 1, M_NAV_FONT, M_NAV, M_NAV_SH, M_NAV_CX, 0);   // una sola pagina: sempre "1/1"
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());

        UUID me = p.getUniqueId();
        List<StaffTasks.Task> open = safe(t.list(StaffTasks.Status.APERTA));
        int mine = 0;
        int openOrders = 0;
        int free = 0;
        for (StaffTasks.Task k : open) {
            if (me.equals(k.createdBy())) {
                mine++;
            }
            for (StaffTasks.Order o : safe(k.orders())) {
                if (o.status() == StaffTasks.Status.APERTA) {
                    openOrders++;
                    if (t.chargeOn(k.id(), o.id()) == null && t.canTake(p, k, o) == null) {
                        free++;
                    }
                }
            }
        }
        boolean admin = t.isAdmin(p);
        boolean create = t.canCreate(p);
        StaffTasks.Charge ch = t.chargeOf(me);

        put(h, S_CLOSE, pane("&cchiudi", List.of()), () -> {
            p.closeInventory();
        });
        put(h, S_PREV, pane("&8pagina precedente", List.of()), null);
        put(h, S_NEXT, pane("&8pagina successiva", List.of()), null);
        List<String> barLore = new ArrayList<>();
        barLore.add("&7task aperte: &f" + open.size());
        barLore.add("&7ordini aperti: &f" + openOrders);
        barLore.add("&7puoi prendere: &f" + free);
        barLore.add("");
        if (ch != null) {
            StaffTasks.Action next = t.nextAction(ch);
            barLore.add("&ein carico a te: &ftask #" + ch.taskId());
            barLore.add("&7prossima azione: &f" + (next == null ? "segna come fatto" : StaffTasks.describe(next)));
            barLore.add("&7tempo rimasto: &f" + mmss(StaffTasks.remainingMs(ch)));
            barLore.add("");
            barLore.add("&eclicca per aprire la task");
        } else {
            barLore.add("&7incarico: &8nessuno");
        }
        put(h, S_BAR, pane(ch != null ? "&eil tuo incarico" : "&ftask staff", barLore), ch == null ? null : () -> {
            viewTask(p, ch.taskId(), 0, () -> {
                open(p);
            });
        });
        put(h, S_INFO, pane("&6info", List.of(
                "&7crea: &funa frase + motivo",
                "&7es.: &fdai 20 palle a mario",
                "&7prendi: &fun ordine, 5 minuti",
                "&7incarichi: &funo alla volta")), null);
        put(h, S_LIST, pane("&elista task", List.of(
                "&7task aperte: &f" + open.size(),
                "&7create da te: &f" + mine,
                "&7puoi prendere: &f" + free,
                "",
                "&eclicca per aprire")), () -> {
            openList(p, 0, 0);
        });
        Draft d = drafts.get(me);
        if (!create) {
            put(h, S_CREATE, pane("&8crea task", List.of()), null);
        } else if (d != null) {
            put(h, S_CREATE, pane("&acrea task", List.of(
                    "&7anteprima: &f" + d.orders.size() + " ordini",
                    "",
                    "&eclicca per riprendere &8| &cQ elimina")), () -> {
                openPreview(p, 0);
            });
            for (int s : S_CREATE) {
                h.dropActions.put(s, () -> {
                    confirmDropDraft(p, () -> {
                        open(p);
                    });
                });
            }
        } else {
            put(h, S_CREATE, pane("&acrea task", List.of(
                    "&7es.: &fdai 20 palle a mario",
                    "",
                    "&eclicca per scrivere")), () -> {
                startCreate(p);
            });
        }
        if (admin) {
            put(h, S_TRASH, pane("&ccestino", List.of(
                    "&7task eliminate: &f" + safe(t.list(StaffTasks.Status.ELIMINATA)).size(),
                    "",
                    "&eclicca per aprire")), () -> {
                openTrash(p, 0, 0);
            });
        } else {
            put(h, S_TRASH, pane("&8cestino", List.of()), null);
        }
        List<StaffTasks.LogEntry> log = safe(t.log(admin ? null : me));
        put(h, S_LOG, pane("&bresoconto task", List.of(
                "&7voci nel registro: &f" + log.size(),
                admin ? "&7mostra: &ftutto" : "&7mostra: &fle tue task",
                "",
                "&eclicca per aprire")), () -> {
            openLog(p, 0, 0);
        });
        p.openInventory(h.inventory);
    }

    /**
     * Schermata task a colori pieni come sfondo dei popup (lo scurisce drawDimmed / confirmOver), CON il titolo "TASK" nella
     * barra in alto (topTitleDim, cursore reale) e il numero pagina "1/1": i popup non scrivono il proprio titolo, resta quello della GUI di sotto
     * scurito, anche per i popup sopra altri popup (riusano questo sfondo). Inventario a colori pieni: variante "task".
     */
    private void background(GuiTitle g) {
        g.at(-15, "&f" + IMAGE, FONT, IMG_REAL);
        UserProfilePowers.topTitleDim(plugin, g, "task");
        navText(g, 1, 1, M_NAV_FONT, M_NAV, M_NAV_SH, M_NAV_CX, 7);   // "1/1" anche nello sfondo (cursore reale: +7)
        UserProfilePowers.invVariant(g, UserProfilePowers.INV_HIGH);   // U+E000 (INV_FULL.md: task.png)
    }

    private Consumer<GuiTitle> bg() {
        return g -> {
            background(g);
        };
    }

    /**
     * Sfondo di un popup (conferma, scelta) aperto dalla GUI task che il player ha davanti adesso: la schermata task, oppure
     * schermata task + pannello (cartella, anteprima, lista, cestino) se si parte da un pannello.
     */
    private Consumer<GuiTitle> bgHere(Player p) {
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h && h.under != null) {
            return h.under;
        }
        return bg();
    }

    // ------------------------------------------------------------------
    //  CREA: frase -> motivazione -> anteprima ("+" = altre frasi) -> salva
    //  (i suggerimenti stanno solo nella GUI: nome/lore dell'incudine; il popup "continua in chat" lo fa askText)
    // ------------------------------------------------------------------

    private void startCreate(Player p) {
        if (!guard(p)) {
            return;
        }
        if (!tasks().canCreate(p)) {
            deny(p, "non puoi creare task (servono helper o un grado piu' alto).");
            return;
        }
        reloadWords();
        Draft d = new Draft();
        drafts.put(p.getUniqueId(), d);
        askSentence(p, "");
    }

    private void reloadWords() {
        TaskParser tp = parser();
        if (tp != null) {
            try {
                tp.reloadWords();   // le parole aggiunte a task-words.yml valgono subito
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("TaskGui: task-words.yml non valido: " + ex);
            }
        }
    }

    /** Incudine per la frase (frase lunga: il popup "continua in chat" e' quello generico di askText). */
    private void askSentence(Player p, String initial) {
        if (parser() == null) {
            deny(p, "il lettore delle frasi non e' disponibile.");
            open(p);
            return;
        }
        String init = initial == null ? "" : initial;
        Consumer<String> onOk = text -> {
            handleSentence(p, text);
        };
        Runnable onCancel = () -> {
            cancelSentence(p);
        };
        if (init.length() >= 50) {
            plugin.getProfileUi().askTextInChat(p, "scrivi l'ordine", init, onOk, onCancel);   // non sta nell'incudine
        } else {
            plugin.getProfileUi().askText(p, "scrivi l'ordine, es. dai 20 palle standard a mario", "SCRIVI L'ORDINE", init, onOk, onCancel);
        }
    }

    /** Annulla la frase: torna all'anteprima se ha ordini, altrimenti (task nuova vuota) alla schermata task. */
    private void cancelSentence(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d != null) {
            d.replace = -1;
        }
        if (d != null && !d.orders.isEmpty()) {
            openPreview(p, 0);
        } else {
            drafts.remove(p.getUniqueId());
            open(p);
        }
    }

    /** Frase completa (dall'incudine o dalla chat) -> TaskParser -> ordini aggiunti (o sostituiti) nella bozza. */
    private void handleSentence(Player p, String text) {
        if (!guard(p)) {
            return;
        }
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) {
            d = new Draft();
            drafts.put(p.getUniqueId(), d);
        }
        if (text == null || text.isBlank()) {
            deny(p, "scrivi cosa deve fare l'ordine.");
            askSentence(p, "");
            return;
        }
        List<TaskParser.ParsedOrder> parsed;
        try {
            parsed = parser().parse(text.trim());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("TaskGui: errore nel leggere la frase \"" + text + "\": " + ex);
            parsed = null;
        }
        if (parsed == null || parsed.isEmpty()) {
            deny(p, "non ho capito nessun ordine: riscrivi la frase.");
            askSentence(p, text.trim());
            return;
        }
        if (d.replace >= 0 && d.replace < d.orders.size()) {
            d.orders.remove(d.replace);
            d.orders.addAll(d.replace, parsed);
        } else {
            d.orders.addAll(parsed);
        }
        d.replace = -1;
        if (d.reason.isBlank()) {
            askMotivation(p, "");
        } else {
            openPreview(p, Integer.MAX_VALUE);   // ultima pagina: si vede l'ordine appena scritto
        }
    }

    /** Motivazione della task (obbligatoria; lunga -> popup "continua in chat" di askText). Annulla = anteprima. */
    private void askMotivation(Player p, String initial) {
        String init = initial == null ? "" : initial;
        Consumer<String> onOk = text -> {
            handleMotivation(p, text);
        };
        Runnable onCancel = () -> {
            openPreview(p, 0);
        };
        if (init.length() >= 50) {
            plugin.getProfileUi().askTextInChat(p, "motivazione", init, onOk, onCancel);
        } else {
            plugin.getProfileUi().askText(p, "motivazione della task (perche' va fatta)", "MOTIVAZIONE", init, onOk, onCancel);
        }
    }

    private void handleMotivation(Player p, String text) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) {
            open(p);
            return;
        }
        if (text == null || text.isBlank()) {
            deny(p, "la motivazione e' obbligatoria.");
            askMotivation(p, "");
            return;
        }
        d.reason = text.trim();
        openPreview(p, 0);
    }

    // ------------------------------------------------------------------
    //  Anteprima (cartella con gli ordini letti dalla frase)
    // ------------------------------------------------------------------

    private void openPreview(Player p, int page) {
        if (!guard(p)) {
            return;
        }
        Draft d = drafts.get(p.getUniqueId());
        if (d == null) {
            open(p);
            return;
        }
        int unsure = 0;
        List<List<Row>> blocks = new ArrayList<>();
        for (int oi = 0; oi < d.orders.size(); oi++) {
            TaskParser.ParsedOrder o = d.orders.get(oi);
            boolean u = TaskParser.uncertain(o);
            if (u) {
                unsure++;
            }
            int orderIdx = oi;
            List<Row> cells = new ArrayList<>();
            // cella testo: sinistro = riscrivi, Q = togli l'ordine dall'anteprima (con conferma)
            List<String> tl = new ArrayList<>();
            tl.add("&7tipo: &f" + typeLabel(o.type()));
            tl.add("&7frase: &f" + clean(o.text()));
            String why = u ? null : invalidReason(p, o);
            if (u) {
                tl.add("&7stato: &eda chiarire");
            } else if (why != null) {
                tl.add("&c" + why);
            }
            tl.add("");
            tl.add("&eclicca per riscrivere &8| &cQ toglie");
            String color = u ? "&e" : o.type() == StaffTasks.OrderType.CUSTOM ? "&d" : "&a";
            cells.add(new Row(icon(o.type() == StaffTasks.OrderType.CUSTOM ? "task_custom" : "task_testo",   // foglio (viola se custom): il "?" solo sui rami/azioni incerti
                    color + "ordine " + (oi + 1), tl), () -> {
                Draft cur = drafts.get(p.getUniqueId());
                if (cur != null && orderIdx < cur.orders.size()) {
                    cur.replace = orderIdx;
                    askSentence(p, cur.orders.get(orderIdx).text());
                }
            }, null, () -> {
                TaskParser.ParsedOrder target = o;
                plugin.getProfileUi().confirmOver(p, "Togliere l'ordine " + (orderIdx + 1) + " dall'anteprima?", bgHere(p), () -> {
                    Draft cur = drafts.get(p.getUniqueId());
                    if (cur != null && orderIdx < cur.orders.size() && cur.orders.get(orderIdx) == target) {
                        cur.orders.remove(orderIdx);
                    }
                    openPreview(p, page);
                }, () -> {
                    openPreview(p, page);
                });
            }));
            List<TaskParser.ParsedBranch> branches = safe(o.branches());
            if (o.type() == StaffTasks.OrderType.CUSTOM) {
                cells.add(new Row(icon("task_custom", "&dda verificare a mano", List.of(
                        "&7azioni: &fa mano")), null, null));
            }
            for (int bi = 0; o.type() != StaffTasks.OrderType.CUSTOM && bi < branches.size(); bi++) {
                cells.add(previewBranchCell(p, o, orderIdx, bi, branches.get(bi), page));
            }
            blocks.add(cells);
        }
        List<FolderPage> pages = layout(blocks);
        int pg = Math.max(0, Math.min(page, pages.size() - 1));
        FolderPage fp = pages.get(pg);

        boolean noReason = d.reason == null || d.reason.isBlank();
        List<String> btnLore = new ArrayList<>();
        btnLore.add("&7ordini: &f" + d.orders.size() + (unsure > 0 ? " &8(&e" + unsure + " da chiarire&8)" : ""));
        btnLore.add("");
        btnLore.add("&eclicca per scrivere &8| &cQ elimina");
        Runnable add = () -> {
            d.replace = -1;
            askSentence(p, "");
        };
        int unsureCount = unsure;
        Runnable save = () -> {
            if (d.orders.isEmpty()) {
                deny(p, "non ci sono ordini da salvare.");
            } else if (unsureCount > 0) {
                deny(p, "ci sono " + unsureCount + " ordini da chiarire (gialli): clicca e scegli cosa intendevi.");
            } else if (d.reason == null || d.reason.isBlank()) {
                deny(p, "manca la motivazione.");
                askMotivation(p, "");
            } else {
                confirmSave(p);
            }
        };
        Map<Integer, Row> extra = new HashMap<>();
        {
            extra.put(F_LEFT, new Row(pane("&fmotivazione", List.of(
                    "&7motivo: " + (noReason ? "&cmanca" : "&f" + clean(d.reason)), "", "&eclicca per cambiarla")), () -> {
                askMotivation(p, d.reason);
            }, null));
        }
        boolean ready = !d.orders.isEmpty() && unsure == 0 && !noReason;
        extra.put(F_RIGHT, new Row(pane(ready ? "&asalva" : "&8salva", ready ? List.of("&eclicca per salvare")
                : List.of("&7manca: &cparti gialle o motivo")), save, null));
        // X = indietro: l'anteprima resta (si riprende da "crea task"); si butta solo con Q (sul "+" o su "crea task")
        Holder h = folder(p, "anteprima", fp, pg, pages.size(), pgn -> {
            openPreview(p, pgn);
        }, () -> {
            open(p);
        }, "&cindietro", List.of("&7anteprima: &fresta salvata"),
                pane("&a+ altro ordine", btnLore), add, extra, true, ready);
        if (h != null) {
            for (int s : L_BTN) {
                h.dropActions.put(s, () -> {
                    confirmDropDraft(p, () -> {
                        openPreview(p, pg);
                    });
                });
            }
        }
    }

    /** Q su "crea task" o sul "+" dell'anteprima: butta l'anteprima (con conferma). onNo = torna dove eri. */
    private void confirmDropDraft(Player p, Runnable onNo) {
        if (!drafts.containsKey(p.getUniqueId())) {
            onNo.run();
            return;
        }
        plugin.getProfileUi().confirmOver(p, "Eliminare l'anteprima? Gli ordini scritti si perdono.", bgHere(p), () -> {
            drafts.remove(p.getUniqueId());
            GuiMsg.send(plugin, p, "&7anteprima eliminata.");
            open(p);
        }, onNo);
    }

    private String invalidReason(Player p, TaskParser.ParsedOrder o) {
        try {
            return tasks().whyInvalid(p, TaskParser.toOrder(o));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Cella di un ramo nell'anteprima: azioni (gialle se incerte, click = scegli), condizione, "non fare nulla". */
    private Row previewBranchCell(Player p, TaskParser.ParsedOrder o, int oi, int bi, TaskParser.ParsedBranch b, int page) {
        if (b.nothing()) {
            return new Row(icon("task_nulla", "&7non fare nulla", List.of(
                    b.condition() == null ? "&7se: &fnessun caso vale" : "&7se: &f" + clean(b.condition()))), null, null);
        }
        List<String> lore = new ArrayList<>();
        if (b.condition() != null && !b.condition().isBlank()) {
            lore.add("&7se: &f" + clean(b.condition()));
        }
        TaskParser.ParsedAction firstUnsure = null;
        int firstIdx = -1;
        List<TaskParser.ParsedAction> acts = safe(b.actions());
        for (int ai = 0; ai < acts.size(); ai++) {
            TaskParser.ParsedAction pa = acts.get(ai);
            String desc = pa.action() == null ? clean(pa.sourceText()) : StaffTasks.describe(pa.action());
            if (pa.uncertain()) {
                lore.add("&e? " + desc + (pa.field() == null ? "" : " &8(" + fieldLabel(pa.field()) + ")"));
                if (firstUnsure == null) {
                    firstUnsure = pa;
                    firstIdx = ai;
                }
            } else {
                lore.add("&f- " + desc);
            }
        }
        boolean cond = o.type() == StaffTasks.OrderType.CONDIZIONALE;
        String name = cond ? "&eramo " + (bi + 1) : "&aazioni";
        String iconName;
        if (firstUnsure != null) {
            iconName = "task_dubbio";
            name = "&e" + (cond ? "ramo " + (bi + 1) : "azioni") + " da chiarire";
            lore.add("");
            lore.add("&eclicca per chiarire");
        } else if (cond) {
            iconName = "task_ramo";
        } else {
            iconName = actionIcon(acts.isEmpty() || acts.get(0).action() == null ? null : acts.get(0).action().verb());
        }
        int ai = firstIdx;
        return new Row(icon(iconName, name, lore), firstUnsure == null ? null : () -> {
            resolve(p, oi, bi, ai, page);
        }, null);
    }

    /** Chiarisce un'azione incerta: alternative con le frecce, oppure il valore (quantita'/codice regola) da scrivere. */
    private void resolve(Player p, int oi, int bi, int ai, int page) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null || oi >= d.orders.size()) {
            openPreview(p, page);
            return;
        }
        TaskParser.ParsedOrder o = d.orders.get(oi);
        TaskParser.ParsedBranch b = o.branches().get(bi);
        TaskParser.ParsedAction pa = b.actions().get(ai);
        List<TaskParser.Candidate> alts = safe(pa.alternatives());
        Runnable back = () -> {
            openPreview(p, page);
        };
        if (alts.isEmpty()) {
            if ("amount".equals(pa.field())) {
                plugin.getProfileUi().askNumber(p, "quantita' (" + clean(pa.sourceText()) + ")", "QUANTITA", 1, Integer.MAX_VALUE, n -> {
                    apply(p, o, oi, bi, ai, pa, new TaskParser.Candidate(String.valueOf(n), String.valueOf(n), 0));
                    openPreview(p, page);
                }, back);
            } else {
                plugin.getProfileUi().askText(p, fieldLabel(pa.field()) + " (" + clean(pa.sourceText()) + ")",
                        fieldLabel(pa.field()).toUpperCase(Locale.ROOT), "", v -> {
                            if (v == null || v.isBlank()) {
                                deny(p, "scrivi " + fieldLabel(pa.field()) + ".");
                            } else {
                                apply(p, o, oi, bi, ai, pa, new TaskParser.Candidate(v.trim(), v.trim(), 0));
                            }
                            openPreview(p, page);
                        }, back);
            }
            return;
        }
        List<Opt> opts = new ArrayList<>();
        for (int i = 0; i < alts.size(); i++) {
            TaskParser.Candidate c = alts.get(i);
            opts.add(new Opt(String.valueOf(i), clean(c.label()), List.of(
                    "&7intendevi: &f" + clean(c.label()) + "&7?",
                    "&7parte da chiarire: &f" + fieldLabel(pa.field()),
                    "&7scritto: &f" + clean(pa.sourceText()))));
        }
        choose(p, "intendevi", opts, 0, bgHere(p), key -> {
            int ci = Integer.parseInt(key);
            if (ci < alts.size()) {
                apply(p, o, oi, bi, ai, pa, alts.get(ci));
            }
            openPreview(p, page);
        }, back);
    }

    /** Applica la scelta (TaskParser.choose) e ricostruisce ramo e ordine (sono record). */
    private void apply(Player p, TaskParser.ParsedOrder o, int oi, int bi, int ai, TaskParser.ParsedAction pa, TaskParser.Candidate c) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null || oi >= d.orders.size() || d.orders.get(oi) != o) {
            return;
        }
        TaskParser.ParsedAction np;
        try {
            np = parser().choose(pa, c);
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("TaskGui: errore nella scelta: " + ex);
            deny(p, "non sono riuscito ad applicare la scelta.");
            return;
        }
        TaskParser.ParsedBranch b = o.branches().get(bi);
        List<TaskParser.ParsedAction> acts = new ArrayList<>(b.actions());
        acts.set(ai, np);
        List<TaskParser.ParsedBranch> brs = new ArrayList<>(o.branches());
        brs.set(bi, new TaskParser.ParsedBranch(b.condition(), acts, b.nothing()));
        d.orders.set(oi, new TaskParser.ParsedOrder(o.text(), brs, o.type()));
    }

    private void confirmSave(Player p) {
        Draft d = drafts.get(p.getUniqueId());
        if (d == null || d.orders.isEmpty()) {
            open(p);
            return;
        }
        List<StaffTasks.Order> orders = new ArrayList<>();
        for (TaskParser.ParsedOrder o : d.orders) {
            if (TaskParser.uncertain(o)) {
                deny(p, "ci sono ordini da chiarire: scegli cosa intendevi prima di salvare.");
                openPreview(p, 0);
                return;
            }
            StaffTasks.Order ord = TaskParser.toOrder(o);
            String why = tasks().whyInvalid(p, ord);
            if (why != null) {
                deny(p, why);
                openPreview(p, 0);
                return;
            }
            orders.add(ord);
        }
        plugin.getProfileUi().confirmOver(p, "Salvare la task con " + orders.size() + (orders.size() == 1 ? " ordine?" : " ordini?"), bgHere(p), () -> {
            if (!guard(p)) {
                return;
            }
            StaffTasks.Task t = tasks().create(p, orders, d.reason);
            if (t == null) {
                deny(p, "non e' stato possibile creare la task (controlla ordini e motivazione).");
                openPreview(p, 0);
                return;
            }
            drafts.remove(p.getUniqueId());
            GuiMsg.send(plugin, p, "&7task &f#" + t.id() + "&7 creata (" + orders.size() + " ordini).");
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
            new Filter("Mie", "MIE"));

    private void openList(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        if (tasks().chargeOf(p.getUniqueId()) != null) {
            chargePopup(p, () -> {
                openList(p, filterIdx, page);
            });
            return;
        }
        List<Filter> filters = withAdv(p, "list", LIST_FILTERS);
        int fi = Math.max(0, Math.min(filterIdx, filters.size() - 1));
        StaffTasks t = tasks();
        UUID me = p.getUniqueId();
        String key = filters.get(fi).key();
        Adv adv = advOf(p, "list", key);
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.Task k : safe(t.list(StaffTasks.Status.APERTA))) {
            int can = 0;
            for (StaffTasks.Order o : safe(k.orders())) {
                if (o.status() == StaffTasks.Status.APERTA && t.chargeOn(k.id(), o.id()) == null && t.canTake(p, k, o) == null) {
                    can++;
                }
            }
            if ("CAN".equals(key) && can == 0) {
                continue;
            }
            if ("MIE".equals(key) && !me.equals(k.createdBy())) {
                continue;
            }
            if (adv != null && !matchesTask(adv, k)) {
                continue;
            }
            StaffTasks.Counts c = StaffTasks.counts(k);
            boolean del = t.canDelete(p, k);
            List<String> lore = folderLore(k, c);
            if (can > 0) {
                lore.add("&7puoi prendere: &f" + can + (can == 1 ? " ordine" : " ordini"));
            }
            lore.add("");
            lore.add("&eclicca per aprire" + (del ? "  &8|  &cQ per eliminare" : ""));
            String id = k.id();
            Runnable back = () -> {
                openList(p, fi, page);
            };
            rows.add(new Row(icon(c.total() == 0 ? "task_cartella_vuota" : "task_cartella", folderName(k), lore), () -> {
                viewTask(p, id, 0, back);
            }, null, del ? () -> {
                confirmDelete(p, id, back, back);
            } : null));
        }
        listPanel(p, "lista task", "list", filters, fi, page, rows, (f, pg) -> {
            openList(p, f, pg);
        }, () -> {
            open(p);
        });
    }

    /** Nome della cartella: "Task #4" (i dettagli sono nella lore). */
    private String folderName(StaffTasks.Task k) {
        return "&f&lTask #" + k.id();
    }

    /**
     * Lore della cartella (uguale in lista, cestino e pulsante della cartella), es.
     * "creata da DoppiaX il 19/11/2026 14:52" / "motivo: ..." / "" / "ordinarie: 2 (1/2 completate)" / "custom: ..." / "stato: da fare".
     * Mai nomi dei player degli ordini (solo chi l'ha creata).
     */
    private List<String> folderLore(StaffTasks.Task k, StaffTasks.Counts c) {
        List<String> lore = new ArrayList<>();
        lore.add("&7creata da &f" + clean(k.createdByName()) + " &7il &f" + SHORT.format(Instant.ofEpochMilli(k.createdAt())));
        if (k.reason() != null && !k.reason().isBlank()) {
            lore.add("&7motivo: &f" + clean(k.reason()));
        }
        lore.add("");
        int ord = c.simple() + c.conditional();
        int doneOrd = c.doneSimple() + c.doneConditional();
        if (ord > 0) {
            lore.add("&7ordinarie: &f" + ord + " &8(&a" + doneOrd + "&8/&f" + ord + " &7completate&8)");
        }
        if (c.custom() > 0) {
            lore.add("&7custom: &f" + c.custom() + " &8(&a" + c.doneCustom() + "&8/&f" + c.custom() + " &7completate&8)");
        }
        if (c.total() == 0) {
            lore.add("&7nessun ordine");
        }
        lore.add("&7stato: " + taskState(k, c));
        return lore;
    }

    private String taskState(StaffTasks.Task k, StaffTasks.Counts c) {
        if (k.status() == StaffTasks.Status.ELIMINATA) {
            return "&celiminata";
        }
        if (c.total() > 0 && c.done() >= c.total()) {
            return "&acompletata";
        }
        for (StaffTasks.Order o : safe(k.orders())) {
            if (o.status() == StaffTasks.Status.APERTA && tasks().chargeOn(k.id(), o.id()) != null) {
                return "&6in corso";
            }
        }
        return c.done() > 0 ? "&ein parte fatta" : "&eda fare";
    }

    /** Cartella di una task salvata: ogni ordine e' un blocco di celle unite. */
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
        Runnable self = () -> {
            viewTask(p, taskId, page, back);
        };
        List<List<Row>> blocks = new ArrayList<>();
        List<StaffTasks.Order> orders = safe(k.orders());
        for (int oi = 0; oi < orders.size(); oi++) {
            blocks.add(orderCells(p, k, orders.get(oi), oi, self));
        }
        List<FolderPage> pages = layout(blocks);
        int pg = Math.max(0, Math.min(page, pages.size() - 1));
        // task salvata: niente "+" (gli ordini si aggiungono solo nell'anteprima); il pulsante verde mostra le info.
        // Creatore o admin+ (canDelete): SOLO Q = elimina la task (sul pulsante) / togli l'ordine (sulla cella testo).
        boolean del = k.status() != StaffTasks.Status.ELIMINATA && t.canDelete(p, k);
        List<String> btnLore = folderLore(k, StaffTasks.counts(k));
        if (del) {
            btnLore.add("");
            btnLore.add("&cQ per eliminare la task");
        }
        Runnable delete = del ? () -> {
            confirmDelete(p, taskId, back, self);
        } : null;
        Holder h = folder(p, "task " + k.id(), pages.get(pg), pg, pages.size(), pgn -> {
            viewTask(p, taskId, pgn, back);
        }, back, "&cindietro", List.of(), pane(folderName(k), btnLore), null, Map.of(), false, false);
        if (h != null && delete != null) {
            for (int s : L_BTN) {
                h.dropActions.put(s, delete);
            }
        }
    }

    /** Celle di un ordine salvato: [testo][prendi] oppure [testo][ramo..][nulla]. */
    private List<Row> orderCells(Player p, StaffTasks.Task k, StaffTasks.Order o, int oi, Runnable self) {
        StaffTasks t = tasks();
        StaffTasks.OrderType type = StaffTasks.type(o);
        StaffTasks.Charge charge = t.chargeOn(k.id(), o.id());
        boolean mine = charge != null && charge.staff().equals(p.getUniqueId());
        boolean done = o.status() != StaffTasks.Status.APERTA;
        boolean deleted = k.status() == StaffTasks.Status.ELIMINATA;
        String takeWhy = done || deleted || charge != null ? null : t.canTake(p, k, o);
        List<Row> cells = new ArrayList<>();

        // testo
        List<String> tl = new ArrayList<>();
        tl.add("&7frase: &f" + clean(o.text()));
        if (k.reason() != null && !k.reason().isBlank()) {
            tl.add("&7motivazione: &f" + clean(k.reason()));
        }
        tl.add("&7tipo: &f" + typeLabel(type));
        tl.add("&7stato: " + orderState(o, charge));
        if (done && o.chosenBranch() >= 0 && o.chosenBranch() < safe(o.branches()).size() && type == StaffTasks.OrderType.CONDIZIONALE) {
            tl.add("&7ramo scelto: &f" + StaffTasks.describe(o.branches().get(o.chosenBranch())));
        }
        Runnable textClick = null;
        if (type == StaffTasks.OrderType.CUSTOM && mine) {
            tl.add("");
            tl.add("&eclicca quando hai finito");
            textClick = () -> {
                finishCustom(p, self);
            };
        }
        // togliere un ordine non completato: solo creatore o admin+, SOLO col tasto Q (con conferma)
        Runnable removeOrder = null;
        if (!done && !deleted && t.canDelete(p, k)) {
            tl.add("");
            tl.add("&cQ per togliere");
            removeOrder = () -> {
                confirmRemoveOrder(p, k.id(), o.id(), oi + 1, self);
            };
        }
        String textIcon = type == StaffTasks.OrderType.CUSTOM ? "task_custom" : "task_testo";   // sempre un foglio (viola se custom)
        cells.add(new Row(icon(textIcon, (done ? "&a" : "&f") + "ordine " + (oi + 1), tl), textClick, null, removeOrder));

        List<StaffTasks.Branch> branches = safe(o.branches());
        if (type != StaffTasks.OrderType.CONDIZIONALE) {
            // [prendi]: semplice (azioni del ramo 0) o custom (fatto a mano)
            List<String> lore = new ArrayList<>();
            if (!branches.isEmpty()) {
                addActions(lore, branches.get(0));
            }
            if (type == StaffTasks.OrderType.CUSTOM) {
                lore.add("&7azioni: &fa mano");
            }
            String iconName;
            String name;
            Runnable click = null;
            if (done) {
                iconName = "task_fatto";
                name = "&acompletato";
                lore.add("&7da: &f" + clean(o.doneByName()) + " &8(" + date(o.doneAt()) + ")");
            } else if (mine && type == StaffTasks.OrderType.CUSTOM) {
                iconName = "task_salva";
                name = "&asegna come fatto";
                lore.add("&7in carico: &ate");
                lore.add("&7mancano: &f" + mmss(StaffTasks.remainingMs(charge)));
                lore.add("");
                lore.add("&eclicca quando hai finito");
                click = () -> {
                    finishCustom(p, self);
                };
            } else if (charge != null) {
                iconName = "task_in_carico";
                name = mine ? "&ain carico a te" : "&6in carico";
                lore.add("&7in carico: " + (mine ? "&ate" : "&f" + staffName(charge.staff())));
                lore.add("&7mancano: &f" + mmss(StaffTasks.remainingMs(charge)));
            } else {
                iconName = "task_prendi";
                name = "&eprendi in carico";
                if (takeWhy == null && !deleted) {
                    lore.add("");
                    lore.add("&eclicca per prenderlo (5 min)");
                } else {
                    lore.add("&7stato: &8non disponibile");
                }
                if (takeWhy == null && !deleted) {
                    click = () -> {
                        confirmTake(p, k, o, 0, branches.isEmpty() ? "" : StaffTasks.describe(branches.get(0)), self);
                    };
                }
            }
            cells.add(new Row(icon(iconName, name, lore), click, null));
            return cells;
        }
        // condizionale: un ramo per cella, l'ultimo e' "non fare nulla"
        for (int bi = 0; bi < branches.size(); bi++) {
            StaffTasks.Branch b = branches.get(bi);
            boolean chosen = o.chosenBranch() == bi;
            boolean chargedHere = charge != null && charge.branch() == bi;
            List<String> lore = new ArrayList<>();
            if (b.condition() != null && !b.condition().isBlank()) {
                lore.add("&7se: &f" + clean(b.condition()));
            }
            if (b.nothing()) {
                lore.add("&7azione: &fnessuna");
            } else {
                addActions(lore, b);
            }
            String iconName = b.nothing() ? "task_nulla" : "task_ramo";
            String name = b.nothing() ? "&7non fare nulla" : "&eramo " + (bi + 1);
            Runnable click = null;
            if (done) {
                if (chosen) {
                    iconName = "task_fatto";
                    name = "&a" + (b.nothing() ? "non fare nulla" : "ramo " + (bi + 1)) + " (scelto)";
                    lore.add("&7da: &f" + clean(o.doneByName()) + " &8(" + date(o.doneAt()) + ")");
                }
            } else if (chargedHere) {
                iconName = "task_in_carico";
                lore.add("&7in carico: " + (mine ? "&ate" : "&f" + staffName(charge.staff())));
                lore.add("&7mancano: &f" + mmss(StaffTasks.remainingMs(charge)));
            } else if (charge == null && takeWhy == null && !deleted) {
                lore.add("");
                if (b.nothing()) {
                    lore.add("&eclicca se non va fatto niente");
                } else {
                    lore.add("&eclicca per prenderlo (5 min)");
                }
                int branch = bi;
                click = () -> {
                    confirmTake(p, k, o, branch, StaffTasks.describe(b), self);
                };
            }
            cells.add(new Row(icon(iconName, name, lore), click, null));
        }
        return cells;
    }

    private void addActions(List<String> lore, StaffTasks.Branch b) {
        for (StaffTasks.Action a : safe(b.actions())) {
            lore.add((a.done() ? "&a✔ " : "&f- ") + StaffTasks.describe(a));
        }
    }

    private String orderState(StaffTasks.Order o, StaffTasks.Charge charge) {
        if (o.status() != StaffTasks.Status.APERTA) {
            return "&acompletato";
        }
        if (charge != null) {
            return "&6in carico a &f" + staffName(charge.staff()) + " &8(" + mmss(StaffTasks.remainingMs(charge)) + ")";
        }
        return "&elibero";
    }

    /**
     * Ha gia' un incarico: "hai gia' un incarico, non puoi prenderne altri" con DISINCARICA (release come la scadenza:
     * azioni fatte rimesse, ordine di nuovo libero) e CONTINUA (chiude: torna a fare l'incarico).
     */
    private void chargePopup(Player p, Runnable after) {
        StaffTasks.Charge c = tasks().chargeOf(p.getUniqueId());
        if (c == null) {
            after.run();
            return;
        }
        StaffTasks.Action next = tasks().nextAction(c);
        String what = next == null ? "ordine custom da segnare come fatto" : StaffTasks.describe(next);
        plugin.getProfileUi().confirmOver(p, "Hai gia' un incarico: " + what + ". Non puoi prenderne altri!", bgHere(p), () -> {
            StaffTasks.Charge now = tasks().chargeOf(p.getUniqueId());
            if (now != null) {
                tasks().release(p.getUniqueId(), "disincaricato");
                GuiMsg.send(plugin, p, "&7incarico lasciato.");
            }
            after.run();
        }, () -> {
            p.closeInventory();
        }, null, "disincarica", "continua");
    }

    private void confirmTake(Player p, StaffTasks.Task k, StaffTasks.Order o, int branch, String what, Runnable self) {
        // ha gia' un incarico: popup DISINCARICA / CONTINUA invece della conferma
        if (tasks().chargeOf(p.getUniqueId()) != null) {
            chargePopup(p, self);
            return;
        }
        int number = Math.max(0, safe(k.orders()).indexOf(o)) + 1;
        boolean cond = StaffTasks.type(o) == StaffTasks.OrderType.CONDIZIONALE;
        StaffTasks.Branch b = branch >= 0 && branch < safe(o.branches()).size() ? o.branches().get(branch) : null;
        String desc = b != null && b.nothing()
                ? "Confermi che nell'ordine " + number + " della task #" + k.id() + " non va fatto niente?"
                : "Vuoi prendere in carico " + (cond ? "il ramo " + (branch + 1) + " dell'ordine " : "l'ordine ") + number
                + " della task #" + k.id() + "?";
        plugin.getProfileUi().confirmOver(p, desc, bgHere(p), () -> {
            if (!guard(p)) {
                return;
            }
            String err = tasks().take(p, k.id(), o.id(), branch);
            if (err != null) {
                deny(p, err);
                self.run();
                return;
            }
            StaffTasks.Charge c = tasks().chargeOf(p.getUniqueId());
            if (c == null) {
                GuiMsg.send(plugin, p, "&aordine completato.");   // ramo "non fare nulla": completato subito
                self.run();
                return;
            }
            StaffTasks.Action next = tasks().nextAction(c);
            GuiMsg.send(plugin, p, "&aordine preso in carico. &7" + (next == null ? "controlla e segna come fatto." : "fai: &f" + StaffTasks.describe(next)));
            p.closeInventory();
        }, self);
    }

    private void finishCustom(Player p, Runnable self) {
        plugin.getProfileUi().confirmOver(p, "Segnare come fatto l'ordine che hai in carico?", bgHere(p), () -> {
            if (!guard(p)) {
                return;
            }
            String err = tasks().finishCustom(p);
            if (err == null) {
                GuiMsg.send(plugin, p, "&aordine segnato come fatto.");
            } else {
                deny(p, err);
            }
            self.run();
        }, self);
    }

    private void confirmRemoveOrder(Player p, String taskId, String orderId, int number, Runnable self) {
        plugin.getProfileUi().confirmOver(p, "Togliere l'ordine " + number + " dalla task #" + taskId + "?", bgHere(p), () -> {
            if (!guard(p)) {
                return;
            }
            String err = tasks().removeOrder(p, taskId, orderId);
            if (err == null) {
                GuiMsg.send(plugin, p, "&7ordine " + number + " tolto dalla task &f#" + taskId + "&7.");
            } else {
                deny(p, err);
            }
            self.run();
        }, self);
    }

    private void confirmDelete(Player p, String taskId, Runnable afterDelete, Runnable onNo) {
        plugin.getProfileUi().confirmOver(p, "Eliminare la task #" + taskId + "? Va nel cestino.", bgHere(p), () -> {
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

    // ------------------------------------------------------------------
    //  CESTINO (admin)
    // ------------------------------------------------------------------

    private static final List<Filter> TRASH_FILTERS = List.of(new Filter("Eliminate", ""));

    private void openTrash(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        if (!tasks().isAdmin(p)) {
            deny(p, "il cestino e' solo per gli admin.");
            return;
        }
        List<Filter> filters = withAdv(p, "trash", TRASH_FILTERS);
        int fi = Math.max(0, Math.min(filterIdx, filters.size() - 1));
        Adv adv = advOf(p, "trash", filters.get(fi).key());
        StaffTasks t = tasks();
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.Task k : safe(t.list(StaffTasks.Status.ELIMINATA))) {
            if (adv != null && !matchesTask(adv, k)) {
                continue;
            }
            List<String> lore = folderLore(k, StaffTasks.counts(k));
            lore.add("");
            lore.add("&esinistro ripristina &8| &edestro apri");
            String id = k.id();
            Runnable back = () -> {
                openTrash(p, fi, page);
            };
            StaffTasks.Counts kc = StaffTasks.counts(k);
            rows.add(new Row(icon(kc.total() == 0 ? "task_cartella_vuota" : "task_cartella", "&c&lTask #" + k.id(), lore), () -> {
                plugin.getProfileUi().confirmOver(p, "Ripristinare la task #" + id + "?", bgHere(p), () -> {
                    if (!guard(p)) {
                        return;
                    }
                    if (tasks().restore(p, id)) {
                        GuiMsg.send(plugin, p, "&7task &f#" + id + "&7 ripristinata.");
                    } else {
                        deny(p, "non e' stato possibile ripristinare la task.");
                    }
                    back.run();
                }, back);
            }, () -> {
                viewTask(p, id, 0, back);
            }));
        }
        listPanel(p, "cestino task", "trash", filters, fi, page, rows, (f, pg) -> {
            openTrash(p, f, pg);
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
            new Filter("Ordini tolti", "TOLTO"),
            new Filter("Prese", "PRESA"),
            new Filter("Azioni", "AZIONE"),
            new Filter("Completate", "COMPLETATA"),
            new Filter("Annullate", "ANNULLATA"),
            new Filter("Eliminate", "ELIMINATA"),
            new Filter("Ripristinate", "RIPRISTINATA"));

    private void openLog(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        List<Filter> filters = withAdv(p, "log", LOG_FILTERS);
        int fi = Math.max(0, Math.min(filterIdx, filters.size() - 1));
        String key = filters.get(fi).key();
        Adv adv = advOf(p, "log", key);
        StaffTasks t = tasks();
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.LogEntry e : safe(t.log(t.isAdmin(p) ? null : p.getUniqueId()))) {
            String ev = e.event() == null ? "" : e.event().toUpperCase(Locale.ROOT);
            if (adv == null && !key.isEmpty() && !key.equals(ev)) {
                continue;
            }
            if (adv != null && !matchesLog(adv, e)) {
                continue;
            }
            String iconName;
            String color;
            switch (ev) {
                case "CREATA" -> { iconName = "task_cartella"; color = "&e"; }
                case "TOLTO" -> { iconName = "task_elimina"; color = "&c"; }
                case "PRESA" -> { iconName = "task_in_carico"; color = "&6"; }
                case "AZIONE" -> { iconName = "task_prendi"; color = "&b"; }
                case "COMPLETATA" -> { iconName = "task_fatto"; color = "&a"; }
                case "ANNULLATA" -> { iconName = "task_rifiutato"; color = "&c"; }
                case "ELIMINATA" -> { iconName = "task_elimina"; color = "&4"; }
                case "RIPRISTINATA" -> { iconName = "task_riscrivi"; color = "&b"; }
                default -> { iconName = "task_testo"; color = "&7"; }
            }
            StaffTasks.Task k = t.get(e.taskId());
            StaffTasks.Order o = null;
            if (k != null && e.orderId() != null) {
                for (StaffTasks.Order x : safe(k.orders())) {
                    if (x.id().equals(e.orderId())) {
                        o = x;
                    }
                }
            }
            List<String> lore = new ArrayList<>();
            lore.add("&7task: &f#" + e.taskId());
            if (o != null) {
                lore.add("&7ordine: &f" + clean(o.text()));
            }
            lore.add("&7da: &f" + (e.byName() == null || e.byName().isBlank() ? "sistema" : clean(e.byName())));
            lore.add("&7quando: &f" + date(e.ts()));
            if (e.detail() != null && !e.detail().isBlank()) {
                lore.add("&f" + clean(e.detail()));
            }
            // nome corto: evento e task, i dettagli stanno nella lore
            rows.add(new Row(icon(iconName, color + ev.toLowerCase(Locale.ROOT) + " &7- &ftask #" + e.taskId(), lore), null, null));
        }
        listPanel(p, "resoconto task", "log", filters, fi, page, rows, (f, pg) -> {
            openLog(p, f, pg);
        }, () -> {
            open(p);
        });
    }

    // ------------------------------------------------------------------
    //  Testi e icone
    // ------------------------------------------------------------------

    private static String typeLabel(StaffTasks.OrderType t) {
        if (t == null) {
            return "";
        }
        return switch (t) {
            case SEMPLICE -> "semplice";
            case CONDIZIONALE -> "con condizioni";
            case CUSTOM -> "custom";
        };
    }

    private static String actionIcon(String verb) {
        return switch (verb == null ? "" : verb) {
            case "give" -> "task_azione_dai";
            case "remove" -> "task_azione_togli";
            case "set" -> "task_azione_setta";
            case "reset" -> "task_azione_azzera";
            case "ban", "warn", "mute" -> "task_azione_sanzione";
            default -> "task_testo";
        };
    }

    private static String staffName(UUID id) {
        String n = UserProfilePowers.nameOf(id);
        return n.isEmpty() ? "?" : n;
    }

    private static String mmss(long ms) {
        long s = Math.max(0, ms) / 1000;
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }

    // ------------------------------------------------------------------
    //  Cartella: impaginazione degli ordini in righe unite
    // ------------------------------------------------------------------

    /**
     * Mette i blocchi (celle di un ordine) nella griglia 7x3 uno dopo l'altro, separati da UNO slot vuoto. Un ordine che
     * entra in una riga non viene spezzato (se non ci sta nel resto della riga va a capo); uno piu' lungo di 7 celle parte da
     * una riga nuova e continua sotto (barra aperta ai bordi). Se le righe finiscono si passa alla pagina dopo.
     */
    private static List<FolderPage> layout(List<List<Row>> blocks) {
        List<FolderPage> pages = new ArrayList<>();
        Map<Integer, Row> cells = new HashMap<>();
        List<Span> spans = new ArrayList<>();
        int row = 0;
        int col = 0;
        for (List<Row> b : blocks) {
            int n = b.size();
            if (n == 0) {
                continue;
            }
            int need = (n + GRID_COLS - 1) / GRID_COLS;
            if (col > 0 && (n > GRID_COLS || col + n > GRID_COLS)) {
                row++;   // non sta nel resto della riga (o va spezzato): riga nuova
                col = 0;
            }
            if (row >= GRID_ROWS || (row > 0 && row + Math.min(need, GRID_ROWS) > GRID_ROWS)) {
                pages.add(new FolderPage(cells, spans));
                cells = new HashMap<>();
                spans = new ArrayList<>();
                row = 0;
                col = 0;
            }
            int i = 0;
            while (i < n) {
                if (row >= GRID_ROWS) {   // ordine piu' lungo di una pagina intera: continua sulla pagina dopo
                    pages.add(new FolderPage(cells, spans));
                    cells = new HashMap<>();
                    spans = new ArrayList<>();
                    row = 0;
                    col = 0;
                }
                int len = Math.min(GRID_COLS - col, n - i);
                for (int c = 0; c < len; c++) {
                    cells.put(10 + row * 9 + col + c, b.get(i + c));
                }
                spans.add(new Span(row, col, len, i > 0, i + len < n));
                i += len;
                if (i < n) {
                    row++;
                    col = 0;
                } else {
                    col += len + 1;   // uno slot vuoto prima del prossimo ordine
                    if (col >= GRID_COLS) {
                        row++;
                        col = 0;
                    }
                }
            }
        }
        pages.add(new FolderPage(cells, spans));
        return pages;
    }

    /**
     * Popup cartella (U+E11E) sopra la schermata task scurita (titolo in alto = "TASK" scurito, niente titolo del popup):
     * barre unite dietro le celle (font task_row), celle, X, pulsante verde, frecce pagina e celle extra (slot 37/43).
     * btns = pulsanti disegnati motivazione/salva dell'anteprima (font task_btn; salva grigio se !saveReady).
     * Ritorna l'holder (per aggiungere azioni Q).
     */
    private Holder folder(Player p, String barText, FolderPage fp, int pg, int pages, IntConsumer reopenPage,
                          Runnable onBack, String backName, List<String> backLore, ItemStack button, Runnable btnLeft,
                          Map<Integer, Row> extra, boolean btns, boolean saveReady) {
        Holder h = new Holder();
        GuiTitle g = new GuiTitle(plugin);
        UserProfilePowers.drawDimmed(plugin, g, bg());
        g.at(-15, "&f" + F_IMAGE, F_FONT, F_ADV);
        for (Span s : fp.spans()) {
            drawSpan(g, s, L_IMG);
        }
        if (btns) {
            drawButtons(g, 0, saveReady);
        }
        smallText(g, barText, L_BAR_FONT, L_BAR_CX, L_BAR_MAX);
        navText(g, pg + 1, pages);
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());
        // sfondo dei popup aperti dalla cartella/anteprima: schermata task (con "TASK") + cartella con barre e pulsanti
        // disegnati (advance reale, testi del pannello esclusi)
        h.under = u -> {
            background(u);
            u.at(-15, "&f" + F_IMAGE, F_FONT, F_ADV + 7);
            for (Span s : fp.spans()) {
                drawSpan(u, s, L_IMG + 7);
            }
            if (btns) {
                drawButtons(u, 7, saveReady);
            }
        };

        put(h, L_X, pane(backName, backLore), onBack);
        put(h, L_BTN, button, btnLeft);
        put(h, new int[]{L_PREV}, pane("&7pagina precedente", List.of("&7pagina: &f" + (pg + 1) + "&8/&f" + pages)), pg > 0 ? () -> {
            reopenPage.accept(pg - 1);
        } : null);
        put(h, new int[]{L_NEXT}, pane("&7pagina successiva", List.of("&7pagina: &f" + (pg + 1) + "&8/&f" + pages)), pg < pages - 1 ? () -> {
            reopenPage.accept(pg + 1);
        } : null);
        Map<Integer, Row> all = new HashMap<>(fp.cells());
        all.putAll(extra);
        if (fp.cells().isEmpty()) {
            h.inventory.setItem(gridSlot(10), icon("task_cartella_vuota", "&7nessun ordine", List.of()));
        }
        for (Map.Entry<Integer, Row> e : all.entrySet()) {
            Row r = e.getValue();
            int[] slot = {e.getKey()};
            put(h, slot, r.item(), r.left());
            if (r.right() != null) {
                putRight(h, slot, r.right());
            }
            if (r.drop() != null) {
                h.dropActions.put(e.getKey(), r.drop());
            }
        }
        p.openInventory(h.inventory);
        return h;
    }

    /**
     * Pulsanti motivazione (fumetto) e salva (dischetto) dell'anteprima, disegnati nel titolo (font task_btn, docs/TASK_BTN.md)
     * allineati alla barra pagina; negli slot F_LEFT/F_RIGHT ci sono pane invisibili con nome e lore. dx = 0 nel titolo
     * normale (cursore 7 px indietro, convenzione IMG = -22), 7 nello sfondo dei popup (advance reale).
     */
    private void drawButtons(GuiTitle g, int dx, boolean saveReady) {
        g.at(BTN_MOT_X + dx, "&f" + BTN_MOT, BTN_FONT, BTN_MOT_ADV);
        g.at(BTN_SAVE_X + dx, (saveReady ? "&f" : "&7") + BTN_SAVE, BTN_FONT, BTN_SAVE_ADV);
    }

    /**
     * Barra di una riga unita dietro le celle (font task_row, TASK_GLYPHS.md di Sara): carattere U+E200 + riga*21 + col*3 + pezzo
     * (l=0 inizio, m=1 centro/continua, r=2 fine), avanzamento reale 51 + 18*col. Disegnati come le overlay a pagina intera:
     * a -22 con l'avanzamento REALE (come StaffProfileGui.overlay), cosi' il cursore resta coerente per i testi dopo.
     */
    private void drawSpan(GuiTitle g, Span s, int x) {
        for (int i = 0; i < s.len(); i++) {
            int col = s.col() + i;
            int piece;
            if (i == s.len() - 1 && !s.toNext() && (i > 0 || s.fromPrev())) {
                piece = ROW_R;
            } else if (i == 0 && !s.fromPrev()) {
                piece = ROW_L;
            } else {
                piece = ROW_M;
            }
            char ch = (char) (ROW_BASE + s.row() * 21 + col * 3 + piece);
            g.at(x, "&f" + ch, ROW_FONT, 51 + 18 * col);
        }
    }

    // ------------------------------------------------------------------
    //  Popup scelta con frecce (come RoleGui, immagine con la barra)
    // ------------------------------------------------------------------

    /**
     * under = sfondo (colori pieni, col titolo "TASK") da scurire: quello del pannello da cui si apre, fisso anche quando si
     * scorre con le frecce. title non si disegna (decide solo la descrizione).
     */
    private void choose(Player p, String title, List<Opt> opts, int index, Consumer<GuiTitle> under, Consumer<String> onPick, Runnable onBack) {
        int n = opts.size();
        if (n == 0) {
            onBack.run();
            return;
        }
        int idx = Math.floorMod(index, n);
        Opt cur = opts.get(idx);
        Holder h = new Holder();
        GuiTitle g = new GuiTitle(plugin);
        UserProfilePowers.drawDimmed(plugin, g, under);
        g.at(-15, "&f" + CH_IMAGE, CH_FONT, CH_ADV);
        smallText(g, cur.label().toUpperCase(Locale.ROOT), CH_BAR_FONT, CH_BAR_CX, CH_BAR_MAX);
        // niente titolo del popup: in alto resta quello della GUI di sotto ("TASK" scurito, dentro under)
        // descrizione per ultima (un errore di larghezza dei glifi non sposta il resto)
        smallText(g, "intendevi".equals(title) ? "Cosa intendevi?" : "Scegli con le frecce.",   // 1 riga: deve stare in CH_DESC_MAX
                CH_DESC_FONT, CH_BAR_CX, CH_DESC_MAX);
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());

        List<String> barLore = new ArrayList<>(cur.lore());
        barLore.add("");
        for (int i = 0; i < n; i++) {
            barLore.add(i == idx ? "&a» " + opts.get(i).label() : "&8" + opts.get(i).label());
        }
        barLore.add("");
        barLore.add("&eusa le frecce per cambiare");
        put(h, CH_PREV, pane("&7precedente", List.of("&eclicca per scorrere")), () -> {
            choose(p, title, opts, idx - 1, under, onPick, onBack);
        });
        put(h, CH_NEXT, pane("&7successivo", List.of("&eclicca per scorrere")), () -> {
            choose(p, title, opts, idx + 1, under, onPick, onBack);
        });
        put(h, CH_BAR, pane("&f" + cur.label(), barLore), null);
        String key = cur.key();
        put(h, CH_YES, pane("&aconferma", List.of("&7Scelta: &f" + cur.label(), "", "&eclicca per confermare")), () -> {
            onPick.accept(key);
        });
        put(h, CH_NO, pane("&cannulla", List.of("&eclicca per annullare")), onBack);
        p.openInventory(h.inventory);
    }

    // ------------------------------------------------------------------
    //  Liste (copia di ProfileLists, con il click sulle voci)
    // ------------------------------------------------------------------

    private interface Reopen {
        void open(int filterIdx, int page);
    }

    /**
     * Popup lista. Pulsante filtro: click sinistro = filtro precedente (su), destro = successivo (giu'), Q = filtro avanzato
     * (incudine: codice task o nick, vedi askAdvanced) che si aggiunge in fondo ai filtri, gia' selezionato.
     * kind = chiave dei filtri avanzati di questa lista ("list", "trash", "log").
     */
    private void listPanel(Player p, String title, String kind, List<Filter> filters, int fi, int page, List<Row> rows, Reopen reopen,
                           Runnable onBack) {
        List<String> fl = new ArrayList<>();
        for (int i = 0; i < filters.size(); i++) {
            fl.add(i == fi ? "&a» " + filters.get(i).label() : "&8" + filters.get(i).label());
        }
        fl.add("");
        fl.add(filters.size() > 1 ? "&esinistro su &8| &edestro giù &8| &eQ cerca" : "&eQ cerca per task o nick");
        int n = filters.size();
        Runnable up = n > 1 ? () -> {
            reopen.open(Math.floorMod(fi - 1, n), 0);
        } : null;
        Runnable down = n > 1 ? () -> {
            reopen.open((fi + 1) % n, 0);
        } : null;
        int pages = Math.max(1, (rows.size() + L_PER_PAGE - 1) / L_PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder();
        GuiTitle g = new GuiTitle(plugin);
        UserProfilePowers.drawDimmed(plugin, g, bg());
        g.at(-15, "&f" + L_IMAGE, L_FONT, L_ADV);
        smallText(g, filters.get(fi).label(), L_BAR_FONT, L_BAR_CX, L_BAR_MAX);
        navText(g, pg + 1, pages);
        // niente titolo del popup: in alto resta "TASK" scurito (dentro bg())
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());
        // sfondo dei popup aperti dalla lista/cestino: schermata task + pannello lista (advance reale, testi esclusi)
        h.under = u -> {
            background(u);
            u.at(-15, "&f" + L_IMAGE, L_FONT, L_ADV + 7);
        };

        put(h, L_X, pane("&cindietro", List.of()), onBack);
        put(h, L_BTN, pane("&bfiltra per", fl), up);
        if (down != null) {
            putRight(h, L_BTN, down);
        }
        for (int s : L_BTN) {
            h.dropActions.put(s, () -> {
                askAdvanced(p, kind, () -> {
                    reopen.open(fi, page);
                }, () -> {
                    reopen.open(n, 0);   // il nuovo filtro e' in fondo: indice n
                });
            });
        }
        put(h, new int[]{L_PREV}, pane("&7pagina precedente", List.of("&7pagina: &f" + (pg + 1) + "&8/&f" + pages)), pg > 0 ? () -> {
            reopen.open(fi, pg - 1);
        } : null);
        put(h, new int[]{L_NEXT}, pane("&7pagina successiva", List.of("&7pagina: &f" + (pg + 1) + "&8/&f" + pages)), pg < pages - 1 ? () -> {
            reopen.open(fi, pg + 1);
        } : null);
        if (rows.isEmpty()) {
            h.inventory.setItem(gridSlot(10), icon("task_cartella_vuota", "&7nessuna voce", List.of()));
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
                if (r.drop() != null) {
                    h.dropActions.put(slot[0], r.drop());
                }
            }
        }
        p.openInventory(h.inventory);
    }

    // ------------------------------------------------------------------
    //  Filtri avanzati (Q sul pulsante filtro)
    // ------------------------------------------------------------------

    private List<Adv> adv(Player p, String kind) {
        return advFilters.computeIfAbsent(p.getUniqueId(), u -> new HashMap<>()).computeIfAbsent(kind, k -> new ArrayList<>());
    }

    /** Filtri base + filtri avanzati di questa lista (chiave ADV + indice). */
    private List<Filter> withAdv(Player p, String kind, List<Filter> base) {
        List<Filter> out = new ArrayList<>(base);
        List<Adv> list = adv(p, kind);
        for (int i = 0; i < list.size(); i++) {
            out.add(new Filter(list.get(i).label(), ADV + i));
        }
        return out;
    }

    private Adv advOf(Player p, String kind, String key) {
        if (key == null || !key.startsWith(ADV)) {
            return null;
        }
        List<Adv> list = adv(p, kind);
        try {
            int i = Integer.parseInt(key.substring(ADV.length()));
            return i >= 0 && i < list.size() ? list.get(i) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * Q sul pulsante filtro: incudine (con "continua in chat" di askText) per il codice di una task ("#4" o "4") o il nick di
     * un utente/staffer (anche scritto male: il piu' simile tra i conosciuti). Riuscito = filtro in fondo e onAdded.
     */
    private void askAdvanced(Player p, String kind, Runnable onBack, Runnable onAdded) {
        plugin.getProfileUi().askText(p, "codice task (es. #4) o nick", "CERCA", "", text -> {
            Adv a = parseAdvanced(text);
            if (a == null) {
                deny(p, "nessuna task o giocatore trovato per \"" + clean(text) + "\".");
                onBack.run();
                return;
            }
            adv(p, kind).add(a);
            onAdded.run();
        }, onBack);
    }

    private Adv parseAdvanced(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        String id = text.startsWith("#") ? text.substring(1).trim() : text;
        if (id.matches("\\d+")) {
            String tid = String.valueOf(Long.parseLong(id));
            boolean known = tasks().get(tid) != null;
            for (StaffTasks.LogEntry e : safe(tasks().log(null))) {
                known |= tid.equals(e.taskId());
            }
            return known ? new Adv("Task #" + tid, tid, null, null) : null;
        }
        // nick: il piu' simile tra player del server e nomi che compaiono nelle task (creatori, staff, bersagli)
        Map<String, UUID> names = knownNames();
        String q = TaskText.plain(text);
        String best = null;
        int bestD = Integer.MAX_VALUE;
        for (String n : names.keySet()) {
            String pn = TaskText.plain(n);
            int d = pn.equals(q) ? 0 : q.length() >= 3 && pn.startsWith(q) ? 1 : TaskText.levenshtein(q, pn);
            if (d < bestD) {
                bestD = d;
                best = n;
            }
        }
        if (best == null || bestD > Math.max(1, TaskText.threshold(q.length()))) {
            return null;
        }
        return new Adv(best, null, names.get(best), best);
    }

    private Map<String, UUID> knownNames() {
        Map<String, UUID> out = new HashMap<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            out.put(o.getName(), o.getUniqueId());
        }
        for (OfflinePlayer o : Bukkit.getOfflinePlayers()) {
            if (o.getName() != null && !o.getName().isEmpty()) {
                out.putIfAbsent(o.getName(), o.getUniqueId());
            }
        }
        StaffTasks t = tasks();
        List<StaffTasks.Task> all = new ArrayList<>(safe(t.list(StaffTasks.Status.APERTA)));
        all.addAll(safe(t.list(StaffTasks.Status.ELIMINATA)));
        for (StaffTasks.Task k : all) {
            putName(out, k.createdByName(), k.createdBy());
            for (StaffTasks.Order o : safe(k.orders())) {
                putName(out, o.doneByName(), o.doneBy());
                for (StaffTasks.Branch b : safe(o.branches())) {
                    for (StaffTasks.Action a : safe(b.actions())) {
                        putName(out, a.targetName(), a.target());
                    }
                }
            }
        }
        for (StaffTasks.LogEntry e : safe(t.log(null))) {
            putName(out, e.byName(), e.by());
        }
        return out;
    }

    private static void putName(Map<String, UUID> out, String name, UUID id) {
        if (name != null && !name.isBlank()) {
            out.putIfAbsent(name.trim(), id);
        }
    }

    private static boolean same(Adv a, UUID id, String name) {
        return (a.player() != null && a.player().equals(id)) || (a.name() != null && name != null && a.name().equalsIgnoreCase(name.trim()));
    }

    /** L'ordine riguarda il player: l'ha completato o e' il bersaglio di un'azione. */
    private static boolean involves(Adv a, StaffTasks.Order o) {
        if (same(a, o.doneBy(), o.doneByName())) {
            return true;
        }
        for (StaffTasks.Branch b : safe(o.branches())) {
            for (StaffTasks.Action x : safe(b.actions())) {
                if (same(a, x.target(), x.targetName()) || same(a, x.doneBy(), null)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Task del filtro: quella col codice, oppure creata dal player o con un ordine che lo riguarda (o in carico a lui). */
    private boolean matchesTask(Adv a, StaffTasks.Task k) {
        if (a.taskId() != null) {
            return a.taskId().equals(k.id());
        }
        if (same(a, k.createdBy(), k.createdByName())) {
            return true;
        }
        for (StaffTasks.Order o : safe(k.orders())) {
            StaffTasks.Charge c = tasks().chargeOn(k.id(), o.id());
            if (involves(a, o) || (c != null && same(a, c.staff(), null))) {
                return true;
            }
        }
        return false;
    }

    /** Voce del registro: della task col codice, oppure fatta dal player o su una task/ordine che lo riguarda. */
    private boolean matchesLog(Adv a, StaffTasks.LogEntry e) {
        if (a.taskId() != null) {
            return a.taskId().equals(e.taskId());
        }
        if (same(a, e.by(), e.byName())) {
            return true;
        }
        StaffTasks.Task k = tasks().get(e.taskId());
        if (k == null) {
            return false;
        }
        if (e.orderId() != null) {
            for (StaffTasks.Order o : safe(k.orders())) {
                if (o.id().equals(e.orderId())) {
                    return involves(a, o);
                }
            }
            return false;
        }
        return matchesTask(a, k);
    }

    private static int gridSlot(int i) {
        return 10 + (i / 7) * 9 + (i % 7);
    }

    /** Punteggiatura del font tiny (TINY_PUNCT.md): c'e' in tiny2/tiny3/tiny_y*, avanzamento = larghezza + 1. */
    private static final String PUNCT = "!?,.:'-/%()#+";
    private static final int[] PUNCT_ADV = {2, 5, 3, 2, 2, 2, 4, 4, 6, 3, 3, 6, 4};

    private static int tinyAdvance(String text) {
        int total = 0;
        for (char ch : text.toCharArray()) {
            int pi = PUNCT.indexOf(ch);
            total += pi >= 0 ? PUNCT_ADV[pi] : ch == ' ' ? 4 : (ch == 'i' || ch == 'I' || ch == '1') ? 4 : 6;
        }
        return total;
    }

    private static boolean tinyChar(char ch) {
        return ch < 128 && (Character.isLetterOrDigit(ch) || PUNCT.indexOf(ch) >= 0);
    }

    private static String letters(String text, char base) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            sb.append(tinyChar(ch) ? (char) (base + ch) : ' ');
        }
        return sb.toString();
    }

    /**
     * Accorcia il testo finche' sta in max px (il testo non deve MAI uscire dalla barra; il tiny e' gia' il font piu'
     * stretto del pack): prima abbrevia la parola piu' lunga col punto ("RIPRISTINATE" -> "RIPRIST."), poi taglia.
     */
    private static String fit(String text, int max) {
        String t = text == null ? "" : text.trim();
        while (tinyAdvance(t) > max) {
            String[] w = t.split(" ");
            int li = -1;
            for (int i = 0; i < w.length; i++) {
                int len = w[i].endsWith(".") ? w[i].length() - 1 : w[i].length();
                if (len > 3 && (li < 0 || len >= (w[li].endsWith(".") ? w[li].length() - 1 : w[li].length()))) {
                    li = i;
                }
            }
            if (li < 0) {
                break;
            }
            String base = w[li].endsWith(".") ? w[li].substring(0, w[li].length() - 1) : w[li];
            w[li] = base.substring(0, base.length() - 1) + ".";
            t = String.join(" ", w);
        }
        while (tinyAdvance(t) > max && t.length() > 1) {
            t = t.substring(0, t.length() - 1).trim();   // ultima risorsa: taglio secco
        }
        return tinyAdvance(t) > max ? "" : t;
    }

    /** Testo piccolo centrato su cx (copia di ProfileLists.barText, con font e larghezza massima a scelta). */
    private void smallText(GuiTitle g, String raw, String font, int cx, int max) {
        StringBuilder safe = new StringBuilder();
        for (char ch : (raw == null ? "" : raw).toCharArray()) {
            safe.append(tinyChar(ch) ? ch : ' ');
        }
        String text = fit(safe.toString().replaceAll(" +", " ").trim(), max);
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

    /** Numero pagina dei popup lista/cartella (glifi NAV del font default, centro 96, quota della barra del popup). */
    private void navText(GuiTitle g, int page, int pages) {
        navText(g, page, pages, "", L_NAV, L_NAV_SH, L_NAV_CX, 0);
    }

    /**
     * Numero pagina "n/tot" centrato su cx (cifre e "/" da 6 px, base+0..9 cifre, base+10 "/"), sempre disegnato.
     * dx = 0 nel titolo normale (convenzione IMG = -22), 7 dentro uno sfondo con cursore reale.
     */
    private void navText(GuiTitle g, int page, int pages, String font, char base, char shadow, int cx, int dx) {
        String plain = Math.max(1, page) + "/" + Math.max(1, pages);
        if (plain.length() * 6 > L_NAV_MAX) {
            plain = String.valueOf(page);   // non sta tra le frecce: solo il numero di pagina
        }
        StringBuilder n = new StringBuilder();
        StringBuilder sh = new StringBuilder();
        for (char ch : plain.toCharArray()) {
            int o = ch == '/' ? 10 : ch - '0';
            n.append((char) (base + o));
            sh.append((char) (shadow + o));
        }
        int adv = plain.length() * 6;
        int start = cx - (adv - 1) / 2;
        g.at(start + 1 + L_IMG + dx, GuiTitle.shadowCode(plugin) + sh, font, adv);
        g.at(start + L_IMG + dx, "&f" + n, font, adv);
    }

    // ------------------------------------------------------------------
    //  Item (nomi e lore passano da LoreWrap: righe corte, colori conservati)
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

    /** Pane invisibile (bottoni disegnati nell'immagine). */
    private ItemStack pane(String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        decorate(meta, name, lore);
        stack.setItemMeta(meta);
        return stack;
    }

    /** Item con l'icona custom del pack (assets/blockmania/items/<name>.json, Sara). */
    private ItemStack icon(String model, String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey("blockmania", model));
        decorate(meta, name, lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private void decorate(ItemMeta meta, String name, List<String> lore) {
        meta.displayName(plugin.text(LoreWrap.name(name)));
        if (lore != null && !lore.isEmpty()) {
            List<Component> out = new ArrayList<>();
            for (String raw : LoreWrap.wrap(lore)) {
                out.add(raw == null || raw.isBlank() ? plugin.color(" ") : plugin.text(raw));
            }
            meta.lore(out);
        }
    }

    private static <T> List<T> safe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace('&', ' ').replace('§', ' ').trim();
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
        ClickType click = event.getClick();
        Runnable action;
        if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
            action = h.dropActions.get(slot);   // Q: solo dove previsto (elimina task)
        } else if (click.isRightClick() && h.rightActions.containsKey(slot)) {
            action = h.rightActions.get(slot);
        } else if (click.isLeftClick() || click.isRightClick()) {
            action = h.actions.get(slot);
        } else {
            action = null;   // tasti numerici, doppio click, centrale...: niente
        }
        if (action == null || h.dead) {
            return;
        }
        h.dead = true;
        plugin.guiClick(p);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                action.run();
            }
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

    /**
     * GUI task chiusa davvero (Esc, closeInventory): i filtri avanzati spariscono. Aprire un popup o un'altra schermata
     * (OPEN_NEW, compresa l'incudine) non li toglie.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder
                && event.getReason() != InventoryCloseEvent.Reason.OPEN_NEW) {
            advFilters.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drafts.remove(event.getPlayer().getUniqueId());
        advFilters.remove(event.getPlayer().getUniqueId());
    }
}
