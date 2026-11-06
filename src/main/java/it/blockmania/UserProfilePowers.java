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
    private static final String IMG_WARN = "\uE10C";              // userprofile-13: add, remove, reset
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
    private static final String FONT_TINY2 = "minecraft:tiny2";
    private static final int BAR_MAX = 58;
    private static final int BAR_CX = 95;     // barra centrale (63,33,64,14) -> centro x 95
    private static final int NAV_CX = 96;     // barra frecce (71,109,48,12) -> centro x 95/96

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
        long min;
        long max;
        LongConsumer onOk;
        Runnable onCancel;
        boolean done;
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
        return p.getOpenInventory().getTopInventory().getHolder() instanceof Holder;
    }

    /** Popup comandi a 3 icone per pagina, con frecce che scorrono. */
    public void openCommands(Player staff, String label, List<String> cmds, Consumer<String> onPick, Runnable onBack,
                             boolean historyButton, Runnable onHistory) {
        showCommands(staff, label, cmds == null ? List.of() : List.copyOf(cmds), 0, onPick, onBack, historyButton, onHistory);
    }

    /** Anvil numerico: accetta solo numeri tra min e max, altrimenti avvisa e riapre. */
    public void askNumber(Player staff, String title, long min, long max, LongConsumer onOk, Runnable onCancel) {
        AnvilSession s = new AnvilSession();
        s.title = title == null ? "numero" : title;
        s.min = min;
        s.max = max;
        s.onOk = onOk;
        s.onCancel = onCancel == null ? () -> { } : onCancel;
        GuiTitle at = new GuiTitle(plugin);
        int dx = plugin.getConfig().getInt("tickets.anvil-shift", -52);
        at.at(-15 + dx, "&f" + IMG_ANVIL, "", 179);
        AnvilView view = MenuType.ANVIL.builder().title(at.build()).build(staff);
        s.view = view;
        String range = rangeText(min, max);
        view.setItem(0, pane("&cannulla", List.of("&7Torna indietro senza fare niente.")));
        view.setItem(1, pane("&e" + s.title, List.of("&7Scrivi un numero " + range + ".", "&7Poi clicca sul tasto verde.")));
        UUID id = staff.getUniqueId();
        anvils.put(id, s);
        switching.add(id);
        try {
            staff.openInventory(view);
        } finally {
            switching.remove(id);
        }
        staff.sendMessage(plugin.color("&7" + s.title + ": scrivi un numero " + range + " nella barra e clicca sul tasto verde."));
    }

    /** Conferma generica (userprofile-6/7). */
    public void confirm(Player staff, String text, Runnable onYes, Runnable onNo) {
        String bg = IMG_EMPTY;
        if (staff.getOpenInventory().getTopInventory().getHolder() instanceof Holder cur && cur.image != null) {
            bg = cur.image;
        }
        Holder h = new Holder();
        h.kind = "confirm";
        h.image = IMG_CONFIRM;
        GuiTitle g = new GuiTitle(plugin);
        g.at(-15, "&f" + bg, FONT_UP, 186);
        g.at(-15, "&f" + IMG_CONFIRM, FONT_UP, 179);
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());
        List<String> yes = new ArrayList<>();
        yes.add("&7" + (text == null ? "" : text));
        yes.add("");
        yes.add("&eclicca per confermare");
        List<String> no = List.of("&7Torna indietro senza fare niente.", "", "&eclicca per annullare");
        Runnable doYes = () -> later(onYes);
        Runnable doNo = () -> later(onNo);
        for (int s : SLOTS_YES) {
            put(h, s, pane("&aconferma", yes), doYes);
        }
        for (int s : SLOTS_NO) {
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
            default -> new String[]{"&f" + cmd, ""};
        };
    }

    private static Material fallbackMaterial(String cmd) {
        return switch (cmd) {
            case "give", "add" -> Material.EMERALD;
            case "remove" -> Material.REDSTONE;
            case "reduce" -> Material.CLOCK;
            case "set" -> Material.COMPARATOR;
            case "full" -> Material.GLASS_BOTTLE;
            case "reset" -> Material.TNT;
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
            default -> null;
        };
    }

    /** Sfondo ban o mute in base all'etichetta del popup (contiene "mute" per i mute, altrimenti ban). */
    private static String sanction(String label, String ban, String mute) {
        return label != null && label.toLowerCase(java.util.Locale.ROOT).contains("mute") ? mute : ban;
    }

    private void showCommands(Player p, String label, List<String> cmds, int page, Consumer<String> onPick, Runnable onBack,
                              boolean historyButton, Runnable onHistory) {
        int pages = Math.max(1, cmds.size() - 2);
        page = Math.floorMod(page, pages);
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
            String[] info = actionInfo(cmd);
            List<String> lore = List.of("&7" + info[1], "", "&eclicca per scegliere");
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
        while (tinyAdvance(text) > BAR_MAX && text.length() > 1) {
            text = text.substring(0, text.length() - 1).trim();
        }
        int total = tinyAdvance(text);
        int st = BAR_CX - Math.max(0, total - 1) / 2;
        int off = 0;
        for (String w : text.split(" ")) {
            if (w.isEmpty()) {
                continue;
            }
            int wa = tinyAdvance(w);
            g.at(st + off + 1 + IMG, GuiTitle.shadowCode(plugin) + tinyLetters(w, BAR_SH), FONT_TINY2, wa);
            g.at(st + off + IMG, "&f" + tinyLetters(w, BAR), FONT_TINY2, wa);
            off += wa + 4;
        }
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
        g.at(start + 1 + IMG, GuiTitle.shadowCode(plugin) + sh, FONT_TINY2, adv);
        g.at(start + IMG, "&f" + n, FONT_TINY2, adv);
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
        p.sendMessage(plugin.color("&c" + text));
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
        later(() -> askNumber(p, s.title, s.min, s.max, s.onOk, s.onCancel));
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
