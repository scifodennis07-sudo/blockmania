package it.blockmania;

import io.papermc.paper.persistence.PersistentDataContainerView;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Dati del player salvati nel suo PersistentDataContainer (contatori interi e testi del plugin: miniere, potenziamenti,
 * ricompense, exp...) letti e scritti per UUID, anche col player OFFLINE (profili: R-OFF).
 * <ul>
 *   <li>online: lettura e scrittura diretta sul player (come AmmoManager.getCounter / setCounter);</li>
 *   <li>offline: lettura dai dati salvati su disco (OfflinePlayer.getPersistentDataContainer, sola lettura) con sopra le
 *       modifiche in coda; scrittura = coda (offline-pdc.yml) applicata al prossimo login, prima di tutto il resto.</li>
 * </ul>
 * Le chiavi sono i nomi delle chiavi del plugin (namespace blockmania), in minuscolo: "mine", "mine_runs_3", "rw_exp",
 * "upgrade_speed", "rwc_broken"... Tutti i metodi vanno chiamati dal thread principale.
 */
public final class OfflinePdc implements Listener {

    private static OfflinePdc instance;

    private final BlockMania plugin;
    private final File file;
    private final YamlConfiguration pending;
    /** Dati salvati letti da disco, tenuti per poco (ogni lettura di un player offline puo' leggere il file). */
    private final Map<UUID, Cached> cache = new HashMap<>();

    private record Cached(PersistentDataContainerView view, long at) {
    }

    private static final long CACHE_MS = 5000L;
    /** Valore in coda: "i:<numero>", "s:<testo>" oppure "x" (chiave da togliere). */
    private static final String REMOVE = "x";

    private OfflinePdc(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "offline-pdc.yml");
        this.pending = YamlConfiguration.loadConfiguration(file);
    }

    /** Istanza unica (registra il listener del login la prima volta). Va chiamata all'avvio (lo fa Rewards). */
    public static synchronized OfflinePdc get(BlockMania plugin) {
        if (instance == null || instance.plugin != plugin) {
            instance = new OfflinePdc(plugin);
            Bukkit.getPluginManager().registerEvents(instance, plugin);
        }
        return instance;
    }

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name.toLowerCase(Locale.ROOT));
    }

    private static String path(UUID id, String name) {
        return id + "." + name.toLowerCase(Locale.ROOT);
    }

    private PersistentDataContainerView saved(UUID id) {
        long now = System.currentTimeMillis();
        Cached c = cache.get(id);
        if (c != null && now - c.at() < CACHE_MS) {
            return c.view();
        }
        PersistentDataContainerView v;
        try {
            v = Bukkit.getOfflinePlayer(id).getPersistentDataContainer();
        } catch (Throwable ex) {
            v = null;   // mai entrato o file non leggibile: valori di default
        }
        cache.put(id, new Cached(v, now));
        return v;
    }

    /** true se il player e' online (le modifiche sono subito valide). */
    public boolean isOnline(UUID id) {
        return id != null && Bukkit.getPlayer(id) != null;
    }

    // ---------- lettura ----------

    public int getInt(UUID id, String name) {
        Player p = id == null ? null : Bukkit.getPlayer(id);
        if (p != null) {
            return p.getPersistentDataContainer().getOrDefault(key(name), PersistentDataType.INTEGER, 0);
        }
        if (id == null) {
            return 0;
        }
        String q = pending.getString(path(id, name));
        if (q != null) {
            if (q.startsWith("i:")) {
                try {
                    return Integer.parseInt(q.substring(2));
                } catch (NumberFormatException ignored) {
                    return 0;
                }
            }
            return 0;
        }
        PersistentDataContainerView v = saved(id);
        if (v == null) {
            return 0;
        }
        try {
            Integer n = v.get(key(name), PersistentDataType.INTEGER);
            return n == null ? 0 : n;
        } catch (Throwable ex) {
            return 0;
        }
    }

    /** Testo salvato (null = non c'e'). */
    public String getString(UUID id, String name) {
        Player p = id == null ? null : Bukkit.getPlayer(id);
        if (p != null) {
            return p.getPersistentDataContainer().get(key(name), PersistentDataType.STRING);
        }
        if (id == null) {
            return null;
        }
        String q = pending.getString(path(id, name));
        if (q != null) {
            return q.startsWith("s:") ? q.substring(2) : null;
        }
        PersistentDataContainerView v = saved(id);
        if (v == null) {
            return null;
        }
        try {
            return v.get(key(name), PersistentDataType.STRING);
        } catch (Throwable ex) {
            return null;
        }
    }

    /** true se la chiave c'e' (qualsiasi tipo, es. il BYTE "limits_off" di Power). */
    public boolean has(UUID id, String name) {
        Player p = id == null ? null : Bukkit.getPlayer(id);
        if (p != null) {
            return p.getPersistentDataContainer().has(key(name));
        }
        if (id == null) {
            return false;
        }
        String q = pending.getString(path(id, name));
        if (q != null) {
            return !q.equals(REMOVE);
        }
        PersistentDataContainerView v = saved(id);
        try {
            return v != null && v.has(key(name));
        } catch (Throwable ex) {
            return false;
        }
    }

    // ---------- scrittura ----------

    /** Come AmmoManager.setCounter (valori sotto zero = 0): subito se online, altrimenti in coda fino al login. */
    public void setInt(UUID id, String name, int value) {
        int v = Math.max(0, value);
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            p.getPersistentDataContainer().set(key(name), PersistentDataType.INTEGER, v);
            return;
        }
        pending.set(path(id, name), "i:" + v);
        save();
    }

    /** Testo; null = toglie la chiave. Subito se online, altrimenti in coda fino al login. */
    public void setString(UUID id, String name, String value) {
        Player p = Bukkit.getPlayer(id);
        if (p != null) {
            if (value == null) {
                p.getPersistentDataContainer().remove(key(name));
            } else {
                p.getPersistentDataContainer().set(key(name), PersistentDataType.STRING, value);
            }
            return;
        }
        pending.set(path(id, name), value == null ? REMOVE : "s:" + value);
        save();
    }

    /** Quante modifiche aspettano il login del player (per la lore: "modifica: al prossimo accesso"). */
    public int pendingCount(UUID id) {
        ConfigurationSection s = id == null ? null : pending.getConfigurationSection(id.toString());
        return s == null ? 0 : s.getKeys(false).size();
    }

    private void save() {
        try {
            pending.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("offline-pdc.yml: " + ex.getMessage());
        }
    }

    // ---------- login: applica la coda ----------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID id = p.getUniqueId();
        cache.remove(id);
        ConfigurationSection s = pending.getConfigurationSection(id.toString());
        if (s == null) {
            return;
        }
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        int count = s.getKeys(false).size();
        for (String name : s.getKeys(false)) {
            String q = s.getString(name, REMOVE);
            if (q.startsWith("i:")) {
                try {
                    pdc.set(key(name), PersistentDataType.INTEGER, Math.max(0, Integer.parseInt(q.substring(2))));
                } catch (NumberFormatException ignored) {
                    // valore rovinato: saltato
                }
            } else if (q.startsWith("s:")) {
                pdc.set(key(name), PersistentDataType.STRING, q.substring(2));
            } else {
                pdc.remove(key(name));
            }
        }
        pending.set(id.toString(), null);
        save();
        plugin.getLogger().info("offline-pdc: applicate " + count + " modifiche a " + p.getName());
    }
}
