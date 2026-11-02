package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Denny e Andrew: due NPC "a cubi" con le animazioni del rig (ItemDisplay col modello del resource pack).
 * Stanno fermi con l'animazione di riposo, guardano chi si avvicina e salutano. Il trailer (scena con audio)
 * non c'e' ancora: /tutorial per ora lo dice solo.
 */
public final class Tutorial implements Listener, CommandExecutor, TabCompleter {

    private static final List<String> KEYS = List.of("denny", "andrew");

    private final BlockMania plugin;
    private final Map<String, ModelNpc> live = new LinkedHashMap<>();
    private final Map<String, Double> greeted = new LinkedHashMap<>();
    private NpcRig rig;
    private File dataFile;
    private YamlConfiguration data;
    private BukkitTask task;
    private long lastNanos;

    public Tutorial(BlockMania plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    //  File: tutorial-data.yml tiene la posizione degli NPC (npc.<nome>.world/x/y/z/yaw)
    // ------------------------------------------------------------------

    public void load() {
        plugin.getDataFolder().mkdirs();
        dataFile = new File(plugin.getDataFolder(), "tutorial-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        rig = NpcRig.load(plugin);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("tutorial-data.yml: " + e.getMessage());
        }
    }

    /** Da chiamare quando i mondi sono pronti: rimette in piedi gli NPC salvati. */
    public void spawnSaved() {
        stop();
        for (String key : KEYS) {
            Location feet = savedFeet(key);
            if (feet != null) {
                put(key, feet, data.getDouble("npc." + key + ".yaw"));
            }
        }
        lastNanos = System.nanoTime();
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (ModelNpc n : live.values()) {
            n.remove();
        }
        live.clear();
    }

    private Location savedFeet(String key) {
        ConfigurationSection c = data.getConfigurationSection("npc." + key);
        if (c == null || !c.contains("x")) {
            return null;
        }
        World w = Bukkit.getWorld(c.getString("world", ""));
        if (w == null) {
            return null;
        }
        return new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"));
    }

    /**
     * Il yaw salvato e' quello di Minecraft (0 = sud); il rig guarda verso -Z con yaw 0, quindi si toglie 180.
     */
    private void put(String key, Location feet, double mcYaw) {
        ModelNpc old = live.remove(key);
        if (old != null) {
            old.remove();
        }
        ModelNpc n = new ModelNpc(key, key, feet, mcYaw - 180.0, rig);
        live.put(key, n);
        if (n.chunkLoaded()) {
            n.spawn();
        }
    }

    // ------------------------------------------------------------------
    //  Vita degli NPC
    // ------------------------------------------------------------------

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min(0.25, (now - lastNanos) / 1.0e9);
        lastNanos = now;
        double clock = now / 1.0e9;
        for (ModelNpc n : live.values()) {
            if (!n.chunkLoaded()) {
                continue;
            }
            if (!n.alive()) {
                n.spawn();
            }
            Player near = n.nearest(plugin.getConfig().getDouble("npc.look-range", 10.0));
            if (near != null) {
                Location eye = near.getEyeLocation();
                n.lookAt(eye.getX(), eye.getY(), eye.getZ());
                Double last = greeted.get(n.key + near.getUniqueId());
                double greetRange = plugin.getConfig().getDouble("npc.greet-range", 5.0);
                if (!n.busy() && near.getLocation().distanceSquared(n.feet) < greetRange * greetRange
                        && (last == null || clock - last > 60.0)) {
                    greeted.put(n.key + near.getUniqueId(), clock);
                    n.play("wave", 1.0);
                }
            } else {
                n.stopLooking();
            }
            n.tick(dt);
        }
    }

    // ------------------------------------------------------------------
    //  Comandi:  /tutorial   /tutorial npc   /tutorial npcremove   /tutorial anim <npc> <clip>
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        handle(sender, args);
        return true;
    }

    public void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("blockmania.tutorial.admin");
        switch (sub) {
            case "" -> sender.sendMessage("§7il tutorial arriva presto");
            case "npc" -> {
                if (!admin) {
                    sender.sendMessage("§cnon hai il permesso");
                } else if (sender instanceof Player p) {
                    createHere(p, args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "");
                } else {
                    sender.sendMessage("solo i giocatori possono mettere gli NPC");
                }
            }
            case "npcremove", "remove" -> {
                if (!admin) {
                    sender.sendMessage("§cnon hai il permesso");
                    return;
                }
                String key = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
                for (String k : KEYS) {
                    if (key.isEmpty() || key.equals(k) || key.equals("all")) {
                        ModelNpc n = live.remove(k);
                        if (n != null) {
                            n.remove();
                        }
                        data.set("npc." + k, null);
                    }
                }
                saveData();
                sender.sendMessage("§aNPC tolti");
            }
            case "anim" -> {
                if (!admin) {
                    sender.sendMessage("§cnon hai il permesso");
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage("§cuso: /tutorial anim <denny|andrew|all> <animazione>   (" + String.join(", ", rig.clipNames()) + ")");
                    return;
                }
                String clip = args[2].toLowerCase(Locale.ROOT);
                if (!rig.hasClip(clip)) {
                    sender.sendMessage("§canimazione sconosciuta: " + String.join(", ", rig.clipNames()));
                    return;
                }
                int count = 0;
                for (ModelNpc n : live.values()) {
                    if (args[1].equalsIgnoreCase("all") || args[1].equalsIgnoreCase(n.key)) {
                        n.play(clip, 1.0);
                        count++;
                    }
                }
                sender.sendMessage(count == 0 ? "§cnessun NPC con quel nome" : "§aanimazione " + clip);
            }
            case "clips", "animazioni" -> sender.sendMessage("§7animazioni: §f" + String.join(", ", rig.clipNames()));
            default -> sender.sendMessage("§7/tutorial npc [denny|andrew]  §8|  §7/tutorial npcremove [nome]  §8|  §7/tutorial anim <nome> <animazione>");
        }
    }

    /** Mette l'NPC dove sta il giocatore, con la faccia verso di lui (sposta anche l'altro se non ne indichi uno). */
    private void createHere(Player p, String which) {
        List<String> keys = which.isEmpty() ? KEYS : List.of(which);
        for (String key : keys) {
            if (!KEYS.contains(key)) {
                p.sendMessage("§cnome sconosciuto: denny o andrew");
                return;
            }
        }
        Location feet = p.getLocation();
        // se ne metto due, uno a sinistra e uno a destra di dove guardo
        double yawRad = Math.toRadians(feet.getYaw());
        double rx = Math.cos(yawRad);
        double rz = Math.sin(yawRad);
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            Location at = feet.clone();
            if (keys.size() > 1) {
                double side = i == 0 ? -0.8 : 0.8;
                at.add(rx * side, 0, rz * side);
            }
            // il giocatore guarda in avanti: l'NPC guarda verso di lui
            float npcYaw = feet.getYaw() + 180f;
            data.set("npc." + key + ".world", at.getWorld().getName());
            data.set("npc." + key + ".x", Math.round(at.getX() * 100) / 100.0);
            data.set("npc." + key + ".y", Math.round(at.getY() * 100) / 100.0);
            data.set("npc." + key + ".z", Math.round(at.getZ() * 100) / 100.0);
            data.set("npc." + key + ".yaw", Math.round(npcYaw * 10) / 10.0);
            put(key, at, npcYaw);
        }
        saveData();
        if (task == null) {
            lastNanos = System.nanoTime();
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
        }
        p.sendMessage("§aNPC creati");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        return complete(sender, args);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("blockmania.tutorial.admin")) {
            return out;
        }
        if (args.length == 1) {
            out.addAll(List.of("npc", "npcremove", "anim", "clips"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("npc") || args[0].equalsIgnoreCase("npcremove"))) {
            out.addAll(KEYS);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("anim")) {
            out.addAll(KEYS);
            out.add("all");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("anim")) {
            out.addAll(rig.clipNames());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
