package it.blockmania;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Palla fantasma: non rimbalza e non si ferma, attraversa i blocchi e fa danno a tutti
 * i blocchi custom che attraversa (una volta sola ciascuno). Si muove da sola, tick dopo tick.
 */
public final class GhostBall {

    private final BlockMania plugin;
    private final UUID shooter;
    private final World world;
    private final Vector step;       // di quanto avanza a ogni tick
    private final double maxDistance;
    private final int damage;
    private final boolean power;
    private final Set<Long> alreadyHit = new HashSet<>();
    private final ItemDisplay display;
    private Location position;
    private double travelled;
    private BukkitTask task;

    private GhostBall(BlockMania plugin, Player shooter, Location start, Vector direction, double speed,
                      double maxDistance, int damage, ItemStack model, boolean power) {
        this.plugin = plugin;
        this.power = power;
        this.shooter = shooter.getUniqueId();
        this.world = start.getWorld();
        this.step = direction.clone().normalize().multiply(speed);
        this.maxDistance = maxDistance;
        this.damage = damage;
        this.position = start.clone();
        this.display = world.spawn(start, ItemDisplay.class, d -> {
            d.setItemStack(model);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
            d.setBillboard(Display.Billboard.CENTER);
            d.setPersistent(false);
            d.setTeleportDuration(1);
            d.setViewRange(2f);
        });
    }

    /** Lancia una palla fantasma. */
    public static void launch(BlockMania plugin, Player shooter, Location start, Vector direction, double speed,
                              double maxDistance, int damage, ItemStack model) {
        launch(plugin, shooter, start, direction, speed, maxDistance, damage, model, false);
    }

    /** Con power = true e' un colpo dell'attacco potenziato: scia di fuoco. */
    public static void launch(BlockMania plugin, Player shooter, Location start, Vector direction, double speed,
                              double maxDistance, int damage, ItemStack model, boolean power) {
        GhostBall ball = new GhostBall(plugin, shooter, start, direction, speed, maxDistance, damage, model, power);
        ball.task = plugin.getServer().getScheduler().runTaskTimer(plugin, ball::tick, 1L, 1L);
    }

    private void tick() {
        double length = step.length();
        int samples = Math.max(1, (int) Math.ceil(length / 0.4));
        Vector sub = step.clone().multiply(1.0 / samples);
        Player owner = plugin.getServer().getPlayer(shooter);

        for (int i = 0; i < samples; i++) {
            position.add(sub);
            travelled += sub.length();
            if (!world.isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4)) {
                finish();
                return;
            }
            if (power) {
                world.spawnParticle(org.bukkit.Particle.FLAME, position, 3, 0.1, 0.1, 0.1, 0.01);
                world.spawnParticle(org.bukkit.Particle.SMOKE, position, 1, 0.05, 0.05, 0.05, 0.01);
            }
            Block block = position.getBlock();
            if (alreadyHit.add(block.getBlockKey())) {
                // damage() ignora da solo i blocchi che non sono custom
                plugin.getBlocks().damage(block, damage, owner, BlockManager.HitStyle.GHOST);
            }
            if (travelled >= maxDistance) {
                finish();
                return;
            }
        }
        if (display != null && display.isValid()) {
            display.teleport(position);
        }
    }

    private void finish() {
        if (task != null) {
            task.cancel();
        }
        if (display != null) {
            display.remove();
        }
    }
}
