package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** ATM: depunere, retragere, transfer bancar + istoric. Serverul valideaza tot; aici doar cerem actiuni. Tema de iarna FlowCity. */
public final class AtmScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final int COL = 224;

    private final TextFieldWidget amount = field("Suma", 12, null);
    private final TextFieldWidget target = field("Jucator", 16, null);
    private final long born = System.currentTimeMillis();
    private float offX;
    private float offY;
    private float scale = 1f;

    public AtmScreen() {
        super("ATM", W, H);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>();
        l.add(amount);
        l.add(target);
        return l;
    }

    private double parseAmount() {
        try {
            return Double.parseDouble(amount.getValue().trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void act(String action) {
        double a = parseAmount();
        if (a <= 0) {
            toast("Introdu o suma valida.", true);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("amount", a);
        if ("atm_transfer".equals(action)) {
            String t = target.getValue().trim();
            if (t.isEmpty()) {
                toast("Alege un jucator.", true);
                return;
            }
            o.addProperty("to", t);
        }
        click();
        HypeClient.request(action, o);
    }

    private static String label(String type) {
        switch (type) {
            case "DEPOSIT": return "Depunere";
            case "WITHDRAW": return "Retragere";
            case "TRANSFER_OUT": return "Transfer catre";
            case "TRANSFER_IN": return "Primit de la";
            default: return type;
        }
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

    private static String money(double v) {
        long n = Math.round(v);
        String s = String.valueOf(Math.abs(n));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (i > 0 && (s.length() - i) % 3 == 0) b.append(' ');
            b.append(s.charAt(i));
        }
        return (n < 0 ? "-" : "") + b;
    }

    /** Pozitia unui camp de text in coordonate de ecran, pe baza zonei lui din spatiul scalat. */
    private void placeField(TextFieldWidget f, float x, float y, float w, float h) {
        f.x = Math.round(offX + (x + 6) * scale);
        f.y = Math.round(offY + (y + (h - 8) / 2f) * scale);
        f.setWidth(Math.max(10, Math.round((w - 12) * scale)));
    }

    private void fieldBox(GuiGraphics ms, TextFieldWidget f, float x, float y, float w, float h) {
        Sk.slant(ms, x, y, w, h, 4f, Sk.ROW);
        Gfx.rrect(ms, x + 2, y + h - 2, w - 4, 1.5f, 0, f.isFocused() ? Sk.ACC : 0xFF22344D);
        placeField(f, x, y, w, h);
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
        // campurile de text se deseneaza in coordonate de ecran, dupa restaurarea transformarii
        amount.render(ms.pose(), mx, my, 0);
        target.render(ms.pose(), mx, my, 0);
    }

    private void content(GuiGraphics ms, float lmx, float lmy) {
        JsonObject d = HypeState.atm;
        Gfx.text(ms, "ATM", 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "FLOWCITY BANK", 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        float lx = 12;
        float top = 66;

        // solduri
        Sk.slant(ms, lx, top, COL, 46, 8f, Sk.ROW);
        Gfx.rrect(ms, lx + 14, top + 9, 3, 28, 0, Sk.GOOD);
        Gfx.text(ms, "CASH", lx + 24, top + 15, 6f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, money(JsonClientModule.num(d, "cash", 0)) + " lei", lx + 24, top + 31, 10.5f, Sk.GOOD, 0, true, 0f);
        Gfx.rrect(ms, lx + COL / 2f + 4, top + 9, 3, 28, 0, Sk.ACC);
        Gfx.text(ms, "BANCĂ", lx + COL / 2f + 14, top + 15, 6f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, money(JsonClientModule.num(d, "bank", 0)) + " lei", lx + COL / 2f + 14, top + 31, 10.5f, Sk.ACC, 0, true, 0f);

        // suma + depunere/retragere
        float y = top + 56;
        fieldBox(ms, amount, lx, y, COL, 22);
        y += 28;
        float bw = (COL - 8) / 2f;
        boolean h1 = Sk.in(lmx, lmy, lx, y, bw, 24);
        Sk.button(ms, "DEPUNE", lx, y, bw, 24, true, true, h1);
        hitL(lx, y, bw, 24, () -> act("atm_deposit"));
        boolean h2 = Sk.in(lmx, lmy, lx + bw + 8, y, bw, 24);
        Sk.button(ms, "RETRAGE", lx + bw + 8, y, bw, 24, true, true, h2);
        hitL(lx + bw + 8, y, bw, 24, () -> act("atm_withdraw"));
        y += 36;

        // transfer
        Gfx.text(ms, "TRANSFER BANCAR", lx + 2, y, 6.4f, Sk.MUTED, 0, false, 1.2f);
        y += 8;
        fieldBox(ms, target, lx, y, COL, 22);
        y += 28;
        boolean h3 = Sk.in(lmx, lmy, lx, y, COL, 24);
        Sk.button(ms, "TRANSFERĂ", lx, y, COL, 24, true, false, h3);
        hitL(lx, y, COL, 24, () -> act("atm_transfer"));
        y += 34;
        double fp = JsonClientModule.num(d, "feePercent", 0);
        Gfx.text(ms, "Taxa transfer: " + fp + "% (min " + JsonClientModule.num(d, "feeMin", 0) + " lei)", lx + 2, y, 6.6f, Sk.FAINT, 0);
        double wl = JsonClientModule.num(d, "withdrawLeft", -1);
        if (wl >= 0) Gfx.text(ms, "Retragere ramasa azi: " + money(wl) + " lei", lx + 2, y + 10, 6.6f, Sk.FAINT, 0);

        // istoric
        float hx = lx + COL + 14;
        float hw = W - 12 - hx;
        Gfx.text(ms, "ULTIMELE TRANZACȚII", hx + 2, top + 4, 6.4f, Sk.MUTED, 0, false, 1.2f);
        float hy = top + 14;
        int shown = 0;
        if (d.has("history") && d.get("history").isJsonArray()) {
            JsonArray arr = d.getAsJsonArray("history");
            for (JsonElement el : arr) {
                if (shown >= 11 || !el.isJsonObject()) break;
                JsonObject h = el.getAsJsonObject();
                String type = JsonClientModule.str(h, "t", "");
                boolean plus = type.equals("TRANSFER_IN") || type.equals("DEPOSIT");
                String who = JsonClientModule.str(h, "o", "");
                String left = label(type) + (who.isEmpty() ? "" : " " + who);
                Sk.slant(ms, hx, hy, hw, 17, 4f, shown % 2 == 0 ? Sk.ROW : Sk.ROW_HOT);
                Gfx.rrect(ms, hx + 7, hy + 4, 2, 9, 0, plus ? Sk.GOOD : Sk.MUTED);
                Gfx.text(ms, clip(left, 7.4f, false, hw - 96), hx + 14, hy + 5.5f, 7.4f, Sk.WHITE, 0);
                Gfx.text(ms, (type.equals("TRANSFER_IN") ? "+" : "") + money(JsonClientModule.num(h, "a", 0)), hx + hw - 10, hy + 5.5f, 7.4f, plus ? Sk.GOOD : Sk.MUTED, 2);
                hy += 19;
                shown++;
            }
        }
        if (shown == 0) Gfx.text(ms, "Nicio tranzacție.", hx + 2, hy + 8, 8f, Sk.FAINT, 0);
    }

    @Override
    protected void onEnter(TextFieldWidget f) {
        act(f == target ? "atm_transfer" : "atm_deposit");
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
