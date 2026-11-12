package ro.solarstudios.flowcity.client.ui;

import net.minecraft.client.gui.GuiGraphics;

/** Decor de Craciun desenat din dreptunghiuri: ghirlanda cu beculete, zapada, caciula de mos. */
public final class Xmas {
    private Xmas() {}

    public static boolean enabled = true;

    private static final int[] BULBS = {0xFFFF3B3B, 0xFF34D399, 0xFFFBBF24, 0xFF38BDF8, 0xFFF472B6};

    /** Ghirlanda cu fir lasat in curbe si beculete care licaresc. */
    public static void garland(GuiGraphics ms, int x, int y, int w, double t, int sag, int spacing) {
        int seg = Math.max(20, spacing * 2);
        for (int px = 0; px < w; px++) {
            double u = (px % seg) / (double) seg;
            int dy = (int) Math.round(Math.sin(u * Math.PI) * sag);
            Ui.rect(ms, x + px, y + dy, 1, 1, 0xFF1F3B2D);
        }
        for (int bx = spacing / 2; bx < w; bx += spacing) {
            double u = (bx % seg) / (double) seg;
            int dy = (int) Math.round(Math.sin(u * Math.PI) * sag);
            int idx = (bx / spacing) % BULBS.length;
            double phase = Math.sin(t * 3.0 + idx * 1.7 + bx * 0.05);
            int a = phase > -0.2 ? 0xFF : 0x70;
            int c = Theme.alpha(BULBS[idx], a);
            Ui.rect(ms, x + bx, y + dy + 1, 2, 3, c);
            if (a == 0xFF) Ui.rect(ms, x + bx - 1, y + dy + 2, 4, 1, Theme.alpha(BULBS[idx], 0x40));
        }
    }

    /** Fulgi care cad in dreptunghiul dat (pozitii calculate din timp, fara stare). */
    public static void snow(GuiGraphics ms, int x, int y, int w, int h, double t, int count, int alpha) {
        for (int i = 0; i < count; i++) {
            double seed = i * 12.9898;
            double speed = 8 + (i * 37 % 17);
            double fx = ((Math.sin(seed) * 43758.5453) % 1 + 1) % 1;
            double py = ((t * speed / h + ((Math.sin(seed * 1.7) * 9183.3) % 1 + 1) % 1) % 1) * h;
            double px = fx * w + Math.sin(t * 0.8 + i) * 3;
            int size = (i % 3 == 0) ? 2 : 1;
            Ui.rect(ms, x + (int) px, y + (int) py, size, size, Theme.alpha(0xFFFFFFFF, alpha - (size == 1 ? 40 : 0)));
        }
    }

    /** Caciula rosie de Mos Craciun (baza pe latimea s). */
    public static void hat(GuiGraphics ms, int x, int y, int s) {
        int rows = Math.max(4, s / 2);
        for (int r = 0; r < rows; r++) {
            int rw = Math.max(2, s - (r * (s - 3)) / rows);
            Ui.rect(ms, x + (s - rw) / 2 + r / 3, y + rows - r, rw, 1, r % 4 == 3 ? 0xFFD62828 : 0xFFE63946);
        }
        Ui.rect(ms, x - 1, y + rows + 1, s + 2, 3, 0xFFF8FAFC); // blana
        Ui.rect(ms, x + s / 2 + rows / 3, y + 1, 3, 3, 0xFFF8FAFC); // ciucure
    }
}
