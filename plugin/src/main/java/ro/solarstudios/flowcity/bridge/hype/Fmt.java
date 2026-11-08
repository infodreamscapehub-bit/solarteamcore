package ro.solarstudios.flowcity.bridge.hype;

import java.util.Locale;

/** Formatare simpla pentru mesaje. */
final class Fmt {
    private Fmt() {}

    /** 1234567.5 -> "1,234,567.50" */
    static String money(double v) {
        return String.format(Locale.US, "%,.2f", v);
    }
}
