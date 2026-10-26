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
import org.bukkit.entity.Entity;
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
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

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
 * /tutorial: una scena con la telecamera guidata e due NPC (Denny e Andrew) fatti con pacchetti (packetevents).
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

    /** Lo stato dei gesti di un NPC durante la scena. */
    private static final class Npc {
        final PacketNpc pn;
        final Location base;
        float yaw;
        float pitch;
        double nextSwing;
        String head = "";
        double headStart;
        double hopStart = -1;
        int waveLeft;
        double nextWave;
        String hand = "";
        double handStart;
        double handDur;
        double cx;
        double cz;
        double tx;
        double tz;
        double nextIdle;
        double patStart = -1;
        String patKind = "";
        int patStep;
        double hopY;
        float flinch;
        double glanceUntil;
        float glanceTarget;
        float glanceNow;
        double speed = 0.1;

        Npc(PacketNpc pn) {
            this.pn = pn;
            this.base = pn.feet.clone();
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
    private File cfgFile;
    private File dataFile;
    private YamlConfiguration cfg;
    private YamlConfiguration data;

    public Tutorial(BlockMania plugin) {
        this.plugin = plugin;
        this.doneKey = new NamespacedKey(plugin, "tutorial_done");
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

    /** Da chiamare quando tutti i plugin sono pronti (packetevents compreso). */
    public void spawnSaved() {
        respawnAll();
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
        if (viewTask != null) {
            viewTask.cancel();
            viewTask = null;
        }
        clearLive();
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
        // offset: se l'audio parte in ritardo sul client si mette un numero positivo (tutto va piu' tardi)
        double t = (System.nanoTime() - s.startNanos) / 1_000_000_000.0
                - (cfg.getDouble("scene.offset", 0) + data.getDouble("offset", 0));
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
        } else if (!look.isEmpty()) {
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
            if (n == null) {
                continue;
            }
            switch (kv[1].trim().toLowerCase(Locale.ROOT)) {
                case "swing" -> n.pn.swing(ThreadLocalRandom.current().nextBoolean());
                case "wave" -> {
                    n.waveLeft = 4;
                    n.nextWave = now;
                }
                case "hop" -> n.hopStart = now;
                case "pat", "bump" -> {
                    n.patStart = now;
                    n.patKind = kv[1].trim().toLowerCase(Locale.ROOT);
                    n.patStep = 0;
                }
                case "step" -> {
                    n.patStart = now;
                    n.patKind = "step";
                    n.patStep = 0;
                }
                case "nod" -> {
                    n.head = "nod";
                    n.headStart = now;
                }
                case "shake" -> {
                    n.head = "shake";
                    n.headStart = now;
                }
                case "eat" -> startHand(n, "eat", now, 1.6);
                case "block" -> startHand(n, "block", now, 1.8);
                case "crouch" -> startHand(n, "crouch", now, 0.7);
                default -> {
                }
            }
        }
    }

    private void startHand(Npc n, String what, double now, double dur) {
        endHand(n);
        n.hand = what;
        n.handStart = now;
        n.handDur = dur;
        switch (what) {
            case "eat" -> n.pn.useItem(Material.APPLE, true);
            case "block" -> n.pn.useItem(Material.SHIELD, true);
            case "crouch" -> n.pn.crouch(true);
            default -> {
            }
        }
    }

    private void endHand(Npc n) {
        switch (n.hand) {
            case "eat", "block" -> n.pn.useItem(Material.AIR, false);
            case "crouch" -> n.pn.crouch(false);
            default -> {
            }
        }
        n.hand = "";
    }

    // ------------------------------------------------------------------
    //  NPC: giocatori veri fatti con i pacchetti (packetevents)
    // ------------------------------------------------------------------

    private final Map<String, PacketNpc> live = new HashMap<>();
    private BukkitTask viewTask;

    private static String plain(String s) {
        return s == null ? "" : s.replaceAll("(?i)[&\u00a7][0-9a-fk-or]", "");
    }

    private boolean hasPacketEvents() {
        return Bukkit.getPluginManager().getPlugin("packetevents") != null;
    }

    /** Rimette in piedi gli NPC salvati (all'avvio e dopo /tutorial reload). */
    private void respawnAll() {
        clearLive();
        if (viewTask != null) {
            viewTask.cancel();
            viewTask = null;
        }
        if (!hasPacketEvents()) {
            plugin.getLogger().warning("Tutorial: manca il plugin packetevents, gli NPC non si vedranno.");
            return;
        }
        for (String key : KEYS) {
            if (data.contains("npc." + key + ".x")) {
                spawnLive(key);
            }
        }
        viewTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateViewers, 20L, 20L);
    }

    private void clearLive() {
        for (PacketNpc n : live.values()) {
            n.destroy();
        }
        live.clear();
    }

    private Location npcFeet(String key) {
        ConfigurationSection c = data.getConfigurationSection("npc." + key);
        if (c == null || !c.contains("x")) {
            return null;
        }
        World w = Bukkit.getWorld(c.getString("world", ""));
        if (w == null) {
            return null;
        }
        return new Location(w, c.getDouble("x"), c.getDouble("y"), c.getDouble("z"), (float) c.getDouble("yaw"), 0f);
    }

    private void spawnLive(String key) {
        Location feet = npcFeet(key);
        if (feet == null) {
            return;
        }
        String name = plain(cfg.getString("npc." + key + ".name", key));
        PacketNpc old = live.remove(key);
        if (old != null) {
            old.destroy();
        }
        PacketNpc n = new PacketNpc(key, name, feet, data.getString("npc." + key + ".tex"),
                data.getString("npc." + key + ".sig"));
        live.put(key, n);
        updateViewers();
    }

    /** Chi e' vicino vede l'NPC, chi e' lontano o in un altro mondo no. */
    private void updateViewers() {
        double range = cfg.getDouble("npc.view-distance", 48);
        for (PacketNpc n : live.values()) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                boolean near = p.getWorld().equals(n.feet.getWorld())
                        && p.getLocation().distanceSquared(n.feet) <= range * range;
                if (near) {
                    n.show(p);
                } else {
                    n.hide(p);
                }
            }
        }
    }

    /** La skin si prende da Mojang in un thread a parte, poi si salva e l'NPC viene rimesso. */
    private void fetchSkin(String key, String skinName, CommandSender notify) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String value = null;
            String sig = null;
            // 1) il giocatore e' online: la skin si prende da lui
            Player online = Bukkit.getPlayerExact(skinName);
            if (online != null) {
                for (var prop : online.getPlayerProfile().getProperties()) {
                    if (prop.getName().equals("textures")) {
                        value = prop.getValue();
                        sig = prop.getSignature();
                    }
                }
            }
            // 2) Paper chiede a Mojang
            if (value == null) {
                try {
                    var profile = Bukkit.createProfile(skinName);
                    profile.complete(true);
                    for (var prop : profile.getProperties()) {
                        if (prop.getName().equals("textures")) {
                            value = prop.getValue();
                            sig = prop.getSignature();
                        }
                    }
                } catch (RuntimeException e) {
                    plugin.getLogger().warning("Tutorial: Paper non trova la skin " + skinName + ": " + e);
                }
            }
            // 3) richiesta diretta a Mojang
            if (value == null) {
                try {
                    String[] r = mojangSkin(skinName);
                    if (r != null) {
                        value = r[0];
                        sig = r[1];
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Tutorial: Mojang non risponde per " + skinName + ": " + e);
                }
            }
            final String v = value;
            final String sg = sig;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (v == null) {
                    if (notify != null) {
                        notify.sendMessage(msg("skin-error", "%skin%", skinName));
                    }
                    return;
                }
                data.set("npc." + key + ".skin", skinName);
                data.set("npc." + key + ".tex", v);
                data.set("npc." + key + ".sig", sg);
                saveData();
                if (data.contains("npc." + key + ".x")) {
                    spawnLive(key);
                }
                if (notify != null) {
                    notify.sendMessage(msg("skin-set", "%name%", key, "%skin%", skinName));
                }
            });
        });
    }

    private void tickNpcs(Session s, double t) {
        Location playerEye = s.lock.clone().add(0, 1.62, 0);
        if (s.ticks % 10 == 1) {
            for (String key : KEYS) {
                Npc old = s.npcs.get(key);
                PacketNpc pn = live.get(key);
                if (pn != null && (old == null || old.pn != pn)) {
                    s.npcs.put(key, new Npc(pn));
                    pn.show(s.player);
                }
            }
        }
        boolean talk = cfg.getBoolean("npc.talk-gestures", true);
        for (Map.Entry<String, Npc> en : s.npcs.entrySet()) {
            Npc n = en.getValue();
            String key = en.getKey();
            boolean speaking = key.equals(s.speaker);
            // chi parla guarda il giocatore, l'altro guarda chi parla
            Location target = playerEye;
            if (!s.speaker.isEmpty() && !speaking) {
                Npc other = s.npcs.get(s.speaker);
                if (other != null) {
                    target = other.base.clone().add(0, 1.62, 0);
                }
            }
            Location eye = n.base.clone().add(0, 1.62, 0);
            float[] a = aim(eye, target);
            n.yaw += wrap(a[0] - n.yaw) * 0.15f;
            n.pitch += (a[1] - n.pitch) * 0.15f;

            String otherKey = key.equals("denny") ? "andrew" : "denny";
            Npc otherNpc = s.npcs.get(otherKey);
            // durante pacca/pugno guarda l'altro
            if (n.patStart >= 0 && otherNpc != null) {
                float[] ao = aim(eye, otherNpc.base.clone().add(0, 1.62, 0));
                n.yaw += wrap(ao[0] - n.yaw) * 0.2f;
                n.pitch += (ao[1] - n.pitch) * 0.2f;
            }
            float headYaw = n.yaw;
            float headPitch = n.pitch + n.flinch;
            n.flinch *= 0.85f;
            if (speaking) {
                // dondola leggermente la testa mentre parla, a ritmo di sillabe
                headYaw += (float) (Math.sin(t * 1.7 + key.length()) * 4.0);
                headPitch += (float) (Math.sin(t * 8.5) * 1.6 + Math.sin(t * 5.1 + 1) * 1.2 + Math.sin(t * 2.3) * 2.0);
                if (talk && t >= n.nextSwing && n.hand.isEmpty()) {
                    n.pn.swing(ThreadLocalRandom.current().nextBoolean());
                    n.nextSwing = t + 0.9 + ThreadLocalRandom.current().nextDouble() * 1.6;
                }
            }
            if (!n.head.isEmpty()) {
                double prog = (t - n.headStart) / (n.head.equals("nod") ? 0.8 : 0.9);
                if (prog >= 1.0) {
                    n.head = "";
                } else if (n.head.equals("nod")) {
                    headPitch += (float) (Math.sin(prog * Math.PI * 4) * 18.0);
                } else {
                    headYaw += (float) (Math.sin(prog * Math.PI * 6) * 30.0);
                }
            }
            if (n.waveLeft > 0 && t >= n.nextWave) {
                n.pn.swing(false);
                n.waveLeft--;
                n.nextWave = t + 0.32;
            }
            if (!n.hand.isEmpty() && t - n.handStart >= n.handDur) {
                endHand(n);
            }
            if (s.ticks % 2 == 0) {
                n.pn.look(headYaw, headPitch);
            }
            // vita: ogni tanto cammina, si guarda intorno, annuisce, muove le braccia
            if (n.patStart < 0 && t >= n.nextIdle) {
                double r = ThreadLocalRandom.current().nextDouble();
                if (r < 0.40) {
                    double ang = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
                    double len = 0.4 + ThreadLocalRandom.current().nextDouble() * 0.7;
                    double nx = n.cx + Math.cos(ang) * len;
                    double nz = n.cz + Math.sin(ang) * len;
                    double rad = Math.sqrt(nx * nx + nz * nz);
                    if (rad > 1.1) {
                        nx = nx / rad * 1.1;
                        nz = nz / rad * 1.1;
                    }
                    boolean free = true;
                    if (otherNpc != null) {
                        double ox = n.base.getX() + nx - (otherNpc.base.getX() + otherNpc.cx);
                        double oz = n.base.getZ() + nz - (otherNpc.base.getZ() + otherNpc.cz);
                        free = ox * ox + oz * oz > 1.0;
                    }
                    if (free) {
                        n.tx = nx;
                        n.tz = nz;
                        n.speed = 0.09;
                    }
                } else if (r < 0.55) {
                    n.glanceTarget = (ThreadLocalRandom.current().nextBoolean() ? 1 : -1)
                            * (25f + ThreadLocalRandom.current().nextFloat() * 30f);
                    n.glanceUntil = t + 0.8 + ThreadLocalRandom.current().nextDouble() * 0.8;
                } else if (r < 0.70) {
                    n.pn.swing(ThreadLocalRandom.current().nextBoolean());
                } else if (r < 0.80) {
                    n.head = speaking ? "shake" : "nod";
                    n.headStart = t;
                } else if (r < 0.87) {
                    startHand(n, "crouch", t, 0.5);
                } else if (r < 0.92) {
                    n.hopStart = t;
                } else {
                    n.waveLeft = 2;
                    n.nextWave = t;
                }
                n.nextIdle = t + (speaking ? 0.7 : 1.2) + ThreadLocalRandom.current().nextDouble() * 1.8;
            }
            if (t > n.glanceUntil) {
                n.glanceTarget = 0f;
            }
            n.glanceNow += (n.glanceTarget - n.glanceNow) * 0.15f;
            headYaw += n.glanceNow;
            if (n.patStart >= 0 && otherNpc != null) {
                double e = t - n.patStart;
                double ddx = otherNpc.base.getX() - n.base.getX();
                double ddz = otherNpc.base.getZ() - n.base.getZ();
                double dist = Math.max(0.01, Math.sqrt(ddx * ddx + ddz * ddz));
                double ux = ddx / dist;
                double uz = ddz / dist;
                double reach = n.patKind.equals("bump") ? Math.max(0, (dist - 0.7) / 2) : Math.max(0, dist - 0.85);
                if (n.patKind.equals("step")) {
                    // piccolo passo verso il giocatore
                    double px = s.lock.getX() - n.base.getX();
                    double pz = s.lock.getZ() - n.base.getZ();
                    double pd = Math.max(0.01, Math.sqrt(px * px + pz * pz));
                    boolean out = e < 1.6;
                    n.tx = out ? px / pd * 0.5 : 0;
                    n.tz = out ? pz / pd * 0.5 : 0;
                    if (e > 2.4) {
                        n.patStart = -1;
                    }
                } else {
                    boolean going = e < 1.7;
                    n.tx = going ? ux * reach : 0;
                    n.tz = going ? uz * reach : 0;
                    if (n.patKind.equals("bump")) {
                        otherNpc.tx = going ? -ux * reach : 0;
                        otherNpc.tz = going ? -uz * reach : 0;
                        otherNpc.nextIdle = t + 1.0;
                    }
                    if (e >= 0.8 && n.patStep == 0) {
                        n.patStep = 1;
                        n.pn.swing(false);
                    }
                    if (e >= 1.05 && n.patStep == 1) {
                        n.patStep = 2;
                        n.pn.swing(false);
                        if (n.patKind.equals("pat")) {
                            // l'altro sente la pacca: la testa balza e fa mezzo passo
                            otherNpc.flinch = 9f;
                            if (otherNpc.patStart < 0) {
                                otherNpc.tx = ux * 0.18;
                                otherNpc.tz = uz * 0.18;
                                otherNpc.nextIdle = t + 1.0;
                            }
                        } else {
                            otherNpc.pn.swing(false);
                        }
                    }
                    if (e > 2.6) {
                        n.patStart = -1;
                    }
                }
            }
            double oldX = n.cx;
            double oldZ = n.cz;
            double mdx = n.tx - n.cx;
            double mdz = n.tz - n.cz;
            double md = Math.sqrt(mdx * mdx + mdz * mdz);
            if (md > 0.001) {
                double stp = Math.min(md, n.patStart >= 0 ? 0.14 : n.speed);
                n.cx += mdx / md * stp;
                n.cz += mdz / md * stp;
            }
            double hy = 0;
            if (n.hopStart >= 0) {
                double prog = (t - n.hopStart) / 0.6;
                if (prog >= 1.0) {
                    n.hopStart = -1;
                } else {
                    hy = 0.45 * Math.sin(prog * Math.PI);
                }
            }
            if (hy > 0 || n.hopY > 0 || Math.abs(n.cx - oldX) > 0.002 || Math.abs(n.cz - oldZ) > 0.002) {
                n.pn.place(n.cx, hy, n.cz);
            }
            n.hopY = hy;
        }
    }

    /** Chiede a Mojang l'UUID del nome e poi la skin firmata: {valore, firma}. */
    private static String[] mojangSkin(String name) throws Exception {
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.http.HttpResponse<String> r1 = http.send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("https://api.mojang.com/users/profiles/minecraft/" + name)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());
        if (r1.statusCode() != 200) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F]+)\"").matcher(r1.body());
        if (!m.find()) {
            return null;
        }
        java.net.http.HttpResponse<String> r2 = http.send(java.net.http.HttpRequest.newBuilder(
                java.net.URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + m.group(1)
                        + "?unsigned=false")).build(), java.net.http.HttpResponse.BodyHandlers.ofString());
        if (r2.statusCode() != 200) {
            return null;
        }
        java.util.regex.Matcher v = java.util.regex.Pattern.compile("\"value\"\\s*:\\s*\"([^\"]+)\"").matcher(r2.body());
        java.util.regex.Matcher g = java.util.regex.Pattern.compile("\"signature\"\\s*:\\s*\"([^\"]+)\"").matcher(r2.body());
        if (!v.find()) {
            return null;
        }
        return new String[]{v.group(1), g.find() ? g.group(1) : null};
    }

    private int removeNpcs() {
        int removed = live.size();
        clearLive();
        for (String key : KEYS) {
            data.set("npc." + key, null);
        }
        saveData();
        return removed;
    }

    private void createNpcs(CommandSender sender) {
        if (!hasPacketEvents()) {
            sender.sendMessage(msg("no-packetevents"));
            return;
        }
        removeNpcs();
        Location spot = spot();
        for (String key : KEYS) {
            Location pt = point(key, spot != null ? spot.getWorld() : null);
            if (pt == null || pt.getWorld() == null) {
                sender.sendMessage(msg("npc-nopoint", "%name%", key));
                continue;
            }
            Location feet = pt.clone().add(0, -1.62 + cfg.getDouble("npc.y-offset", 0), 0);
            if (spot != null) {
                feet.setYaw(aim(pt, spot.clone().add(0, 1.62, 0))[0]);
            }
            data.set("npc." + key + ".world", feet.getWorld().getName());
            data.set("npc." + key + ".x", Math.round(feet.getX() * 100) / 100.0);
            data.set("npc." + key + ".y", Math.round(feet.getY() * 100) / 100.0);
            data.set("npc." + key + ".z", Math.round(feet.getZ() * 100) / 100.0);
            data.set("npc." + key + ".yaw", Math.round(feet.getYaw() * 10) / 10.0);
            saveData();
            String name = plain(cfg.getString("npc." + key + ".name", key));
            sender.sendMessage(msg("npc-created", "%name%", name));
            fetchSkin(key, cfg.getString("npc." + key + ".skin", key), sender);
        }
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

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        for (PacketNpc n : live.values()) {
            n.viewers.remove(e.getPlayer().getUniqueId());
        }
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
            endHand(n);
            n.pn.place(0, 0, 0);
            n.pn.look(n.base.getYaw(), 0f);
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
                    sender.sendMessage(msg("info-line", "%name%", "npc " + key,
                            "%state%", live.containsKey(key) ? "&apresente (" + data.getString("npc." + key + ".skin", "?") + ")" : "&cassente"));
                }
                sender.sendMessage(msg("info-line", "%name%", "packetevents",
                        "%state%", hasPacketEvents() ? "&apresente" : "&cmanca"));
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
                int n = removeNpcs();
                sender.sendMessage(msg("npc-removed", "%count%", String.valueOf(n)));
            }
            case "skin" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                if (args.length < 3 || !KEYS.contains(args[1].toLowerCase(Locale.ROOT))) {
                    sender.sendMessage(msg("usage-skin"));
                    return;
                }
                String key = args[1].toLowerCase(Locale.ROOT);
                if (!data.contains("npc." + key + ".x")) {
                    sender.sendMessage(msg("npc-nopoint", "%name%", key));
                    return;
                }
                sender.sendMessage(msg("skin-loading", "%skin%", args[2]));
                fetchSkin(key, args[2], sender);
            }
            case "offset" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                if (args.length < 2) {
                    sender.sendMessage(msg("offset-now", "%value%", String.valueOf(data.getDouble("offset", 0))));
                    return;
                }
                try {
                    double v = Double.parseDouble(args[1].replace(',', '.'));
                    data.set("offset", v);
                    saveData();
                    sender.sendMessage(msg("offset-set", "%value%", String.valueOf(v)));
                } catch (NumberFormatException ex) {
                    sender.sendMessage(msg("usage-offset"));
                }
            }
            case "reload" -> {
                if (!admin) {
                    sender.sendMessage(msg("no-permission"));
                    return;
                }
                load();
                respawnAll();
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
                        "npc", "npcremove", "skin", "offset", "reload"));
            }
        } else if (args.length == 2 && admin) {
            String sub = args[0].toLowerCase(Locale.ROOT);
            if (sub.equals("skin")) {
                out.addAll(KEYS);
            } else if (sub.equals("setpoint")) {
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
        if (args.length == 3 && admin && args[0].equalsIgnoreCase("skin")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                out.add(p.getName());
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        out.removeIf(x -> !x.toLowerCase(Locale.ROOT).startsWith(prefix));
        return out;
    }
}
