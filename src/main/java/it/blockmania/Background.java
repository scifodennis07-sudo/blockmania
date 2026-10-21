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

        left = c.getString("background.left.char", "\u3c00");
        center = c.getString("background.center.char", "\u3c01");
        right = c.getString("background.right.char", "\u3c02");
        leftWidth = Math.max(0, c.getInt("background.left.width", 6));
        centerWidth = Math.max(1, c.getInt("background.center.width", 1));
        rightWidth = Math.max(0, c.getInt("background.right.width", 6));

        readSpaces(c.getConfigurationSection("background.negative-spaces"), negative,
                new int[]{1, 2, 3, 4, 5, 6, 16, 32},
                new char[]{'\u2f1e', '\u3400', '\u3401', '\u3402', '\u3403', '\u3404', '\u4977', '\u4978'});
        readSpaces(c.getConfigurationSection("background.positive-spaces"), positive,
                new int[]{1, 2, 4, 8, 16, 32},
                new char[]{'\u3410', '\u3411', '\u3412', '\u3413', '\u3414', '\u3415'});
        plugin.getLogger().info("Sfondo: spazi negativi=" + negative.size() + ", positivi=" + positive.size()
                + ", attivo=" + enabled);
    }

    /**
     * Legge gli spazi dal config. Se il config non si legge (o e' vuoto) usa quelli standard
     * del resource pack, cosi' lo sfondo non si rompe mai.
     */
    private void readSpaces(ConfigurationSection section, TreeMap<Integer, String> target,
                            int[] defaultAmounts, char[] defaultChars) {
        target.clear();
        if (section != null) {
            for (Map.Entry<String, Object> e : section.getValues(false).entrySet()) {
                try {
                    int amount = Integer.parseInt(e.getKey().trim());
                    String chr = e.getValue() == null ? "" : String.valueOf(e.getValue());
                    if (amount > 0 && !chr.isEmpty()) {
                        target.put(amount, chr);
                    }
                } catch (NumberFormatException ignored) {
                    // chiave non numerica: la salto
                }
            }
        }
        if (target.isEmpty()) {
            plugin.getLogger().warning("background: spazi non letti dal config, uso quelli standard.");
            for (int i = 0; i < defaultAmounts.length; i++) {
                target.put(defaultAmounts[i], String.valueOf(defaultChars[i]));
            }
        }
    }

    /** Riepilogo dei caratteri letti (in esadecimale), per controllare che combacino con il pack. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("sx=").append(hex(left)).append(" centro=").append(hex(center)).append(" dx=").append(hex(right));
        sb.append(" | indietro: ");
        for (Map.Entry<Integer, String> e : negative.entrySet()) {
            sb.append(e.getKey()).append("=").append(hex(e.getValue())).append(" ");
        }
        sb.append("| avanti: ");
        for (Map.Entry<Integer, String> e : positive.entrySet()) {
            sb.append(e.getKey()).append("=").append(hex(e.getValue())).append(" ");
        }
        return sb.toString().trim();
    }

    private static String hex(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(String.format("%04x", (int) c));
        }
        return sb.toString();
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

    /** Mette lo sfondo dietro al testo (versione in una sola stringa, senza togliere l'ombra). */
    public String wrap(String legacy) {
        String[] parts = parts(legacy);
        return parts[0] + legacy + parts[1];
    }

    public boolean removeShadow() {
        return plugin.getConfig().getBoolean("background.remove-shadow", true);
    }

    /**
     * Ritorna {prima, dopo}: la parte che va PRIMA del testo (sfondo + ritorno indietro)
     * e quella che va DOPO (avanzamento). Il testo in mezzo resta com'e'.
     */
    public String[] parts(String legacy) {
        if (negative.isEmpty() || positive.isEmpty()) {
            return new String[]{"", ""};
        }
        int textWidth = measure(legacy);
        if (textWidth <= 0) {
            return new String[]{"", ""};
        }
        // l'ultimo pixel dell'ultima lettera e' vuoto: il testo "visibile" e' 1 pixel in meno
        int visible = Math.max(1, textWidth - 1);
        int centerSpan = Math.max(0, visible + 2 * padding + adjust);
        int pieces = (centerSpan + centerWidth - 1) / centerWidth;

        int total = leftWidth + pieces * centerWidth + rightWidth + 1;
        int textStart = leftWidth + padding;

        StringBuilder before = new StringBuilder("&f");
        before.append(left).append(move(-1));
        String piece = center + move(-1);
        for (int i = 0; i < pieces; i++) {
            before.append(piece);
        }
        before.append(right);
        before.append(move(textStart - total));

        String after = "&f" + move(total - (textStart + textWidth));
        return new String[]{before.toString(), after};
    }
}
