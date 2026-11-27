package ro.solarstudios.flowcity.docs;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Un document (buletin / permis / port-arma) desenat ca o cartela realista, fara texturi: ghiosat fin, poza mare cu rama,
 * sigiliu holografic si o banda de luciu care trece in diagonala. Tema de iarna FlowCity. Document fictiv de joc.
 */
public final class DocViewScreen extends FlowScreen {
    private static final int W = 520;
    private static final int H = 290;
    private static final float CARD_W = 280f;
    private static final float CARD_H = 177f;

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

    private static int accentOf(String type) {
        return "permis".equals(type) ? 0xFF1F6B4A : "portarma".equals(type) ? 0xFF8A3B2E : 0xFF1F4E96;
    }

    private static int argb(int a, int rgb) {
        return (Math.max(0, Math.min(255, a)) << 24) | (rgb & 0xFFFFFF);
    }

    private static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private static ResourceLocation skin(String ownerName) {
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

    // ------------------------------------------------------------------ desen cartela

    /** Camp cu eticheta mica deasupra (baza etichetei = y) si valoare dedesubt. */
    private static void lv(GuiGraphics ms, String label, String val, float x, float y, float size, int col) {
        Gfx.text(ms, label, x, y, 4.3f, 0xFF4A6A8A, 0, false, 0.4f);
        Gfx.text(ms, val, x, y + 8.6f, size, col, 0, true, 0f);
    }

    /** Fulg de nea din 3 axe cu ramuri (decor). */
    private static void flake(GuiGraphics ms, float cx, float cy, float r, float w, int c) {
        for (int i = 0; i < 3; i++) {
            double a = Math.PI * i / 3.0;
            float dx = (float) Math.cos(a) * r;
            float dy = (float) Math.sin(a) * r;
            Gfx.line(ms, cx - dx, cy - dy, cx + dx, cy + dy, w, c);
            for (int e = -1; e <= 1; e += 2) {
                double out = a + (e < 0 ? Math.PI : 0.0);
                float bx = cx + (float) Math.cos(out) * r * 0.62f;
                float by = cy + (float) Math.sin(out) * r * 0.62f;
                for (int k = -1; k <= 1; k += 2) {
                    double b = out + k * 0.8;
                    Gfx.line(ms, bx, by, bx + (float) Math.cos(b) * r * 0.3f, by + (float) Math.sin(b) * r * 0.3f, w, c);
                }
            }
        }
    }

    /** Culoare iridescenta (holograma) in functie de faza p. */
    private static int holo(float p, int a) {
        int r = 160 + (int) (95 * Math.sin(p));
        int g = 170 + (int) (85 * Math.sin(p + 2.1));
        int b = 200 + (int) (55 * Math.sin(p + 4.2));
        return (Math.max(0, Math.min(255, a)) << 24) | (r << 16) | (g << 8) | b;
    }

    private static boolean inside(float x, float y, int e, float v) {
        switch (e) {
            case 0: return x >= v;
            case 1: return x <= v;
            case 2: return y >= v;
            default: return y <= v;
        }
    }

    /** Taie un poligon convex cu un semiplan (e: 0 x>=v, 1 x<=v, 2 y>=v, 3 y<=v). */
    private static float[] clipEdge(float[] in, int e, float v) {
        if (in.length < 6) return in;
        float[] out = new float[in.length + 4];
        int n = 0;
        int cnt = in.length / 2;
        for (int i = 0; i < cnt; i++) {
            float ax = in[2 * i];
            float ay = in[2 * i + 1];
            int j = (i + 1) % cnt;
            float bx = in[2 * j];
            float by = in[2 * j + 1];
            boolean ia = inside(ax, ay, e, v);
            boolean ib = inside(bx, by, e, v);
            if (ia) {
                out[n++] = ax;
                out[n++] = ay;
            }
            if (ia != ib) {
                float t = e < 2 ? (v - ax) / (bx - ax) : (v - ay) / (by - ay);
                out[n++] = ax + (bx - ax) * t;
                out[n++] = ay + (by - ay) * t;
            }
        }
        return Arrays.copyOf(out, n);
    }

    private static float[] clipRect(float[] p, float x0, float y0, float x1, float y1) {
        float[] r = clipEdge(p, 0, x0);
        r = clipEdge(r, 1, x1);
        r = clipEdge(r, 2, y0);
        return clipEdge(r, 3, y1);
    }

    /** O banda diagonala (paralelogram) cu centrul jos la cx, taiata de dreptunghiul cartelei. */
    private static void band(GuiGraphics ms, float x, float y, float w, float h, float cx, float shear, float half, int color) {
        float[] p = {cx - half + shear, y, cx + half + shear, y, cx + half, y + h, cx - half, y + h};
        float[] c = clipRect(p, x + 1.5f, y + 1.5f, x + w - 1.5f, y + h - 1.5f);
        if (c.length >= 6) Gfx.poly(ms, c, color);
    }

    /** Banda de luciu holografic care traverseaza cartela in diagonala, in bucla. */
    private static void sheen(GuiGraphics ms, float x, float y, float w, float h, long now, float strength) {
        float u = (now % 4200L) / 4200f;
        float shear = h * 0.55f;
        float span = w + shear + 120f;
        float bx = x - 60f + u * span;
        float ph = u * 6.2832f;
        band(ms, x, y, w, h, bx, shear, 34f, argb((int) (0x18 * strength), 0xB8E6FF));
        band(ms, x, y, w, h, bx, shear, 20f, argb((int) (0x26 * strength), 0xFFFFFF));
        band(ms, x, y, w, h, bx, shear, 7f, argb((int) (0x44 * strength), 0xFFFFFF));
        band(ms, x, y, w, h, bx - 24f, shear, 2.5f, holo(ph, (int) (0x44 * strength)));
        band(ms, x, y, w, h, bx + 24f, shear, 2.5f, holo(ph + 2f, (int) (0x44 * strength)));
    }

    /**
     * Cartela unui document la (x,y), 280 x 177 (proportii ID-1). Fictiva: nu foloseste simboluri oficiale, nu are MRZ real.
     */
    static void card(GuiGraphics ms, JsonObject d, float x, float y) {
        card(ms, d, x, y, System.currentTimeMillis());
    }

    static void card(GuiGraphics ms, JsonObject d, float x, float y, long now) {
        final float w = CARD_W;
        final float h = CARD_H;
        String type = JsonClientModule.str(d, "type", "buletin");
        boolean expired = JsonClientModule.bool(d, "expired", false);
        boolean permis = "permis".equals(type);
        boolean arma = "portarma".equals(type);
        int ink = 0xFF14202F;
        int label = 0xFF4A6A8A;
        int accent = accentOf(type);
        int accentDk = permis ? 0xFF124A33 : arma ? 0xFF5E241B : 0xFF123A78;
        int paperA = permis ? 0xFFE4F2EA : arma ? 0xFFF4E8E3 : 0xFFE6F0F9;
        int paperB = permis ? 0xFFC5DDD0 : arma ? 0xFFDCC7BF : 0xFFC4D8EA;
        int bandCol = permis ? 0xFFB3CFBF : arma ? 0xFFCFB5AB : 0xFFB2C9DE;

        // halou de gheata in jurul cartelei si corpul ei
        Gfx.rrect(ms, x - 2, y - 2, w + 4, h + 4, 11, 0x30FFFFFF);
        Gfx.rrect(ms, x, y, w, h, 9, paperA, paperB);

        // ghiosat fin: doua familii de unde care se intretaie + rozeta
        int g1 = argb(0x30, accent);
        for (int k = 0; k < 10; k++) {
            float by = y + 32f + k * 12.5f;
            float px = x + 6f;
            float py = by + (float) Math.sin(k * 0.8) * 4f;
            for (float xx = x + 16f; xx <= x + w - 6f; xx += 10f) {
                float yy = by + (float) Math.sin((xx - x) * 0.09 + k * 0.8) * 4f;
                Gfx.line(ms, px, py, xx, yy, 0.5f, g1);
                px = xx;
                py = yy;
            }
        }
        int g2 = argb(0x1E, accentDk);
        for (int k = 0; k < 5; k++) {
            float by = y + 38f + k * 24f;
            float px = x + 6f;
            float py = by + (float) Math.sin(-k * 1.3) * 6f;
            for (float xx = x + 16f; xx <= x + w - 6f; xx += 10f) {
                float yy = by + (float) Math.sin((xx - x) * 0.13 - k * 1.3) * 6f;
                Gfx.line(ms, px, py, xx, yy, 0.5f, g2);
                px = xx;
                py = yy;
            }
        }
        int rc = argb(0x18, accent);
        for (int i = 0; i < 4; i++) {
            float r = 22f + i * 8f;
            Gfx.ring(ms, x + 196f, y + 92f, r - 0.4f, r + 0.4f, rc, rc);
        }

        // antet colorat
        Gfx.rrect(ms, x, y, w, 26, 9, accent, accentDk);
        Gfx.rrect(ms, x, y + 14, w, 12, 0, accent, accentDk);
        Gfx.rrect(ms, x, y + 26, w, 1.2f, 0, 0x90FFFFFF);
        boolean ro = !permis && !arma;
        if (ro) {
            // banda tricolor pe marginea de sus (doar culori, fara embleme oficiale)
            float sw = (w - 18f) / 3f;
            Gfx.rrect(ms, x + 9f, y, sw, 3f, 0, 0xFF2B4AA8);
            Gfx.rrect(ms, x + 9f + sw, y, sw, 3f, 0, 0xFFFCD116);
            Gfx.rrect(ms, x + 9f + 2f * sw, y, sw, 3f, 0, 0xFFCE1126);
            Gfx.text(ms, "ROMÂNIA", x + 12, y + 13, 10.5f, 0xFFFFFFFF, 0, true, 1.6f);
            Gfx.text(ms, "CARTE DE IDENTITATE / CARTE D'IDENTITÉ / IDENTITY CARD", x + 12, y + 21.5f, 4.4f, 0xFFD6E6F7, 0, true, 0.4f);
        } else {
            Gfx.text(ms, "FLOWCITY", x + 12, y + 13, 10.5f, 0xFFFFFFFF, 0, true, 1.6f);
            Gfx.text(ms, sub(type), x + 12, y + 21.5f, 4.6f, 0xFFD6E6F7, 0, true, 0.5f);
        }
        flake(ms, x + w - 18, y + 13, 8f, 0.9f, 0xE0FFFFFF);

        // poza mare cu rama dubla
        float px0 = x + 11;
        float py0 = y + 34;
        Gfx.rrect(ms, px0 - 2, py0 - 2, 78, 100, 5, accentDk);
        Gfx.rrect(ms, px0 - 1, py0 - 1, 76, 98, 4, 0xFFF4F9FD);
        Gfx.rrect(ms, px0, py0, 74, 96, 3, 0xFFC3D6E8, 0xFF9DB8D2);
        Gfx.rrect(ms, px0 + 8, py0 + 70, 58, 26, 12, 0xFF2C4A72);
        ResourceLocation sk = skin(JsonClientModule.str(d, "owner", ""));
        if (sk != null) {
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.setShaderColor(1f, 1f, 1f, Gfx.alpha);
            ms.blit(sk, Math.round(px0 + 9), Math.round(py0 + 10), 56, 56, 8f, 8f, 8, 8, 64, 64);
            ms.blit(sk, Math.round(px0 + 9), Math.round(py0 + 10), 56, 56, 40f, 8f, 8, 8, 64, 64);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        } else {
            Gfx.disc(ms, px0 + 37, py0 + 38, 22, 0xFF7C93AC);
        }
        Gfx.rrect(ms, px0, py0 + 104, 74, 0.8f, 0, label);
        Gfx.text(ms, "SEMNĂTURA / SIGNATURE", px0, py0 + 111, 3.8f, label, 0, false, 0.4f);

        // campuri bilingve
        float tx = x + 96;
        String cnp = JsonClientModule.str(d, "cnp", "");
        if (!permis && !arma) {
            lv(ms, "SERIA / SÉRIE", JsonClientModule.str(d, "serie", ""), tx, y + 38, 8.6f, 0xFFB3261E);
            lv(ms, "NR / NO.", JsonClientModule.str(d, "numar", ""), tx + 52, y + 38, 8.6f, 0xFFB3261E);
            lv(ms, "CNP", cnp, tx + 104, y + 38, 8.6f, ink);
        } else {
            lv(ms, "SERIA NR / SERIES NO.", JsonClientModule.str(d, "serie", "") + " " + JsonClientModule.str(d, "numar", ""), tx, y + 38, 8.6f, 0xFFB3261E);
            String cats = JsonClientModule.str(d, "categories", "");
            if (!cats.isEmpty()) lv(ms, "CATEGORII / CATEGORIES", cats, tx + 104, y + 38, 8.6f, accent);
        }
        boolean ro2 = !permis && !arma;
        lv(ms, ro2 ? "NUME / NOM / LAST NAME" : "NUME / SURNAME", clip(JsonClientModule.str(d, "surname", ""), 9.5f, true, 170f), tx, y + 58, 9.5f, ink);
        lv(ms, ro2 ? "PRENUME / PRÉNOM / FIRST NAME" : "PRENUME / GIVEN NAME", clip(JsonClientModule.str(d, "name", ""), 9.5f, true, 170f), tx, y + 78, 9.5f, ink);
        lv(ms, ro2 ? "CETĂȚENIE / NATIONALITÉ" : "CETĂȚENIE / NATIONALITY", JsonClientModule.str(d, "citizenship", "Română / ROU"), tx, y + 98, 6.4f, ink);
        String sex = JsonClientModule.str(d, "sex", "");
        if (!sex.isEmpty() && !"-".equals(sex)) lv(ms, "SEX / SEXE", sex, tx + 104, y + 98, 6.4f, ink);
        lv(ms, ro2 ? "LOC NAȘTERE / LIEU DE NAISSANCE" : "LOC NAȘTERE / PLACE OF BIRTH", clip(JsonClientModule.str(d, "birthPlace", "FlowCity") + ", " + JsonClientModule.str(d, "birth", ""), 6.4f, true, 170f), tx, y + 118, 6.4f, ink);
        String addr = JsonClientModule.str(d, "address", "");
        if (!addr.isEmpty()) lv(ms, ro2 ? "DOMICILIU / ADRESSE" : "DOMICILIU / ADDRESS", clip(addr, 6.2f, true, 140f), tx, y + 138, 6.2f, ink);

        // sigiliu holografic (sau stampila daca documentul a expirat)
        float sx = x + 250f;
        float sy = y + 130f;
        if (!expired) {
            float ph = now / 900f;
            Gfx.disc(ms, sx, sy, 14f, holo(ph, 0x40));
            Gfx.ring(ms, sx, sy, 11.5f, 12.6f, holo(ph + 1.5f, 0x90), holo(ph + 1.5f, 0x90));
            Gfx.ring(ms, sx, sy, 7.5f, 8.4f, holo(ph + 3f, 0x80), holo(ph + 3f, 0x80));
            flake(ms, sx, sy, 6f, 0.7f, holo(ph + 4.5f, 0xB0));
        } else {
            Sk.slant(ms, x + w - 104, y + 120, 92, 20, 5f, 0xFF7A2A26);
            Gfx.text(ms, "EXPIRAT / EXPIRED", x + w - 56, y + 133.5f, 6.4f, Sk.WHITE, 1, true, 0.6f);
        }

        // banda de jos
        Gfx.rrect(ms, x, y + h - 21, w, 21, 9, bandCol);
        Gfx.rrect(ms, x, y + h - 21, w, 9, 0, bandCol);
        Gfx.text(ms, (ro ? "EMISĂ DE / DÉLIVRÉE PAR " : "EMISĂ DE ") + JsonClientModule.str(d, "issuer", "Primăria FlowCity").toUpperCase(), x + 12, y + h - 12.5f, ro ? 4.2f : 4.6f, label, 0, true, 0.4f);
        Gfx.text(ms, "VALABIL / VALID: " + JsonClientModule.str(d, "expiresStr", ""), x + w - 12, y + h - 12.5f, 4.8f, expired ? Sk.BAD : 0xFF1F6B3F, 2, true, 0.4f);
        // zigzag decorativ (NU e MRZ)
        int zz = argb(0x70, accent);
        for (int i = 0; i < 27; i++) {
            Sk.slant(ms, x + 12 + i * 5.4f, y + h - 8.5f, 3.6f, 3.4f, 1.4f, (i & 1) == 0 ? zz : argb(0x38, accent));
        }
        Gfx.text(ms, "DOCUMENT DE JOC · FLOWCITY ROLEPLAY", x + w - 12, y + h - 4.8f, 3.8f, 0xFF6B84A0, 2, false, 0.4f);

        // luciu holografic peste tot
        sheen(ms, x, y, w, h, now, expired ? 0.5f : 1f);
    }

    // ------------------------------------------------------------------ ecran

    private static void frost(GuiGraphics ms, float x, float y, float w, float h) {
        Gfx.rrect(ms, x, y, w, h, 7, Gfx.fade(0xFFBFD9F5, 0.11f), Gfx.fade(0xFF8FB5E0, 0.05f));
        Gfx.rrect(ms, x + 5, y, w - 10, 1f, 0, 0x40FFFFFF);
        Gfx.rrect(ms, x, y + 5, 1f, h - 10, 0, 0x22FFFFFF);
        Gfx.rrect(ms, x + w - 1, y + 5, 1f, h - 10, 0, 0x16FFFFFF);
        Gfx.rrect(ms, x + 5, y + h - 1, w - 10, 1f, 0, 0x16FFFFFF);
    }

    private void btn(GuiGraphics ms, String label, float x, float y, float w, float h, boolean filled, float lmx, float lmy, Runnable r) {
        boolean hov = Sk.in(lmx, lmy, x, y, w, h);
        Sk.button(ms, label, x, y, w, h, true, filled, hov);
        hitL(x, y, w, h, r);
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        long now = System.currentTimeMillis();
        float open = easeOut((now - born) / 300f);
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
            content(ms, lmx, lmy, now);
        } finally {
            Gfx.alpha = 1f;
            ms.pose().popPose();
        }
    }

    private void content(GuiGraphics ms, float lmx, float lmy, long now) {
        final boolean self = JsonClientModule.bool(d, "self", true);
        final String type = JsonClientModule.str(d, "type", "buletin");
        final String owner = JsonClientModule.str(d, "owner", "");
        final boolean expired = JsonClientModule.bool(d, "expired", false);
        int accent = accentOf(type);

        Gfx.text(ms, title(type), 16, 38, 17, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, self ? "ACTELE TALE" : "ARĂTAT DE " + owner.toUpperCase(), 17, 50, 6.4f, Sk.MUTED, 0, false, 1.2f);
        Sk.close(ms, W - 34, 14, lmx, lmy);
        hitL(W - 34, 14, 20, 20, this::onClose);

        // cartela: aluneca din dreapta-jos si se roteste usor pana se aseaza
        float e = easeOut((now - born) / 750f);
        float s = 1.28f;
        float cw = CARD_W * s;
        float ch = CARD_H * s;
        float cx0 = 16f + cw / 2f;
        float cy0 = 58f + ch / 2f;
        Gfx.scrim(ms, cx0, cy0 + ch / 2f + 4f, cw * 0.46f, 12f, 0x66000000);
        ms.pose().pushPose();
        try {
            ms.pose().translate(cx0 + (1f - e) * 140f, cy0 + (1f - e) * 26f, 0);
            ms.pose().mulPose(com.mojang.math.Axis.ZP.rotationDegrees(-(1f - e) * 11f + (float) Math.sin(now / 1300.0) * 0.45f * e));
            ms.pose().scale(s, s, 1f);
            card(ms, d, -CARD_W / 2f, -CARD_H / 2f, now);
        } finally {
            ms.pose().popPose();
        }

        // panou lateral inghetat
        float px = 388f;
        float pw = 120f;
        frost(ms, px, 62, pw, 216);
        Gfx.rrect(ms, px + 14, 66, 28, 2, 0, accent);
        Gfx.text(ms, "DOCUMENT", px + 14, 84, 5.6f, Sk.FAINT, 0, false, 1.2f);
        Gfx.text(ms, clip(title(type), 7.6f, true, pw - 26), px + 14, 96, 7.6f, Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "STARE", px + 14, 118, 5.6f, Sk.FAINT, 0, false, 1.2f);
        Sk.slant(ms, px + 14, 123, pw - 28, 16, 4f, expired ? 0xFF7A2A26 : 0xFF1F6B3F);
        Gfx.text(ms, expired ? "EXPIRAT" : "VALID", px + pw / 2f + 1, 134, 7f, Sk.WHITE, 1, true, 0.8f);
        Gfx.text(ms, "VALABIL PÂNĂ", px + 14, 154, 5.6f, Sk.FAINT, 0, false, 1.2f);
        Gfx.text(ms, JsonClientModule.str(d, "expiresStr", "-"), px + 14, 166, 8.4f, expired ? Sk.BAD : Sk.WHITE, 0, true, 0f);
        Gfx.text(ms, "TITULAR", px + 14, 184, 5.6f, Sk.FAINT, 0, false, 1.2f);
        String who = (JsonClientModule.str(d, "name", "") + " " + JsonClientModule.str(d, "surname", "")).trim();
        if (who.isEmpty()) who = owner;
        Gfx.text(ms, clip(who, 7.6f, true, pw - 26), px + 14, 195, 7.6f, Sk.WHITE, 0, true, 0f);

        if (self) {
            btn(ms, "ARATĂ", px + 10, 206, pw - 20, 28, true, lmx, lmy, () -> {
                DocsClient.showNearest(type);
                onClose();
            });
            Gfx.text(ms, "celui din față", px + pw / 2f, 245, 5.4f, Sk.FAINT, 1);
            btn(ms, "ÎNCHIDE", px + 10, 250, pw - 20, 22, false, lmx, lmy, this::onClose);
        } else {
            Gfx.text(ms, "Document arătat", px + pw / 2f, 218, 6f, Sk.MUTED, 1);
            Gfx.text(ms, "de " + clip(owner, 6f, false, pw - 40), px + pw / 2f, 228, 6f, Sk.MUTED, 1);
            btn(ms, "ÎNCHIDE", px + 10, 246, pw - 20, 26, true, lmx, lmy, this::onClose);
        }
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }
}
