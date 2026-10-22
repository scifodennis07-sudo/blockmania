package it.blockmania;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Negozi fatti con gli armor stand:
 *  - "ammo": comprare le palle (riempie al massimo per un prezzo fisso)
 *  - "sell": vendere le blockshell in cambio di coin
 * Il testo sopra lo stand (ologramma) compare solo al giocatore che lo guarda.
 */
public final class ShopManager implements Listener {

    public static final List<String> TYPES = List.of("ammo", "sell");

    private final BlockMania plugin;

    /** Ologramma attuale di ogni giocatore. */
    private final Map<UUID, Holo> holos = new HashMap<>();
    /** Vendite in corso. */
    private final Map<UUID, Sale> sales = new HashMap<>();
    private BukkitTask holoTask;

    private record Holo(TextDisplay display, UUID stand) {
    }

    private static final class Sale {
        BossBar bar;
        BukkitTask task;
        Location where;
        Deque<Integer> queue = new ArrayDeque<>();
        Map<Integer, Integer> groups = new TreeMap<>();
        int total;
        int done;
        int coins;
        int expected;
    }

    public ShopManager(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  Avvio / stop
    // ------------------------------------------------------------------

    public void start() {
        long period = Math.max(1, plugin.getConfig().getLong("hologram.update-ticks", 5));
        holoTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::updateHolograms, 20L, period);
    }

    public void stop() {
        if (holoTask != null) {
            holoTask.cancel();
            holoTask = null;
        }
        for (Holo h : holos.values()) {
            h.display().remove();
        }
        holos.clear();
        for (UUID id : new ArrayList<>(sales.keySet())) {
            endSale(id, false);
        }
    }

    // ------------------------------------------------------------------
    //  Riconoscere e creare gli stand
    // ------------------------------------------------------------------

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private static int defaultModel(String type) {
        return type.equals("sell") ? 6 : 1;
    }

    private boolean matches(ItemStack helmet, String type) {
        if (helmet == null) {
            return false;
        }
        Material material = Material.matchMaterial(cfg().getString("shops." + type + ".material", "FEATHER"));
        if (material == null || helmet.getType() != material) {
            return false;
        }
        int cmd = cfg().getInt("shops." + type + ".custom-model-data", defaultModel(type));
        ItemMeta meta = helmet.getItemMeta();
        return meta != null && meta.hasCustomModelData() && meta.getCustomModelData() == cmd;
    }

    /** "ammo", "sell" oppure null se lo stand non e' un negozio. */
    public String shopType(Entity entity) {
        if (!(entity instanceof ArmorStand stand) || stand.getEquipment() == null) {
            return null;
        }
        ItemStack helmet = stand.getEquipment().getHelmet();
        for (String type : TYPES) {
            if (matches(helmet, type)) {
                return type;
            }
        }
        return null;
    }

    /** La piuma che il giocatore piazza per creare il negozio. */
    public ItemStack createShopItem(String type) {
        ItemStack item = helmetFor(type);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.text(cfg().getString("shops." + type + ".item.name", type)));
            List<Component> lore = new ArrayList<>();
            for (String line : cfg().getStringList("shops." + type + ".item.lore")) {
                lore.add(plugin.text(line));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack helmetFor(String type) {
        Material material = Material.matchMaterial(cfg().getString("shops." + type + ".material", "FEATHER"));
        if (material == null || !material.isItem()) {
            material = Material.FEATHER;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(cfg().getInt("shops." + type + ".custom-model-data", defaultModel(type)));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Crea uno stand invisibile con la piuma giusta in testa. */
    public ArmorStand spawn(Location location, String type) {
        Material material = Material.matchMaterial(cfg().getString("shops." + type + ".material", "FEATHER"));
        if (material == null || !material.isItem()) {
            material = Material.FEATHER;
        }
        ItemStack helmet = new ItemStack(material);
        ItemMeta meta = helmet.getItemMeta();
        if (meta != null) {
            meta.setCustomModelData(cfg().getInt("shops." + type + ".custom-model-data", defaultModel(type)));
            helmet.setItemMeta(meta);
        }
        final ItemStack finalHelmet = helmet;
        return location.getWorld().spawn(location, ArmorStand.class, stand -> {
            stand.setInvisible(true);
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(false);
            stand.setSilent(true);
            stand.setPersistent(true);
            stand.getEquipment().setHelmet(finalHelmet);
        });
    }

    // ------------------------------------------------------------------
    //  Protezione degli stand
    // ------------------------------------------------------------------

    /** Toglie lo stand e rida' la piuma al giocatore. */
    private void removeShop(Player p, Entity stand, String type) {
        if (!stand.isValid()) {
            return;
        }
        stand.remove();
        Holo h = holos.remove(p.getUniqueId());
        if (h != null) {
            h.display().remove();
        }
        Map<Integer, ItemStack> left = p.getInventory().addItem(createShopItem(type));
        for (ItemStack extra : left.values()) {
            p.getWorld().dropItem(p.getLocation(), extra);
        }
        plugin.send(p, "shop-removed", "%shop%", type);
    }

    /** Colpire uno stand di negozio non fa niente (si toglie con shift + destro). */
    private boolean handleAttack(Player p, Entity stand) {
        if (shopType(stand) == null) {
            return false;
        }
        if (p.hasPermission("blockmania.shop.edit")) {
            plugin.send(p, "shop-remove-hint");
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (handleAttack(event.getPlayer(), event.getAttacked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageEvent event) {
        if (shopType(event.getEntity()) == null) {
            return;
        }
        if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Player p) {
            handleAttack(p, event.getEntity());
        }
        event.setCancelled(true);
    }

    /** Piazzare la piuma del negozio crea lo stand. */
    @EventHandler
    public void onPlace(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getClickedBlock() == null) {
            return;
        }
        ItemStack item = event.getItem();
        if (item == null) {
            return;
        }
        String type = null;
        for (String t : TYPES) {
            if (matches(item, t)) {
                type = t;
            }
        }
        if (type == null) {
            return;
        }
        Player player = event.getPlayer();
        event.setCancelled(true);
        if (!player.hasPermission("blockmania.shop.edit")) {
            return;
        }
        Location at = event.getClickedBlock().getRelative(event.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        float yaw = Math.round((player.getLocation().getYaw() + 180f) / 45f) * 45f;
        at.setYaw(yaw);
        spawn(at, type);
        if (player.getGameMode() != GameMode.CREATIVE) {
            item.setAmount(item.getAmount() - 1);
        }
    }

    @EventHandler
    public void onManipulate(PlayerArmorStandManipulateEvent event) {
        if (shopType(event.getRightClicked()) != null) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------
    //  Click destro
    // ------------------------------------------------------------------

    @EventHandler
    public void onInteract(PlayerInteractAtEntityEvent event) {
        String type = shopType(event.getRightClicked());
        if (type == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        // shift + destro a mano vuota (solo con blockmania.shop.edit): toglie il negozio
        if (player.isSneaking() && player.hasPermission("blockmania.shop.edit")
                && player.getInventory().getItemInMainHand().getType().isAir()) {
            removeShop(player, event.getRightClicked(), type);
            return;
        }
        if (!player.hasPermission("blockmania.use")) {
            return;
        }
        if (type.equals("ammo")) {
            buyAmmo(player);
        } else {
            startSale(player, event.getRightClicked());
        }
    }

    // ------------------------------------------------------------------
    //  Negozio palle
    // ------------------------------------------------------------------

    private void buyAmmo(Player player) {
        AmmoManager ammo = plugin.getAmmo();
        String type = cfg().getString("shops.ammo.ammo-type", "standardball").toLowerCase(Locale.ROOT);
        if (!ammo.exists(type)) {
            return;
        }
        int price = cfg().getInt("shops.ammo.price", 10);
        String currency = cfg().getString("shops.ammo.currency", "coin");

        int current = ammo.getAmmo(player, type);
        int max = ammo.getAmmoMax(player, type);
        if (current >= max) {
            plugin.send(player, "ammo-full", "%ammo%", current + "/" + max);
            return;
        }
        int balance = ammo.getBalance(player, currency);
        if (balance < price) {
            plugin.send(player, "shop-no-funds",
                    "%price%", String.valueOf(price),
                    "%currency%", plugin.tiny(currency),
                    "%balance%", String.valueOf(balance));
            plugin.playConfiguredSound(player.getLocation(), "shops.ammo.sounds.error");
            return;
        }
        ammo.setBalance(player, currency, balance - price);
        ammo.setAmmo(player, type, max);
        plugin.send(player, "ammo-bought",
                "%amount%", String.valueOf(max - current),
                "%price%", String.valueOf(price),
                "%currency%", plugin.tiny(currency));
        plugin.playConfiguredSound(player.getLocation(), "shops.ammo.sounds.buy");
    }

    // ------------------------------------------------------------------
    //  Vendita delle blockshell
    // ------------------------------------------------------------------

    /** True mentre il giocatore sta vendendo le shell. */
    public boolean isSelling(Player player) {
        return sales.containsKey(player.getUniqueId());
    }

    /** Durante la vendita non si puo' aprire nessuna GUI. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(org.bukkit.event.inventory.InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && sales.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
            plugin.send(player, "sale-no-gui");
        }
    }

    private void startSale(Player player, Entity stand) {
        if (sales.containsKey(player.getUniqueId())) {
            plugin.send(player, "sell-busy");
            return;
        }
        AmmoManager ammo = plugin.getAmmo();
        Map<Integer, Integer> shells = ammo.getShells(player);
        Sale sale = new Sale();
        for (Map.Entry<Integer, Integer> e : shells.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            sale.groups.put(e.getKey(), e.getValue());
            for (int i = 0; i < e.getValue(); i++) {
                sale.queue.add(e.getKey()); // dal livello piu basso, come removeShells
            }
            sale.total += e.getValue();
            sale.expected += e.getValue() * ammo.shellValue(e.getKey());
        }
        if (sale.total <= 0) {
            plugin.send(player, "sell-no-shells");
            return;
        }
        sale.where = stand.getLocation();

        BossBar.Color color = BossBar.Color.GREEN;
        BossBar.Overlay overlay = BossBar.Overlay.PROGRESS;
        try {
            color = BossBar.Color.valueOf(cfg().getString("shops.sell.bossbar.color", "GREEN").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // colore non valido: resta verde
        }
        try {
            overlay = BossBar.Overlay.valueOf(cfg().getString("shops.sell.bossbar.style", "PROGRESS").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // stile non valido: resta PROGRESS
        }
        sale.bar = BossBar.bossBar(saleText(sale), 0f, color, overlay);
        player.showBossBar(sale.bar);
        sales.put(player.getUniqueId(), sale);

        double seconds = Math.max(0.05, cfg().getDouble("shops.sell.seconds-per-shell", 0.2));
        long period = Math.max(1, Math.round(seconds * 20));
        UUID id = player.getUniqueId();
        sale.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tickSale(id), period, period);
    }

    private Component saleText(Sale sale) {
        AmmoManager ammo = plugin.getAmmo();
        String format = cfg().getString("shops.sell.bossbar.group-format",
                "&fx%count% &7lv%level% &e%coins% coin");
        List<String> segments = new ArrayList<>();
        String title = cfg().getString("shops.sell.bossbar.title", "");
        if (title != null && !title.isBlank()) {
            segments.add(title
                    .replace("%done%", String.valueOf(sale.done))
                    .replace("%shells%", String.valueOf(sale.total)));
        }
        // un riquadro per ogni livello. %coins% = quanto vale UNA shell di quel livello
        for (Map.Entry<Integer, Integer> e : sale.groups.entrySet()) {
            int each = ammo.shellValue(e.getKey());
            segments.add(format
                    .replace("%count%", String.valueOf(e.getValue()))
                    .replace("%level%", String.valueOf(e.getKey()))
                    .replace("%coins%", String.valueOf(each))
                    .replace("%group_total%", String.valueOf(each * e.getValue())));
        }
        double progress = cfg().getBoolean("shops.sell.bossbar.progress-bar", true) && sale.total > 0
                ? (double) sale.done / sale.total : -1;
        return plugin.bossSegments(segments, progress);
    }

    private void tickSale(UUID id) {
        Sale sale = sales.get(id);
        Player player = plugin.getServer().getPlayer(id);
        if (sale == null) {
            return;
        }
        if (player == null || !player.isOnline()
                || !player.getWorld().equals(sale.where.getWorld())
                || player.getLocation().distance(sale.where) > cfg().getDouble("shops.sell.max-distance", 6)) {
            endSale(id, true);
            return;
        }

        AmmoManager ammo = plugin.getAmmo();
        Integer level = sale.queue.poll();
        if (level == null || ammo.removeShells(player, 1) <= 0) {
            endSale(id, false);
            return;
        }
        int value = ammo.shellValue(level);
        ammo.setCoin(player, ammo.getCoin(player) + value);
        sale.coins += value;
        sale.done++;
        sale.bar.progress(Math.min(1f, (float) sale.done / sale.total));
        sale.bar.name(saleText(sale));
        plugin.playConfiguredSound(player.getLocation(), "shops.sell.sounds.tick");

        if (sale.queue.isEmpty()) {
            endSale(id, false);
        }
    }

    /** Chiude la vendita. cancelled = interrotta prima della fine. */
    private void endSale(UUID id, boolean cancelled) {
        Sale sale = sales.remove(id);
        if (sale == null) {
            return;
        }
        if (sale.task != null) {
            sale.task.cancel();
        }
        Player player = plugin.getServer().getPlayer(id);
        if (player != null) {
            player.hideBossBar(sale.bar);
            if (sale.done > 0 || !cancelled) {
                plugin.send(player, cancelled ? "sell-cancelled" : "sell-done",
                        "%shells%", String.valueOf(sale.done),
                        "%coins%", String.valueOf(sale.coins));
            } else {
                plugin.send(player, "sell-cancelled", "%shells%", "0", "%coins%", "0");
            }
            if (!cancelled) {
                plugin.playConfiguredSound(player.getLocation(), "shops.sell.sounds.done");
            }
        }
    }

    // ------------------------------------------------------------------
    //  Ologramma: compare solo a chi guarda lo stand
    // ------------------------------------------------------------------

    private void updateHolograms() {
        double range = cfg().getDouble("hologram.range", 5);
        double height = cfg().getDouble("hologram.height", 2.4);

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            ArmorStand target = null;
            String type = null;
            if (player.hasPermission("blockmania.use")) {
                RayTraceResult hit = player.getWorld().rayTrace(player.getEyeLocation(),
                        player.getEyeLocation().getDirection(), range, FluidCollisionMode.NEVER,
                        true, 0.2, e -> e instanceof ArmorStand a && shopType(a) != null);
                if (hit != null && hit.getHitEntity() instanceof ArmorStand stand) {
                    target = stand;
                    type = shopType(stand);
                }
            }

            Holo current = holos.get(player.getUniqueId());
            if (target == null || type == null) {
                if (current != null) {
                    current.display().remove();
                    holos.remove(player.getUniqueId());
                }
                continue;
            }

            if (current != null && (!current.stand().equals(target.getUniqueId()) || !current.display().isValid())) {
                current.display().remove();
                holos.remove(player.getUniqueId());
                current = null;
            }
            Component text = hologramText(player, type);
            if (current == null) {
                Location at = target.getLocation().add(0, height, 0);
                TextDisplay display = at.getWorld().spawn(at, TextDisplay.class, d -> {
                    d.setVisibleByDefault(false);
                    d.setPersistent(false);
                    d.setBillboard(Display.Billboard.CENTER);
                });
                display.text(text);
                player.showEntity(plugin, display);
                holos.put(player.getUniqueId(), new Holo(display, target.getUniqueId()));
            } else {
                current.display().text(text);
            }
        }
    }

    private Component hologramText(Player player, String type) {
        AmmoManager ammo = plugin.getAmmo();
        List<String> lines = cfg().getStringList("shops." + type + ".hologram");
        String currency = cfg().getString("shops." + type + ".currency", "coin");
        String ammoType = cfg().getString("shops.ammo.ammo-type", "standardball").toLowerCase(Locale.ROOT);

        int expected = 0;
        for (Map.Entry<Integer, Integer> e : ammo.getShells(player).entrySet()) {
            expected += e.getValue() * ammo.shellValue(e.getKey());
        }
        List<Component> parts = new ArrayList<>();
        for (String line : lines) {
            parts.add(plugin.text(line,
                    "%price%", String.valueOf(cfg().getInt("shops.ammo.price", 10)),
                    "%currency%", plugin.tiny(currency),
                    "%balance%", String.valueOf(ammo.getBalance(player, currency)),
                    "%ammo%", String.valueOf(ammo.getAmmo(player, ammoType)),
                    "%ammo_max%", String.valueOf(ammo.getAmmoMax(player, ammoType)),
                    "%shells%", String.valueOf(ammo.getShell(player)),
                    "%coins%", String.valueOf(expected)));
        }
        return Component.join(JoinConfiguration.newlines(), parts);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        Holo h = holos.remove(id);
        if (h != null) {
            h.display().remove();
        }
        endSale(id, true);
    }
}
