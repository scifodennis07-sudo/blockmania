package it.blockmania;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.NotePlayEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

/** Tutto quello che riguarda i blocchi custom: piazzare, rompere, proteggerli e danneggiarli. */
public final class CustomBlockListener implements Listener {

    private final BlockMania plugin;

    public CustomBlockListener(BlockMania plugin) {
        this.plugin = plugin;
    }

    private BlockManager blocks() {
        return plugin.getBlocks();
    }

    /** Piazzamento: l'oggetto "N-1" diventa il blocco custom con vita piena. */
    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        ItemStack item = event.getItemInHand();
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        Integer number = item.getItemMeta().getPersistentDataContainer()
                .get(plugin.getBlockKey(), PersistentDataType.INTEGER);
        if (number == null) {
            return;
        }
        blocks().place(event.getBlockPlaced(), number);
    }

    /** Rottura a mano: solo chi ha il permesso blockmania.break. */
    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        BlockManager.Tracked tracked = blocks().get(block);
        if (tracked == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!player.hasPermission("blockmania.break")) {
            event.setCancelled(true);
            plugin.send(player, "cannot-break");
            return;
        }
        event.setDropItems(false);
        blocks().remove(block);
        if (player.getGameMode() != GameMode.CREATIVE) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5),
                    plugin.createBlockItem(tracked.number()));
        }
    }

    /** La palla colpisce un blocco: gli toglie vita. */
    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        Block hit = event.getHitBlock();
        if (hit == null || hit.getType() != Material.NOTE_BLOCK) {
            return;
        }
        String type = event.getEntity().getPersistentDataContainer()
                .get(plugin.getAmmoKey(), PersistentDataType.STRING);
        if (type == null) {
            return;
        }
        Player shooter = event.getEntity().getShooter() instanceof Player p ? p : null;
        Integer stored = event.getEntity().getPersistentDataContainer()
                .get(plugin.getDamageKey(), PersistentDataType.INTEGER);
        int damage = stored != null ? stored : plugin.getAmmo().getDamage(type);
        blocks().damage(hit, damage, shooter);
    }

    /** Impedisce al note block di cambiare strumento/stato quando i vicini cambiano. */
    @EventHandler
    public void onPhysics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() != Material.NOTE_BLOCK) {
            return;
        }
        if (blocks().get(block) == null) {
            return;
        }
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(plugin, () -> blocks().refresh(block));
    }

    /** Niente suono ne' cambio nota. */
    @EventHandler
    public void onNote(NotePlayEvent event) {
        if (blocks().get(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    /**
     * Il tasto destro non deve cambiare la nota. Si nega l'uso del blocco cliccato ma si
     * permette l'uso dell'oggetto in mano: cosi' puoi piazzare altri blocchi (anche altri
     * note block) accanto o sopra senza toccare quello cliccato.
     */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block clicked = event.getClickedBlock();
        if (clicked == null || blocks().get(clicked) == null) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.ALLOW);
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) {
            if (blocks().get(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) {
            if (blocks().get(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Le esplosioni non distruggono i blocchi custom. */
    @EventHandler
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> blocks().get(b) != null);
    }

    @EventHandler
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> blocks().get(b) != null);
    }
}
