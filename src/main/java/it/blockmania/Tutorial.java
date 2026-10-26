package it.blockmania;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.EulerAngle;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * /tutorial: una scena con la telecamera guidata e due NPC (Denny e Andrew) fatti con gli armor stand.
 * Il giocatore resta fermo, la visuale segue chi parla a tempo con l'audio (playsound tutorial1).
 * Tutto e' dentro BlockMania: nessun altro plugin (niente Citizens, niente FancyNpcs).
 */
public final class Tutorial implements Listener, TabExecutor {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final List<String> KEYS = List.of("denny", "andrew");
    private static final List<String> POINTS = List.of("denny", "andrew", "center");

    private record Step(double at, String look, double turn, String text, List<String> commands,
                        String speaker, List<String> actions) {
    }

    /** Un NPC: un armor stand che gira la testa e muove le braccia. */
    private static final class Npc {
        final ArmorStand stand;
        final Location base;
        float yaw;
        float pitch;
        double nextSwing;
        String arm = "";
        double armStart;
        String head = "";
        double headStart;
        double hopStart = -1;

        Npc(ArmorStand stand) {
            this.stand = stand;
            this.base = stand.getLocation().clone();
            this.yaw = base.getYaw();
        }
    }

    private static final class Session {
        final Player player;
        final Location back;
        final Location lock;
        final List<Step> steps;
        final long startNanos = System.nanoTime();
        final double length;
        int next;
        float yaw;
        float pitch;
        float targetYaw;
        float targetPitch;
        double alpha = 0.2;
        BukkitTask task;
        final Map<String, Npc> npcs = new HashMap<>();
        String speaker = "";
        int ticks;
        Component sub;

        Session(Player player, Location back, Location lock, List<Step> steps, double length) {
            this.player = player;
            this.back = back;
            this.lock = lock;
            this.steps = steps;
            this.length = length;
            this.yaw = lock.getYaw();
            this.pitch = lock.getPitch();
            this.targetYaw = yaw;
            this.targetPitch = pitch;
        }
    }

    private final BlockMania plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final NamespacedKey doneKey;
    private final NamespacedKey npcKey;
    private File cfgFile;
    private File dataFile;
    private YamlConfiguration cfg;
    private YamlConfiguration data;

    public Tutorial(BlockMania plugin) {
        this.plugin = plugin;
        this.doneKey = new NamespacedKey(plugin, "tutorial_done");
        this.npcKey = new NamespacedKey(plugin, "tutorial_npc");
    }

    // ------------------------------------------------------------------
    //  File (tutorial.yml = testi e scena, tutorial-data.yml = spot, punti e NPC)
    // ------------------------------------------------------------------

    public void load() {
        plugin.getDataFolder().mkdirs();
        cfgFile = new File(plugin.getDataFolder(), "tutorial.yml");
        dataFile = new File(plugin.getDataFolder(), "tutorial-data.yml");
        YamlConfiguration defaults = null;
        try (InputStreamReader reader = new InputStreamReader(plugin.getResource("tutorial.yml"), StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException | RuntimeException ignored) {
            // senza il file di base si usano solo i valori di default scritti nel codice
        }
        int wanted = defaults == null ? 1 : defaults.getInt("config-version", 1);
        if (cfgFile.exists()) {
            YamlConfiguration old = YamlConfiguration.loadConfiguration(cfgFile);
            if (old.getInt("config-version", 0) < wanted) {
                // file vecchio: lo tiene da parte e scrive quello nuovo
                File bak = new File(plugin.getDataFolder(), "tutorial.yml.vecchio");
                if (bak.exists()) {
                    bak.delete();
                }
                cfgFile.renameTo(bak);
            }
        }
        if (!cfgFile.exists()) {
            plugin.saveResource("tutorial.yml", false);
        }
        cfg = YamlConfiguration.loadConfiguration(cfgFile);
        if (defaults != null) {
            cfg.setDefaults(defaults);
        }
        data = YamlConfiguration.loadConfiguration(dataFile);
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("tutorial-data.yml: " + e.getMessage());
        }
    }

    public void stop() {
        for (Session s : new ArrayList<>(sessions.values())) {
            finish(s, false);
        }
    }

    // ------------------------------------------------------------------
    //  Utilita'
    // ------------------------------------------------------------------

    private Component msg(String key, String... repl) {
        String t = cfg.getString("messages." + key);
        if (t == null) {
            t = key;
        }
        for (int i = 0; i + 1 < repl.length; i += 2) {
            t = t.replace(repl[i], repl[i + 1]);
        }
        return LEGACY.deserialize(t);
    }

    private Location spot() {
        ConfigurationSection s = data.getConfigurationSection("spot");
        if (s == null || !s.contains("world")) {
            return null;
        }
        World w = Bukkit.getWorld(s.getString("world", ""));
        if (w == null) {
            return null;
        }
        return new Location(w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"),
                (float) s.getDouble("yaw"), (float) s.getDouble("pitch"));
    }

    private Location point(String name, World world) {
        ConfigurationSection s = data.getConfigurationSection("points." + name);
        if (s == null || !s.contains("x")) {
            return null;
        }
        World w = Bukkit.getWorld(s.getString("world", ""));
        return new Location(w != null ? w : world, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"));
    }

    private void save(String path, Location l, boolean rotation) {
        data.set(path + ".world", l.getWorld().getName());
        data.set(path + ".x", Math.round(l.getX() * 100) / 100.0);
        data.set(path + ".y", Math.round(l.getY() * 100) / 100.0);
        data.set(path + ".z", Math.round(l.getZ() * 100) / 100.0);
        if (rotation) {
            data.set(path + ".yaw", Math.round(l.getYaw() * 10) / 10.0);
            data.set(path + ".pitch", Math.round(l.getPitch() * 10) / 10.0);
        }
        saveData();
    }

    private List<Step> loadSteps() {
        List<Step> out = new ArrayList<>();
        for (Map<?, ?> m : cfg.getMapList("scene.steps")) {
            double at = m.get("at") instanceof Number n ? n.doubleValue() : 0;
            String look = m.get("look") == null ? "" : String.valueOf(m.get("look"));
            double turn = m.get("turn") instanceof Number n ? n.doubleValue() : 0.7;
            String text = m.get("text") == null ? null : String.valueOf(m.get("text"));
            List<String> cmds = new ArrayList<>();
            if (m.get("commands") instanceof List<?> l) {
                for (Object o : l) {
                    cmds.add(String.valueOf(o));
                }
            }
            List<String> acts = new ArrayList<>();
            if (m.get("do") instanceof List<?> l) {
                for (Object o : l) {
                    acts.add(String.valueOf(o));
                }
            }
            String speaker = m.get("speaker") == null ? "" : String.valueOf(m.get("speaker")).toLowerCase(Locale.ROOT);
            out.add(new Step(at, look, turn, text, cmds, speaker, acts));
        }
        out.sort((a, b) -> Double.compare(a.at(), b.at()));
        return out;
    }

    public boolean isDone(Player p) {
        return p.getPersistentDataContainer().has(doneKey, PersistentDataType.BYTE);
    }

    private static float wrap(float angle) {
        while (angle > 180f) {
            angle -= 360f;
        }
        while (angle < -180f) {
            angle += 360f;
        }
        return angle;
    }

    /** {yaw, pitch} per guardare da "from" verso "to". */
    private static float[] aim(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new float[]{yaw, pitch};
    }

    // ------------------------------------------------------------------
    //  Scena
    // ------------------------------------------------------------------

    public void start(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            return;
        }
        Location back = player.getLocation().clone();
        Location spot = spot();
        Location lock = spot != null ? spot : back.clone();
        if (spot != null) {
            player.teleport(spot, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        Session s = new Session(player, back, lock, loadSteps(), cfg.getDouble("scene.length", 80));
        double startAway = cfg.getDouble("scene.start-away", 0);
        double[] g = geometry(lock);
        if (g != null && startAway != 0) {
            double x = startAway * g[3];
            if (x > g[1] && x < g[2]) {
                x = x - g[1] < g[2] - x ? g[1] : g[2];
            }
            s.yaw = (float) (g[0] + x);
            s.targetYaw = s.yaw;
        }
        sessions.put(player.getUniqueId(), s);
        player.sendMessage(msg("start"));
        String sound = cfg.getString("sound.name", "tutorial1");
        player.playSound(lock, sound, SoundCategory.MASTER,
                (float) cfg.getDouble("sound.volume", 1.0), (float) cfg.getDouble("sound.pitch", 1.0));
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(s), 1L, 1L);
    }

    /** {yawCentro, limiteMin, limiteMax, lato}: la zona davanti al giocatore dove stanno gli NPC (gradi rispetto al centro). */
    private double[] geometry(Location lock) {
        Location center = point("center", lock.getWorld());
        if (center == null) {
            return null;
        }
        Location eye = lock.clone().add(0, 1.62, 0);
        double cy = aim(eye, center)[0];
        double margin = cfg.getDouble("scene.avoid-margin", 30);
        double lo = -margin;
        double hi = margin;
        double side = 1;
        Location dn = point("denny", lock.getWorld());
        Location an = point("andrew", lock.getWorld());
        if (dn != null && an != null) {
            double d = wrap((float) (aim(eye, dn)[0] - cy));
            double a = wrap((float) (aim(eye, an)[0] - cy));
            lo = Math.min(d, a) - margin;
            hi = Math.max(d, a) + margin;
            side = d >= 0 ? 1 : -1;
        }
        return new double[]{cy, lo, hi, side};
    }

    private void tick(Session s) {
        Player p = s.player;
        if (!p.isOnline()) {
            finish(s, false);
            return;
        }
        double t = (System.nanoTime() - s.startNanos) / 1_000_000_000.0;
        while (s.next < s.steps.size() && s.steps.get(s.next).at() <= t) {
            runStep(s, s.steps.get(s.next), t);
            s.next++;
        }
        // la visuale si avvicina dolcemente al bersaglio
        s.yaw += (s.targetYaw - s.yaw) * s.alpha;
        float turns = 360f * Math.round(s.yaw / 360f);
        s.yaw -= turns;
        s.targetYaw -= turns;
        s.pitch += (s.targetPitch - s.pitch) * s.alpha;
        double sway = cfg.getDouble("camera.sway", 0.8);
        float yaw = (float) (s.yaw + Math.sin(t * 0.9) * sway);
        float pitch = (float) (s.pitch + Math.sin(t * 0.6 + 1) * sway * 0.5);
        Location loc = s.lock.clone();
        loc.setYaw(yaw);
        loc.setPitch(Math.max(-89f, Math.min(89f, pitch)));
        p.teleport(loc, PlayerTeleportEvent.TeleportCause.PLUGIN);
        s.ticks++;
        if (s.sub != null && s.ticks % 30 == 0) {
            p.sendActionBar(s.sub);
        }
        tickNpcs(s, t);
        if (t >= s.length) {
            finish(s, true);
        }
    }

    private void runStep(Session s, Step step, double now) {
        Player p = s.player;
        String look = step.look();
        if (look.equals("denny") || look.equals("andrew")) {
            s.speaker = look;
        } else {
            s.speaker = step.speaker();
        }
        if (!look.isEmpty()) {
            Location eye = s.lock.clone().add(0, 1.62, 0);
            if (look.startsWith("away:")) {
                // si guarda in giro ma MAI verso gli NPC e senza passargli davanti: va a girare dietro.
                // i gradi + sono dal lato di Denny, - dal lato di Andrew (0 = verso il centro)
                double[] g = geometry(s.lock);
                if (g != null) {
                    String[] parts = look.substring(5).split(",");
                    try {
                        double x = Double.parseDouble(parts[0].trim()) * g[3];
                        if (x > g[1] && x < g[2]) {
                            x = x - g[1] < g[2] - x ? g[1] : g[2];
                        }
                        double cur = wrap((float) (s.yaw - g[0]));
                        boolean crosses = Math.max(cur, x) > g[1] && Math.min(cur, x) < g[2];
                        double diff = crosses ? (x > cur ? x - cur - 360 : x - cur + 360) : x - cur;
                        s.targetYaw = s.yaw + (float) diff;
                        s.targetPitch = parts.length > 1 ? Float.parseFloat(parts[1].trim()) : 0f;
                        s.alpha = Math.max(0.02, Math.min(0.6, 3.0 / Math.max(1.0, step.turn() * 20.0)));
                    } catch (NumberFormatException ignored) {
                        // valore sbagliato nel file: lascia la visuale dov'e'
                    }
                }
            } else {
                Location target = point(look, s.lock.getWorld());
                if (target != null) {
                    float[] a = aim(eye, target);
                    s.targetYaw = s.yaw + wrap(a[0] - s.yaw);
                    s.targetPitch = a[1];
                    s.alpha = Math.max(0.02, Math.min(0.6, 3.0 / Math.max(1.0, step.turn() * 20.0)));
                }
            }
        }
        if (step.text() != null && cfg.getBoolean("subtitles", true)) {
            // il sottotitolo resta fino al prossimo passo con un testo (o fino a "text: ''" che lo toglie)
            if (step.text().isEmpty()) {
                s.sub = null;
                p.sendActionBar(Component.empty());
            } else {
                s.sub = plugin.withBackground(step.text());
                p.sendActionBar(s.sub);
            }
        }
        for (String cmd : step.commands()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        for (String act : step.actions()) {
            String[] kv = act.split(":", 2);
            if (kv.length < 2) {
                continue;
            }
            Npc n = s.npcs.get(kv[0].trim().toLowerCase(Locale.ROOT));
            if (n == null || !n.stand.isValid()) {
                continue;
            }
            switch (kv[1].trim().toLowerCase(Locale.ROOT)) {
                case "swing" -> {
                    n.arm = ThreadLocalRandom.current().nextBoolean() ? "swingR" : "swingL";
                    n.armStart = now;
                }
                case "wave" -> {
                    n.arm = "wave";
                    n.armStart = now;
                }
                case "hop" -> n.hopStart = now;
                case "nod" -> {
                    n.head = "nod";
                    n.headStart = now;
                }
                case "shake" -> {
                    n.head = "shake";
                    n.headStart = now;
                }
                default -> {
                }
            }
        }
    }

    // ------------------------------------------------------------------
    //  NPC: armor stand con la testa del giocatore
    // ------------------------------------------------------------------

    private Entity npcEntity(String key) {
        String id = data.getString("npc." + key + ".uuid");
        if (id == null) {
            return null;
        }
        try {
            return Bukkit.getEntity(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void tickNpcs(Session s, double t) {
        Location playerEye = s.lock.clone().add(0, 1.62, 0);
        if (s.ticks % 10 == 1) {
            for (String key : KEYS) {
                Npc old = s.npcs.get(key);
                if (old == null || !old.stand.isValid()) {
                    if (npcEntity(key) instanceof ArmorStand st) {
                        s.npcs.put(key, new Npc(st));
                    }
                }
            }
        }
        boolean talk = cfg.getBoolean("npc.talk-gestures", true);
        for (Map.Entry<String, Npc> en : s.npcs.entrySet()) {
            Npc n = en.getValue();
            if (!n.stand.isValid()) {
                continue;
            }
            String key = en.getKey();
            boolean speaking = key.equals(s.speaker);
            // chi parla guarda il giocatore, l'altro guarda chi parla
            Location target = playerEye;
            if (!s.speaker.isEmpty() && !speaking) {
                Npc other = s.npcs.get(s.speaker);
                if (other != null && other.stand.isValid()) {
                    target = other.base.clone().add(0, 1.62, 0);
                }
            }
            Location eye = n.base.clone().add(0, 1.62, 0);
            float[] a = aim(eye, target);
            n.yaw += wrap(a[0] - n.yaw) * 0.15f;
            n.pitch += (a[1] - n.pitch) * 0.15f;

            // testa: inclinazione verso chi guarda + sì/no con la testa
            double headX = Math.toRadians(Math.max(-40, Math.min(40, n.pitch)));
            double headY = 0;
            if (speaking) {
                headY += Math.toRadians(Math.sin(t * 1.7 + key.length()) * 4.0);
                headX += Math.toRadians(Math.sin(t * 2.3) * 3.0);
                if (talk && t >= n.nextSwing) {
                    n.arm = ThreadLocalRandom.current().nextBoolean() ? "swingR" : "swingL";
                    n.armStart = t;
                    n.nextSwing = t + 1.8 + ThreadLocalRandom.current().nextDouble() * 2.7;
                }
            }
            if (!n.head.isEmpty()) {
                double prog = (t - n.headStart) / (n.head.equals("nod") ? 0.8 : 0.9);
                if (prog >= 1.0) {
                    n.head = "";
                } else if (n.head.equals("nod")) {
                    headX += Math.toRadians(Math.sin(prog * Math.PI * 4) * 16.0);
                } else {
                    headY += Math.toRadians(Math.sin(prog * Math.PI * 6) * 25.0);
                }
            }
            n.stand.setHeadPose(new EulerAngle(headX, headY, 0));

            // braccia: un po' di respiro e i gesti
            double breath = Math.sin(t * 1.4 + key.length()) * 0.04;
            double rx = Math.toRadians(-10) + breath;
            double rz = Math.toRadians(10);
            double lx = Math.toRadians(-10) - breath;
            double lz = Math.toRadians(-10);
            if (!n.arm.isEmpty()) {
                double dur = n.arm.equals("wave") ? 1.5 : 0.7;
                double prog = (t - n.armStart) / dur;
                if (prog >= 1.0) {
                    n.arm = "";
                } else if (n.arm.equals("wave")) {
                    double up = Math.min(1.0, prog * 5.0) * Math.min(1.0, (1.0 - prog) * 5.0);
                    rx = Math.toRadians(-160) * up + rx * (1 - up);
                    rz = Math.toRadians(25 * Math.sin(prog * Math.PI * 6)) * up + rz * (1 - up);
                } else if (n.arm.equals("swingL")) {
                    lx = Math.toRadians(-95 * Math.sin(prog * Math.PI)) + lx * (1 - Math.sin(prog * Math.PI));
                } else {
                    rx = Math.toRadians(-95 * Math.sin(prog * Math.PI)) + rx * (1 - Math.sin(prog * Math.PI));
                }
            }
            n.stand.setRightArmPose(new EulerAngle(rx, 0, rz));
            n.stand.setLeftArmPose(new EulerAngle(lx, 0, lz));

            // corpo: gira verso chi guarda; il saltello alza l'NPC per un attimo
            Location pos = n.base.clone();
            pos.setYaw(n.yaw);
            if (n.hopStart >= 0) {
                double prog = (t - n.hopStart) / 0.6;
                if (prog >= 1.0) {
                    n.hopStart = -1;
                } else {
                    pos.setY(pos.getY() + 0.4 * Math.sin(prog * Math.PI));
                }
            }
            n.stand.teleport(pos);
        }
    }

    private static Color parseColor(String hex, Color fallback) {
        if (hex == null) {
            return fallback;
        }
        try {
            return Color.fromRGB(Integer.parseInt(hex.replace("#", "").trim(), 16));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private ItemStack leather(Material mat, Color color) {
        ItemStack item = new ItemStack(mat);
        if (item.getItemMeta() instanceof LeatherArmorMeta meta) {
            meta.setColor(color);
            item.setItemMeta(meta);
        }
        return item;
    }

    /** Toglie gli armor stand del tutorial (quelli salvati e quelli rimasti in giro con la nostra etichetta). */
    private int removeStands() {
        int removed = 0;
        for (String key : KEYS) {
            Entity e = npcEntity(key);
            if (e != null && e.isValid()) {
                e.remove();
                removed++;
            }
            data.set("npc." + key + ".uuid", null);
        }
        for (World w : Bukkit.getWorlds()) {
            for (Entity e : w.getEntitiesByClass(ArmorStand.class)) {
                if (e.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
                    e.remove();
                    removed++;
                }
            }
        }
        saveData();
        return removed;
    }

    private void createNpcs(CommandSender sender) {
        removeStands();
        Location spot = spot();
        for (String key : KEYS) {
            Location pt = point(key, spot != null ? spot.getWorld() : null);
            if (pt == null || pt.getWorld() == null) {
                sender.sendMessage(msg("npc-nopoint", "%name%", key));
                continue;
            }
            String name = cfg.getString("npc." + key + ".name", key);
            String skin = cfg.getString("npc." + key + ".skin", key);
            Location feet = pt.clone().add(0, -1.62, 0);
            if (spot != null) {
                feet.setYaw(aim(pt, spot.clone().add(0, 1.62, 0))[0]);
            }
            Color shirt = parseColor(cfg.getString("npc." + key + ".color"), Color.BLUE);
            Color legs = parseColor(cfg.getString("npc." + key + ".legs"), Color.NAVY);
            Color boots = parseColor(cfg.getString("npc." + key + ".boots"), Color.BLACK);
            ArmorStand stand = feet.getWorld().spawn(feet, ArmorStand.class, a -> {
                a.setArms(true);
                a.setBasePlate(false);
                a.setGravity(false);
                a.setInvulnerable(true);
                a.setPersistent(true);
                a.setSilent(true);
                a.setCanPickupItems(false);
                a.customName(LEGACY.deserialize(name));
                a.setCustomNameVisible(cfg.getBoolean("npc.show-name", true));
                a.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, key);
                a.getEquipment().setChestplate(leather(Material.LEATHER_CHESTPLATE, shirt));
                a.getEquipment().setLeggings(leather(Material.LEATHER_LEGGINGS, legs));
                a.getEquipment().setBoots(leather(Material.LEATHER_BOOTS, boots));
                a.getEquipment().setHelmet(new ItemStack(Material.PLAYER_HEAD));
                for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS,
                        EquipmentSlot.FEET, EquipmentSlot.HAND, EquipmentSlot.OFF_HAND)) {
                    a.addEquipmentLock(slot, ArmorStand.LockType.ADDING_OR_CHANGING);
                    a.addEquipmentLock(slot, ArmorStand.LockType.REMOVING_OR_CHANGING);
                }
            });
            data.set("npc." + key + ".uuid", stand.getUniqueId().toString());
            saveData();
            sender.sendMessage(msg("npc-created", "%name%", name.replaceAll("&.", "")));
            applySkin(stand, skin);
        }
    }

    /** La testa con la skin si prende da Mojang in un thread a parte e poi si mette sull'NPC. */
    private void applySkin(ArmorStand stand, String skinName) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                var profile = Bukkit.createProfile(skinName);
                profile.complete(true);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!stand.isValid()) {
                        return;
                    }
                    ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                    if (head.getItemMeta() instanceof SkullMeta meta) {
                        meta.setPlayerProfile(profile);
                        head.setItemMeta(meta);
                    }
                    stand.getEquipment().setHelmet(head);
                });
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Tutorial: non riesco a prendere la skin " + skinName + ": " + e.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------
    //  Blocchi durante la scena
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s == null || e.getTo() == null) {
            return;
        }
        Location from = e.getFrom();
        Location to = e.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            e.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId())) {
            e.setCancelled(true);
        } else if (e.getEntity() instanceof ArmorStand st
                && st.getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommandPre(PlayerCommandPreprocessEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) {
            String m = e.getMessage().toLowerCase(Locale.ROOT);
            if (!m.startsWith("/tutorial") && !m.startsWith("/bm tutorial") && !m.startsWith("/blockmania tutorial")) {
                e.setCancelled(true);
                e.getPlayer().sendMessage(msg("blocked"));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (sessions.containsKey(e.getPlayer().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStandManipulate(PlayerArmorStandManipulateEvent e) {
        if (e.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onStandClick(PlayerInteractAtEntityEvent e) {
        if (e.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        Session s = sessions.get(e.getPlayer().getUniqueId());
        if (s != null) {
            finish(s, false);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!cfg.getBoolean("auto-start", true) || spot() == null || isDone(p) || !p.hasPermission("blockmania.tutorial")) {
            return;
        }
        long delay = Math.max(1, cfg.getLong("auto-start-delay", 80));
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !isDone(p)) {
                start(p);
            }
        }, delay);
    }

    private void finish(Session s, boolean completed) {
        if (s.task != null) {
            s.task.cancel();
        }
        sessions.remove(s.player.getUniqueId());
        Player p = s.player;
        p.stopSound(cfg.getString("sound.name", "tutorial1"));
        if (cfg.getBoolean("scene.return-after", true) && s.back.getWorld() != null) {
            p.teleport(s.back, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        for (Npc n : s.npcs.values()) {
            if (n.stand.isValid()) {
                n.stand.teleport(n.base);
            }
        }
        p.getPersistentDataContainer().set(doneKey, PersistentDataType.BYTE, (byte) 1);
        if (p.isOnline()) {
            p.sendActionBar(Component.empty());
            p.sendMessage(msg(completed ? "end" : "skipped"));
        }
    }

    // ------------------------------------------------------------------
    //  Comandi:  /tutorial ...   oppure   /bm tutorial ...
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
            case "" -> {
                if (sender instanceof Player p && p.hasPermission("blockmania.tutorial")) {
                    start(p);
                } else {
                    sender.sendMessage("solo i giocatori possono vedere il tutorial");
                }
            }
            case "skip", "salta" -> {
                if (sender instanceof Player p) {
                    Session s = sessions.get(p.getUniqueId());
                    if (s != null) {
                        finish(s, false);
                    } else {
                        sender.sendMessage(msg("not-running"));
                    }
                }
            }
            case "play" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) {
                    sender.sendMessage(msg("unknown-player"));
                    return;
                }
                start(target);
            }
            case "reset" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                Player target = args.length > 1 ? Bukkit.getPlayerExact(args[1]) : sender instanceof Player p ? p : null;
                if (target == null) {
                    sender.sendMessage(msg("unknown-player"));
                    return;
                }
                target.getPersistentDataContainer().remove(doneKey);
                sender.sendMessage(msg("reset", "%player%", target.getName()));
            }
            case "setspot" -> {
                if (!admin || !(sender instanceof Player p)) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                save("spot", p.getLocation(), true);
                sender.sendMessage(msg("spot-saved"));
            }
            case "resetspot" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                data.set("spot", null);
                saveData();
                sender.sendMessage(msg("spot-reset"));
            }
            case "setpoint" -> {
                if (!admin || !(sender instanceof Player p)) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                if (args.length < 2 || !POINTS.contains(args[1].toLowerCase(Locale.ROOT))) {
                    sender.sendMessage(msg("usage-setpoint"));
                    return;
                }
                // il punto e' all'altezza degli occhi di chi si mette dove sta l'NPC
                Location l = p.getLocation().clone().add(0, 1.62, 0);
                save("points." + args[1].toLowerCase(Locale.ROOT), l, false);
                sender.sendMessage(msg("point-saved", "%name%", args[1].toLowerCase(Locale.ROOT)));
            }
            case "resetpoint" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("usage-resetpoint"));
                    return;
                }
                String name = args[1].toLowerCase(Locale.ROOT);
                if (name.equals("all") || name.equals("tutti")) {
                    data.set("points", null);
                } else if (POINTS.contains(name)) {
                    data.set("points." + name, null);
                } else {
                    sender.sendMessage(msg("usage-resetpoint"));
                    return;
                }
                saveData();
                sender.sendMessage(msg("point-reset", "%name%", name));
            }
            case "info" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                sender.sendMessage(msg("info-title"));
                sender.sendMessage(msg("info-line", "%name%", "spot", "%state%", spot() != null ? "&aimpostato" : "&cmanca"));
                for (String name : POINTS) {
                    boolean ok = data.contains("points." + name + ".x");
                    sender.sendMessage(msg("info-line", "%name%", name, "%state%", ok ? "&aimpostato" : "&cmanca"));
                }
                for (String key : KEYS) {
                    Entity e = npcEntity(key);
                    sender.sendMessage(msg("info-line", "%name%", "npc " + key,
                            "%state%", e != null && e.isValid() ? "&apresente" : "&cassente"));
                }
            }
            case "npc" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                createNpcs(sender);
            }
            case "npcremove" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                int n = removeStands();
                sender.sendMessage(msg("npc-removed", "%count%", String.valueOf(n)));
            }
            case "reload" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                load();
                sender.sendMessage(msg("reloaded"));
            }
            default -> sender.sendMessage(msg("usage"));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        return complete(sender, args);
    }

    public List<String> complete(CommandSender sender, String[] args) {
        List<String> out = new ArrayList<>();
        boolean admin = sender.hasPermission("blockmania.tutorial.admin");
        if (args.length == 1) {
            out.add("skip");
            if (admin) {
                out.addAll(List.of("play", "reset", "setspot", "resetspot", "setpoint", "resetpoint", "info",
                        "npc", "npcremove", "reload"));
            }
        } else if (args.length == 2 && admin) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("setpoint")) {
                out.addAll(POINTS);
            } else if (sub.equals("resetpoint")) {
                out.addAll(POINTS);
                out.add("all");
            } else if (sub.equals("play") || sub.equals("reset")) {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    out.add(p.getName());
                }
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
