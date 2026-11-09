package it.blockmania;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Contatori di un giocatore per il profilo staff: valori, massimi, comandi (give/remove/set/full/reset),
 * totale di palle sparate e snapshot al quit per i giocatori offline (sola lettura).
 *
 * Tutti i metodi che scrivono vanno chiamati dal thread principale.
 */
public final class PlayerCounters implements Listener {

    public enum Item { DOUBLEBALL, GHOSTBALL, STANDARDBALL, EXPLOSIONBALL, BLOCKSHELL, BLOCKCOIN, COIN, POWER_CHARGES, POWER_USES }

    /** Istanza unica, impostata dal costruttore (LauncherListener la usa senza passare da BlockMania). */
    public static PlayerCounters INSTANCE;

    private static final String SHOTS_POWER = "shots_power";

    private final BlockMania plugin;
    private final File file;
    private final YamlConfiguration snap;

    public PlayerCounters(BlockMania plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "counters.yml");
        this.snap = YamlConfiguration.loadConfiguration(file);
        INSTANCE = this;
    }

    // ---------- helpers ----------

    private static boolean isBall(Item it) {
        return it == Item.DOUBLEBALL || it == Item.GHOSTBALL || it == Item.STANDARDBALL || it == Item.EXPLOSIONBALL;
    }

    private static String ballType(Item it) {
        return it.name().toLowerCase(Locale.ROOT);
    }

    private Player online(UUID p) {
        return Bukkit.getPlayer(p);
    }

    public boolean isOnline(UUID p) {
        return online(p) != null;
    }

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }

    private long getLong(Player p, String name) {
        return p.getPersistentDataContainer().getOrDefault(key(name), PersistentDataType.LONG, 0L);
    }

    private static int clamp(long v) {
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, v));
    }

    // ---------- lettura ----------

    private int liveValue(Player pl, Item it) {
        AmmoManager ammo = plugin.getAmmo();
        Power power = plugin.getPower();
        return switch (it) {
            case DOUBLEBALL, GHOSTBALL, STANDARDBALL, EXPLOSIONBALL -> ammo.getAmmo(pl, ballType(it));
            case BLOCKSHELL -> ammo.getShell(pl);
            case BLOCKCOIN -> ammo.getBlockCoin(pl);
            case COIN -> ammo.getCoin(pl);
            case POWER_CHARGES -> power == null ? 0 : power.charges(pl);
            case POWER_USES -> power == null ? 0 : power.uses(pl);
        };
    }

    private int liveMax(Player pl, Item it) {
        AmmoManager ammo = plugin.getAmmo();
        Power power = plugin.getPower();
        return switch (it) {
            case DOUBLEBALL, GHOSTBALL, STANDARDBALL, EXPLOSIONBALL -> ammo.getAmmoMax(pl, ballType(it));
            case BLOCKSHELL -> ammo.getShellMax(pl);
            case BLOCKCOIN, COIN, POWER_USES -> Integer.MAX_VALUE;
            case POWER_CHARGES -> power == null ? 1 : power.cap(pl);
        };
    }

    private long liveFired(Player pl, Item it) {
        if (isBall(it)) {
            return getLong(pl, "shots_" + ballType(it));
        }
        if (it == Item.POWER_CHARGES || it == Item.POWER_USES) {
            return getLong(pl, SHOTS_POWER);
        }
        return 0L;
    }

    private String sk(UUID p, Item it, String field) {
        return p + "." + it.name().toLowerCase(Locale.ROOT) + "." + field;
    }

    public int value(UUID p, Item it) {
        Player pl = online(p);
        if (pl != null) {
            return liveValue(pl, it);
        }
        return snap.getInt(sk(p, it, "value"), 0);
    }

    public int max(UUID p, Item it) {
        Player pl = online(p);
        if (pl != null) {
            return liveMax(pl, it);
        }
        int def = switch (it) {
            case DOUBLEBALL, GHOSTBALL, STANDARDBALL, EXPLOSIONBALL -> plugin.getAmmo().getAmmoBaseMax(ballType(it));
            case BLOCKSHELL -> plugin.getAmmo().getShellMax();
            case POWER_CHARGES -> Math.max(1, plugin.getConfig().getInt("power-attack.max-charges", 1));
            default -> Integer.MAX_VALUE;
        };
        return snap.getInt(sk(p, it, "max"), def);
    }

    public long totalFired(UUID p, Item it) {
        Player pl = online(p);
        if (pl != null) {
            return liveFired(pl, it);
        }
        return snap.getLong(sk(p, it, "fired"), 0L);
    }

    // ---------- comandi ----------

    public Set<String> commands(Item it) {
        Set<String> s = new LinkedHashSet<>();
        switch (it) {
            case DOUBLEBALL, GHOSTBALL, STANDARDBALL, EXPLOSIONBALL, BLOCKSHELL, POWER_CHARGES -> {
                s.add("give"); s.add("remove"); s.add("set"); s.add("full"); s.add("reset");
            }
            case BLOCKCOIN, COIN -> {
                s.add("give"); s.add("remove"); s.add("set"); s.add("reset");
            }
            case POWER_USES -> {
                s.add("set"); s.add("reset");
            }
        }
        return s;
    }

    /** null = ok, altrimenti il messaggio d'errore. */
    public String apply(UUID p, Item it, String cmd, int n, CommandSender by) {
        if (it == null || cmd == null) {
            return "Comando non valido.";
        }
        cmd = cmd.toLowerCase(Locale.ROOT);
        if (!commands(it).contains(cmd)) {
            return "Il comando '" + cmd + "' non e' disponibile per " + it.name().toLowerCase(Locale.ROOT) + ".";
        }
        Player pl = online(p);
        if (pl == null) {
            return "Il giocatore e' offline: i contatori si possono solo leggere, modificali quando e' online.";
        }
        boolean needsNumber = cmd.equals("give") || cmd.equals("remove") || cmd.equals("set");
        if (needsNumber) {
            if (n < 0 || (!cmd.equals("set") && n == 0)) {
                return "Inserisci un numero valido" + (cmd.equals("set") ? " (0 o piu')." : " (maggiore di 0).");
            }
            if (it == Item.POWER_USES && n < 1) {
                return "Gli utilizzi per sblocco devono essere almeno 1.";
            }
        }
        AmmoManager ammo = plugin.getAmmo();
        Power power = plugin.getPower();
        int cur = liveValue(pl, it);
        int max = liveMax(pl, it);

        switch (it) {
            case DOUBLEBALL, GHOSTBALL, STANDARDBALL, EXPLOSIONBALL -> {
                String type = ballType(it);
                if (!ammo.exists(type)) {
                    return "Il tipo di palla '" + type + "' non esiste nel config.";
                }
                Integer next = target(cmd, cur, n, max);
                if (next == null) {
                    return "Il massimo per questo giocatore e' " + max + ".";
                }
                ammo.setAmmo(pl, type, next);
            }
            case BLOCKSHELL -> {
                Integer next = target(cmd, cur, n, max);
                if (next == null) {
                    return "Il massimo per questo giocatore e' " + max + ".";
                }
                ammo.setShell(pl, next);
            }
            case BLOCKCOIN -> ammo.setBlockCoinQuiet(pl, target(cmd, cur, n, Integer.MAX_VALUE));
            case COIN -> ammo.setCoinQuiet(pl, target(cmd, cur, n, Integer.MAX_VALUE));
            case POWER_CHARGES -> {
                if (power == null) {
                    return "Attacco potenziato non disponibile.";
                }
                if (cmd.equals("reset")) {
                    power.resetAll(pl);
                } else {
                    Integer next = target(cmd, cur, n, max);
                    if (next == null) {
                        return "Il massimo per questo giocatore e' " + max + ".";
                    }
                    power.setChargesTo(pl, next);
                }
            }
            case POWER_USES -> {
                if (power == null) {
                    return "Attacco potenziato non disponibile.";
                }
                if (cmd.equals("reset")) {
                    pl.getPersistentDataContainer().remove(key("power_uses"));
                } else {
                    power.setUses(pl, n);
                }
            }
        }
        snapshot(pl);
        if (plugin.getMovements() != null) {
            String what = it.name().toLowerCase(Locale.ROOT);
            String text = switch (cmd) {
                case "give" -> "Ha ricevuto " + n + " " + what;
                case "remove" -> "Gli sono stati tolti " + n + " " + what;
                case "set" -> "Ha " + what + " impostato a " + n;
                case "full" -> "Ha ricevuto " + what + " al massimo";
                default -> "Ha azzerato " + what;
            };
            plugin.getMovements().record(p, MovementLog.Type.ITEMS, text);
        }
        return null;
    }

    /**
     * Nuovo valore dopo il comando. give e' limitato al massimo; set oltre il massimo ritorna null (errore).
     * Per i tipi senza limite il risultato non supera Integer.MAX_VALUE.
     */
    private Integer target(String cmd, int cur, int n, int max) {
        return switch (cmd) {
            case "give" -> clamp(Math.min((long) max, (long) cur + n));
            case "remove" -> Math.max(0, cur - n);
            case "set" -> n > max ? null : n;
            case "full" -> max;
            default -> 0; // reset
        };
    }

    // ---------- totali sparati ----------

    /** Una palla consumata (chiamato da LauncherListener dopo il consumo). */
    public void recordShot(Player p, String ammoType, boolean power) {
        if (p == null) {
            return;
        }
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (ammoType != null) {
            NamespacedKey k = key("shots_" + ammoType.toLowerCase(Locale.ROOT));
            pdc.set(k, PersistentDataType.LONG, pdc.getOrDefault(k, PersistentDataType.LONG, 0L) + 1);
        }
        if (power) {
            NamespacedKey k = key(SHOTS_POWER);
            pdc.set(k, PersistentDataType.LONG, pdc.getOrDefault(k, PersistentDataType.LONG, 0L) + 1);
        }
    }

    /** Reset: dimentica lo snapshot del giocatore (si riscrive da solo al prossimo quit). */
    public void forget(UUID p) {
        snap.set(p.toString(), null);
        try {
            snap.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("counters.yml: " + e.getMessage());
        }
    }

    // ---------- snapshot per gli offline ----------

    private void snapshot(Player pl) {
        UUID id = pl.getUniqueId();
        for (Item it : Item.values()) {
            snap.set(sk(id, it, "value"), liveValue(pl, it));
            snap.set(sk(id, it, "max"), liveMax(pl, it));
            snap.set(sk(id, it, "fired"), liveFired(pl, it));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        snapshot(event.getPlayer());
        try {
            snap.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("counters.yml: " + e.getMessage());
        }
    }
}
