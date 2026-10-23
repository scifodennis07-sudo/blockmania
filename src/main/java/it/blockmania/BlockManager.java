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
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiene traccia dei blocchi custom piazzati (numero del blocco + vita rimasta)
 * e sceglie quale variante del note block mostrare in base alla vita.
 */
public final class BlockManager {

    /** Quante varianti (stati di danno) ha ogni blocco. */
    public static final int VARIANTS = 10;
    /** Quanti blocchi complessivi si possono avere: 1140 modelli / 10 varianti. */
    public static final int MAX_BLOCKS = 114;

    /** Custom model data dell'oggetto "numero-variante": (numero-1)*10+variante, da 1 a 1140. */
    public static int itemModelData(int number, int variant) {
        return (number - 1) * VARIANTS + variant;
    }

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

    /** Animazioni di vibrazione in corso (chiave del blocco -> animazione). */
    private final Map<String, Shake> shakes = new HashMap<>();

    private static final Pattern P_INSTRUMENT = Pattern.compile("instrument=([a-z_]+)");
    private static final Pattern P_NOTE = Pattern.compile("note=(\\d+)");
    private static final Pattern P_POWERED = Pattern.compile("powered=(true|false)");

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

    /**
     * Ritorna i dati del blocco se e' un nostro blocco custom, altrimenti null.
     * Un note block con uno stato custom che non e' nel registro (per esempio piazzato con
     * WorldEdit) viene registrato da solo, con la vita che corrisponde al suo stato.
     */
    public Tracked get(Block block) {
        String k = key(block);
        Material type = block.getType();
        if (type == Material.BARRIER) {
            // durante la vibrazione il blocco e' una barrier: conta ancora come il suo blocco
            return shakes.containsKey(k) ? blocks.get(k) : healOrphanBarrier(block, k);
        }
        if (type != Material.NOTE_BLOCK) {
            if (blocks.remove(k) != null) {
                dirty = true; // il blocco e' stato tolto da qualcun altro
            }
            return null;
        }
        Tracked t = blocks.get(k);
        int idx = decodeIndex(block);
        if (idx > 0 && autoDetect()) {
            int number = (idx - 1) / VARIANTS + 1;
            int stage = (idx - 1) % VARIANTS + 1;
            if (t == null || t.number != number) {
                int max = maxHealth(number);
                int lost = (int) Math.ceil((stage - 1) * max / (double) VARIANTS);
                t = new Tracked(number, Math.max(1, max - lost));
                blocks.put(k, t);
                dirty = true;
            }
        }
        return t;
    }

    /** Una barrier senza animazione sopra un blocco registrato: resto di un'animazione interrotta. */
    private Tracked healOrphanBarrier(Block block, String k) {
        Tracked t = blocks.get(k);
        if (t != null) {
            block.setBlockData(dataFor(t.number, stageFor(t.hp, maxHealth(t.number))), false);
        }
        return t;
    }

    private boolean autoDetect() {
        return plugin.getConfig().getBoolean("blocks.auto-detect", true);
    }

    /** Indice 1..1140 dello stato del note block (stesso schema di dataFor), 0 se non e' uno stato custom. */
    private int decodeIndex(Block block) {
        String data = block.getBlockData().getAsString();
        Matcher mi = P_INSTRUMENT.matcher(data);
        Matcher mn = P_NOTE.matcher(data);
        Matcher mp = P_POWERED.matcher(data);
        if (!mi.find() || !mn.find() || !mp.find()) {
            return 0;
        }
        int instrument = -1;
        for (int i = 0; i < INSTRUMENTS.length; i++) {
            if (INSTRUMENTS[i].equals(mi.group(1))) {
                instrument = i;
                break;
            }
        }
        if (instrument < 0) {
            return 0;
        }
        int idx = instrument * 50 + Integer.parseInt(mn.group(1)) * 2 + (mp.group(1).equals("true") ? 1 : 0);
        int limit = Math.min(MAX_BLOCKS, Math.max(1, plugin.getConfig().getInt("blocks.max", MAX_BLOCKS)));
        return idx >= 1 && idx <= limit * VARIANTS ? idx : 0;
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

    /** Lo stato che il blocco deve avere in questo momento (anche durante una vibrazione). */
    public BlockData visibleData(Block block, Tracked t) {
        if (t == null) {
            return block.getBlockData();
        }
        return dataFor(t.number, stageFor(t.hp, maxHealth(t.number)));
    }

    /** Rimette un blocco com'era prima (usato da "undo"): number 0 = blocco normale, non custom. */
    public void restore(Block block, BlockData data, int number, int hp) {
        String k = key(block);
        Shake running = shakes.remove(k);
        if (running != null) {
            running.stop();
        }
        if (number > 0) {
            blocks.put(k, new Tracked(number, hp));
        } else {
            blocks.remove(k);
        }
        dirty = true;
        block.setBlockData(data, false);
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
        String k = key(block);
        if (blocks.remove(k) != null) {
            dirty = true;
        }
        Shake s = shakes.remove(k);
        if (s != null) {
            s.stop(); // il blocco e' gia' stato tolto da chi chiama: niente da ripristinare
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
        damage(block, amount, shooter, HitStyle.NORMAL);
    }

    /** Com'e' fatto il colpo: cambia l'animazione del blocco (normale, esplosione, fantasma). */
    public enum HitStyle { NORMAL, EXPLOSION, GHOST }

    /**
     * Esplosione scenica della explosionball: fa un po' di danno ai blocchi vicini a quello colpito
     * (explosion.damage-percent del danno della palla, in un raggio di explosion.radius blocchi).
     * Il blocco colpito prende il danno normale dalla palla, qui si trattano solo i vicini.
     */
    public void explode(Block center, int damage, Player shooter) {
        explode(center, damage, shooter, plugin.getConfig().getInt("effects.explosion.radius", 1));
    }

    /** Esplosione dell'attacco potenziato: piu grande, con fiamme e fumo. */
    public void powerBlast(Block center, int damage, Player shooter) {
        FileConfiguration c = plugin.getConfig();
        Location at = center.getLocation().add(0.5, 0.5, 0.5);
        World world = center.getWorld();
        world.spawnParticle(Particle.EXPLOSION_EMITTER, at, 1, 0, 0, 0, 0);
        world.spawnParticle(Particle.FLAME, at, c.getInt("power-attack.blast-flames", 40), 0.6, 0.6, 0.6, 0.08);
        world.spawnParticle(Particle.LAVA, at, 8, 0.5, 0.5, 0.5, 0);
        world.spawnParticle(Particle.LARGE_SMOKE, at, 10, 0.5, 0.5, 0.5, 0.03);
        // l'esplosione e' scenica: il danno forte lo fa la palla, qui solo una piccola parte
        explode(center, damage, shooter, c.getInt("power-attack.explosion-radius", 2),
                c.getDouble("power-attack.explosion-damage-percent", 3));
    }

    public void explode(Block center, int damage, Player shooter, int blastRadius) {
        explode(center, damage, shooter, blastRadius,
                plugin.getConfig().getDouble("effects.explosion.damage-percent", 25));
    }

    public void explode(Block center, int damage, Player shooter, int blastRadius, double damagePercent) {
        FileConfiguration c = plugin.getConfig();
        int radius = Math.max(0, Math.min(4, blastRadius));
        double percent = Math.max(0, damagePercent) / 100.0;
        int splash = (int) Math.round(damage * percent);
        Location at = center.getLocation().add(0.5, 0.5, 0.5);
        World world = center.getWorld();
        int particles = c.getInt("effects.explosion.particles", 1);
        if (particles > 0) {
            world.spawnParticle(Particle.EXPLOSION, at, particles, 0.2, 0.2, 0.2, 0);
        }
        plugin.playLayers(at, "effects.explosion-sounds");
        if (splash <= 0 || radius <= 0) {
            return;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    Block near = center.getRelative(dx, dy, dz);
                    if (near.getType() == Material.NOTE_BLOCK || near.getType() == Material.BARRIER) {
                        damage(near, splash, shooter, HitStyle.EXPLOSION, false);
                    }
                }
            }
        }
    }

    public void damage(Block block, int amount, Player shooter, HitStyle style) {
        damage(block, amount, shooter, style, true);
    }

    /** countHit = false per i danni dell'esplosione ai vicini: non contano come colpi di palla. */
    private void damage(Block block, int amount, Player shooter, HitStyle style, boolean countHit) {
        Tracked t = get(block);
        if (t == null || amount <= 0) {
            return;
        }
        if (countHit && shooter != null && plugin.getPower() != null) {
            plugin.getPower().onHit(shooter);
        }
        int max = maxHealth(t.number);
        int newHp = t.hp - amount;
        int stageBefore = stageFor(t.hp, max);

        World world = block.getWorld();
        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        BlockData before = dataFor(t.number, stageBefore);
        FileConfiguration c = plugin.getConfig();
        boolean shake = c.getBoolean("effects.shake.enabled", true);

        if (newHp <= 0) {
            if (shooter != null) {
                AmmoManager stats = plugin.getAmmo();
                if (stats.getShell(shooter) >= stats.getShellMax(shooter)) {
                    plugin.send(shooter, "shell-full");
                    return; // l'ultimo colpo non fa danno
                }
                stats.addShell(shooter, t.number, Math.max(0, c.getInt("rewards.blockshell", 1)));
                stats.setBlockCoin(shooter, stats.getBlockCoin(shooter) + Math.max(0, c.getInt("rewards.blockcoin", 1)));
            }
            blocks.remove(key(block));
            dirty = true;
            if (shake && c.getBoolean("effects.shake.on-break", true)) {
                startShake(block, t.number, stageBefore, true, style);
            } else {
                Shake running = shakes.remove(key(block));
                if (running != null) {
                    running.stop();
                }
                block.setType(Material.AIR, false);
            }
            int breakParticles = c.getInt("effects.destroy.particles", 40);
            if (breakParticles > 0) {
                world.spawnParticle(Particle.BLOCK, center, breakParticles, 0.3, 0.3, 0.3, before);
            }
            plugin.playConfiguredSound(center, "effects.destroy");
            if (style == HitStyle.GHOST) {
                world.spawnParticle(Particle.SOUL, center, 6, 0.3, 0.3, 0.3, 0.02);
                plugin.playLayers(center, "effects.ghost-sounds");
            }
            if (c.getBoolean("blocks.drop-on-destroy", false)) {
                world.dropItemNaturally(center, plugin.createBlockItem(t.number));
            }
            return;
        }

        t.hp = newHp;
        dirty = true;
        int stage = stageFor(t.hp, max);
        if (shake && c.getBoolean("effects.shake.on-hit", true)) {
            startShake(block, t.number, stage, false, style);
        } else {
            block.setBlockData(dataFor(t.number, stage), false);
        }
        if (style == HitStyle.GHOST) {
            // colpo fantasma: anime che salgono invece dei pezzetti di blocco
            world.spawnParticle(Particle.SOUL, center, 2, 0.25, 0.25, 0.25, 0.01);
            plugin.playLayers(center, "effects.ghost-sounds");
        } else {
            int hitParticles = c.getInt("effects.hit.particles", 8);
            if (hitParticles > 0) {
                world.spawnParticle(Particle.BLOCK, center, hitParticles, 0.25, 0.25, 0.25, before);
            }
            plugin.playConfiguredSound(center, style == HitStyle.EXPLOSION ? "effects.explosion-hit" : "effects.hit");
        }
    }

    // ------------------------------------------------------------------
    //  Vibrazione: il blocco diventa una barrier e un "fantasma" con il suo modello trema
    // ------------------------------------------------------------------

    /** Un'animazione in corso su un blocco. */
    private final class Shake {
        final Block block;
        final String key;
        ItemDisplay display;
        BukkitTask task;
        int age;
        int duration;
        boolean breaking;
        HitStyle style = HitStyle.NORMAL;

        Shake(Block block, String key) {
            this.block = block;
            this.key = key;
        }

        /** Ferma l'animazione e toglie il fantasma (non tocca il blocco). */
        void stop() {
            if (task != null) {
                task.cancel();
                task = null;
            }
            if (display != null) {
                display.remove();
                display = null;
            }
        }
    }

    private ItemStack modelItem(int number, int stage) {
        ItemStack stack = new ItemStack(Material.NOTE_BLOCK);
        ItemMeta meta = stack.getItemMeta();
        meta.setCustomModelData(itemModelData(number, stage));
        stack.setItemMeta(meta);
        return stack;
    }

    private void startShake(Block block, int number, int stage, boolean breaking, HitStyle style) {
        FileConfiguration c = plugin.getConfig();
        String k = key(block);
        int ticks = Math.max(2, breaking ? c.getInt("effects.shake.break-ticks", 10)
                : c.getInt("effects.shake.hit-ticks", 8));
        Shake s = shakes.get(k);
        if (s == null) {
            s = new Shake(block, k);
            Location center = block.getLocation().add(0.5, 0.5, 0.5);
            ItemStack item = modelItem(number, stage);
            s.display = block.getWorld().spawn(center, ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setPersistent(false);
                d.setInterpolationDuration(2);
                d.setViewRange(1.5f);
            });
            block.setType(Material.BARRIER, false);
            shakes.put(k, s);
            final Shake shake = s;
            s.task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> tickShake(shake), 2L, 2L);
        } else if (s.display != null) {
            s.display.setItemStack(modelItem(number, stage));
        }
        s.age = 0;
        s.duration = style == HitStyle.NORMAL ? ticks : (int) Math.round(ticks * 1.5);
        s.breaking = s.breaking || breaking;
        s.style = style;
        if (s.display != null) {
            if (style == HitStyle.GHOST) {
                // contorno azzurro: l'aspetto del fantasma
                s.display.setGlowColorOverride(org.bukkit.Color.fromRGB(150, 210, 255));
                s.display.setGlowing(true);
            } else {
                s.display.setGlowing(false);
            }
        }
    }

    private void tickShake(Shake s) {
        s.age += 2;
        if (s.age >= s.duration || s.display == null || !s.display.isValid()) {
            finishShake(s);
            return;
        }
        double p = s.age / (double) s.duration;
        float strength = (float) plugin.getConfig().getDouble("effects.shake.strength", 0.06);
        if (s.style == HitStyle.EXPLOSION) {
            strength *= (float) plugin.getConfig().getDouble("effects.explosion.shake-multiplier", 3.0);
        }
        float damp = (float) (1 - p);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        Vector3f move = new Vector3f(
                (float) (r.nextDouble() - 0.5) * 2 * strength * damp,
                (float) (r.nextDouble() - 0.5) * 2 * strength * damp,
                (float) (r.nextDouble() - 0.5) * 2 * strength * damp);
        float scale = s.breaking && plugin.getConfig().getBoolean("effects.shake.break-shrink", true)
                ? (float) Math.max(0.05, 1 - p) : 1f;
        if (s.style == HitStyle.EXPLOSION && !s.breaking) {
            // il blocco "si gonfia" per l'urto e poi torna normale
            scale *= 1f + (float) (0.3 * Math.sin(Math.PI * p));
        } else if (s.style == HitStyle.GHOST) {
            // sfarfalla (quasi invisibile a tratti) e sale un po' come un'anima
            move = new Vector3f(move.x * 0.3f, move.y * 0.3f + (float) (0.12 * p), move.z * 0.3f);
            if ((s.age / 2) % 2 == 1) {
                scale *= 0.02f;
            }
        }
        s.display.setTransformation(new Transformation(move, new AxisAngle4f(),
                new Vector3f(scale, scale, scale), new AxisAngle4f()));
        s.display.setInterpolationDelay(0);
    }

    /** Fine dell'animazione: il blocco torna normale (o sparisce, se e' stato distrutto). */
    private void finishShake(Shake s) {
        shakes.remove(s.key);
        s.stop();
        Tracked t = blocks.get(s.key);
        if (t != null) {
            s.block.setBlockData(dataFor(t.number, stageFor(t.hp, maxHealth(t.number))), false);
        } else if (s.block.getType() == Material.BARRIER) {
            s.block.setType(Material.AIR, false);
        }
    }

    /** Chiude tutte le animazioni subito (spegnimento del server): niente barrier rimaste in giro. */
    public void finishAllShakes() {
        for (Shake s : new ArrayList<>(shakes.values())) {
            finishShake(s);
        }
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
