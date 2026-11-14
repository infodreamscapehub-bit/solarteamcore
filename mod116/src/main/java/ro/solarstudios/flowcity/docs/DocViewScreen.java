package ro.solarstudios.flowcity.docs;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Un document (buletin / permis / port-arma) desenat ca o cartela, fara texturi. */
public final class DocViewScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;

    private final JsonObject d;
    private final long born = System.currentTimeMillis();
    private float offX;
    private float offY;
    private float scale = 1f;

    DocViewScreen(JsonObject d) {
        super("Document", W, H);
        this.d = d;
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

    static String title(String type) {
        return "permis".equals(type) ? "PERMIS DE CONDUCERE" : "portarma".equals(type) ? "PORT-ARMĂ" : "CARTE DE IDENTITATE";
    }

    /** Cartela unui document la (x,y), 300 x 170. */
    static void card(GuiGraphics ms, JsonObject d, float x, float y) {
        String type = JsonClientModule.str(d, "type", "buletin");
        boolean expired = JsonClientModule.bool(d, "expired", false);
        int band = "permis".equals(type) ? 0xFF2E7D5B : "portarma".equals(type) ? 0xFF8A3B2E : 0xFF2C6FB8;
        Sk.slant(ms, x, y, 300, 170, 10f, 0xFF14202F);
        Sk.slant(ms, x, y, 300, 26, 10f, band);
        Gfx.text(ms, title(type), x + 16, y + 17, 9f, Sk.WHITE, 0, true, 1f);
        Gfx.text(ms, "ROMÂNIA · FLOW CITY", x + 286, y + 16.5f, 6f, 0xFFDCE6F2, 2, false, 1f);

        // "poza"
        Gfx.rrect(ms, x + 16, y + 38, 62, 78, 4, 0xFF0C1622);
        Gfx.disc(ms, x + 47, y + 62, 13, 0xFF2A3B52);
        Gfx.rrect(ms, x + 29, y + 80, 36, 28, 10, 0xFF2A3B52);

        float tx = x + 92;
        Gfx.text(ms, "NUME", tx, y + 44, 5.4f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, JsonClientModule.str(d, "surname", ""), tx, y + 55, 9.5f, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "PRENUME", tx, y + 68, 5.4f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, JsonClientModule.str(d, "name", ""), tx, y + 79, 9.5f, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "NĂSCUT", tx, y + 92, 5.4f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, JsonClientModule.str(d, "birth", ""), tx, y + 102, 8f, Sk.WHITE, 0);
        String sex = JsonClientModule.str(d, "sex", "");
        if (!sex.isEmpty() && !"-".equals(sex)) {
            Gfx.text(ms, "SEX", tx + 70, y + 92, 5.4f, Sk.FAINT, 0, false, 1f);
            Gfx.text(ms, sex, tx + 70, y + 102, 8f, Sk.WHITE, 0);
        }
        String cats = JsonClientModule.str(d, "categories", "");
        if (!cats.isEmpty()) {
            Gfx.text(ms, "CATEGORII", tx + 110, y + 92, 5.4f, Sk.FAINT, 0, false, 1f);
            Gfx.text(ms, cats, tx + 110, y + 102, 8f, Sk.ACC, 0, true, 0f);
        }
        String addr = JsonClientModule.str(d, "address", "");
        if (!addr.isEmpty()) {
            Gfx.text(ms, "DOMICILIU", x + 16, y + 126, 5.4f, Sk.FAINT, 0, false, 1f);
            Gfx.text(ms, addr, x + 16, y + 136, 7.2f, Sk.WHITE, 0);
        }
        Gfx.rrect(ms, x + 16, y + 144, 268, 1, 0, 0xFF22344D);
        Gfx.text(ms, "SERIA / NR", x + 16, y + 154, 5.4f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, JsonClientModule.str(d, "serie", "") + " " + JsonClientModule.str(d, "numar", ""), x + 16, y + 164, 8.5f, Sk.WHITE, 0, true, 1f);
        Gfx.text(ms, "EMIS " + JsonClientModule.str(d, "issuedStr", ""), x + 150, y + 154, 5.4f, Sk.FAINT, 0, false, 1f);
        Gfx.text(ms, "VALABIL PÂNĂ " + JsonClientModule.str(d, "expiresStr", ""), x + 150, y + 164, 7.2f, expired ? Sk.BAD : Sk.GOOD, 0, true, 0.4f);
        if (expired) {
            Sk.slant(ms, x + 196, y + 38, 88, 20, 5f, 0xFF7A2A26);
            Gfx.text(ms, "EXPIRAT", x + 240, y + 52, 9f, Sk.WHITE, 1, true, 1f);
        }
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
            boolean self = JsonClientModule.bool(d, "self", true);
            String owner = JsonClientModule.str(d, "owner", "");
            Gfx.text(ms, "DOCUMENT", 16, 38, 17, Sk.WHITE, 0, true, 0f);
            Gfx.text(ms, self ? "ACTELE TALE" : "ARĂTAT DE " + owner.toUpperCase(), 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
            Sk.close(ms, W - 34, 14, lmx, lmy);
            hitL(W - 34, 14, 20, 20, this::onClose);
            card(ms, d, 110, 64);
            if (self) {
                final String type = JsonClientModule.str(d, "type", "buletin");
                boolean h = Sk.in(lmx, lmy, 160, 246, 200, 24);
                Sk.button(ms, "ARATĂ CELUI DIN FAȚĂ", 160, 246, 200, 24, true, true, h);
                hitL(160, 246, 200, 24, () -> {
                    DocsClient.showNearest(type);
                    onClose();
                });
            }
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
