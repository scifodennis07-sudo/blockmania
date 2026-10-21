package it.blockmania;

/**
 * Trasforma le lettere in "tiny text" (piccole maiuscole), lasciando intatti numeri,
 * simboli, codici colore (&a, &#ff0000) e segnaposto (%nome%).
 */
public final class Tiny {

    private static final String FROM = "abcdefghijklmnopqrstuvwxyz";
    // \u1d00 \u0299 \u1d04 \u1d05 \u1d07 \ua730 \u0262 \u029c \u026a \u1d0a \u1d0b \u029f \u1d0d \u0274 \u1d0f \u1d18 \u01eb \u0280 \ua731 \u1d1b \u1d1c \u1d20 \u1d21 x \u028f \u1d22
    private static final String TO =
            "\u1d00\u0299\u1d04\u1d05\u1d07\ua730\u0262\u029c\u026a\u1d0a\u1d0b\u029f\u1d0d"
                    + "\u0274\u1d0f\u1d18\u01eb\u0280\ua731\u1d1b\u1d1c\u1d20\u1d21x\u028f\u1d22";

    private static final String COLOR_CODES = "0123456789abcdefklmnorABCDEFKLMNOR";

    private Tiny() {
    }

    private static boolean isHex(String s) {
        if (s.length() != 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isPlaceholder(String s) {
        if (s.isEmpty() || s.length() > 24) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || c == '_')) {
                return false;
            }
        }
        return true;
    }

    public static String convert(String input) {
        StringBuilder out = new StringBuilder(input.length());
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);

            if (c == '&' && i + 1 < input.length()) {
                char next = input.charAt(i + 1);
                if (next == '#' && i + 8 <= input.length() && isHex(input.substring(i + 2, i + 8))) {
                    out.append(input, i, i + 8);
                    i += 8;
                    continue;
                }
                if (COLOR_CODES.indexOf(next) >= 0) {
                    out.append(c).append(next);
                    i += 2;
                    continue;
                }
            }

            if (c == '%') {
                int end = input.indexOf('%', i + 1);
                if (end > i + 1 && isPlaceholder(input.substring(i + 1, end))) {
                    out.append(input, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }

            char lower = Character.toLowerCase(c);
            if (lower >= 'a' && lower <= 'z') {
                out.append(TO.charAt(lower - 'a'));
            } else {
                out.append(c);
            }
            i++;
        }
        return out.toString();
    }
}
