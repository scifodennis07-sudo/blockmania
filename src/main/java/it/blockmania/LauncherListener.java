package it.blockmania;

import org.bukkit.Location;
import org.bukkit.Sound;
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
    /** Ultimo lancio di ogni giocatore, per ogni oggetto (in millisecondi). */
    private final Map<UUID, Map<String, Long>> lastUse = new HashMap<>();

    public LauncherListener(BlockMania plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
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

        // e' un nostro oggetto: non deve fare altro (es. aprire casse)
        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!player.hasPermission("blockmania.use")) {
            return;
        }

        long now = System.currentTimeMillis();
        long cooldownMs = (long) (li.cooldownSeconds() * 1000);
        Map<String, Long> uses = lastUse.computeIfAbsent(player.getUniqueId(), k -> new HashMap<>());
        Long last = uses.get(li.id());
        if (last != null && now - last < cooldownMs) {
            double remaining = (cooldownMs - (now - last)) / 1000.0;
            player.sendActionBar(plugin.message("cooldown", "%time%", String.format("%.1f", remaining)));
            return;
        }
        uses.put(li.id(), now);

        Location eye = player.getEyeLocation();
        player.launchProjectile(Snowball.class, eye.getDirection().multiply(li.speed()));
        player.getWorld().playSound(eye, Sound.ENTITY_SNOWBALL_THROW, 0.5f, 0.4f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }
}
