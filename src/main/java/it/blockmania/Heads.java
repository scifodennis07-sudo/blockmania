package it.blockmania;

import com.destroystokyo.paper.profile.PlayerProfile;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Teste 2D come item (PLAYER_HEAD + item_model blockmania:head_2d / head_2d_big del pack, vedi tools/README_teste2d.txt):
 * il client disegna la faccia dalla skin del profilo, niente pixel nel titolo e niente immagini scaricate dal server.
 * <p>
 * Se il profilo locale non ha le texture (player offline mai visto in questa sessione) si chiede UNA volta per uuid
 * profile.update() asincrono; il profilo completo resta in cache e i callback (whenReady) ridisegnano le GUI aperte.
 * Tutto il resto solo sul main thread.
 */
public final class Heads {

    private static final String NS = "blockmania";
    private static final NamespacedKey MODEL_SMALL = new NamespacedKey(NS, "head_2d");
    private static final NamespacedKey MODEL_BIG = new NamespacedKey(NS, "head_2d_big");
    private static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_]{1,16}");
    private static final int CACHE_MAX = 512;
    /** Dopo un update() fallito si riprova al massimo ogni 10 minuti. */
    private static final long RETRY_MS = 10L * 60L * 1000L;

    /** Profili completi (con texture) dei player offline, LRU. Solo main thread. */
    private static final Map<UUID, PlayerProfile> CACHE = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<UUID, PlayerProfile> e) {
            return size() > CACHE_MAX;
        }
    };
    /** update() in corso. */
    private static final Set<UUID> IN_FLIGHT = new HashSet<>();
    /** uuid -> momento dell'ultimo update() fallito (o senza texture). */
    private static final Map<UUID, Long> FAILED = new HashMap<>();
    private static final Map<UUID, List<Runnable>> WAITERS = new HashMap<>();

    private Heads() {
    }

    /** Testa 2D: big = faccia 32x32 del riquadro dei profili (nello slot in alto a sinistra), altrimenti 16x16. Nome con i codici &. */
    public static ItemStack head2d(UUID id, boolean big, String name, List<Component> lore) {
        return head2d(id, big, name == null ? null : plugin().text(name), lore);
    }

    public static ItemStack head2d(UUID id, boolean big, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta im = stack.getItemMeta();
        if (im instanceof SkullMeta meta) {
            PlayerProfile prof = id == null ? null : profileOf(id);
            if (prof != null) {
                try {
                    meta.setPlayerProfile(prof);
                } catch (Throwable ignored) {
                    // profilo non valido: resta la testa di default
                }
            }
            meta.setItemModel(big ? MODEL_BIG : MODEL_SMALL);
            if (name != null) {
                meta.displayName(name);
            }
            if (lore != null && !lore.isEmpty()) {
                meta.lore(lore);
            }
            LoreWrap.fix(meta);   // lore a capo, nome non troppo lungo
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /** Come head2d ma con nome e lore copiati da un item gia' fatto (es. le voci delle liste). */
    public static ItemStack head2dLike(UUID id, boolean big, ItemStack src) {
        ItemMeta sm = src == null ? null : src.getItemMeta();
        return head2d(id, big, sm == null ? null : sm.displayName(), sm == null ? null : sm.lore());
    }

    /**
     * Se la skin di id non e' ancora nota avvia (una volta) il caricamento e a caricamento finito esegue onReady sul main
     * thread; se e' gia' nota (o id null) non fa nulla. Per ridisegnare le GUI aperte.
     */
    public static void whenReady(UUID id, Runnable onReady) {
        if (id == null || ready(id)) {
            return;
        }
        if (request(id, Bukkit.createProfile(id, validName(id))) && onReady != null) {
            WAITERS.computeIfAbsent(id, k -> new ArrayList<>()).add(onReady);
        }
    }

    /** True se il profilo di id ha gia' le texture (player online o profilo in cache). */
    public static boolean ready(UUID id) {
        if (id == null) {
            return true;
        }
        if (CACHE.containsKey(id)) {
            return true;
        }
        Player online = Bukkit.getPlayer(id);
        return online != null && online.getPlayerProfile().hasTextures();
    }

    // ------------------------------------------------------------------

    private static PlayerProfile profileOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            PlayerProfile p = online.getPlayerProfile();
            if (p.hasTextures()) {
                CACHE.put(id, p);   // skin aggiornata: vale anche quando uscira'
                return p;
            }
        }
        PlayerProfile cached = CACHE.get(id);
        if (cached != null) {
            return cached;
        }
        PlayerProfile p;
        try {
            p = Bukkit.createProfile(id, validName(id));
        } catch (Throwable t) {
            return null;
        }
        // senza texture il client la risolve da solo; intanto la chiediamo una volta per averla completa in cache
        request(id, p);
        return p;
    }

    /** Avvia update() se non e' gia' in corso e non e' fallito da poco. True se c'e' un caricamento in corso. */
    private static boolean request(UUID id, PlayerProfile p) {
        if (IN_FLIGHT.contains(id)) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = FAILED.get(id);
        if (last != null && now - last < RETRY_MS) {
            return false;
        }
        BlockMania plugin = plugin();
        try {
            IN_FLIGHT.add(id);
            p.update().whenComplete((updated, err) -> {
                if (!plugin.isEnabled()) {
                    return;
                }
                Bukkit.getScheduler().runTask(plugin, () -> {
                    loaded(id, err == null ? updated : null);
                });
            });
            return true;
        } catch (Throwable t) {
            IN_FLIGHT.remove(id);
            FAILED.put(id, now);
            return false;
        }
    }

    private static void loaded(UUID id, org.bukkit.profile.PlayerProfile updated) {
        IN_FLIGHT.remove(id);
        if (!(updated instanceof PlayerProfile full) || !full.hasTextures()) {
            FAILED.put(id, System.currentTimeMillis());   // niente skin: si riprova dopo RETRY_MS
            WAITERS.remove(id);
            return;
        }
        FAILED.remove(id);
        CACHE.put(id, full);
        List<Runnable> waiting = WAITERS.remove(id);
        if (waiting == null) {
            return;
        }
        for (Runnable r : waiting) {
            try {
                r.run();
            } catch (Throwable ignored) {
                // un callback difettoso non blocca gli altri
            }
        }
    }

    /** Nome dalla cache del server (nessun lookup di rete), solo se valido per un profilo; altrimenti null. */
    private static String validName(UUID id) {
        try {
            OfflinePlayer op = Bukkit.getOfflinePlayer(id);
            String n = op.getName();
            return n != null && VALID_NAME.matcher(n).matches() ? n : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static BlockMania plugin() {
        return JavaPlugin.getPlugin(BlockMania.class);
    }
}
