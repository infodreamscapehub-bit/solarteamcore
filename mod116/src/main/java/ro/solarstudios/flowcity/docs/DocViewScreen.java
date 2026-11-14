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

    private static String sub(String type) {
        return "permis".equals(type) ? "DRIVING LICENCE · PERMIS DE CONDUIRE" : "portarma".equals(type) ? "WEAPON LICENCE · PERMIS DE PORT D'ARME" : "CARTE D'IDENTITÉ · IDENTITY CARD";
    }

    private static net.minecraft.util.ResourceLocation skin(String ownerName) {
        Minecraft mc = Minecraft.getInstance();
        try {
            if (mc.getConnection() != null && ownerName != null && !ownerName.isEmpty()) {
                net.minecraft.client.network.play.NetworkPlayerInfo i = mc.getConnection().getPlayerInfo(ownerName);
                if (i != null) return i.getSkinLocation();
            }
            if (mc.player != null) return mc.player.getSkinTextureLocation();
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void field(GuiGraphics ms, String label, String val, float x, float y, float size, int col) {
        Gfx.text(ms, label, x, y, 4.6f, 0xFF4A6A8A, 0, false, 0.5f);
        Gfx.text(ms, val, x, y + 8.5f, size, col, 0, true, 0f);
    }

    /** Cartela unui document la (x,y), 280 x 177 (proportii ID-1). Fictiva: nu foloseste simboluri oficiale. */
    static void card(GuiGraphics ms, JsonObject d, float x, float y) {
        final float w = 280f;
        final float h = 177f;
        String type = JsonClientModule.str(d, "type", "buletin");
        boolean expired = JsonClientModule.bool(d, "expired", false);
        int ink = 0xFF14202F;
        int accent = "permis".equals(type) ? 0xFF1F6B4A : "portarma".equals(type) ? 0xFF8A3B2E : 0xFF1F4E96;
        Gfx.rrect(ms, x, y, w, h, 9, 0xFFDCE8F2, 0xFFC4D6E6);
        // fundal ghiosat (linii ondulate)
        for (int k = 0; k < 9; k++) {
            float by = y + 30 + k * 16f;
            float px = x + 6;
            float py = by + (float) Math.sin(k * 0.9) * 4f;
            for (float xx = x + 12; xx <= x + w - 6; xx += 9f) {
                float yy = by + (float) Math.sin((xx - x) * 0.07 + k * 0.9) * 4.5f;
                Gfx.line(ms, px, py, xx, yy, 0.6f, (0x38 << 24) | (accent & 0xFFFFFF));
                px = xx;
                py = yy;
            }
        }
        // antet
        Gfx.text(ms, "ROMÂNIA", x + 12, y + 15, 11f, ink, 0, true, 1.4f);
        Gfx.text(ms, sub(type), x + 12, y + 24.5f, 4.8f, accent, 0, true, 0.6f);
        Gfx.text(ms, "ROU", x + w - 12, y + 15, 9f, accent, 2, true, 1f);
        float bw = (w - 24) / 3f;
        Gfx.rrect(ms, x + 12, y + 29, bw, 3, 0, 0xFF002B7F);
        Gfx.rrect(ms, x + 12 + bw, y + 29, bw, 3, 0, 0xFFFCD116);
        Gfx.rrect(ms, x + 12 + 2 * bw, y + 29, bw, 3, 0, 0xFFCE1126);

        // poza
        float px0 = x + 12;
        float py0 = y + 40;
        Gfx.rrect(ms, px0 - 1, py0 - 1, 70, 88, 4, 0xFF7C93AC);
        Gfx.rrect(ms, px0, py0, 68, 86, 3, 0xFFB9CCDF);
        net.minecraft.util.ResourceLocation sk = skin(JsonClientModule.str(d, "owner", ""));
        Gfx.rrect(ms, px0 + 8, py0 + 62, 52, 30, 12, 0xFF2C4A72);
        if (sk != null) {
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, 1f);
            ms.blit(sk, Math.round(px0 + 10), Math.round(py0 + 8), 48, 48, 8f, 8f, 8, 8, 64, 64);
            ms.blit(sk, Math.round(px0 + 10), Math.round(py0 + 8), 48, 48, 40f, 8f, 8, 8, 64, 64);
        } else {
            Gfx.disc(ms, px0 + 34, py0 + 32, 20, 0xFF7C93AC);
        }
        Gfx.rrect(ms, px0, py0 + 90, 68, 0.8f, 0, 0xFF4A6A8A);
        Gfx.text(ms, "SEMNĂTURA / SIGNATURE", px0, py0 + 97, 4f, 0xFF4A6A8A, 0, false, 0.4f);

        float tx = x + 92;
        String cnp = JsonClientModule.str(d, "cnp", "");
        if (!"permis".equals(type) && !"portarma".equals(type)) {
            Gfx.text(ms, "SERIA", tx, y + 40, 4.6f, 0xFF4A6A8A, 0, false, 0.5f);
            Gfx.text(ms, JsonClientModule.str(d, "serie", ""), tx, y + 48.5f, 9f, 0xFFB3261E, 0, true, 1f);
            Gfx.text(ms, "NR", tx + 36, y + 40, 4.6f, 0xFF4A6A8A, 0, false, 0.5f);
            Gfx.text(ms, JsonClientModule.str(d, "numar", ""), tx + 36, y + 48.5f, 9f, 0xFFB3261E, 0, true, 1f);
            Gfx.text(ms, "CNP", tx + 96, y + 40, 4.6f, 0xFF4A6A8A, 0, false, 0.5f);
            Gfx.text(ms, cnp, tx + 96, y + 48.5f, 9f, ink, 0, true, 0.8f);
        } else {
            Gfx.text(ms, "SERIA / NR", tx, y + 40, 4.6f, 0xFF4A6A8A, 0, false, 0.5f);
            Gfx.text(ms, JsonClientModule.str(d, "serie", "") + " " + JsonClientModule.str(d, "numar", ""), tx, y + 48.5f, 9f, 0xFFB3261E, 0, true, 1f);
            String cats = JsonClientModule.str(d, "categories", "");
            if (!cats.isEmpty()) {
                Gfx.text(ms, "CATEGORII / CATEGORIES", tx + 96, y + 40, 4.6f, 0xFF4A6A8A, 0, false, 0.5f);
                Gfx.text(ms, cats, tx + 96, y + 48.5f, 9f, accent, 0, true, 1f);
            }
        }
        field(ms, "Nume / Nom / Last name", JsonClientModule.str(d, "surname", ""), tx, y + 62, 9.5f, ink);
        field(ms, "Prenume / Prénom / First name", JsonClientModule.str(d, "name", ""), tx, y + 82, 9.5f, ink);
        field(ms, "Cetățenie / Nationalité", JsonClientModule.str(d, "citizenship", "Română / ROU"), tx, y + 102, 6.6f, ink);
        String sex = JsonClientModule.str(d, "sex", "");
        if (!sex.isEmpty() && !"-".equals(sex)) field(ms, "Sex", sex, tx + 96, y + 102, 6.6f, ink);
        field(ms, "Loc naștere / Lieu de naissance", JsonClientModule.str(d, "birthPlace", "FlowCity") + ", " + JsonClientModule.str(d, "birth", ""), tx, y + 122, 6.6f, ink);
        String addr = JsonClientModule.str(d, "address", "");
        if (!addr.isEmpty()) field(ms, "Domiciliu / Adresse", addr.length() > 38 ? addr.substring(0, 38) : addr, tx, y + 142, 6.2f, ink);

        // banda de jos (decorativa, NU e MRZ real)
        Gfx.rrect(ms, x, y + h - 20, w, 20, 9, 0xFFC4D6E6);
        Gfx.rrect(ms, x, y + h - 20, w, 8, 0, 0xFFC4D6E6);
        Gfx.text(ms, "EMISĂ DE " + JsonClientModule.str(d, "issuer", "Primăria FlowCity").toUpperCase(), x + 12, y + h - 14, 4.6f, 0xFF4A6A8A, 0, true, 0.5f);
        Gfx.text(ms, "VALABILĂ PÂNĂ " + JsonClientModule.str(d, "expiresStr", ""), x + w - 12, y + h - 14, 5.2f, expired ? Sk.BAD : 0xFF1F6B3F, 2, true, 0.4f);
        Gfx.text(ms, "<<<<" + JsonClientModule.str(d, "serie", "") + "<" + JsonClientModule.str(d, "numar", "") + "<<<<<<<<<<<<<<<<<<<<<<", x + 12, y + h - 5, 5f, 0xFF7C93AC, 0, false, 0.6f);
        Gfx.text(ms, "DOCUMENT DE JOC · FLOWCITY ROLEPLAY", x + w - 12, y + h - 5, 4f, 0xFF7C93AC, 2, false, 0.4f);
        if (expired) {
            Sk.slant(ms, x + w - 96, y + 38, 84, 18, 5f, 0xFF7A2A26);
            Gfx.text(ms, "EXPIRAT", x + w - 54, y + 51, 8.5f, Sk.WHITE, 1, true, 1f);
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
            card(ms, d, 120, 58);
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
