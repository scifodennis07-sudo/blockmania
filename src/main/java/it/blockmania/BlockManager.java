package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiene traccia dei blocchi custom piazzati (numero del blocco + vita rimasta)
 * e sceglie quale variante del note block mostrare in base alla vita.
 */
public final class BlockManager {

    /** Quante varianti (stati di danno) ha ogni blocco. */
    public static final int VARIANTS = 10;
    /** Quanti blocchi complessivi si possono avere: 1140 modelli / 10 varianti. */
    public static final int MAX_BLOCKS = 114;

    /** Gli strumenti del note block, nello stesso ordine usato per generare il resource pack. */
    private static final String[] INSTRUMENTS = {
            "harp", "basedrum", "snare", "hat", "bass", "flute", "bell", "guitar", "chime",
            "xylophone", "iron_xylophone", "cow_bell", "didgeridoo", "bit", "banjo", "pling",
            "zombie", "skeleton", "creeper", "dragon", "wither_skeleton", "piglin", "custom_head"
    };

    /** Un blocco custom piazzato nel mondo. */
    public static final class Tracked {
        final int number;
        int hp;

        Tracked(int number, int hp) {
            this.number = number;
            this.hp = hp;
        }

        public int number() {
            return number;
        }

        public int hp() {
            return hp;
        }
    }

    private final BlockMania plugin;
    private final File file;
    private final Map<String, Tracked> blocks = new HashMap<>();
    private boolean dirty;

    public BlockManager(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "blocks.yml");
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    public int count() {
        return blocks.size();
    }

    /** Ritorna i dati del blocco se e' un nostro blocco custom, altrimenti null. */
    public Tracked get(Block block) {
        if (block.getType() != Material.NOTE_BLOCK) {
            return null;
        }
        return blocks.get(key(block));
    }

    /** Vita massima del blocco numero N, secondo il config. */
    public int maxHealth(int number) {
        FileConfiguration c = plugin.getConfig();
        int override = c.getInt("blocks.health.overrides." + number, -1);
        if (override > 0) {
            return override;
        }
        double base = c.getDouble("blocks.health.base", 1000);
        double inc = c.getDouble("blocks.health.increase-percent", 50) / 100.0;
        String mode = c.getString("blocks.health.mode", "linear");
        double value;
        if (mode.equalsIgnoreCase("compound")) {
            value = base * Math.pow(1 + inc, number - 1);
        } else {
            value = base * (1 + inc * (number - 1));
        }
        return Math.max(1, (int) Math.round(value));
    }

    /** Quale variante (1..10) mostrare con questa vita. */
    public int stageFor(int hp, int max) {
        int lost = Math.max(0, Math.min(max, max - hp));
        int stage = (int) ((long) lost * VARIANTS / max) + 1;
        return Math.min(VARIANTS, stage);
    }

    /** Lo stato del note block che corrisponde alla variante "numero-stage" (es. 8-7). */
    public BlockData dataFor(int number, int stage) {
        int idx = (number - 1) * VARIANTS + stage; // 1..1140, lo 0 resta il note block classico
        String instrument = INSTRUMENTS[idx / 50];
        int rem = idx % 50;
        int note = rem / 2;
        boolean powered = rem % 2 == 1;
        return Bukkit.createBlockData("minecraft:note_block[instrument=" + instrument
                + ",note=" + note + ",powered=" + powered + "]");
    }

    /** Piazza un nuovo blocco custom con vita piena. */
    public void place(Block block, int number) {
        int max = maxHealth(number);
        blocks.put(key(block), new Tracked(number, max));
        block.setBlockData(dataFor(number, stageFor(max, max)), false);
        dirty = true;
    }

    /** Toglie il blocco dall'elenco (non lo rimuove dal mondo). */
    public void remove(Block block) {
        if (blocks.remove(key(block)) != null) {
            dirty = true;
        }
    }

    /** Rimette il blocco nello stato giusto (se qualcosa l'ha cambiato). */
    public void refresh(Block block) {
        Tracked t = get(block);
        if (t == null) {
            return;
        }
        BlockData expected = dataFor(t.number, stageFor(t.hp, maxHealth(t.number)));
        if (!block.getBlockData().equals(expected)) {
            block.setBlockData(expected, false);
        }
    }

    /**
     * Fa danno al blocco: cambia variante, e se la vita arriva a 0 lo distrugge.
     * Chi lo distrugge guadagna 1 blockshell e 1 blockcoin. Se ha gia' il massimo di
     * blockshell, il colpo che avrebbe distrutto il blocco non fa danno.
     */
    public void damage(Block block, int amount, Player shooter) {
        Tracked t = get(block);
        if (t == null || amount <= 0) {
            return;
        }
        int max = maxHealth(t.number);
        int newHp = t.hp - amount;

        World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        BlockData before = block.getBlockData();

        if (newHp <= 0) {
            if (shooter != null) {
                AmmoManager stats = plugin.getAmmo();
                if (stats.getShell(shooter) >= stats.getShellMax()) {
                    plugin.send(shooter, "shell-full");
                    return; // l'ultimo colpo non fa danno
                }
                FileConfiguration c = plugin.getConfig();
                stats.setShell(shooter, stats.getShell(shooter) + Math.max(0, c.getInt("rewards.blockshell", 1)));
                stats.setBlockCoin(shooter, stats.getBlockCoin(shooter) + Math.max(0, c.getInt("rewards.blockcoin", 1)));
            }
            blocks.remove(key(block));
            dirty = true;
            block.setType(Material.AIR, false);
            int breakParticles = plugin.getConfig().getInt("effects.destroy.particles", 40);
            if (breakParticles > 0) {
                world.spawnParticle(Particle.BLOCK, center, breakParticles, 0.3, 0.3, 0.3, before);
            }
            plugin.playConfiguredSound(center, "effects.destroy");
            if (plugin.getConfig().getBoolean("blocks.drop-on-destroy", false)) {
                world.dropItemNaturally(center, plugin.createBlockItem(t.number));
            }
            return;
        }

        t.hp = newHp;
        dirty = true;
        block.setBlockData(dataFor(t.number, stageFor(t.hp, max)), false);
        int hitParticles = plugin.getConfig().getInt("effects.hit.particles", 8);
        if (hitParticles > 0) {
            world.spawnParticle(Particle.BLOCK, center, hitParticles, 0.25, 0.25, 0.25, before);
        }
        plugin.playConfiguredSound(center, "effects.hit");
    }

    public void load() {
        blocks.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String line : yaml.getStringList("blocks")) {
            String[] parts = line.split(";");
            if (parts.length < 6) {
                continue;
            }
            try {
                int number = Integer.parseInt(parts[4]);
                int hp = Integer.parseInt(parts[5]);
                String k = parts[0] + ";" + Integer.parseInt(parts[1]) + ";"
                        + Integer.parseInt(parts[2]) + ";" + Integer.parseInt(parts[3]);
                blocks.put(k, new Tracked(number, hp));
            } catch (NumberFormatException ignored) {
                plugin.getLogger().warning("Riga non valida in blocks.yml: " + line);
            }
        }
    }

    public void saveIfDirty() {
        if (dirty) {
            save();
        }
    }

    public void save() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Tracked> e : blocks.entrySet()) {
            lines.add(e.getKey() + ";" + e.getValue().number + ";" + e.getValue().hp);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("blocks", lines);
        try {
            plugin.getDataFolder().mkdirs();
            yaml.save(file);
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("Non riesco a salvare blocks.yml: " + ex.getMessage());
        }
    }
}
