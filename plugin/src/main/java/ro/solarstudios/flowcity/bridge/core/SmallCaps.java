package ro.solarstudios.flowcity.bridge.core;

/**
 * Text in "small caps" unicode (ᴀʙᴄᴅ...). Pastreaza intacte codurile de culoare (&x, §x, &#RRGGBB, &x&r&r..., {#RRGGBB}),
 * tag-urile {@code <...>} (gradient, #rrggbb, p), placeholderele (%...%, {...}), comenzile slash (/help, /atm deposit...),
 * cifrele si literele cu diacritice romanesti. Comutatorul global {@link #enabled} (cheia "smallcaps" din help.yml) il
 * foloseste {@link #title(String)}; {@link #convert(String)} converteste mereu.
 */
public final class SmallCaps {
    private SmallCaps() {}

    /** Setat din help.yml (smallcaps: true/false). */
    public static volatile boolean enabled = true;

    // ᴀ ʙ ᴄ ᴅ ᴇ ꜰ ɢ ʜ ɪ ᴊ ᴋ ʟ ᴍ ɴ ᴏ ᴘ ǫ ʀ ꜱ ᴛ ᴜ ᴠ ᴡ x ʏ ᴢ
    private static final String SMALL = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";

    /** Converteste doar daca {@link #enabled}. */
    public static String title(String s) {
        return enabled ? convert(s) : s;
    }

    public static String convert(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int i = 0;
        boolean cmdWord = false;
        while (i < n) {
            char c = s.charAt(i);
            // cod legacy: &x / §x (inclusiv &x&r&r... pentru hex) si &#RRGGBB
            if ((c == '&' || c == '§') && i + 1 < n) {
                char d = s.charAt(i + 1);
                if (d == '#' && i + 7 < n && isHex(s, i + 2, 6)) {
                    out.append(s, i, i + 8);
                    i += 8;
                    continue;
                }
                if ("0123456789abcdefklmnorxABCDEFKLMNORX".indexOf(d) >= 0) {
                    out.append(c).append(d);
                    i += 2;
                    continue;
                }
            }
            // {#RRGGBB}, {#}, {player}...: tot ce e intre acolade ramane
            if (c == '{') {
                int e = s.indexOf('}', i);
                if (e > i && e - i <= 40) {
                    out.append(s, i, e + 1);
                    i = e + 1;
                    continue;
                }
            }
            // %placeholder%
            if (c == '%') {
                int e = s.indexOf('%', i + 1);
                if (e > i + 1 && e - i <= 40 && s.substring(i + 1, e).indexOf(' ') < 0) {
                    out.append(s, i, e + 1);
                    i = e + 1;
                    continue;
                }
            }
            // <gradient:#..:#..>, <#rrggbb>, </gradient>, <p>
            if (c == '<') {
                int e = s.indexOf('>', i);
                if (e > i && e - i <= 80) {
                    out.append(s, i, e + 1);
                    i = e + 1;
                    continue;
                }
            }
            // comenzi slash: "/atm" ramane neschimbat pana la spatiu
            if (c == '/' && (i == 0 || Character.isWhitespace(s.charAt(i - 1)) || s.charAt(i - 1) == '§' || s.charAt(i - 1) == '>')) {
                cmdWord = true;
            }
            if (cmdWord) {
                if (Character.isWhitespace(c)) cmdWord = false;
                out.append(c);
                i++;
                continue;
            }
            int idx = c >= 'a' && c <= 'z' ? c - 'a' : c >= 'A' && c <= 'Z' ? c - 'A' : -1;
            out.append(idx >= 0 ? SMALL.charAt(idx) : c);
            i++;
        }
        return out.toString();
    }

    private static boolean isHex(String s, int from, int len) {
        if (from + len > s.length()) return false;
        for (int k = from; k < from + len; k++) {
            if (Character.digit(s.charAt(k), 16) < 0) return false;
        }
        return true;
    }
}
