package ro.solarstudios.flowcity.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.vector.Matrix4f;
import net.minecraft.util.text.IFormattableTextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.Style;
import org.lwjgl.opengl.GL11;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Desen "neted" pentru interfetele FlowCity: cercuri, arcuri, dreptunghiuri cu colturi rotunde si gradient, desenate din
 * triunghiuri (nu din patratele), plus text cu fontul Inter (assets/flowcitycore/font, licenta OFL).
 */
public final class Gfx {
    private Gfx() {}

    public static final ResourceLocation FONT = new ResourceLocation("flowcitycore", "ui");
    public static final ResourceLocation FONT_XL = new ResourceLocation("flowcitycore", "ui_xl");
    private static final float BASE = 8f;
    private static final float BASE_XL = 30f;
    /** Cat din marimea fontului e deasupra liniei de baza (aproximativ inaltimea cifrelor la Inter). */
    private static final float ASCENT = 0.80f;

    /** Culoare in functie de pozitie (pentru gradiente). */
    public interface Paint {
        int at(float x, float y);
    }

    // ------------------------------------------------------------------ stare GL

    private static BufferBuilder begin(int mode) {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableTexture();
        RenderSystem.disableAlphaTest();
        RenderSystem.disableCull();
        RenderSystem.shadeModel(GL11.GL_SMOOTH);
        BufferBuilder b = Tessellator.getInstance().getBuilder();
        b.begin(mode, DefaultVertexFormats.POSITION_COLOR);
        return b;
    }

    private static void end() {
        Tessellator.getInstance().end();
        RenderSystem.shadeModel(GL11.GL_FLAT);
        RenderSystem.enableCull();
        RenderSystem.enableAlphaTest();
        RenderSystem.enableTexture();
    }

    /** Transparenta globala a elementului de HUD desenat acum (o seteaza editorul de HUD prin HudLayout.begin/end). */
    public static float alpha = 1f;

    private static void v(BufferBuilder b, Matrix4f m, float x, float y, int argb) {
        b.vertex(m, x, y, 0f).color((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, Math.round(((argb >>> 24) & 255) * alpha)).endVertex();
    }

    // ------------------------------------------------------------------ forme

    /** Disc plin; centrul poate avea alta culoare (gradient radial), mutat cu (ox, oy). */
    public static void disc(GuiGraphics g, float cx, float cy, float r, int center, int edge, float ox, float oy) {
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = begin(GL11.GL_TRIANGLE_FAN);
        v(b, m, cx + ox, cy + oy, center);
        int n = Math.max(24, (int) (r * 1.5f));
        for (int i = 0; i <= n; i++) {
            double a = Math.PI * 2 * i / n;
            v(b, m, cx + (float) Math.cos(a) * r, cy + (float) Math.sin(a) * r, edge);
        }
        end();
    }

    public static void disc(GuiGraphics g, float cx, float cy, float r, int color) {
        disc(g, cx, cy, r, color, color, 0, 0);
    }

    /** Inel intre r0 si r1, cu culoare diferita pe interior si exterior (umbre, contururi, straluciri). */
    public static void ring(GuiGraphics g, float cx, float cy, float r0, float r1, int in, int out) {
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = begin(GL11.GL_TRIANGLE_STRIP);
        int n = Math.max(32, (int) (r1 * 1.5f));
        for (int i = 0; i <= n; i++) {
            double a = Math.PI * 2 * i / n;
            float c = (float) Math.cos(a);
            float s = (float) Math.sin(a);
            v(b, m, cx + c * r0, cy + s * r0, in);
            v(b, m, cx + c * r1, cy + s * r1, out);
        }
        end();
    }

    /** Segment de inel (felie de roata) intre r0 si r1 si unghiurile d0..d1, cu culoare pe interior si pe exterior. */
    public static void sector(GuiGraphics g, float cx, float cy, float r0, float r1, float d0, float d1, int in, int out) {
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = begin(GL11.GL_TRIANGLE_STRIP);
        int n = Math.max(2, (int) Math.ceil(Math.abs(d1 - d0) / 3f));
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(d0 + (d1 - d0) * i / n);
            float c = (float) Math.cos(a);
            float s = (float) Math.sin(a);
            v(b, m, cx + c * r0, cy + s * r0, in);
            v(b, m, cx + c * r1, cy + s * r1, out);
        }
        end();
    }

    /** Arc gros cu capete rotunde, intre unghiurile d0 si d1 (grade, 0 = dreapta, sensul acelor de ceasornic). */
    public static void arc(GuiGraphics g, float cx, float cy, float r, float th, float d0, float d1, Paint p, float alpha) {
        if (d1 - d0 < 0.01f) return;
        Matrix4f m = g.pose().last().pose();
        float h = th / 2f;
        BufferBuilder b = begin(GL11.GL_TRIANGLE_STRIP);
        int n = Math.max(2, (int) Math.ceil((d1 - d0) / 2f));
        for (int i = 0; i <= n; i++) {
            double a = Math.toRadians(d0 + (d1 - d0) * i / n);
            float c = (float) Math.cos(a);
            float s = (float) Math.sin(a);
            int col = fade(p.at(cx + c * r, cy + s * r), alpha);
            v(b, m, cx + c * (r - h), cy + s * (r - h), col);
            v(b, m, cx + c * (r + h), cy + s * (r + h), col);
        }
        end();
        for (float d : new float[] {d0, d1}) {
            double a = Math.toRadians(d);
            float x = cx + (float) Math.cos(a) * r;
            float y = cy + (float) Math.sin(a) * r;
            int col = fade(p.at(x, y), alpha);
            disc(g, x, y, h, col);
        }
    }

    /** Linie de grosime w (dreptunghi rotit). */
    public static void line(GuiGraphics g, float x0, float y0, float x1, float y1, float w, int color) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return;
        float nx = -dy / len * w / 2f;
        float ny = dx / len * w / 2f;
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = begin(GL11.GL_TRIANGLE_FAN);
        v(b, m, x0 + nx, y0 + ny, color);
        v(b, m, x1 + nx, y1 + ny, color);
        v(b, m, x1 - nx, y1 - ny, color);
        v(b, m, x0 - nx, y0 - ny, color);
        end();
    }

    /** Dreptunghi cu colturi rotunde de raza r; culoarea poate trece de la top la bottom (gradient vertical). */
    public static void rrect(GuiGraphics g, float x, float y, float w, float h, float r, int top, int bottom) {
        r = Math.max(0, Math.min(r, Math.min(w, h) / 2f));
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = begin(GL11.GL_TRIANGLE_FAN);
        float cx = x + w / 2f;
        float cy = y + h / 2f;
        v(b, m, cx, cy, mixArgb(top, bottom, 0.5f));
        float[][] corners = {{x + w - r, y + r, -90}, {x + w - r, y + h - r, 0}, {x + r, y + h - r, 90}, {x + r, y + r, 180}};
        int steps = Math.max(3, (int) (r * 1.2f));
        for (float[] c : corners) {
            for (int i = 0; i <= steps; i++) {
                double a = Math.toRadians(c[2] + 90.0 * i / steps);
                float px = c[0] + (float) Math.cos(a) * r;
                float py = c[1] + (float) Math.sin(a) * r;
                v(b, m, px, py, mixArgb(top, bottom, h <= 0 ? 0 : (py - y) / h));
            }
        }
        v(b, m, x + w - r, y, top); // inchide conturul
        end();
    }

    public static void rrect(GuiGraphics g, float x, float y, float w, float h, float r, int color) {
        rrect(g, x, y, w, h, r, color, color);
    }

    /** Bara rotunjita cu umplere (0..1). */
    public static void bar(GuiGraphics g, float x, float y, float w, float h, float frac, int track, int fill) {
        rrect(g, x, y, w, h, h / 2f, track);
        float f = Math.max(0f, Math.min(1f, frac));
        if (f > 0) rrect(g, x, y, Math.max(h, w * f), h, h / 2f, fill);
    }

    // ------------------------------------------------------------------ text (Inter)

    /** Componenta de text + latimea ei, tinute minte: acelasi text se deseneaza in fiecare cadru si nu trebuie refacut. */
    private static final class Cached {
        final IFormattableTextComponent c;
        float w = -1f;

        Cached(IFormattableTextComponent c) {
            this.c = c;
        }
    }

    private static final java.util.HashMap<String, Cached> CACHE = new java.util.HashMap<>();
    private static final java.util.HashMap<String, Cached> CACHE_XL = new java.util.HashMap<>();
    /** Caractere singure (text cu spatiere): fara substring nou la fiecare frame. */
    private static final String[] CHARS = new String[256];

    private static String charStr(String s, int i) {
        char ch = s.charAt(i);
        if (ch >= 256) return s.substring(i, i + 1);
        String r = CHARS[ch];
        if (r == null) {
            r = String.valueOf(ch);
            CHARS[ch] = r;
        }
        return r;
    }

    private static Cached cached(String s, boolean xl) {
        java.util.HashMap<String, Cached> map = xl ? CACHE_XL : CACHE;
        Cached c = map.get(s);
        if (c == null) {
            if (map.size() > 1500) map.clear();
            c = new Cached(new StringTextComponent(s).setStyle(Style.EMPTY.withFont(xl ? FONT_XL : FONT)));
            map.put(s, c);
        }
        return c;
    }

    private static IFormattableTextComponent comp(String s, boolean xl) {
        return cached(s, xl).c;
    }

    private static float rawWidth(String s, boolean xl) {
        Cached c = cached(s, xl);
        if (c.w < 0f) c.w = Minecraft.getInstance().font.width(c.c);
        return c.w;
    }

    public static float width(String s, float size, boolean xl) {
        return rawWidth(s, xl) * size / (xl ? BASE_XL : BASE);
    }

    /**
     * Text cu fontul Inter. y = linia de baza (ca in CSS/SVG), size = inaltimea fontului in pixeli GUI.
     * align: 0 stanga, 1 centru, 2 dreapta. spacing = spatiu suplimentar intre litere.
     */
    public static void text(GuiGraphics g, String s, float x, float y, float size, int color, int align, boolean xl, float spacing) {
        if (s == null || s.isEmpty()) return;
        if (alpha < 0.999f) color = fade(color, alpha);
        FontRenderer f = Minecraft.getInstance().font;
        float k = size / (xl ? BASE_XL : BASE);
        float w = width(s, size, xl) + spacing * (s.length() - 1);
        float sx = align == 1 ? x - w / 2f : (align == 2 ? x - w : x);
        g.pose().pushPose();
        g.pose().translate(sx, y - size * ASCENT, 0);
        g.pose().scale(k, k, 1f);
        if (spacing == 0f) {
            f.draw(g.pose(), comp(s, xl), 0, 0, color);
        } else {
            float cx = 0;
            for (int i = 0; i < s.length(); i++) {
                String ch = charStr(s, i);
                f.draw(g.pose(), comp(ch, xl), cx, 0, color);
                cx += rawWidth(ch, xl) + spacing / k;
            }
        }
        g.pose().popPose();
    }

    public static void text(GuiGraphics g, String s, float x, float y, float size, int color, int align) {
        text(g, s, x, y, size, color, align, false, 0f);
    }

    /** Text cu umbra moale dedesubt (pentru elemente fara panou, peste lume). */
    public static void textS(GuiGraphics g, String s, float x, float y, float size, int color, int align, boolean xl, float spacing) {
        float a = ((color >>> 24) & 255) / 255f;
        text(g, s, x + size * 0.03f, y + Math.max(0.8f, size * 0.06f), size, fade(0xA0000000, a), align, xl, spacing);
        text(g, s, x, y, size, color, align, xl, spacing);
    }

    /** Poligon convex plin (puncte x0,y0,x1,y1,...). */
    public static void poly(GuiGraphics g, float[] pts, int color) {
        Matrix4f m = g.pose().last().pose();
        BufferBuilder b = begin(GL11.GL_TRIANGLE_FAN);
        for (int i = 0; i + 1 < pts.length; i += 2) v(b, m, pts[i], pts[i + 1], color);
        end();
    }

    /** Umbra/estompare eliptica (centru opac -> margini transparente), pentru lizibilitate fara panou. */
    public static void scrim(GuiGraphics g, float cx, float cy, float rx, float ry, int center) {
        g.pose().pushPose();
        g.pose().translate(cx, cy, 0);
        g.pose().scale(1f, ry / rx, 1f);
        disc(g, 0, 0, rx, center, center & 0x00FFFFFF, 0, 0);
        g.pose().popPose();
    }

    // ------------------------------------------------------------------ culori

    public static int fade(int argb, float alpha) {
        int a = Math.round(((argb >>> 24) & 255) * alpha);
        return (a << 24) | (argb & 0xFFFFFF);
    }

    public static int mixArgb(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int aa = (int) (((a >>> 24) & 255) * (1 - t) + ((b >>> 24) & 255) * t);
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int gg = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return (aa << 24) | (r << 16) | (gg << 8) | bl;
    }

    /** Gradient cu opriri: stops = {pozitie0, culoare0, pozitie1, culoare1, ...} cu pozitii 0..1. */
    public static int stops(float t, float[] pos, int[] cols) {
        if (t <= pos[0]) return cols[0];
        for (int i = 1; i < pos.length; i++) {
            if (t <= pos[i]) return mixArgb(cols[i - 1], cols[i], (t - pos[i - 1]) / (pos[i] - pos[i - 1]));
        }
        return cols[cols.length - 1];
    }
}
