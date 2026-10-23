package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.util.Vector;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.Material;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

public final class LauncherListener implements Listener {

    private final BlockMania plugin;
    /** Ultimo sparo di ogni giocatore, per ogni oggetto (in tick del server). */
    private final Map<UUID, Map<String, Integer>> lastUse = new HashMap<>();
    /** Giocatori che tengono premuto il tasto destro: fino a che tick continuano a sparare da soli. */
    private final Map<UUID, Integer> holdUntil = new HashMap<>();
    /** Ultimo messaggio "senza palle", per non ripeterlo a ogni sparo automatico. */
    private final Map<UUID, Integer> lastNoAmmo = new HashMap<>();

    private final NamespacedKey bouncesKey;

    public LauncherListener(BlockMania plugin) {
        this.plugin = plugin;
        this.bouncesKey = new NamespacedKey(plugin, "bounces");
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::autoFire, 1L, 1L);
    }

    /** La palla colpisce un blocco: se ha ancora rimbalzi, rimbalza invece di sparire. */
    @EventHandler
    public void onBounce(ProjectileHitEvent event) {
        var c = plugin.getConfig();
        if (!c.getBoolean("bounce.enabled", true)) {
            return;
        }
        if (!(event.getEntity() instanceof Snowball ball) || event.getHitBlock() == null) {
            return;
        }
        var pdc = ball.getPersistentDataContainer();
        if (!pdc.has(plugin.getAmmoKey(), PersistentDataType.STRING)) {
            return; // non e' una nostra palla
        }
        String ballType = pdc.get(plugin.getAmmoKey(), PersistentDataType.STRING);
        if (!c.getBoolean("ammo." + ballType + ".bounce", true)) {
            return; // questo tipo di palla non rimbalza
        }
        int done = pdc.getOrDefault(bouncesKey, PersistentDataType.INTEGER, 0);
        if (done >= c.getInt("bounce.max", 3)) {
            return; // rimbalzi finiti: la palla sparisce come al solito
        }
        BlockFace face = event.getHitBlockFace();
        if (face == null) {
            return;
        }
        Vector normal = face.getDirection();
        Vector v = ball.getVelocity();
        double keep = c.getDouble("bounce.speed-keep", 0.8);
        Vector reflected = v.clone().subtract(normal.clone().multiply(2 * v.dot(normal))).multiply(keep);

        // La palla originale sparisce come sempre; al suo posto ne nasce una nuova, uguale,
        // che parte dal punto dell'urto con la direzione riflessa (funziona su ogni versione).
        Location spawn = ball.getLocation().add(normal.clone().multiply(0.15));
        Integer damage = pdc.get(plugin.getDamageKey(), PersistentDataType.INTEGER);
        String ammoType = pdc.get(plugin.getAmmoKey(), PersistentDataType.STRING);
        var shooter = ball.getShooter();
        ItemStack model = ball.getItem();
        ball.getWorld().spawn(spawn, Snowball.class, next -> {
            next.setShooter(shooter);
            next.setItem(model);
            next.getPersistentDataContainer().set(plugin.getAmmoKey(), PersistentDataType.STRING, ammoType);
            if (damage != null) {
                next.getPersistentDataContainer().set(plugin.getDamageKey(), PersistentDataType.INTEGER, damage);
            }
            next.getPersistentDataContainer().set(bouncesKey, PersistentDataType.INTEGER, done + 1);
            next.setVelocity(reflected);
        });
        plugin.playConfiguredSound(ball.getLocation(), "bounce");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }

        ItemStack inHand = event.getItem();
        if (inHand == null || !inHand.hasItemMeta()) {
            return;
        }
        String id = inHand.getItemMeta().getPersistentDataContainer()
                .get(plugin.getItemKey(), PersistentDataType.STRING);
        if (id == null) {
            return;
        }
        LauncherItem li = plugin.getItem(id);
        if (li == null) {
            return;
        }

        Player player = event.getPlayer();
        Action action = event.getAction();

        // tasto sinistro (senza shift): apre la GUI dei potenziamenti
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            if (!player.isSneaking() && player.hasPermission("blockmania.use")) {
                plugin.getUpgrades().open(player, li);
            }
            return;
        }

        // tasto destro: spara
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }

        // e' un nostro oggetto: non deve fare altro (es. aprire casse)
        event.setCancelled(true);

        if (!player.hasPermission("blockmania.use")) {
            return;
        }

        // tenendo premuto spara da solo, alla velocita' del potenziamento
        if (plugin.getConfig().getBoolean("autofire.enabled", true)) {
            holdUntil.put(player.getUniqueId(), Bukkit.getCurrentTick()
                    + Math.max(1, plugin.getConfig().getInt("autofire.grace-ticks", 5)));
        }
        tryFire(player, li);
    }

    /** Sparo automatico: ogni tick, per chi tiene premuto il tasto destro con il lanciapalle in mano. */
    private void autoFire() {
        if (holdUntil.isEmpty()) {
            return;
        }
        int now = Bukkit.getCurrentTick();
        Iterator<Map.Entry<UUID, Integer>> it = holdUntil.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> entry = it.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || now > entry.getValue()) {
                it.remove();
                continue;
            }
            LauncherItem li = plugin.getLauncher(player.getInventory().getItemInMainHand());
            if (li == null || plugin.hasOpenGui(player) || !player.hasPermission("blockmania.use")) {
                it.remove();
                continue;
            }
            tryFire(player, li);
        }
    }

    /** Spara se la ricarica e' finita e ci sono palle del tipo scelto. */
    private void tryFire(Player player, LauncherItem li) {
        UpgradeManager up = plugin.getUpgrades();
        int now = Bukkit.getCurrentTick();
        int cooldownTicks = Math.max(1, (int) Math.round(up.cooldown(player, li.cooldownSeconds()) * 20));
        Map<String, Integer> uses = lastUse.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        Integer last = uses.get(li.id());
        if (last != null && now - last < cooldownTicks) {
            return; // in ricarica: nessun messaggio, il giocatore non deve sapere il tempo
        }

        AmmoManager ammo = plugin.getAmmo();
        String type = ammo.getSelected(player, li.ammoType());
        if (ammo.getAmmo(player, type) <= 0) {
            Integer lastMsg = lastNoAmmo.get(player.getUniqueId());
            if (lastMsg == null || now - lastMsg >= 20) {
                lastNoAmmo.put(player.getUniqueId(), now);
                plugin.send(player, "no-ammo");
            }
            return;
        }
        ammo.addAmmo(player, type, -1);
        uses.put(li.id(), now);

        var c = plugin.getConfig();
        String path = "ammo." + type + ".";
        int shots = Math.max(1, c.getInt(path + "shots", 1));
        int delay = Math.max(1, c.getInt(path + "shot-delay-ticks", 2));
        for (int i = 0; i < shots; i++) {
            if (i == 0) {
                launchOnce(player, li, type);
            } else {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> launchOnce(player, li, type),
                        (long) delay * i);
            }
        }
        plugin.playConfiguredSound(player.getEyeLocation(), "items." + li.id());
    }

    /** Lancia una "raffica" di una palla: una sola, oppure una griglia (es. 3x3) di palle parallele. */
    private void launchOnce(Player player, LauncherItem li, String type) {
        if (!player.isOnline() || player.isDead()) {
            return;
        }
        var c = plugin.getConfig();
        String path = "ammo." + type + ".";
        UpgradeManager up = plugin.getUpgrades();
        boolean ghost = c.getBoolean(path + "ghost", false);
        int grid = Math.max(1, c.getInt(path + "grid", 1));
        double spacing = c.getDouble(path + "grid-spacing", 1.0);
        double speed = up.speed(player, li.speed());
        int damage = up.damage(player, type);
        ItemStack model = projectileModel(type, li);

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        Vector right = dir.clone().crossProduct(new Vector(0, 1, 0));
        if (right.lengthSquared() < 1.0e-6) {
            right = new Vector(1, 0, 0);
        }
        right.normalize();
        Vector upward = right.clone().crossProduct(dir).normalize();

        for (int ix = 0; ix < grid; ix++) {
            for (int iy = 0; iy < grid; iy++) {
                double ox = (ix - (grid - 1) / 2.0) * spacing;
                double oy = (iy - (grid - 1) / 2.0) * spacing;
                boolean center = ox == 0 && oy == 0;
                Location from = eye.clone().add(right.clone().multiply(ox)).add(upward.clone().multiply(oy));
                if (!center && !from.getBlock().isPassable()) {
                    continue; // la palla nascerebbe dentro un muro: la salto
                }
                if (ghost) {
                    GhostBall.launch(plugin, player, from, dir, speed,
                            c.getDouble(path + "range", 40), damage, model);
                    continue;
                }
                Snowball ball;
                if (center) {
                    ball = player.launchProjectile(Snowball.class, dir.clone().multiply(speed));
                } else {
                    ball = player.getWorld().spawn(from, Snowball.class, b -> {
                        b.setShooter(player);
                        b.setVelocity(dir.clone().multiply(speed));
                    });
                }
                if (model != null) {
                    ball.setItem(model);
                }
                // segniamo la palla con il suo tipo e il suo danno (potenziamenti compresi)
                ball.getPersistentDataContainer().set(plugin.getAmmoKey(), PersistentDataType.STRING, type);
                ball.getPersistentDataContainer().set(plugin.getDamageKey(), PersistentDataType.INTEGER, damage);
            }
        }
    }

    /**
     * L'oggetto che da' il modello alla palla lanciata: ammo.<tipo>.projectile-custom-model-data;
     * per la palla del lanciapalle vale anche items.<id>.projectile-custom-model-data. Null = palla normale.
     */
    private ItemStack projectileModel(String type, LauncherItem li) {
        var c = plugin.getConfig();
        String ammoPath = "ammo." + type + ".";
        String itemPath = "items." + li.id() + ".";
        String path;
        if (c.contains(ammoPath + "projectile-custom-model-data")) {
            path = ammoPath;
        } else if (type.equals(li.ammoType()) && c.contains(itemPath + "projectile-custom-model-data")) {
            path = itemPath;
        } else {
            return null;
        }
        Material material = Material.matchMaterial(c.getString(path + "projectile-material", "SNOWBALL"));
        if (material == null || !material.isItem()) {
            material = Material.SNOWBALL;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        meta.setCustomModelData(c.getInt(path + "projectile-custom-model-data"));
        stack.setItemMeta(meta);
        return stack;
    }

    /** Tasto F con il lanciapalle in mano: cambia il tipo di palla. */
    @EventHandler
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        LauncherItem li = plugin.getLauncher(player.getInventory().getItemInMainHand());
        if (li == null) {
            return;
        }
        event.setCancelled(true);
        if (!player.hasPermission("blockmania.use")) {
            return;
        }
        AmmoManager ammo = plugin.getAmmo();
        String type = ammo.cycleSelected(player, li.ammoType());
        plugin.getUpgrades().refreshLaunchers(player);
        plugin.send(player, "ball-selected", "%ball%", plugin.tiny(type),
                "%ammo%", String.valueOf(ammo.getAmmo(player, type)),
                "%max%", String.valueOf(ammo.getAmmoMax(player, type)));
        plugin.playConfiguredSound(player.getLocation(), "ball-switch");
    }

    // ---------- mira: shift = modello 2, rilasciato = modello 1 ----------

    /** Mette il modello giusto al lanciapalle in questo slot. */
    private void setAim(Player player, int slot, boolean aim) {
        PlayerInventory inv = player.getInventory();
        ItemStack item = inv.getItem(slot);
        LauncherItem li = plugin.getLauncher(item);
        if (li != null) {
            plugin.applyModel(item, li, aim);
        }
    }

    private void resetAll(Player player) {
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            setAim(player, i, false);
        }
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (event.isSneaking()) {
            setAim(player, player.getInventory().getHeldItemSlot(), true);
        } else {
            resetAll(player);
        }
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        setAim(player, event.getPreviousSlot(), false);
        if (player.isSneaking()) {
            setAim(player, event.getNewSlot(), true);
        }
    }

    @EventHandler
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        LauncherItem li = plugin.getLauncher(dropped);
        if (li != null) {
            plugin.applyModel(dropped, li, false);
            event.getItemDrop().setItemStack(dropped);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        resetAll(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        resetAll(event.getPlayer());
        lastUse.remove(event.getPlayer().getUniqueId());
        holdUntil.remove(event.getPlayer().getUniqueId());
        lastNoAmmo.remove(event.getPlayer().getUniqueId());
    }
}
