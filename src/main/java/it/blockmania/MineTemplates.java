package it.blockmania;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Miniere: la costruzione ufficiale di ogni miniera N (salvata su file dallo staff) e le miniere
 * personali dei giocatori (una copia a testa, in un mondo vuoto dedicato).
 *
 * - Staff: /bm mines setmines N (la selezione diventa la miniera), changemines N (la sostituisce),
 *   editmines N (ti porta li' per modificarla: shift + F salva, shift + Q annulla).
 * - Player: cliccando una miniera in /mines entra nella sua copia personale. I blocchi che ha rotto
 *   restano rotti; le modifiche dello staff si applicano ai blocchi che non ha rotto.
 */
public final class MineTemplates implements Listener {

    /** Info di una miniera ufficiale salvata. Lo spawn e' relativo all'angolo minimo della costruzione. */
    public record Info(String world, int ox, int oy, int oz, int dx, int dy, int dz, int count, long stamp,
                       double sx, double sy, double sz, float yaw, float pitch) {
    }

    /** Miniera ufficiale caricata in memoria (per copiarla). */
    private static final class Template {
        Info info;
        List<BlockData> palette = new ArrayList<>();
        int[] pal;   // per cella: -2 aria, -1 blocco custom, >=0 indice nella palette
        int[] num;   // numero del blocco custom
    }

    /** La copia di una miniera di un giocatore. */
    private static final class Instance {
        final UUID owner;
        final int mine;
        long applied;           // stamp della miniera ufficiale applicata l'ultima volta (0 = mai creata)
        int adx, ady, adz;      // dimensioni applicate l'ultima volta
        final Set<Long> broken = new HashSet<>();
        boolean dirty;

        Instance(UUID owner, int mine) {
            this.owner = owner;
            this.mine = mine;
        }
    }

    /** Lavoro in corso: scrive una miniera in un punto del mondo, a pezzi. */
    private static final class Job {
        Template t;
        World world;
        int ox, oy, oz;
        int dx, dy, dz;
        Set<Long> broken;
        Instance instance; // se e' una miniera personale
        long total;
        long cursor;
        Runnable done;
    }

    /** Una modifica della miniera ufficiale in corso da parte di uno staffer. */
    private static final class Session {
        final int mine;
        final Location back;
        final Info info;
        boolean dirty;
        BossBar bar;

        Session(int mine, Location back, Info info) {
            this.mine = mine;
            this.back = back;
            this.info = info;
        }
    }

    private final BlockMania plugin;
    private final File dir;
    private final File instancesDir;
    private final File plotsFile;
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, Instance> instances = new HashMap<>();
    private final Deque<Job> jobs = new ArrayDeque<>();
    private final Map<UUID, Integer> plots = new HashMap<>();
    private final Map<Integer, UUID> plotOwners = new HashMap<>();
    private final NamespacedKey returnKey;
    private BukkitTask jobTask;
    private BukkitTask maintenance;
    private World minesWorld;

    public MineTemplates(BlockMania plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "mines");
        this.instancesDir = new File(dir, "instances");
        this.plotsFile = new File(dir, "plots.yml");
        this.returnKey = new NamespacedKey(plugin, "mine_return");
    }

    public void start() {
        loadPlots();
        if (plotsFile.isFile()) {
            Bukkit.getScheduler().runTask(plugin, this::world); // rimette in funzione il mondo delle miniere
        }
        maintenance = Bukkit.getScheduler().runTaskTimer(plugin, this::maintain, 100L, 100L);
    }

    public void stop() {
        if (maintenance != null) {
            maintenance.cancel();
        }
        if (jobTask != null) {
            jobTask.cancel();
            jobTask = null;
        }
        saveInstances(true);
    }

    public int mineCount() {
        return Math.max(1, plugin.getConfig().getInt("mines-gui.count", 21));
    }

    private File file(int mine) {
        return new File(dir, "mine_" + mine + ".dat");
    }

    public boolean exists(int mine) {
        return file(mine).isFile();
    }

    public void forget(UUID id) {
        Session s = sessions.remove(id);
        if (s != null && s.bar != null) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                plugin.getBars().remove(p, "edit");
            }
        }
        busy.remove(id);
    }

    // ------------------------------------------------------------------
    //  File della miniera ufficiale
    // ------------------------------------------------------------------

    private DataInputStream open(File f) throws IOException {
        return new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(f))));
    }

    private Info readInfo(DataInputStream in, File f) throws IOException {
        int version = in.readInt();
        String world = in.readUTF();
        int ox = in.readInt(), oy = in.readInt(), oz = in.readInt();
        int dx = in.readInt(), dy = in.readInt(), dz = in.readInt();
        long stamp = f.lastModified();
        double sx = dx / 2.0, sy = dy + 1, sz = dz / 2.0;
        float yaw = 0, pitch = 30;
        if (version >= 2) {
            stamp = in.readLong();
            sx = in.readDouble();
            sy = in.readDouble();
            sz = in.readDouble();
            yaw = in.readFloat();
            pitch = in.readFloat();
        }
        return new Info(world, ox, oy, oz, dx, dy, dz, 0, stamp, sx, sy, sz, yaw, pitch);
    }

    public Info info(int mine) {
        File f = file(mine);
        if (!f.isFile()) {
            return null;
        }
        try (DataInputStream in = open(f)) {
            Info base = readInfo(in, f);
            int palette = in.readInt();
            for (int i = 0; i < palette; i++) {
                in.readUTF();
            }
            int count = in.readInt();
            return new Info(base.world(), base.ox(), base.oy(), base.oz(), base.dx(), base.dy(), base.dz(), count,
                    base.stamp(), base.sx(), base.sy(), base.sz(), base.yaw(), base.pitch());
        } catch (IOException ex) {
            plugin.getLogger().warning("Impossibile leggere la miniera " + mine + ": " + ex.getMessage());
            return null;
        }
    }

    private Template load(int mine) {
        File f = file(mine);
        if (!f.isFile()) {
            return null;
        }
        try (DataInputStream in = open(f)) {
            Template t = new Template();
            t.info = readInfo(in, f);
            int palette = in.readInt();
            for (int i = 0; i < palette; i++) {
                t.palette.add(Bukkit.createBlockData(in.readUTF()));
            }
            int count = in.readInt();
            long volume = (long) t.info.dx() * t.info.dy() * t.info.dz();
            if (volume > Integer.MAX_VALUE / 2) {
                return null;
            }
            t.pal = new int[(int) volume];
            t.num = new int[(int) volume];
            java.util.Arrays.fill(t.pal, -2);
            for (int i = 0; i < count; i++) {
                long idx = in.readLong();
                int p = in.readInt();
                int n = in.readInt();
                if (idx >= 0 && idx < volume) {
                    t.pal[(int) idx] = p;
                    t.num[(int) idx] = n;
                }
            }
            return t;
        } catch (IOException | IllegalArgumentException ex) {
            plugin.getLogger().warning("Impossibile leggere la miniera " + mine + ": " + ex.getMessage());
            return null;
        }
    }

    private boolean write(int mine, BlockEdit.Selection sel, double[] spawn, List<String> palette, List<long[]> entries) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return false;
        }
        File tmp = new File(dir, "mine_" + mine + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(2);
            out.writeUTF(sel.world().getName());
            out.writeInt(sel.minX());
            out.writeInt(sel.minY());
            out.writeInt(sel.minZ());
            out.writeInt(sel.dx());
            out.writeInt(sel.dy());
            out.writeInt(sel.dz());
            out.writeLong(System.currentTimeMillis());
            out.writeDouble(spawn[0]);
            out.writeDouble(spawn[1]);
            out.writeDouble(spawn[2]);
            out.writeFloat((float) spawn[3]);
            out.writeFloat((float) spawn[4]);
            out.writeInt(palette.size());
            for (String p : palette) {
                out.writeUTF(p);
            }
            out.writeInt(entries.size());
            for (long[] e : entries) {
                out.writeLong(e[0]);
                out.writeInt((int) e[1]);
                out.writeInt((int) e[2]);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Salvataggio miniera " + mine + " fallito: " + ex.getMessage());
            return false;
        }
        File target = file(mine);
        if (target.exists() && !target.delete()) {
            return false;
        }
        return tmp.renameTo(target);
    }

    // ------------------------------------------------------------------
    //  Salvare la zona scelta come miniera ufficiale
    // ------------------------------------------------------------------

    private void capture(Player player, BlockEdit.Selection sel, int mine, double[] spawn, Runnable done) {
        UUID id = player.getUniqueId();
        long total = sel.volume();
        long limit = Math.max(1, plugin.getConfig().getLong("mines.max-blocks", 400000));
        if (total > limit) {
            plugin.sendCommand(player, "selection-too-big", "%count%", String.valueOf(total), "%max%", String.valueOf(limit));
            return;
        }
        if (!busy.add(id)) {
            plugin.sendCommand(player, "edit-busy");
            return;
        }
        World world = sel.world();
        BlockManager bm = plugin.getBlocks();
        int perTick = Math.max(500, plugin.getConfig().getInt("mines.blocks-per-tick", 20000));
        List<String> palette = new ArrayList<>();
        Map<String, Integer> paletteIndex = new HashMap<>();
        List<long[]> entries = new ArrayList<>();
        final long[] cursor = {0};
        final BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long end = Math.min(total, cursor[0] + perTick);
            for (long i = cursor[0]; i < end; i++) {
                int x = sel.minX() + (int) (i % sel.dx());
                int y = sel.minY() + (int) ((i / sel.dx()) % sel.dy());
                int z = sel.minZ() + (int) (i / ((long) sel.dx() * sel.dy()));
                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    continue;
                }
                Block block = world.getBlockAt(x, y, z);
                BlockManager.Tracked t = bm.get(block);
                if (t != null) {
                    entries.add(new long[]{i, -1, t.number()});
                    continue;
                }
                Material m = block.getType();
                if (m == Material.AIR || m == Material.CAVE_AIR || m == Material.VOID_AIR) {
                    continue;
                }
                String data = block.getBlockData().getAsString();
                Integer idx = paletteIndex.get(data);
                if (idx == null) {
                    idx = palette.size();
                    palette.add(data);
                    paletteIndex.put(data, idx);
                }
                entries.add(new long[]{i, idx, 0});
            }
            cursor[0] = end;
            if (end < total) {
                return;
            }
            task[0].cancel();
            busy.remove(id);
            boolean ok = write(mine, sel, spawn, palette, entries);
            Player online = Bukkit.getPlayer(id);
            if (!ok) {
                if (online != null) {
                    plugin.send(online, "mine-tpl-error");
                }
                return;
            }
            if (done != null) {
                done.run();
            }
            refreshOnlineCopies(mine);
        }, 1L, 1L);
        if (total > perTick * 5L) {
            plugin.send(player, "edit-start", "%count%", String.valueOf(total));
        }
    }

    private double[] spawnOf(Player player, BlockEdit.Selection sel) {
        Location l = player.getLocation();
        return new double[]{l.getX() - sel.minX(), l.getY() - sel.minY(), l.getZ() - sel.minZ(), l.getYaw(), l.getPitch()};
    }

    private boolean validNumber(Player player, int mine) {
        if (mine < 1 || mine > mineCount()) {
            plugin.sendCommand(player, "mine-tpl-number", "%max%", String.valueOf(mineCount()));
            return false;
        }
        return true;
    }

    /** /bm mines setmines N: la zona scelta diventa la miniera N (non deve esistere gia'). */
    public void setMines(Player player, int mine) {
        if (!validNumber(player, mine)) {
            return;
        }
        if (exists(mine)) {
            plugin.sendCommand(player, "mine-tpl-exists", "%mine%", String.valueOf(mine));
            return;
        }
        BlockEdit.Selection sel = plugin.getBlockEdit().selection(player);
        if (sel == null) {
            return;
        }
        capture(player, sel, mine, spawnOf(player, sel), () -> {
            Info info = info(mine);
            Player p = Bukkit.getPlayer(player.getUniqueId());
            if (p != null) {
                plugin.send(p, "mine-tpl-saved", "%mine%", String.valueOf(mine),
                        "%count%", String.valueOf(info == null ? 0 : info.count()));
            }
        });
    }

    /** /bm mines changemines N: la zona scelta sostituisce la vecchia miniera N. */
    public void changeMines(Player player, int mine) {
        if (!validNumber(player, mine)) {
            return;
        }
        if (!exists(mine)) {
            plugin.sendCommand(player, "mine-tpl-missing", "%mine%", String.valueOf(mine));
            return;
        }
        BlockEdit.Selection sel = plugin.getBlockEdit().selection(player);
        if (sel == null) {
            return;
        }
        capture(player, sel, mine, spawnOf(player, sel), () -> {
            Info info = info(mine);
            Player p = Bukkit.getPlayer(player.getUniqueId());
            if (p != null) {
                plugin.send(p, "mine-tpl-changed", "%mine%", String.valueOf(mine),
                        "%count%", String.valueOf(info == null ? 0 : info.count()));
            }
        });
    }

    // ------------------------------------------------------------------
    //  Modifica della miniera ufficiale (editmines)
    // ------------------------------------------------------------------

    private Location referenceSpawn(Info info, World world) {
        return new Location(world, info.ox() + info.sx(), info.oy() + info.sy(), info.oz() + info.sz(), info.yaw(), info.pitch());
    }

    /** /bm mines editmines N: ti porta alla costruzione ufficiale. Shift + F salva, shift + Q annulla. */
    public void editMines(Player player, int mine) {
        if (!validNumber(player, mine)) {
            return;
        }
        Info info = info(mine);
        if (info == null) {
            plugin.sendCommand(player, "mine-tpl-missing", "%mine%", String.valueOf(mine));
            return;
        }
        World world = Bukkit.getWorld(info.world());
        if (world == null) {
            plugin.sendCommand(player, "mine-tpl-noworld", "%world%", info.world());
            return;
        }
        Session old = sessions.get(player.getUniqueId());
        Location back = old != null ? old.back : player.getLocation();
        if (old != null && old.bar != null) {
            plugin.getBars().remove(player, "edit");
        }
        sessions.put(player.getUniqueId(), new Session(mine, back, info));
        player.teleport(referenceSpawn(info, world));
        plugin.send(player, "mine-edit-start", "%mine%", String.valueOf(mine));
    }

    public boolean isEditing(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    private void markDirty(Session s, Player player) {
        if (s.dirty && s.bar != null) {
            return;
        }
        s.dirty = true;
        String text = plugin.getConfig().getString("mines.edit.bossbar",
                "&epremi &fshift + f &eper salvare le modifiche della miniera &f%mine% &8| &fshift + q &eper annullare");
        Component name = plugin.bossSegments(List.of(text.replace("%mine%", String.valueOf(s.mine))), -1);
        if (s.bar == null) {
            s.bar = BossBar.bossBar(name, 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
        } else {
            s.bar.name(name);
        }
        plugin.getBars().set(player, "edit", s.bar, 90, false);
    }

    /** Un blocco e' cambiato: se cade nella zona di una miniera in modifica, lo staffer vede la bossbar. */
    public void touch(Block block) {
        if (sessions.isEmpty() || block == null) {
            return;
        }
        for (Map.Entry<UUID, Session> e : sessions.entrySet()) {
            Session s = e.getValue();
            Info i = s.info;
            if (!block.getWorld().getName().equals(i.world())) {
                continue;
            }
            int x = block.getX(), y = block.getY(), z = block.getZ();
            // un po' di margine: si puo' costruire anche appena fuori dalla zona salvata
            if (x >= i.ox() - 1 && x <= i.ox() + i.dx() && y >= i.oy() - 1 && y <= i.oy() + i.dy()
                    && z >= i.oz() - 1 && z <= i.oz() + i.dz()) {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) {
                    markDirty(s, p);
                }
            }
        }
    }

    /** Lo staffer ha fatto una modifica in blocco (set / replace / remove / undo). */
    public void touch(Player player) {
        Session s = sessions.get(player.getUniqueId());
        if (s != null) {
            markDirty(s, player);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(BlockBreakEvent event) {
        touch(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(BlockPlaceEvent event) {
        touch(event.getBlock());
    }

    /** shift + F: salva le modifiche della miniera ufficiale. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        Session s = sessions.get(player.getUniqueId());
        if (s == null || !player.isSneaking()) {
            return;
        }
        event.setCancelled(true);
        save(player, s);
    }

    /** shift + Q: annulla le modifiche, rimette la miniera com'era e ti riporta dove eri. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        Player player = event.getPlayer();
        Session s = sessions.get(player.getUniqueId());
        if (s == null || !player.isSneaking()) {
            return;
        }
        event.setCancelled(true);
        cancel(player, s);
    }

    private void endBar(Player player, Session s) {
        s.dirty = false;
        if (s.bar != null) {
            plugin.getBars().remove(player, "edit");
            s.bar = null;
        }
    }

    private void save(Player player, Session s) {
        if (!s.dirty) {
            plugin.send(player, "mine-edit-nochange");
            return;
        }
        World world = Bukkit.getWorld(s.info.world());
        if (world == null) {
            plugin.sendCommand(player, "mine-tpl-noworld", "%world%", s.info.world());
            return;
        }
        BlockEdit.Selection sel = new BlockEdit.Selection(world, s.info.ox(), s.info.oy(), s.info.oz(),
                s.info.dx(), s.info.dy(), s.info.dz());
        double[] spawn = {s.info.sx(), s.info.sy(), s.info.sz(), s.info.yaw(), s.info.pitch()};
        capture(player, sel, s.mine, spawn, () -> {
            Player p = Bukkit.getPlayer(player.getUniqueId());
            if (p == null) {
                return;
            }
            Info now = info(s.mine);
            endBar(p, s);
            plugin.send(p, "mine-edit-saved", "%mine%", String.valueOf(s.mine),
                    "%count%", String.valueOf(now == null ? 0 : now.count()));
        });
    }

    private void cancel(Player player, Session s) {
        sessions.remove(player.getUniqueId());
        boolean wasDirty = s.dirty;
        endBar(player, s);
        Template t = wasDirty ? load(s.mine) : null;
        World world = Bukkit.getWorld(s.info.world());
        if (t != null && world != null) {
            Job job = new Job();
            job.t = t;
            job.world = world;
            job.ox = t.info.ox();
            job.oy = t.info.oy();
            job.oz = t.info.oz();
            job.dx = t.info.dx();
            job.dy = t.info.dy();
            job.dz = t.info.dz();
            job.total = (long) job.dx * job.dy * job.dz;
            enqueue(job);
        }
        if (s.back != null && s.back.getWorld() != null) {
            player.teleport(s.back);
        }
        plugin.send(player, "mine-edit-cancelled", "%mine%", String.valueOf(s.mine));
    }

    // ------------------------------------------------------------------
    //  Mondo delle miniere personali
    // ------------------------------------------------------------------

    private String worldName() {
        return plugin.getConfig().getString("mines.world", "blockmania_mines");
    }

    private World world() {
        if (minesWorld != null) {
            return minesWorld;
        }
        World w = Bukkit.getWorld(worldName());
        if (w == null) {
            WorldCreator creator = new WorldCreator(worldName());
            creator.generator(new ChunkGenerator() {
                @Override
                public Location getFixedSpawnLocation(World world, Random random) {
                    return new Location(world, 0, 100, 0);
                }
            });
            creator.generateStructures(false);
            w = creator.createWorld();
        }
        if (w != null) {
            try {
                w.setSpawnFlags(false, false);
                w.setGameRuleValue("doDaylightCycle", "false");
                w.setGameRuleValue("doWeatherCycle", "false");
                w.setGameRuleValue("doMobSpawning", "false");
                w.setTime(6000);
            } catch (RuntimeException ignored) {
                // regole non disponibili: non e' grave
            }
        }
        minesWorld = w;
        return w;
    }

    private int spacingX() {
        return Math.max(64, plugin.getConfig().getInt("mines.spacing-x", 1024));
    }

    private int spacingZ() {
        return Math.max(64, plugin.getConfig().getInt("mines.spacing-z", 1024));
    }

    private int baseY() {
        return plugin.getConfig().getInt("mines.base-y", 64);
    }

    private void loadPlots() {
        plots.clear();
        plotOwners.clear();
        if (!plotsFile.isFile()) {
            return;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(plotsFile);
        for (String key : y.getKeys(false)) {
            try {
                int idx = y.getInt(key);
                UUID id = UUID.fromString(key);
                plots.put(id, idx);
                plotOwners.put(idx, id);
            } catch (IllegalArgumentException ignored) {
                // voce rovinata
            }
        }
    }

    private int plotOf(UUID id) {
        Integer idx = plots.get(id);
        if (idx != null) {
            return idx;
        }
        int next = 0;
        for (int v : plots.values()) {
            next = Math.max(next, v + 1);
        }
        plots.put(id, next);
        plotOwners.put(next, id);
        if (!dir.isDirectory()) {
            dir.mkdirs();
        }
        YamlConfiguration y = new YamlConfiguration();
        for (Map.Entry<UUID, Integer> e : plots.entrySet()) {
            y.set(e.getKey().toString(), e.getValue());
        }
        try {
            y.save(plotsFile);
        } catch (IOException ex) {
            plugin.getLogger().warning("Impossibile salvare plots.yml: " + ex.getMessage());
        }
        return next;
    }

    private int plotX(UUID id) {
        return plotOf(id) * spacingX();
    }

    private int plotZ(int mine) {
        return (mine - 1) * spacingZ();
    }

    // ---------- copie personali ----------

    private File instanceFile(UUID id, int mine) {
        return new File(instancesDir, id + "_" + mine + ".dat");
    }

    private Instance instance(UUID id, int mine) {
        String key = id + "_" + mine;
        Instance inst = instances.get(key);
        if (inst != null) {
            return inst;
        }
        inst = new Instance(id, mine);
        File f = instanceFile(id, mine);
        if (f.isFile()) {
            try (DataInputStream in = open(f)) {
                in.readInt();
                inst.applied = in.readLong();
                inst.adx = in.readInt();
                inst.ady = in.readInt();
                inst.adz = in.readInt();
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    inst.broken.add(in.readLong());
                }
            } catch (IOException ex) {
                plugin.getLogger().warning("Impossibile leggere la copia della miniera " + mine + " di " + id + ": " + ex.getMessage());
            }
        }
        instances.put(key, inst);
        return inst;
    }

    private void saveInstances(boolean all) {
        for (Instance inst : new ArrayList<>(instances.values())) {
            if (inst.dirty) {
                writeInstance(inst);
            }
        }
        if (all) {
            instances.clear();
        } else {
            instances.values().removeIf(i -> !i.dirty && Bukkit.getPlayer(i.owner) == null);
        }
    }

    private void writeInstance(Instance inst) {
        if (!instancesDir.isDirectory() && !instancesDir.mkdirs()) {
            return;
        }
        File tmp = new File(instancesDir, inst.owner + "_" + inst.mine + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(1);
            out.writeLong(inst.applied);
            out.writeInt(inst.adx);
            out.writeInt(inst.ady);
            out.writeInt(inst.adz);
            out.writeInt(inst.broken.size());
            for (long k : inst.broken) {
                out.writeLong(k);
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("Salvataggio copia miniera fallito: " + ex.getMessage());
            return;
        }
        File target = instanceFile(inst.owner, inst.mine);
        if (target.exists() && !target.delete()) {
            return;
        }
        if (tmp.renameTo(target)) {
            inst.dirty = false;
        }
    }

    private static long key(int x, int y, int z) {
        return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
    }

    /** Un blocco custom e' stato distrutto: se e' dentro una miniera personale, resta rotto per sempre. */
    public void onBroken(Block block) {
        touch(block);
        World w = minesWorld != null ? minesWorld : Bukkit.getWorld(worldName());
        if (w == null || block.getWorld() != w) {
            return;
        }
        int sx = spacingX();
        int sz = spacingZ();
        int idx = Math.floorDiv(block.getX(), sx);
        int mine = Math.floorDiv(block.getZ(), sz) + 1;
        UUID owner = plotOwners.get(idx);
        if (owner == null || mine < 1 || mine > mineCount()) {
            return;
        }
        Instance inst = instance(owner, mine);
        if (inst.applied == 0) {
            return;
        }
        int rx = block.getX() - idx * sx;
        int ry = block.getY() - baseY();
        int rz = block.getZ() - (mine - 1) * sz;
        if (inst.broken.add(key(rx, ry, rz))) {
            inst.dirty = true;
        }
    }

    // ---------- entrare ----------

    /** Il giocatore clicca una sua miniera in /mines: la prepara se serve e lo porta dentro. */
    public void enter(Player player, int mine) {
        Template t = load(mine);
        if (t == null) {
            plugin.send(player, "mine-unavailable", "%mine%", String.valueOf(mine));
            plugin.playConfiguredSound(player.getLocation(), "mines-gui.sounds.error");
            return;
        }
        World w = world();
        if (w == null) {
            plugin.send(player, "mine-unavailable", "%mine%", String.valueOf(mine));
            return;
        }
        UUID id = player.getUniqueId();
        Instance inst = instance(id, mine);
        if (w != player.getWorld()) {
            player.getPersistentDataContainer().set(returnKey, PersistentDataType.STRING, encode(player.getLocation()));
        }
        if (inst.applied >= t.info.stamp()) {
            teleportIn(player, t, mine);
            return;
        }
        plugin.send(player, "mine-loading", "%mine%", String.valueOf(mine));
        Job job = jobFor(t, inst, w);
        job.done = () -> {
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                teleportIn(p, t, mine);
            }
        };
        enqueue(job);
    }

    private Job jobFor(Template t, Instance inst, World w) {
        Job job = new Job();
        job.t = t;
        job.world = w;
        job.ox = plotX(inst.owner);
        job.oy = baseY();
        job.oz = plotZ(inst.mine);
        job.dx = Math.max(t.info.dx(), inst.adx);
        job.dy = Math.max(t.info.dy(), inst.ady);
        job.dz = Math.max(t.info.dz(), inst.adz);
        job.broken = inst.broken;
        job.instance = inst;
        job.total = (long) job.dx * job.dy * job.dz;
        return job;
    }

    private void teleportIn(Player player, Template t, int mine) {
        World w = world();
        Info i = t.info;
        Location to = new Location(w, plotX(player.getUniqueId()) + i.sx(), baseY() + i.sy(),
                plotZ(mine) + i.sz(), i.yaw(), i.pitch());
        player.teleport(to);
        plugin.send(player, "mine-enter", "%mine%", String.valueOf(mine));
    }

    /** /mines exit: ti riporta dov'eri prima di entrare in una miniera. */
    public void leave(Player player) {
        String raw = player.getPersistentDataContainer().get(returnKey, PersistentDataType.STRING);
        Location back = raw == null ? null : decode(raw);
        if (back == null) {
            plugin.send(player, "mine-not-inside");
            return;
        }
        player.teleport(back);
        plugin.send(player, "mine-left");
    }

    private String encode(Location l) {
        return l.getWorld().getName() + ";" + l.getX() + ";" + l.getY() + ";" + l.getZ() + ";" + l.getYaw() + ";" + l.getPitch();
    }

    private Location decode(String raw) {
        String[] p = raw.split(";");
        if (p.length != 6) {
            return null;
        }
        World w = Bukkit.getWorld(p[0]);
        if (w == null) {
            return null;
        }
        try {
            return new Location(w, Double.parseDouble(p[1]), Double.parseDouble(p[2]), Double.parseDouble(p[3]),
                    Float.parseFloat(p[4]), Float.parseFloat(p[5]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Miniera ufficiale cambiata: chi e' dentro la sua copia la vede aggiornarsi subito, gli altri la trovano aggiornata. */
    private void refreshOnlineCopies(int mine) {
        World w = minesWorld;
        Template t = load(mine);
        if (w == null || t == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld() != w) {
                continue;
            }
            Integer idx = plots.get(p.getUniqueId());
            if (idx == null) {
                continue;
            }
            int cx = p.getLocation().getBlockZ();
            int currentMine = Math.floorDiv(cx, spacingZ()) + 1;
            if (currentMine != mine) {
                continue;
            }
            Instance inst = instance(p.getUniqueId(), mine);
            if (inst.applied == 0) {
                continue;
            }
            enqueue(jobFor(t, inst, w));
        }
    }

    // ------------------------------------------------------------------
    //  Scrivere una miniera nel mondo, a pezzi
    // ------------------------------------------------------------------

    private void enqueue(Job job) {
        jobs.add(job);
        if (jobTask == null) {
            jobTask = Bukkit.getScheduler().runTaskTimer(plugin, this::runJobs, 1L, 1L);
        }
    }

    private void runJobs() {
        Job job = jobs.peek();
        if (job == null) {
            jobTask.cancel();
            jobTask = null;
            return;
        }
        int budget = Math.max(500, plugin.getConfig().getInt("mines.paste-per-tick", 6000));
        BlockManager bm = plugin.getBlocks();
        Template t = job.t;
        int tdx = t.info.dx();
        int tdy = t.info.dy();
        int tdz = t.info.dz();
        World world = job.world;
        long end = Math.min(job.total, job.cursor + budget);
        for (long i = job.cursor; i < end; i++) {
            int x = (int) (i % job.dx);
            int y = (int) ((i / job.dx) % job.dy);
            int z = (int) (i / ((long) job.dx * job.dy));
            int wy = job.oy + y;
            if (wy < world.getMinHeight() || wy >= world.getMaxHeight()) {
                continue;
            }
            int pal = -2;
            int number = 0;
            if (x < tdx && y < tdy && z < tdz) {
                int lin = x + tdx * (y + tdy * z);
                pal = t.pal[lin];
                number = t.num[lin];
            }
            if (job.broken != null && !job.broken.isEmpty() && job.broken.contains(key(x, y, z))) {
                pal = -2;
            }
            Block block = world.getBlockAt(job.ox + x, wy, job.oz + z);
            BlockManager.Tracked tracked = bm.get(block);
            if (pal == -1) {
                if (tracked != null && tracked.number() == number) {
                    continue;
                }
                if (tracked != null) {
                    bm.remove(block);
                }
                bm.place(block, number);
            } else if (pal >= 0) {
                BlockData data = t.palette.get(pal);
                if (tracked == null && block.getBlockData().equals(data)) {
                    continue;
                }
                if (tracked != null) {
                    bm.remove(block);
                }
                block.setBlockData(data, false);
            } else {
                if (tracked != null) {
                    bm.remove(block);
                    block.setType(Material.AIR, false);
                } else if (!block.getType().isAir()) {
                    block.setType(Material.AIR, false);
                }
            }
        }
        job.cursor = end;
        if (end >= job.total) {
            jobs.poll();
            if (job.instance != null) {
                job.instance.applied = t.info.stamp();
                job.instance.adx = tdx;
                job.instance.ady = tdy;
                job.instance.adz = tdz;
                job.instance.dirty = true;
                writeInstance(job.instance);
            }
            if (job.done != null) {
                job.done.run();
            }
        }
    }

    // ------------------------------------------------------------------
    //  Manutenzione
    // ------------------------------------------------------------------

    private void maintain() {
        saveInstances(false);
        World w = minesWorld;
        if (w == null) {
            return;
        }
        for (Player p : w.getPlayers()) {
            if (p.getLocation().getY() < w.getMinHeight() + 10) {
                Integer idx = plots.get(p.getUniqueId());
                String raw = p.getPersistentDataContainer().get(returnKey, PersistentDataType.STRING);
                Location back = raw == null ? null : decode(raw);
                if (back != null) {
                    p.teleport(back);
                } else if (idx != null) {
                    p.teleport(new Location(w, idx * spacingX(), baseY() + 5, 0));
                }
            }
        }
    }

    /** Descrizione di tutte le miniere salvate (per /bm mines list). */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= mineCount(); i++) {
            Info info = info(i);
            if (info != null) {
                lines.add("&7miniera &f" + i + "&7: &f" + info.dx() + "x" + info.dy() + "x" + info.dz() + " &7(" + info.count()
                        + " blocchi) in &f" + info.world() + " " + info.ox() + " " + info.oy() + " " + info.oz());
            }
        }
        return lines;
    }
}
