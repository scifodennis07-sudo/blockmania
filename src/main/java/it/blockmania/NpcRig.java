package it.blockmania;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Lo scheletro e le animazioni (npc_rig.json, esportate dal rig). Tutti i valori sono in pixel e gradi. */
final class NpcRig {

    private record Bone(String name, String parent, double[] pivot) {
    }

    private final List<Bone> bones = new ArrayList<>();
    private final List<String> cubeBones = new ArrayList<>();
    private final Map<String, double[]> lengths = new HashMap<>();
    // clip -> osso -> canale -> tempi / valori
    private final Map<String, Map<String, Map<String, double[]>>> keyTimes = new HashMap<>();
    private final Map<String, Map<String, Map<String, double[][]>>> keyValues = new HashMap<>();

    static NpcRig load(BlockMania plugin) {
        NpcRig rig = new NpcRig();
        try (InputStream in = plugin.getResource("npc_rig.json")) {
            if (in == null) {
                return rig;
            }
            JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            for (JsonElement e : root.getAsJsonArray("bones")) {
                JsonObject b = e.getAsJsonObject();
                JsonArray pv = b.getAsJsonArray("pivot");
                rig.bones.add(new Bone(b.get("name").getAsString(),
                        b.get("parent").isJsonNull() ? null : b.get("parent").getAsString(),
                        new double[]{pv.get(0).getAsDouble(), pv.get(1).getAsDouble(), pv.get(2).getAsDouble()}));
            }
            for (JsonElement e : root.getAsJsonArray("cubeBones")) {
                rig.cubeBones.add(e.getAsString());
            }
            JsonObject clips = root.getAsJsonObject("clips");
            for (String name : clips.keySet()) {
                JsonObject c = clips.getAsJsonObject(name);
                rig.lengths.put(name, new double[]{c.get("length").getAsDouble(), c.get("loop").getAsBoolean() ? 1 : 0});
                Map<String, Map<String, double[]>> times = new HashMap<>();
                Map<String, Map<String, double[][]>> values = new HashMap<>();
                JsonObject bs = c.getAsJsonObject("bones");
                for (String bone : bs.keySet()) {
                    JsonObject chs = bs.getAsJsonObject(bone);
                    for (String ch : chs.keySet()) {
                        JsonArray kfs = chs.getAsJsonArray(ch);
                        double[] t = new double[kfs.size()];
                        double[][] v = new double[kfs.size()][3];
                        for (int i = 0; i < kfs.size(); i++) {
                            JsonArray kf = kfs.get(i).getAsJsonArray();
                            t[i] = kf.get(0).getAsDouble();
                            JsonArray val = kf.get(1).getAsJsonArray();
                            for (int k = 0; k < 3; k++) {
                                v[i][k] = val.get(k).getAsDouble();
                            }
                        }
                        times.computeIfAbsent(bone, k -> new HashMap<>()).put(ch, t);
                        values.computeIfAbsent(bone, k -> new HashMap<>()).put(ch, v);
                    }
                }
                rig.keyTimes.put(name, times);
                rig.keyValues.put(name, values);
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("npc_rig.json: " + ex.getMessage());
        }
        return rig;
    }

    List<String> cubeBones() {
        return cubeBones;
    }

    boolean hasClip(String name) {
        return lengths.containsKey(name);
    }

    List<String> clipNames() {
        return new ArrayList<>(lengths.keySet());
    }

    double length(String clip) {
        double[] l = lengths.get(clip);
        return l == null ? 1.0 : l[0];
    }

    boolean loops(String clip) {
        double[] l = lengths.get(clip);
        return l != null && l[1] > 0;
    }

    private static double[] catmull(double[] p0, double[] p1, double[] p2, double[] p3, double u) {
        double[] o = new double[3];
        for (int i = 0; i < 3; i++) {
            double a = p0[i];
            double b = p1[i];
            double c = p2[i];
            double d = p3[i];
            o[i] = 0.5 * ((2 * b) + (-a + c) * u + (2 * a - 5 * b + 4 * c - d) * u * u + (-a + 3 * b - 3 * c + d) * u * u * u);
        }
        return o;
    }

    private static double[] sample(double[] t, double[][] v, double time, boolean loop, double length) {
        int n = t.length;
        if (n == 1) {
            return v[0];
        }
        if (loop) {
            time = ((time % length) + length) % length;
        }
        if (time <= t[0]) {
            return v[0];
        }
        if (time >= t[n - 1]) {
            return v[n - 1];
        }
        int i = 0;
        while (t[i + 1] < time) {
            i++;
        }
        double u = t[i + 1] > t[i] ? (time - t[i]) / (t[i + 1] - t[i]) : 0;
        double[] v0 = i > 0 ? v[i - 1] : v[i];
        double[] v3 = i + 2 < n ? v[i + 2] : v[i + 1];
        return catmull(v0, v[i], v[i + 1], v3, u);
    }

    /** I valori di una clip al tempo t: osso -> canale (rotation / position) -> x, y, z. */
    Map<String, Map<String, double[]>> eval(String clip, double t) {
        Map<String, Map<String, double[]>> out = new LinkedHashMap<>();
        Map<String, Map<String, double[]>> times = keyTimes.get(clip);
        if (times == null) {
            return out;
        }
        double len = length(clip);
        boolean loop = loops(clip);
        for (Map.Entry<String, Map<String, double[]>> be : times.entrySet()) {
            for (Map.Entry<String, double[]> ce : be.getValue().entrySet()) {
                double[][] v = keyValues.get(clip).get(be.getKey()).get(ce.getKey());
                out.computeIfAbsent(be.getKey(), k -> new HashMap<>()).put(ce.getKey(), sample(ce.getValue(), v, t, loop, len));
            }
        }
        return out;
    }

    // ---------- matrici ----------

    private static double[][] rx(double a) {
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new double[][]{{1, 0, 0}, {0, c, -s}, {0, s, c}};
    }

    private static double[][] ry(double a) {
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new double[][]{{c, 0, s}, {0, 1, 0}, {-s, 0, c}};
    }

    private static double[][] rz(double a) {
        double c = Math.cos(a);
        double s = Math.sin(a);
        return new double[][]{{c, -s, 0}, {s, c, 0}, {0, 0, 1}};
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

    private static double[] apply(double[][] r, double[] v) {
        return new double[]{r[0][0] * v[0] + r[0][1] * v[1] + r[0][2] * v[2],
                r[1][0] * v[0] + r[1][1] * v[1] + r[1][2] * v[2],
                r[2][0] * v[0] + r[2][1] * v[1] + r[2][2] * v[2]};
    }

    /**
     * Per ogni osso con cubi: 9 numeri della rotazione (riga per riga) e 3 della posizione del suo punto di
     * rotazione nel modello (in pixel).
     */
    Map<String, double[]> place(Map<String, Map<String, double[]>> pose) {
        Map<String, double[][]> rot = new HashMap<>();
        Map<String, double[]> tr = new HashMap<>();
        Map<String, double[]> out = new HashMap<>();
        for (Bone b : bones) {
            Map<String, double[]> ch = pose.getOrDefault(b.name(), Map.of());
            double[] r = ch.getOrDefault("rotation", new double[]{0, 0, 0});
            double[] p = ch.getOrDefault("position", new double[]{0, 0, 0});
            double[][] rm = mul(rz(Math.toRadians(r[2])), mul(ry(Math.toRadians(-r[1])), rx(Math.toRadians(-r[0]))));
            double[] pv = b.pivot();
            double[] rp = apply(rm, pv);
            double[] t = {pv[0] - p[0] - rp[0], pv[1] + p[1] - rp[1], pv[2] + p[2] - rp[2]};
            if (b.parent() != null) {
                double[][] pr = rot.get(b.parent());
                double[] pt = tr.get(b.parent());
                double[] nt = apply(pr, t);
                t = new double[]{nt[0] + pt[0], nt[1] + pt[1], nt[2] + pt[2]};
                rm = mul(pr, rm);
            }
            rot.put(b.name(), rm);
            tr.put(b.name(), t);
            if (cubeBones.contains(b.name())) {
                double[] wp = apply(rm, pv);
                out.put(b.name(), new double[]{rm[0][0], rm[0][1], rm[0][2], rm[1][0], rm[1][1], rm[1][2],
                        rm[2][0], rm[2][1], rm[2][2], wp[0] + t[0], wp[1] + t[1], wp[2] + t[2]});
            }
        }
        return out;
    }
}
