package it.blockmania;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * Servizi UI riusabili del profilo utente: popup comandi a 3 icone con frecce (userprofile-2..5),
 * anvil numerico, conferma generica (userprofile-6/7). Le icone delle azioni sono disegnate
 * nell'immagine di sfondo: i click sono su slot invisibili allineati alle icone.
 */
public final class UserProfilePowers implements Listener {

    // ---------- costanti configurabili (da PACK_USERPROFILE.md) ----------
    private static final int IMG = -22;
    private static final String FONT_UP = "minecraft:userprofile";
    private static final String IMG_MAIN = "";
    private static final String IMG_GIVE_REMOVE_SET = "";    // userprofile-2
    private static final String IMG_REMOVE_SET_FULL = "";    // userprofile-3
    private static final String IMG_REMOVE_SET_RESET = "";   // userprofile-4
    private static final String IMG_SET_FULL_RESET = "";     // userprofile-5
    private static final String IMG_CONFIRM = "";            // userprofile-6 (7 identica)
    private static final String IMG_EMPTY = "";              // userprofile-8 (cornice con hamburger)
    private static final String IMG_BAN_A = "\uE108";             // userprofile-9: add, remove, reduce
    private static final String IMG_BAN_B = "\uE109";             // userprofile-10: remove, reduce, reset
    private static final String IMG_MUTE_A = "\uE10A";            // userprofile-11
    private static final String IMG_MUTE_B = "\uE10B";            // userprofile-12
    private static final String IMG_SET_FULL_USES = "\uE10E";    // userprofile-15: set, full, setuses
    private static final String IMG_FULL_USES_RESET = "\uE10F";  // userprofile-16: full, setuses, reset
    private static final String IMG_SET_FULL_INF = "";     // userprofile-17: set, full, infinite (stamina)
    private static final String IMG_FULL_INF_RESET = "";   // userprofile-18: full, infinite, reset (stamina)
    private static final String IMG_WARN ="\uE10C";              // userprofile-13: add, remove, reset
    private static final String IMG_ANVIL = "㸈";              // incudine personalizzata (pack ticket)
    private static final String DIM = "&#555555";
    private static final int SIZE = 54;

    // slot (rawSlot 0..53; slot = riga*9+colonna, x container = 8+18c, y container = 18+18r)
    private static final int[] SLOTS_ICON = {20, 22, 24};          // 3 icone (container 44/80/116, 54)
    private static final int[] SLOTS_X = {1, 2};                   // X rossa (container 26,19)
    private static final int[] SLOTS_BLUE = {6, 7};                // pulsante blu in alto a destra (container 128,18)
    private static final int SLOT_PREV = 38;                       // freccia sinistra (container 43,93)
    private static final int SLOT_NEXT = 42;                       // freccia destra (container 113,93)
    private static final int[] SLOTS_YES = {19, 20, 21, 28, 29, 30};   // CONFERMA (container 27,63)
    private static final int[] SLOTS_NO = {23, 24, 25, 32, 33, 34};    // ANNULLA (container 92,63)

    // testo piccolo nella barra e numero pagina (stessi set dei popup ticket-5, font tiny2)
    private static final char BAR = '';
    private static final char BAR_SH = '';
    private static final char NAV2 = '';
    private static final char NAV2_SH = '';
    private static final String FONT_TINY3 = "minecraft:tiny3";   // = tiny2 alzato di 18 px (barra del popup userprofile)
    private static final String FONT_TINY4 = "minecraft:tiny4";   // font alto: testo sopra il bordo, per la barra dell'anvil
    private static final int ANVIL_BAR_CX = 99;                   // centro barra anvil (container 23..153)
    private static final int ANVIL_BAR_MAX = 112;
    private static final int BAR_MAX = 62;
    private static final int BAR_CX = 94;     // barra centrale (63,33,64,14) -> centro x 95
    private static final int NAV_CX = 94;     // barra frecce (71,109,48,12) -> centro x 95/96

    private final BlockMania plugin;
    private final Set<UUID> switching = new HashSet<>();
    private final Map<UUID, AnvilSession> anvils = new HashMap<>();

    public UserProfilePowers(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Tipi interni
    // ------------------------------------------------------------------

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        String kind;                 // "cmds" oppure "confirm"
        String image = IMG_EMPTY;    // immagine mostrata (serve da sfondo per la conferma)
        boolean dead;                // gia' gestita: la chiusura non va interpretata
        final Map<Integer, Runnable> actions = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final class AnvilSession {
        AnvilView view;
        String title;
        String barText;
        long min;
        long max;
        LongConsumer onOk;
        Runnable onCancel;
        boolean done;
        // modalita' testo libero (askText)
        boolean text;
        String initial = "";
        Consumer<String> onText;
    }

    // ------------------------------------------------------------------
    //  API pubblica
    // ------------------------------------------------------------------

    public boolean owns(Player p) {
        if (p == null) {
            return false;
        }
        if (anvils.containsKey(p.getUniqueId())) {
            return true;
        }
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
            return true;
        }
        UserProfileMines m = plugin.getProfileMines();
        UserProfileUpgrades u = plugin.getProfileUpgrades();
        UserProfileRewards r = plugin.getProfileRewards();
        return (m != null && m.owns(p)) || (u != null && u.owns(p)) || (r != null && r.owns(p));
    }

    /** Popup comandi a 3 icone per pagina, con frecce che scorrono. */
    public void openCommands(Player staff, String label, List<String> cmds, Consumer<String> onPick, Runnable onBack,
                             boolean historyButton, Runnable onHistory) {
        showCommands(staff, label, cmds == null ? List.of() : List.copyOf(cmds), 0, onPick, onBack, historyButton, onHistory);
    }

    /** Anvil numerico: accetta solo numeri tra min e max, altrimenti avvisa e riapre. */
    public void askNumber(Player staff, String title, long min, long max, LongConsumer onOk, Runnable onCancel) {
        askNumber(staff, title, title, min, max, onOk, onCancel);
    }

    /** Come sopra, ma barText e' il testo scritto nella barra grigia in alto (es. "SETTA STANDARD 1/20"). */
    public void askNumber(Player staff, String title, String barText, long min, long max, LongConsumer onOk, Runnable onCancel) {
        AnvilSession s = new AnvilSession();
        s.title = title == null ? "numero" : title;
        s.barText = barText == null ? s.title : barText;
        s.min = min;
        s.max = max;
        s.onOk = onOk;
        s.onCancel = onCancel == null ? () -> { } : onCancel;
        GuiTitle at = new GuiTitle(plugin);
        int dx = plugin.getConfig().getInt("tickets.anvil-shift", -52);
        at.at(-15 + dx, "&f" + IMG_ANVIL, "", 179);
        anvilBar(at, dx, s.barText);
        AnvilView view = MenuType.ANVIL.builder().title(at.build()).build(staff);
        s.view = view;
        String range = rangeText(min, max);
        // la casella di testo mostra il nome dell'oggetto 0: lo lasciamo vuoto (niente "ANNULLA")
        view.setItem(0, pane(" ", List.of("&cannulla", "&7Torna indietro senza fare niente.")));
        view.setItem(1, pane("&e" + s.title, List.of("&7Scrivi un numero " + range + ".", "&7Poi clicca sul tasto verde.")));
        UUID id = staff.getUniqueId();
        anvils.put(id, s);
        switching.add(id);
        try {
            staff.openInventory(view);
        } finally {
            switching.remove(id);
        }
        GuiMsg.send(plugin, staff, "&7" + s.title + ": scrivi un numero " + range + " nella barra e clicca sul tasto verde.");
    }

    /**
     * Anvil di testo libero (max 100 caratteri): stessa grafica di askNumber, nella casella c'e' "initial".
     * Il testo vuoto e' accettato (onOk(""))); la X chiama onCancel.
     */
    public void askText(Player staff, String title, String barText, String initial, Consumer<String> onOk, Runnable onCancel) {
        AnvilSession s = new AnvilSession();
        s.text = true;
        s.title = title == null ? "testo" : title;
        s.barText = barText == null ? s.title : barText;
        s.initial = initial == null ? "" : initial;
        s.onText = onOk;
        s.onCancel = onCancel == null ? () -> { } : onCancel;
        GuiTitle at = new GuiTitle(plugin);
        int dx = plugin.getConfig().getInt("tickets.anvil-shift", -52);
        at.at(-15 + dx, "&f" + IMG_ANVIL, "", 179);
        anvilBar(at, dx, s.barText);
        AnvilView view = MenuType.ANVIL.builder().title(at.build()).build(staff);
        s.view = view;
        // l'oggetto 0 e' la casella di testo: il suo nome e' il testo iniziale (testo semplice, senza colori)
        ItemStack in = new ItemStack(Material.GLASS_PANE);
        ItemMeta im = in.getItemMeta();
        im.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        im.displayName(Component.text(s.initial.isEmpty() ? " " : s.initial));
        im.lore(lore(List.of("&cannulla", "&7Torna indietro senza fare niente.")));
        in.setItemMeta(im);
        view.setItem(0, in);
        view.setItem(1, pane("&e" + s.title, List.of("&7Scrivi il testo (max 100 caratteri).", "&7Poi clicca sul tasto verde.")));
        UUID id = staff.getUniqueId();
        anvils.put(id, s);
        switching.add(id);
        try {
            staff.openInventory(view);
        } finally {
            switching.remove(id);
        }
        GuiMsg.send(plugin, staff, "&7" + s.title + ": scrivi il testo nella barra e clicca sul tasto verde.");
    }

    /** Slot dei bottoni del pannello conferma (glifo popup_confirm U+E207: riga 3). */
    private static final int[] CONFIRM_YES = {28, 29, 30};
    private static final int[] CONFIRM_NO = {32, 33, 34};

    /**
     * Conferma generica: lo sfondo e' quello della GUI da cui parte il comando, scurito. Se il popup comandi
     * di questo servizio e' aperto si vede quello (profilo + popup), altrimenti il profilo principale.
     * Le GUI di altre classi (miniere, potenziamenti, ricompense, cronologia) devono usare confirmOver.
     */
    public void confirm(Player staff, String text, Runnable onYes, Runnable onNo) {
        String cur = "";
        boolean cmds = false;
        if (staff.getOpenInventory().getTopInventory().getHolder() instanceof Holder h0
                && "cmds".equals(h0.kind) && h0.image != null) {
            cur = h0.image;
            cmds = true;
        }
        final String img = cur;
        final boolean overCmds = cmds;
        confirmOver(staff, text, c -> {
            // advance reale (186) per tutte le immagini: il cursore lo sistema confirmOver
            c.at(-15, (overCmds ? DIM : "&f") + IMG_MAIN, FONT_UP, 186);
            if (overCmds && !img.isEmpty()) {
                c.at(-15, "&f" + img, FONT_UP, 186);
            }
        }, onYes, onNo);
    }

    /**
     * Conferma sopra la GUI del chiamante. drawDimmedBackground disegna nel GuiTitle ricevuto lo sfondo della
     * propria GUI (immagini a immagine intera con x=-15 e advance REALE 186, niente numero pagina/testi): lo
     * scurisce questo metodo (x0.6) e disegna sopra a colori pieni il pannello "CONFERMA O ANNULLA IL COMANDO."
     * (glifo U+E207, font minecraft:mines) con il titolo CONFERMA nella barra in alto.
     * Il chiamante riceve onYes/onNo nel tick dopo il click; le slot del pannello sono 28-30 e 32-34.
     */
    public void confirmOver(Player staff, String text, Consumer<GuiTitle> drawDimmedBackground,
                            Runnable onYes, Runnable onNo) {
        Holder h = new Holder();
        h.kind = "confirm";
        h.image = IMG_EMPTY;
        GuiTitle content = new GuiTitle(plugin);
        if (drawDimmedBackground != null) {
            drawDimmedBackground.accept(content);
        }
        GuiTitle g = new GuiTitle(plugin);
        g.atComponent(0, MinesGui.tint(content.build(), null), content.cursor());
        g.at(-15, "&f" + '\uE207', MinesGui.F_MINES, 186);
        g.atComponent(g.cursor(), Component.empty(), -7);   // cursore 7 px indietro (convenzione dei ticket)
        topTitle(plugin, g, "conferma");
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());
        List<String> yes = new ArrayList<>();
        yes.add("&7" + (text == null ? "" : text));
        yes.add("");
        yes.add("&eclicca per confermare");
        List<String> no = List.of("&7Torna indietro senza fare niente.", "", "&eclicca per annullare");
        Runnable doYes = () -> later(onYes);
        Runnable doNo = () -> later(onNo);
        for (int s : CONFIRM_YES) {
            put(h, s, pane("&aconferma", yes), doYes);
        }
        for (int s : CONFIRM_NO) {
            put(h, s, pane("&cannulla", no), doNo);
        }
        open(staff, h);
    }

    // ------------------------------------------------------------------
    //  Popup comandi
    // ------------------------------------------------------------------

    private static String[] actionInfo(String cmd) {
        // {nome, descrizione}
        return switch (cmd) {
            case "give" -> new String[]{"&aaggiungi", "Aggiunge una quantita."};
            case "add" -> new String[]{"&aaggiungi", "Aggiunge (sanzione o quantita)."};
            case "remove" -> new String[]{"&ctogli", "Toglie una quantita o rimuove."};
            case "reduce" -> new String[]{"&6riduci", "Riduce la durata."};
            case "set" -> new String[]{"&eimposta", "Imposta un valore preciso."};
            case "full" -> new String[]{"&bmassimo", "Porta al valore massimo."};
            case "reset" -> new String[]{"&4azzera", "Riporta tutto a zero."};
            case "setuses" -> new String[]{"&dcolpi potenziati", "Imposta quanti colpi puo' sparare con un colpo potenziato."};
            default -> new String[]{"&f" + cmd, ""};
        };
    }

    /** {nome, descrizione, riga finale "clicca per ..."}: testi specifici per il popup stamina, altrimenti quelli generici. */
    private static String[] actionInfo(String cmd, String label) {
        if (label != null && label.toLowerCase(java.util.Locale.ROOT).startsWith("stamina")) {
            switch (cmd) {
                case "set" -> {
                    return new String[]{"&eimposta", "Imposta la stamina a una percentuale.", "&eclicca per impostare la stamina"};
                }
                case "full" -> {
                    return new String[]{"&bpiena", "Riempie la stamina.", "&eclicca per riempire la stamina"};
                }
                case "infinite" -> {
                    return new String[]{"&dinfinita", "La stamina non finisce mai.", "&eclicca per rendere infinita la stamina"};
                }
                case "reset" -> {
                    return new String[]{"&4svuota", "Svuota la stamina.", "&eclicca per svuotare la stamina"};
                }
                default -> { }
            }
        }
        String[] base = actionInfo(cmd);
        return new String[]{base[0], base[1], "&eclicca per scegliere"};
    }

    private static Material fallbackMaterial(String cmd) {
        return switch (cmd) {
            case "infinite" -> Material.NETHER_STAR;
            case "give", "add" -> Material.EMERALD;
            case "remove" -> Material.REDSTONE;
            case "reduce" -> Material.CLOCK;
            case "set" -> Material.COMPARATOR;
            case "full" -> Material.GLASS_BOTTLE;
            case "reset" -> Material.TNT;
            case "setuses" -> Material.BLAZE_ROD;
            default -> Material.PAPER;
        };
    }

    /** Immagine di sfondo per la finestra di 3 comandi, o null se non disegnata nel pack. */
    private static String imageFor(List<String> trio, String label) {
        if (trio.size() != 3) {
            return null;
        }
        String k = String.join(",", trio);
        return switch (k) {
            case "give,remove,set" -> IMG_GIVE_REMOVE_SET;
            case "remove,set,full" -> IMG_REMOVE_SET_FULL;
            case "remove,set,reset" -> IMG_REMOVE_SET_RESET;
            case "set,full,reset" -> IMG_SET_FULL_RESET;
            case "add,remove,reduce" -> sanction(label, IMG_BAN_A, IMG_MUTE_A);
            case "remove,reduce,reset" -> sanction(label, IMG_BAN_B, IMG_MUTE_B);
            case "add,remove,reset" -> IMG_WARN;
            case "set,full,setuses" -> IMG_SET_FULL_USES;
            case "full,setuses,reset" -> IMG_FULL_USES_RESET;
            case "set,full,infinite" -> IMG_SET_FULL_INF;
            case "full,infinite,reset" -> IMG_FULL_INF_RESET;
            default -> null;
        };
    }

    /** Sfondo ban o mute in base all'etichetta del popup (contiene "mute" per i mute, altrimenti ban). */
    private static String sanction(String label, String ban, String mute) {
        return label != null && label.toLowerCase(java.util.Locale.ROOT).startsWith("mute") ? mute : ban;
    }

    private void showCommands(Player p, String label, List<String> cmds, int page, Consumer<String> onPick, Runnable onBack,
                              boolean historyButton, Runnable onHistory) {
        int pages = Math.max(1, cmds.size() - 2);
        page = Math.max(0, Math.min(pages - 1, page));
        List<String> trio = cmds.subList(page, Math.min(cmds.size(), page + 3));
        String img = imageFor(trio, label);
        boolean drawn = img != null;
        if (!drawn) {
            img = IMG_EMPTY;
        }
        Holder h = new Holder();
        h.kind = "cmds";
        h.image = img;
        GuiTitle g = new GuiTitle(plugin);
        g.at(-15, DIM + IMG_MAIN, FONT_UP, 186);
        g.at(-15, "&f" + img, FONT_UP, 179);
        tinyBar(g, label == null ? "" : label);
        navText(g, page + 1, pages);
        topTitle(plugin, g, label == null ? "" : label);
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());

        final int pg = page;
        Runnable back = () -> later(onBack);
        for (int s : SLOTS_X) {
            put(h, s, pane("&cindietro", List.of()), back);
        }
        if (historyButton) {
            for (int s : SLOTS_BLUE) {
                put(h, s, pane("&9cronologia", List.of("&7Mostra lo storico.")), () -> later(onHistory));
            }
        }
        if (pages > 1) {
            put(h, SLOT_PREV, pane("&7pagina precedente", List.of()),
                    () -> showCommands(p, label, cmds, pg - 1, onPick, onBack, historyButton, onHistory));
            put(h, SLOT_NEXT, pane("&7pagina successiva", List.of()),
                    () -> showCommands(p, label, cmds, pg + 1, onPick, onBack, historyButton, onHistory));
        }
        for (int i = 0; i < trio.size() && i < 3; i++) {
            String cmd = trio.get(i);
            String[] info = actionInfo(cmd, label);
            List<String> lore = List.of("&7" + info[1], "", info[2]);
            ItemStack st = drawn ? pane(info[0], lore) : item(fallbackMaterial(cmd), info[0], lore);
            put(h, SLOTS_ICON[i], st, () -> later(() -> onPick.accept(cmd)));
        }
        open(p, h);
    }

    // ------------------------------------------------------------------
    //  Testi piccoli (copiati da Tickets)
    // ------------------------------------------------------------------

    private static int tinyAdvance(String text) {
        int total = 0;
        for (char ch : text.toCharArray()) {
            total += ch == ' ' ? 4 : (ch == 'i' || ch == 'I' || ch == '1') ? 4 : 6;
        }
        return total;
    }

    private static String tinyLetters(String text, char base) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            sb.append(ch < 128 && Character.isLetterOrDigit(ch) ? (char) (base + ch) : ' ');
        }
        return sb.toString();
    }

    private static String tinySafe(String s) {
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            sb.append(ch < 128 && Character.isLetterOrDigit(ch) ? ch : ' ');
        }
        return sb.toString().replaceAll(" +", " ").trim();
    }

    private void tinyBar(GuiTitle g, String raw) {
        String text = tinySafe(raw);
        if (text.isEmpty()) {
            return;
        }
        text = fit(text, BAR_MAX);
        int total = tinyAdvance(text);
        int st = BAR_CX - Math.max(0, total - 1) / 2;
        int off = 0;
        for (String w : text.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            int wa = tinyAdvance(w);
            g.at(st + off + 1 + IMG, GuiTitle.shadowCode(plugin) + tinyLetters(w, BAR_SH), FONT_TINY3, wa);
            g.at(st + off + IMG, "&f" + tinyLetters(w, BAR), FONT_TINY3, wa);
            off += wa + 4;
        }
    }

    // titolo grande nella barra grigia in alto (sopra il pannello): stessi glifi del codice dei ticket (font default)
    private static final char TOP = '\uf100';
    private static final char TOP_SH = '\uf200';
    private static final int TOP_CX = 94;
    private static final int TOP_MAX = 112;

    /** Scrive il nome della GUI centrato nella barra grigia in alto (colori pieni, sopra qualunque popup). */
    public static void topTitle(BlockMania plugin, GuiTitle g, String raw) {
        String text = tinySafe(raw == null ? "" : raw).toUpperCase(java.util.Locale.ROOT);
        if (text.isEmpty()) {
            return;
        }
        text = fit(text, TOP_MAX);
        int total = tinyAdvance(text);
        int st = TOP_CX - Math.max(0, total - 1) / 2;
        int off = 0;
        for (String w : text.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            int wa = tinyAdvance(w);
            g.at(st + off + 1 + IMG, GuiTitle.shadowCode(plugin) + tinyLetters(w, TOP_SH), "", wa);
            g.at(st + off + IMG, "&f" + tinyLetters(w, TOP), "", wa);
            off += wa + 4;
        }
    }

    /** Nome del giocatore per i titoli (anche offline). */
    public static String nameOf(UUID id) {
        String n = id == null ? null : Bukkit.getOfflinePlayer(id).getName();
        return n == null ? "" : n;
    }

    /** Accorcia il testo per farlo stare nella barra: toglie "di", poi "ball", poi taglia la parola piu' lunga. */
    private static String fit(String text, int max) {
        String t = text;
        if (tinyAdvance(t) <= max) {
            return t;
        }
        t = t.replaceAll("(?i)\\bdi\\b", " ").replaceAll(" +", " ").trim();
        if (tinyAdvance(t) > max) {
            t = t.replaceAll("(?i)(\\w)ball\\b", "$1").replaceAll(" +", " ").trim();
        }
        while (tinyAdvance(t) > max) {
            String[] w = t.split(" ");
            int li = -1;
            for (int i = 0; i < w.length; i++) {
                if (w[i].length() > 1 && !w[i].matches(".*\\d.*") && (li < 0 || w[i].length() >= w[li].length())) {
                    li = i;
                }
            }
            if (li < 0) {
                break;
            }
            w[li] = w[li].substring(0, w[li].length() - 1);
            t = String.join(" ", w).trim();
        }
        return t;
    }

    /** Testo nella barra grigia in alto dell'anvil (font alto tiny4, permette di scrivere sopra il bordo). */
    private void anvilBar(GuiTitle g, int dx, String raw) {
        StringBuilder sb = new StringBuilder();
        for (char ch : (raw == null ? "" : raw).toCharArray()) {
            sb.append(ch < 128 && (Character.isLetterOrDigit(ch) || ch == '/') ? ch : ' ');
        }
        String text = fit(sb.toString().replaceAll(" +", " ").trim(), ANVIL_BAR_MAX);
        if (text.isEmpty()) {
            return;
        }
        int total = tinyAdvance(text);
        int st = ANVIL_BAR_CX - Math.max(0, total - 1) / 2;
        int off = 0;
        for (String w : text.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            int wa = tinyAdvance(w);
            g.at(st + off + 1 + IMG + dx, GuiTitle.shadowCode(plugin) + tinyLettersSlash(w, BAR_SH), FONT_TINY4, wa);
            g.at(st + off + IMG + dx, "&f" + tinyLettersSlash(w, BAR), FONT_TINY4, wa);
            off += wa + 4;
        }
    }

    private static String tinyLettersSlash(String text, char base) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            sb.append(ch < 128 && (Character.isLetterOrDigit(ch) || ch == '/') ? (char) (base + ch) : ' ');
        }
        return sb.toString();
    }

    private void navText(GuiTitle g, int page, int pages) {
        String plain = page + "/" + pages;
        StringBuilder n = new StringBuilder();
        StringBuilder sh = new StringBuilder();
        for (char ch : plain.toCharArray()) {
            int o = ch == '/' ? 10 : ch - '0';
            n.append((char) (NAV2 + o));
            sh.append((char) (NAV2_SH + o));
        }
        int adv = plain.length() * 6;
        int start = NAV_CX - (adv - 1) / 2;
        g.at(start + 1 + IMG, GuiTitle.shadowCode(plugin) + sh, FONT_TINY3, adv);
        g.at(start + IMG, "&f" + n, FONT_TINY3, adv);
    }

    // ------------------------------------------------------------------
    //  Oggetti e utilita'
    // ------------------------------------------------------------------

    private List<Component> lore(List<String> lines) {
        List<Component> out = new ArrayList<>();
        for (String raw : lines) {
            out.add(raw == null || raw.isBlank() ? plugin.color(" ") : plugin.text(raw));
        }
        return out;
    }

    private ItemStack pane(String name, List<String> lore) {
        ItemStack stack = new ItemStack(Material.GLASS_PANE);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(plugin.getConfig().getInt("rewards-gui.invisible.cmd", 999));
        meta.displayName(plugin.text(name));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore(lore));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        ItemStack stack = new ItemStack(m);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text(name));
        if (lore != null && !lore.isEmpty()) {
            meta.lore(lore(lore));
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private void put(Holder h, int slot, ItemStack stack, Runnable action) {
        h.inventory.setItem(slot, stack);
        if (action != null) {
            h.actions.put(slot, action);
        }
    }

    private void open(Player p, Holder h) {
        UUID id = p.getUniqueId();
        switching.add(id);
        try {
            p.openInventory(h.inventory);
        } finally {
            switching.remove(id);
        }
    }

    private void later(Runnable r) {
        if (r == null) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> r.run());
    }

    private void err(Player p, String text) {
        GuiMsg.send(plugin, p, "&c" + text);
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.error");
    }

    private static String rangeText(long min, long max) {
        if (max >= Integer.MAX_VALUE) {
            return "da " + min + " in su";
        }
        return "da " + min + " a " + max;
    }

    /** Numero valido dal testo dell'anvil, oppure null. */
    private static Long parse(String raw, long min) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim();
        if (!t.matches(min < 0 ? "-?\\d{1,15}" : "\\d{1,15}")) {
            return null;
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    //  Eventi
    // ------------------------------------------------------------------

    @EventHandler
    public void onPrepare(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player p)) {
            return;
        }
        AnvilSession s = anvils.get(p.getUniqueId());
        if (s == null || event.getView().getTopInventory() != s.view.getTopInventory()) {
            return;
        }
        AnvilView av = (AnvilView) event.getView();
        if (s.text) {
            String raw = av.getRenameText() == null ? s.initial : av.getRenameText();
            boolean okText = raw.length() <= 100;
            event.setResult(pane(okText ? "&ainvia" : "&ctesto troppo lungo",
                    List.of(okText ? "&7" + (raw.isEmpty() ? "(vuoto)" : raw) : "&7Massimo 100 caratteri.")));
            av.setRepairCost(0);
            return;
        }
        String txt = av.getRenameText() == null ? "" : av.getRenameText();
        Long v = parse(txt, s.min);
        boolean ok = v != null && v >= s.min && v <= s.max;
        event.setResult(pane(ok ? "&ainvia" : "&cnumero non valido",
                List.of(ok ? "&7" + v : "&7Serve un numero " + rangeText(s.min, s.max) + ".")));
        av.setRepairCost(0);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player p)) {
            return;
        }
        AnvilSession s = anvils.get(p.getUniqueId());
        if (s != null && event.getView().getTopInventory() == s.view.getTopInventory()) {
            event.setCancelled(true);
            int raw = event.getRawSlot();
            if (raw == 2) {
                plugin.guiClick(p);
                submitAnvil(p, s);
            } else if (raw == 0) {
                plugin.guiClick(p);
                s.done = true;
                p.closeInventory();
                later(s.onCancel);
            }
            return;
        }
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() != h.inventory) {
            return;
        }
        Runnable action = h.actions.get(event.getRawSlot());
        if (action == null || h.dead) {
            return;
        }
        plugin.guiClick(p);
        h.dead = true;
        action.run();
        // le azioni di pagina riaprono un nuovo popup; se non e' cambiato niente la GUI resta valida
        if (p.getOpenInventory().getTopInventory().getHolder() == h) {
            h.dead = false;
        }
    }

    private void submitAnvil(Player p, AnvilSession s) {
        AnvilView av = s.view;
        if (s.text) {
            // testo libero: se non e' stato modificato vale il testo iniziale; vuoto ok
            String raw = av.getRenameText() == null ? s.initial : av.getRenameText();
            if (raw.length() > 100) {
                err(p, "Il testo puo' avere al massimo 100 caratteri.");
                reopenAnvil(p, s);
                return;
            }
            s.done = true;
            p.closeInventory();
            final String val = raw.trim();
            later(() -> s.onText.accept(val));
            return;
        }
        Long v = parse(av.getRenameText(), s.min);
        if (v == null) {
            err(p, "Valore non valido: scrivi solo numeri " + rangeText(s.min, s.max) + ".");
            reopenAnvil(p, s);
            return;
        }
        if (v < s.min || v > s.max) {
            err(p, "Il numero deve essere " + rangeText(s.min, s.max) + ".");
            reopenAnvil(p, s);
            return;
        }
        s.done = true;
        p.closeInventory();
        final long val = v;
        later(() -> s.onOk.accept(val));
    }

    private void reopenAnvil(Player p, AnvilSession s) {
        s.done = true;
        p.closeInventory();
        if (s.text) {
            later(() -> askText(p, s.title, s.barText, s.initial, s.onText, s.onCancel));
            return;
        }
        later(() -> askNumber(p, s.title, s.barText, s.min, s.max, s.onOk, s.onCancel));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
            return;
        }
        if (event.getWhoClicked() instanceof Player p && anvils.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player p)) {
            return;
        }
        UUID id = p.getUniqueId();
        AnvilSession s = anvils.get(id);
        if (s != null && event.getView().getTopInventory() == s.view.getTopInventory()) {
            event.getView().setItem(0, null);
            event.getView().setItem(1, null);
            event.getView().setItem(2, null);
            if (switching.contains(id)) {
                return;
            }
            anvils.remove(id);
            if (!s.done) {
                later(s.onCancel);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        AnvilSession s = anvils.remove(event.getPlayer().getUniqueId());
        if (s != null) {
            s.done = true;
        }
    }
}
