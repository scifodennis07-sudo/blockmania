package it.blockmania;

import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Una "WorldEdit" per i blocchi custom: la bacchetta sceglie due angoli e i comandi
 * /bm block set | replace | remove riempiono, sostituiscono o tolgono blocchi in tutta la zona.
 * Il lavoro e' diviso in gruppi per non far laggare il server, e si puo' annullare con undo.
 */
public final class BlockEdit implements Listener {

    public enum Mode { SET, REPLACE, REMOVE }

    /** Com'era un blocco prima della modifica. number 0 = blocco normale (non custom). */
    private record Entry(int x, int y, int z, BlockData before, int number, int hp) {
    }

    private static final class Undo {
        final World world;
        final List<Entry> entries = new ArrayList<>();

        Undo(World world) {
            this.world = world;
        }
    }

    private final BlockMania plugin;
    private final NamespacedKey wandKey;
    private final Map<UUID, Location> pos1 = new HashMap<>();
    private final Map<UUID, Location> pos2 = new HashMap<>();
    private final Map<UUID, Deque<Undo>> history = new HashMap<>();
    private final Set<UUID> busy = new HashSet<>();

    public BlockEdit(BlockMania plugin) {
        this.plugin = plugin;
        this.wandKey = new NamespacedKey(plugin, "wand");
    }

    // ------------------------------------------------------------------
    //  Bacchetta
    // ------------------------------------------------------------------

    public ItemStack createWand() {
        var c = plugin.getConfig();
        Material material = Material.matchMaterial(c.getString("blockwand.material", "STICK"));
        if (material == null || !material.isItem()) {
            material = Material.STICK;
        }
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.text(c.getString("blockwand.name", "&6Bacchetta BlockMania")));
        List<Component> lore = new ArrayList<>();
        for (String line : c.getStringList("blockwand.lore")) {
            lore.add(plugin.text(line));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        if (c.contains("blockwand.custom-model-data") && c.getInt("blockwand.custom-model-data") > 0) {
            meta.setCustomModelData(c.getInt("blockwand.custom-model-data"));
        }
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private boolean isWand(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isWand(event.getItem())) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (!player.hasPermission("blockmania.build")) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block != null) {
            setPos(player, action == Action.LEFT_CLICK_BLOCK ? 1 : 2, block.getLocation());
        }
    }

    /** Con la bacchetta in mano (anche in creativa) non si rompono blocchi. */
    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        if (isWand(event.getPlayer().getInventory().getItemInMainHand())) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pos1.remove(id);
        pos2.remove(id);
        history.remove(id);
    }

    // ------------------------------------------------------------------
    //  Selezione
    // ------------------------------------------------------------------

    public void setPos(Player player, int which, Location location) {
        UUID id = player.getUniqueId();
        Location block = new Location(location.getWorld(), location.getBlockX(), location.getBlockY(), location.getBlockZ());
        if (which == 1) {
            pos1.put(id, block);
        } else {
            pos2.put(id, block);
        }
        String size = "";
        Location a = pos1.get(id);
        Location b = pos2.get(id);
        if (a != null && b != null && a.getWorld() == b.getWorld()) {
            size = "(" + volume(a, b) + " blocchi)";
        }
        plugin.send(player, "pos-set", "%pos%", "pos" + which,
                "%x%", String.valueOf(block.getBlockX()), "%y%", String.valueOf(block.getBlockY()),
                "%z%", String.valueOf(block.getBlockZ()), "%size%", size);
    }

    private static long volume(Location a, Location b) {
        long dx = Math.abs(a.getBlockX() - b.getBlockX()) + 1L;
        long dy = Math.abs(a.getBlockY() - b.getBlockY()) + 1L;
        long dz = Math.abs(a.getBlockZ() - b.getBlockZ()) + 1L;
        return dx * dy * dz;
    }

    // ------------------------------------------------------------------
    //  Operazioni
    // ------------------------------------------------------------------

    /**
     * Esegue set / replace / remove sulla zona scelta. "a" e' il blocco da mettere (set),
     * quello da sostituire (replace) o quello da togliere (remove, null = tutti).
     * "b" e' il blocco nuovo di replace.
     */
    public void run(Player player, Mode mode, Integer a, Integer b) {
        UUID id = player.getUniqueId();
        Location l1 = pos1.get(id);
        Location l2 = pos2.get(id);
        if (l1 == null || l2 == null || l1.getWorld() != l2.getWorld() || l1.getWorld() == null) {
            plugin.sendCommand(player, "missing-selection");
            return;
        }
        long total = volume(l1, l2);
        long limit = Math.max(1, plugin.getConfig().getLong("blockwand.max-blocks", 200000));
        if (total > limit) {
            plugin.sendCommand(player, "selection-too-big", "%count%", String.valueOf(total),
                    "%max%", String.valueOf(limit));
            return;
        }
        if (!busy.add(id)) {
            plugin.sendCommand(player, "edit-busy");
            return;
        }

        World world = l1.getWorld();
        int minX = Math.min(l1.getBlockX(), l2.getBlockX());
        int minY = Math.min(l1.getBlockY(), l2.getBlockY());
        int minZ = Math.min(l1.getBlockZ(), l2.getBlockZ());
        int dx = Math.abs(l1.getBlockX() - l2.getBlockX()) + 1;
        int dy = Math.abs(l1.getBlockY() - l2.getBlockY()) + 1;
        int dz = Math.abs(l1.getBlockZ() - l2.getBlockZ()) + 1;
        int perTick = Math.max(100, plugin.getConfig().getInt("blockwand.blocks-per-tick", 4000));
        Undo undo = new Undo(world);
        BlockManager bm = plugin.getBlocks();

        if (total > perTick * 5L) {
            plugin.send(player, "edit-start", "%count%", String.valueOf(total));
        }

        final long[] cursor = {0};
        final int[] changed = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            long end = Math.min(total, cursor[0] + perTick);
            for (long i = cursor[0]; i < end; i++) {
                int x = minX + (int) (i % dx);
                int y = minY + (int) ((i / dx) % dy);
                int z = minZ + (int) (i / ((long) dx * dy));
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    continue;
                }
                Block block = world.getBlockAt(x, y, z);
                BlockManager.Tracked t = bm.get(block);
                boolean touch;
                switch (mode) {
                    case SET -> touch = true;
                    case REPLACE -> touch = t != null && a != null && t.number() == a;
                    default -> touch = t != null && (a == null || t.number() == a);
                }
                if (!touch) {
                    continue;
                }
                undo.entries.add(new Entry(x, y, z, bm.visibleData(block, t),
                        t == null ? 0 : t.number(), t == null ? 0 : t.hp()));
                bm.remove(block);
                switch (mode) {
                    case SET -> bm.place(block, a);
                    case REPLACE -> bm.place(block, b);
                    default -> block.setType(Material.AIR, false);
                }
                changed[0]++;
            }
            cursor[0] = end;
            if (end >= total) {
                task[0].cancel();
                busy.remove(id);
                if (!undo.entries.isEmpty()) {
                    Deque<Undo> deque = history.computeIfAbsent(id, k -> new ArrayDeque<>());
                    deque.addFirst(undo);
                    int steps = Math.max(1, plugin.getConfig().getInt("blockwand.undo-steps", 5));
                    while (deque.size() > steps) {
                        deque.removeLast();
                    }
                }
                Player online = plugin.getServer().getPlayer(id);
                if (online != null) {
                    if (changed[0] == 0) {
                        plugin.send(online, "edit-nothing");
                    } else {
                        plugin.send(online, "edit-done", "%count%", String.valueOf(changed[0]));
                    }
                }
            }
        }, 1L, 1L);
    }

    /** Annulla l'ultima modifica del giocatore. */
    public void undo(Player player) {
        UUID id = player.getUniqueId();
        Deque<Undo> deque = history.get(id);
        if (deque == null || deque.isEmpty()) {
            plugin.sendCommand(player, "undo-empty");
            return;
        }
        if (!busy.add(id)) {
            plugin.sendCommand(player, "edit-busy");
            return;
        }
        Undo undo = deque.removeFirst();
        int perTick = Math.max(100, plugin.getConfig().getInt("blockwand.blocks-per-tick", 4000));
        BlockManager bm = plugin.getBlocks();
        final int[] cursor = {undo.entries.size() - 1};
        final int[] done = {0};
        final BukkitTask[] task = new BukkitTask[1];
        // si torna indietro dall'ultima modifica alla prima
        task[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            int count = 0;
            while (cursor[0] >= 0 && count < perTick) {
                Entry e = undo.entries.get(cursor[0]--);
                Block block = undo.world.getBlockAt(e.x(), e.y(), e.z());
                bm.restore(block, e.before(), e.number(), e.hp());
                count++;
                done[0]++;
            }
            if (cursor[0] < 0) {
                task[0].cancel();
                busy.remove(id);
                Player online = plugin.getServer().getPlayer(id);
                if (online != null) {
                    plugin.send(online, "undo-done", "%count%", String.valueOf(done[0]));
                }
            }
        }, 1L, 1L);
    }
}
