package it.blockmania;

/**
 * Glifi del resource pack per la pagina 2 dello staffprofile (generati da maniablock5/tools/genera_staffprofile2.py).
 * Tutti canvas 256x256, ascent 28, height 256.
 * real = avanzamento Minecraft = ultima colonna opaca + 2 (larghezza + 1 px di spaziatura), stessa convenzione di
 * StaffProfileGui.IMG_REAL (186). Dichiarato nel titolo normale = real - 7.
 * Disegno: normale g.at(-15 - 7, ch, font, real - 7); sfondo popup g.at(-15, ch, font, real).
 */
public final class StaffPage2Glyphs {

    private StaffPage2Glyphs() {}

    public record Glyph(String ch, String font, int real) {
        /** Avanzamento da usare nel titolo normale (reale - 7). */
        public int declared() { return real - 7; }
    }

    // ---- sfondo pagina 2 (staffprofile-p2.png: zona barra ruolo / pezzetti vuota, la disegnano i glifi sotto) ----
    public static final String BG = "";
    public static final String BG_FONT = "minecraft:staffprofile2";
    public static final int BG_REAL = 186;
    public static final Glyph BG_GLYPH = new Glyph(BG, BG_FONT, BG_REAL);

    // ---- barra ruolo staff: U+E150 + indice in StaffRoles.ORDER (x12..107, y90..105) ----
    public static final String ROLE_FONT = "minecraft:sp_ruolo";
    private static final int ROLE_REAL = 109;

    public static String roleChar(int orderIndex) {
        return String.valueOf((char) ('' + orderIndex));
    }

    public static int roleReal(int orderIndex) {
        return ROLE_REAL;
    }

    /** orderIndex = StaffRoles.ORDER.indexOf(id) (0..10). */
    public static Glyph role(int orderIndex) {
        if (orderIndex < 0 || orderIndex > 10) throw new IllegalArgumentException("ruolo staff fuori range: " + orderIndex);
        return new Glyph(roleChar(orderIndex), ROLE_FONT, ROLE_REAL);
    }

    // ---- pezzetti ruoli aggiuntivi (y108..123) ----
    public static final String SUB1_FONT = "minecraft:sp_sub1";   // intere + meta'
    public static final String SUB2_FONT = "minecraft:sp_sub2";   // terzi
    private static final int[] HALF_REAL = {61, 109};             // sx x12..59, dx x61..107
    private static final int[] THIRD_REAL = {45, 76, 109};        // x12..43, x45..74, x76..107

    private static int subIndex(String id) {
        if ("dev".equals(id)) return 0;
        if ("t_maker".equals(id)) return 1;
        if ("builder".equals(id)) return 2;
        throw new IllegalArgumentException("ruolo aggiuntivo sconosciuto: " + id);
    }

    /** Riga intera: id = "dev" | "t_maker" | "builder"; null = NESSUNO (grigia). */
    public static Glyph subFull(String idOrNull) {
        int i = idOrNull == null ? 3 : subIndex(idOrNull);
        return new Glyph(String.valueOf((char) ('' + i)), SUB1_FONT, 109);
    }

    /** Meta': pos 0 = sinistra, 1 = destra. */
    public static Glyph subHalf(String id, int pos) {
        if (pos < 0 || pos > 1) throw new IllegalArgumentException("pos meta': " + pos);
        return new Glyph(String.valueOf((char) ('' + subIndex(id) * 2 + pos)), SUB1_FONT, HALF_REAL[pos]);
    }

    /** Terzo: pos 0 = sinistra, 1 = centro, 2 = destra. */
    public static Glyph subThird(String id, int pos) {
        if (pos < 0 || pos > 2) throw new IllegalArgumentException("pos terzo: " + pos);
        return new Glyph(String.valueOf((char) ('' + subIndex(id) * 3 + pos)), SUB2_FONT, THIRD_REAL[pos]);
    }

    // ---- numero pagina (font pagnum, stessa posizione di pagnum-1..3 della userprofile) ----
    // stessa estensione (ultima colonna 103): avanzamento reale 105, come UserProfileGui.PAGNUM_REAL.
    public static final Glyph PAGE_1_2 = new Glyph("", "minecraft:pagnum", 105);
    public static final Glyph PAGE_2_2 = new Glyph("", "minecraft:pagnum", 105);
}
