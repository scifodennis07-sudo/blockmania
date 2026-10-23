package it.blockmania;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Ordina TUTTE le bossbar di un giocatore.
 *
 * - La principale ("main") sta sempre in cima e non si muove mai.
 * - Le altre sono ordinate per importanza (priorita' piu' alta = piu' in alto).
 * - Se una barra ha la barra di progresso disegnata sotto il testo ("under"), dopo di lei viene
 *   messa una bossbar vuota (spaziatore) cosi' la barra non finisce mai sopra la riga successiva.
 */
public final class BarStack {

    public static final String MAIN = "main";

    private static final class Entry {
        final String key;
        BossBar bar;
        int priority;
        boolean under;
        long order;

        Entry(String key) {
            this.key = key;
        }
    }

    private static final class State {
        final Map<String, Entry> entries = new LinkedHashMap<>();
        final List<BossBar> shown = new ArrayList<>();
        final List<BossBar> spacers = new ArrayList<>();
    }

    private final Map<UUID, State> states = new HashMap<>();
    private long counter = 0;

    private State state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), id -> new State());
    }

    /** Aggiunge o aggiorna una barra e rimette tutto in ordine. */
    public void set(Player player, String key, BossBar bar, int priority, boolean under) {
        State st = state(player);
        Entry e = st.entries.get(key);
        if (e == null) {
            e = new Entry(key);
            e.order = counter++;
            st.entries.put(key, e);
        }
        e.bar = bar;
        e.priority = priority;
        e.under = under;
        layout(player, st);
    }

    /** Cambia solo il flag "under" (barra di progresso sotto il testo) di una barra gia' presente. */
    public void setUnder(Player player, String key, boolean under) {
        State st = states.get(player.getUniqueId());
        if (st == null) {
            return;
        }
        Entry e = st.entries.get(key);
        if (e != null && e.under != under) {
            e.under = under;
            layout(player, st);
        }
    }

    public void remove(Player player, String key) {
        State st = states.get(player.getUniqueId());
        if (st == null || st.entries.remove(key) == null) {
            return;
        }
        layout(player, st);
    }

    public boolean has(Player player, String key) {
        State st = states.get(player.getUniqueId());
        return st != null && st.entries.containsKey(key);
    }

    public BossBar get(Player player, String key) {
        State st = states.get(player.getUniqueId());
        Entry e = st == null ? null : st.entries.get(key);
        return e == null ? null : e.bar;
    }

    /** Toglie tutte le barre al giocatore. */
    public void clear(Player player) {
        State st = states.remove(player.getUniqueId());
        if (st != null) {
            for (BossBar b : st.shown) {
                player.hideBossBar(b);
            }
        }
    }

    public void forget(UUID id) {
        states.remove(id);
    }

    private void layout(Player player, State st) {
        List<Entry> others = new ArrayList<>();
        Entry main = null;
        for (Entry e : st.entries.values()) {
            if (MAIN.equals(e.key)) {
                main = e;
            } else {
                others.add(e);
            }
        }
        others.sort(Comparator.<Entry>comparingInt(e -> -e.priority).thenComparingLong(e -> e.order));
        List<Entry> ordered = new ArrayList<>();
        if (main != null) {
            ordered.add(main);
        }
        ordered.addAll(others);

        List<BossBar> desired = new ArrayList<>();
        int spacerIndex = 0;
        for (int i = 0; i < ordered.size(); i++) {
            Entry e = ordered.get(i);
            desired.add(e.bar);
            if (e.under && i < ordered.size() - 1) {
                while (st.spacers.size() <= spacerIndex) {
                    st.spacers.add(BossBar.bossBar(Component.empty(), 0f, BossBar.Color.WHITE, BossBar.Overlay.PROGRESS));
                }
                desired.add(st.spacers.get(spacerIndex++));
            }
        }

        int first = 0;
        while (first < st.shown.size() && first < desired.size() && st.shown.get(first) == desired.get(first)) {
            first++;
        }
        for (int i = st.shown.size() - 1; i >= first; i--) {
            player.hideBossBar(st.shown.get(i));
        }
        for (int i = first; i < desired.size(); i++) {
            player.showBossBar(desired.get(i));
        }
        st.shown.clear();
        st.shown.addAll(desired);
    }
}
