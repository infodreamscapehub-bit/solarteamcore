package ro.solarstudios.flowcity.bridge.hype;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Text colorat de tip birdflop -> segmente {t, c, b} pentru HUD. Intelege: &#RRGGBB, &x&R&R&R&R&R&R (si cu §), {#RRGGBB}, &lt;#RRGGBB&gt;,
 * culorile clasice &0-&f, &l (bold), &r, si MiniMessage: &lt;gradient:#a:#b&gt;text&lt;/gradient&gt;, &lt;#rrggbb&gt;, &lt;bold&gt;.
 */
final class RankText {
    private RankText() {}

    private static final int[] LEGACY = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
        0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
    private static final Pattern GRAD = Pattern.compile("<gradient:([^>]+)>(.*?)</gradient>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final class Seg {
        String t;
        int c;
        boolean b;
    }

    /** Fara coduri de culoare (pentru clienti fara mod / fallback). */
    static String plain(String raw) {
        return flatten(raw).stream().map(x -> x.t).reduce("", String::concat).trim();
    }

    static JsonArray segments(String raw) {
        JsonArray a = new JsonArray();
        for (Seg s : flatten(raw)) {
            JsonObject o = new JsonObject();
            o.addProperty("t", s.t);
            o.addProperty("c", s.c);
            o.addProperty("b", s.b);
            a.add(o);
        }
        return a;
    }

    private static int hex(String h) {
        try {
            return Integer.parseInt(h, 16) & 0xFFFFFF;
        } catch (NumberFormatException e) {
            return 0xFFFFFF;
        }
    }

    private static int lerp(int a, int b, double t) {
        int r = (int) Math.round(((a >> 16) & 255) + (((b >> 16) & 255) - ((a >> 16) & 255)) * t);
        int g = (int) Math.round(((a >> 8) & 255) + (((b >> 8) & 255) - ((a >> 8) & 255)) * t);
        int bl = (int) Math.round((a & 255) + ((b & 255) - (a & 255)) * t);
        return (r << 16) | (g << 8) | bl;
    }

    /** Expandeaza <gradient:...>text</gradient> in culori pe litera (&#RRGGBBx) ca sa ramana un singur parser. */
    private static String expandGradients(String s) {
        Matcher m = GRAD.matcher(s);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            List<Integer> stops = new ArrayList<>();
            for (String p : m.group(1).split(":")) {
                p = p.trim().replace("#", "");
                if (p.length() == 6) stops.add(hex(p));
            }
            String text = m.group(2).replaceAll("<[^>]*>", "");
            if (stops.size() < 2) {
                out.append(text);
            } else {
                int n = text.length();
                for (int i = 0; i < n; i++) {
                    double t = n == 1 ? 0 : (double) i / (n - 1);
                    double pos = t * (stops.size() - 1);
                    int k = Math.min(stops.size() - 2, (int) Math.floor(pos));
                    int col = lerp(stops.get(k), stops.get(k + 1), pos - k);
                    out.append(String.format("&#%06x", col)).append(text.charAt(i));
                }
            }
            last = m.end();
        }
        out.append(s.substring(last));
        return out.toString();
    }

    private static List<Seg> flatten(String raw) {
        List<Seg> out = new ArrayList<>();
        if (raw == null) return out;
        String s = expandGradients(raw.replace('§', '&'));
        s = s.replaceAll("(?i)&x((?:&[0-9a-f]){6})", "&#$1").replaceAll("(?i)(&#)((?:&[0-9a-f]){6})", "$1$2");
        // &x&r&r&r&r&r&r -> &#rrggbb
        Matcher x = Pattern.compile("(?i)&#((?:&[0-9a-f]){6})").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (x.find()) x.appendReplacement(sb, "&#" + x.group(1).replace("&", ""));
        x.appendTail(sb);
        s = sb.toString();
        s = s.replaceAll("(?i)\\{#([0-9a-f]{6})\\}", "&#$1").replaceAll("(?i)<#([0-9a-f]{6})>", "&#$1");
        s = s.replaceAll("(?i)<(/?)(bold|b)>", "&l").replaceAll("<[^>]*>", "");
        int color = 0xFFFFFF;
        boolean bold = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '&' && i + 1 < s.length()) {
                char n = Character.toLowerCase(s.charAt(i + 1));
                int adv = 0;
                int ncol = color;
                boolean nb = bold;
                if (n == '#' && i + 7 < s.length() + 0 && i + 8 <= s.length() && s.substring(i + 2, i + 8).matches("(?i)[0-9a-f]{6}")) {
                    ncol = hex(s.substring(i + 2, i + 8));
                    nb = false;
                    adv = 8;
                } else if (Character.digit(n, 16) >= 0) {
                    ncol = LEGACY[Character.digit(n, 16)];
                    nb = false;
                    adv = 2;
                } else if (n == 'l') {
                    nb = true;
                    adv = 2;
                } else if (n == 'r') {
                    ncol = 0xFFFFFF;
                    nb = false;
                    adv = 2;
                } else if ("kmno".indexOf(n) >= 0) {
                    adv = 2;
                }
                if (adv > 0) {
                    if (cur.length() > 0) {
                        Seg g = new Seg();
                        g.t = cur.toString();
                        g.c = color;
                        g.b = bold;
                        out.add(g);
                        cur.setLength(0);
                    }
                    color = ncol;
                    bold = nb;
                    i += adv - 1;
                    continue;
                }
            }
            cur.append(ch);
        }
        if (cur.length() > 0) {
            Seg g = new Seg();
            g.t = cur.toString();
            g.c = color;
            g.b = bold;
            out.add(g);
        }
        // uneste segmentele vecine identice (gradient pe litera = multe segmente; pastram, dar scoatem goale)
        return out;
    }
}
