package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Id progressivi dei player (player-ids.yml): id utente u-00001 dato al primo accesso, id staffer s-00001 dato la
 * prima volta che riceve un grado staff o un ruolo speciale. Gli id non cambiano mai e non si riusano (anche dopo un
 * depex l'id staffer resta).
 */
public final class PlayerIds implements Listener {

    private final BlockMania plugin;
    private final File file;
    private final Map<UUID, Integer> users = new HashMap<>();
    private final Map<UUID, Integer> staff = new HashMap<>();
    private final Map<Integer, UUID> userById = new HashMap<>();
    private final Map<Integer, UUID> staffById = new HashMap<>();
    /** Prossimo numero libero: non scende mai, cosi' un id non si riusa. */
    private int nextUser = 1;
    private int nextStaff = 1;

    public PlayerIds(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "player-ids.yml");
    }

    public void load() {
        users.clear();
        staff.clear();
        userById.clear();
        staffById.clear();
        nextUser = 1;
        nextStaff = 1;
        if (file.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
            readSection(y.getConfigurationSection("users"), users, userById);
            readSection(y.getConfigurationSection("staff"), staff, staffById);
            nextUser = Math.max(y.getInt("next-user", 1), maxKey(userById) + 1);
            nextStaff = Math.max(y.getInt("next-staff", 1), maxKey(staffById) + 1);
        }
        // player gia' conosciuti dal server: in ordine di primo accesso, cosi' i vecchi hanno i numeri piu' bassi
        List<OfflinePlayer> known = new ArrayList<>(Arrays.asList(Bukkit.getOfflinePlayers()));
        known.sort(Comparator.comparingLong((OfflinePlayer op) -> op.getFirstPlayed() > 0 ? op.getFirstPlayed() : Long.MAX_VALUE)
                .thenComparing((OfflinePlayer op) -> op.getUniqueId()));
        boolean changed = false;
        for (OfflinePlayer op : known) {
            if (!users.containsKey(op.getUniqueId())) {
                assignUser(op.getUniqueId());
                changed = true;
            }
        }
        if (changed) {
            save();
        }
    }

    private void readSection(ConfigurationSection sec, Map<UUID, Integer> byUuid, Map<Integer, UUID> byNum) {
        if (sec == null) {
            return;
        }
        for (String key : sec.getKeys(false)) {
            try {
                UUID u = UUID.fromString(key);
                int n = sec.getInt(key, 0);
                if (n <= 0 || byNum.containsKey(n)) {
                    plugin.getLogger().warning("player-ids.yml: id non valido o doppio per " + key);
                    continue;
                }
                byUuid.put(u, n);
                byNum.put(n, u);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("player-ids.yml: uuid non valido " + key);
            }
        }
    }

    private static int maxKey(Map<Integer, UUID> m) {
        int max = 0;
        for (int n : m.keySet()) {
            max = Math.max(max, n);
        }
        return max;
    }

    public void save() {
        YamlConfiguration y = new YamlConfiguration();
        y.set("next-user", nextUser);
        y.set("next-staff", nextStaff);
        for (Map.Entry<UUID, Integer> e : users.entrySet()) {
            y.set("users." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, Integer> e : staff.entrySet()) {
            y.set("staff." + e.getKey(), e.getValue());
        }
        try {
            plugin.getDataFolder().mkdirs();
            y.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("player-ids.yml: " + ex.getMessage());
        }
    }

    private int assignUser(UUID id) {
        int n = nextUser++;
        users.put(id, n);
        userById.put(n, id);
        return n;
    }

    /** Id utente (u-00012); se il player non ce l'ha ancora glielo assegna e salva. */
    public String userId(UUID id) {
        if (id == null) {
            return null;
        }
        Integer n = users.get(id);
        if (n == null) {
            n = assignUser(id);
            save();
        }
        return format("u", n);
    }

    /** Id staffer (s-00003) o null se non e' mai stato staff. */
    public String staffId(UUID id) {
        Integer n = id == null ? null : staff.get(id);
        return n == null ? null : format("s", n);
    }

    /** Da' l'id staffer se non ce l'ha ancora (primo grado staff o ruolo speciale). True se ne ha assegnato uno. */
    public boolean ensureStaffId(UUID id) {
        if (id == null || staff.containsKey(id)) {
            return false;
        }
        int n = nextStaff++;
        staff.put(id, n);
        staffById.put(n, id);
        userId(id);   // uno staffer ha sempre anche l'id utente (salva se lo assegna)
        save();
        return true;
    }

    /** All'avvio: id staffer a chi e' gia' staff (o lo e' stato), in ordine di id utente. */
    public void ensureStaffIds(Collection<UUID> ids) {
        List<UUID> todo = new ArrayList<>();
        for (UUID u : ids) {
            if (u != null && !staff.containsKey(u) && !todo.contains(u)) {
                todo.add(u);
            }
        }
        if (todo.isEmpty()) {
            return;
        }
        todo.sort(Comparator.comparingInt((UUID u) -> users.getOrDefault(u, Integer.MAX_VALUE)).thenComparing((UUID u) -> u));
        for (UUID u : todo) {
            if (!users.containsKey(u)) {
                assignUser(u);
            }
            int n = nextStaff++;
            staff.put(u, n);
            staffById.put(n, u);
        }
        save();
    }

    /** Player da un id "u-12", "U-00012", "s-3"...; null se il formato e' sbagliato o l'id non esiste. */
    public UUID byId(String id) {
        if (id == null) {
            return null;
        }
        String s = id.trim().toLowerCase(Locale.ROOT);
        if (s.length() < 3 || s.charAt(1) != '-' || (s.charAt(0) != 'u' && s.charAt(0) != 's')) {
            return null;
        }
        String num = s.substring(2);
        if (num.isEmpty() || num.length() > 9 || !num.matches("[0-9]+")) {
            return null;
        }
        int n = Integer.parseInt(num);
        return s.charAt(0) == 'u' ? userById.get(n) : staffById.get(n);
    }

    private static String format(String prefix, int n) {
        return prefix + "-" + String.format(Locale.ROOT, "%05d", n);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        userId(event.getPlayer().getUniqueId());
    }
}
