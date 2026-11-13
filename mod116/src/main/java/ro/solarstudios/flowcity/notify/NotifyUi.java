package ro.solarstudios.flowcity.notify;

import java.util.ArrayList;
import java.util.List;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/** Elemente comune pentru toast-uri si istoric: iconite desenate din cod (nu litere) si impartirea textului. */
public final class NotifyUi {
    private NotifyUi() {}

    public static String clip(String s, float size, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, false) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, false) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    /** Imparte textul pe randuri de cel mult maxW pixeli; ultimul rand se termina cu "..." daca textul nu incape. */
    public static List<String> wrap(String text, float size, float maxW, int maxLines) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        String[] words = text.replace('\n', ' ').trim().split("\\s+");
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            String tryLine = cur.length() == 0 ? w : cur + " " + w;
            if (Gfx.width(tryLine, size, false) <= maxW) {
                cur = new StringBuilder(tryLine);
            } else {
                if (cur.length() > 0) out.add(cur.toString());
                cur = new StringBuilder(Gfx.width(w, size, false) <= maxW ? w : clip(w, size, maxW));
            }
        }
        if (cur.length() > 0) out.add(cur.toString());
        if (out.size() > maxLines) {
            List<String> cut = new ArrayList<>(out.subList(0, maxLines));
            cut.set(maxLines - 1, clip(cut.get(maxLines - 1) + "...", size, maxW));
            return cut;
        }
        return out;
    }

    private static int luma(int c) {
        return (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
    }

    /** Insigna rotunda cu pictograma tipului (OK, WARN, BAD, MONEY, JOB, NEWS, INFO). r = raza insignei. */
    public static void icon(GuiGraphics g, String type, float cx, float cy, float r, int color, float alpha) {
        Gfx.disc(g, cx, cy, r + 5, Gfx.fade(color & 0x00FFFFFF | 0x40000000, alpha), color & 0x00FFFFFF, 0, 0);
        Gfx.disc(g, cx, cy, r, Gfx.fade(Gfx.mixArgb(color | 0xFF000000, 0xFFFFFFFF, 0.25f), alpha),
            Gfx.fade(Gfx.mixArgb(color | 0xFF000000, 0xFF000000, 0.28f), alpha), -r * 0.22f, -r * 0.3f);
        int gl = luma(color) > 150 ? 0xFF14161A : 0xFFFFFFFF;
        gl = Gfx.fade(gl, alpha);
        float k = r / 12f;
        switch (type) {
            case "OK":
                Gfx.line(g, cx - 4.5f * k, cy + 0.5f * k, cx - 1.5f * k, cy + 4f * k, 2.3f * k, gl);
                Gfx.line(g, cx - 1.5f * k, cy + 4f * k, cx + 5f * k, cy - 4f * k, 2.3f * k, gl);
                break;
            case "BAD":
                Gfx.line(g, cx - 4f * k, cy - 4f * k, cx + 4f * k, cy + 4f * k, 2.3f * k, gl);
                Gfx.line(g, cx + 4f * k, cy - 4f * k, cx - 4f * k, cy + 4f * k, 2.3f * k, gl);
                break;
            case "WARN":
                Gfx.line(g, cx, cy - 5.5f * k, cx, cy + 1.5f * k, 2.4f * k, gl);
                Gfx.disc(g, cx, cy + 5f * k, 1.5f * k, gl);
                break;
            case "MONEY":
                Gfx.text(g, "$", cx, cy + 4.4f * k, 12.5f * k, gl, 1, false, 0f);
                break;
            case "JOB": {
                Gfx.rrect(g, cx - 6f * k, cy - 3f * k, 12f * k, 9f * k, 2f * k, gl);
                Gfx.rrect(g, cx - 2.6f * k, cy - 6.2f * k, 5.2f * k, 3.6f * k, 1.4f * k, gl);
                Gfx.line(g, cx - 6f * k, cy + 1.2f * k, cx + 6f * k, cy + 1.2f * k, 1f * k, Gfx.fade(Gfx.mixArgb(color | 0xFF000000, 0xFF000000, 0.45f), alpha));
                break;
            }
            case "NEWS":
                Gfx.rrect(g, cx - 5.5f * k, cy - 5f * k, 11f * k, 2f * k, 1f * k, gl);
                Gfx.rrect(g, cx - 5.5f * k, cy - 1f * k, 11f * k, 2f * k, 1f * k, gl);
                Gfx.rrect(g, cx - 5.5f * k, cy + 3f * k, 7f * k, 2f * k, 1f * k, gl);
                break;
            default:
                Gfx.disc(g, cx, cy - 5f * k, 1.5f * k, gl);
                Gfx.line(g, cx, cy - 1.4f * k, cx, cy + 6f * k, 2.4f * k, gl);
                break;
        }
    }
}
