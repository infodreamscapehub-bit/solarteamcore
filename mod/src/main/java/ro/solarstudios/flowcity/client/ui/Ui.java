package ro.solarstudios.flowcity.client.ui;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.text.SimpleDateFormat;
import java.util.Date;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.AbstractGui;
import net.minecraft.client.gui.FontRenderer;

/** Primitive de desenare pentru panou (mod imediat: se redeseneaza la fiecare frame). */
public final class Ui {
    private Ui() {}

    private static FontRenderer font() {
        return Minecraft.getInstance().fontRenderer;
    }

    public static void rect(MatrixStack ms, int x, int y, int w, int h, int color) {
        AbstractGui.fill(ms, x, y, x + w, y + h, color);
    }

    /** Dreptunghi cu colturi usor rotunjite (1px). */
    public static void round(MatrixStack ms, int x, int y, int w, int h, int color) {
        if (w < 3 || h < 3) {
            rect(ms, x, y, w, h, color);
            return;
        }
        rect(ms, x + 1, y, w - 2, h, color);
        rect(ms, x, y + 1, w, h - 2, color);
    }

    /** Dreptunghi rotunjit cu contur de 1px. */
    public static void frame(MatrixStack ms, int x, int y, int w, int h, int fill, int border) {
        round(ms, x, y, w, h, border);
        round(ms, x + 1, y + 1, w - 2, h - 2, fill);
    }

    public static int width(String s) {
        return font().getStringWidth(s);
    }

    public static void text(MatrixStack ms, String s, int x, int y, int color) {
        font().drawString(ms, s, (float) x, (float) y, color);
    }

    public static void textShadow(MatrixStack ms, String s, int x, int y, int color) {
        font().drawStringWithShadow(ms, s, (float) x, (float) y, color);
    }

    public static void textRight(MatrixStack ms, String s, int xRight, int y, int color) {
        text(ms, s, xRight - width(s), y, color);
    }

    public static void textCenter(MatrixStack ms, String s, int cx, int y, int color) {
        text(ms, s, cx - width(s) / 2, y, color);
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
    public static void bar(MatrixStack ms, int x, int y, int w, int h, float frac, int color) {
        round(ms, x, y, w, h, Theme.PANEL_3);
        int fw = Math.round((w - 2) * Math.max(0f, Math.min(1f, frac)));
        if (fw > 0) rect(ms, x + 1, y + 1, fw, h - 2, color);
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
}
