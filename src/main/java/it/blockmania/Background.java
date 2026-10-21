package it.blockmania;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Map;
import java.util.TreeMap;

/**
 * Sfondo automatico dietro al testo (actionbar e bossbar).
 *
 * Come funziona: si scrive il bordo sinistro, tanti pezzi centrali quanti servono, il bordo destro,
 * poi si torna indietro con gli spazi negativi e si scrive il testo sopra. Alla fine si avanza di nuovo
 * con gli spazi positivi, cosi' il testo intero ha la stessa larghezza dello sfondo e resta centrato.
 * La larghezza del testo e' calcolata con le misure vere del font di Minecraft (vedi FontMetrics).
 */
public final class Background {

    private final BlockMania plugin;

    private boolean enabled;
    private boolean actionbar;
    private boolean bossbar;
    private int padding;
    private int adjust;
    private int unknownWidth;

    private String left;
    private String center;
    private String right;
    private int leftWidth;
    private int centerWidth;
    private int rightWidth;

    /** quantita' -> carattere, dal piu' grande al piu' piccolo. */
    private final TreeMap<Integer, String> negative = new TreeMap<>(java.util.Comparator.reverseOrder());
    private final TreeMap<Integer, String> positive = new TreeMap<>(java.util.Comparator.reverseOrder());

    public Background(BlockMania plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        FileConfiguration c = plugin.getConfig();
        enabled = c.getBoolean("background.enabled", true);
        actionbar = c.getBoolean("background.actionbar", true);
        bossbar = c.getBoolean("background.bossbar", true);
        padding = Math.max(0, c.getInt("background.padding", 0));
        adjust = c.getInt("background.adjust", 0);
        unknownWidth = Math.max(1, c.getInt("background.unknown-char-width", 6));

        left = c.getString("background.left.char", "㰀");
        center = c.getString("background.center.char", "㰁");
        right = c.getString("background.right.char", "㰂");
        leftWidth = Math.max(0, c.getInt("background.left.width", 6));
        centerWidth = Math.max(1, c.getInt("background.center.width", 1));
        rightWidth = Math.max(0, c.getInt("background.right.width", 6));

        readSpaces(c.getConfigurationSection("background.negative-spaces"), negative);
        readSpaces(c.getConfigurationSection("background.positive-spaces"), positive);
        if (negative.isEmpty() || positive.isEmpty()) {
            enabled = false;
            plugin.getLogger().warning("background: mancano negative-spaces o positive-spaces, sfondo disattivato.");
        }
    }

    private void readSpaces(ConfigurationSection section, TreeMap<Integer, String> target) {
        target.clear();
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                int amount = Integer.parseInt(key);
                String chr = section.getString(key, "");
                if (amount > 0 && !chr.isEmpty()) {
                    target.put(amount, chr);
                }
            } catch (NumberFormatException ignored) {
                // chiave non numerica: la salto
            }
        }
    }

    public boolean forActionbar() {
        return enabled && actionbar;
    }

    public boolean forBossbar() {
        return enabled && bossbar;
    }

    // ------------------------------------------------------------------
    //  Misura del testo
    // ------------------------------------------------------------------

    /** Larghezza in pixel di un testo con codici colore (&a, &#rrggbb, &l ...), spazio finale incluso. */
    public int measure(String legacy) {
        int total = 0;
        boolean bold = false;
        for (int i = 0; i < legacy.length(); i++) {
            char c = legacy.charAt(i);
            if (c == '&' && i + 1 < legacy.length()) {
                char n = legacy.charAt(i + 1);
                if (n == '#' && i + 7 < legacy.length() + 0 && isHex(legacy, i + 2, 6)) {
                    bold = false;
                    i += 7;
                    continue;
                }
                char low = Character.toLowerCase(n);
                if ((low >= '0' && low <= '9') || (low >= 'a' && low <= 'f') || low == 'r') {
                    bold = false;
                    i++;
                    continue;
                }
                if (low == 'l') {
                    bold = true;
                    i++;
                    continue;
                }
                if (low == 'k' || low == 'm' || low == 'n' || low == 'o') {
                    i++;
                    continue;
                }
            }
            int w = FontMetrics.width(c);
            if (w < 0) {
                w = unknownWidth;
            }
            total += w + (bold && w > 0 ? 1 : 0);
        }
        return total;
    }

    private static boolean isHex(String s, int from, int count) {
        if (from + count > s.length()) {
            return false;
        }
        for (int i = from; i < from + count; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  Costruzione
    // ------------------------------------------------------------------

    /** Scrive uno spostamento di "amount" pixel (positivo = avanti, negativo = indietro). */
    private String move(int amount) {
        if (amount == 0) {
            return "";
        }
        TreeMap<Integer, String> table = amount < 0 ? negative : positive;
        int remaining = Math.abs(amount);
        StringBuilder sb = new StringBuilder();
        while (remaining > 0) {
            boolean found = false;
            for (Map.Entry<Integer, String> e : table.entrySet()) {
                if (e.getKey() <= remaining) {
                    sb.append(e.getValue());
                    remaining -= e.getKey();
                    found = true;
                    break;
                }
            }
            if (!found) {
                break; // non esiste uno spazio cosi' piccolo
            }
        }
        return sb.toString();
    }

    /** Mette lo sfondo dietro al testo. Il testo resta com'e', con i suoi colori. */
    public String wrap(String legacy) {
        int textWidth = measure(legacy);
        if (textWidth <= 0) {
            return legacy;
        }
        // l'ultimo pixel dell'ultima lettera e' vuoto: il testo "visibile" e' 1 pixel in meno
        int visible = Math.max(1, textWidth - 1);
        int centerSpan = Math.max(0, visible + 2 * padding + adjust);
        int pieces = (centerSpan + centerWidth - 1) / centerWidth;

        int total = leftWidth + pieces * centerWidth + rightWidth + 1;
        int textStart = leftWidth + padding;

        StringBuilder sb = new StringBuilder("&f");
        sb.append(left).append(move(-1));
        String piece = center + move(-1);
        for (int i = 0; i < pieces; i++) {
            sb.append(piece);
        }
        sb.append(right);
        sb.append(move(textStart - total));
        sb.append(legacy);
        sb.append("&f").append(move(total - (textStart + textWidth)));
        return sb.toString();
    }
}
