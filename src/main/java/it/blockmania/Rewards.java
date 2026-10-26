package it.blockmania;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * /ricompense: cinque sezioni (blocchi spaccati, blocchi colpiti, coin spesi, tempo di gioco, ricompensa
 * giornaliera), /afk e gli assegni (bastoncini col modello 3-9 che si usano col tasto destro).
 */
public final class Rewards implements Listener {

    private static final String GUI = "rewards-gui.";
    private static final int SIZE = 54;
    /** Le sezioni, nell'ordine in cui compaiono nella barra a sinistra. */
    static final String[] CATEGORIES = {"broken", "hits", "spent", "play", "daily"};
    private static final String[] CHEQUES = {"standardball", "doubleball", "ghostball", "explosionball",
            "coin", "blockcoin", "power"};
    /** Lettere disegnate dal pack (ascii.png): carattere ASCII c -> base + c. */
    private static final char TEXT_BASE = '';
    private static final char TEXT_SHADOW_BASE = '';

    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        private String category;
        private final Map<Integer, String> actions = new HashMap<>();

        Holder(String category) {
            this.category = category;
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
    //  Livelli e ricompense
    // ------------------------------------------------------------------

    private int maxLevel() {
        return Math.max(1, plugin.getConfig().getInt(GUI + "levels", 100));
    }

    private String counterOf(String cat) {
        return switch (cat) {
            case "broken" -> "rw_broken";
            case "hits" -> "rw_hits";
            case "spent" -> "rw_spent";
            default -> "rw_play";
        };
    }

    private long stat(Player player, String cat) {
        return plugin.getAmmo().getCounter(player, counterOf(cat));
    }

    private int claimedLevel(Player player, String cat) {
        return Math.min(maxLevel(), plugin.getAmmo().getCounter(player, "rw_lvl_" + cat));
    }

    /** Quanto bisogna aver fatto in tutto per completare il livello n (cresce sempre piu' in fretta). */
    long threshold(String cat, int level) {
        if (level <= 0) {
            return 0;
        }
        var c = plugin.getConfig();
        double base = c.getDouble(GUI + "categories." + cat + ".base", 100);
        double growth = c.getDouble(GUI + "categories." + cat + ".growth", 0.05);
        return Math.max(1, Math.round(base * (level + growth * level * level)));
    }

    /** La ricompensa del livello n: i livelli bassi danno coin, poi blockcoin, poi gli assegni sempre piu' rari. */
    Reward rewardOf(int level) {
        int prev = 0;
        String type = "coin";
        int amount = 100;
        for (Map<?, ?> map : plugin.getConfig().getMapList(GUI + "tiers")) {
            int upto = num(map.get("upto"), maxLevel());
            type = String.valueOf(map.get("type") == null ? "coin" : map.get("type")).toLowerCase(Locale.ROOT);
            int base = num(map.get("amount"), 1);
            int step = num(map.get("step"), 0);
            amount = Math.max(1, base + step * Math.max(0, level - prev - 1));
            if (level <= upto) {
                return new Reward(type, amount);
            }
            prev = upto;
        }
        return new Reward(type, amount);
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
        return Math.max(0, plugin.getConfig().getInt(GUI + "daily.minutes", 30));
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

    /** Giorni davvero riscattati (non quelli giocati). */
    private int daysClaimed(Player player) {
        return plugin.getAmmo().getCounter(player, "rw_days");
    }

    private void claimDaily(Player player) {
        AmmoManager a = plugin.getAmmo();
        if (dailyClaimedToday(player)) {
            plugin.send(player, "daily-already");
            plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.error");
            return;
        }
        if (isAfk(player)) {
            plugin.send(player, "daily-afk");
            plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.error");
            return;
        }
        int left = dailyMinutes() - minutesToday(player);
        if (left > 0) {
            plugin.send(player, "daily-wait", "%minutes%", String.valueOf(left));
            plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.error");
            return;
        }
        a.setCounter(player, "rw_dayclaim", (int) today());
        a.setCounter(player, "rw_days", daysClaimed(player) + 1);
        give(player, new Reward("coin", dailyCoins()));
        plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.claim");
        plugin.send(player, "daily-claimed", "%coin%", String.valueOf(dailyCoins()),
                "%day%", String.valueOf(daysClaimed(player)));
        open(player, "daily", false);
    }

    // ------------------------------------------------------------------
    //  GUI
    // ------------------------------------------------------------------

    private String[] repl(Player player, String cat) {
        List<String> r = new ArrayList<>();
        r.add("%coin%");
        r.add(String.valueOf(plugin.getAmmo().getCoin(player)));
        r.add("%blockcoin%");
        r.add(String.valueOf(plugin.getAmmo().getBlockCoin(player)));
        r.add("%max%");
        r.add(String.valueOf(maxLevel()));
        if (cat.equals("daily")) {
            int have = Math.min(dailyMinutes(), minutesToday(player));
            r.addAll(List.of("%day%", String.valueOf(daysClaimed(player)),
                    "%minutes%", String.valueOf(have),
                    "%need%", String.valueOf(dailyMinutes()),
                    "%coin_reward%", String.valueOf(dailyCoins())));
        } else {
            int claimed = claimedLevel(player, cat);
            int next = Math.min(maxLevel(), claimed + 1);
            long have = stat(player, cat);
            long need = threshold(cat, next);
            long from = threshold(cat, claimed);
            r.addAll(List.of("%level%", String.valueOf(next),
                    "%claimed%", String.valueOf(claimed),
                    "%have%", String.valueOf(have),
                    "%need%", String.valueOf(need),
                    "%left%", String.valueOf(Math.max(0, need - have)),
                    "%percent%", String.valueOf(claimed >= maxLevel() ? 100
                            : Math.min(100, Math.max(0, (have - from) * 100 / Math.max(1, need - from)))),
                    "%reward%", rewardName(rewardOf(next))));
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

    private List<Integer> slots(String path, List<Integer> def) {
        List<Integer> out = new ArrayList<>();
        var c = plugin.getConfig();
        List<Integer> src = c.isList(path) ? c.getIntegerList(path) : def;
        for (Integer i : src) {
            if (i >= 0 && i < SIZE) {
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

    private int iconCmd(String key, int def) {
        return plugin.getConfig().getInt(GUI + "cmds." + key, def);
    }

    private List<String> loreOf(String path, List<String> def) {
        var c = plugin.getConfig();
        return c.isList(path) ? c.getStringList(path) : def;
    }

    private void fill(Inventory inv, Holder holder, Player player) {
        var c = plugin.getConfig();
        String cat = holder.category;
        String[] r = repl(player, cat);
        holder.actions.clear();
        ItemStack filler = pane(invisibleCmd(), " ", List.of(), r, true);
        for (int i = 0; i < SIZE; i++) {
            inv.setItem(i, filler);
        }
        for (int slot : slots(GUI + "slots.close", List.of(0, 1))) {
            put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "close.name", "&cchiudi"),
                    List.of(), r, false), "close");
        }
        for (int slot : slots(GUI + "slots.info", List.of(7, 8))) {
            put(inv, holder, slot, pane(invisibleCmd(), c.getString(GUI + "info.name", "&6informazioni"),
                    loreOf(GUI + "info.lore", List.of()), r, false), null);
        }
        // barra in alto: nome della sezione
        String title = c.getString(GUI + "categories." + cat + ".name", cat);
        for (int slot : slots(GUI + "slots.bar", List.of(2, 3, 4, 5, 6))) {
            put(inv, holder, slot, pane(invisibleCmd(), title, loreOf(GUI + "categories." + cat + ".lore",
                    List.of()), r, false), null);
        }
        // barra a sinistra: le sezioni
        List<Integer> left = slots(GUI + "slots.categories", List.of(9, 18, 27, 36, 45));
        for (int i = 0; i < CATEGORIES.length && i < left.size(); i++) {
            String k = CATEGORIES[i];
            String name = c.getString(GUI + "categories." + k + ".name", k);
            String prefix = k.equals(cat) ? c.getString(GUI + "selected-prefix", "&a▶ ") : "";
            put(inv, holder, left.get(i), pane(iconCmd(k, 501 + i), prefix + name,
                    List.of(c.getString(GUI + "categories." + k + ".hint", "&8clicca per aprire")), r, false),
                    "cat:" + k);
        }
        // centro: la ricompensa
        List<Integer> panel = slots(GUI + "slots.panel", defaultPanel());
        int iconSlot = c.getInt(GUI + "slots.icon", 31);
        if (cat.equals("daily")) {
            fillDaily(inv, holder, player, panel, iconSlot, r);
        } else {
            fillLevel(inv, holder, player, cat, panel, iconSlot, r);
        }
    }

    private static List<Integer> defaultPanel() {
        List<Integer> l = new ArrayList<>();
        for (int row = 1; row <= 5; row++) {
            for (int col = 2; col <= 6; col++) {
                l.add(row * 9 + col);
            }
        }
        return l;
    }

    private void fillDaily(Inventory inv, Holder holder, Player player, List<Integer> panel, int iconSlot, String[] r) {
        var c = plugin.getConfig();
        boolean done = dailyClaimedToday(player);
        boolean ready = !done && minutesToday(player) >= dailyMinutes() && !isAfk(player);
        String key = done ? "claimed" : ready ? "ready" : "wait";
        String name = c.getString(GUI + "daily.states." + key + ".name", "&fricompensa giornaliera");
        List<String> lore = loreOf(GUI + "daily.states." + key + ".lore", List.of());
        for (int slot : panel) {
            put(inv, holder, slot, pane(invisibleCmd(), name, lore, r, false), "daily");
        }
    }

    private void fillLevel(Inventory inv, Holder holder, Player player, String cat, List<Integer> panel,
                           int iconSlot, String[] r) {
        var c = plugin.getConfig();
        int claimed = claimedLevel(player, cat);
        boolean finished = claimed >= maxLevel();
        int next = Math.min(maxLevel(), claimed + 1);
        boolean ready = !finished && stat(player, cat) >= threshold(cat, next);
        String key = finished ? "finished" : ready ? "ready" : "progress";
        String name = c.getString(GUI + "level-states." + key + ".name", "&flivello %level%");
        List<String> lore = loreOf(GUI + "level-states." + key + ".lore", List.of());
        for (int slot : panel) {
            put(inv, holder, slot, pane(invisibleCmd(), name, lore, r, false), ready ? "claim:" + cat : null);
        }
        // l'icona della ricompensa al centro
        if (!finished) {
            Reward rw = rewardOf(next);
            ItemStack icon;
            if (rw.type().startsWith("cheque_")) {
                icon = cheque(rw.type().substring(7), Math.min(64, rw.amount()));
            } else {
                icon = pane(iconCmd(rw.type(), rw.type().equals("coin") ? 506 : 507), name, lore, r, false);
            }
            if (icon != null) {
                ItemMeta meta = icon.getItemMeta();
                meta.displayName(plugin.text(name, r));
                List<Component> lines = new ArrayList<>();
                for (String line : lore) {
                    lines.add(plugin.text(line, r));
                }
                meta.lore(lines);
                icon.setItemMeta(meta);
                put(inv, holder, iconSlot, icon, ready ? "claim:" + cat : null);
            }
        } else {
            put(inv, holder, iconSlot, pane(iconCmd("check", 509), name, lore, r, false), null);
        }
    }

    private void claimLevel(Player player, String cat) {
        int claimed = claimedLevel(player, cat);
        if (claimed >= maxLevel()) {
            return;
        }
        int next = claimed + 1;
        if (stat(player, cat) < threshold(cat, next)) {
            plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.error");
            return;
        }
        Reward rw = rewardOf(next);
        plugin.getAmmo().setCounter(player, "rw_lvl_" + cat, next);
        give(player, rw);
        plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.claim");
        plugin.send(player, "reward-claimed", "%level%", String.valueOf(next), "%reward%", rewardName(rw));
        open(player, cat, false);
    }

    // ---------- titolo ----------

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
    private static int textWidth(String text) {
        int total = 0;
        for (char ch : text.toCharArray()) {
            int w = ch == ' ' ? 4 : FontMetrics.width(ch);
            total += w < 0 ? 6 : w;
        }
        return Math.max(0, total - 1);
    }

    private int imageIndex(Player player, String cat) {
        var c = plugin.getConfig();
        int steps = Math.max(1, c.getInt(GUI + "progress-images", 12)) - 1;
        int claimed = claimedLevel(player, cat);
        if (claimed >= maxLevel()) {
            return steps;
        }
        long from = threshold(cat, claimed);
        long need = threshold(cat, claimed + 1);
        double p = Math.min(1.0, Math.max(0.0, (stat(player, cat) - from) / (double) Math.max(1, need - from)));
        return Math.min(steps, (int) Math.floor(p * steps));
    }

    private String image(Player player, String cat) {
        var c = plugin.getConfig();
        if (cat.equals("daily")) {
            return dailyClaimedToday(player) ? c.getString(GUI + "image-daily-claimed", "㴍")
                    : c.getString(GUI + "image-daily-ready", "㴌");
        }
        List<String> images = c.getStringList(GUI + "images");
        if (images.isEmpty()) {
            return "";
        }
        return images.get(Math.min(images.size() - 1, imageIndex(player, cat)));
    }

    private Component title(Player player, String cat) {
        var c = plugin.getConfig();
        String raw = c.getString(GUI + "title.text", "&f{shift:-15}%image%");
        String shown = raw.replace("%image%", image(player, cat));
        GuiTitle t = new GuiTitle(plugin);
        t.text(shown, c.getString(GUI + "title.font", ""), GUI + "title", shown);
        // nella barra in alto: "giorno N" oppure "livello N" (sempre uguale, centrato)
        String text = (cat.equals("daily")
                ? c.getString(GUI + "bar-text.daily", "giorno %n%").replace("%n%", String.valueOf(daysClaimed(player)))
                : c.getString(GUI + "bar-text.level", "livello %n%").replace("%n%",
                String.valueOf(Math.min(maxLevel(), claimedLevel(player, cat) + 1))))
                .toLowerCase(Locale.ROOT);
        int center = c.getInt(GUI + "bar-center", 88);
        int start = center - textWidth(text) / 2;
        String font = c.getString(GUI + "title.font", "");
        if (c.getBoolean(GUI + "bar-shadow", true)) {
            t.at(start + 1, GuiTitle.shadowCode(plugin) + letters(text, TEXT_SHADOW_BASE), font);
        }
        t.at(start, "&f" + letters(text, TEXT_BASE), font);
        return t.build();
    }

    // ---------- aprire e usare ----------

    public void open(Player player) {
        open(player, "broken", true);
    }

    public void open(Player player, String category, boolean sound) {
        String cat = category;
        boolean known = false;
        for (String k : CATEGORIES) {
            known |= k.equals(cat);
        }
        if (!known) {
            cat = "broken";
        }
        Holder holder = new Holder(cat);
        Inventory inv = Bukkit.createInventory(holder, SIZE, title(player, cat));
        holder.inventory = inv;
        fill(inv, holder, player);
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
        if (action.equals("close")) {
            player.closeInventory();
        } else if (action.equals("daily")) {
            claimDaily(player);
        } else if (action.startsWith("cat:")) {
            String cat = action.substring(4);
            if (!cat.equals(holder.category)) {
                plugin.playConfiguredSound(player.getLocation(), GUI + "sounds.page");
                open(player, cat, false);
            }
        } else if (action.startsWith("claim:")) {
            claimLevel(player, action.substring(6));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof Holder) {
            event.setCancelled(true);
        }
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
