package it.blockmania;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.entity.Interaction;
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
import com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Il tutorial con Denny e Andrew. Gli NPC non esistono finche' qualcuno non fa partire la scena: compaiono solo
 * per quel giocatore (nessun altro li vede, quindi piu' persone possono fare il tutorial nello stesso posto).
 * La scena: schermo nero in dissolvenza, il giocatore va nel posto della scena, gli NPC compaiono, la dissolvenza
 * si toglie e parte la scena (tutorial-scene.yml: visuale, sottotitoli, animazioni e camminate a tempo con l'audio);
 * a fine scena di nuovo dissolvenza al nero e il giocatore torna dov'era. Bande nere da film su e giu',
 * niente mirino, niente bossbar.
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
        final long createdNanos = System.nanoTime();
        long startNanos;
        /** 0 = dissolvenza al nero, 1 = scena, 2 = dissolvenza finale. */
        int stage;
        long endNanos;
        int next;
        float yaw;
        float pitch;
        float targetYaw;
        float targetPitch;
        double turn = 0.4;
        long lastNanos = System.nanoTime();
        double acc;
        int ticks;
        int lastLevel = -1;
        Component sub;
        BukkitTask task;
        boolean invulnerable;
        float walkSpeed;
        GameMode mode;
        Interaction cam;
        final Map<String, ModelNpc> npcs = new LinkedHashMap<>();
        final List<int[]> tickets = new ArrayList<>();

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
    private NpcRig rig;
    private File dataFile;
    private YamlConfiguration data;

    public Tutorial(BlockMania plugin) {
        this.plugin = plugin;
        this.seenKey = new NamespacedKey(plugin, "tutorial_seen");
    }

    // ------------------------------------------------------------------
    //  File: tutorial-data.yml tiene i punti (npc.<nome>.world/x/y/z/yaw/slim, spot.*)
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

    /** Chiamato all'avvio: gli NPC non si mettono in piedi, compaiono solo quando parte una scena. */
    public void spawnSaved() {
        ModelNpc.flip = plugin.getConfig().getBoolean("npc.flip", true);
    }

    public void stop() {
        for (Session sess : new ArrayList<>(sessions.values())) {
            endScene(sess, true);
        }
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

    private boolean ready() {
        Location a = savedFeet("denny");
        Location b = savedFeet("andrew");
        return a != null && b != null && a.getWorld() == b.getWorld();
    }

    // ------------------------------------------------------------------
    //  Comandi
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        handle(sender, args);
        return true;
    }

    private void savePoint(Player p, String key) {
        Location at = p.getLocation();
        data.set("npc." + key + ".world", at.getWorld().getName());
        data.set("npc." + key + ".x", Math.round(at.getX() * 100) / 100.0);
        data.set("npc." + key + ".y", Math.round(at.getY() * 100) / 100.0);
        data.set("npc." + key + ".z", Math.round(at.getZ() * 100) / 100.0);
        data.set("npc." + key + ".yaw", Math.round(at.getYaw() * 10) / 10.0);
        saveData();
    }

    public void handle(CommandSender sender, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        boolean admin = sender.hasPermission("blockmania.tutorial.admin");
        if (!sub.isEmpty() && !sub.equals("skip") && !admin) {
            sender.sendMessage("§cnon hai il permesso");
            return;
        }
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
                    sender.sendMessage("§aposto del giocatore salvato (nella scena starà qui e guarderà dove guardi tu adesso)");
                } else if (KEYS.contains(w)) {
                    savePoint(p, w);
                    sender.sendMessage("§apunto di " + w + " salvato, guarderà dove guardi tu adesso. Non compare ora: compare solo quando parte il tutorial (/tutorial per provarlo)");
                } else {
                    sender.sendMessage("§cpunto sconosciuto: scrivi denny, andrew oppure giocatore");
                }
            }
            case "resetpoint", "resetspot", "npcremove", "remove" -> {
                String w = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : (sub.equals("resetspot") ? "giocatore" : "");
                if (w.equals("giocatore") || w.equals("player")) {
                    data.set("spot", null);
                    saveData();
                    sender.sendMessage("§aposto del giocatore tolto");
                } else if (KEYS.contains(w) || w.equals("all")) {
                    for (String k : KEYS) {
                        if (w.equals("all") || w.equals(k)) {
                            data.set("npc." + k + ".world", null);
                            data.set("npc." + k + ".x", null);
                            data.set("npc." + k + ".y", null);
                            data.set("npc." + k + ".z", null);
                            data.set("npc." + k + ".yaw", null);
                        }
                    }
                    saveData();
                    sender.sendMessage("§apunto tolto");
                } else {
                    sender.sendMessage("§cscrivi quale punto togliere: denny, andrew, giocatore oppure all");
                }
            }
            case "skin" -> {
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
                sender.sendMessage("§a" + key + " avrà la skin " + (slim ? "slim" : "large") + " dal prossimo tutorial");
            }
            case "anim", "animazione" -> {
                if (!(sender instanceof Player p) || !sessions.containsKey(p.getUniqueId())) {
                    sender.sendMessage("§cgli NPC esistono solo durante il tutorial: avvialo con /tutorial e poi scrivi il comando");
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
                for (ModelNpc n : sessions.get(p.getUniqueId()).npcs.values()) {
                    if (args[1].equalsIgnoreCase("all") || args[1].equalsIgnoreCase("tutti") || args[1].equalsIgnoreCase(n.key)) {
                        n.play(clip, 1.0);
                        count++;
                    }
                }
                sender.sendMessage(count == 0 ? "§cnessun NPC con quel nome (denny, andrew o all)" : "§aanimazione " + NpcRig.italian(clip));
            }
            case "clips", "animazioni" -> sender.sendMessage("§7animazioni: §f" + String.join(", ", rig.clipNames()));
            default -> sender.sendMessage("§7scrivi cosa vuoi fare: §f/tutorial §8(parte la scena)§7, §f/tutorial skip§7, §f/tutorial setpoint <denny|andrew|giocatore>§7, §f/tutorial resetpoint <...>§7, §f/tutorial skin <denny|andrew> <slim|large>§7, §f/tutorial animazioni");
        }
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

    private boolean cinematic() {
        return plugin.getConfig().getBoolean("tutorial.cinematic", true);
    }

    public void startScene(Player p) {
        if (sessions.containsKey(p.getUniqueId())) {
            return;
        }
        if (steps.isEmpty() || !ready()) {
            p.sendMessage("§7il tutorial non è ancora pronto (mancano i punti di Denny e Andrew: /tutorial setpoint denny e andrew)");
            return;
        }
        World world = savedFeet("denny").getWorld();
        Location spot = spotLocation();
        if (spot == null || spot.getWorld() != world) {
            spot = p.getLocation().clone();
            if (spot.getWorld() != world) {
                p.sendMessage("§7vai dove ci sono i punti di Denny e Andrew e riscrivi /tutorial (oppure salva il posto del giocatore con /tutorial setpoint giocatore)");
                return;
            }
        }
        p.getPersistentDataContainer().set(seenKey, PersistentDataType.BYTE, (byte) 1);
        Session s = new Session(p, p.getLocation().clone(), spot);
        sessions.put(p.getUniqueId(), s);
        s.invulnerable = p.isInvulnerable();
        p.setInvulnerable(true);
        s.walkSpeed = p.getWalkSpeed();
        p.setWalkSpeed(0f);
        plugin.getBars().hideAll(p, true);
        s.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> sceneTick(s), 1L, 1L);
    }

    /** Sotto lo schermo nero: il giocatore va al posto della scena, compaiono gli NPC, parte l'audio. */
    private void beginRun(Session s) {
        Player p = s.player;
        Location spot = s.spot;
        s.stage = 1;
        s.startNanos = System.nanoTime();
        s.lastNanos = s.startNanos;
        p.teleport(spot);
        p.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, (int) (sceneLength * 20) + 200, 200, false, false, false));
        // gli NPC: li vede solo questo giocatore
        for (String key : KEYS) {
            Location feet = savedFeet(key);
            if (feet == null) {
                continue;
            }
            int cx = feet.getBlockX() >> 4;
            int cz = feet.getBlockZ() >> 4;
            feet.getWorld().addPluginChunkTicket(cx, cz, plugin);
            s.tickets.add(new int[]{cx, cz});
            ModelNpc n = new ModelNpc(key, key + (isSlim(key) ? "_slim" : "_large"), feet,
                    data.getDouble("npc." + key + ".yaw") - 180.0, rig);
            n.viewer(plugin, p);
            n.spawn();
            Location eye = spot.clone().add(0, 1.62, 0);
            n.lookAt(eye.getX(), eye.getY(), eye.getZ());
            s.npcs.put(key, n);
        }
        if (cinematic()) {
            s.mode = p.getGameMode();
            s.cam = spot.getWorld().spawn(spot, Interaction.class, e -> {
                e.setInteractionHeight(1.906f);
                e.setInteractionWidth(0.1f);
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setRotation(spot.getYaw(), spot.getPitch());
            });
            p.showEntity(plugin, s.cam);
            p.setGameMode(GameMode.SPECTATOR);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (s.cam != null && s.cam.isValid() && p.isOnline() && sessions.get(p.getUniqueId()) == s) {
                    p.setSpectatorTarget(s.cam);
                }
            }, 3L);
        }
        p.playSound(p.getLocation(), plugin.getConfig().getString("tutorial.sound", "tutorial1"), SoundCategory.MASTER,
                (float) plugin.getConfig().getDouble("tutorial.volume", 1.0), 1f);
    }

    // ---- dissolvenza e bande da film (title) ----

    private static final String FADE_FONT = "";

    private void showFx(Player p, int level, boolean bars) {
        int style = plugin.getConfig().getInt("tutorial.bars", 2);
        Component top = bars && style >= 1 && style <= 5
                ? Component.text(String.valueOf((char) (0xF800 + style)), NamedTextColor.WHITE) : Component.empty();
        Component bottom = level > 0
                ? Component.text(String.valueOf((char) (0xF810 + Math.min(8, level) - 1)), NamedTextColor.WHITE) : Component.empty();
        if (top == Component.empty() && bottom == Component.empty()) {
            p.clearTitle();
            return;
        }
        p.showTitle(Title.title(top, bottom, Title.Times.times(Duration.ZERO, Duration.ofSeconds(4), Duration.ZERO)));
    }

    /** Alla fine: dal nero torna a vedersi il posto dove il giocatore era. */
    private void fadeBackIn(Player p) {
        showFx(p, 8, false);
        int[] k = {8};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            k[0]--;
            if (!p.isOnline() || k[0] <= 0) {
                p.clearTitle();
                task.cancel();
                return;
            }
            showFx(p, k[0], false);
        }, 3L, 3L);
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
        ModelNpc d = s.npcs.get("denny");
        ModelNpc a = s.npcs.get("andrew");
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
            ModelNpc d = s.npcs.get("denny");
            ModelNpc a = s.npcs.get("andrew");
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
                ModelNpc n = s.npcs.get(parts[0]);
                if (n != null && parts[1].startsWith("@")) {
                    moveNpc(s, n, parts[1].substring(1));
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
    private void moveNpc(Session s, ModelNpc n, String where) {
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
                ModelNpc other = s.npcs.get(where);
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
        double fadeIn = plugin.getConfig().getDouble("tutorial.fade-in", 0.8);
        double hold = plugin.getConfig().getDouble("tutorial.black-hold", 2.0);
        double fadeOut = plugin.getConfig().getDouble("tutorial.fade-out", 1.6);
        double endFade = plugin.getConfig().getDouble("tutorial.end-fade", 1.2);
        double alpha;
        if (s.stage == 0) {
            alpha = Math.min(1.0, (now - s.createdNanos) / 1.0e9 / Math.max(0.05, fadeIn));
            if (alpha >= 1.0) {
                beginRun(s);
            }
        } else if (s.stage == 1) {
            double t = (now - s.startNanos) / 1.0e9;
            alpha = t < hold ? 1.0 : t < hold + fadeOut ? 1.0 - (t - hold) / fadeOut : 0.0;
            if (t >= sceneLength) {
                s.stage = 2;
                s.endNanos = now;
            } else if (t >= sceneLength - endFade) {
                alpha = Math.max(alpha, (t - (sceneLength - endFade)) / endFade);
            }
            runScene(s, t, now);
        } else {
            alpha = 1.0;
            if ((now - s.endNanos) / 1.0e9 >= 0.3) {
                endScene(s, true);
                return;
            }
        }
        int level = (int) Math.round(Math.max(0, Math.min(1, alpha)) * 8);
        s.ticks++;
        if (level != s.lastLevel || s.ticks % 60 == 0) {
            s.lastLevel = level;
            showFx(p, level, true);
        }
    }

    private void runScene(Session s, double t, long now) {
        Player p = s.player;
        double dt = Math.min(0.2, (now - s.lastNanos) / 1.0e9);
        s.lastNanos = now;
        while (s.next < steps.size() && steps.get(s.next).at() <= t) {
            runStep(s, steps.get(s.next++));
        }
        // gli NPC si aggiornano 10 volte al secondo
        s.acc += dt;
        if (s.acc >= 0.1) {
            for (ModelNpc n : s.npcs.values()) {
                n.tick(s.acc);
            }
            s.acc = 0;
        }
        // visuale: si gira dolcemente verso il punto indicato
        double k = 1.0 - Math.exp(-dt * 3.0 / s.turn);
        float dy = wrapDeg(s.targetYaw - s.yaw);
        s.yaw += (float) (dy * k);
        s.pitch += (float) ((s.targetPitch - s.pitch) * k);
        double sway = plugin.getConfig().getDouble("tutorial.sway", 0.8);
        float yaw = s.yaw + (float) (Math.sin(t * 0.9) * sway);
        float pitch = Math.max(-89f, Math.min(89f, s.pitch + (float) (Math.sin(t * 0.6 + 1.0) * sway * 0.5)));
        if (s.cam != null && s.cam.isValid()) {
            s.cam.setRotation(yaw, pitch);
        } else {
            p.setRotation(yaw, pitch);
        }
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
        for (ModelNpc n : s.npcs.values()) {
            n.remove();
        }
        s.npcs.clear();
        for (int[] t : s.tickets) {
            Location any = savedFeet("denny");
            if (any != null && any.getWorld() != null) {
                any.getWorld().removePluginChunkTicket(t[0], t[1], plugin);
            }
        }
        s.tickets.clear();
        Player p = s.player;
        if (s.cam != null) {
            s.cam.remove();
            s.cam = null;
        }
        if (p.isOnline()) {
            if (s.mode != null) {
                p.setSpectatorTarget(null);
                p.setGameMode(s.mode);
            }
            plugin.getBars().hideAll(p, false);
            p.stopSound(plugin.getConfig().getString("tutorial.sound", "tutorial1"));
            p.sendActionBar(Component.empty());
            p.setInvulnerable(s.invulnerable);
            p.setWalkSpeed(s.walkSpeed);
            p.removePotionEffect(PotionEffectType.JUMP_BOOST);
            if (teleportBack && s.back.getWorld() != null) {
                p.teleport(s.back);
            }
            if (s.stage >= 1) {
                fadeBackIn(p);
            } else {
                p.clearTitle();
            }
        }
    }

    // ------------------------------------------------------------------
    //  Eventi
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (sessions.isEmpty()) {
            return;
        }
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s == null || (s.cam != null && s.stage >= 1)) {
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
    public void onStopSpectating(PlayerStopSpectatingEntityEvent event) {
        Session s = sessions.get(event.getPlayer().getUniqueId());
        if (s != null && s.cam != null) {
            event.setCancelled(true);
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
            endScene(s, false);
        }
    }

    /** Chi entra per la prima volta vede la scena da solo (se i punti ci sono). */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (!plugin.getConfig().getBoolean("tutorial.auto-start", true)
                || p.getPersistentDataContainer().has(seenKey, PersistentDataType.BYTE)) {
            return;
        }
        long delay = plugin.getConfig().getLong("tutorial.auto-start-delay", 80L);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && !p.getPersistentDataContainer().has(seenKey, PersistentDataType.BYTE) && ready()) {
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
            out.addAll(List.of("skip", "setpoint", "resetpoint", "skin", "anim", "animazioni"));
        } else if (args.length == 2 && c0.equals("skin")) {
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
