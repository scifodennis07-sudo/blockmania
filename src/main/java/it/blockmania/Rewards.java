package it.blockmania;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * /bm ricompense: pagina principale con le tipologie (ognuna con livello e tacchette), il percorso dei livelli
 * (barili e bauli), il battlepass (1 livello ogni 1500 exp), la ricompensa giornaliera, /afk e gli assegni.
 */
public final class Rewards implements Listener {

    private static final String GUI = "rewards-gui.";
    private static final int SIZE = 54;
    /** Le tipologie conosciute (ognuna ha il suo contatore). La giornaliera e il battlepass sono speciali. */
    static final String[] TYPES = {"broken", "hits", "bc_earned", "bc_spent", "coin_earned", "coin_spent",
            "platinum", "play", "daily", "pass"};
    private static final String[] CHEQUES = {"standardball", "doubleball", "ghostball", "explosionball",
            "coin", "blockcoin", "power"};
    /** Lettere disegnate dal pack (ascii.png): carattere ASCII c -> base + c. */
    private static final char TEXT_BASE = '';
    private static final char TEXT_SHADOW_BASE = '';
    private static final char BAR2_BASE = '';        // testo nella barra verde dei percorsi
    private static final char BAR2_SHADOW_BASE = '';
    private static final char CELL_BASE = '';        // si / no della giornaliera nella pagina principale
    private static final char CELL_SHADOW_BASE = '';
    private static final char TICK_BASE = '';        // tacchette: +0x10 * colore (rosso, giallo, verde) + riga
    private static final char DIGIT_BASE = '';       // numeri del livello: +0x10 * riga (0-4)
    private static final char DIGIT_SHADOW_BASE = '';
    /** Da pixel dell'immagine a pixel del titolo (la stessa misura usata per il numero pagina: centro barra 95 -> 73). */
    private static final int IMG = -22;
    private static final char TINY_BASE = '\uEA00';         // lettere piccole (5 pixel) nelle barre verdi
    private static final char TINY_SHADOW_BASE = '\uEB00';
    /** Percorso dei 15 livelli: riga e colonna di ogni casella. */
    private static final int[][] PATH = {{2, 1}, {3, 1}, {4, 1}, {4, 2}, {4, 3}, {3, 3}, {2, 3}, {2, 4}, {2, 5},
            {3, 5}, {4, 5}, {4, 6}, {4, 7}, {3, 7}, {2, 7}};
    private static final int PASS_FIRST = 28;              // il battlepass: 7 caselle da 28 a 34

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private String kind;            // main, levels, daily
        private String type = "";
        private int page;
        private int mainPage;
        private final Map<Integer, String> actions = new HashMap<>();

        Holder(String kind) {
            this.kind = kind;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record Reward(String type, int amount) {
    }

    private final BlockMania plugin;
    private final Set<UUID> afk = new HashSet<>();
    private final Set<UUID> rolling = new HashSet<>();
    private final Random random = new Random();
    private final NamespacedKey chequeKey;
    private BukkitTask task;

    public Rewards(BlockMania plugin) {
        this.plugin = plugin;
        this.chequeKey = new NamespacedKey(plugin, "cheque");
    }

    // ------------------------------------------------------------------
    //  Avvio e tempo di gioco
    // ------------------------------------------------------------------

    public void start() {
        // ogni minuto: chi non e' AFK ha un minuto di gioco in piu' (anche per la ricompensa di oggi)
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long today = today();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (afk.contains(p.getUniqueId())) {
                    continue;
                }
                AmmoManager a = plugin.getAmmo();
                a.setCounter(p, "rw_play", a.getCounter(p, "rw_play") + 1);
                if (a.getCounter(p, "rw_daydate") != today) {
                    a.setCounter(p, "rw_daydate", (int) today);
                    a.setCounter(p, "rw_daymin", 0);
                }
                a.setCounter(p, "rw_daymin", a.getCounter(p, "rw_daymin") + 1);
            }
        }, 1200L, 1200L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private static long today() {
        return LocalDate.now().toEpochDay();
    }

    // ------------------------------------------------------------------
    //  AFK
    // ------------------------------------------------------------------

    public boolean isAfk(Player player) {
        return afk.contains(player.getUniqueId());
    }

    /** /afk: accende o spegne (per ora non fa altro). */
    public void toggleAfk(Player player) {
        if (afk.remove(player.getUniqueId())) {
            plugin.send(player, "afk-off");
        } else {
            afk.add(player.getUniqueId());
            plugin.send(player, "afk-on");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        afk.remove(event.getPlayer().getUniqueId());
        rolling.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    //  Contatori (chiamati dal resto del plugin)
    // ------------------------------------------------------------------

    public void onHit(Player player) {
        add(player, "rw_hits", 1);
    }

    public void onBreak(Player player) {
        add(player, "rw_broken", 1);
    }

    public void onSpend(Player player, int coins) {
        if (coins > 0) {
            add(player, "rw_spent", coins);
        }
    }

    private void add(Player player, String name, int amount) {
        AmmoManager a = plugin.getAmmo();
        long now = (long) a.getCounter(player, name) + amount;
        a.setCounter(player, name, (int) Math.min(Integer.MAX_VALUE, now));
    }

    // ------------------------------------------------------------------
    //  Tipologie, livelli e ricompense
    // ------------------------------------------------------------------

    private static boolean known(String type) {
        for (String t : TYPES) {
            if (t.equals(type)) {
                return true;
            }
        }
        return false;
    }

    private int levelsOf(String type) {
        int def = type.equals("platinum") ? 20 : 100;
        return Math.max(1, plugin.getConfig().getInt(GUI + "types." + type + ".levels", def));
    }

    private int expPerLevel() {
        return Math.max(1, plugin.getConfig().getInt(GUI + "exp-per-level", 1500));
    }

    private int expPerClaim() {
        return Math.max(0, plugin.getConfig().getInt(GUI + "exp-per-claim", 500));
    }

    private int maxRuns() {
        return Math.max(1, plugin.getConfig().getInt("mines-gui.max-runs", 10));
    }

    private int platinumMines(Player player) {
        int n = 0;
        int max = maxRuns();
        for (int mine = 1; mine <= 100; mine++) {
            if (plugin.getAmmo().getCounter(player, "mine_runs_" + mine) >= max) {
                n++;
            }
        }
        return n;
    }

    /** Quanto ha fatto il giocatore in questa tipologia (per il battlepass sono gli exp). */
    long stat(Player player, String type) {
        AmmoManager a = plugin.getAmmo();
        return switch (type) {
            case "broken" -> a.getCounter(player, "rw_broken");
            case "hits" -> a.getCounter(player, "rw_hits");
            case "bc_earned" -> a.getCounter(player, "rw_bcin");
            case "bc_spent" -> a.getCounter(player, "rw_bcout");
            case "coin_earned" -> a.getCounter(player, "rw_coinin");
            case "coin_spent" -> a.getCounter(player, "rw_spent");
            case "platinum" -> platinumMines(player);
            case "play" -> a.getCounter(player, "rw_play");
            case "pass" -> a.getCounter(player, "rw_exp");
            default -> 0;
        };
    }

    /** Quanto bisogna aver fatto in tutto per completare il livello n (cresce sempre piu' in fretta). */
    long threshold(String type, int level) {
        if (level <= 0) {
            return 0;
        }
        if (type.equals("pass")) {
            return (long) level * expPerLevel();
        }
        var c = plugin.getConfig();
        double base = c.getDouble(GUI + "types." + type + ".base", 100);
        double growth = c.getDouble(GUI + "types." + type + ".growth", 0.05);
        return Math.max(1, Math.round(base * (level + growth * level * level)));
    }

    /** Livello raggiunto (completato, anche se la ricompensa non e' stata ritirata). */
    int reached(Player player, String type) {
        int max = levelsOf(type);
        long have = stat(player, type);
        int level = 0;
        while (level < max && have >= threshold(type, level + 1)) {
            level++;
        }
        return level;
    }

    // ---- livelli ritirati (ognuno si ritira da solo, in qualsiasi ordine) ----

    private NamespacedKey claimKey(String type) {
        return new NamespacedKey(plugin, "rwc_" + type);
    }

    BitSet claims(Player player, String type) {
        BitSet bits = new BitSet();
        String raw = player.getPersistentDataContainer().get(claimKey(type), PersistentDataType.STRING);
        if (raw != null && !raw.isBlank()) {
            for (String part : raw.split(",")) {
                try {
                    bits.set(Integer.parseInt(part.trim()));
                } catch (NumberFormatException ignored) {
                    // voce non valida
                }
            }
        }
        return bits;
    }

    private void saveClaims(Player player, String type, BitSet bits) {
        StringBuilder sb = new StringBuilder();
        for (int i = bits.nextSetBit(0); i >= 0; i = bits.nextSetBit(i + 1)) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(i);
        }
        player.getPersistentDataContainer().set(claimKey(type), PersistentDataType.STRING, sb.toString());
    }

    /** Le ricompense del livello n (anche 3 o 4 insieme): coin, blockcoin e assegni sempre piu' ricchi. */
    List<Reward> rewardsOf(String type, int level) {
        int scaled = (int) Math.max(1, Math.ceil(level * 100.0 / levelsOf(type)));
        double mult = plugin.getConfig().getDouble(GUI + "types." + type + ".multiplier", 1.0);
        int prev = 0;
        List<Reward> out = new ArrayList<>();
        for (Map<?, ?> tier : plugin.getConfig().getMapList(GUI + "tiers")) {
            int upto = num(tier.get("upto"), 100);
            out.clear();
            if (tier.get("items") instanceof List<?> items) {
                for (Object o : items) {
                    if (!(o instanceof Map<?, ?> map)) {
                        continue;
                    }
                    String kind = String.valueOf(map.get("type") == null ? "coin" : map.get("type")).toLowerCase(Locale.ROOT);
                    int base = num(map.get("amount"), 1);
                    int step = num(map.get("step"), 0);
                    int amount = Math.max(1, base + step * Math.max(0, scaled - prev - 1));
                    if (mult != 1.0) {
                        amount = Math.max(1, (int) Math.round(amount * mult));
                    }
                    out.add(new Reward(kind, amount));
                }
            }
            if (scaled <= upto) {
                break;
            }
            prev = upto;
        }
        if (out.isEmpty()) {
            out.add(new Reward("coin", 100));
        }
        return out;
    }

    private static int num(Object o, int def) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return o == null ? def : Integer.parseInt(String.valueOf(o));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private String rewardName(Reward r) {
        var c = plugin.getConfig();
        String name = c.getString(GUI + "reward-names." + r.type(), r.type());
        return name.replace("%amount%", String.valueOf(r.amount()));
    }

    /** Tutte le ricompense su una riga: "100 coin, 20 blockcoin, ...". */
    private String rewardNames(List<Reward> list) {
        List<String> names = new ArrayList<>();
        for (Reward r : list) {
            names.add(rewardName(r));
        }
        return String.join(", ", names);
    }

    private void give(Player player, List<Reward> list) {
        for (Reward r : list) {
            give(player, r);
        }
    }

    /** Da' la ricompensa al giocatore (coin, blockcoin oppure assegni nell'inventario). */
    private void give(Player player, Reward r) {
        AmmoManager a = plugin.getAmmo();
        switch (r.type()) {
            case "coin" -> a.setCoin(player, a.getCoin(player) + r.amount());
            case "blockcoin" -> a.setBlockCoin(player, a.getBlockCoin(player) + r.amount());
            default -> {
                if (r.type().startsWith("cheque_")) {
                    ItemStack item = cheque(r.type().substring(7), r.amount());
                    if (item != null) {
                        for (ItemStack left : player.getInventory().addItem(item).values()) {
                            player.getWorld().dropItemNaturally(player.getLocation(), left);
                        }
                    }
                }
            }
        }
        a.refreshMain(player);
    }

    // ------------------------------------------------------------------
    //  Ricompensa giornaliera
    // ------------------------------------------------------------------

    private int dailyMinutes() {
        return Math.max(1, plugin.getConfig().getInt(GUI + "daily.minutes", 30));
    }

    private int dailyCoins() {
        return Math.max(0, plugin.getConfig().getInt(GUI + "daily.coin", 1000));
    }

    private boolean dailyClaimedToday(Player player) {
        return plugin.getAmmo().getCounter(player, "rw_dayclaim") == (int) today();
    }

    private int minutesToday(Player player) {
        AmmoManager a = plugin.getAmmo();
        return a.getCounter(player, "rw_daydate") == (int) today() ? a.getCounter(player, "rw_daymin") : 0;
    }

    private boolean dailyReady(Player player) {
        return !dailyClaimedToday(player) && minutesToday(player) >= dailyMinutes() && !isAfk(player);
    }

    /** Giorni davvero riscattati (non quelli giocati). */
    private int daysClaimed(Player player) {
        return plugin.getAmmo().getCounter(player, "rw_days");
    }

    private void claimDaily(Player player, Holder holder) {
        AmmoManager a = plugin.getAmmo();
        if (dailyClaimedToday(player)) {
            plugin.send(player, "daily-already");
            return;
        }
        if (isAfk(player)) {
            plugin.send(player, "daily-afk");
            return;
        }
        int left = dailyMinutes() - minutesToday(player);
        if (left > 0) {
            plugin.send(player, "daily-wait", "%minutes%", String.valueOf(left));
            return;
        }
        a.setCounter(player, "rw_dayclaim", (int) today());
        a.setCounter(player, "rw_days", daysClaimed(player) + 1);
        give(player, new Reward("coin", dailyCoins()));
        plugin.send(player, "daily-claimed", "%coin%", String.valueOf(dailyCoins()),
                "%day%", String.valueOf(daysClaimed(player)));
        openDaily(player, holder.mainPage, false);
    }

    // ------------------------------------------------------------------
    //  Ritirare un livello
    // ------------------------------------------------------------------

    private void claimLevel(Player player, Holder holder, String type, int level) {
        BitSet bits = claims(player, type);
        if (level < 1 || level > reached(player, type) || bits.get(level)) {
            plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.error");
            return;
        }
        bits.set(level);
        saveClaims(player, type, bits);
        List<Reward> rw = rewardsOf(type, level);
        give(player, rw);
        plugin.send(player, "reward-claimed", "%level%", String.valueOf(level), "%reward%", rewardNames(rw));
        plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.claim");
        if (!type.equals("pass") && expPerClaim() > 0) {
            int before = reached(player, "pass");
            add(player, "rw_exp", expPerClaim());
            plugin.send(player, "reward-exp", "%exp%", String.valueOf(expPerClaim()),
                    "%level%", String.valueOf(reached(player, "pass")), "%before%", String.valueOf(before));
        }
        openLevels(player, type, holder.mainPage, holder.page, false);
    }

    // ------------------------------------------------------------------
    //  Testi e oggetti della GUI
    // ------------------------------------------------------------------

    private String typeName(String type) {
        return plugin.getConfig().getString(GUI + "types." + type + ".name", type);
    }

    private String[] repl(Player player, String type) {
        List<String> r = new ArrayList<>();
        r.add("%coin%");
        r.add(String.valueOf(plugin.getAmmo().getCoin(player)));
        r.add("%blockcoin%");
        r.add(String.valueOf(plugin.getAmmo().getBlockCoin(player)));
        r.add("%type%");
        r.add(BlockMania.stripCodes(typeName(type)));
        if (type.equals("daily")) {
            int have = Math.min(dailyMinutes(), minutesToday(player));
            r.addAll(List.of("%day%", String.valueOf(daysClaimed(player)),
                    "%minutes%", String.valueOf(have),
                    "%need%", String.valueOf(dailyMinutes()),
                    "%left%", String.valueOf(Math.max(0, dailyMinutes() - have)),
                    "%coin_reward%", String.valueOf(dailyCoins())));
        } else {
            int levels = levelsOf(type);
            int reach = reached(player, type);
            int claimed = claims(player, type).cardinality();
            long have = stat(player, type);
            r.addAll(List.of("%level%", String.valueOf(reach),
                    "%max%", String.valueOf(levels),
                    "%claimed%", String.valueOf(claimed),
                    "%unclaimed%", String.valueOf(Math.max(0, reach - claimed)),
                    "%have%", String.valueOf(have),
                    "%next%", reach >= levels ? "-" : String.valueOf(threshold(type, reach + 1)),
                    "%left%", reach >= levels ? "0" : String.valueOf(Math.max(0, threshold(type, reach + 1) - have)),
                    "%exp%", String.valueOf(plugin.getAmmo().getCounter(player, "rw_exp")),
                    "%exp_level%", String.valueOf(expPerLevel())));
        }
        return r.toArray(new String[0]);
    }

    private ItemStack pane(int cmd, String name, List<String> lore, String[] r, boolean hide) {
        Material m = Material.matchMaterial(plugin.getConfig().getString(GUI + "invisible.material", "GLASS_PANE"));
        ItemStack stack = new ItemStack(m == null || !m.isItem() ? Material.GLASS_PANE : m);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(cmd);
        if (hide) {
            meta.setHideTooltip(true);
        } else {
            meta.displayName(plugin.text(name, r));
            if (!lore.isEmpty()) {
                List<Component> lines = new ArrayList<>();
                for (String line : lore) {
                    lines.add(plugin.text(line, r));
                }
                meta.lore(lines);
            }
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private int invisibleCmd() {
        return plugin.getConfig().getInt(GUI + "invisible.cmd", 999);
    }

    private int iconCmd(String key, int def) {
        return plugin.getConfig().getInt(GUI + "cmds." + key, def);
    }

    private List<String> loreOf(String path, List<String> def) {
        var c = plugin.getConfig();
        return c.isList(path) ? c.getStringList(path) : def;
    }

    private List<Integer> slots(String name, int... def) {
        var c = plugin.getConfig();
        List<Integer> out = new ArrayList<>();
        if (c.isList(GUI + "slots." + name)) {
            for (Integer i : c.getIntegerList(GUI + "slots." + name)) {
                if (i >= 0 && i < SIZE) {
                    out.add(i);
                }
            }
        } else {
            for (int i : def) {
                out.add(i);
            }
        }
        return out;
    }

    private void put(Inventory inv, Holder holder, int slot, ItemStack item, String action) {
        inv.setItem(slot, item);
        if (action != null) {
            holder.actions.put(slot, action);
        } else {
            holder.actions.remove(slot);
        }
    }

    private void fillBase(Inventory inv, Holder holder, String[] r, boolean backClose) {
        var c = plugin.getConfig();
        holder.actions.clear();
        String closeName = backClose ? c.getString(GUI + "back.name", "&cindietro")
                : c.getString(GUI + "close.name", "&cchiudi");
        boolean sub = holder.kind.equals("levels");
        for (int slot : slots(sub ? "close-sub" : "close", sub ? new int[]{10, 11} : new int[]{0, 1})) {
            put(inv, holder, slot, pane(invisibleCmd(), closeName, List.of(), r, false), backClose ? "back" : "close");
        }
    }

    // ------------------------------------------------------------------
    //  Pagina principale
    // ------------------------------------------------------------------

    private List<Map<?, ?>> pages() {
        List<Map<?, ?>> list = plugin.getConfig().getMapList(GUI + "pages");
        return list;
    }

    private int pageCount() {
        return Math.max(1, pages().size());
    }

    private String pageImage(int page) {
        List<Map<?, ?>> p = pages();
        if (page >= 0 && page < p.size() && p.get(page).get("image") != null) {
            return String.valueOf(p.get(page).get("image"));
        }
        return plugin.getConfig().getString(GUI + "image-main", "㴀");
    }

    private List<String> pageTypes(int page) {
        List<String> out = new ArrayList<>();
        List<Map<?, ?>> p = pages();
        if (page >= 0 && page < p.size() && p.get(page).get("types") instanceof List<?> l) {
            for (Object o : l) {
                out.add(String.valueOf(o).toLowerCase(Locale.ROOT));
            }
        } else {
            for (int i = 0; i < 10 && i < TYPES.length; i++) {
                out.add(TYPES[i]);
            }
        }
        return out;
    }

    /** Riga (1-5) e gruppo (0 sinistra, 1 destra) della tipologia numero i. */
    private static int rowOf(int i) {
        return 1 + (i % 5);
    }

    private static int groupOf(int i) {
        return i / 5;
    }

    private void fillMain(Inventory inv, Holder holder, Player player) {
        var c = plugin.getConfig();
        String[] r = repl(player, "broken");
        fillBase(inv, holder, r, false);
        int pages = pageCount();
        for (int slot : slots("info", 7, 8)) {
            put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "info.name", "&6informazioni"),
                    loreOf(GUI + "info.lore", List.of()), r, false), null);
        }
        String[] pr = {"%page%", String.valueOf(holder.page + 1), "%pages%", String.valueOf(pages)};
        for (int slot : slots("main-bar", 3, 4, 5)) {
            put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "page.name", "&7pagina &f%page%&8/&f%pages%"),
                    List.of(), pr, false), null);
        }
        if (holder.page > 0) {
            for (int slot : slots("main-prev", 2)) {
                put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "page-prev.name", "&7pagina precedente"),
                        List.of(), pr, false), "page:-1");
            }
        }
        if (holder.page < pages - 1) {
            for (int slot : slots("main-next", 6)) {
                put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "page-next.name", "&7pagina successiva"),
                        List.of(), pr, false), "page:1");
            }
        }
        List<String> types = pageTypes(holder.page);
        for (int i = 0; i < types.size() && i < 10; i++) {
            String type = types.get(i);
            if (!known(type)) {
                continue;
            }
            String[] tr = repl(player, type);
            boolean daily = type.equals("daily");
            String name = daily ? dailyName(player) : c.getString(GUI + "types." + type + ".name", type);
            List<String> lore = daily ? loreOf(GUI + "daily.states." + dailyKey(player) + ".lore", List.of())
                    : loreOf(GUI + "types." + type + ".lore", List.of());
            int row = rowOf(i);
            int firstCol = groupOf(i) == 0 ? 0 : 5;
            for (int col = firstCol; col < firstCol + 4; col++) {
                put(inv, holder, row * 9 + col, pane(invisibleCmd(), name, lore, tr, false), "type:" + type);
            }
        }
    }

    private String dailyKey(Player player) {
        return dailyClaimedToday(player) ? "claimed" : dailyReady(player) ? "ready" : "wait";
    }

    private String dailyName(Player player) {
        return plugin.getConfig().getString(GUI + "daily.states." + dailyKey(player) + ".name", "&fricompensa giornaliera");
    }

    // ------------------------------------------------------------------
    //  Percorso dei livelli (e battlepass)
    // ------------------------------------------------------------------

    private int perPage(String type) {
        return type.equals("pass") ? 7 : PATH.length;
    }

    private int pagesOf(String type) {
        return Math.max(1, (levelsOf(type) + perPage(type) - 1) / perPage(type));
    }

    private int slotOf(String type, int index) {
        if (type.equals("pass")) {
            return PASS_FIRST + index;
        }
        return PATH[index][0] * 9 + PATH[index][1];
    }

    private String[] levelRepl(Player player, String type, int level, String[] general) {
        BitSet bits = claims(player, type);
        int reach = reached(player, type);
        long have = stat(player, type);
        long need = threshold(type, level);
        String state = bits.get(level) ? "claimed" : level <= reach ? "ready" : "locked";
        String[] mine = {"%number%", String.valueOf(level),
                "%need%", String.valueOf(need),
                "%missing%", String.valueOf(Math.max(0, need - have)),
                "%reward%", rewardNames(rewardsOf(type, level)),
                "%state%", state};
        String[] out = new String[mine.length + general.length];
        System.arraycopy(mine, 0, out, 0, mine.length);
        System.arraycopy(general, 0, out, mine.length, general.length);
        return out;
    }

    private void fillLevels(Inventory inv, Holder holder, Player player) {
        var c = plugin.getConfig();
        String type = holder.type;
        String[] general = repl(player, type);
        fillBase(inv, holder, general, true);
        int levels = levelsOf(type);
        int per = perPage(type);
        int pages = pagesOf(type);
        holder.page = Math.max(0, Math.min(holder.page, pages - 1));
        BitSet bits = claims(player, type);
        int reach = reached(player, type);

        for (int slot : slots("info-sub", 15, 16)) {
            put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "types." + type + ".name", type),
                    loreOf(GUI + (type.equals("pass") ? "pass.info-lore" : "level-info-lore"), List.of()), general, false), null);
        }
        for (int slot : slots("level-bar", 12, 13, 14)) {
            put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "types." + type + ".name", type),
                    loreOf(GUI + "types." + type + ".lore", List.of()), general, false), null);
        }
        String[] pr = {"%page%", String.valueOf(holder.page + 1), "%pages%", String.valueOf(pages)};
        for (int slot : slots("nav-bar", 48, 49, 50)) {
            put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "page.name", "&7pagina &f%page%&8/&f%pages%"),
                    List.of(), pr, false), null);
        }
        if (holder.page > 0) {
            for (int slot : slots("nav-prev", 47)) {
                put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "page-prev.name", "&7pagina precedente"),
                        List.of(), pr, false), "scroll:-1");
            }
        }
        if (holder.page < pages - 1) {
            for (int slot : slots("nav-next", 51)) {
                put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "page-next.name", "&7pagina successiva"),
                        List.of(), pr, false), "scroll:1");
            }
        }
        for (int i = 0; i < per; i++) {
            int level = holder.page * per + i + 1;
            if (level > levels) {
                break;
            }
            boolean claimed = bits.get(level);
            boolean ready = !claimed && level <= reach;
            String key = claimed ? "claimed" : ready ? "ready" : "locked";
            String[] r = levelRepl(player, type, level, general);
            int cmd = claimed ? iconCmd("barrel-empty", 514) : ready ? iconCmd("barrel-gold", 511) : iconCmd("chest", 510);
            String name = c.getString(GUI + "level-states." + key + ".name", "&flivello %number%");
            List<String> lore = new ArrayList<>();
            for (String line : loreOf(GUI + "level-states." + key + ".lore", List.of())) {
                if (line.contains("%reward_lines%")) {
                    for (Reward rw : rewardsOf(type, level)) {
                        lore.add(line.replace("%reward_lines%", rewardName(rw)));
                    }
                } else {
                    lore.add(line);
                }
            }
            put(inv, holder, slotOf(type, i), pane(cmd, name, lore, r, false), ready ? "claim:" + level : null);
        }
    }

    // ------------------------------------------------------------------
    //  Giornaliera
    // ------------------------------------------------------------------

    private void fillDaily(Inventory inv, Holder holder, Player player) {
        var c = plugin.getConfig();
        String[] r = repl(player, "daily");
        fillBase(inv, holder, r, true);
        for (int slot : slots("info", 7, 8)) {
            put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "info.name", "&6informazioni"),
                    loreOf(GUI + "daily.info-lore", List.of()), r, false), null);
        }
        String key = dailyKey(player);
        String name = dailyName(player);
        List<String> lore = loreOf(GUI + "daily.states." + key + ".lore", List.of());
        List<Integer> panel = new ArrayList<>();
        for (int row = 1; row <= 5; row++) {
            for (int col = 2; col <= 6; col++) {
                panel.add(row * 9 + col);
            }
        }
        for (int slot : panel) {
            put(inv, holder, slot, pane(invisibleCmd(), name, lore, r, false), "daily");
        }
    }

    // ------------------------------------------------------------------
    //  Titoli (immagine + tacchette + numeri)
    // ------------------------------------------------------------------

    private static String letters(String text, char base) {
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch > ' ' && ch < 0x7F) {
                sb.append((char) (base + ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Larghezza in pixel di un testo scritto con le lettere del pack (senza l'ultimo pixel vuoto). */
    private static int textAdvance(String text) {
        int total = 0;
        for (char ch : text.toCharArray()) {
            int w = ch == ' ' ? 4 : FontMetrics.width(ch);
            total += w < 0 ? 6 : w;
        }
        return total;
    }

    private static int textWidth(String text) {
        return Math.max(0, textAdvance(text) - 1);
    }

    private GuiTitle newTitle(String image) {
        var c = plugin.getConfig();
        String raw = "&f{shift:-15}" + image;
        // l'avanzamento si misura sempre come la prima immagine (stessa larghezza per tutte)
        String measure = "&f{shift:-15}" + c.getString(GUI + "image-main", "㴀");
        GuiTitle t = new GuiTitle(plugin);
        t.text(raw, c.getString(GUI + "title.font", ""), GUI + "title", measure);
        return t;
    }

    private String font() {
        return plugin.getConfig().getString(GUI + "title.font", "");
    }

    /** Scrive un testo con le lettere del pack, centrato su centerX (pixel del titolo), con l'ombra. */
    private void centeredText(GuiTitle t, String text, char base, char shadowBase, String color, int centerX) {
        int start = centerX - textWidth(text) / 2;
        if (plugin.getConfig().getBoolean(GUI + "text-shadow", true)) {
            t.at(start + 1, GuiTitle.shadowCode(plugin) + letters(text, shadowBase), font(), textAdvance(text));
        }
        t.at(start, color + letters(text, base), font(), textAdvance(text));
    }

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
            if (ch < 128 && Character.isLetterOrDigit(ch)) {
                sb.append((char) (base + ch));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Testo piccolo (minuscolo, 5 pixel) centrato su centerX con l'ombra. */
    private void centeredTiny(GuiTitle t, String text, int centerX) {
        int adv = tinyAdvance(text);
        int start = centerX - Math.max(0, adv - 1) / 2;
        if (plugin.getConfig().getBoolean(GUI + "text-shadow", true)) {
            t.at(start + 1, GuiTitle.shadowCode(plugin) + tinyLetters(text, TINY_SHADOW_BASE), font(), adv);
        }
        t.at(start, "&f" + tinyLetters(text, TINY_BASE), font(), adv);
    }

    /** Numero pagina (1/7) nella barra in basso dei percorsi. */
    private void navNumber(GuiTitle t, int page, int pages) {
        String plain = (page + 1) + "/" + pages;
        int center = plugin.getConfig().getInt(GUI + "page-center", 73);
        int start = center - GuiTitle.widthOf(plugin, "nav", plain) / 2;
        t.number(start, "nav", "nav-shadow", plugin.getConfig().getBoolean(GUI + "text-shadow", true), plain, font());
    }

    private void numberAt(GuiTitle t, int row, String text, int centerX) {
        String set = "lvl" + row;
        int start = centerX - GuiTitle.widthOf(plugin, set, text) / 2;
        t.number(start, set, set + "-shadow", plugin.getConfig().getBoolean(GUI + "text-shadow", true), text, font());
    }

    /** n tacchette (0-7) dalla x indicata (pixel dell'immagine) alla riga 1-5: prime rosse, di mezzo gialle, ultime verdi. */
    private void ticks(GuiTitle t, int row, int xImage, int n) {
        if (n <= 0) {
            return;
        }
        StringBuilder sb = new StringBuilder("&f");
        int color = n <= 2 ? 0 : n <= 5 ? 1 : 2;   // tutte le tacchette hanno lo stesso colore
        for (int k = 0; k < Math.min(7, n); k++) {
            sb.append((char) (TICK_BASE + 0x10 * color + (row - 1)));
        }
        t.at(xImage + IMG, sb.toString(), font(), 4 * Math.min(7, n));
    }

    private int tickCount(Player player, String type) {
        if (type.equals("daily")) {
            if (dailyClaimedToday(player)) {
                return 7;
            }
            return Math.min(7, minutesToday(player) * 7 / dailyMinutes());
        }
        int levels = levelsOf(type);
        return Math.min(7, reached(player, type) * 7 / levels);
    }

    private Component titleMain(Player player, int page) {
        var c = plugin.getConfig();
        GuiTitle t = newTitle(pageImage(page));
        List<String> types = pageTypes(page);
        for (int i = 0; i < types.size() && i < 10; i++) {
            String type = types.get(i);
            if (!known(type)) {
                continue;
            }
            int row = rowOf(i);
            int group = groupOf(i);
            ticks(t, row, group == 0 ? 35 : 125, tickCount(player, type));
            int center = (group == 0 ? 76 : 166) + IMG;
            if (type.equals("daily")) {
                boolean ready = dailyReady(player);
                centeredText(t, ready ? c.getString(GUI + "daily.yes", "SI") : c.getString(GUI + "daily.no", "NO"),
                        CELL_BASE, CELL_SHADOW_BASE, ready ? "&a" : "&c", center - 2);
            } else {
                numberAt(t, row, String.valueOf(reached(player, type)), center);
            }
        }
        // numero della pagina nella barra in alto (1/2)
        String plain = (page + 1) + "/" + pageCount();
        int center = c.getInt(GUI + "page-center", 73);
        int start = center - GuiTitle.widthOf(plugin, "main", plain) / 2;
        t.number(start, "main", "main-shadow", c.getBoolean(GUI + "text-shadow", true), plain, font());
        return t.build();
    }

    private Component titleLevels(Player player, String type, int page) {
        var c = plugin.getConfig();
        GuiTitle t = newTitle(c.getString(GUI + (type.equals("pass") ? "image-pass" : "image-levels"), type.equals("pass") ? "㴂" : "㴁"));
        String text = c.getString(GUI + "bar-text.level", "livello %n%").replace("%n%", String.valueOf(reached(player, type)))
                .toLowerCase(Locale.ROOT);
        centeredTiny(t, text, c.getInt(GUI + "bar-center", 73));
        navNumber(t, page, pagesOf(type));
        return t.build();
    }

    private Component titleDaily(Player player) {
        var c = plugin.getConfig();
        GuiTitle t = newTitle(c.getString(GUI + (dailyClaimedToday(player) || !dailyReady(player) ? "image-daily-wait" : "image-daily-ready"), "㴄"));
        String text = c.getString(GUI + "bar-text.daily", "giorno %n%").replace("%n%", String.valueOf(daysClaimed(player)))
                .toLowerCase(Locale.ROOT);
        centeredTiny(t, text, c.getInt(GUI + "bar-center", 73));
        return t.build();
    }

    // ------------------------------------------------------------------
    //  Aprire e usare
    // ------------------------------------------------------------------

    public void open(Player player) {
        openMain(player, 0, true);
    }

    public void openMain(Player player, int page, boolean sound) {
        Holder holder = new Holder("main");
        holder.page = Math.max(0, Math.min(page, pageCount() - 1));
        holder.mainPage = holder.page;
        Inventory inv = Bukkit.createInventory(holder, SIZE, titleMain(player, holder.page));
        holder.inventory = inv;
        fillMain(inv, holder, player);
        player.openInventory(inv);
        if (sound) {
            plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.open");
        }
    }

    /** Il percorso di una tipologia. page < 0 = la pagina dove si trova il livello raggiunto. */
    public void openLevels(Player player, String type, int mainPage, int page, boolean sound) {
        if (!known(type) || type.equals("daily")) {
            openMain(player, mainPage, sound);
            return;
        }
        Holder holder = new Holder("levels");
        holder.type = type;
        holder.mainPage = mainPage;
        int reach = reached(player, type);
        holder.page = page >= 0 ? page : Math.max(0, (Math.max(1, reach) - 1) / perPage(type));
        holder.page = Math.min(holder.page, pagesOf(type) - 1);
        Inventory inv = Bukkit.createInventory(holder, SIZE, titleLevels(player, type, holder.page));
        holder.inventory = inv;
        fillLevels(inv, holder, player);
        player.openInventory(inv);
        if (sound) {
            plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.open");
        }
    }

    public void openDaily(Player player, int mainPage, boolean sound) {
        Holder holder = new Holder("daily");
        holder.type = "daily";
        holder.mainPage = mainPage;
        Inventory inv = Bukkit.createInventory(holder, SIZE, titleDaily(player));
        holder.inventory = inv;
        fillDaily(inv, holder, player);
        player.openInventory(inv);
        if (sound) {
            plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.open");
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof Holder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || !(clicked.getHolder() instanceof Holder)) {
            return;
        }
        String action = holder.actions.get(event.getSlot());
        if (action == null) {
            return;
        }
        plugin.guiClick(player);
        if (action.equals("close")) {
            player.closeInventory();
        } else if (action.equals("back")) {
            openMain(player, holder.mainPage, false);
        } else if (action.equals("daily")) {
            claimDaily(player, holder);
        } else if (action.startsWith("page:")) {
            try {
                openMain(player, holder.page + Integer.parseInt(action.substring(5)), false);
            } catch (NumberFormatException ignored) {
                // azione non valida
            }
        } else if (action.startsWith("type:")) {
            String type = action.substring(5);
            if (type.equals("daily")) {
                openDaily(player, holder.page, false);
            } else {
                openLevels(player, type, holder.page, -1, false);
            }
        } else if (action.startsWith("scroll:")) {
            try {
                openLevels(player, holder.type, holder.mainPage, holder.page + Integer.parseInt(action.substring(7)), false);
            } catch (NumberFormatException ignored) {
                // azione non valida
            }
        } else if (action.startsWith("claim:")) {
            try {
                claimLevel(player, holder, holder.type, Integer.parseInt(action.substring(6)));
            } catch (NumberFormatException ignored) {
                // azione non valida
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    //  Comandi admin: /bm ricompense exp ... e /bm ricompense livello ...
    // ------------------------------------------------------------------

    public List<String> adminTypes() {
        List<String> out = new ArrayList<>();
        for (String t : TYPES) {
            if (!t.equals("daily")) {
                out.add(t);
            }
        }
        return out;
    }

    /** Imposta il livello raggiunto portando il contatore al valore giusto. */
    void setReached(Player target, String type, int level) {
        AmmoManager a = plugin.getAmmo();
        int max = levelsOf(type);
        int n = Math.max(0, Math.min(max, level));
        long value = threshold(type, n);
        int v = (int) Math.min(Integer.MAX_VALUE, value);
        switch (type) {
            case "broken" -> a.setCounter(target, "rw_broken", v);
            case "hits" -> a.setCounter(target, "rw_hits", v);
            case "bc_earned" -> a.setCounter(target, "rw_bcin", v);
            case "bc_spent" -> a.setCounter(target, "rw_bcout", v);
            case "coin_earned" -> a.setCounter(target, "rw_coinin", v);
            case "coin_spent" -> a.setCounter(target, "rw_spent", v);
            case "play" -> a.setCounter(target, "rw_play", v);
            case "pass" -> a.setCounter(target, "rw_exp", v);
            case "platinum" -> {
                for (int mine = 1; mine <= 100; mine++) {
                    a.setCounter(target, "mine_runs_" + mine, mine <= n ? maxRuns() : 0);
                }
            }
            default -> {
                return;
            }
        }
        // i livelli oltre quello nuovo tornano da ritirare
        BitSet bits = claims(target, type);
        bits.clear(n + 1, 1024);
        saveClaims(target, type, bits);
    }

    /** /bm ricompense <exp|livello|tipi> ...  (args[0] = "ricompense"). */
    public void admin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("blockmania.stats")) {
            plugin.sendCommand(sender, "no-permission");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "tipi", "types" -> plugin.sendCommand(sender, "rw-types", "%types%", String.join(", ", adminTypes()));
            case "exp" -> adminExp(sender, args);
            case "livello", "level", "lvl" -> adminLevel(sender, args);
            default -> plugin.sendCommand(sender, "rw-usage");
        }
    }

    private Player targetAt(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player p = Bukkit.getPlayerExact(args[index]);
            if (p == null) {
                plugin.sendCommand(sender, "player-not-found", "%player%", args[index]);
            }
            return p;
        }
        if (sender instanceof Player p) {
            return p;
        }
        plugin.sendCommand(sender, "rw-usage");
        return null;
    }

    private void adminExp(CommandSender sender, String[] args) {
        if (args.length < 3) {
            plugin.sendCommand(sender, "rw-usage");
            return;
        }
        String action = args[2].toLowerCase(Locale.ROOT);
        boolean noAmount = action.equals("get") || action.equals("see") || action.equals("reset");
        int amount = 0;
        if (!noAmount) {
            if (args.length < 4) {
                plugin.sendCommand(sender, "rw-usage");
                return;
            }
            try {
                amount = Math.max(0, Integer.parseInt(args[3]));
            } catch (NumberFormatException ex) {
                plugin.sendCommand(sender, "rw-usage");
                return;
            }
        }
        Player target = targetAt(sender, args, noAmount ? 3 : 4);
        if (target == null) {
            return;
        }
        AmmoManager a = plugin.getAmmo();
        int now = a.getCounter(target, "rw_exp");
        switch (action) {
            case "give", "add" -> a.setCounter(target, "rw_exp", (int) Math.min(Integer.MAX_VALUE, (long) now + amount));
            case "take", "remove" -> a.setCounter(target, "rw_exp", Math.max(0, now - amount));
            case "set" -> a.setCounter(target, "rw_exp", amount);
            case "reset" -> a.setCounter(target, "rw_exp", 0);
            case "get", "see" -> {
            }
            default -> {
                plugin.sendCommand(sender, "rw-usage");
                return;
            }
        }
        plugin.sendCommand(sender, "rw-value", "%player%", target.getName(), "%what%", "exp",
                "%value%", a.getCounter(target, "rw_exp") + " (livello pass " + reached(target, "pass") + ")");
    }

    private void adminLevel(CommandSender sender, String[] args) {
        if (args.length < 4) {
            plugin.sendCommand(sender, "rw-usage");
            return;
        }
        String type = args[2].toLowerCase(Locale.ROOT);
        if (!known(type) || type.equals("daily")) {
            plugin.sendCommand(sender, "rw-types", "%types%", String.join(", ", adminTypes()));
            return;
        }
        String action = args[3].toLowerCase(Locale.ROOT);
        boolean noAmount = action.equals("get") || action.equals("see") || action.equals("reset");
        int amount = 0;
        if (!noAmount) {
            if (args.length < 5) {
                plugin.sendCommand(sender, "rw-usage");
                return;
            }
            try {
                amount = Math.max(0, Integer.parseInt(args[4]));
            } catch (NumberFormatException ex) {
                plugin.sendCommand(sender, "rw-usage");
                return;
            }
        }
        Player target = targetAt(sender, args, noAmount ? 4 : 5);
        if (target == null) {
            return;
        }
        int now = reached(target, type);
        switch (action) {
            case "add", "give" -> setReached(target, type, now + amount);
            case "remove", "take" -> setReached(target, type, now - amount);
            case "set" -> setReached(target, type, amount);
            case "reset" -> setReached(target, type, 0);
            case "get", "see" -> {
            }
            default -> {
                plugin.sendCommand(sender, "rw-usage");
                return;
            }
        }
        plugin.sendCommand(sender, "rw-value", "%player%", target.getName(),
                "%what%", BlockMania.stripCodes(typeName(type)),
                "%value%", "livello " + reached(target, type) + "/" + levelsOf(type));
    }

    public List<String> adminComplete(String[] args) {
        // args[0] = "ricompense"
        List<String> out = new ArrayList<>();
        if (args.length == 2) {
            out.addAll(List.of("exp", "livello", "tipi"));
        } else if (args.length == 3 && args[1].equalsIgnoreCase("exp")) {
            out.addAll(List.of("give", "take", "set", "get", "reset"));
        } else if (args.length == 3 && (args[1].equalsIgnoreCase("livello") || args[1].equalsIgnoreCase("level"))) {
            out.addAll(adminTypes());
        } else if (args.length == 4 && (args[1].equalsIgnoreCase("livello") || args[1].equalsIgnoreCase("level"))) {
            out.addAll(List.of("add", "remove", "set", "get", "reset"));
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }

    // ------------------------------------------------------------------
    //  Assegni
    // ------------------------------------------------------------------

    private int chequeCmd(String kind) {
        int def = 3;
        for (int i = 0; i < CHEQUES.length; i++) {
            if (CHEQUES[i].equals(kind)) {
                def = 3 + i;
            }
        }
        return plugin.getConfig().getInt("cheques." + kind + ".cmd", def);
    }

    /** Un assegno (bastoncino col suo modello). kind: standardball, doubleball, ghostball, explosionball, coin, blockcoin, power. */
    public ItemStack cheque(String kind, int amount) {
        kind = kind.toLowerCase(Locale.ROOT);
        boolean known = false;
        for (String k : CHEQUES) {
            known |= k.equals(kind);
        }
        if (!known) {
            return null;
        }
        var c = plugin.getConfig();
        ItemStack stack = new ItemStack(Material.STICK, Math.max(1, Math.min(64, amount)));
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(chequeCmd(kind));
        meta.displayName(plugin.text(c.getString("cheques." + kind + ".name", "&eassegno " + kind)));
        List<Component> lore = new ArrayList<>();
        for (String line : c.getStringList("cheques." + kind + ".lore")) {
            lore.add(plugin.text(line));
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(chequeKey, PersistentDataType.STRING, kind);
        stack.setItemMeta(meta);
        return stack;
    }

    public List<String> chequeTypes() {
        return List.of(CHEQUES);
    }

    private String chequeKind(ItemStack item) {
        if (item == null || item.getType() != Material.STICK || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(chequeKey, PersistentDataType.STRING);
    }

    @EventHandler
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        String kind = chequeKind(item);
        if (kind == null) {
            return;
        }
        event.setCancelled(true);
        if (rolling.contains(player.getUniqueId())) {
            return;
        }
        var c = plugin.getConfig();
        AmmoManager a = plugin.getAmmo();
        switch (kind) {
            case "coin", "blockcoin" -> {
                int min = Math.max(1, c.getInt("cheques." + kind + ".min", 100));
                int max = Math.max(min, c.getInt("cheques." + kind + ".max", 500));
                int value = min + random.nextInt(max - min + 1);
                consume(player, item);
                reward(player.getUniqueId(), kind, value); // il premio arriva subito: l'effetto e' solo scena
                roll(player, kind, value);
            }
            case "power" -> {
                if (plugin.getPower() == null) {
                    return;
                }
                consume(player, item);
                plugin.getPower().giveCharge(player);
                plugin.send(player, "cheque-power", "%uses%", String.valueOf(plugin.getPower().uses(player)));
            }
            default -> {
                if (!a.exists(kind)) {
                    return;
                }
                consume(player, item);
                a.setAmmo(player, kind, a.getAmmoMax(player, kind));
                a.refreshMain(player);
                plugin.send(player, "cheque-ammo", "%ball%", kind);
                plugin.playConfiguredSound(player.getLocation(), "cheques.sounds.use");
            }
        }
    }

    private void consume(Player player, ItemStack item) {
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            item.setAmount(item.getAmount() - 1);
        }
    }

    /** Effetto "slot machine": le cifre sono illeggibili (&k) e si fermano una alla volta, poi arriva il premio. */
    private void roll(Player player, String kind, int value) {
        UUID id = player.getUniqueId();
        rolling.add(id);
        String digits = String.valueOf(value);
        String label = plugin.getConfig().getString("cheques." + kind + ".label", kind);
        String color = plugin.getConfig().getString("cheques.title-color", "&e");
        int every = Math.max(2, plugin.getConfig().getInt("cheques.reveal-ticks", 8));
        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                Player p = Bukkit.getPlayer(id);
                if (p == null) {
                    rolling.remove(id);
                    cancel();
                    return;
                }
                int revealed = tick / every;
                boolean done = revealed >= digits.length();
                StringBuilder sb = new StringBuilder(color).append("+");
                for (int i = 0; i < digits.length(); i++) {
                    if (i < revealed) {
                        sb.append(color).append("&l").append(digits.charAt(i));
                    } else {
                        sb.append(color).append("&k").append(random.nextInt(10));
                    }
                }
                Component main = LegacyComponentSerializer.legacyAmpersand().deserialize(sb.toString());
                Component sub = LegacyComponentSerializer.legacyAmpersand().deserialize("&7" + label);
                p.showTitle(Title.title(main, sub, Title.Times.times(Duration.ZERO,
                        Duration.ofMillis(done ? 1500 : 400), Duration.ofMillis(done ? 500 : 100))));
                if (done) {
                    plugin.playConfiguredSound(p.getLocation(), "cheques.sounds.win");
                    rolling.remove(id);
                    cancel();
                    return;
                }
                if (tick % 2 == 0) {
                    plugin.playConfiguredSound(p.getLocation(), "cheques.sounds.tick");
                }
                tick++;
            }
        }.runTaskTimer(plugin, 0L, 1L);
    }

    private void reward(UUID id, String kind, int value) {
        Player p = Bukkit.getPlayer(id);
        AmmoManager a = plugin.getAmmo();
        if (p == null) {
            return;
        }
        if (kind.equals("coin")) {
            a.setCoin(p, a.getCoin(p) + value);
        } else {
            a.setBlockCoin(p, a.getBlockCoin(p) + value);
        }
        a.refreshMain(p);
    }
}
