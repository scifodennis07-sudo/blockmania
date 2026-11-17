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
import java.util.function.Function;

/**
 * GUI delle task staff (/adminmania task). Schermata principale = immagine task del pack (U+E180, font minecraft:task)
 * disegnata come lo sfondo di StaffProfileGui, con slot invisibili sopra i bottoni (LISTA, CREA, CESTINO, RESOCONTO TASK,
 * X, info, frecce). Tutti i popup hanno come sfondo la schermata task scurita (UserProfilePowers.drawDimmed / confirmOver):
 * - scelta con frecce (tipo, risorsa, verbo, azione su una task): immagine del popup ruoli (U+E11A) come RoleGui,
 *   con l'opzione scelta scritta tra le frecce (testo piccolo tiny2, come la barra di ProfileLists ma una riga piu' in basso);
 * - liste (aperte, cestino, resoconto): copia di ProfileLists (pannello U+E119, filtro nella barra) con il click sulle voci;
 * - player / quantita' / testo / motivo: UserProfilePowers.askText / askNumber; conferme: confirmOver.
 * I dati e i permessi sono in StaffTasks (Giovanni).
 * <p>
 * Slot della schermata principale (misurati su task_final.png: item dello slot (c,r) a (15+18c, 33+18r), raw = r*9+c,
 * ogni bottone copre tutti gli slot che tocca; vedi anche TASK_GLYPHS.md di Sara).
 */
public final class TaskGui implements Listener {

    // ---------- pack ----------
    private static final String FONT = "minecraft:task";
    private static final String IMAGE = "\ue180";
    private static final int SIZE = 54;
    private static final int IMG_ADV = 179;    // avanzamento dichiarato nel titolo normale (reale 186 - 7)
    private static final int IMG_REAL = 186;   // avanzamento reale (sfondo dei popup): ultima colonna opaca 184 + 2

    // ---------- slot della schermata principale ----------
    private static final int[] S_CLOSE = {0, 1};                                   // X 20-42 x 30-43
    private static final int[] S_PREV = {2};                                       // freccia sinistra 49-70
    private static final int[] S_BAR = {3, 4, 5};                                  // barra tra le frecce 71-118
    private static final int[] S_NEXT = {6};                                       // freccia destra 119-140
    private static final int[] S_INFO = {7, 8};                                    // info 147-169
    private static final int[] S_LIST = {9, 10, 11, 18, 19, 20, 27, 28, 29, 36};   // LISTA 18-63 x 50-106
    private static final int[] S_CREATE = {12, 13, 14, 21, 22, 23, 30, 31, 32};    // CREA 72-117 x 50-106
    private static final int[] S_TRASH = {15, 16, 17, 24, 25, 26, 33, 34, 35, 44}; // CESTINO 126-171 x 50-106
    private static final int[] S_LOG = {37, 38, 39, 40, 41, 42, 43, 46, 47, 48, 49, 50, 51, 52};   // RESOCONTO 44-147 x 115-137

    // ---------- popup scelta con frecce (immagine di RoleGui) ----------
    private static final String CH_IMAGE = "\ue11c";   // userprofile-29: popup frecce con "SELEZIONA E CONFERMA!"
    private static final String CH_FONT = "minecraft:sp_popup";
    private static final int CH_ADV = 162;     // come RoleGui.ADV (reale 169 - 7)
    private static final int[] CH_PREV = {10, 11};
    private static final int[] CH_BAR = {12, 13, 14};
    private static final int[] CH_NEXT = {15, 16};
    private static final int[] CH_YES = {28, 29, 30};
    private static final int[] CH_NO = {32, 33, 34};
    /** Testo dell'opzione tra le frecce: set F500/F600 nel font tiny2 (= tiny3 18 px piu' in basso, riga delle frecce). */
    private static final String CH_BAR_FONT = "minecraft:tiny2";
    private static final int CH_BAR_CX = 95;   // centro tra le frecce (x immagine 62..127)
    private static final int CH_BAR_MAX = 62;

    // ---------- popup lista (copia di ProfileLists) ----------
    private static final String L_IMAGE = "\ue119";
    private static final String L_FONT = "minecraft:userprofile3";
    private static final int L_IMG = -22;
    private static final int L_ADV = 162;
    private static final int L_NAV_CX = 96;
    private static final char L_NAV = '\ue4a0';
    private static final char L_NAV_SH = '\ue4b0';
    private static final char BAR_BASE = '\uf500';
    private static final char BAR_SHADOW = '\uf600';
    private static final String L_BAR_FONT = "minecraft:tiny3";
    private static final int L_BAR_CX = 95;
    private static final int L_BAR_MAX = 70;
    private static final int L_PER_PAGE = 21;
    private static final int[] L_X = {1, 2};
    private static final int[] L_FILTER = {6, 7};
    private static final int L_PREV = 38;
    private static final int L_NEXT = 42;

    private static final long MAX_AMOUNT = 1_000_000_000L;

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ROME);

    // ---------- tipi interni ----------

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        boolean dead;   // gia' cliccata: il secondo click nello stesso tick non ripete l'azione
        final Map<Integer, Runnable> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Opzione del popup con frecce. */
    private record Opt(String key, String label, List<String> lore) {
    }

    /** Voce di una lista: item (nome e lore) e azione al click (null = niente). */
    private record Row(ItemStack item, Runnable onClick) {
    }

    private record Filter(String label, String key) {
    }

    /** Task in costruzione (popup di CREA). */
    private static final class Draft {
        StaffTasks.Type type = StaffTasks.Type.ORDINARIA;
        UUID target;
        String targetName = "";
        String what;
        String verb;
        long amount;
        String text = "";
    }

    private final BlockMania plugin;

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
        int ord = 0;
        int cus = 0;
        int mine = 0;
        int canDo = 0;
        for (StaffTasks.Task k : open) {
            if (k.type() == StaffTasks.Type.CUSTOM) {
                cus++;
            } else {
                ord++;
            }
            if (me.equals(k.createdBy())) {
                mine++;
            }
            if (t.canExecute(p, k)) {
                canDo++;
            }
        }
        int doneToday = 0;
        int rejectedToday = 0;
        LocalDate today = LocalDate.now(ROME);
        for (StaffTasks.Task k : safe(t.list(null))) {
            if (k.doneAt() > 0 && Instant.ofEpochMilli(k.doneAt()).atZone(ROME).toLocalDate().equals(today)) {
                if (k.status() == StaffTasks.Status.ESEGUITA) {
                    doneToday++;
                } else if (k.status() == StaffTasks.Status.RIFIUTATA) {
                    rejectedToday++;
                }
            }
        }
        boolean admin = t.isAdmin(p);
        boolean create = t.canCreate(p);

        put(h, S_CLOSE, pane("&cchiudi", List.of()), () -> p.closeInventory());
        put(h, S_PREV, pane("&8pagina precedente", List.of("&7C'e' una sola pagina.")), null);
        put(h, S_NEXT, pane("&8pagina successiva", List.of("&7C'e' una sola pagina.")), null);
        put(h, S_BAR, pane("&ftask staff", List.of(
                "&7aperte: &f" + open.size(),
                "&7che puoi eseguire: &f" + canDo,
                "&7eseguite oggi: &f" + doneToday,
                "&7rifiutate oggi: &f" + rejectedToday)), null);
        put(h, S_INFO, pane("&6info", List.of(
                "&7Le task le creano gli helper (e i gradi sopra):",
                "&7un'azione su un giocatore (dai, togli, imposta,",
                "&7azzera coin, miniere, potenziamenti...).",
                "&fordinarie&7: le eseguono i mod in su.",
                "&fcustom&7 (con un testo da verificare): gli sr_mod in su.",
                "&7Chi crea non puo' eseguire la propria task.",
                "&7Chi puo' eseguire puo' anche rifiutare con un motivo.",
                "&7Gli admin possono eliminare e ripristinare (cestino).",
                "&7Tutto finisce nel resoconto.",
                "",
                "&eclicca per ricevere l'aiuto in chat")), () -> help(p));
        put(h, S_LIST, pane("&elista task", List.of(
                "&7task aperte: &f" + open.size(),
                "&7ordinarie: &f" + ord,
                "&7custom: &f" + cus,
                "&7create da te: &f" + mine,
                "&7che puoi eseguire: &f" + canDo,
                "",
                "&eclicca per aprire")), () -> openList(p, 0, 0));
        put(h, S_CREATE, pane(create ? "&acrea task" : "&8crea task", create
                ? List.of("&7Crea una nuova task: tipo, giocatore,", "&7risorsa, azione e quantita'.", "", "&eclicca per creare")
                : List.of("&7Le task le creano gli helper in su.", "", "&cnon puoi creare task")), create ? () -> startCreate(p) : null);
        if (admin) {
            put(h, S_TRASH, pane("&ccestino", List.of(
                    "&7task eliminate: &f" + safe(t.list(StaffTasks.Status.ELIMINATA)).size(),
                    "&7Clicca una task per ripristinarla.",
                    "",
                    "&eclicca per aprire")), () -> openTrash(p, 0));
        } else {
            put(h, S_TRASH, pane("&8cestino", List.of("&csolo admin")), null);
        }
        List<StaffTasks.LogEntry> log = safe(t.log(admin ? null : me));
        put(h, S_LOG, pane("&bresoconto task", List.of(
                "&7voci nel registro: &f" + log.size(),
                admin ? "&7Vedi tutto il registro." : "&7Vedi solo le voci delle tue task (create o eseguite da te).",
                "",
                "&eclicca per aprire")), () -> openLog(p, 0, 0));
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
        GuiMsg.send(plugin, p, "&7crea: tipo (ordinaria/custom), giocatore, risorsa, azione, quantita' (e il testo per le custom).");
        GuiMsg.send(plugin, p, "&7lista: clicca una task per eseguirla (con conferma), rifiutarla (con motivo) o eliminarla (admin).");
        GuiMsg.send(plugin, p, "&7ordinarie = mod in su, custom = sr_mod in su; non puoi eseguire le task create da te.");
        GuiMsg.send(plugin, p, "&7cestino (admin): ripristina le task eliminate. resoconto: tutto quello che e' successo.");
    }

    // ------------------------------------------------------------------
    //  CREA
    // ------------------------------------------------------------------

    private void startCreate(Player p) {
        if (!guard(p)) {
            return;
        }
        if (!tasks().canCreate(p)) {
            deny(p, "non puoi creare task (servono helper o un grado piu' alto).");
            return;
        }
        chooseType(p, new Draft());
    }

    private void chooseType(Player p, Draft d) {
        List<Opt> opts = List.of(
                new Opt("ORDINARIA", "ordinaria", List.of("&7Un'azione su un giocatore.", "&7La eseguono i mod in su.")),
                new Opt("CUSTOM", "custom", List.of("&7Un testo da verificare + un'azione.", "&7La eseguono gli sr_mod in su.")));
        choose(p, "crea task tipo", opts, d.type == StaffTasks.Type.CUSTOM ? 1 : 0, key -> {
            d.type = StaffTasks.Type.valueOf(key);
            askTarget(p, d);
        }, () -> {
            open(p);
        });
    }

    private void askTarget(Player p, Draft d) {
        plugin.getProfileUi().askText(p, "nome del giocatore", "NOME GIOCATORE", d.targetName, name -> {
            UUID id = resolve(name);
            if (id == null) {
                deny(p, "giocatore sconosciuto: " + (name == null ? "" : name));
                askTarget(p, d);
                return;
            }
            if (id.equals(p.getUniqueId()) && !tasks().isAdmin(p)) {
                deny(p, "non puoi creare una task su te stesso.");
                askTarget(p, d);
                return;
            }
            d.target = id;
            String n = UserProfilePowers.nameOf(id);
            d.targetName = n.isEmpty() ? name.trim() : n;
            chooseWhat(p, d);
        }, () -> {
            chooseType(p, d);
        });
    }

    private void chooseWhat(Player p, Draft d) {
        List<Opt> opts = new ArrayList<>();
        for (String w : StaffTasks.WHATS) {
            opts.add(new Opt(w, whatLabel(w), List.of("&7Giocatore: &f" + d.targetName)));
        }
        int start = d.what == null ? 0 : Math.max(0, StaffTasks.WHATS.indexOf(d.what));
        choose(p, "crea task risorsa", opts, start, key -> {
            if (!key.equals(d.what)) {
                d.verb = null;
            }
            d.what = key;
            chooseVerb(p, d);
        }, () -> {
            askTarget(p, d);
        });
    }

    private void chooseVerb(Player p, Draft d) {
        List<String> verbs = safe(StaffTasks.verbsFor(d.what));
        if (verbs.isEmpty()) {
            deny(p, "nessuna azione possibile per " + whatLabel(d.what) + ".");
            chooseWhat(p, d);
            return;
        }
        List<Opt> opts = new ArrayList<>();
        for (String v : verbs) {
            opts.add(new Opt(v, verbLabel(v), List.of("&7" + verbLabel(v) + " " + whatLabel(d.what) + " a &f" + d.targetName)));
        }
        int start = d.verb == null ? 0 : Math.max(0, verbs.indexOf(d.verb));
        choose(p, "crea task azione", opts, start, key -> {
            d.verb = key;
            askAmount(p, d);
        }, () -> {
            chooseWhat(p, d);
        });
    }

    private void askAmount(Player p, Draft d) {
        if ("reset".equals(d.verb)) {
            d.amount = 0;
            askCustomText(p, d);
            return;
        }
        long min = "edit".equals(d.verb) ? 0 : 1;
        plugin.getProfileUi().askNumber(p, "quantita' (" + verbLabel(d.verb) + " " + whatLabel(d.what) + ")", "QUANTITA", min, MAX_AMOUNT, n -> {
            d.amount = n;
            askCustomText(p, d);
        }, () -> {
            chooseVerb(p, d);
        });
    }

    private void askCustomText(Player p, Draft d) {
        if (d.type != StaffTasks.Type.CUSTOM) {
            confirmCreate(p, d);
            return;
        }
        plugin.getProfileUi().askText(p, "testo della task (es. verifica se ...)", "TESTO TASK", d.text, text -> {
            if (text == null || text.isBlank()) {
                deny(p, "scrivi il testo della task custom.");
                askCustomText(p, d);
                return;
            }
            d.text = text.trim();
            confirmCreate(p, d);
        }, () -> {
            if ("reset".equals(d.verb)) {
                chooseVerb(p, d);
            } else {
                askAmount(p, d);
            }
        });
    }

    private void confirmCreate(Player p, Draft d) {
        StaffTasks.Action action = new StaffTasks.Action(d.what, d.verb, d.amount);
        String text = "Creare la task " + typeLabel(d.type) + ": " + actionText(action) + " a " + d.targetName
                + (d.type == StaffTasks.Type.CUSTOM ? " (" + cut(clean(d.text), 40) + ")" : "") + "?";
        plugin.getProfileUi().confirmOver(p, text, bg(), () -> {
            if (!guard(p)) {
                return;
            }
            StaffTasks.Task t = tasks().create(p, d.type, d.target, action, d.type == StaffTasks.Type.CUSTOM ? d.text : null);
            if (t == null) {
                deny(p, "non e' stato possibile creare la task.");
            } else {
                GuiMsg.send(plugin, p, "&7task &f#" + t.id() + "&7 creata: &f" + actionText(action) + "&7 a &f" + d.targetName + "&7.");
            }
            open(p);
        }, () -> {
            open(p);
        });
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
    //  LISTA (task aperte)
    // ------------------------------------------------------------------

    private static final List<Filter> LIST_FILTERS = List.of(
            new Filter("Tutte", ""),
            new Filter("Ordinarie", "ORDINARIA"),
            new Filter("Custom", "CUSTOM"),
            new Filter("Mie", "MIE"));

    private void openList(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        showList(p, "lista task", LIST_FILTERS, filterIdx, page, key -> openRows(p, key, filterIdx),
                (fi, pg) -> openList(p, fi, pg), () -> open(p));
    }

    private List<Row> openRows(Player p, String key, int filterIdx) {
        List<Row> out = new ArrayList<>();
        StaffTasks t = tasks();
        UUID me = p.getUniqueId();
        for (StaffTasks.Task k : safe(t.list(StaffTasks.Status.APERTA))) {
            if ("ORDINARIA".equals(key) && k.type() != StaffTasks.Type.ORDINARIA) {
                continue;
            }
            if ("CUSTOM".equals(key) && k.type() != StaffTasks.Type.CUSTOM) {
                continue;
            }
            if ("MIE".equals(key) && !me.equals(k.createdBy())) {
                continue;
            }
            boolean exec = t.canExecute(p, k);
            boolean admin = t.isAdmin(p);
            List<String> lore = taskLore(k);
            lore.add(Bukkit.getPlayer(k.target()) != null ? "&7giocatore online: &asi" : "&7giocatore online: &cno &8(per eseguirla deve essere online)");
            lore.add("");
            if (exec || admin) {
                lore.add("&eclicca per " + (exec && admin ? "eseguire, rifiutare o eliminare" : exec ? "eseguire o rifiutare" : "eliminare"));
            } else if (me.equals(k.createdBy())) {
                lore.add("&8l'hai creata tu: non puoi eseguirla");
            } else {
                lore.add("&8il tuo grado non puo' eseguirla");
            }
            String id = k.id();
            out.add(new Row(item(k.type() == StaffTasks.Type.CUSTOM ? Material.WRITABLE_BOOK : Material.PAPER, taskName(k), lore),
                    exec || admin ? () -> taskActions(p, id, filterIdx) : null));
        }
        return out;
    }

    /** Popup con frecce: esegui / rifiuta / elimina, secondo i permessi. */
    private void taskActions(Player p, String id, int filterIdx) {
        if (!guard(p)) {
            return;
        }
        StaffTasks t = tasks();
        StaffTasks.Task k = t.get(id);
        if (k == null || k.status() != StaffTasks.Status.APERTA) {
            deny(p, "la task non e' piu' aperta.");
            openList(p, filterIdx, 0);
            return;
        }
        List<Opt> opts = new ArrayList<>();
        String what = actionText(k.action()) + " a " + k.targetName();
        if (t.canExecute(p, k)) {
            opts.add(new Opt("esegui", "esegui", List.of("&7Esegue davvero: &f" + what, "&7Chiede conferma.")));
            opts.add(new Opt("rifiuta", "rifiuta", List.of("&7Rifiuta la task con un motivo.")));
        }
        if (t.isAdmin(p)) {
            opts.add(new Opt("elimina", "elimina", List.of("&7Sposta la task nel cestino (solo admin).", "&7Chiede conferma.")));
        }
        if (opts.isEmpty()) {
            deny(p, "non puoi fare niente su questa task.");
            openList(p, filterIdx, 0);
            return;
        }
        Runnable back = () -> openList(p, filterIdx, 0);
        choose(p, "task " + id, opts, 0, key -> {
            switch (key) {
                case "esegui" -> plugin.getProfileUi().confirmOver(p, "Eseguire la task #" + id + ": " + what + "?", bg(), () -> {
                    if (!guard(p)) {
                        return;
                    }
                    String err = tasks().execute(p, id);
                    if (err == null) {
                        GuiMsg.send(plugin, p, "&atask #" + id + " eseguita: &f" + what + "&a.");
                    } else {
                        deny(p, err);
                    }
                    back.run();
                }, back);
                case "rifiuta" -> askReason(p, id, back);
                case "elimina" -> plugin.getProfileUi().confirmOver(p, "Eliminare la task #" + id + " (va nel cestino)?", bg(), () -> {
                    if (!guard(p)) {
                        return;
                    }
                    if (tasks().delete(p, id)) {
                        GuiMsg.send(plugin, p, "&7task &f#" + id + "&7 spostata nel cestino.");
                    } else {
                        deny(p, "non e' stato possibile eliminare la task.");
                    }
                    back.run();
                }, back);
                default -> back.run();
            }
        }, back);
    }

    private void askReason(Player p, String id, Runnable back) {
        plugin.getProfileUi().askText(p, "motivo del rifiuto", "MOTIVO RIFIUTO", "", reason -> {
            if (reason == null || reason.isBlank()) {
                deny(p, "scrivi il motivo del rifiuto.");
                askReason(p, id, back);
                return;
            }
            if (!guard(p)) {
                return;
            }
            String err = tasks().reject(p, id, reason.trim());
            if (err == null) {
                GuiMsg.send(plugin, p, "&7task &f#" + id + "&7 rifiutata.");
            } else {
                deny(p, err);
            }
            back.run();
        }, back);
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
        showList(p, "cestino task", List.of(new Filter("Eliminate", "")), 0, page, key -> trashRows(p),
                (fi, pg) -> openTrash(p, pg), () -> open(p));
    }

    private List<Row> trashRows(Player p) {
        List<Row> out = new ArrayList<>();
        for (StaffTasks.Task k : safe(tasks().list(StaffTasks.Status.ELIMINATA))) {
            List<String> lore = taskLore(k);
            lore.add("");
            lore.add("&eclicca per ripristinare");
            String id = k.id();
            out.add(new Row(item(Material.BARRIER, taskName(k), lore), () -> {
                plugin.getProfileUi().confirmOver(p, "Ripristinare la task #" + id + "?", bg(), () -> {
                    if (!guard(p)) {
                        return;
                    }
                    if (tasks().restore(p, id)) {
                        GuiMsg.send(plugin, p, "&7task &f#" + id + "&7 ripristinata.");
                    } else {
                        deny(p, "non e' stato possibile ripristinare la task.");
                    }
                    openTrash(p, 0);
                }, () -> {
                    openTrash(p, 0);
                });
            }));
        }
        return out;
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
        showList(p, "resoconto task", LOG_FILTERS, filterIdx, page, key -> logRows(p, key),
                (fi, pg) -> openLog(p, fi, pg), () -> open(p));
    }

    private List<Row> logRows(Player p, String key) {
        List<Row> out = new ArrayList<>();
        StaffTasks t = tasks();
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
            List<String> lore = new ArrayList<>();
            lore.add("&7task: &f#" + e.taskId());
            StaffTasks.Task k = t.get(e.taskId());
            if (k != null) {
                lore.add("&7tipo: &f" + typeLabel(k.type()));
                lore.add("&7azione: &f" + actionText(k.action()) + " a " + clean(k.targetName()));
            }
            lore.add("&7da: &f" + (e.byName() == null || e.byName().isBlank() ? "sistema" : clean(e.byName())));
            lore.add("&7quando: &f" + date(e.ts()));
            if (e.detail() != null && !e.detail().isBlank()) {
                for (String line : wrap(clean(e.detail()), 34)) {
                    lore.add("&f" + line);
                }
            }
            out.add(new Row(item(mat, color + ev.toLowerCase(Locale.ROOT) + " &7- &f#" + e.taskId() + " &7- &f" + date(e.ts()), lore), null));
        }
        return out;
    }

    // ------------------------------------------------------------------
    //  Testi delle task
    // ------------------------------------------------------------------

    private String taskName(StaffTasks.Task k) {
        String c = k.type() == StaffTasks.Type.CUSTOM ? "&d" : "&e";
        return c + typeLabel(k.type()) + " &7- &f" + actionText(k.action()) + " a " + clean(k.targetName());
    }

    private List<String> taskLore(StaffTasks.Task k) {
        List<String> lore = new ArrayList<>();
        lore.add("&7id: &f#" + k.id());
        lore.add("&7tipo: &f" + typeLabel(k.type()));
        lore.add("&7giocatore: &f" + clean(k.targetName()));
        lore.add("&7azione: &f" + actionText(k.action()));
        if (k.text() != null && !k.text().isBlank()) {
            lore.add("&7testo:");
            for (String line : wrap(clean(k.text()), 34)) {
                lore.add("&f" + line);
            }
        }
        lore.add("&7creata da: &f" + clean(k.createdByName()));
        lore.add("&7quando: &f" + date(k.createdAt()));
        lore.add("&7stato: &f" + k.status().name().toLowerCase(Locale.ROOT));
        // doneBy/doneAt restano anche nel cestino: con il motivo = rifiutata, senza = eseguita (come StaffTasks.restore)
        if (k.doneAt() > 0 && k.doneByName() != null && !k.doneByName().isBlank()) {
            boolean rejected = k.reason() != null && !k.reason().isBlank();
            lore.add("&7" + (rejected ? "rifiutata" : "eseguita") + " da: &f" + clean(k.doneByName()) + " &8(" + date(k.doneAt()) + ")");
        }
        if (k.reason() != null && !k.reason().isBlank()) {
            lore.add("&7motivo:");
            for (String line : wrap(clean(k.reason()), 34)) {
                lore.add("&f" + line);
            }
        }
        return lore;
    }

    private static String typeLabel(StaffTasks.Type t) {
        return t == StaffTasks.Type.CUSTOM ? "custom" : "ordinaria";
    }

    private static String verbLabel(String verb) {
        if (verb == null) {
            return "";
        }
        return switch (verb) {
            case "get" -> "dai";
            case "remove" -> "togli";
            case "edit" -> "imposta";
            case "reset" -> "azzera";
            default -> verb;
        };
    }

    private static String whatLabel(String what) {
        if (what == null) {
            return "";
        }
        return switch (what) {
            case "mine" -> "miniere";
            case "upgrade" -> "potenziamenti";
            default -> what;
        };
    }

    /** "dai 100 coin", "azzera stamina", "imposta 50 power". */
    private static String actionText(StaffTasks.Action a) {
        if (a == null) {
            return "";
        }
        if ("reset".equals(a.verb())) {
            return verbLabel(a.verb()) + " " + whatLabel(a.what());
        }
        return verbLabel(a.verb()) + " " + a.amount() + " " + whatLabel(a.what());
    }

    // ------------------------------------------------------------------
    //  Popup scelta con frecce (come RoleGui)
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

        List<String> barLore = new ArrayList<>();
        barLore.addAll(cur.lore());
        barLore.add("");
        for (int i = 0; i < n; i++) {
            barLore.add(i == idx ? "&a» " + opts.get(i).label() : "&8" + opts.get(i).label());
        }
        barLore.add("");
        barLore.add("&eusa le frecce per cambiare");
        put(h, CH_PREV, pane("&7precedente", List.of("&eclicca per scorrere")), () -> choose(p, title, opts, idx - 1, onPick, onBack));
        put(h, CH_NEXT, pane("&7successivo", List.of("&eclicca per scorrere")), () -> choose(p, title, opts, idx + 1, onPick, onBack));
        put(h, CH_BAR, pane("&f" + cur.label(), barLore), null);
        String key = cur.key();
        put(h, CH_YES, pane("&aconferma", List.of("&7Scelta: &f" + cur.label(), "", "&eclicca per confermare")), () -> onPick.accept(key));
        put(h, CH_NO, pane("&cannulla", List.of("&7Torna indietro.", "", "&eclicca per annullare")), onBack);
        p.openInventory(h.inventory);
    }

    // ------------------------------------------------------------------
    //  Popup lista (copia di ProfileLists, con il click sulle voci)
    // ------------------------------------------------------------------

    private interface Reopen {
        void open(int filterIdx, int page);
    }

    private void showList(Player p, String title, List<Filter> filters, int filterIdx, int page,
                          Function<String, List<Row>> rowsFor, Reopen reopen, Runnable onBack) {
        int fi = Math.max(0, Math.min(filterIdx, filters.size() - 1));
        List<Row> rows = null;
        try {
            rows = rowsFor.apply(filters.get(fi).key());
        } catch (RuntimeException ex) {
            plugin.getLogger().warning("TaskGui: errore nel caricare le righe: " + ex);
        }
        if (rows == null) {
            rows = new ArrayList<>();
        }
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
        List<String> fl = new ArrayList<>();
        fl.add("&7scegli cosa vedere:");
        fl.add(" ");
        for (int i = 0; i < filters.size(); i++) {
            fl.add(i == fi ? "&a» " + filters.get(i).label() : "&8" + filters.get(i).label());
        }
        fl.add(" ");
        fl.add("&eclicca per cambiare");
        put(h, L_FILTER, pane("&bfiltra per", fl), filters.size() > 1 ? () -> reopen.open((fi + 1) % filters.size(), 0) : null);
        put(h, new int[]{L_PREV}, pane("&7pagina precedente", List.of()), pg > 0 ? () -> reopen.open(fi, pg - 1) : null);
        put(h, new int[]{L_NEXT}, pane("&7pagina successiva", List.of()), pg < pages - 1 ? () -> reopen.open(fi, pg + 1) : null);
        if (rows.isEmpty()) {
            h.inventory.setItem(gridSlot(10), pane("&7nessuna voce", List.of("&8La lista e' vuota.")));
        } else {
            for (int i = 0; i < L_PER_PAGE; i++) {
                int idx = pg * L_PER_PAGE + i;
                if (idx >= rows.size()) {
                    break;
                }
                Row r = rows.get(idx);
                put(h, new int[]{gridSlot(i)}, r.item(), r.onClick());
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
    //  Item e utilita'
    // ------------------------------------------------------------------

    private void put(Holder h, int[] slots, ItemStack stack, Runnable action) {
        for (int s : slots) {
            h.inventory.setItem(s, stack);
            if (action != null) {
                h.actions.put(s, action);
            }
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
        Runnable action = h.actions.get(event.getRawSlot());
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
            // se l'azione non ha aperto un'altra schermata (es. click su una voce senza permessi) la GUI resta valida
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
}
