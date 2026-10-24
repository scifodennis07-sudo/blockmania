package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/**
 * Le miniere "ufficiali" (di riferimento): la costruzione di ogni miniera numero N, salvata su file.
 * Lo staff le crea con la bacchetta (/bm mines setmines N), le cambia (changemines N) oppure le
 * modifica sul posto (editmines N, poi editmines save). Le miniere personali dei giocatori
 * (fase successiva) vengono copiate da qui.
 */
public final class MineTemplates {

    /** Info di una miniera salvata: dov'e' la costruzione di riferimento e quanto e' grande. */
    public record Info(String world, int ox, int oy, int oz, int dx, int dy, int dz, int count) {
    }

    private final BlockMania plugin;
    private final File dir;
    private final Set<UUID> busy = new HashSet<>();
    private final Map<UUID, Integer> editing = new HashMap<>();

    public MineTemplates(BlockMania plugin) {
        this.plugin = plugin;
        this.dir = new File(plugin.getDataFolder(), "mines");
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

    public Integer editingMine(Player player) {
        return editing.get(player.getUniqueId());
    }

    public void forget(UUID id) {
        editing.remove(id);
        busy.remove(id);
    }

    // ---------- lettura info ----------

    public Info info(int mine) {
        File f = file(mine);
        if (!f.isFile()) {
            return null;
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new BufferedInputStream(new FileInputStream(f))))) {
            in.readInt(); // versione
            String world = in.readUTF();
            int ox = in.readInt(), oy = in.readInt(), oz = in.readInt();
            int dx = in.readInt(), dy = in.readInt(), dz = in.readInt();
            int palette = in.readInt();
            for (int i = 0; i < palette; i++) {
                in.readUTF();
            }
            int count = in.readInt();
            return new Info(world, ox, oy, oz, dx, dy, dz, count);
        } catch (IOException ex) {
            plugin.getLogger().warning("Impossibile leggere la miniera " + mine + ": " + ex.getMessage());
            return null;
        }
    }

    // ---------- salvataggio ----------

    /**
     * Legge la zona e la salva come miniera N. Lo fa a pezzi (perTick blocchi per tick) per non pesare sul server.
     * Dove c'e' un blocco custom salva il suo numero, dove c'e' un blocco normale salva il suo stato; l'aria non si salva.
     */
    public void capture(Player player, BlockEdit.Selection sel, int mine, Runnable done) {
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
        // ogni voce: indice lineare, indice palette (-1 = blocco custom), numero
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
                if (block.getType() == Material.AIR || block.getType() == Material.CAVE_AIR
                        || block.getType() == Material.VOID_AIR) {
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
            boolean ok = write(mine, sel, palette, entries);
            Player online = Bukkit.getPlayer(id);
            if (online != null) {
                if (!ok) {
                    plugin.send(online, "mine-tpl-error");
                } else if (done != null) {
                    done.run();
                }
            }
        }, 1L, 1L);
        if (total > perTick * 5L) {
            plugin.send(player, "edit-start", "%count%", String.valueOf(total));
        }
    }

    private boolean write(int mine, BlockEdit.Selection sel, List<String> palette, List<long[]> entries) {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return false;
        }
        File tmp = new File(dir, "mine_" + mine + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tmp))))) {
            out.writeInt(1);
            out.writeUTF(sel.world().getName());
            out.writeInt(sel.minX());
            out.writeInt(sel.minY());
            out.writeInt(sel.minZ());
            out.writeInt(sel.dx());
            out.writeInt(sel.dy());
            out.writeInt(sel.dz());
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

    // ---------- comandi ----------

    /** /bm mines setmines N: la zona scelta diventa la miniera N (non deve esistere gia). */
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
        capture(player, sel, mine, () -> {
            Info info = info(mine);
            plugin.send(player, "mine-tpl-saved", "%mine%", String.valueOf(mine),
                    "%count%", String.valueOf(info == null ? 0 : info.count()));
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
        capture(player, sel, mine, () -> {
            Info info = info(mine);
            plugin.send(player, "mine-tpl-changed", "%mine%", String.valueOf(mine),
                    "%count%", String.valueOf(info == null ? 0 : info.count()));
        });
    }

    /** /bm mines editmines N: ti porta alla costruzione di riferimento; poi "editmines save" salva le modifiche. */
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
        double cx = info.ox() + info.dx() / 2.0;
        double cz = info.oz() + info.dz() / 2.0;
        double y = info.oy() + info.dy() + 1;
        Location to = new Location(world, cx, y, cz, player.getLocation().getYaw(), 30f);
        player.teleport(to);
        editing.put(player.getUniqueId(), mine);
        plugin.send(player, "mine-edit-start", "%mine%", String.valueOf(mine));
    }

    /** /bm mines editmines save: rilegge la zona della miniera che stai modificando e la salva. */
    public void saveEdit(Player player) {
        Integer mine = editing.get(player.getUniqueId());
        if (mine == null) {
            plugin.sendCommand(player, "mine-edit-none");
            return;
        }
        Info info = info(mine);
        World world = info == null ? null : Bukkit.getWorld(info.world());
        if (world == null) {
            plugin.sendCommand(player, "mine-tpl-missing", "%mine%", String.valueOf(mine));
            return;
        }
        BlockEdit.Selection sel = new BlockEdit.Selection(world, info.ox(), info.oy(), info.oz(), info.dx(), info.dy(), info.dz());
        capture(player, sel, mine, () -> {
            Info now = info(mine);
            plugin.send(player, "mine-edit-saved", "%mine%", String.valueOf(mine),
                    "%count%", String.valueOf(now == null ? 0 : now.count()));
        });
    }

    private boolean validNumber(Player player, int mine) {
        if (mine < 1 || mine > mineCount()) {
            plugin.sendCommand(player, "mine-tpl-number", "%max%", String.valueOf(mineCount()));
            return false;
        }
        return true;
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
