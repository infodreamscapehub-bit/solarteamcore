package ro.solarstudios.flowcity.cars.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Mecanic: reparatie caroserie, ITP si asigurare RCA pentru masinile tale. Tema de iarna FlowCity. */
public final class MecanicScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final int ROW = 42;

    private static final class Car {
        int id;
        String name;
        String plate;
        float hp;
        float maxHp;
        long itp;
        long ins;
        boolean out;
        boolean pound;
    }

    private final List<Car> cars = new ArrayList<>();
    private int selected;
    private int scroll;
    private final long born = System.currentTimeMillis();
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private float offX;
    private float offY;
    private float scale = 1f;

    MecanicScreen(JsonObject d) {
        super("Mecanic", W, H);
        update(d);
    }

    void update(JsonObject d) {
        int keep = selected < cars.size() ? cars.get(selected).id : -1;
        cars.clear();
        JsonArray a = d.has("cars") && d.get("cars").isJsonArray() ? d.getAsJsonArray("cars") : new JsonArray();
        for (JsonElement e : a) {
            JsonObject o = e.getAsJsonObject();
            Car c = new Car();
            c.id = JsonClientModule.integer(o, "id", 0);
            c.name = JsonClientModule.str(o, "name", "Masina");
            c.plate = JsonClientModule.str(o, "plate", "");
            c.hp = (float) JsonClientModule.num(o, "hp", 0);
            c.maxHp = (float) JsonClientModule.num(o, "maxHp", 0);
            c.itp = JsonClientModule.lng(o, "itp", 0);
            c.ins = JsonClientModule.lng(o, "ins", 0);
            c.out = JsonClientModule.bool(o, "out", false);
            c.pound = JsonClientModule.bool(o, "pound", false);
            cars.add(c);
        }
        selected = 0;
        for (int i = 0; i < cars.size(); i++) if (cars.get(i).id == keep) selected = i;
    }

    void result(boolean ok, String m) {
        msg = m;
        msgOk = ok;
        msgUntil = System.currentTimeMillis() + 4500;
    }

    @Override
    protected boolean framed() {
        return false;
    }

    private static float easeOut(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private void hitL(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(offX + x * scale), Math.round(offY + y * scale), Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), r);
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private static String date(long ms) {
        return new SimpleDateFormat("dd.MM.yyyy HH:mm").format(new Date(ms));
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        int visible = (H - 12 - 66) / ROW;
        scroll = Math.max(0, Math.min(Math.max(0, cars.size() - visible), scroll - (int) Math.signum(delta)));
        return true;
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        float open = easeOut((System.currentTimeMillis() - born) / 300f);
        scale = Math.min(1f, Math.min((width - 8f) / W, (height - 8f) / H));
        offX = width / 2f - W / 2f * scale;
        offY = height / 2f - H / 2f * scale;
        float lmx = (mx - offX) / scale;
        float lmy = (my - offY) / scale;
        ms.pose().pushPose();
        ms.pose().translate(offX, offY, 0);
        ms.pose().scale(scale, scale, scale);
        Sk.background(ms, -offX / scale, -offY / scale, (width - offX) / scale, (height - offY) / scale, open);
        Gfx.alpha = open;
        try {
            content(ms, lmx, lmy);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
    }

    private void content(GuiGraphics ms, float lmx, float lmy) {
        Gfx.text(ms, "MECANIC", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "REPARAȚII · ITP · ASIGURARE RCA", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);
        if (cars.isEmpty()) {
            Gfx.text(ms, "Nu ai nicio mașină.", W / 2f, H / 2f, 11, Sk.WHITE, 1, true, 0f);
            return;
        }
        float top = 66f;
        int visible = (int) ((H - 12 - top) / ROW);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, cars.size() - visible)));
        float y = top;
        long now = System.currentTimeMillis();
        for (int i = scroll; i < Math.min(cars.size(), scroll + visible); i++) {
            Car c = cars.get(i);
            boolean sel = i == selected;
            boolean h = Sk.in(lmx, lmy, 12, y, 220, ROW - 6);
            Sk.slant(ms, 12, y, 220, ROW - 6, 8f, sel ? Sk.ROW_HOT : h ? 0xD0182436 : Sk.ROW);
            Gfx.rrect(ms, 24, y + 8, 3, ROW - 22, 0, sel ? Sk.ACC : Sk.FAINT);
            Gfx.text(ms, clip(c.name, 9.4f, true, 130), 36, y + 15, 9.4f, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, c.plate, 36, y + 27, 6.8f, Sk.MUTED, 0);
            Gfx.rrect(ms, 190, y + 11, 8, 8, 4, c.itp > now ? Sk.GOOD : Sk.BAD);
            Gfx.rrect(ms, 204, y + 11, 8, 8, 4, c.ins > now ? Sk.GOOD : Sk.BAD);
            Gfx.text(ms, "ITP", 194, y + 29, 5.4f, Sk.FAINT, 1);
            Gfx.text(ms, "RCA", 208, y + 29, 5.4f, Sk.FAINT, 1);
            final int idx = i;
            hitL(12, y, 220, ROW - 6, () -> selected = idx);
            y += ROW;
        }
        if (selected >= cars.size()) selected = 0;
        Car c = cars.get(selected);
        float rx = 244;
        float rw = W - rx - 14;
        Sk.slant(ms, rx, top, rw, H - 12 - top, 10f, Sk.PANEL);
        Gfx.text(ms, clip(c.name, 12, true, rw - 100), rx + 16, top + 24, 12, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, c.plate, rx + rw - 16, top + 24, 9, Sk.ACC, 2, true, 0f);

        // caroserie
        float frac = c.maxHp <= 0 ? 1f : Math.max(0f, Math.min(1f, c.hp / c.maxHp));
        int hpCol = frac > 0.66f ? Sk.GOOD : frac > 0.33f ? Sk.WARN : Sk.BAD;
        Gfx.text(ms, "CAROSERIE", rx + 16, top + 46, 6.2f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, c.maxHp <= 0 ? "-" : Math.round(frac * 100) + "%", rx + rw - 16, top + 46, 7.5f, hpCol, 2, true, 0f);
        Gfx.rrect(ms, rx + 16, top + 52, rw - 32, 5, 0, 0xFF1B2839);
        Gfx.rrect(ms, rx + 16, top + 52, (rw - 32) * frac, 5, 0, hpCol);

        statusLine(ms, "ITP", c.itp, rx + 16, top + 76, rw - 32, now);
        statusLine(ms, "ASIGURARE RCA", c.ins, rx + 16, top + 100, rw - 32, now);

        float bw = (rw - 32 - 8) / 2f;
        float by = top + 126;
        boolean canRepair = !c.out && c.maxHp > 0 && frac < 0.999f;
        service(ms, lmx, lmy, rx + 16, by, bw, 28, "REPARĂ  " + MecanicClient.repair + " lei", canRepair, c.id, "repair");
        service(ms, lmx, lmy, rx + 16 + bw + 8, by, bw, 28, "ITP +14 zile  " + MecanicClient.itp + " lei", true, c.id, "itp");
        service(ms, lmx, lmy, rx + 16, by + 34, bw, 28, "RCA 7 zile  " + MecanicClient.rca7 + " lei", true, c.id, "rca7");
        service(ms, lmx, lmy, rx + 16 + bw + 8, by + 34, bw, 28, "RCA 30 zile  " + MecanicClient.rca30 + " lei", true, c.id, "rca30");
        if (c.out) Gfx.text(ms, "Parchează mașina în garaj pentru reparații.", rx + 16, by + 76, 6.6f, Sk.WARN, 0);

        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, msg, W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    private void statusLine(GuiGraphics ms, String label, long until, float x, float y, float w, long now) {
        boolean ok = until > now;
        Gfx.text(ms, label, x, y, 6.2f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, ok ? "valabil până la " + date(until) : until == 0 ? "LIPSĂ" : "EXPIRAT", x, y + 11, 8.6f, ok ? Sk.GOOD : Sk.BAD, 0, true, 0f);
    }

    private void service(GuiGraphics ms, float lmx, float lmy, float x, float y, float w, float h, String label, boolean enabled, int car, String svc) {
        boolean hov = enabled && Sk.in(lmx, lmy, x, y, w, h);
        Sk.button(ms, label, x, y, w, h, enabled, true, hov);
        if (enabled) hitL(x, y, w, h, () -> MecanicClient.service(car, svc));
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
