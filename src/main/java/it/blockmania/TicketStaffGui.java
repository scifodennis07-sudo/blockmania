package it.blockmania;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
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

/**
 * Sezione TICKET dello staff (/adminmania ticket, e dalla futura STAFFBOARD con {@link #open(Player, Runnable)}).
 * Stessa struttura della GUI task (TaskGui): schermata principale con 3 pulsanti grandi APERTI / MIEI / CHIUSI e due in basso
 * VALUTAZIONI / RESOCONTO; ogni pulsante apre il popup lista U+E119 sopra la schermata scurita (titolo "TICKET"
 * identico, solo scurito; inventario chiaro; numero pagina sempre, anche 1/1).
 * Filtro: sinistro = su, destro = giu', Q = cerca (#numero, codice o nick; sparisce chiudendo la GUI).
 * Ticket nelle liste: sinistro = apre la chat (ticket-3, l'indietro torna alla stessa lista/filtro/pagina), destro = profilo
 * del player, shift + click = prendi in carico (con conferma, solo ticket aperti non tuoi). Niente si elimina.
 * La logica resta in Tickets (viste di sola lettura StaffTicket / StaffLogLine e azioni take / openChatFrom).
 * Proposta: docs/ADMIN_TICKET_PROPOSTA.md. Grafica di Sara: maniablock5/docs/TICKET_STAFF.md (costanti marcate PACK). Popup lista: ESC lo riapre.
 */
public final class TicketStaffGui implements Listener {

    // ---------- PACK (Sara, maniablock5/docs/TICKET_STAFF.md): schermata principale ticket_staff.png ----------
    // Copia di task.png (header, card, pulsanti e inventario identici al pixel): icone ticket, strisce delle scritte vuote.
    private static final String FONT = "minecraft:task";
    private static final String IMAGE = "\ue181";            // PACK: ticket_staff.png, U+E181 nel font minecraft:task
    /** PACK: font delle etichette delle card (lettere y 96..100, ombra 97..101: tiny_y123 con ascent -67/-68). */
    private static final String CARD_FONT = "minecraft:tiny_y95";
    // PACK: centri delle strisce etichetta (interni x 19..62, 73..116, 127..170; larghezza utile 40 -> max 41)
    private static final int CARD_OPEN_CX = 40;
    private static final int CARD_MINE_CX = 94;
    private static final int CARD_CLOSED_CX = 148;
    private static final int CARD_MAX = 41;
    private static final int IMG_ADV = 179;    // avanzamento nel titolo normale (reale 186 - 7), come TaskGui
    private static final int IMG_REAL = 186;   // avanzamento reale (sfondo dei popup)
    private static final int SIZE = 54;

    // slot della schermata principale (come TaskGui: item dello slot (c,r) a (15+18c, 33+18r))
    private static final int[] S_CLOSE = {0, 1};
    private static final int[] S_PREV = {2};
    private static final int[] S_BAR = {3, 4, 5};
    private static final int[] S_NEXT = {6};
    private static final int[] S_INFO = {7, 8};
    private static final int[] S_OPEN = {9, 10, 11, 18, 19, 20, 27, 28, 29};
    private static final int[] S_MINE = {12, 13, 14, 21, 22, 23, 30, 31, 32};
    private static final int[] S_CLOSED = {15, 16, 17, 24, 25, 26, 33, 34, 35};
    private static final int[] S_STARS = {36, 37, 38, 39, 45, 46, 47, 48};
    private static final int[] S_LOG = {41, 42, 43, 44, 50, 51, 52, 53};
    private static final int[] S_GAP = {40, 49};
    // PACK: scritte dei due pulsanti in basso (font tiny_y123, lettere y 124..128). A sinistra la stella e' di 11 px e la scritta
    // parte da x 28: VALUTAZIONI (61 px) ci sta giusta, centro 58, max 62. RESOCONTO come in task: centro 145, max 60.
    private static final String M_BTN_FONT = "minecraft:tiny_y123";
    private static final int M_STARS_CX = 58;
    private static final int M_STARS_MAX = 62;
    private static final int M_LOG_CX = 145;
    private static final int M_LOG_MAX = 60;
    // numero pagina della schermata principale (come TaskGui: tiny5, centro 94, sempre "1/1")
    private static final String M_NAV_FONT = "minecraft:tiny5";
    private static final char M_NAV = '\uf700';
    private static final char M_NAV_SH = '\uf710';
    private static final int M_NAV_CX = 94;

    // ---------- popup lista (ProfileLists / TaskGui, U+E119) ----------
    private static final String L_IMAGE = "\ue119";
    private static final String L_FONT = "minecraft:userprofile3";
    private static final int L_IMG = -22;
    private static final int L_ADV = 162;
    private static final int L_NAV_CX = 96;
    private static final char L_NAV = '\ue4a0';
    private static final char L_NAV_SH = '\ue4b0';
    private static final int L_NAV_MAX = 40;
    private static final char BAR_BASE = '\uf500';
    private static final char BAR_SHADOW = '\uf600';
    private static final String L_BAR_FONT = "minecraft:tiny3";
    private static final int L_BAR_CX = 95;
    private static final int L_BAR_MAX = 64;
    private static final int L_PER_PAGE = 21;
    private static final int[] L_X = {1, 2};
    private static final int[] L_BTN = {6, 7};
    private static final int L_PREV = 38;
    private static final int L_NEXT = 42;

    // ---------- icone degli item (blockmania:*, Sara) ----------
    private static final String[] STATUS_MODEL = {"ticket_open", "ticket_wait", "ticket_closed"};

    private static final ZoneId ROME = ZoneId.of("Europe/Rome");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ROME);   // sempre con l'anno

    // ---------- tipi interni ----------

    private static final class Holder implements InventoryHolder {
        Inventory inventory;
        boolean dead;   // gia' cliccata: il secondo click nello stesso tick non ripete l'azione
        final Map<Integer, Runnable> actions = new HashMap<>();
        final Map<Integer, Runnable> rightActions = new HashMap<>();   // click destro (se manca vale actions)
        final Map<Integer, Runnable> shiftActions = new HashMap<>();   // shift + click (prendi in carico)
        final Map<Integer, Runnable> dropActions = new HashMap<>();    // Q: solo sul filtro (cerca)
        /** Sfondo (colori pieni, advance reale) dei popup aperti da questo pannello. null = solo la schermata TICKET. */
        Consumer<GuiTitle> under;
        /** Popup (lista): chiuso con ESC/E si riapre, si esce solo dai pulsanti. */
        boolean popup;
        int reopens;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** Voce di una lista: item, click sinistro, destro, shift (null = niente). */
    private record Row(ItemStack item, Runnable left, Runnable right, Runnable shift) {
    }

    /** Filtro di una lista; hint = riga breve che lo spiega nella lore del pulsante (null = niente). */
    private record Filter(String label, String key, String hint) {
        Filter(String label, String key) {
            this(label, key, null);
        }
    }

    /** Filtro cercato con Q: un ticket (ticketId > 0) oppure un player (uuid e/o nome). Vive finche' la GUI resta aperta. */
    private record Adv(String label, int ticketId, UUID player, String name) {
    }

    private interface Reopen {
        void open(int filterIdx, int page);
    }

    private static final String ADV = "ADV";

    private final BlockMania plugin;
    /** Indietro della X della schermata principale (STAFFBOARD); assente = la X chiude. */
    private final Map<UUID, Runnable> boardBack = new HashMap<>();
    /** Filtri cercati per player e per lista ("open", "mine", "closed", "stars", "log"). */
    private final Map<UUID, Map<String, List<Adv>>> advFilters = new HashMap<>();

    public TicketStaffGui(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Permessi
    // ------------------------------------------------------------------

    private Tickets tk() {
        return plugin.getTickets();
    }

    private boolean guard(Player p) {
        if (tk() == null) {
            deny(p, "i ticket non sono disponibili.");
            return false;
        }
        if (!tk().staffAccess(p)) {
            deny(p, "questa sezione e' solo per lo staff.");
            return false;
        }
        return true;
    }

    private boolean isAdmin(Player p) {
        StaffTasks t = plugin.getStaffTasks();
        return p.isOp() || (t != null && t.isAdmin(p));
    }

    private void deny(Player p, String text) {
        GuiMsg.send(plugin, p, "&c" + text);
        plugin.playConfiguredSound(p.getLocation(), "message-sounds.error");
    }

    // ------------------------------------------------------------------
    //  Ingressi
    // ------------------------------------------------------------------

    /** Da comando: la X chiude. */
    public void open(Player p) {
        open(p, null);
    }

    /** Dalla STAFFBOARD (o altre GUI): back = cosa fa la X ("indietro"); null = la X chiude. */
    public void open(Player p, Runnable back) {
        UUID id = p.getUniqueId();
        advFilters.remove(id);
        if (back == null) {
            boardBack.remove(id);
        } else {
            boardBack.put(id, back);
        }
        if (tk() != null && tk().staffAccess(p)) {
            tk().markStaffSeen(p);
        }
        openMain(p);
    }

    /** /adminmania ticket aperti|miei|chiusi: schermata principale con sopra la lista della sezione. */
    public void openSection(Player p, String section) {
        if (!guard(p)) {
            return;
        }
        UUID id = p.getUniqueId();
        advFilters.remove(id);
        boardBack.remove(id);
        tk().markStaffSeen(p);
        // la lista si disegna gia' sopra la schermata TICKET scurita; la X della lista riporta alla schermata
        switch (section) {
            case "mine" -> openMine(p, 0, 0);
            case "closed" -> openClosed(p, 0, 0);
            default -> openOpen(p, 0, 0);
        }
    }

    // ------------------------------------------------------------------
    //  Schermata principale
    // ------------------------------------------------------------------

    private void openMain(Player p) {
        if (!guard(p)) {
            return;
        }
        UUID me = p.getUniqueId();
        List<Tickets.StaffTicket> all = tk().staffTickets(me);
        int open = 0;
        int free = 0;
        int mine = 0;
        int unread = 0;
        int waitPlayer = 0;
        int closed = 0;
        int closedMe = 0;
        int rated = 0;
        int starsSum = 0;
        int toRate = 0;
        Tickets.StaffTicket unreadFirst = null;
        for (Tickets.StaffTicket v : all) {
            if (v.status() != 2) {
                open++;
                if (v.staffIds().isEmpty()) {
                    free++;
                }
                if (v.staffIds().contains(me)) {
                    mine++;
                    unread += v.unread();
                    if (v.lastFromStaff()) {
                        waitPlayer++;
                    }
                    if (v.unread() > 0 && unreadFirst == null) {
                        unreadFirst = v;   // lista gia' ordinata: il piu' recente
                    }
                }
            } else {
                closed++;
                if (me.equals(v.closedByUuid())) {
                    closedMe++;
                }
                if (v.rating() > 0) {
                    rated++;
                    starsSum += v.rating();
                } else if (v.rating() == 0) {
                    toRate++;
                }
            }
        }
        int limit = tk().staffLimit();
        Holder h = new Holder();
        GuiTitle g = new GuiTitle(plugin);
        g.at(-15, "&f" + IMAGE, FONT, IMG_ADV);
        UserProfilePowers.topTitle(plugin, g, "ticket");
        navText(g, 1, 1, M_NAV_FONT, M_NAV, M_NAV_SH, M_NAV_CX, 0);   // una sola pagina: sempre "1/1"
        mainLabels(g, 0);
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());

        Runnable back = boardBack.get(me);
        if (back != null) {
            put(h, S_CLOSE, pane("&cindietro", List.of()), () -> {
                back.run();
            });
        } else {
            put(h, S_CLOSE, pane("&cchiudi", List.of()), () -> {
                p.closeInventory();
            });
        }
        put(h, S_PREV, pane("&8pagina precedente", List.of()), null);
        put(h, S_NEXT, pane("&8pagina successiva", List.of()), null);
        List<String> barLore = new ArrayList<>();
        barLore.add("&7presi da te: &f" + mine + "&8/&f" + limit);
        barLore.add("&7non letti: " + (unread > 0 ? "&e" : "&f") + unread);
        barLore.add("&7in attesa del player: &f" + waitPlayer);
        Runnable barClick = null;
        if (unreadFirst != null) {
            int tid = unreadFirst.id();
            barLore.add("");
            barLore.add("&eclicca per aprire il ticket #" + tid);
            barClick = () -> {
                openChat(p, tid, () -> {
                    openMain(p);
                });
            };
        }
        put(h, S_BAR, pane(unreadFirst != null ? "&eil tuo carico" : "&fil tuo carico", barLore), barClick);
        put(h, S_INFO, pane("&6info", List.of(
                "&7prendi: &frispondi o shift + click",
                "&7massimo: &f" + limit + " ticket a testa",
                "&7chiusura auto: &f" + tk().autoCloseHoursValue() + "h dopo il messaggio",
                "&7voto: &fentro " + tk().rateHoursValue() + "h, poi 5 stelle")), null);
        put(h, S_OPEN, pane("&aticket aperti", List.of(
                "&7aperti: &f" + open,
                "&7da prendere: " + (free > 0 ? "&e" : "&f") + free,
                "",
                "&eclicca per aprire")), () -> {
            openOpen(p, 0, 0);
        });
        put(h, S_MINE, pane("&eticket miei", List.of(
                "&7presi da te: &f" + mine + "&8/&f" + limit,
                "&7non letti: " + (unread > 0 ? "&e" : "&f") + unread,
                "",
                "&eclicca per aprire")), () -> {
            openMine(p, 0, 0);
        });
        put(h, S_CLOSED, pane("&cticket chiusi", List.of(
                "&7chiusi: &f" + closed,
                "&7chiusi da te: &f" + closedMe,
                "",
                "&eclicca per aprire")), () -> {
            openClosed(p, 0, 0);
        });
        put(h, S_GAP, pane(" ", List.of()), null);
        put(h, S_STARS, pane("&6valutazioni", List.of(
                "&7media: &f" + (rated == 0 ? "-" : String.format(Locale.ITALY, "%.1f", starsSum / (double) rated)) + " &8(&f" + rated + " voti&8)",
                "&7da valutare: &f" + toRate,
                "",
                "&eclicca per aprire")), () -> {
            openStars(p, 0, 0);
        });
        boolean admin = isAdmin(p);
        put(h, S_LOG, pane("&bresoconto ticket", List.of(
                "&7voci: &f" + tk().staffLog(me, admin).size(),
                admin ? "&7mostra: &ftutto" : "&7mostra: &fi tuoi ticket",
                "",
                "&eclicca per aprire")), () -> {
            openLog(p, 0, 0);
        });
        p.openInventory(h.inventory);
    }

    /**
     * Schermata TICKET a colori pieni come sfondo dei popup (la scurisce drawDimmed / confirmOver), con il titolo "TICKET"
     * (topTitleDim, cursore reale) e "1/1": i popup non scrivono un loro titolo. Inventario chiaro: variante di task.png.
     */
    private void background(GuiTitle g) {
        g.at(-15, "&f" + IMAGE, FONT, IMG_REAL);
        UserProfilePowers.topTitleDim(plugin, g, "ticket");
        navText(g, 1, 1, M_NAV_FONT, M_NAV, M_NAV_SH, M_NAV_CX, 7);
        mainLabels(g, 7);
        UserProfilePowers.invVariant(g, UserProfilePowers.INV_HIGH);   // ticket_staff.png ha la parte bassa di task.png (U+E000)
    }

    /** Scritte che non sono nel PNG. dx = 0 nel titolo normale, 7 nello sfondo (cursore reale). */
    private void mainLabels(GuiTitle g, int dx) {
        smallText(g, "aperti", CARD_FONT, CARD_OPEN_CX + dx, CARD_MAX, true);
        smallText(g, "miei", CARD_FONT, CARD_MINE_CX + dx, CARD_MAX, true);
        smallText(g, "chiusi", CARD_FONT, CARD_CLOSED_CX + dx, CARD_MAX, true);
        smallText(g, "valutazioni", M_BTN_FONT, M_STARS_CX + dx, M_STARS_MAX, true);
        smallText(g, "resoconto", M_BTN_FONT, M_LOG_CX + dx, M_LOG_MAX, true);
    }

    private Consumer<GuiTitle> bg() {
        return g -> {
            background(g);
        };
    }

    /** Sfondo di un popup aperto dal pannello che il player ha davanti (lista sopra la schermata), altrimenti la schermata. */
    private Consumer<GuiTitle> bgHere(Player p) {
        if (p.getOpenInventory().getTopInventory().getHolder() instanceof Holder h && h.under != null) {
            return h.under;
        }
        return bg();
    }

    /** Chat del ticket (Tickets, ticket-3): il suo indietro esegue back. */
    private void openChat(Player p, int ticketId, Runnable back) {
        if (!guard(p)) {
            return;
        }
        tk().openChatFrom(p, ticketId, back);
    }

    // ------------------------------------------------------------------
    //  Liste dei ticket
    // ------------------------------------------------------------------

    private static final List<Filter> OPEN_FILTERS = List.of(
            new Filter("Tutti", ""),
            new Filter("Da prendere", "FREE", "Nessuno staffer li ha presi."),
            new Filter("In attesa", "WAIT", "Chiusura automatica avviata."),
            new Filter("Assistenza", "assistenza"),
            new Filter("Bug", "bug"),
            new Filter("Report", "report"));

    private static final List<Filter> MINE_FILTERS = List.of(
            new Filter("Tutti", ""),
            new Filter("Non letti", "UNREAD", "Con messaggi da leggere."),
            new Filter("Risposti", "WAITP", "Hai risposto: tocca al player."));

    private static final List<Filter> CLOSED_FILTERS = List.of(
            new Filter("Tutti", ""),
            new Filter("Chiusi da me", "ME"),
            new Filter("Automatici", "AUTO", "Chiusi dal sistema."),
            new Filter("Da valutare", "RATE", "Il player non ha ancora votato."),
            new Filter("Assistenza", "assistenza"),
            new Filter("Bug", "bug"),
            new Filter("Report", "report"));

    private static final List<Filter> STAR_FILTERS = List.of(
            new Filter("Tutte", ""),
            new Filter("5 stelle", "S5"),
            new Filter("4 stelle", "S4"),
            new Filter("3 stelle", "S3"),
            new Filter("2 stelle", "S2"),
            new Filter("1 stella", "S1"),
            new Filter("Automatiche", "AUTO", "Il player non ha votato in tempo."),
            new Filter("Con me", "ME", "Ticket in cui hai risposto tu."));

    private void openOpen(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        List<Filter> filters = withAdv(p, "open", OPEN_FILTERS);
        int fi = Math.max(0, Math.min(filterIdx, filters.size() - 1));
        String key = filters.get(fi).key();
        Adv adv = advOf(p, "open", key);
        UUID me = p.getUniqueId();
        Runnable self = () -> {
            openOpen(p, fi, page);
        };
        List<Row> rows = new ArrayList<>();
        for (Tickets.StaffTicket v : tk().staffTickets(me)) {
            if (v.status() == 2) {
                continue;
            }
            if ("FREE".equals(key) && !v.staffIds().isEmpty()) {
                continue;
            }
            if ("WAIT".equals(key) && v.autoClose() <= 0) {
                continue;
            }
            if (isCategory(key) && !key.equals(v.category())) {
                continue;
            }
            if (adv != null && !matchesTicket(adv, v)) {
                continue;
            }
            rows.add(ticketRow(p, v, self));
        }
        listPanel(p, "open", filters, fi, page, rows, (f, pg) -> {
            openOpen(p, f, pg);
        });
    }

    private void openMine(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        List<Filter> filters = withAdv(p, "mine", MINE_FILTERS);
        int fi = Math.max(0, Math.min(filterIdx, filters.size() - 1));
        String key = filters.get(fi).key();
        Adv adv = advOf(p, "mine", key);
        UUID me = p.getUniqueId();
        Runnable self = () -> {
            openMine(p, fi, page);
        };
        List<Row> rows = new ArrayList<>();
        for (Tickets.StaffTicket v : tk().staffTickets(me)) {
            if (v.status() == 2 || !v.staffIds().contains(me)) {
                continue;
            }
            if ("UNREAD".equals(key) && v.unread() <= 0) {
                continue;
            }
            if ("WAITP".equals(key) && !v.lastFromStaff()) {
                continue;
            }
            if (adv != null && !matchesTicket(adv, v)) {
                continue;
            }
            rows.add(ticketRow(p, v, self));
        }
        listPanel(p, "mine", filters, fi, page, rows, (f, pg) -> {
            openMine(p, f, pg);
        });
    }

    private void openClosed(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        List<Filter> filters = withAdv(p, "closed", CLOSED_FILTERS);
        int fi = Math.max(0, Math.min(filterIdx, filters.size() - 1));
        String key = filters.get(fi).key();
        Adv adv = advOf(p, "closed", key);
        UUID me = p.getUniqueId();
        Runnable self = () -> {
            openClosed(p, fi, page);
        };
        List<Row> rows = new ArrayList<>();
        for (Tickets.StaffTicket v : tk().staffTickets(me)) {
            if (v.status() != 2) {
                continue;
            }
            if ("ME".equals(key) && !me.equals(v.closedByUuid())) {
                continue;
            }
            if ("AUTO".equals(key) && v.closedByUuid() != null) {
                continue;
            }
            if ("RATE".equals(key) && v.rating() != 0) {
                continue;
            }
            if (isCategory(key) && !key.equals(v.category())) {
                continue;
            }
            if (adv != null && !matchesTicket(adv, v)) {
                continue;
            }
            rows.add(ticketRow(p, v, self));
        }
        listPanel(p, "closed", filters, fi, page, rows, (f, pg) -> {
            openClosed(p, f, pg);
        });
    }

    private void openStars(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        List<Filter> filters = withAdv(p, "stars", STAR_FILTERS);
        int fi = Math.max(0, Math.min(filterIdx, filters.size() - 1));
        String key = filters.get(fi).key();
        Adv adv = advOf(p, "stars", key);
        UUID me = p.getUniqueId();
        Runnable self = () -> {
            openStars(p, fi, page);
        };
        List<Row> rows = new ArrayList<>();
        for (Tickets.StaffTicket v : tk().staffTickets(me)) {
            if (v.status() != 2 || v.rating() <= 0) {
                continue;
            }
            if (key.length() == 2 && key.charAt(0) == 'S' && v.rating() != key.charAt(1) - '0') {
                continue;
            }
            if ("AUTO".equals(key) && !v.ratedAuto()) {
                continue;
            }
            if ("ME".equals(key) && !v.repliers().contains(me)) {
                continue;
            }
            if (adv != null && !matchesTicket(adv, v)) {
                continue;
            }
            rows.add(ticketRow(p, v, self));
        }
        listPanel(p, "stars", filters, fi, page, rows, (f, pg) -> {
            openStars(p, f, pg);
        });
    }

    private static boolean isCategory(String key) {
        return key.equals("assistenza") || key.equals("bug") || key.equals("report");
    }

    /** Item di un ticket: sinistro = chat, destro = profilo, shift + click = prendi (solo aperti non tuoi). */
    private Row ticketRow(Player p, Tickets.StaffTicket v, Runnable self) {
        UUID me = p.getUniqueId();
        boolean canTake = v.status() != 2 && !v.staffIds().contains(me) && !me.equals(v.owner());
        List<String> lore = ticketLore(v);
        lore.add("");
        lore.add(canTake ? "&esinistro apri &8| &edestro profilo &8| &eshift + click prendi"
                : "&esinistro apri &8| &edestro profilo");
        ItemStack item = icon(STATUS_MODEL[Math.max(0, Math.min(2, v.status()))],
                "&f&lTicket #" + v.id() + " &7- " + v.category(), lore);
        int tid = v.id();
        UUID owner = v.owner();
        Runnable left = () -> {
            openChat(p, tid, self);
        };
        Runnable right = () -> {
            if (plugin.getUserProfile() != null && owner != null) {
                plugin.getUserProfile().open(p, owner);
            } else {
                deny(p, "il profilo del giocatore non e' disponibile.");
            }
        };
        Runnable shift = canTake ? () -> {
            confirmTake(p, tid, self);
        } : null;
        return new Row(item, left, right, shift);
    }

    /**
     * Lore compatta: player, aperto il, staff o "da prendere", ultimo messaggio, chiusura auto / chiuso da chi e quando,
     * valutazione, non letti. Date sempre con l'anno.
     */
    private List<String> ticketLore(Tickets.StaffTicket v) {
        List<String> lore = new ArrayList<>();
        lore.add("&7player: &f" + clean(v.ownerName()));
        lore.add("&7aperto il: &f" + date(v.created()));
        if (v.status() != 2) {
            lore.add("&7staff: " + (v.staffNames().isEmpty() ? "&eda prendere" : "&f" + clean(String.join(", ", v.staffNames()))));
            lore.add("&7ultimo: &f" + date(v.lastTime()) + " &7da &f" + clean(v.lastName()));
            if (v.autoClose() > 0) {
                lore.add("&7chiusura auto: &f" + date(v.autoClose()));
            }
        } else {
            String by = v.closedBy() == null || v.closedBy().isBlank() || v.closedByUuid() == null ? "sistema" : clean(v.closedBy());
            lore.add("&7chiuso da &f" + by + " &7il &f" + (v.closedAt() > 0 ? date(v.closedAt()) : "non disponibile"));
            lore.add("&7valutazione: " + ratingText(v));
        }
        if (v.unread() > 0) {
            lore.add("&7non letti: &e" + v.unread());
        }
        return lore;
    }

    private static String ratingText(Tickets.StaffTicket v) {
        if (v.rating() > 0) {
            int f = Math.min(5, v.rating());
            return "&e" + "★".repeat(f) + "&7" + "☆".repeat(5 - f) + " &7(" + f + "/5" + (v.ratedAuto() ? ", automatica" : "") + ")";
        }
        if (v.rating() == 0) {
            return v.rateDeadline() > 0 ? "&eda dare entro " + date(v.rateDeadline()) : "&eda dare";
        }
        return "&8non richiesta";
    }

    /** Shift + click: conferma "prendere il ticket?" sopra la lista (scurita). */
    private void confirmTake(Player p, int ticketId, Runnable self) {
        plugin.getProfileUi().confirmOver(p, "Prendere in carico il ticket #" + ticketId + "?", bgHere(p), () -> {
            if (!guard(p)) {
                return;
            }
            String err = tk().take(p, ticketId);
            if (err == null) {
                GuiMsg.send(plugin, p, "&aticket #" + ticketId + " preso in carico.");
                plugin.playConfiguredSound(p.getLocation(), "message-sounds.success");
            } else {
                deny(p, err);
            }
            self.run();
        }, self);
    }

    // ------------------------------------------------------------------
    //  RESOCONTO
    // ------------------------------------------------------------------

    private static final List<Filter> LOG_FILTERS = List.of(
            new Filter("Tutto", ""),
            new Filter("Aperti", "APERTO"),
            new Filter("Risposte", "RISPOSTA"),
            new Filter("Msg pronti", "PRONTO", "Messaggi pronti mandati."),
            new Filter("Sezioni", "SEZIONE", "Sezione cambiata."),
            new Filter("Tag", "TAG", "Staffer taggati."),
            new Filter("Presi", "PRESO", "Presi in carico."),
            new Filter("Chiusi", "CHIUSO"),
            new Filter("Valutati", "VALUTATO"));

    private void openLog(Player p, int filterIdx, int page) {
        if (!guard(p)) {
            return;
        }
        List<Filter> filters = withAdv(p, "log", LOG_FILTERS);
        int fi = Math.max(0, Math.min(filterIdx, filters.size() - 1));
        String key = filters.get(fi).key();
        Adv adv = advOf(p, "log", key);
        UUID me = p.getUniqueId();
        Map<Integer, Tickets.StaffTicket> byId = new HashMap<>();
        for (Tickets.StaffTicket v : tk().staffTickets(me)) {
            byId.put(v.id(), v);
        }
        Runnable self = () -> {
            openLog(p, fi, page);
        };
        List<Row> rows = new ArrayList<>();
        for (Tickets.StaffLogLine e : tk().staffLog(me, isAdmin(p))) {
            if (adv == null && !key.isEmpty() && !key.equals(e.event())) {
                continue;
            }
            Tickets.StaffTicket v = byId.get(e.ticketId());
            if (adv != null && !matchesLog(adv, e, v)) {
                continue;
            }
            String color = switch (e.event()) {
                case "APERTO" -> "&a";
                case "RISPOSTA" -> "&b";
                case "PRONTO" -> "&9";
                case "SEZIONE" -> "&d";
                case "TAG" -> "&6";
                case "PRESO" -> "&e";
                case "CHIUSO" -> "&c";
                case "VALUTATO" -> "&6";
                default -> "&7";
            };
            List<String> lore = new ArrayList<>();
            lore.add("&7ticket: &f#" + e.ticketId() + (v != null ? " &7di &f" + clean(v.ownerName()) : ""));
            lore.add("&7da: &f" + clean(e.byName()));
            lore.add("&7quando: &f" + date(e.time()));
            String text = clean(e.text());
            if (!text.isEmpty()) {
                lore.add("&f" + (text.length() > 60 ? text.substring(0, 59) + "..." : text));
            }
            lore.add("");
            lore.add("&eclicca per aprire il ticket");
            int tid = e.ticketId();
            rows.add(new Row(icon(logIcon(e.event()), color + e.event().toLowerCase(Locale.ROOT) + " &7- &fticket #" + tid, lore),
                    () -> {
                        openChat(p, tid, self);
                    }, null, null));
        }
        listPanel(p, "log", filters, fi, page, rows, (f, pg) -> {
            openLog(p, f, pg);
        });
    }

    /** PACK: icone degli eventi (TICKET_STAFF.md par. 2). "Preso in carico" non ha un'icona sua: ticket verde dello stato aperto. */
    private static String logIcon(String event) {
        return switch (event) {
            case "APERTO" -> "ticket_ev_open";
            case "RISPOSTA" -> "ticket_ev_reply";
            case "PRONTO" -> "ticket_ev_canned";
            case "SEZIONE" -> "ticket_ev_section";
            case "TAG" -> "ticket_ev_tag";
            case "CHIUSO" -> "ticket_ev_closed";
            case "VALUTATO" -> "ticket_ev_rated";
            default -> "ticket_open";
        };
    }

    // ------------------------------------------------------------------
    //  Popup lista (copia di TaskGui.listPanel, con shift + click sulle voci)
    // ------------------------------------------------------------------

    /**
     * Popup lista sopra la schermata TICKET scurita (titolo "TICKET" identico, scurito). Filtro: sinistro = su, destro = giu',
     * Q = cerca (#numero, codice o nick) che si aggiunge in fondo, gia' selezionato. X = indietro alla schermata principale.
     */
    private void listPanel(Player p, String kind, List<Filter> filters, int fi, int page, List<Row> rows, Reopen reopen) {
        List<String> fl = new ArrayList<>();
        for (int i = 0; i < filters.size(); i++) {
            fl.add(i == fi ? "&a» " + filters.get(i).label() : "&8" + filters.get(i).label());
        }
        if (filters.get(fi).hint() != null) {
            fl.add("&7" + filters.get(fi).hint());
        }
        fl.add("");
        fl.add(filters.size() > 1 ? "&esinistro su &8| &edestro giù &8| &eQ cerca" : "&eQ cerca per ticket o nick");
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
        h.popup = true;
        GuiTitle g = new GuiTitle(plugin);
        UserProfilePowers.drawDimmed(plugin, g, bg());
        g.at(-15, "&f" + L_IMAGE, L_FONT, L_ADV);
        smallText(g, filters.get(fi).label(), L_BAR_FONT, L_BAR_CX, L_BAR_MAX);   // fit: il testo non esce mai dalla barra
        navText(g, pg + 1, pages);   // sempre, anche 1/1
        h.inventory = Bukkit.createInventory(h, SIZE, g.build());
        // sfondo dei popup aperti dalla lista (conferma prendi): schermata TICKET + pannello lista (advance reale, testi esclusi)
        h.under = u -> {
            background(u);
            u.at(-15, "&f" + L_IMAGE, L_FONT, L_ADV + 7);
        };

        put(h, L_X, pane("&cindietro", List.of()), () -> {
            openMain(p);
        });
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
            h.inventory.setItem(gridSlot(10), icon("ticket_closed", "&7nessun ticket", List.of()));
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
                if (r.shift() != null) {
                    h.shiftActions.put(slot[0], r.shift());
                }
            }
        }
        p.openInventory(h.inventory);
    }

    // ------------------------------------------------------------------
    //  Ricerca (Q sul filtro)
    // ------------------------------------------------------------------

    private List<Adv> adv(Player p, String kind) {
        return advFilters.computeIfAbsent(p.getUniqueId(), u -> new HashMap<>()).computeIfAbsent(kind, k -> new ArrayList<>());
    }

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

    /** Incudine (con "continua in chat" di askText) per "#12", "12", il codice del ticket o un nick (anche scritto male). */
    private void askAdvanced(Player p, String kind, Runnable onBack, Runnable onAdded) {
        plugin.getProfileUi().askText(p, "ticket (es. #12), codice o nick", "CERCA", "", text -> {
            Adv a = parseAdvanced(p, text);
            if (a == null) {
                deny(p, "nessun ticket o giocatore trovato per \"" + clean(text) + "\".");
                onBack.run();
                return;
            }
            adv(p, kind).add(a);
            onAdded.run();
        }, onBack);
    }

    private Adv parseAdvanced(Player p, String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        Map<String, UUID> names = knownNames(p);
        boolean looksId = text.startsWith("#") || text.matches("\\d+");
        if (!looksId) {
            for (String n : names.keySet()) {
                if (n.equalsIgnoreCase(text)) {
                    return new Adv(n, 0, names.get(n), n);   // nick esatto prima del codice
                }
            }
        }
        int tid = tk().ticketIdOf(text);
        if (tid > 0) {
            return new Adv("Ticket #" + tid, tid, null, null);
        }
        if (looksId) {
            return null;
        }
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
        return new Adv(best, 0, names.get(best), best);
    }

    /** Nomi conosciuti: player del server e nomi che compaiono nei ticket (player, staff, chi ha chiuso, resoconto). */
    private Map<String, UUID> knownNames(Player p) {
        Map<String, UUID> out = new HashMap<>();
        for (Player o : Bukkit.getOnlinePlayers()) {
            out.put(o.getName(), o.getUniqueId());
        }
        for (OfflinePlayer o : Bukkit.getOfflinePlayers()) {
            if (o.getName() != null && !o.getName().isEmpty()) {
                out.putIfAbsent(o.getName(), o.getUniqueId());
            }
        }
        for (Tickets.StaffTicket v : tk().staffTickets(null)) {
            putName(out, v.ownerName(), v.owner());
            putName(out, v.closedBy(), v.closedByUuid());
            for (String s : v.staffNames()) {
                putName(out, s, null);
            }
        }
        for (Tickets.StaffLogLine e : tk().staffLog(p.getUniqueId(), true)) {
            putName(out, e.byName(), e.by());
        }
        out.remove("sistema");
        return out;
    }

    private static void putName(Map<String, UUID> out, String name, UUID id) {
        if (name != null && !name.isBlank() && !name.equals("?")) {
            out.putIfAbsent(name.trim(), id);
        }
    }

    private static boolean same(Adv a, UUID id, String name) {
        return (a.player() != null && a.player().equals(id)) || (a.name() != null && name != null && a.name().equalsIgnoreCase(name.trim()));
    }

    /** Ticket del filtro: quello col numero, oppure del player, con lui nello staff, che ha risposto o chiuso. */
    private static boolean matchesTicket(Adv a, Tickets.StaffTicket v) {
        if (a.ticketId() > 0) {
            return a.ticketId() == v.id();
        }
        if (same(a, v.owner(), v.ownerName()) || same(a, v.closedByUuid(), v.closedBy())) {
            return true;
        }
        if (a.player() != null && (v.staffIds().contains(a.player()) || v.repliers().contains(a.player()))) {
            return true;
        }
        for (String s : v.staffNames()) {
            if (same(a, null, s)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesLog(Adv a, Tickets.StaffLogLine e, Tickets.StaffTicket v) {
        if (a.ticketId() > 0) {
            return a.ticketId() == e.ticketId();
        }
        return same(a, e.by(), e.byName()) || (v != null && same(a, v.owner(), v.ownerName()));
    }

    // ------------------------------------------------------------------
    //  Testo piccolo nel titolo (copia di TaskGui)
    // ------------------------------------------------------------------

    private static int gridSlot(int i) {
        return 10 + (i / 7) * 9 + (i % 7);
    }

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

    /** Accorcia il testo finche' sta in max px (non deve MAI uscire dalla barra): abbrevia col punto, poi taglia. */
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
            t = t.substring(0, t.length() - 1).trim();
        }
        return tinyAdvance(t) > max ? "" : t;
    }

    private void smallText(GuiTitle g, String raw, String font, int cx, int max) {
        smallText(g, raw, font, cx, max, false);
    }

    private void smallText(GuiTitle g, String raw, String font, int cx, int max, boolean upper) {
        String src = raw == null ? "" : upper ? raw.toUpperCase(Locale.ROOT) : raw;
        StringBuilder safe = new StringBuilder();
        for (char ch : src.toCharArray()) {
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

    private void navText(GuiTitle g, int page, int pages) {
        navText(g, page, pages, "", L_NAV, L_NAV_SH, L_NAV_CX, 0);
    }

    private void navText(GuiTitle g, int page, int pages, String font, char base, char shadow, int cx, int dx) {
        String plain = Math.max(1, page) + "/" + Math.max(1, pages);
        if (plain.length() * 6 > L_NAV_MAX) {
            plain = String.valueOf(page);
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
    //  Item (nomi e lore passano da LoreWrap: righe corte, tasti -> icone)
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
        decorate(meta, name, lore);
        stack.setItemMeta(meta);
        return stack;
    }

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
            action = h.dropActions.get(slot);   // Q: solo sul filtro (cerca)
        } else if (click.isShiftClick()) {
            action = h.shiftActions.get(slot);  // shift + click: prendi in carico (altrove niente)
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
     * Popup (lista) chiuso con ESC/E: si riapre al tick dopo (si esce solo con la X), come la conferma di UserProfilePowers.
     * GUI chiusa davvero (ESC sulla schermata principale, closeInventory): i filtri cercati spariscono. Aprire popup, chat o
     * incudine (OPEN_NEW) non li toglie.
     */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Holder h) || !(event.getPlayer() instanceof Player p)) {
            return;
        }
        if (h.popup && event.getReason() == InventoryCloseEvent.Reason.PLAYER && h.reopens < 40) {
            h.reopens++;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (p.isOnline() && p.getOpenInventory().getTopInventory().getType() == InventoryType.CRAFTING) {
                    h.dead = false;
                    p.openInventory(h.inventory);
                } else {
                    advFilters.remove(p.getUniqueId());
                }
            });
            return;
        }
        if (event.getReason() != InventoryCloseEvent.Reason.OPEN_NEW) {
            advFilters.remove(p.getUniqueId());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        advFilters.remove(event.getPlayer().getUniqueId());
        boardBack.remove(event.getPlayer().getUniqueId());
    }
}
