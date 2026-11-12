package ro.solarstudios.flowcity.client.ui;

/** Paleta FlowCity (ARGB): grafit cald + un singur accent chihlimbar. Schimba aici culorile pentru tot panoul. */
public final class Theme {
    private Theme() {}

    public static final int DIM = 0xA0080909;
    public static final int PANEL = 0xFF16181D;
    public static final int PANEL_2 = 0xFF1D2026;
    public static final int PANEL_3 = 0xFF262A32;
    public static final int LINE = 0xFF30343D;
    public static final int ACCENT = 0xFFE9A23B;
    public static final int ACCENT_DIM = 0xFF9A6524;
    public static final int VIOLET = 0xFF7F93B3;
    public static final int TEXT = 0xFFEDEDEA;
    public static final int MUTED = 0xFFA19F98;
    public static final int FAINT = 0xFF67666A;
    public static final int OK = 0xFF6DBE6F;
    public static final int WARN = 0xFFE3B649;
    public static final int BAD = 0xFFDA5F55;
    public static final int DISABLED = 0xFF24272D;

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
