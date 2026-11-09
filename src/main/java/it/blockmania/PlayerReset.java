package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataContainer;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Reset di un giocatore: riporta allo stato iniziale tutto tranne le sanzioni (per quelle c'e' SanctionManager.resetAll).
 *
 * Online: si azzerano tutti i dati salvati sul giocatore dal plugin (palle, shell, coin, blockcoin, miniera, ricompense,
 * livelli ritirati, potenziamenti, stamina e attacco potenziato, tutorial visto, scelte, contatori sparati...),
 * le sue copie personali delle miniere e lo snapshot dei contatori.
 * Offline: i dati sul giocatore (PDC) non si possono toccare senza che sia connesso, quindi si azzerano subito i file
 * (copie miniere, snapshot contatori) e il resto viene messo in coda (pending-resets.yml) e fatto al suo prossimo ingresso.
 *
 * Si tocca solo cio' che appartiene a quel giocatore: nessun mondo, nessun file di altri. Va chiamato dal thread principale.
 */
public final class PlayerReset implements Listener {

    /** Chiavi del plugin sul giocatore che NON sono progressi: flag staff e ritorno dalla miniera. */
    private static final Set<String> KEEP = Set.of("limits_off", "mine_return");

    private final BlockMania plugin;
    private final File pendingFile;

    public PlayerReset(BlockMania plugin) {
        this.plugin = plugin;
        this.pendingFile = new File(plugin.getDataFolder(), "pending-resets.yml");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** Azzera tutto tranne le sanzioni. Ritorna un riassunto su una riga (cosa fatto e cosa no). */
    public String resetPlayer(UUID target, CommandSender by) {
        if (target == null) {
            return "Giocatore non valido.";
        }
        List<String> done = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        Player online = Bukkit.getPlayer(target);

        // copie personali delle miniere (file del solo giocatore)
        try {
            MineTemplates mt = plugin.getMineTemplates();
            if (mt != null) {
                int n = mt.resetPlayer(target);
                done.add("miniere (" + n + " copie)");
            } else {
                skipped.add("miniere");
            }
        } catch (RuntimeException e) {
            skipped.add("miniere (" + e.getMessage() + ")");
        }

        // snapshot dei contatori (counters.yml, solo la sua sezione)
        try {
            if (plugin.getCounters() != null) {
                plugin.getCounters().forget(target);
                done.add("contatori salvati");
            }
        } catch (RuntimeException e) {
            skipped.add("contatori salvati (" + e.getMessage() + ")");
        }

        if (online != null) {
            try {
                int n = wipeOnline(online);
                done.add("dati del giocatore (" + n + " valori: palle, shell, coin, blockcoin, miniera, ricompense, "
                        + "potenziamenti, stamina, attacco potenziato, tutorial)");
            } catch (RuntimeException e) {
                skipped.add("dati del giocatore (" + e.getMessage() + ")");
            }
        } else {
            addPending(target);
            skipped.add("dati sul giocatore (offline: verranno azzerati al prossimo ingresso)");
        }

        String name = nameOf(target, online);
        String who = by == null ? "console" : by.getName();
        plugin.getLogger().info("Reset di " + name + " eseguito da " + who + ": " + String.join(", ", done)
                + (skipped.isEmpty() ? "" : " | non fatto subito: " + String.join(", ", skipped)));

        StringBuilder sb = new StringBuilder("Reset di ").append(name).append(": ");
        sb.append(done.isEmpty() ? "niente azzerato" : String.join(", ", done));
        if (!skipped.isEmpty()) {
            sb.append(" | non fatto: ").append(String.join(", ", skipped));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ online

    /** Toglie dal giocatore ogni valore salvato dal plugin (tranne i flag staff) e ridisegna cio' che dipende da loro. */
    private int wipeOnline(Player p) {
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        String ns = new NamespacedKey(plugin, "x").getNamespace();
        List<NamespacedKey> remove = new ArrayList<>();
        for (NamespacedKey k : pdc.getKeys()) {
            if (k.getNamespace().equals(ns) && !KEEP.contains(k.getKey())) {
                remove.add(k);
            }
        }
        for (NamespacedKey k : remove) {
            pdc.remove(k);
        }
        try {
            if (plugin.getUpgrades() != null) {
                plugin.getUpgrades().refreshLaunchers(p);
            }
            if (plugin.getAmmo() != null) {
                plugin.getAmmo().refreshMain(p);
            }
        } catch (RuntimeException e) {
            plugin.getLogger().warning("Reset: aggiornamento grafica di " + p.getName() + ": " + e.getMessage());
        }
        return remove.size();
    }

    // ------------------------------------------------------------------ in coda per chi e' offline

    private synchronized void addPending(UUID id) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pendingFile);
        Set<String> all = new HashSet<>(y.getStringList("pending"));
        if (all.add(id.toString())) {
            y.set("pending", new ArrayList<>(all));
            savePending(y);
        }
    }

    private synchronized boolean takePending(UUID id) {
        if (!pendingFile.isFile()) {
            return false;
        }
        YamlConfiguration y = YamlConfiguration.loadConfiguration(pendingFile);
        List<String> list = new ArrayList<>(y.getStringList("pending"));
        if (!list.remove(id.toString())) {
            return false;
        }
        y.set("pending", list);
        savePending(y);
        return true;
    }

    private void savePending(YamlConfiguration y) {
        try {
            plugin.getDataFolder().mkdirs();
            y.save(pendingFile);
        } catch (IOException e) {
            plugin.getLogger().warning("pending-resets.yml: " + e.getMessage());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!takePending(id)) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null) {
                addPending(id); // e' uscito prima: resta in coda
                return;
            }
            try {
                int n = wipeOnline(p);
                plugin.getCounters().forget(id);
                plugin.getLogger().info("Reset in sospeso applicato a " + p.getName() + " (" + n + " valori).");
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Reset in sospeso di " + p.getName() + " fallito: " + e.getMessage());
                addPending(id);
            }
        }, 10L);
    }

    private static String nameOf(UUID id, Player online) {
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : id.toString();
    }
}
