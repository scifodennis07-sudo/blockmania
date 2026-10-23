package it.blockmania;

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
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.Material;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class LauncherListener implements Listener {

    private final BlockMania plugin;
    /** Ultimo sparo di ogni giocatore, per ogni oggetto (in millisecondi). */
    private final Map<UUID, Map<String, Long>> lastUse = new HashMap<>();

    private final NamespacedKey bouncesKey;

    public LauncherListener(BlockMania plugin) {
        this.plugin = plugin;
        this.bouncesKey = new NamespacedKey(plugin, "bounces");
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

        event.setCancelled(true);
        pdc.set(bouncesKey, PersistentDataType.INTEGER, done + 1);
        // un filo fuori dal blocco, cosi' non lo ritocca subito
        ball.teleport(ball.getLocation().add(normal.clone().multiply(0.1)));
        ball.setVelocity(reflected);
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

        UpgradeManager up = plugin.getUpgrades();
        long now = System.currentTimeMillis();
        long cooldownMs = (long) (up.cooldown(player, li.cooldownSeconds()) * 1000);
        Map<String, Long> uses = lastUse.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        Long last = uses.get(li.id());
        if (last != null && now - last < cooldownMs) {
            return; // in ricarica: nessun messaggio, il giocatore non deve sapere il tempo
        }

        AmmoManager ammo = plugin.getAmmo();
        if (ammo.getAmmo(player, li.ammoType()) <= 0) {
            plugin.send(player, "no-ammo");
            return;
        }
        ammo.addAmmo(player, li.ammoType(), -1);
        uses.put(li.id(), now);

        Location eye = player.getEyeLocation();
        Snowball ball = player.launchProjectile(Snowball.class,
                eye.getDirection().multiply(up.speed(player, li.speed())));
        applyProjectileModel(ball, li);
        // segniamo la palla con il suo tipo e il suo danno (potenziamenti compresi)
        ball.getPersistentDataContainer().set(plugin.getAmmoKey(), PersistentDataType.STRING, li.ammoType());
        ball.getPersistentDataContainer().set(plugin.getDamageKey(), PersistentDataType.INTEGER,
                up.damage(player, li.ammoType()));
        plugin.playConfiguredSound(eye, "items." + li.id());
    }

    /** La palla lanciata mostra il custom-model-data scelto nel config (items.<id>.projectile-...). */
    private void applyProjectileModel(Snowball ball, LauncherItem li) {
        var c = plugin.getConfig();
        String path = "items." + li.id() + ".";
        if (!c.contains(path + "projectile-custom-model-data")) {
            return;
        }
        Material material = Material.matchMaterial(c.getString(path + "projectile-material", "SNOWBALL"));
        if (material == null || !material.isItem()) {
            material = Material.SNOWBALL;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return;
        }
        meta.setCustomModelData(c.getInt(path + "projectile-custom-model-data"));
        stack.setItemMeta(meta);
        ball.setItem(stack);
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
    }
}
