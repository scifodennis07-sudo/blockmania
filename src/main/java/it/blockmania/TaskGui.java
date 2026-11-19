package it.blockmania;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
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
 * [testo][prendi] per l'ordine semplice / custom, [testo][ramo 1]..[ramo N][non fare nulla] per il condizionale; se non
 * entra in 7 slot continua sulla riga sotto. Cliccando "prendi" o un ramo lo staffer PRENDE IN CARICO l'ordine
 * (StaffTasks.take: bossbar con timer 5 minuti, le azioni le fa a mano con userprofile / adminmania).
 * Tutti gli item usano le icone del pack (blockmania:task_*, ItemMeta.setItemModel); nomi e lore passano da LoreWrap.
 * Popup: si chiudono solo con conferma/annulla (gli altri click non fanno niente). Q sull'item di una task nella lista =
 * elimina (solo creatore o admin+). Sfondo dei popup = schermata task scurita; il popup "continuare in chat?" ha come
 * sfondo l'incudine (font task_anvil del pack).
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

    // ---------- popup scelta con frecce (userprofile-30, U+E11D: barra x62..127 y51..62) ----------
    private static final String CH_IMAGE = "";
    private static final String CH_FONT = "minecraft:sp_popup";
    private static final int CH_ADV = 162;     // reale 169 - 7 (come RoleGui.ADV)
    private static final int[] CH_PREV = {10, 11};
    private static final int[] CH_BAR = {12, 13, 14};
    private static final int[] CH_NEXT = {15, 16};
    private static final int[] CH_YES = {28, 29, 30};
    private static final int[] CH_NO = {32, 33, 34};
    private static final String CH_BAR_FONT = "minecraft:tiny2";
    private static final int CH_BAR_CX = 95;
    private static final int CH_BAR_MAX = 58;

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
    private static final int GRID_COLS = 7;
    private static final int GRID_ROWS = 3;
    private static final int[] L_X = {1, 2};
    private static final int[] L_BTN = {6, 7};
    private static final int L_PREV = 38;
    private static final int L_NEXT = 42;
    /** Slot liberi della cartella ai lati delle frecce pagina: nell'anteprima motivazione (37) e riscrivi (43). */
    private static final int F_LEFT = 37;
    private static final int F_RIGHT = 43;

    // ---------- sfondo incudine del popup "continuare in chat?" (font task_anvil del pack) ----------
    /**
     * Stessa immagine dell'incudine custom (custom-gui/anvil/anvil_vanilla.png) con ascent 0 invece di 28: la chest da 6
     * righe (222) a schermo parte 28 px piu' in alto dell'incudine (166). Senza il pack nuovo il glifo manca: il quadratino
     * avanza di pochi px invece di 186 e tutto il resto (pannello) finisce spostato a sinistra.
     */
    private static final String ANVIL_IMAGE = "";
    private static final String ANVIL_FONT = "minecraft:task_anvil";
    private static final int ANVIL_REAL = 186;
    private static final int ANVIL_BAR_CX = 95;
    private static final int ANVIL_BAR_MAX = 118;

    // ---------- barre delle righe unite nella cartella (font task_row, Sara) ----------
    private static final String ROW_FONT = "minecraft:task_row";
    private static final char ROW_BASE = '\ue200';
    private static final int ROW_L = 0;
    private static final int ROW_M = 1;
    private static final int ROW_R = 2;

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ROME);

    // ---------- tipi interni ----------

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        boolean dead;   // gia' cliccata: il secondo click nello stesso tick non ripete l'azione
        final Map<Integer, Runnable> actions = new HashMap<>();
        final Map<Integer, Runnable> rightActions = new HashMap<>();   // click destro (se manca vale actions)
        final Map<Integer, Runnable> dropActions = new HashMap<>();    // tasto Q: solo dove previsto

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

    /** Pezzo di barra di una riga della cartella: riga 0..2, colonna di partenza 0..6, quante celle, continua da/su altre righe. */
    private record Span(int row, int col, int len, boolean fromPrev, boolean toNext) {
    }

    /** Una pagina di cartella: celle per slot e barre da disegnare dietro. */
    private record FolderPage(Map<Integer, Row> cells, List<Span> spans) {
    }

    /** Task scritta (CREA), in memoria finche' non si salva o si annulla. */
    private static final class Draft {
        String text = "";
        String reason = "";
        final List<TaskParser.ParsedOrder> orders = new ArrayList<>();
    }

    private record ChatWait(Consumer<String> onText, Runnable onCancel) {
    }

    private final BlockMania plugin;
    private final Map<UUID, Draft> drafts = new HashMap<>();
    private final Map<UUID, ChatWait> chatting = new java.util.concurrent.ConcurrentHashMap<>();

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
        put(h, S_PREV, pane("&8pagina precedente", List.of("&7C'e' una sola pagina.")), null);
        put(h, S_NEXT, pane("&8pagina successiva", List.of("&7C'e' una sola pagina.")), null);
        List<String> barLore = new ArrayList<>();
        barLore.add("&7task aperte: &f" + open.size());
        barLore.add("&7ordini aperti: &f" + openOrders);
        barLore.add("&7ordini che puoi prendere: &f" + free);
        barLore.add("");
        if (ch != null) {
            StaffTasks.Action next = t.nextAction(ch);
            barLore.add("&ein carico a te: &ftask #" + ch.taskId());
            barLore.add("&7prossima azione: &f" + (next == null ? "segna come fatto" : StaffTasks.describe(next)));
            barLore.add("&7tempo rimasto: &f" + mmss(StaffTasks.remainingMs(ch)));
            barLore.add("");
            barLore.add("&eclicca per aprire la task");
        } else {
            barLore.add("&7Nessun incarico: prendi un ordine dalla lista.");
        }
        put(h, S_BAR, pane(ch != null ? "&eil tuo incarico" : "&ftask staff", barLore), ch == null ? null : () -> {
            viewTask(p, ch.taskId(), 0, () -> {
                open(p);
            });
        });
        put(h, S_INFO, pane("&6info", List.of(
                "&7Una task si crea scrivendo una frase, es.",
                "&f\"dai 20 palle standard a mario e toglile a luigi\"",
                "&7Anche con condizioni: &f\"se ha la miniera 5 toglila, se invece non ce l'ha non fare niente\"",
                "&7Serve sempre la motivazione.",
                "&7Nella cartella clicca &fprendi&7 o il &framo&7 giusto per prendere in carico l'ordine:",
                "&7hai 5 minuti (bossbar) per fare le azioni a mano con lo userprofile o /adminmania.",
                "&7Un solo incarico alla volta.",
                "&7Q su una task nella lista = elimina (chi l'ha creata o gli admin).",
                "",
                "&eclicca per ricevere l'aiuto in chat")), () -> {
            help(p);
        });
        put(h, S_LIST, pane("&elista task", List.of(
                "&7task aperte: &f" + open.size(),
                "&7create da te: &f" + mine,
                "&7ordini che puoi prendere: &f" + free,
                "",
                "&eclicca per aprire")), () -> {
            openList(p, 0, 0);
        });
        Draft d = drafts.get(me);
        if (!create) {
            put(h, S_CREATE, pane("&8crea task", List.of("&7Le task le creano gli helper in su.", "", "&cnon puoi creare task")), null);
        } else if (d != null) {
            put(h, S_CREATE, pane("&acrea task", List.of(
                    "&7Anteprima aperta: &f" + d.orders.size() + " ordini",
                    "",
                    "&eclick sinistro: riprendi l'anteprima",
                    "&eclick destro: scrivi una nuova task (l'anteprima si perde)")), () -> {
                openPreview(p, 0);
            });
            putRight(h, S_CREATE, () -> {
                drafts.remove(me);
                startCreate(p);
            });
        } else {
            put(h, S_CREATE, pane("&acrea task", List.of(
                    "&7Scrivi cosa fare in una frase, es.",
                    "&f\"dai 20 palle standard a mario\"",
                    "&7Poi la motivazione e l'anteprima.",
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
                openTrash(p, 0);
            });
        } else {
            put(h, S_TRASH, pane("&8cestino", List.of("&csolo admin")), null);
        }
        List<StaffTasks.LogEntry> log = safe(t.log(admin ? null : me));
        put(h, S_LOG, pane("&bresoconto task", List.of(
                "&7voci nel registro: &f" + log.size(),
                admin ? "&7Vedi tutto il registro." : "&7Vedi solo le voci delle tue task.",
                "",
                "&eclicca per aprire")), () -> {
            openLog(p, 0, 0);
        });
        p.openInventory(h.inventory);
    }

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
        GuiMsg.send(plugin, p, "&7crea: scrivi una frase (anche con \"se ... se invece ...\"), poi la motivazione, controlla l'anteprima e salva.");
        GuiMsg.send(plugin, p, "&7cartella: clicca prendi (o il ramo giusto) per prendere in carico l'ordine: 5 minuti, bossbar con cosa fare.");
        GuiMsg.send(plugin, p, "&7le azioni le fai a mano (userprofile, /adminmania): il plugin le spunta da solo.");
        GuiMsg.send(plugin, p, "&7Q su una task nella lista = elimina (chi l'ha creata o gli admin). cestino (admin): ripristina.");
    }

    // ------------------------------------------------------------------
    //  CREA: frase -> motivazione -> anteprima -> salva
    // ------------------------------------------------------------------

    private void startCreate(Player p) {
        if (!guard(p)) {
            return;
        }
        if (!tasks().canCreate(p)) {
            deny(p, "non puoi creare task (servono helper o un grado piu' alto).");
            return;
        }
        TaskParser tp = parser();
        if (tp != null) {
            try {
                tp.reloadWords();   // le parole aggiunte a task-words.yml valgono subito
            } catch (RuntimeException ex) {
                plugin.getLogger().warning("TaskGui: task-words.yml non valido: " + ex);
            }
        }
        askSentence(p, "");
    }

    private void askSentence(Player p, String initial) {
        if (parser() == null) {
            deny(p, "il lettore delle frasi non e' disponibile.");
            open(p);
            return;
        }
        String init = initial == null || initial.length() > 50 ? "" : initial;
        GuiMsg.send(plugin, p, "&7scrivi la task, es. &f\"dai 20 palle standard a mario e toglile a luigi\"&7. frase lunga? continua in chat.");
        plugin.getProfileUi().askText(p, "scrivi la task (frase lunga: continua in chat)", "SCRIVI LA TASK", init, text -> {
            handleSentence(p, text);
        }, () -> {
            cancelSentence(p);
        }, full -> {
            askChat(p, full, "la task", t -> {
                handleSentence(p, t);
            }, () -> {
                askSentence(p, full);
            }, () -> {
                cancelSentence(p);
            });
        });
    }

    private void cancelSentence(Player p) {
        if (drafts.containsKey(p.getUniqueId())) {
            openPreview(p, 0);
        } else {
            open(p);
        }
    }

    /**
     * Limite dell'incudine raggiunto: conferma (sfondo = incudine) per continuare in chat; annulla = di nuovo l'incudine
     * col testo (backToAnvil). In chat: il testo finito va a onText, "annulla" a onCancel.
     */
    private void askChat(Player p, String written, String what, Consumer<String> onText, Runnable backToAnvil, Runnable onCancel) {
        plugin.getProfileUi().confirmOver(p, "L'incudine e' piena (50 caratteri). Vuoi continuare a scrivere " + what + " in chat?", g -> {
            g.at(-15, "&f" + ANVIL_IMAGE, ANVIL_FONT, ANVIL_REAL);
        }, () -> {
            p.closeInventory();
            chatting.put(p.getUniqueId(), new ChatWait(onText, onCancel));
            Component msg = plugin.color("&6[task] &e&nclicca qui per riprendere il testo&7, poi continua a scrivere e premi invio; scrivi &fannulla&7 per uscire.")
                    .clickEvent(ClickEvent.suggestCommand(written))
                    .hoverEvent(HoverEvent.showText(plugin.color("&7rimette nella chat il testo scritto")));
            p.sendMessage(msg);
        }, backToAnvil, g -> {
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
            text = text.substring(1).trim();
        }
        smallText(g, text, ANVIL_FONT, ANVIL_BAR_CX, ANVIL_BAR_MAX);
    }

    /** Frase completa (dall'incudine o dalla chat) -> TaskParser -> motivazione. */
    private void handleSentence(Player p, String text) {
        if (!guard(p)) {
            return;
        }
        if (text == null || text.isBlank()) {
            deny(p, "scrivi cosa deve fare la task.");
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
        Draft old = drafts.get(p.getUniqueId());
        Draft d = new Draft();
        d.text = text.trim();
        d.reason = old == null ? "" : old.reason;
        d.orders.addAll(parsed);
        drafts.put(p.getUniqueId(), d);
        if (d.reason.isBlank()) {
            askMotivation(p, "");
        } else {
            openPreview(p, 0);   // riscrivi: la motivazione di prima resta (si cambia dall'anteprima)
        }
    }

    /** Motivazione della task (obbligatoria): incudine, lunga -> stesso passaggio alla chat. Annulla = anteprima. */
    private void askMotivation(Player p, String initial) {
        String init = initial == null || initial.length() > 50 ? "" : initial;
        GuiMsg.send(plugin, p, "&7scrivi la motivazione della task (perche' va fatta). lunga? continua in chat.");
        plugin.getProfileUi().askText(p, "motivazione della task", "MOTIVAZIONE", init, text -> {
            handleMotivation(p, text);
        }, () -> {
            openPreview(p, 0);
        }, full -> {
            askChat(p, full, "la motivazione", t -> {
                handleMotivation(p, t);
            }, () -> {
                askMotivation(p, full);
            }, () -> {
                openPreview(p, 0);
            });
        });
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
            // cella testo: destro = togli l'ordine dall'anteprima
            List<String> tl = new ArrayList<>();
            tl.add("&7tipo: &f" + typeLabel(o.type()));
            tl.add("&7frase: &f" + clean(o.text()));
            String why = u ? null : invalidReason(p, o);
            if (u) {
                tl.add("&eda chiarire: clicca le parti gialle");
            } else if (why != null) {
                tl.add("&c" + why);
            }
            tl.add("");
            tl.add("&eclick destro: togli dall'anteprima");
            String color = u ? "&e" : o.type() == StaffTasks.OrderType.CUSTOM ? "&d" : "&a";
            cells.add(new Row(icon(u ? "task_dubbio" : o.type() == StaffTasks.OrderType.CUSTOM ? "task_custom" : "task_testo",
                    color + "ordine " + (oi + 1), tl), null, () -> {
                Draft cur = drafts.get(p.getUniqueId());
                if (cur != null && orderIdx < cur.orders.size()) {
                    cur.orders.remove(orderIdx);
                }
                openPreview(p, page);
            }));
            List<TaskParser.ParsedBranch> branches = safe(o.branches());
            if (o.type() == StaffTasks.OrderType.CUSTOM) {
                cells.add(new Row(icon("task_custom", "&dda verificare a mano", List.of(
                        "&7Nessuna azione automatica:", "&7chi la prende in carico controlla", "&7e la segna come fatta.")), null, null));
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
        btnLore.add("&7motivazione: &f" + (noReason ? "&cmanca" : clean(d.reason)));
        btnLore.add("");
        btnLore.add(d.orders.isEmpty() || unsure > 0 || noReason ? "&8click sinistro: salva (prima completa)" : "&aclick sinistro: salva la task");
        btnLore.add("&eclick destro: riscrivi la frase");
        int unsureCount = unsure;
        Runnable save = () -> {
            if (d.orders.isEmpty()) {
                deny(p, "non ci sono ordini da salvare: riscrivi la frase.");
            } else if (unsureCount > 0) {
                deny(p, "ci sono " + unsureCount + " ordini da chiarire (gialli): clicca e scegli cosa intendevi.");
            } else if (d.reason == null || d.reason.isBlank()) {
                deny(p, "manca la motivazione.");
                askMotivation(p, "");
            } else {
                confirmSave(p);
            }
        };
        Runnable rewrite = () -> {
            if (d.text.length() >= 50) {
                askChat(p, d.text, "la task", t -> {
                    handleSentence(p, t);
                }, () -> {
                    openPreview(p, 0);
                }, () -> {
                    openPreview(p, 0);
                });
            } else {
                askSentence(p, d.text);
            }
        };
        Map<Integer, Row> extra = new HashMap<>();
        extra.put(F_LEFT, new Row(icon("task_motivazione", "&fmotivazione", List.of(
                "&7" + (noReason ? "&cmanca" : clean(d.reason)), "", "&eclicca per cambiarla")), () -> {
            askMotivation(p, d.reason);
        }, null));
        extra.put(F_RIGHT, new Row(icon("task_riscrivi", "&friscrivi", List.of(
                "&7Riapre la scrittura con la frase di prima.", "", "&eclicca per riscrivere")), rewrite, null));
        folder(p, "anteprima task", "anteprima", fp, pg, pages.size(), pgn -> {
            openPreview(p, pgn);
        }, () -> {
            drafts.remove(p.getUniqueId());
            GuiMsg.send(plugin, p, "&7task annullata.");
            open(p);
        }, "&cannulla", List.of("&7Butta l'anteprima e torna alla schermata task."),
                pane("&asalva task", btnLore), save, rewrite, extra);
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
                    b.condition() == null ? "&7Se nessun caso vale, non si fa niente." : "&7se " + clean(b.condition()) + ": non fare niente")), null, null);
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
            lore.add("&eclicca: intendevi...?");
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
                    "&7scritto: &8" + clean(pa.sourceText()))));
        }
        choose(p, "intendevi", opts, 0, key -> {
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
        plugin.getProfileUi().confirmOver(p, "Salvare la task con " + orders.size() + " ordini?", bg(), () -> {
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
        int fi = Math.max(0, Math.min(filterIdx, LIST_FILTERS.size() - 1));
        StaffTasks t = tasks();
        UUID me = p.getUniqueId();
        String key = LIST_FILTERS.get(fi).key();
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
            StaffTasks.Counts c = StaffTasks.counts(k);
            boolean del = t.canDelete(p, k);
            List<String> lore = folderLore(k, c);
            lore.add("&7ordini che puoi prendere: &f" + can);
            lore.add("");
            lore.add("&eclicca per aprire la cartella");
            if (del) {
                lore.add("&cQ: elimina la task");
            }
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
        listPanel(p, "lista task", LIST_FILTERS, fi, page, rows, (f, pg) -> {
            openList(p, f, pg);
        }, () -> {
            open(p);
        });
    }

    private String folderName(StaffTasks.Task k) {
        return "&ftask &e#" + k.id() + " &7- &f" + date(k.createdAt());
    }

    /** Lore della cartella: id, data, motivazione, conteggi (mai nomi di player). */
    private List<String> folderLore(StaffTasks.Task k, StaffTasks.Counts c) {
        List<String> lore = new ArrayList<>();
        lore.add("&7id: &f#" + k.id());
        lore.add("&7creata il: &f" + date(k.createdAt()));
        if (k.reason() != null && !k.reason().isBlank()) {
            lore.add("&7motivazione: &f" + clean(k.reason()));
        }
        lore.add("&7ordini: &f" + c.total() + " &8(&f" + c.simple() + "&8 semplici, &f" + c.conditional() + "&8 con condizioni, &d"
                + c.custom() + "&8 custom)");
        lore.add("&7completati: &a" + c.done() + "&8/&f" + c.total());
        return lore;
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
        boolean del = k.status() != StaffTasks.Status.ELIMINATA && t.canDelete(p, k);
        List<String> btnLore = folderLore(k, StaffTasks.counts(k));
        btnLore.add("&7creata da: &f" + clean(k.createdByName()));
        if (del) {
            btnLore.add("");
            btnLore.add("&cclick destro o Q: elimina la task");
        }
        Runnable delete = del ? () -> {
            confirmDelete(p, taskId, back, self);
        } : null;
        Holder h = folder(p, "task " + k.id(), "task " + k.id(), pages.get(pg), pg, pages.size(), pgn -> {
            viewTask(p, taskId, pgn, back);
        }, back, "&cindietro", List.of(), pane("&fcartella &e#" + k.id(), btnLore), null, delete, Map.of());
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
            tl.add("&eclicca quando hai finito: segna come fatto");
            textClick = () -> {
                finishCustom(p, self);
            };
        }
        String textIcon = done ? "task_fatto" : type == StaffTasks.OrderType.CUSTOM ? "task_custom" : "task_testo";
        cells.add(new Row(icon(textIcon, (done ? "&a" : "&f") + "ordine " + (oi + 1), tl), textClick, null));

        List<StaffTasks.Branch> branches = safe(o.branches());
        if (type != StaffTasks.OrderType.CONDIZIONALE) {
            // [prendi]: semplice (azioni del ramo 0) o custom (fatto a mano)
            List<String> lore = new ArrayList<>();
            if (!branches.isEmpty()) {
                addActions(lore, branches.get(0));
            }
            if (type == StaffTasks.OrderType.CUSTOM) {
                lore.add("&7Da verificare a mano, poi segnare come fatto.");
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
                lore.add("");
                lore.add("&ain carico a te: mancano " + mmss(StaffTasks.remainingMs(charge)));
                lore.add("&eclicca quando hai finito");
                click = () -> {
                    finishCustom(p, self);
                };
            } else if (charge != null) {
                iconName = "task_in_carico";
                name = mine ? "&ain carico a te" : "&6in carico";
                lore.add("");
                lore.add((mine ? "&ain carico a te" : "&7in carico a &f" + staffName(charge.staff())) + "&7: mancano &f" + mmss(StaffTasks.remainingMs(charge)));
            } else {
                iconName = "task_prendi";
                name = "&eprendi in carico";
                lore.add("");
                lore.add(takeWhy == null && !deleted ? "&eclicca per prenderlo in carico (5 minuti)" : "&8non disponibile");
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
                lore.add("&7Non fare niente: completa l'ordine.");
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
                lore.add("");
                lore.add((mine ? "&ain carico a te" : "&7in carico a &f" + staffName(charge.staff())) + "&7: mancano &f" + mmss(StaffTasks.remainingMs(charge)));
            } else if (charge == null && takeWhy == null && !deleted) {
                lore.add("");
                lore.add(b.nothing() ? "&eclicca se non va fatto niente" : "&eclicca se vale questo caso: lo prendi in carico (5 minuti)");
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

    private void confirmTake(Player p, StaffTasks.Task k, StaffTasks.Order o, int branch, String what, Runnable self) {
        plugin.getProfileUi().confirmOver(p, "Prendere in carico: " + clean(what) + "? Hai 5 minuti.", bg(), () -> {
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
        plugin.getProfileUi().confirmOver(p, "Segnare come fatto l'ordine che hai in carico?", bg(), () -> {
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
            List<String> lore = folderLore(k, StaffTasks.counts(k));
            lore.add("");
            lore.add("&eclick sinistro: ripristina");
            lore.add("&eclick destro: apri la cartella");
            String id = k.id();
            Runnable back = () -> {
                openTrash(p, page);
            };
            rows.add(new Row(icon("task_elimina", "&ctask #" + k.id() + " &7- &f" + date(k.createdAt()), lore), () -> {
                plugin.getProfileUi().confirmOver(p, "Ripristinare la task #" + id + "?", bg(), () -> {
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
        int fi = Math.max(0, Math.min(filterIdx, LOG_FILTERS.size() - 1));
        String key = LOG_FILTERS.get(fi).key();
        StaffTasks t = tasks();
        List<Row> rows = new ArrayList<>();
        for (StaffTasks.LogEntry e : safe(t.log(t.isAdmin(p) ? null : p.getUniqueId()))) {
            String ev = e.event() == null ? "" : e.event().toUpperCase(Locale.ROOT);
            if (!key.isEmpty() && !key.equals(ev)) {
                continue;
            }
            String iconName;
            String color;
            switch (ev) {
                case "CREATA" -> { iconName = "task_cartella"; color = "&e"; }
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
        listPanel(p, "resoconto task", LOG_FILTERS, fi, page, rows, (f, pg) -> {
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
     * Mette i blocchi (celle di un ordine) nella griglia 7x3: ogni ordine parte da una riga nuova, se e' piu' lungo di 7 celle
     * continua sulla riga sotto; se non entra nelle righe rimaste della pagina passa alla pagina dopo.
     */
    private static List<FolderPage> layout(List<List<Row>> blocks) {
        List<FolderPage> pages = new ArrayList<>();
        Map<Integer, Row> cells = new HashMap<>();
        List<Span> spans = new ArrayList<>();
        int row = 0;
        for (List<Row> b : blocks) {
            int need = Math.max(1, (b.size() + GRID_COLS - 1) / GRID_COLS);
            if (row > 0 && row + Math.min(need, GRID_ROWS) > GRID_ROWS) {
                pages.add(new FolderPage(cells, spans));
                cells = new HashMap<>();
                spans = new ArrayList<>();
                row = 0;
            }
            int i = 0;
            while (i < b.size()) {
                if (row >= GRID_ROWS) {   // ordine piu' lungo di una pagina intera: continua sulla pagina dopo
                    pages.add(new FolderPage(cells, spans));
                    cells = new HashMap<>();
                    spans = new ArrayList<>();
                    row = 0;
                }
                int len = Math.min(GRID_COLS, b.size() - i);
                for (int c = 0; c < len; c++) {
                    cells.put(10 + row * 9 + c, b.get(i + c));
                }
                spans.add(new Span(row, 0, len, i > 0, i + len < b.size()));
                i += len;
                row++;
            }
        }
        pages.add(new FolderPage(cells, spans));
        return pages;
    }

    /**
     * Popup cartella (U+E11E): barre unite dietro le celle (font task_row), celle, X, pulsante verde (sinistro/destro),
     * frecce pagina e celle extra (slot 37/43). Ritorna l'holder (per aggiungere azioni Q).
     */
    private Holder folder(Player p, String title, String barText, FolderPage fp, int pg, int pages, IntConsumer reopenPage,
                          Runnable onBack, String backName, List<String> backLore, ItemStack button, Runnable btnLeft,
                          Runnable btnRight, Map<Integer, Row> extra) {
        Holder h = new Holder();
        GuiTitle g = new GuiTitle(plugin);
        UserProfilePowers.drawDimmed(plugin, g, bg());
        g.at(-15, "&f" + F_IMAGE, F_FONT, F_ADV);
        for (Span s : fp.spans()) {
            drawSpan(g, s);
        }
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
        Map<Integer, Row> all = new HashMap<>(fp.cells());
        all.putAll(extra);
        if (fp.cells().isEmpty()) {
            h.inventory.setItem(gridSlot(10), icon("task_cartella_vuota", "&7nessun ordine", List.of("&8La cartella e' vuota.")));
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
     * Barra di una riga unita dietro le celle (font task_row, TASK_GLYPHS.md di Sara): carattere U+E200 + riga*21 + col*3 + pezzo
     * (l=0 inizio, m=1 centro/continua, r=2 fine), avanzamento reale 51 + 18*col. Disegnati come le overlay a pagina intera:
     * a -22 con l'avanzamento REALE (come StaffProfileGui.overlay), cosi' il cursore resta coerente per i testi dopo.
     */
    private void drawSpan(GuiTitle g, Span s) {
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
            g.at(L_IMG, "&f" + ch, ROW_FONT, 51 + 18 * col);
        }
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
    //  Liste (copia di ProfileLists, con il click sulle voci)
    // ------------------------------------------------------------------

    private interface Reopen {
        void open(int filterIdx, int page);
    }

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
        int pages = Math.max(1, (rows.size() + L_PER_PAGE - 1) / L_PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Holder h = new Holder();
        GuiTitle g = new GuiTitle(plugin);
        UserProfilePowers.drawDimmed(plugin, g, bg());
        g.at(-15, "&f" + L_IMAGE, L_FONT, L_ADV);
        smallText(g, filters.get(fi).label(), L_BAR_FONT, L_BAR_CX, L_BAR_MAX);
        navText(g, pg + 1, pages);
        UserProfilePowers.topTitle(plugin, g, title);
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());

        put(h, L_X, pane("&cindietro", List.of()), onBack);
        put(h, L_BTN, pane("&bfiltra per", fl), cycle);
        put(h, new int[]{L_PREV}, pane("&7pagina precedente", List.of("&7Pagina &f" + (pg + 1) + "&8/&f" + pages)), pg > 0 ? () -> {
            reopen.open(fi, pg - 1);
        } : null);
        put(h, new int[]{L_NEXT}, pane("&7pagina successiva", List.of("&7Pagina &f" + (pg + 1) + "&8/&f" + pages)), pg < pages - 1 ? () -> {
            reopen.open(fi, pg + 1);
        } : null);
        if (rows.isEmpty()) {
            h.inventory.setItem(gridSlot(10), icon("task_cartella_vuota", "&7nessuna voce", List.of("&8La lista e' vuota.")));
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

    private static int gridSlot(int i) {
        return 10 + (i / 7) * 9 + (i % 7);
    }

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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        drafts.remove(event.getPlayer().getUniqueId());
        chatting.remove(event.getPlayer().getUniqueId());
    }

    /** Testo finito in chat dopo il limite dell'incudine (frase o motivazione); "annulla" esce. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player p = event.getPlayer();
        ChatWait wait = chatting.remove(p.getUniqueId());
        if (wait == null) {
            return;
        }
        event.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!p.isOnline()) {
                return;
            }
            if (msg.equalsIgnoreCase("annulla")) {
                GuiMsg.send(plugin, p, "&7scrittura annullata.");
                wait.onCancel().run();
                return;
            }
            wait.onText().accept(msg);
        });
    }
}
