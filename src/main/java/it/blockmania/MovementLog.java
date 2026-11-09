package it.blockmania;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cronologia dei movimenti di ogni player (miniere, acquisti, ticket, ricompense...).
 * Massimo {@link #MAX_PER_PLAYER} voci per player, salvate su movements.yml.
 * Thread-safe: record() si puo' chiamare da qualsiasi thread; il salvataggio e' asincrono a intervalli.
 */
public final class MovementLog {

    public static final int MAX_PER_PLAYER = 500;
    private static final long SAVE_EVERY_TICKS = 20L * 60;

    public enum Type {
        MINE("Miniere", "IRON_PICKAXE"),
        SHOP("Acquisti", "EMERALD"),
        TICKET("Ticket", "PAPER"),
        REWARD("Ricompense", "CHEST"),
        UPGRADE("Potenziamenti", "ANVIL"),
        SANCTION("Sanzioni", "BARRIER"),
        ITEMS("Oggetti", "SNOWBALL"),
        STAFF("Staff", "SHIELD"),
        OTHER("Altro", "BOOK");

        private final String label;
        private final String icon;

        Type(String label, String icon) {
            this.label = label;
            this.icon = icon;
        }

        public String label() {
            return label;
        }

        public String icon() {
            return icon;
        }
    }

    public record Entry(long ts, Type type, String text) {}

    private final BlockMania plugin;
    private final File file;
    private final Map<UUID, LinkedList<Entry>> data = new HashMap<>();
    private volatile boolean dirty;
    private volatile boolean saving;
    private org.bukkit.scheduler.BukkitTask task;

    public MovementLog(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "movements.yml");
    }

    public synchronized void load() {
        data.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yaml.getConfigurationSection("players");
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                continue;
            }
            LinkedList<Entry> list = new LinkedList<>();
            for (String line : sec.getStringList(key)) {
                String[] p = line.split(";", 3);
                if (p.length < 3) {
                    continue;
                }
                try {
                    list.add(new Entry(Long.parseLong(p[0]), Type.valueOf(p[1]), p[2]));
                } catch (IllegalArgumentException ignored) {
                    // riga non valida: saltata
                }
            }
            while (list.size() > MAX_PER_PLAYER) {
                list.removeFirst();
            }
            data.put(id, list);
        }
        // salvataggio periodico asincrono
        if (task == null) {
            task = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> saveAsync(), SAVE_EVERY_TICKS, SAVE_EVERY_TICKS);
        }
    }

    private synchronized List<String> snapshot(Map<String, List<String>> out) {
        for (Map.Entry<UUID, LinkedList<Entry>> e : data.entrySet()) {
            List<String> lines = new ArrayList<>(e.getValue().size());
            for (Entry en : e.getValue()) {
                lines.add(en.ts() + ";" + en.type().name() + ";" + en.text().replace('\n', ' '));
            }
            out.put(e.getKey().toString(), lines);
        }
        return null;
    }

    private void write(Map<String, List<String>> snap) throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<String, List<String>> e : snap.entrySet()) {
            yaml.set("players." + e.getKey(), e.getValue());
        }
        plugin.getDataFolder().mkdirs();
        File tmp = new File(plugin.getDataFolder(), "movements.yml.tmp");
        yaml.save(tmp);
        java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    /** Salvataggio sincrono (usare allo spegnimento). */
    public void save() {
        Map<String, List<String>> snap = new HashMap<>();
        synchronized (this) {
            snapshot(snap);
            dirty = false;
        }
        synchronized (file) {
            try {
                write(snap);
            } catch (IOException ex) {
                dirty = true;
                plugin.getLogger().severe("Non riesco a salvare movements.yml: " + ex.getMessage());
            }
        }
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void saveAsync() {
        if (!dirty || saving) {
            return;
        }
        Map<String, List<String>> snap = new HashMap<>();
        synchronized (this) {
            snapshot(snap);
            dirty = false;
        }
        saving = true;
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                synchronized (file) {
                    write(snap);
                }
            } catch (IOException ex) {
                dirty = true;
                plugin.getLogger().severe("Non riesco a salvare movements.yml: " + ex.getMessage());
            } finally {
                saving = false;
            }
        });
    }

    public void record(UUID player, Type type, String text) {
        if (player == null || text == null || text.isBlank()) {
            return;
        }
        Entry entry = new Entry(System.currentTimeMillis(), type == null ? Type.OTHER : type, text);
        synchronized (this) {
            LinkedList<Entry> list = data.computeIfAbsent(player, k -> new LinkedList<>());
            list.addLast(entry);
            while (list.size() > MAX_PER_PLAYER) {
                list.removeFirst();
            }
        }
        dirty = true;
    }

    /** Voci del player, le piu' recenti prima; filterOrNull = solo quel tipo. */
    public synchronized List<Entry> list(UUID player, Type filterOrNull) {
        List<Entry> out = new ArrayList<>();
        LinkedList<Entry> list = data.get(player);
        if (list == null) {
            return out;
        }
        for (java.util.Iterator<Entry> it = list.descendingIterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (filterOrNull == null || e.type() == filterOrNull) {
                out.add(e);
            }
        }
        return out;
    }
}
