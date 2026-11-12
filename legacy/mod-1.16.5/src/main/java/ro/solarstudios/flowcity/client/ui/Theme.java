package ro.solarstudios.flowcity.client.ui;

/** Paleta FlowCity (ARGB). Schimba aici culorile pentru tot panoul. */
public final class Theme {
    private Theme() {}

    public static final int DIM = 0x99050912;
    public static final int PANEL = 0xFF0E1627;
    public static final int PANEL_2 = 0xFF141F36;
    public static final int PANEL_3 = 0xFF1B2846;
    public static final int LINE = 0xFF26365A;
    public static final int ACCENT = 0xFF22D3EE;
    public static final int ACCENT_DIM = 0xFF0E6F82;
    public static final int VIOLET = 0xFF7C83FF;
    public static final int TEXT = 0xFFE8EEF9;
    public static final int MUTED = 0xFF8EA2C2;
    public static final int FAINT = 0xFF56688A;
    public static final int OK = 0xFF34D399;
    public static final int WARN = 0xFFFBBF24;
    public static final int BAD = 0xFFF87171;
    public static final int DISABLED = 0xFF222D45;

    /** Face o culoare mai deschisa (pentru hover). */
    public static int lighten(int argb, int amount) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, ((argb >> 16) & 0xFF) + amount);
        int g = Math.min(255, ((argb >> 8) & 0xFF) + amount);
        int b = Math.min(255, (argb & 0xFF) + amount);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /** Amestec liniar opac intre doua culori (t=0 -> a, t=1 -> b). */
    public static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Aceeasi culoare cu alta opacitate (0-255). */
    public static int alpha(int argb, int alpha) {
        return (alpha << 24) | (argb & 0xFFFFFF);
    }
}
