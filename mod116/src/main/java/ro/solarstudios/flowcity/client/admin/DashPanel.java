package ro.solarstudios.flowcity.client.admin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Dashboard eAdmin (cand nu e selectat niciun jucator): carduri live, grafic de jucatori, activitate recenta, alerte. */
public final class DashPanel extends JsonClientModule {
    private static DashPanel instance;
    private static JsonObject stats = new JsonObject();
    private static boolean loaded;
    private static final List<Integer> HISTORY = new ArrayList<>();
    private static long lastRequest;
    private static long loadedAt;
    // valori afisate (numarare animata)
    private static final double[] SHOWN = new double[8];

    private DashPanel() {
        on("stats", d -> {
            stats = d;
            if (!loaded) loadedAt = System.currentTimeMillis();
            loaded = true;
            HISTORY.add(integer(d, "online", 0));
            while (HISTORY.size() > 48) HISTORY.remove(0);
        });
    }

    @Override
    public String id() {
        return "dash";
    }

    @Override
    public void onLogout() {
        stats = new JsonObject();
        loaded = false;
        HISTORY.clear();
    }

    public static void register() {
        instance = new DashPanel();
        ClientModules.register(instance);
    }

    /** Cere statistici (cel mult o data la 5 secunde cat timp panoul e deschis). */
    public static void poll() {
        long now = System.currentTimeMillis();
        if (instance != null && now - lastRequest > 5000) {
            lastRequest = now;
            instance.send("stats", new JsonObject());
        }
    }

    public static void forceRefresh() {
        lastRequest = 0;
    }

    // ------------------------------------------------------------------ desenare

    private static double animate(int slot, double target) {
        SHOWN[slot] += (target - SHOWN[slot]) * 0.18;
        if (Math.abs(target - SHOWN[slot]) < 0.05) SHOWN[slot] = target;
        return SHOWN[slot];
    }

    private static double n(String k) {
        return stats.has(k) && stats.get(k).isJsonPrimitive() ? stats.get(k).getAsDouble() : 0;
    }

    private static String ago(long ts) {
        long s = Math.max(0, (System.currentTimeMillis() - ts) / 1000);
        if (s < 60) return s + "s";
        if (s < 3600) return (s / 60) + "m";
        if (s < 86400) return (s / 3600) + "h";
        return (s / 86400) + "z";
    }

    public static void draw(GuiGraphics ms, int x, int y, int w, int h, long now) {
        if (!loaded) {
            int dots = (int) ((now / 350) % 4);
            Ui.textCenter(ms, "Se incarca statisticile" + "...".substring(0, dots), x + w / 2, y + h / 2 - 4, Theme.MUTED);
            return;
        }
        float fade = Math.min(1f, (now - loadedAt) / 450f);
        int a = (int) (255 * fade);
        int gap = 4;
        // titlu
        Ui.text(ms, "DASHBOARD", x, y, Theme.ACCENT);
        Ui.textRight(ms, (int) n("total") + " jucatori inregistrati  |  +" + (int) n("newToday") + " in 24h", x + w, y, Theme.MUTED);
        y += 12;

        // ---- carduri mari
        int cw = (w - gap * 3) / 4;
        int chh = 46;
        double tps = n("tps");
        card(ms, x, y, cw, chh, "ONLINE", String.valueOf(Math.round(animate(0, n("online")))) + "/" + (int) n("max"),
            n("max") <= 0 ? 0 : (float) (n("online") / n("max")), Theme.ACCENT, a);
        card(ms, x + (cw + gap), y, cw, chh, "TPS", tps < 0 ? "n/a" : String.format(Locale.US, "%.1f", animate(1, tps)),
            tps < 0 ? 0 : (float) (tps / 20.0), tps >= 18 ? Theme.OK : (tps >= 14 ? Theme.WARN : Theme.BAD), a);
        double ramFrac = n("ramMax") <= 0 ? 0 : n("ramUsed") / n("ramMax");
        card(ms, x + 2 * (cw + gap), y, cw, chh, "RAM", Math.round(animate(2, n("ramUsed"))) + " MB",
            (float) ramFrac, ramFrac < 0.75 ? Theme.VIOLET : (ramFrac < 0.9 ? Theme.WARN : Theme.BAD), a);
        card(ms, x + 3 * (cw + gap), y, cw, chh, "CU MOD", (int) n("mods") + "/" + (int) n("online"),
            n("online") <= 0 ? 0 : (float) (n("mods") / n("online")), Theme.OK, a);
        y += chh + gap;

        // ---- grafic jucatori online
        int gh = 34;
        Ui.frame(ms, x, y, w, gh, Theme.PANEL, Theme.LINE);
        Ui.text(ms, "Jucatori online (live)", x + 5, y + 3, Theme.FAINT);
        Ui.textRight(ms, "staff online: " + (int) n("staff"), x + w - 5, y + 3, Theme.MUTED);
        sparkline(ms, x + 4, y + 13, w - 8, gh - 17, a);
        y += gh + gap;

        // ---- mini statistici
        int mw = (w - gap * 3) / 4;
        mini(ms, x, y, mw, "BAN-URI ACTIVE", (int) animate(3, n("bans")), Theme.BAD, a);
        mini(ms, x + (mw + gap), y, mw, "MUTE ACTIVE", (int) animate(4, n("mutes")), Theme.WARN, a);
        mini(ms, x + 2 * (mw + gap), y, mw, "SANCTIUNI 24H", (int) animate(5, n("sanctions24")), Theme.VIOLET, a);
        mini(ms, x + 3 * (mw + gap), y, mw, "ALERTE 24H", (int) animate(6, n("flags24")), n("flags24") > 0 ? Theme.WARN : Theme.FAINT, a);
        y += 26 + gap;

        // ---- activitate recenta + alerte
        int lw = (int) (w * 0.58f);
        int rw = w - lw - gap;
        feed(ms, x, y, lw, a);
        alerts(ms, x + lw + gap, y, rw, a);
    }

    private static void card(GuiGraphics ms, int x, int y, int w, int h, String label, String value, float frac, int color, int a) {
        Ui.frame(ms, x, y, w, h, Theme.PANEL, Theme.mix(Theme.LINE, color, 0.35f));
        Ui.rect(ms, x + 1, y + 1, w - 2, 2, Theme.alpha(color, Math.min(255, a)));
        Ui.text(ms, label, x + 6, y + 7, Theme.FAINT);
        ms.pose().pushPose();
        ms.pose().translate(x + 6, y + 18, 0);
        ms.pose().scale(1.5f, 1.5f, 1f);
        Ui.textShadow(ms, value, 0, 0, Theme.TEXT);
        ms.pose().popPose();
        Ui.bar(ms, x + 6, y + h - 7, w - 12, 3, Math.max(0f, Math.min(1f, frac)), color);
    }

    private static void mini(GuiGraphics ms, int x, int y, int w, String label, int value, int color, int a) {
        Ui.frame(ms, x, y, w, 26, Theme.PANEL, Theme.LINE);
        Ui.rect(ms, x + 1, y + 3, 2, 20, color);
        Ui.text(ms, label, x + 7, y + 4, Theme.FAINT);
        Ui.text(ms, String.valueOf(value), x + 7, y + 14, value > 0 ? color : Theme.MUTED);
    }

    private static void sparkline(GuiGraphics ms, int x, int y, int w, int h, int a) {
        if (HISTORY.size() < 2) {
            Ui.text(ms, "se aduna date...", x + 2, y + h / 2 - 4, Theme.FAINT);
            return;
        }
        int max = 1;
        for (int v : HISTORY) max = Math.max(max, v);
        int n = HISTORY.size();
        for (int i = 0; i < n; i++) {
            int bx = x + i * w / 48;
            int bw = Math.max(1, w / 48 - 1);
            int bh = Math.max(1, (int) ((double) HISTORY.get(i) / max * h));
            boolean last = i == n - 1;
            Ui.rect(ms, bx, y + h - bh, bw, bh, last ? Theme.ACCENT : Theme.alpha(Theme.ACCENT_DIM, 0xFF));
        }
    }

    private static int typeColor(String t) {
        switch (t) {
            case "BAN": return Theme.BAD;
            case "MUTE": return Theme.WARN;
            case "KICK": return Theme.VIOLET;
            default: return Theme.ACCENT;
        }
    }

    private static void feed(GuiGraphics ms, int x, int y, int w, int a) {
        Ui.text(ms, "ACTIVITATE RECENTA", x, y, Theme.FAINT);
        int yy = y + 11;
        boolean any = false;
        if (stats.has("recent")) {
            for (JsonElement e : stats.getAsJsonArray("recent")) {
                JsonObject o = e.getAsJsonObject();
                any = true;
                String t = str(o, "type", "?");
                int c = typeColor(t);
                Ui.round(ms, x, yy, 32, 10, Theme.alpha(c, 0x40));
                Ui.textCenter(ms, t, x + 16, yy + 1, c);
                String line = str(o, "target", "?") + " - " + str(o, "reason", "");
                Ui.text(ms, Ui.clip(line, w - 74), x + 36, yy + 1, Theme.TEXT);
                Ui.textRight(ms, ago(lng(o, "ts", 0)), x + w - 1, yy + 1, Theme.FAINT);
                yy += 12;
            }
        }
        if (!any) Ui.text(ms, "Nicio sanctiune inca.", x + 2, yy + 2, Theme.MUTED);
    }

    private static void alerts(GuiGraphics ms, int x, int y, int w, int a) {
        Ui.text(ms, "ALERTE ANTI-CHEAT", x, y, Theme.FAINT);
        int yy = y + 11;
        boolean any = false;
        if (stats.has("flags")) {
            for (JsonElement e : stats.getAsJsonArray("flags")) {
                JsonObject o = e.getAsJsonObject();
                any = true;
                String sev = str(o, "sev", "LOW");
                int c = sev.startsWith("H") ? Theme.BAD : (sev.startsWith("M") ? Theme.WARN : Theme.MUTED);
                Ui.rect(ms, x, yy + 1, 3, 8, c);
                Ui.text(ms, Ui.clip(str(o, "target", "?") + " " + str(o, "trigger", ""), w - 30), x + 7, yy + 1, Theme.TEXT);
                Ui.textRight(ms, ago(lng(o, "ts", 0)), x + w - 1, yy + 1, Theme.FAINT);
                yy += 12;
            }
        }
        if (!any) Ui.text(ms, "Totul curat.", x + 2, yy + 2, Theme.OK);
    }
}
