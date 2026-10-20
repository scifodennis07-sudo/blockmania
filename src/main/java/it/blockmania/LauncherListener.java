package it.blockmania;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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

    public LauncherListener(BlockMania plugin) {
        this.plugin = plugin;
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

        // shift + tasto sinistro: apre la GUI dei potenziamenti
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (player.isSneaking() && player.hasPermission("blockmania.use")) {
                event.setCancelled(true);
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
        // segniamo la palla con il suo tipo e il suo danno (potenziamenti compresi)
        ball.getPersistentDataContainer().set(plugin.getAmmoKey(), PersistentDataType.STRING, li.ammoType());
        ball.getPersistentDataContainer().set(plugin.getDamageKey(), PersistentDataType.INTEGER,
                up.damage(player, li.ammoType()));
        plugin.playConfiguredSound(eye, "items." + li.id());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }
}
