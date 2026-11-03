package it.blockmania;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Un NPC "a cubi": un ItemDisplay per ogni parte del corpo (testa, corpo, braccia, gambe), con il modello e la skin
 * del resource pack (blockmania:npc/<skin>/<osso>). Le animazioni sono le stesse create nel rig (npc_rig.json):
 * ogni clip muove le ossa e qui si calcola la posizione di ogni parte, 10 volte al secondo.
 * Il rig usa y in su e fronte verso -Z; yaw e' l'angolo di sguardo del rig (0 = verso -Z, positivo verso +X).
 */
final class ModelNpc {

    final String key;
    final String skin;
    final Location feet;
    /** Dove torna quando ha finito. */
    final Location home;
    /** Se true compensa il giro di 180 gradi che Minecraft applica agli oggetti degli ItemDisplay. */
    static boolean flip = true;
    /** Direzione del corpo (gradi, convenzione del rig). */
    double yaw;
    private double headYaw;
    private double headPitch;
    private double wmove;
    private double phase;
    private double time;
    private final double idleOffset;
    private final NpcRig rig;
    private final Map<String, ItemDisplay> parts = new HashMap<>();
    private final List<Gesture> gestures = new ArrayList<>();
    private double walkX;
    private double walkZ;
    private double walkSpeed;
    private boolean walking;
    private double lookX;
    private double lookY;
    private double lookZ;
    private boolean looking;
    private boolean talking;
    private Player viewer;
    private org.bukkit.plugin.Plugin owner;
    private boolean facing;
    private double faceX;
    private double faceZ;

    private record Gesture(String clip, double start, double speed, double until) {
    }

    ModelNpc(String key, String skin, Location feet, double yaw, NpcRig rig) {
        this.key = key;
        this.skin = skin;
        this.feet = feet.clone();
        // l'entita' non deve ruotare: la rotazione e' tutta nella trasformazione delle parti
        this.feet.setYaw(0f);
        this.feet.setPitch(0f);
        this.home = this.feet.clone();
        this.yaw = yaw;
        this.rig = rig;
        this.idleOffset = Math.abs(key.hashCode() % 100) / 100.0 * 4.0;
    }

    /** L'NPC lo vede solo questo giocatore (cosi' piu' persone possono fare il tutorial insieme senza vedersi). */
    void viewer(org.bukkit.plugin.Plugin owner, Player viewer) {
        this.owner = owner;
        this.viewer = viewer;
    }

    // ---------- entita' ----------

    boolean alive() {
        for (ItemDisplay d : parts.values()) {
            if (d.isValid()) {
                return true;
            }
        }
        return false;
    }

    boolean chunkLoaded() {
        return feet.getWorld() != null && feet.getWorld().isChunkLoaded(feet.getBlockX() >> 4, feet.getBlockZ() >> 4);
    }

    void spawn() {
        remove();
        if (feet.getWorld() == null) {
            return;
        }
        for (String bone : rig.cubeBones()) {
            ItemStack stack = new ItemStack(org.bukkit.Material.STICK);
            ItemMeta meta = stack.getItemMeta();
            meta.setItemModel(new NamespacedKey("blockmania", "npc/" + skin + "/" + bone));
            stack.setItemMeta(meta);
            ItemDisplay d = feet.getWorld().spawn(feet, ItemDisplay.class, e -> {
                e.setItemStack(stack);
                e.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                e.setPersistent(false);
                e.setInvulnerable(true);
                e.setViewRange(1.0f);
                e.setBrightness(null);
                e.setInterpolationDelay(0);
                e.setInterpolationDuration(3);
                e.setTeleportDuration(3);
                e.setShadowRadius(0f);
                if (viewer != null) {
                    e.setVisibleByDefault(false);
                }
            });
            if (viewer != null && viewer.isOnline()) {
                viewer.showEntity(owner, d);
            }
            parts.put(bone, d);
        }
        apply();
    }

    void remove() {
        for (ItemDisplay d : parts.values()) {
            if (d.isValid()) {
                d.remove();
            }
        }
        parts.clear();
    }

    // ---------- comandi di scena ----------

    void play(String clip, double speed) {
        clip = NpcRig.internal(clip);
        if (!rig.hasClip(clip)) {
            return;
        }
        double len = rig.length(clip);
        gestures.add(new Gesture(clip, time, speed, time + len / speed));
    }

    void walkTo(double x, double z, double speed) {
        walkX = x;
        walkZ = z;
        walkSpeed = speed;
        walking = true;
    }

    void lookAt(double x, double y, double z) {
        lookX = x;
        lookY = y;
        lookZ = z;
        looking = true;
    }

    void stopLooking() {
        looking = false;
    }

    /** Il corpo si gira (piano) verso un punto quando e' fermo. */
    void faceTo(double x, double z) {
        faceX = x;
        faceZ = z;
        facing = true;
    }

    void stopFacing() {
        facing = false;
    }

    boolean walkingNow() {
        return walking;
    }

    void stopWalking() {
        walking = false;
    }

    /** Gradi (convenzione del rig) per guardare un punto dalla posizione attuale. */
    double yawTowards(double x, double z) {
        return Math.toDegrees(Math.atan2(x - feet.getX(), -(z - feet.getZ())));
    }

    void talking(boolean on) {
        talking = on;
    }

    boolean busy() {
        return !gestures.isEmpty();
    }

    // ---------- avanzamento ----------

    private static double wrap(double a) {
        while (a > 180) {
            a -= 360;
        }
        while (a < -180) {
            a += 360;
        }
        return a;
    }

    /** Fa avanzare l'NPC di dt secondi e aggiorna le parti. */
    void tick(double dt) {
        time += dt;
        boolean moving = false;
        if (walking) {
            double dx = walkX - feet.getX();
            double dz = walkZ - feet.getZ();
            double d = Math.hypot(dx, dz);
            if (d < 0.05) {
                walking = false;
            } else {
                double st = Math.min(d, walkSpeed * dt);
                double nx = feet.getX() + dx / d * st;
                double nz = feet.getZ() + dz / d * st;
                double ny = groundY(nx, nz);
                if (Double.isNaN(ny)) {
                    walking = false; // c'e' un ostacolo o un vuoto: si ferma
                    st = 0;
                } else {
                    feet.setX(nx);
                    feet.setY(ny);
                    feet.setZ(nz);
                    phase += st / (36.0 / 16.0);
                    moving = true;
                }
                double want = Math.toDegrees(Math.atan2(dx, -dz));
                yaw += wrap(want - yaw) * (1 - Math.exp(-dt * 9));
                for (ItemDisplay part : parts.values()) {
                    if (part.isValid()) {
                        part.teleport(feet);
                    }
                }
            }
        }
        if (facing && !moving) {
            double want = yawTowards(faceX, faceZ);
            yaw += wrap(want - yaw) * (1 - Math.exp(-dt * 5));
        }
        wmove += ((moving ? 1.0 : 0.0) - wmove) * (1 - Math.exp(-dt * 12));
        // testa verso chi guardiamo (il corpo la segue piano se si gira troppo)
        double wantHead = 0;
        double wantPitch = 0;
        if (looking) {
            double dx = lookX - feet.getX();
            double dz = lookZ - feet.getZ();
            double rel = wrap(Math.toDegrees(Math.atan2(dx, -dz)) - yaw);
            if (Math.abs(rel) > 70 && !moving) {
                yaw += wrap(rel - Math.signum(rel) * 70) * (1 - Math.exp(-dt * 3));
                rel = wrap(Math.toDegrees(Math.atan2(dx, -dz)) - yaw);
            }
            wantHead = Math.max(-65, Math.min(65, rel));
            double dist = Math.max(0.1, Math.hypot(dx, dz));
            wantPitch = Math.max(-35, Math.min(35, Math.toDegrees(Math.atan2(lookY - (feet.getY() + 1.62), dist))));
        }
        headYaw += (wantHead - headYaw) * (1 - Math.exp(-dt * 7));
        headPitch += (wantPitch - headPitch) * (1 - Math.exp(-dt * 7));
        gestures.removeIf(g -> g.until() <= time);
        apply();
    }

    /** Altezza dei piedi in (x, z): sale/scende di un blocco, ritorna NaN se c'e' un muro o un vuoto. */
    private double groundY(double x, double z) {
        var w = feet.getWorld();
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        if (w == null || !w.isChunkLoaded(bx >> 4, bz >> 4)) {
            return Double.NaN;
        }
        int by = (int) Math.floor(feet.getY() + 0.01);
        int[] tries = {0, 1, -1, -2};
        for (int dy : tries) {
            int y = by + dy;
            if (w.getBlockAt(bx, y, bz).isPassable() && w.getBlockAt(bx, y + 1, bz).isPassable()
                    && !w.getBlockAt(bx, y - 1, bz).isPassable()) {
                return dy == 0 ? feet.getY() : y;
            }
        }
        return Double.NaN;
    }

    // ---------- posa ----------

    private static double[] lerp3(double[] a, double[] b, double w) {
        return new double[]{a[0] + (b[0] - a[0]) * w, a[1] + (b[1] - a[1]) * w, a[2] + (b[2] - a[2]) * w};
    }

    private void blend(Map<String, Map<String, double[]>> pose, Map<String, Map<String, double[]>> clip, double w) {
        for (Map.Entry<String, Map<String, double[]>> e : clip.entrySet()) {
            Map<String, double[]> ch = pose.computeIfAbsent(e.getKey(), k -> new HashMap<>());
            for (Map.Entry<String, double[]> c : e.getValue().entrySet()) {
                double[] base = ch.getOrDefault(c.getKey(), new double[]{0, 0, 0});
                ch.put(c.getKey(), lerp3(base, c.getValue(), w));
            }
        }
    }

    private Map<String, Map<String, double[]>> pose() {
        Map<String, Map<String, double[]>> pose = new HashMap<>();
        blend(pose, rig.eval("idle", time + idleOffset), 1.0);
        if (wmove > 0.01) {
            blend(pose, rig.eval("walk", phase), wmove);
        }
        if (talking) {
            blend(pose, rig.eval("talk", time), 1 - 0.7 * wmove);
        }
        for (Gesture g : gestures) {
            double len = rig.length(g.clip());
            double lt = (time - g.start()) * g.speed();
            double w = Math.min(1.0, Math.min((time - g.start()) / 0.18, (g.until() - time) / 0.22));
            w = Math.max(0.0, w);
            w = w * w * (3 - 2 * w);
            blend(pose, rig.eval(g.clip(), rig.loops(g.clip()) ? lt % len : Math.min(lt, len)), w);
        }
        Map<String, double[]> head = pose.computeIfAbsent("head", k -> new HashMap<>());
        double[] r = head.getOrDefault("rotation", new double[]{0, 0, 0});
        head.put("rotation", new double[]{r[0] - headPitch, r[1] + headYaw, r[2]});
        return pose;
    }

    private void apply() {
        if (parts.isEmpty()) {
            return;
        }
        Map<String, double[]> placed = rig.place(pose());
        // rotazione del corpo intero: Ry(-yaw) del rig
        double a = Math.toRadians(-yaw);
        double ca = Math.cos(a);
        double sa = Math.sin(a);
        for (Map.Entry<String, ItemDisplay> e : parts.entrySet()) {
            double[] m = placed.get(e.getKey());
            ItemDisplay d = e.getValue();
            if (m == null || !d.isValid()) {
                continue;
            }
            // m = 3x3 (riga per riga) + traslazione (in pixel)
            double[][] r = {{m[0], m[1], m[2]}, {m[3], m[4], m[5]}, {m[6], m[7], m[8]}};
            double[][] w = {{ca, 0, sa}, {0, 1, 0}, {-sa, 0, ca}};
            double[][] rr = mul(w, r);
            if (flip) {
                // Minecraft gira l'oggetto di 180 gradi attorno a Y prima della trasformazione: si annulla qui
                rr = mul(rr, new double[][]{{-1, 0, 0}, {0, 1, 0}, {0, 0, -1}});
            }
            double tx = m[9] / 16.0;
            double ty = m[10] / 16.0;
            double tz = m[11] / 16.0;
            double wx = w[0][0] * tx + w[0][1] * ty + w[0][2] * tz;
            double wy = w[1][0] * tx + w[1][1] * ty + w[1][2] * tz;
            double wz = w[2][0] * tx + w[2][1] * ty + w[2][2] * tz;
            d.setInterpolationDelay(0);
            d.setInterpolationDuration(3);
            d.setTransformation(new Transformation(new Vector3f((float) wx, (float) wy, (float) wz),
                    quat(rr), new Vector3f(1f, 1f, 1f), new Quaternionf()));
        }
    }

    private static double[][] mul(double[][] a, double[][] b) {
        double[][] o = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                o[i][j] = a[i][0] * b[0][j] + a[i][1] * b[1][j] + a[i][2] * b[2][j];
            }
        }
        return o;
    }

    /** Da matrice di rotazione (riga, colonna) a quaternione. */
    private static Quaternionf quat(double[][] m) {
        double tr = m[0][0] + m[1][1] + m[2][2];
        double x;
        double y;
        double z;
        double w;
        if (tr > 0) {
            double s = Math.sqrt(tr + 1.0) * 2;
            w = 0.25 * s;
            x = (m[2][1] - m[1][2]) / s;
            y = (m[0][2] - m[2][0]) / s;
            z = (m[1][0] - m[0][1]) / s;
        } else if (m[0][0] > m[1][1] && m[0][0] > m[2][2]) {
            double s = Math.sqrt(1.0 + m[0][0] - m[1][1] - m[2][2]) * 2;
            w = (m[2][1] - m[1][2]) / s;
            x = 0.25 * s;
            y = (m[0][1] + m[1][0]) / s;
            z = (m[0][2] + m[2][0]) / s;
        } else if (m[1][1] > m[2][2]) {
            double s = Math.sqrt(1.0 + m[1][1] - m[0][0] - m[2][2]) * 2;
            w = (m[0][2] - m[2][0]) / s;
            x = (m[0][1] + m[1][0]) / s;
            y = 0.25 * s;
            z = (m[1][2] + m[2][1]) / s;
        } else {
            double s = Math.sqrt(1.0 + m[2][2] - m[0][0] - m[1][1]) * 2;
            w = (m[1][0] - m[0][1]) / s;
            x = (m[0][2] + m[2][0]) / s;
            y = (m[1][2] + m[2][1]) / s;
            z = 0.25 * s;
        }
        return new Quaternionf((float) x, (float) y, (float) z, (float) w).normalize();
    }

    /** Il giocatore piu' vicino (nello stesso mondo) entro range blocchi. */
    Player nearest(double range) {
        Player best = null;
        double bestD = range * range;
        for (Player p : feet.getWorld().getPlayers()) {
            double d = p.getLocation().distanceSquared(feet);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }
}
