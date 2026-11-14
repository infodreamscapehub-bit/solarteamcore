package ro.solarstudios.flowcity.houses;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** "Case": lista caselor cu cumparare/inchiriere/vanzare, colegi si teleport. Tema de iarna FlowCity. */
public final class HousesScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final int ROW = 48;

    private static final class House {
        String id;
        String name;
        int buy;
        int rent;
        String state;
        String by;
        int sell;
        String due;
        boolean late;
        final List<String> mates = new ArrayList<>();
    }

    private final List<House> houses = new ArrayList<>();
    private int maxMates = 4;
    private String matesOf = "";
    private int scroll;
    private final long born = System.currentTimeMillis();
    private String msg = "";
    private boolean msgOk;
    private long msgUntil;
    private float offX;
    private float offY;
    private float scale = 1f;

    HousesScreen(JsonObject d) {
        super("Case", W, H);
        update(d);
    }

    void update(JsonObject d) {
        houses.clear();
        maxMates = JsonClientModule.integer(d, "maxMates", 4);
        JsonArray a = d.has("houses") && d.get("houses").isJsonArray() ? d.getAsJsonArray("houses") : new JsonArray();
        for (JsonElement e : a) {
            JsonObject o = e.getAsJsonObject();
            House h = new House();
            h.id = JsonClientModule.str(o, "id", "");
            h.name = JsonClientModule.str(o, "name", h.id);
            h.buy = JsonClientModule.integer(o, "buy", 0);
            h.rent = JsonClientModule.integer(o, "rent", 0);
            h.state = JsonClientModule.str(o, "state", "free");
            h.by = JsonClientModule.str(o, "by", "");
            h.sell = JsonClientModule.integer(o, "sell", 0);
            h.due = JsonClientModule.str(o, "due", "");
            h.late = JsonClientModule.bool(o, "late", false);
            if (o.has("mates") && o.get("mates").isJsonArray()) {
                for (JsonElement m : o.getAsJsonArray("mates")) h.mates.add(m.getAsString());
            }
            houses.add(h);
        }
        boolean still = false;
        for (House h : houses) if (h.id.equals(matesOf)) still = true;
        if (!still) matesOf = "";
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

    private static String money(long v) {
        String s = String.valueOf(v);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return b.toString();
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private int visibleRows() {
        return (int) ((H - 12 - 66) / ROW);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, Math.min(Math.max(0, houses.size() - visibleRows()), scroll - (int) Math.signum(delta)));
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
        Gfx.text(ms, "CASE ȘI CHIRII", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "AGENȚIA IMOBILIARĂ FLOWCITY", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        if (!matesOf.isEmpty()) {
            matesView(ms, lmx, lmy);
        } else if (houses.isEmpty()) {
            Gfx.text(ms, "Nu există case definite.", W / 2f, H / 2f - 2, 11, Sk.WHITE, 1, true, 0f);
        } else {
            list(ms, lmx, lmy);
        }

        if (System.currentTimeMillis() < msgUntil && !msg.isEmpty()) {
            float w = Math.min(W - 60, Gfx.width(msg, 8f, false) + 28);
            Sk.slant(ms, W / 2f - w / 2f, 12, w, 20, 6f, msgOk ? 0xFF1F6B3F : 0xFF7A2A26);
            Gfx.text(ms, msg, W / 2f, 25.5f, 8f, Sk.WHITE, 1);
        }
    }

    private void list(GuiGraphics ms, float lmx, float lmy) {
        float top = 66f;
        int visible = visibleRows();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, houses.size() - visible)));
        float y = top;
        for (int i = scroll; i < Math.min(houses.size(), scroll + visible); i++) {
            final House h = houses.get(i);
            boolean mine = "owner".equals(h.state) || "tenant".equals(h.state);
            int accent = "free".equals(h.state) ? Sk.GOOD : "taken".equals(h.state) ? Sk.BAD : Sk.ACC;
            Sk.slant(ms, 12, y, W - 36, ROW - 6, 8f, Sk.ROW);
            Gfx.rrect(ms, 26, y + 8, 3, ROW - 22, 0, accent);
            Gfx.text(ms, clip(h.name, 9.4f, true, 200), 38, y + 15, 9.4f, Sk.WHITE, 0, true, 0f);
            String status;
            if ("free".equals(h.state)) status = "Liberă  ·  " + money(h.buy) + " lei  ·  chirie " + money(h.rent) + " lei";
            else if ("owner".equals(h.state)) status = "A ta (proprietar)  ·  vânzare " + money(h.sell) + " lei";
            else if ("tenant".equals(h.state)) status = (h.late ? "Chirie restantă!  ·  " : "Chiriaș  ·  ") + money(h.rent) + " lei, scadent " + h.due;
            else if ("mate".equals(h.state)) status = "Coleg de casă la " + h.by;
            else status = "Ocupată  ·  " + h.by;
            Gfx.text(ms, clip(status, 6.6f, false, 238), 38, y + 28, 6.6f, h.late ? Sk.BAD : Sk.MUTED, 0);

            float bx = W - 24 - 8;
            if ("free".equals(h.state)) {
                bx -= 74;
                boolean hv = Sk.in(lmx, lmy, bx, y + 7, 74, 26);
                Sk.button(ms, "CUMPĂRĂ", bx, y + 7, 74, 26, true, true, hv);
                hitL(bx, y + 7, 74, 26, () -> HousesClient.act("buy", h.id));
                bx -= 78;
                hv = Sk.in(lmx, lmy, bx, y + 7, 74, 26);
                Sk.button(ms, "ÎNCHIRIAZĂ", bx, y + 7, 74, 26, true, false, hv);
                hitL(bx, y + 7, 74, 26, () -> HousesClient.act("rent", h.id));
            } else if (mine) {
                bx -= 64;
                boolean hv = Sk.in(lmx, lmy, bx, y + 7, 64, 26);
                Sk.button(ms, "owner".equals(h.state) ? "VINDE" : "RENUNȚĂ", bx, y + 7, 64, 26, true, false, hv);
                hitL(bx, y + 7, 64, 26, () -> HousesClient.act("sell", h.id));
                bx -= 68;
                hv = Sk.in(lmx, lmy, bx, y + 7, 64, 26);
                Sk.button(ms, "COLEGI", bx, y + 7, 64, 26, true, false, hv);
                hitL(bx, y + 7, 64, 26, () -> matesOf = h.id);
                bx -= 68;
                hv = Sk.in(lmx, lmy, bx, y + 7, 64, 26);
                Sk.button(ms, "INTRĂ", bx, y + 7, 64, 26, true, true, hv);
                hitL(bx, y + 7, 64, 26, () -> HousesClient.act("tp", h.id));
            } else if ("mate".equals(h.state)) {
                bx -= 64;
                boolean hv = Sk.in(lmx, lmy, bx, y + 7, 64, 26);
                Sk.button(ms, "INTRĂ", bx, y + 7, 64, 26, true, true, hv);
                hitL(bx, y + 7, 64, 26, () -> HousesClient.act("tp", h.id));
            }
            y += ROW;
        }
        if (houses.size() > visible) {
            float trackH = H - 12 - top;
            float th = Math.max(14f, trackH * visible / houses.size());
            float ty = top + (trackH - th) * scroll / Math.max(1, houses.size() - visible);
            Gfx.rrect(ms, W - 14, top, 2, trackH, 0, 0xFF14202F);
            Gfx.rrect(ms, W - 14, ty, 2, th, 0, Sk.ACC);
        }
    }

    private void matesView(GuiGraphics ms, float lmx, float lmy) {
        House h = null;
        for (House x : houses) if (x.id.equals(matesOf)) h = x;
        if (h == null) {
            matesOf = "";
            return;
        }
        final House fh = h;
        Gfx.text(ms, "COLEGI · " + clip(h.name.toUpperCase(), 9f, true, 260), 16, 74, 9f, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, h.mates.size() + " / " + maxMates + " colegi cu acces în casă", 16, 86, 6.6f, Sk.MUTED, 0);
        float y = 98f;
        for (final String n : h.mates) {
            Sk.slant(ms, 12, y, W - 36, 30, 7f, Sk.ROW);
            Gfx.text(ms, n, 28, y + 18, 9f, Sk.WHITE, 0, true, 0f);
            boolean hv = Sk.in(lmx, lmy, W - 112, y + 4, 74, 22);
            Sk.button(ms, "SCOATE", W - 112, y + 4, 74, 22, true, false, hv);
            hitL(W - 112, y + 4, 74, 22, () -> HousesClient.removeMate(fh.id, n));
            y += 34;
            if (y > H - 70) break;
        }
        if (h.mates.isEmpty()) Gfx.text(ms, "Niciun coleg adăugat.", 28, y + 10, 8, Sk.FAINT, 0);
        boolean hv = Sk.in(lmx, lmy, 12, H - 40, 200, 26);
        Sk.button(ms, "ADAUGĂ JUCĂTORUL DIN APROPIERE", 12, H - 40, 200, 26, h.mates.size() < maxMates, true, hv);
        if (h.mates.size() < maxMates) hitL(12, H - 40, 200, 26, () -> HousesClient.act("mate_add", fh.id));
        hv = Sk.in(lmx, lmy, W - 112, H - 40, 90, 26);
        Sk.button(ms, "ÎNAPOI", W - 112, H - 40, 90, 26, true, false, hv);
        hitL(W - 112, H - 40, 90, 26, () -> matesOf = "");
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
