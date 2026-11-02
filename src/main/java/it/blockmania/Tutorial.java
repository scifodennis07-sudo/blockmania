package it.blockmania;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Denny e Andrew: due NPC "a cubi" con le animazioni del rig (ItemDisplay col modello del resource pack).
 * Stanno fermi con l'animazione di riposo, guardano chi si avvicina e salutano. /tutorial fa partire la scena:
 * il giocatore resta fermo, la visuale segue chi parla a tempo con l'audio (tutorial-scene.yml), con i sottotitoli
 * e le animazioni degli NPC.
 */
public final class Tutorial implements Listener, CommandExecutor, TabCompleter {

    private static final List<String> KEYS = List.of("denny", "andrew");

    private record Step(double at, String look, double turn, boolean hasText, String text, String talk,
                        List<String> actions) {
    }

    private static final class Session {
        final Player player;
        final Location back;
        final Location spot;
        final long startNanos = System.nanoTime();
        int next;
        float yaw;
        float pitch;
        float targetYaw;
        float targetPitch;
        double turn = 0.4;
        long lastNanos = System.nanoTime();
        int ticks;
        Component sub;
        BukkitTask task;
        boolean invulnerable;
        float walkSpeed;

        Session(Player player, Location back, Location spot) {
            this.player = player;
            this.back = back;
            this.spot = spot;
            this.yaw = spot.getYaw();
            this.pitch = spot.getPitch();
            this.targetYaw = yaw;
            this.targetPitch = pitch;
        }
    }

    private final BlockMania plugin;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private final NamespacedKey seenKey;
    private List<Step> steps = new ArrayList<>();
    private double sceneLength;
    private final Map<String, ModelNpc> live = new LinkedHashMap<>();
    private final Map<String, Double> greeted = new LinkedHashMap<>();
    private NpcRig rig;
    private File dataFile;
    private YamlConfiguration data;
    private BukkitTask task;
    private long lastNanos;

    public Tutorial(BlockMania plugin) {
        this.plugin = plugin;
        this.seenKey = new NamespacedKey(plugin, "tutorial_seen");
    }

    // ------------------------------------------------------------------
    //  File: tutorial-data.yml tiene la posizione degli NPC (npc.<nome>.world/x/y/z/yaw)
    // ------------------------------------------------------------------

    public void load() {
        plugin.getDataFolder().mkdirs();
        dataFile = new File(plugin.getDataFolder(), "tutorial-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        rig = NpcRig.load(plugin);
        loadScene();
    }

    /** La scena sta dentro il plugin (tutorial-scene.yml): tempi dei sottotitoli, della visuale e delle animazioni. */
    private void loadScene() {
        steps = new ArrayList<>();
        var in = plugin.getResource("tutorial-scene.yml");
        if (in == null) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        sceneLength = y.getDouble("length", 60);
        for (Map<?, ?> m : y.getMapList("steps")) {
            double at = m.get("at") instanceof Number n ? n.doubleValue() : 0;
            String look = m.get("look") == null ? null : String.valueOf(m.get("look"));
            double turn = m.get("turn") instanceof Number n ? n.doubleValue() : 0.4;
            boolean hasText = m.containsKey("text");
            String text = m.get("text") == null ? "" : String.valueOf(m.get("text"));
            String talk = m.get("talk") == null ? null : String.valueOf(m.get("talk"));
            List<String> acts = new ArrayList<>();
            if (m.get("do") instanceof List<?> l) {
                for (Object o : l) {
                    acts.add(String.valueOf(o));
                }
            }
            steps.add(new Step(at, look, turn, hasText, text, talk, acts));
        }
        steps.sort((a, b) -> Double.compare(a.at(), b.at()));
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
        ModelNpc.flip = plugin.getConfig().getBoolean("npc.flip", true);
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
        for (Session sess : new ArrayList<>(sessions.values())) {
            endScene(sess, false);
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (ModelNpc n : live.values()) {
            n.remove();
        }
        live.clear();
    }

    /** Braccia sottili (slim) o larghe (large): scelta con /tutorial skin, di base Andrew e' slim. */
    private boolean isSlim(String key) {
        return data.getBoolean("npc." + key + ".slim", key.equals("andrew"));
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
        String model = key + (isSlim(key) ? "_slim" : "_large");
        ModelNpc n = new ModelNpc(key, model, feet, mcYaw - 180.0, rig);
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
            if (n.chunkLoaded() && !n.alive()) {
                n.spawn();
            }
        }
        if (sessions.isEmpty() && plugin.getConfig().getBoolean("npc.alive", true)) {
            think(clock);
        }
        for (ModelNpc n : live.values()) {
            if (n.chunkLoaded()) {
                n.tick(dt);
            }
        }
    }

    // ------------------------------------------------------------------
    //  Cervello degli NPC: camminano, vanno dal giocatore, chiacchierano tra loro
    // ------------------------------------------------------------------

    private static final class Brain {
        String mode = "idle";
        double next = 2 + Math.random() * 4;
        double until;
        double start;
        double lastApproach = -1000;
        double lastRetarget;
        Player target;
    }

    private record DuoDef(String a, String b, double dist) {
    }

    private static final class Duo {
        DuoDef def;
        boolean swap;
        String phase = "walk";
        double start;
        double t;
    }

    private static final List<DuoDef> DUOS = List.of(
            new DuoDef("handshake", "handshake", 1.3), new DuoDef("high_five", "high_five", 1.35),
            new DuoDef("hug", "hug", 0.95), new DuoDef("fist_bump", "fist_bump", 1.25),
            new DuoDef("pat_shoulder", "laugh", 1.0), new DuoDef("shrug", "nod", 1.8),
            new DuoDef("facepalm", "laugh", 1.6), new DuoDef("clap", "cheer", 1.6),
            new DuoDef("point", "look_around", 1.7), new DuoDef("warn_finger", "shrug", 1.4));
    private static final List<String> SOLO = List.of("look_around", "scratch_head", "stretch", "shrug", "sigh",
            "tilt", "nod", "crossed_arms", "look_around", "tilt");
    private final Map<String, Brain> brains = new HashMap<>();
    private Duo duo;

    private static double rnd(double a, double b) {
        return a + Math.random() * (b - a);
    }

    private void think(double clock) {
        if (duo != null) {
            runDuo(clock);
            return;
        }
        for (ModelNpc n : live.values()) {
            if (!n.chunkLoaded() || !n.alive()) {
                continue;
            }
            Brain b = brains.computeIfAbsent(n.key, k -> new Brain());
            Player near = n.nearest(plugin.getConfig().getDouble("npc.look-range", 10.0));
            if (near != null && !near.getWorld().equals(n.feet.getWorld())) {
                near = null;
            }
            switch (b.mode) {
                case "idle" -> brainIdle(n, b, clock, near);
                case "wander" -> {
                    if (near != null) {
                        Location eye = near.getEyeLocation();
                        n.lookAt(eye.getX(), eye.getY(), eye.getZ());
                    }
                    if (!n.walkingNow() || clock > b.until) {
                        n.stopWalking();
                        b.mode = "idle";
                        b.next = clock + rnd(2, 6);
                    }
                }
                case "toPlayer" -> brainToPlayer(n, b, clock);
                case "chat" -> brainChat(n, b, clock);
                case "home" -> {
                    if (!n.walkingNow() || clock > b.until) {
                        n.stopWalking();
                        n.stopFacing();
                        b.mode = "idle";
                        b.next = clock + rnd(3, 7);
                    }
                }
                default -> b.mode = "idle";
            }
        }
    }

    private void brainIdle(ModelNpc n, Brain b, double clock, Player near) {
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
        if (clock < b.next || n.busy()) {
            return;
        }
        if (near != null && n.feet.distanceSquared(near.getLocation()) > 9 && clock - b.lastApproach > 45
                && Math.random() < 0.6) {
            b.mode = "toPlayer";
            b.target = near;
            b.start = clock;
            b.until = clock + 18;
            b.lastApproach = clock;
            b.lastRetarget = -10;
            return;
        }
        double r = Math.random();
        if (r < 0.2 && startDuo(clock)) {
            return;
        }
        if (r < 0.6) {
            double radius = plugin.getConfig().getDouble("npc.wander-radius", 3.5);
            double ang = Math.random() * Math.PI * 2;
            double dist = rnd(1.0, radius);
            n.walkTo(n.home.getX() + Math.cos(ang) * dist, n.home.getZ() + Math.sin(ang) * dist, rnd(1.0, 1.5));
            b.mode = "wander";
            b.until = clock + 10;
            return;
        }
        n.play(SOLO.get((int) (Math.random() * SOLO.size())), 1.0);
        b.next = clock + rnd(3, 8);
    }

    private void brainToPlayer(ModelNpc n, Brain b, double clock) {
        Player p = b.target;
        if (p == null || !p.isOnline() || !p.getWorld().equals(n.feet.getWorld()) || clock > b.until
                || p.getLocation().distanceSquared(n.feet) > 400) {
            toHome(n, b, clock);
            return;
        }
        Location pl = p.getLocation();
        Location eye = p.getEyeLocation();
        n.lookAt(eye.getX(), eye.getY(), eye.getZ());
        double dx = n.feet.getX() - pl.getX();
        double dz = n.feet.getZ() - pl.getZ();
        double d = Math.hypot(dx, dz);
        if (d < 2.4) {
            n.stopWalking();
            n.faceTo(pl.getX(), pl.getZ());
            b.mode = "chat";
            b.start = clock;
            b.until = clock + rnd(8, 16);
            b.next = clock;
            n.play(Math.random() < 0.6 ? "wave" : "nod", 1.0);
            return;
        }
        if (clock - b.lastRetarget > 0.5) {
            b.lastRetarget = clock;
            n.walkTo(pl.getX() + dx / d * 2.0, pl.getZ() + dz / d * 2.0, 1.8);
        }
    }

    private void brainChat(ModelNpc n, Brain b, double clock) {
        Player p = b.target;
        if (p == null || !p.isOnline() || !p.getWorld().equals(n.feet.getWorld()) || clock > b.until) {
            n.talking(false);
            toHome(n, b, clock);
            return;
        }
        Location pl = p.getLocation();
        Location eye = p.getEyeLocation();
        n.lookAt(eye.getX(), eye.getY(), eye.getZ());
        n.faceTo(pl.getX(), pl.getZ());
        if (n.feet.distanceSquared(pl) > 16) {
            n.talking(false);
            b.mode = "toPlayer";
            b.until = clock + 10;
            b.lastRetarget = -10;
            return;
        }
        n.talking(((int) ((clock - b.start) / 2.5)) % 2 == 0);
        if (clock >= b.next && !n.busy()) {
            n.play(List.of("nod", "tilt", "point_self", "spread", "shrug", "laugh", "point").get((int) (Math.random() * 7)), 1.0);
            b.next = clock + rnd(3, 6);
        }
    }

    private void toHome(ModelNpc n, Brain b, double clock) {
        n.talking(false);
        n.walkTo(n.home.getX(), n.home.getZ(), 1.3);
        b.mode = "home";
        b.until = clock + 15;
    }

    private boolean startDuo(double clock) {
        ModelNpc a = live.get("denny");
        ModelNpc c = live.get("andrew");
        if (a == null || c == null || !a.alive() || !c.alive() || !a.chunkLoaded() || !c.chunkLoaded()
                || a.feet.getWorld() != c.feet.getWorld() || a.feet.distanceSquared(c.feet) > 196) {
            return false;
        }
        Brain ba = brains.computeIfAbsent("denny", k -> new Brain());
        Brain bc = brains.computeIfAbsent("andrew", k -> new Brain());
        if (!ba.mode.equals("idle") || !bc.mode.equals("idle") || a.busy() || c.busy()) {
            return false;
        }
        Duo d = new Duo();
        d.def = DUOS.get((int) (Math.random() * DUOS.size()));
        d.swap = Math.random() < 0.5;
        d.start = clock;
        double mx = (a.feet.getX() + c.feet.getX()) / 2;
        double mz = (a.feet.getZ() + c.feet.getZ()) / 2;
        double dx = c.feet.getX() - a.feet.getX();
        double dz = c.feet.getZ() - a.feet.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 0.01) {
            dx = 1;
            dz = 0;
            len = 1;
        }
        dx /= len;
        dz /= len;
        double h = d.def.dist() / 2;
        a.walkTo(mx - dx * h, mz - dz * h, 1.4);
        c.walkTo(mx + dx * h, mz + dz * h, 1.4);
        ba.mode = "duo";
        bc.mode = "duo";
        duo = d;
        return true;
    }

    private void endDuo(double clock) {
        duo = null;
        for (String k : KEYS) {
            ModelNpc n = live.get(k);
            Brain b = brains.computeIfAbsent(k, x -> new Brain());
            if (n != null) {
                n.stopFacing();
                n.stopLooking();
                n.talking(false);
                n.walkTo(n.home.getX(), n.home.getZ(), 1.3);
            }
            b.mode = "home";
            b.until = clock + 15;
        }
    }

    private void runDuo(double clock) {
        ModelNpc a = live.get("denny");
        ModelNpc c = live.get("andrew");
        if (a == null || c == null || !a.alive() || !c.alive()) {
            endDuo(clock);
            return;
        }
        switch (duo.phase) {
            case "walk" -> {
                if ((!a.walkingNow() && !c.walkingNow()) || clock - duo.start > 10) {
                    a.stopWalking();
                    c.stopWalking();
                    a.faceTo(c.feet.getX(), c.feet.getZ());
                    c.faceTo(a.feet.getX(), a.feet.getZ());
                    a.lookAt(c.feet.getX(), c.feet.getY() + 1.62, c.feet.getZ());
                    c.lookAt(a.feet.getX(), a.feet.getY() + 1.62, a.feet.getZ());
                    duo.phase = "face";
                    duo.t = clock + 0.8;
                }
            }
            case "face" -> {
                if (clock >= duo.t) {
                    String ca = duo.swap ? duo.def.b() : duo.def.a();
                    String cb = duo.swap ? duo.def.a() : duo.def.b();
                    a.play(ca, 1.0);
                    c.play(cb, 1.0);
                    duo.phase = "act";
                    duo.t = clock + Math.max(rig.length(ca), rig.length(cb)) + 0.5;
                }
            }
            case "act" -> {
                if (clock >= duo.t) {
                    endDuo(clock);
                }
            }
            default -> endDuo(clock);
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
            case "" -> {
                if (sender instanceof Player p) {
                    startScene(p);
                } else {
                    sender.sendMessage("solo i giocatori vedono il tutorial");
                }
            }
            case "skip" -> {
                if (sender instanceof Player p && sessions.containsKey(p.getUniqueId())) {
                    endScene(sessions.get(p.getUniqueId()), true);
                }
            }
            case "setpoint", "setspot" -> {
                if (!admin) {
                    sender.sendMessage("§cnon hai il permesso");
                    return;
                }
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("§csolo i giocatori possono salvare un punto");
                    return;
                }
                String w = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : (sub.equals("setspot") ? "giocatore" : "");
                if (w.isEmpty()) {
                    sender.sendMessage("§cmettiti dove vuoi il punto e scrivi quale: §f/tutorial setpoint denny§c, §f/tutorial setpoint andrew§c oppure §f/tutorial setpoint giocatore");
                } else if (w.equals("giocatore") || w.equals("player") || w.equals("centro") || w.equals("center")) {
                    Location l = p.getLocation();
                    data.set("spot.world", l.getWorld().getName());
                    data.set("spot.x", Math.round(l.getX() * 100) / 100.0);
                    data.set("spot.y", Math.round(l.getY() * 100) / 100.0);
                    data.set("spot.z", Math.round(l.getZ() * 100) / 100.0);
                    data.set("spot.yaw", Math.round(l.getYaw() * 10) / 10.0);
                    data.set("spot.pitch", Math.round(l.getPitch() * 10) / 10.0);
                    saveData();
                    sender.sendMessage("§aposto del giocatore salvato (durante la scena starà qui e guarderà dove guardi tu adesso)");
                } else if (KEYS.contains(w)) {
                    placeNpc(p, w);
                    sender.sendMessage("§a" + w + " messo qui, guarda dove guardi tu adesso");
                } else {
                    sender.sendMessage("§cpunto sconosciuto: scrivi denny, andrew oppure giocatore");
                }
            }
            case "resetpoint", "resetspot" -> {
                if (!admin) {
                    sender.sendMessage("§cnon hai il permesso");
                    return;
                }
                String w = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : (sub.equals("resetspot") ? "giocatore" : "");
                if (w.equals("giocatore") || w.equals("player")) {
                    data.set("spot", null);
                    saveData();
                    sender.sendMessage("§aposto del giocatore tolto");
                } else if (KEYS.contains(w)) {
                    ModelNpc n = live.remove(w);
                    if (n != null) {
                        n.remove();
                    }
                    data.set("npc." + w, null);
                    saveData();
                    sender.sendMessage("§a" + w + " tolto");
                } else {
                    sender.sendMessage("§cscrivi quale punto togliere: denny, andrew oppure giocatore");
                }
            }
            case "skin" -> {
                if (!admin) {
                    sender.sendMessage("§cnon hai il permesso");
                    return;
                }
                if (args.length < 2 || !KEYS.contains(args[1].toLowerCase(Locale.ROOT))) {
                    sender.sendMessage("§cscrivi di quale NPC vuoi cambiare la skin: denny oppure andrew");
                    return;
                }
                String key = args[1].toLowerCase(Locale.ROOT);
                if (args.length < 3) {
                    sender.sendMessage("§cscrivi il tipo di skin di " + key + ": §fslim§c (braccia sottili, 3 pixel) oppure §flarge§c (braccia larghe, 4 pixel). Ora è " + (isSlim(key) ? "slim" : "large"));
                    return;
                }
                String t = args[2].toLowerCase(Locale.ROOT);
                boolean slim;
                if (t.equals("slim") || t.equals("sottile") || t.equals("sottili")) {
                    slim = true;
                } else if (t.equals("large") || t.equals("largo") || t.equals("larga") || t.equals("classic") || t.equals("normale")) {
                    slim = false;
                } else {
                    sender.sendMessage("§cscrivi slim (sottile) oppure large (largo)");
                    return;
                }
                data.set("npc." + key + ".slim", slim);
                saveData();
                ModelNpc old = live.get(key);
                if (old != null) {
                    Location at = old.feet.clone();
                    put(key, old.home.clone(), data.getDouble("npc." + key + ".yaw"));
                    live.get(key).feet.setX(at.getX());
                    live.get(key).feet.setZ(at.getZ());
                }
                sender.sendMessage("§a" + key + " ora ha la skin " + (slim ? "slim" : "large"));
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
            case "anim", "animazione" -> {
                if (!admin) {
                    sender.sendMessage("§cnon hai il permesso");
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage("§cscrivi quale NPC deve muoversi: denny, andrew oppure all (tutti e due)");
                    return;
                }
                if (args.length < 3) {
                    sender.sendMessage("§cscrivi quale animazione vuoi, scegli tra: " + String.join(", ", rig.clipNames()));
                    return;
                }
                String clip = NpcRig.internal(args[2]);
                if (!rig.hasClip(clip)) {
                    sender.sendMessage("§canimazione sconosciuta, scegli tra: " + String.join(", ", rig.clipNames()));
                    return;
                }
                int count = 0;
                for (ModelNpc n : live.values()) {
                    if (args[1].equalsIgnoreCase("all") || args[1].equalsIgnoreCase("tutti") || args[1].equalsIgnoreCase(n.key)) {
                        n.play(clip, 1.0);
                        count++;
                    }
                }
                sender.sendMessage(count == 0 ? "§cnessun NPC con quel nome (denny, andrew o all)" : "§aanimazione " + NpcRig.italian(clip));
            }
            case "clips", "animazioni" -> sender.sendMessage("§7animazioni: §f" + String.join(", ", rig.clipNames()));
            default -> sender.sendMessage("§7scrivi cosa vuoi fare: §f/tutorial §8(parte la scena)§7, §f/tutorial skip§7, §f/tutorial setpoint <denny|andrew|giocatore>§7, §f/tutorial resetpoint <...>§7, §f/tutorial skin <denny|andrew> <slim|large>§7, §f/tutorial npc§7, §f/tutorial npcremove§7, §f/tutorial anim <npc> <animazione>§7, §f/tutorial animazioni");
        }
    }

    /** Mette un NPC dove sta il giocatore, rivolto dove guarda il giocatore. */
    private void placeNpc(Player p, String key) {
        Location at = p.getLocation();
        float yaw = at.getYaw();
        data.set("npc." + key + ".world", at.getWorld().getName());
        data.set("npc." + key + ".x", Math.round(at.getX() * 100) / 100.0);
        data.set("npc." + key + ".y", Math.round(at.getY() * 100) / 100.0);
        data.set("npc." + key + ".z", Math.round(at.getZ() * 100) / 100.0);
        data.set("npc." + key + ".yaw", Math.round(yaw * 10) / 10.0);
        put(key, at, yaw);
        saveData();
        if (task == null) {
            lastNanos = System.nanoTime();
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
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


    // ------------------------------------------------------------------
    //  La scena
    // ------------------------------------------------------------------

    private Location spotLocation() {
        ConfigurationSection c = data.getConfigurationSection("spot");
        if (c == null || !c.contains("x")) {
            return null;
        }
        World w = Bukkit.getWorld(c.getString("world", ""));
        if (w == null) {
            return null;
        }
        return new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"),
                (float) c.getDouble("yaw"), (float) c.getDouble("pitch"));
    }

    public boolean inScene(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public void startScene(Player p) {
        if (sessions.containsKey(p.getUniqueId())) {
            return;
        }
        if (steps.isEmpty() || live.size() < 2) {
            p.sendMessage("§7il tutorial non e' ancora pronto (mancano Denny e Andrew: /tutorial npc)");
            return;
        }
        ModelNpc d = live.get("denny");
        if (d == null || d.feet.getWorld() == null) {
            return;
        }
        Location spot = spotLocation();
        if (spot == null || spot.getWorld() != d.feet.getWorld()) {
            spot = p.getLocation().clone();
            if (spot.getWorld() != d.feet.getWorld()) {
                p.sendMessage("§7vai dove ci sono Denny e Andrew e riscrivi /tutorial");
                return;
            }
        }
        p.getPersistentDataContainer().set(seenKey, PersistentDataType.BYTE, (byte) 1);
        Session s = new Session(p, p.getLocation().clone(), spot);
        sessions.put(p.getUniqueId(), s);
        p.teleport(spot);
        s.invulnerable = p.isInvulnerable();
        p.setInvulnerable(true);
        s.walkSpeed = p.getWalkSpeed();
        p.setWalkSpeed(0f);
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, (int) (sceneLength * 20) + 100, 200, false, false, false));
        p.playSound(p.getLocation(), plugin.getConfig().getString("tutorial.sound", "tutorial1"), SoundCategory.MASTER,
                (float) plugin.getConfig().getDouble("tutorial.volume", 1.0), 1f);
        brains.clear();
        duo = null;
        for (ModelNpc n : live.values()) {
            Location eye = spot.clone().add(0, 1.62, 0);
            n.stopWalking();
            n.stopFacing();
            n.talking(false);
            n.walkTo(n.home.getX(), n.home.getZ(), 2.0);
            n.lookAt(eye.getX(), eye.getY(), eye.getZ());
        }
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> sceneTick(s), 1L, 1L);
    }

    private double[] angles(Location from, double tx, double ty, double tz) {
        double dx = tx - from.getX();
        double dy = ty - from.getY();
        double dz = tz - from.getZ();
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return new double[]{yaw, pitch};
    }

    private static float wrapDeg(float a) {
        while (a > 180) {
            a -= 360;
        }
        while (a < -180) {
            a += 360;
        }
        return a;
    }

    private void setLook(Session s, String look, double turn) {
        ModelNpc d = live.get("denny");
        ModelNpc a = live.get("andrew");
        if (d == null || a == null || look == null || look.isBlank()) {
            return;
        }
        Location eye = s.spot.clone().add(0, 1.62, 0);
        double[] yd = angles(eye, d.feet.getX(), d.feet.getY() + 1.62, d.feet.getZ());
        double[] ya = angles(eye, a.feet.getX(), a.feet.getY() + 1.62, a.feet.getZ());
        double[] center = angles(eye, (d.feet.getX() + a.feet.getX()) / 2, d.feet.getY() + 1.4, (d.feet.getZ() + a.feet.getZ()) / 2);
        double yaw;
        double pitch;
        if (look.equals("denny")) {
            yaw = yd[0];
            pitch = yd[1];
        } else if (look.equals("andrew")) {
            yaw = ya[0];
            pitch = ya[1];
        } else if (look.startsWith("away:")) {
            String[] parts = look.substring(5).split(",");
            double deg = Double.parseDouble(parts[0].trim());
            pitch = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : 0;
            // i gradi + sono dal lato di Denny, - dal lato di Andrew
            double side = wrapDeg((float) (yd[0] - center[0])) >= 0 ? 1 : -1;
            yaw = center[0] + side * deg;
        } else {
            yaw = center[0];
            pitch = center[1];
        }
        s.targetYaw = (float) yaw;
        s.targetPitch = (float) pitch;
        s.turn = Math.max(0.05, turn);
    }

    private void runStep(Session s, Step st) {
        if (st.look() != null) {
            setLook(s, st.look(), st.turn());
        }
        if (st.talk() != null) {
            ModelNpc d = live.get("denny");
            ModelNpc a = live.get("andrew");
            if (d != null && a != null) {
                Location eye = s.spot.clone().add(0, 1.62, 0);
                String who = st.talk();
                d.talking(who.equals("denny"));
                a.talking(who.equals("andrew"));
                ModelNpc speaker = who.equals("denny") ? d : who.equals("andrew") ? a : null;
                ModelNpc listener = speaker == d ? a : speaker == a ? d : null;
                d.lookAt(eye.getX(), eye.getY(), eye.getZ());
                a.lookAt(eye.getX(), eye.getY(), eye.getZ());
                if (listener != null && speaker != null) {
                    listener.lookAt(speaker.feet.getX(), speaker.feet.getY() + 1.62, speaker.feet.getZ());
                }
            }
        }
        if (st.hasText()) {
            Player p = s.player;
            if (st.text().isEmpty()) {
                s.sub = null;
                p.sendActionBar(Component.empty());
            } else {
                s.sub = plugin.withBackground(st.text());
                p.sendActionBar(s.sub);
            }
        }
        for (String act : st.actions()) {
            String[] parts = act.split(":");
            if (parts.length >= 2) {
                ModelNpc n = live.get(parts[0]);
                if (n != null && parts[1].startsWith("@")) {
                    moveNpc(n, parts[1].substring(1), s);
                } else if (n != null) {
                    double speed = 1.0;
                    if (parts.length > 2) {
                        try {
                            speed = Double.parseDouble(parts[2]);
                        } catch (NumberFormatException ignored) {
                            // velocita' non valida
                        }
                    }
                    n.play(parts[1], speed);
                }
            }
        }
    }

    /** Azioni di scena "npc:@dove": @andrew / @denny (si affianca all'altro), @player (va verso il giocatore), @home (torna al suo posto). */
    private void moveNpc(ModelNpc n, String where, Session s) {
        Location eye = s.spot.clone().add(0, 1.62, 0);
        switch (where) {
            case "home" -> {
                n.walkTo(n.home.getX(), n.home.getZ(), 1.5);
                n.stopFacing();
            }
            case "player" -> {
                double dx = n.feet.getX() - s.spot.getX();
                double dz = n.feet.getZ() - s.spot.getZ();
                double d = Math.max(0.1, Math.hypot(dx, dz));
                double stop = Math.min(d, 2.6);
                n.walkTo(s.spot.getX() + dx / d * stop, s.spot.getZ() + dz / d * stop, 1.6);
                n.faceTo(s.spot.getX(), s.spot.getZ());
            }
            default -> {
                ModelNpc other = live.get(where);
                if (other == null || other == n) {
                    return;
                }
                double dx = n.feet.getX() - other.feet.getX();
                double dz = n.feet.getZ() - other.feet.getZ();
                double d = Math.max(0.1, Math.hypot(dx, dz));
                n.walkTo(other.feet.getX() + dx / d * 1.1, other.feet.getZ() + dz / d * 1.1, 1.6);
                n.faceTo(other.feet.getX(), other.feet.getZ());
            }
        }
    }

    private void sceneTick(Session s) {
        Player p = s.player;
        if (!p.isOnline()) {
            endScene(s, false);
            return;
        }
        long now = System.nanoTime();
        double t = (now - s.startNanos) / 1.0e9;
        double dt = Math.min(0.2, (now - s.lastNanos) / 1.0e9);
        s.lastNanos = now;
        while (s.next < steps.size() && steps.get(s.next).at() <= t) {
            runStep(s, steps.get(s.next++));
        }
        if (t >= sceneLength) {
            endScene(s, true);
            return;
        }
        // visuale: si gira dolcemente verso il punto indicato
        double k = 1.0 - Math.exp(-dt * 3.0 / s.turn);
        float dy = wrapDeg(s.targetYaw - s.yaw);
        s.yaw += (float) (dy * k);
        s.pitch += (float) ((s.targetPitch - s.pitch) * k);
        double sway = plugin.getConfig().getDouble("tutorial.sway", 0.8);
        float yaw = s.yaw + (float) (Math.sin(t * 0.9) * sway);
        float pitch = s.pitch + (float) (Math.sin(t * 0.6 + 1.0) * sway * 0.5);
        p.setRotation(yaw, Math.max(-89f, Math.min(89f, pitch)));
        s.ticks++;
        if (s.sub != null && s.ticks % 30 == 0) {
            p.sendActionBar(s.sub);
        }
    }

    private void endScene(Session s, boolean teleportBack) {
        if (s == null || sessions.remove(s.player.getUniqueId()) == null) {
            return;
        }
        if (s.task != null) {
            s.task.cancel();
        }
        Player p = s.player;
        if (p.isOnline()) {
            p.stopSound(plugin.getConfig().getString("tutorial.sound", "tutorial1"));
            p.sendActionBar(Component.empty());
            p.setInvulnerable(s.invulnerable);
            p.setWalkSpeed(s.walkSpeed);
            p.removePotionEffect(PotionEffectType.JUMP_BOOST);
            if (teleportBack && s.back.getWorld() != null) {
                p.teleport(s.back);
            }
        }
        brains.clear();
        duo = null;
        for (ModelNpc n : live.values()) {
            n.talking(false);
            n.stopLooking();
            n.stopFacing();
            n.walkTo(n.home.getX(), n.home.getZ(), 1.5);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (sessions.isEmpty() || !sessions.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location l = from.clone();
            l.setYaw(to.getYaw());
            l.setPitch(to.getPitch());
            event.setTo(l);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && sessions.containsKey(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!sessions.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        String m = event.getMessage().toLowerCase(Locale.ROOT).trim();
        if (!m.startsWith("/tutorial") && !m.startsWith("/bm tutorial") && !m.startsWith("/blockmania tutorial")) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null) {
            endScene(s, true);
        }
    }

    /** Chi entra per la prima volta vede la scena da solo (se gli NPC ci sono). */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getConfig().getBoolean("tutorial.auto-start", true)
                || p.getPersistentDataContainer().has(seenKey, PersistentDataType.BYTE)) {
            return;
        }
        long delay = plugin.getConfig().getLong("tutorial.auto-start-delay", 80L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !p.getPersistentDataContainer().has(seenKey, PersistentDataType.BYTE)
                    && live.size() >= 2) {
                startScene(p);
            }
        }, delay);
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
        String c0 = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (args.length == 1) {
            out.addAll(List.of("skip", "setpoint", "resetpoint", "skin", "npc", "npcremove", "anim", "animazioni"));
        } else if (args.length == 2 && (c0.equals("npc") || c0.equals("npcremove") || c0.equals("skin"))) {
            out.addAll(KEYS);
        } else if (args.length == 2 && (c0.equals("setpoint") || c0.equals("resetpoint"))) {
            out.addAll(KEYS);
            out.add("giocatore");
        } else if (args.length == 3 && c0.equals("skin")) {
            out.addAll(List.of("slim", "large"));
        } else if (args.length == 2 && (c0.equals("anim") || c0.equals("animazione"))) {
            out.addAll(KEYS);
            out.add("all");
        } else if (args.length == 3 && (c0.equals("anim") || c0.equals("animazione"))) {
            out.addAll(rig.clipNames());
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(s -> !s.startsWith(last));
        return out;
    }
}
