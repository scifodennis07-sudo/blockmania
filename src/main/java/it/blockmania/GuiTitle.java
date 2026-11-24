package it.blockmania;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.configuration.ConfigurationSection;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Costruisce il titolo di una GUI: prima il testo/sfondo disegnato (con gli spostamenti {shift:N}),
 * poi altri pezzi (numeri delle pagine) messi in una posizione fissa, sempre uguale
 * qualunque sia la lunghezza del titolo. Tiene il conto di dove si trova il cursore.
 */
public final class GuiTitle {

    private static final Pattern SHIFT = Pattern.compile("\\{shift:(-?\\d+)\\}");

    private final BlockMania plugin;
    private Component result = Component.empty();
    private int cursor;

    public GuiTitle(BlockMania plugin) {
        this.plugin = plugin;
    }

    /** Dove si trova il cursore adesso (in pixel dall'inizio del titolo). */
    public int cursor() {
        return cursor;
    }

    static Component withFont(Component component, String font) {
        if (font == null || font.isBlank()) {
            return component;
        }
        try {
            return component.font(Key.key(font.trim()));
        } catch (Exception ex) {
            return component; // nome del font non valido: resta il font normale
        }
    }

    /**
     * Scrive il testo del titolo. "path" e' la sezione del config che contiene anche
     * "widths" (larghezza dei caratteri disegnati) e, se vuoi, "advance" (avanzamento fisso).
     */
    public GuiTitle text(String raw, String font, String path) {
        return text(raw, font, path, raw);
    }

    /** Come text(), ma l'avanzamento del cursore si misura su advanceRaw (testo con gli stessi pezzi di larghezza nota). */
    public GuiTitle text(String raw, String font, String path, String advanceRaw) {
        var c = plugin.getConfig();
        Matcher m = SHIFT.matcher(raw);
        StringBuilder shifted = new StringBuilder();
        while (m.find()) {
            String space = plugin.getBackground().shift(Integer.parseInt(m.group(1)));
            m.appendReplacement(shifted, Matcher.quoteReplacement(space));
        }
        m.appendTail(shifted);
        result = result.append(withFont(plugin.colorPlain(plugin.tiny(shifted.toString())), font));
        int manual = c.getInt(path + ".advance", 0);
        cursor += manual > 0 ? manual : advanceOf(advanceRaw, path + ".widths");
        return this;
    }

    /** Scrive "legacy" (con i codici colore) in modo che inizi a x pixel dall'inizio del titolo. */
    public GuiTitle at(int x, String legacy, String font) {
        int move = x - cursor;
        if (move != 0) {
            result = result.append(plugin.colorPlain("&f" + plugin.getBackground().shift(move)));
        }
        result = result.append(withFont(colorKeep(legacy, font), font));
        cursor = x + plugin.getBackground().measure(legacy);
        return this;
    }

    /** Come at(), ma dice tu di quanti pixel avanza il testo (per i caratteri che il plugin non sa misurare). */
    public GuiTitle at(int x, String legacy, String font, int advance) {
        int move = x - cursor;
        if (move != 0) {
            result = result.append(plugin.colorPlain("&f" + plugin.getBackground().shift(move)));
        }
        result = result.append(withFont(colorKeep(legacy, font), font));
        cursor = x + advance;
        return this;
    }

    /** Come at(), ma scrive un pezzo di testo gia' pronto (con il suo font e colore) e dice tu di quanti pixel avanza. */
    public GuiTitle atComponent(int x, Component component, int advance) {
        int move = x - cursor;
        if (move != 0) {
            result = result.append(plugin.colorPlain("&f" + plugin.getBackground().shift(move)));
        }
        result = result.append(component);
        cursor = x + advance;
        return this;
    }

    /**
     * plugin.colorPlain() toglie U+E000..U+E002 (segni dei tasti del font normale): nei font del pack (inv_full, task_btn...) quei
     * caratteri sono glifi veri, quindi li si protegge durante la conversione e poi si rimettono.
     */
    private Component colorKeep(String legacy, String font) {
        if (font == null || font.isBlank() || legacy == null) {
            return plugin.colorPlain(legacy);
        }
        boolean has = false;
        StringBuilder sb = new StringBuilder(legacy.length());
        for (char ch : legacy.toCharArray()) {
            if (ch >= '\uE000' && ch <= '\uE002') {
                sb.append((char) ('\uF8F0' + (ch - '\uE000')));
                has = true;
            } else {
                sb.append(ch);
            }
        }
        Component c = plugin.colorPlain(sb.toString());
        if (!has) {
            return c;
        }
        for (int i = 0; i < 3; i++) {
            final String from = String.valueOf((char) ('\uF8F0' + i));
            final String to = String.valueOf((char) ('\uE000' + i));
            c = c.replaceText(net.kyori.adventure.text.TextReplacementConfig.builder().matchLiteral(from).replacement(to).build());
        }
        return c;
    }

    public Component build() {
        return result;
    }

    // ---------- misure ----------

    /** Di quanti pixel avanza il cursore scrivendo questo testo (lettere, {shift:N} e caratteri disegnati). */
    public int advanceOf(String raw, String widthsPath) {
        Map<Character, Integer> widths = new HashMap<>();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection(widthsPath);
        if (section != null) {
            for (String key : section.getKeys(false)) {
                if (key.length() == 1) {
                    widths.put(key.charAt(0), section.getInt(key));
                }
            }
        }
        int total = 0;
        int last = 0;
        Matcher m = SHIFT.matcher(raw);
        while (m.find()) {
            total += pieceAdvance(raw.substring(last, m.start()), widths);
            total += Integer.parseInt(m.group(1));
            last = m.end();
        }
        return total + pieceAdvance(raw.substring(last), widths);
    }

    private int pieceAdvance(String piece, Map<Character, Integer> widths) {
        int total = 0;
        StringBuilder normal = new StringBuilder();
        for (char ch : piece.toCharArray()) {
            Integer w = widths.get(ch);
            if (w != null) {
                total += plugin.getBackground().measure(plugin.tiny(normal.toString()));
                normal.setLength(0);
                total += w;
            } else {
                normal.append(ch);
            }
        }
        return total + plugin.getBackground().measure(plugin.tiny(normal.toString()));
    }

    // ---------- numeri disegnati dal pack ----------

    /**
     * Un numero scritto con i caratteri del set "glyphs.<set>": le cifre 0-9 sono base+0 ... base+9,
     * la barra "/" e' base+10.
     */
    public static String glyphs(BlockMania plugin, String set, String text) {
        String base = plugin.getConfig().getString("glyphs." + set, "");
        if (base == null || base.isEmpty()) {
            return text;
        }
        char b = base.charAt(0);
        StringBuilder sb = new StringBuilder();
        for (char ch : text.toCharArray()) {
            if (ch >= '0' && ch <= '9') {
                sb.append((char) (b + (ch - '0')));
            } else if (ch == '/') {
                sb.append((char) (b + 10));
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** Colore dell'ombra (quello del testo scurito: in Minecraft e' un quarto della luminosita'). */
    public static String shadowCode(BlockMania plugin) {
        String hex = plugin.getConfig().getString("glyphs.shadow-color", "#3F3F3F").trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        return "&#" + hex;
    }

    /**
     * Scrive un numero con la sua ombra: prima l'ombra (un pixel a destra, con il set "shadowSet",
     * che e' gia' un pixel piu' in basso), poi il numero sopra.
     */
    public GuiTitle number(int x, String set, String shadowSet, boolean shadow, String text, String font) {
        if (shadow) {
            at(x + 1, shadowCode(plugin) + glyphs(plugin, shadowSet, text), font);
        }
        return at(x, "&f" + glyphs(plugin, set, text), font);
    }

    /** Larghezza (senza l'ultimo pixel vuoto) di un numero scritto con un set. */
    public static int widthOf(BlockMania plugin, String set, String text) {
        return Math.max(0, plugin.getBackground().measure(glyphs(plugin, set, text)) - 1);
    }
}
