package it.blockmania;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Stamina (bossbar gialla sotto quella principale) e attacco potenziato.
 *
 * - La stamina e' sempre piena, tranne quando la consuma l'attacco potenziato: da 0 ci vogliono
 *   power-attack.regen-seconds secondi (default 180 = 3 minuti) per tornare piena.
 * - Ogni colpo di palla a un blocco (anche ai rimbalzi) conta: dopo un numero a caso di colpi tra
 *   unlock-hits-min e unlock-hits-max si sblocca un attacco potenziato (al massimo max-charges).
 * - Chi ha le limitazioni tolte (/bm limits off) puo' usarlo sempre, senza consumare niente.
 */
public final class Power implements Listener {

    private final BlockMania plugin;
    private final Map<UUID, BossBar> powerBars = new HashMap<>();
    private final NamespacedKey fullAtKey;
    private final NamespacedKey progressKey;
    private final NamespacedKey chargesKey;
    private final NamespacedKey targetKey;
    private final NamespacedKey noLimitsKey;
    private final NamespacedKey modeKey;
    private final NamespacedKey usesKey;
    private BukkitTask task;

    public Power(BlockMania plugin) {
        this.plugin = plugin;
        this.fullAtKey = new NamespacedKey(plugin, "stamina_full_at");
        this.progressKey = new NamespacedKey(plugin, "power_progress");
        this.chargesKey = new NamespacedKey(plugin, "power_charges");
        this.targetKey = new NamespacedKey(plugin, "power_target");
        this.noLimitsKey = new NamespacedKey(plugin, "limits_off");
        this.modeKey = new NamespacedKey(plugin, "stamina_mode");
        this.usesKey = new NamespacedKey(plugin, "power_uses");
    }

    public void start() {
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 5L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            hideBars(p);
        }
    }

    // ---------- limitazioni ----------

    /** Vero se il giocatore ha le limitazioni normali (default). Falso = staff che prova tutto. */
    public boolean isLimited(Player player) {
        return !player.getPersistentDataContainer().has(noLimitsKey, PersistentDataType.BYTE);
    }

    public void setLimited(Player player, boolean limited) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (limited) {
            pdc.remove(noLimitsKey);
        } else {
            pdc.set(noLimitsKey, PersistentDataType.BYTE, (byte) 1);
        }
    }

    // ---------- stamina ----------

    private long regenMillis() {
        return Math.max(1000L, (long) (plugin.getConfig().getDouble("power-attack.regen-seconds", 180) * 1000));
    }

    /** Modalita' della stamina: normal, infinite (sempre piena) o empty (sempre vuota). */
    public String mode(Player player) {
        String m = player.getPersistentDataContainer().get(modeKey, PersistentDataType.STRING);
        return m == null ? "normal" : m;
    }

    public void setMode(Player player, String mode) {
        if (mode.equals("normal")) {
            player.getPersistentDataContainer().remove(modeKey);
        } else {
            player.getPersistentDataContainer().set(modeKey, PersistentDataType.STRING, mode);
        }
    }

    /** Mette la stamina a una percentuale (0-100) e la fa ricaricare da li'. */
    public void setPercent(Player player, double percent) {
        double level = Math.max(0.0, Math.min(1.0, percent / 100.0));
        setMode(player, "normal");
        long fullAt = System.currentTimeMillis() + (long) ((1.0 - level) * regenMillis());
        if (level >= 1.0) {
            player.getPersistentDataContainer().remove(fullAtKey);
        } else {
            player.getPersistentDataContainer().set(fullAtKey, PersistentDataType.LONG, fullAt);
        }
    }

    /** Vero se la stamina va mostrata sotto la bossbar principale. */
    public boolean staminaVisible(Player player) {
        var c = plugin.getConfig();
        return c.getBoolean("stamina.enabled", true) && c.getBoolean("power-attack.enabled", true);
    }

    /** Stamina da 0 a 1. */
    public double stamina(Player player) {
        String mode = mode(player);
        if (mode.equals("infinite")) {
            return 1.0;
        }
        if (mode.equals("empty")) {
            return 0.0;
        }
        Long fullAt = player.getPersistentDataContainer().get(fullAtKey, PersistentDataType.LONG);
        if (fullAt == null) {
            return 1.0;
        }
        long left = fullAt - System.currentTimeMillis();
        if (left <= 0) {
            return 1.0;
        }
        return Math.max(0.0, Math.min(1.0, 1.0 - left / (double) regenMillis()));
    }

    /** Toglie una parte di stamina (0.25 = un quarto). Per i casi particolari che aggiungeremo. */
    public void drain(Player player, double fraction) {
        if (!mode(player).equals("normal")) {
            return;
        }
        double level = Math.max(0.0, stamina(player) - Math.max(0.0, fraction));
        long fullAt = System.currentTimeMillis() + (long) ((1.0 - level) * regenMillis());
        player.getPersistentDataContainer().set(fullAtKey, PersistentDataType.LONG, fullAt);
    }

    /** Rimette la stamina al massimo subito. */
    public void refill(Player player) {
        setMode(player, "normal");
        player.getPersistentDataContainer().remove(fullAtKey);
    }

    // ---------- sblocco dell'attacco potenziato ----------

    public int charges(Player player) {
        return player.getPersistentDataContainer().getOrDefault(chargesKey, PersistentDataType.INTEGER, 0);
    }

    private void setCharges(Player player, int value) {
        player.getPersistentDataContainer().set(chargesKey, PersistentDataType.INTEGER, Math.max(0, value));
    }

    /** Quanti attacchi potenziati regala ogni sblocco (di default 1, si cambia con /bm power uses). */
    public int uses(Player player) {
        int def = Math.max(1, plugin.getConfig().getInt("power-attack.uses-per-unlock", 1));
        return Math.max(1, player.getPersistentDataContainer().getOrDefault(usesKey, PersistentDataType.INTEGER, def));
    }

    public void setUses(Player player, int value) {
        player.getPersistentDataContainer().set(usesKey, PersistentDataType.INTEGER, Math.max(1, value));
    }

    /** Quanti attacchi si possono tenere al massimo. */
    public int cap(Player player) {
        return Math.max(Math.max(1, plugin.getConfig().getInt("power-attack.max-charges", 1)), uses(player));
    }

    public void setChargesTo(Player player, int value) {
        setCharges(player, value);
    }

    public int progress(Player player) {
        return player.getPersistentDataContainer().getOrDefault(progressKey, PersistentDataType.INTEGER, 0);
    }

    /** Quanti colpi servono per lo sblocco successivo (a caso tra unlock-hits-min e unlock-hits-max). */
    public int target(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        int target = pdc.getOrDefault(targetKey, PersistentDataType.INTEGER, 0);
        if (target <= 0) {
            var c = plugin.getConfig();
            int min = Math.max(1, c.getInt("power-attack.unlock-hits-min", 500));
            int max = Math.max(min, c.getInt("power-attack.unlock-hits-max", 3000));
            target = java.util.concurrent.ThreadLocalRandom.current().nextInt(min, max + 1);
            pdc.set(targetKey, PersistentDataType.INTEGER, target);
        }
        return target;
    }

    /** Un blocco colpito da una palla di questo giocatore (anche ai rimbalzi): avvicina lo sblocco. */
    public void onHit(Player player) {
        var c = plugin.getConfig();
        if (!c.getBoolean("power-attack.enabled", true)) {
            return;
        }
        int max = cap(player);
        if (charges(player) >= max) {
            return; // ne ha gia uno pronto: finche' non lo usa non si conta
        }
        int progress = progress(player) + 1;
        if (progress >= target(player)) {
            player.getPersistentDataContainer().remove(targetKey); // il prossimo obiettivo e' un nuovo numero a caso
            player.getPersistentDataContainer().set(progressKey, PersistentDataType.INTEGER, 0);
            giveCharge(player);
            return;
        }
        player.getPersistentDataContainer().set(progressKey, PersistentDataType.INTEGER, progress);
    }

    /** Regala un attacco potenziato (con messaggio e suono). Serve anche per i test. */
    public void giveCharge(Player player) {
        setCharges(player, Math.min(cap(player), charges(player) + uses(player)));
        plugin.send(player, "power-unlocked");
        plugin.playConfiguredSound(player.getLocation(), "power-attack.sounds.unlock");
    }

    /** Azzera attacchi sbloccati e conteggio, e rimette la stamina piena. */
    public void resetAll(Player player) {
        setCharges(player, 0);
        player.getPersistentDataContainer().remove(progressKey);
        player.getPersistentDataContainer().remove(targetKey);
        refill(player);
    }

    /** Vero se l'attacco potenziato si puo' usare adesso (senza messaggi e senza consumare). */
    public boolean canUse(Player player) {
        if (!plugin.getConfig().getBoolean("power-attack.enabled", true)) {
            return false;
        }
        return charges(player) > 0 && stamina(player) >= 0.999;
    }

    /**
     * Prova a usare l'attacco potenziato: controlla lo sblocco e la stamina e li consuma.
     * Ritorna vero se l'attacco puo' partire.
     */
    public boolean tryUse(Player player) {
        if (!plugin.getConfig().getBoolean("power-attack.enabled", true)) {
            return false;
        }
        if (charges(player) <= 0) {
            plugin.send(player, "power-locked");
            return false;
        }
        if (stamina(player) < 0.999) {
            plugin.send(player, "power-no-stamina");
            return false;
        }
        setCharges(player, charges(player) - 1);
        drain(player, 1.0); // consuma tutta la stamina (con /bm stamina infinite no)
        return true;
    }

    // ---------- bossbar ----------

    private BossBar.Color color(String path, BossBar.Color fallback) {
        try {
            return BossBar.Color.valueOf(plugin.getConfig().getString(path, fallback.name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return fallback;
        }
    }

    private void tick() {
        var c = plugin.getConfig();
        boolean powerOn = c.getBoolean("power-attack.enabled", true);
        BarStack stack = plugin.getBars();
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID id = player.getUniqueId();
            double level = stamina(player);
            boolean mainReady = plugin.getAmmo().hasBossBar(player) || !c.getBoolean("bossbar.enabled", true);
            BossBar pbar = powerBars.get(id);
            boolean show = powerOn && mainReady && charges(player) > 0;
            if (!show) {
                if (pbar != null) {
                    powerBars.remove(id);
                    stack.remove(player, "power");
                }
                continue;
            }
            String key = level >= 0.999 ? "power-attack.bossbar.ready" : "power-attack.bossbar.waiting";
            String text = c.getString(key, level >= 0.999
                    ? "&dattacco potenziato sbloccato &8| &fpremi &eshift + click destro &fper usarlo"
                    : "&dattacco potenziato sbloccato &8| &7aspetta la stamina");
            Component name = plugin.bossSegments(List.of(text), -1);
            if (pbar == null) {
                pbar = BossBar.bossBar(name, 1f, color("power-attack.bossbar.color", BossBar.Color.PURPLE),
                        BossBar.Overlay.PROGRESS);
                powerBars.put(id, pbar);
                stack.set(player, "power", pbar, 50, false);
            } else {
                pbar.name(name);
            }
        }
    }

    private void hideBars(Player player) {
        powerBars.remove(player.getUniqueId());
        plugin.getBars().remove(player, "power");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        powerBars.remove(id);
        plugin.getBars().forget(id);
        if (plugin.getMineTemplates() != null) {
            plugin.getMineTemplates().forget(id);
        }
    }
}
