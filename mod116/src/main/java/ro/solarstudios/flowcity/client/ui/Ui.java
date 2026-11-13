package ro.solarstudios.flowcity.client.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import java.text.SimpleDateFormat;
import java.util.Date;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/** Primitive de desen pentru interfata FlowCity (dreptunghiuri, text, small caps). */
public final class Ui {
    private Ui() {}

    private static FontRenderer font() {
        return Minecraft.getInstance().font;
    }

    private static int a(int c) {
        return Gfx.alpha >= 0.999f ? c : Gfx.fade(c, Gfx.alpha);
    }

    public static void rect(GuiGraphics g, int x, int y, int w, int h, int color) {
        g.fill(x, y, x + w, y + h, a(color));
    }

    /** Dreptunghi cu colturi usor rotunjite (1px). */
    public static void round(GuiGraphics g, int x, int y, int w, int h, int color) {
        if (w < 3 || h < 3) {
            rect(g, x, y, w, h, color);
            return;
        }
        rect(g, x + 1, y, w - 2, h, color);
        rect(g, x, y + 1, w, h - 2, color);
    }

    /** Dreptunghi rotunjit cu contur de 1px. */
    public static void frame(GuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        round(g, x, y, w, h, border);
        round(g, x + 1, y + 1, w - 2, h - 2, fill);
    }

    /** Gradient vertical (de sus in jos). */
    public static void gradient(GuiGraphics g, int x, int y, int w, int h, int top, int bottom) {
        g.fillGradient(x, y, x + w, y + h, a(top), a(bottom));
    }

    public static int width(String s) {
        return font().width(s);
    }

    public static void text(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font(), s, x, y, a(color), false);
    }

    public static void textShadow(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(font(), s, x, y, a(color), true);
    }

    public static void textRight(GuiGraphics g, String s, int xRight, int y, int color) {
        text(g, s, xRight - width(s), y, color);
    }

    public static void textCenter(GuiGraphics g, String s, int cx, int y, int color) {
        text(g, s, cx - width(s) / 2, y, color);
    }

    /** Taie textul la latimea data, cu "..." la final. */
    public static String clip(String s, int maxWidth) {
        if (s == null) return "";
        if (width(s) <= maxWidth) return s;
        String dots = "...";
        int limit = maxWidth - width(dots);
        if (limit <= 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (width(sb.toString() + s.charAt(i)) > limit) break;
            sb.append(s.charAt(i));
        }
        return sb + dots;
    }

    /** Bara de progres (frac 0..1). */
    public static void bar(GuiGraphics g, int x, int y, int w, int h, float frac, int color) {
        round(g, x, y, w, h, Theme.PANEL_3);
        int fw = Math.round((w - 2) * Math.max(0f, Math.min(1f, frac)));
        if (fw > 0) rect(g, x + 1, y + 1, fw, h - 2, color);
    }

    /** Suma formatata: 1 234 567 (separator de mii cu spatiu), fara zecimale daca e intreaga. */
    public static String money(double v) {
        boolean whole = Math.abs(v - Math.rint(v)) < 0.005;
        String s = whole ? String.format("%,d", (long) Math.rint(v)) : String.format("%,.2f", v);
        return s.replace(',', ' ');
    }

    public static boolean in(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    public static String time(long ms) {
        if (ms <= 0) return "-";
        return new SimpleDateFormat("dd.MM.yy HH:mm").format(new Date(ms));
    }

    public static String duration(long seconds) {
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        if (h >= 24) return (h / 24) + "z " + (h % 24) + "h";
        return h + "h " + String.format("%02d", m) + "m";
    }

    public static String minutesLabel(int minutes) {
        if (minutes <= 0) return "perm";
        if (minutes % 1440 == 0) return (minutes / 1440) + "z";
        if (minutes % 60 == 0) return (minutes / 60) + "h";
        return minutes + "m";
    }

    /** Decupare dreptunghiulara (coordonate GUI). Se inchide cu {@link #endScissor()}. */
    public static void scissor(int x, int y, int w, int h) {
        net.minecraft.client.MainWindow win = Minecraft.getInstance().getWindow();
        double sc = win.getGuiScale();
        RenderSystem.enableScissor((int) (x * sc), (int) (win.getHeight() - (y + h) * sc), Math.max(0, (int) (w * sc)), Math.max(0, (int) (h * sc)));
    }

    public static void endScissor() {
        RenderSystem.disableScissor();
    }

    private static final float SC = 0.78f;

    /** Text in small caps: prima litera din fiecare cuvant normala, restul majuscule mai mici. */
    public static void smallCaps(GuiGraphics g, String text, int x, int y, int color) {
        float cx = x;
        boolean word = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                cx += width(" ");
                word = true;
                continue;
            }
            boolean small = !word && Character.isLowerCase(c);
            String ch = String.valueOf(word || small ? Character.toUpperCase(c) : c);
            g.pose().pushPose();
            g.pose().translate(cx, small ? y + 1.5f : y, 0);
            if (small) g.pose().scale(SC, SC, 1f);
            text(g, ch, 0, 0, color);
            g.pose().popPose();
            cx += width(ch) * (small ? SC : 1f);
            word = false;
        }
    }

    public static int smallCapsWidth(String text) {
        float w = 0;
        boolean word = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                w += width(" ");
                word = true;
                continue;
            }
            boolean small = !word && Character.isLowerCase(c);
            w += width(String.valueOf(Character.toUpperCase(c))) * (small ? SC : 1f);
            word = false;
        }
        return Math.round(w);
    }
}
