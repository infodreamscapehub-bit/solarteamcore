package ro.solarstudios.flowcity.menu;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.Util;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.fml.loading.moddiscovery.ModInfo;
import net.minecraftforge.fml.packs.ResourcePackLoader;
import net.minecraftforge.forgespi.language.IModInfo;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Lista de moduri FlowCity (inlocuieste lista vanilla de la Forge): cautare, lista animata cu avatare colorate,
 * panou de detalii cu logo-ul modului, autori, descriere, configurare si folderul de moduri.
 */
public final class FlowModsScreen extends FlowScreen {
    private static final int[] AVATAR = {0xFFE9A23B, 0xFF4F8FE0, 0xFF3BB8B0, 0xFFE25046, 0xFF9B7BE0, 0xFF4CC878, 0xFFE07BB5, 0xFFC9A86B};

    private final Screen parent;
    private final TextFieldWidget search;
    private final List<IModInfo> all = new ArrayList<>();
    private final Map<String, Float> hover = new HashMap<>();
    private final Map<String, Object[]> logos = new HashMap<>(); // id -> {ResourceLocation, w, h} sau {null}
    private final long born = System.currentTimeMillis();
    private IModInfo sel;
    private long selAt;
    private float scroll;
    private float scrollTarget;
    private int listRows;
    private long lastFrame = System.nanoTime();
    private String lastQuery = "";
    private long filterAt;

    public FlowModsScreen(Screen parent) {
        super("Moduri", 100, 100);
        this.parent = parent;
        this.search = field("Cauta un mod...", 40, null);
        for (IModInfo m : ModList.get().getMods()) all.add(m);
        all.sort(Comparator.comparing((IModInfo m) -> m.getDisplayName().toLowerCase(Locale.ROOT)));
        if (!all.isEmpty()) select(all.get(0));
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>();
        l.add(search);
        return l;
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private void select(IModInfo m) {
        if (m == sel) return;
        sel = m;
        selAt = System.currentTimeMillis();
    }

    private List<IModInfo> filtered() {
        String q = search.getValue().trim().toLowerCase(Locale.ROOT);
        if (!q.equals(lastQuery)) {
            lastQuery = q;
            filterAt = System.currentTimeMillis();
            scrollTarget = 0;
        }
        if (q.isEmpty()) return all;
        List<IModInfo> out = new ArrayList<>();
        for (IModInfo m : all) {
            if (m.getDisplayName().toLowerCase(Locale.ROOT).contains(q) || m.getModId().toLowerCase(Locale.ROOT).contains(q)) out.add(m);
        }
        return out;
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    private static int avatarColor(String id) {
        return AVATAR[Math.abs(id.hashCode()) % AVATAR.length];
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        long nsNow = System.nanoTime();
        float dt = Math.min(0.1f, (nsNow - lastFrame) / 1e9f);
        lastFrame = nsNow;
        double t = now / 1000.0;
        float open = ease((now - born) / 420f);

        // ---- fundal: grafit + doua lumini care se misca lent
        Gfx.rrect(g, 0, 0, width, height, 0, 0xFF101217, 0xFF0B0C10);
        Gfx.scrim(g, width * (0.22f + 0.05f * (float) Math.sin(t * 0.25)), height * 0.25f, width * 0.45f, height * 0.5f, Gfx.fade(0x26E9A23B, open));
        Gfx.scrim(g, width * (0.82f + 0.04f * (float) Math.cos(t * 0.2)), height * 0.85f, width * 0.4f, height * 0.45f, Gfx.fade(0x1F2F8CFF, open));

        int m = Math.max(12, width / 28);
        float slide = (1f - open) * 14f;

        // ---- antet
        g.pose().pushPose();
        g.pose().translate(0, -slide, 0);
        float hy = m + 18;
        Gfx.text(g, "MODURI", m, hy, 22, Gfx.fade(0xFFF4F1EA, open), 0, true, 1.2f);
        float tw = Gfx.width("MODURI", 22, true) + 1.2f * 5;
        String cnt = all.size() + " instalate";
        float cw = Gfx.width(cnt, 9, false) + 14;
        Gfx.rrect(g, m + tw + 10, hy - 14, cw, 16, 8, Gfx.fade(0x33E9A23B, open));
        Gfx.text(g, cnt, m + tw + 10 + cw / 2, hy - 2.5f, 9, Gfx.fade(0xFFE9A23B, open), 1);
        Gfx.text(g, "FlowCity · Forge 1.16.5", m, hy + 14, 9, Gfx.fade(0xFF7D7B76, open), 0);

        // inapoi
        int bw = 70;
        int bh = 20;
        int bx = width - m - bw;
        int by = m + 2;
        boolean bhov = Ui.in(mx, my, bx, by, bw, bh);
        Gfx.rrect(g, bx, by, bw, bh, 10, Gfx.fade(bhov ? 0xFF2A2E36 : 0xFF1B1E24, open));
        Gfx.text(g, "‹  Inapoi", bx + bw / 2f, by + 13.5f, 9, Gfx.fade(bhov ? 0xFFF4F1EA : 0xFFB5B3AC, open), 1);
        hit(bx, by, bw, bh, this::onClose);
        // cautare
        int sw = Math.min(220, width / 3);
        int sx = bx - 10 - sw;
        boolean focused = search.isFocused();
        Gfx.rrect(g, sx, by, sw, bh, 10, Gfx.fade(focused ? 0xFF3A3220 : 0xFF23262D, open));
        Gfx.rrect(g, sx + 1, by + 1, sw - 2, bh - 2, 9, Gfx.fade(0xFF15171C, open));
        Gfx.ring(g, sx + 13, by + 9.5f, 3.2f, 4.6f, Gfx.fade(0xFF8E8C86, open), Gfx.fade(0xFF8E8C86, open));
        Gfx.line(g, sx + 16.2f, by + 12.7f, sx + 18.5f, by + 15, 1.4f, Gfx.fade(0xFF8E8C86, open));
        search.x = sx + 24;
        search.y = by + 6;
        search.setWidth(sw - 32);
        search.render(g.pose(), mx, my, 0);
        g.pose().popPose();

        // ---- zona de continut
        int top = m + 52;
        int bottom = height - m;
        int lw = Math.max(170, Math.min(300, (int) (width * 0.36f)));
        int dx = m + lw + 14;
        int dw = width - m - dx;

        // ---- lista
        List<IModInfo> list = filtered();
        int rowH = 34;
        int gap = 4;
        int listH = bottom - top;
        listRows = listH / (rowH + gap);
        float maxScroll = Math.max(0, list.size() * (rowH + gap) - listH);
        scrollTarget = Math.max(0, Math.min(maxScroll, scrollTarget));
        scroll += (scrollTarget - scroll) * Math.min(1f, dt * 14f);
        Ui.scissor(m - 4, top, lw + 8, listH);
        float since = (now - Math.max(born, filterAt)) / 1000f;
        if (list.isEmpty()) Gfx.text(g, "Niciun mod gasit.", m + lw / 2f, top + 30, 10, 0xFF7D7B76, 1);
        for (int i = 0; i < list.size(); i++) {
            IModInfo mi = list.get(i);
            float ry = top + i * (rowH + gap) - scroll;
            if (ry + rowH < top || ry > bottom) continue;
            float st = ease((since - Math.min(i, 14) * 0.035f) / 0.35f);
            if (st <= 0) continue;
            String id = mi.getModId();
            boolean inside = my >= top && my < bottom;
            boolean h = inside && Ui.in(mx, my, m, (int) ry, lw, rowH);
            float hv = hover.getOrDefault(id, 0f);
            hv += ((h ? 1f : 0f) - hv) * Math.min(1f, dt * 12f);
            hover.put(id, hv);
            boolean isSel = mi == sel;
            float ox = (1f - st) * 18f + hv * 3f;
            float x = m + ox;
            int base = isSel ? 0xFF262A32 : Gfx.mixArgb(0xFF181B20, 0xFF21252C, hv);
            if (isSel) Gfx.rrect(g, x - 2, ry - 2, lw + 4 - ox, rowH + 4, 12, Gfx.fade(0x33E9A23B, st));
            Gfx.rrect(g, x, ry, lw - ox, rowH, 10, Gfx.fade(base, st));
            if (isSel) Gfx.rrect(g, x + 4, ry + 9, 3, rowH - 18, 1.5f, Gfx.fade(0xFFE9A23B, st));
            int ac = avatarColor(id);
            float ax = x + 12;
            float ay = ry + 7;
            Gfx.rrect(g, ax, ay, 20, 20, 7, Gfx.fade(Gfx.mixArgb(ac, 0xFFFFFFFF, 0.15f), st), Gfx.fade(Gfx.mixArgb(ac, 0xFF000000, 0.25f), st));
            String letter = mi.getDisplayName().isEmpty() ? "?" : mi.getDisplayName().substring(0, 1).toUpperCase(Locale.ROOT);
            Gfx.text(g, letter, ax + 10, ay + 14, 11, Gfx.fade(0xFF14161A, st), 1, true, 0);
            float textW = lw - ox - 46 - 8;
            Gfx.text(g, clip(mi.getDisplayName(), 11, textW), x + 40, ry + 15, 11, Gfx.fade(isSel || h ? 0xFFF4F1EA : 0xFFD9D6CF, st), 0);
            Gfx.text(g, clip(id + "  ·  " + mi.getVersion(), 8.5f, textW), x + 40, ry + 27, 8.5f, Gfx.fade(0xFF7D7B76, st), 0);
            if (inside) hit((int) x, (int) ry, lw, rowH, () -> select(mi));
        }
        Ui.endScissor();
        // bara de scroll
        if (maxScroll > 0) {
            float th = Math.max(20, listH * listH / (float) (list.size() * (rowH + gap)));
            float ty = top + (listH - th) * (scroll / maxScroll);
            Gfx.rrect(g, m + lw + 5, ty, 3, th, 1.5f, 0x40FFFFFF);
        }

        // ---- detalii
        if (sel != null && dw > 120) drawDetails(g, mc, mx, my, dx, top, dw, bottom - top, open, now);
    }

    private void drawDetails(GuiGraphics g, Minecraft mc, int mx, int my, int x, int y, int w, int h, float open, long now) {
        float a = ease((now - selAt) / 260f) * open;
        float sy = (1f - a) * 10f;
        Gfx.rrect(g, x, y, w, h, 14, 0xFF16181D, 0xFF121418);
        Gfx.rrect(g, x + 0.5f, y + 0.5f, w - 1, 1, 0, 0x14FFFFFF);
        g.pose().pushPose();
        g.pose().translate(0, sy, 0);
        String id = sel.getModId();
        int ac = avatarColor(id);
        float pad = 18;
        float cy = y + pad;

        // logo-ul modului (daca are) sau un banner cu initiala
        Object[] logo = logo(mc, sel);
        float bannerH = Math.min(90, h * 0.28f);
        if (logo[0] != null) {
            int lwImg = (Integer) logo[1];
            int lhImg = (Integer) logo[2];
            float k = Math.min((w - pad * 2) / lwImg, bannerH / lhImg);
            int dwImg = Math.round(lwImg * k);
            int dhImg = Math.round(lhImg * k);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, a);
            g.blit((ResourceLocation) logo[0], (int) (x + pad), (int) cy, dwImg, dhImg, 0f, 0f, lwImg, lhImg, lwImg, lhImg);
            com.mojang.blaze3d.systems.RenderSystem.color4f(1f, 1f, 1f, 1f);
            cy += dhImg + 14;
        } else {
            Gfx.rrect(g, x + pad, cy, 48, 48, 14, Gfx.fade(Gfx.mixArgb(ac, 0xFFFFFFFF, 0.15f), a), Gfx.fade(Gfx.mixArgb(ac, 0xFF000000, 0.3f), a));
            Gfx.disc(g, x + pad + 24, cy + 24, 34, Gfx.fade(Gfx.mixArgb(ac, 0, 0) & 0x00FFFFFF | 0x22000000, a), ac & 0x00FFFFFF, 0, 0);
            String letter = sel.getDisplayName().isEmpty() ? "?" : sel.getDisplayName().substring(0, 1).toUpperCase(Locale.ROOT);
            Gfx.text(g, letter, x + pad + 24, cy + 34, 26, Gfx.fade(0xFF14161A, a), 1, true, 0);
            cy += 62;
        }

        // nume + versiune
        Gfx.text(g, clip(sel.getDisplayName(), 20, w - pad * 2), x + pad, cy + 14, 20, Gfx.fade(0xFFF4F1EA, a), 0, true, 0);
        cy += 24;
        String ver = "v" + sel.getVersion();
        float vw = Gfx.width(ver, 9, false) + 14;
        Gfx.rrect(g, x + pad, cy, vw, 16, 8, Gfx.fade(0x33E9A23B, a));
        Gfx.text(g, ver, x + pad + vw / 2, cy + 11.5f, 9, Gfx.fade(0xFFE9A23B, a), 1);
        String idTag = id;
        float iw = Gfx.width(idTag, 9, false) + 14;
        Gfx.rrect(g, x + pad + vw + 6, cy, iw, 16, 8, Gfx.fade(0xFF23262D, a));
        Gfx.text(g, idTag, x + pad + vw + 6 + iw / 2, cy + 11.5f, 9, Gfx.fade(0xFFB5B3AC, a), 1);
        cy += 28;

        // autori / site
        String authors = cfg(sel, "authors");
        String site = cfg(sel, "displayURL");
        if (!authors.isEmpty()) {
            Gfx.text(g, "AUTORI", x + pad, cy + 8, 8.5f, Gfx.fade(0xFF7D7B76, a), 0, false, 0.8f);
            Gfx.text(g, clip(authors, 10, w - pad * 2 - 60), x + pad + 60, cy + 8, 10, Gfx.fade(0xFFD9D6CF, a), 0);
            cy += 16;
        }
        if (!site.isEmpty()) {
            Gfx.text(g, "SITE", x + pad, cy + 8, 8.5f, Gfx.fade(0xFF7D7B76, a), 0, false, 0.8f);
            Gfx.text(g, clip(site, 10, w - pad * 2 - 60), x + pad + 60, cy + 8, 10, Gfx.fade(0xFF8FB8F0, a), 0);
            cy += 16;
        }
        cy += 6;
        Gfx.rrect(g, x + pad, cy, w - pad * 2, 1, 0, Gfx.fade(0xFF262A31, a));
        cy += 14;

        // descriere (impartita pe randuri)
        String desc = sel.getDescription() == null ? "" : sel.getDescription().trim().replace("\r", "");
        if (desc.isEmpty()) desc = "Modul nu are descriere.";
        float lineH = 13;
        float maxY = y + h - 52;
        for (String line : wrap(desc, 10, w - pad * 2)) {
            if (cy + lineH > maxY) {
                Gfx.text(g, "...", x + pad, cy + 9, 10, Gfx.fade(0xFF7D7B76, a), 0);
                break;
            }
            Gfx.text(g, line, x + pad, cy + 9, 10, Gfx.fade(0xFFC9C7C0, a), 0);
            cy += lineH;
        }
        g.pose().popPose();

        // butoane jos
        float by = y + h - 38;
        float bx = x + pad;
        Optional<BiFunction<Minecraft, Screen, Screen>> cfgGui = ModList.get().getModContainerById(id)
            .flatMap(c -> c.getCustomExtension(ExtensionPoint.CONFIGGUIFACTORY));
        if (cfgGui.isPresent()) {
            bx = pill(g, mx, my, "Configurare", bx, by, true, a, () -> mc.setScreen(cfgGui.get().apply(mc, this))) + 8;
        }
        pill(g, mx, my, "Folderul de moduri", bx, by, false, a, () -> Util.getPlatform().openFile(FMLPaths.MODSDIR.get().toFile()));
    }

    private float pill(GuiGraphics g, int mx, int my, String label, float x, float y, boolean primary, float a, Runnable r) {
        float w = Gfx.width(label, 10, false) + 28;
        float h = 22;
        boolean hov = Ui.in(mx, my, (int) x, (int) y, (int) w, (int) h);
        if (primary) {
            if (hov) Gfx.rrect(g, x - 3, y - 3, w + 6, h + 6, 14, Gfx.fade(0x40E9A23B, a));
            Gfx.rrect(g, x, y, w, h, 11, Gfx.fade(hov ? 0xFFF6C15A : 0xFFE9A23B, a), Gfx.fade(hov ? 0xFFEE9A3A : 0xFFD48A2C, a));
            Gfx.text(g, label, x + w / 2, y + 14.5f, 10, Gfx.fade(0xFF16181D, a), 1);
        } else {
            Gfx.rrect(g, x, y, w, h, 11, Gfx.fade(hov ? 0xFF3A3E47 : 0xFF2A2E36, a));
            Gfx.rrect(g, x + 1, y + 1, w - 2, h - 2, 10, Gfx.fade(hov ? 0xFF22252C : 0xFF181B20, a));
            Gfx.text(g, label, x + w / 2, y + 14.5f, 10, Gfx.fade(hov ? 0xFFF4F1EA : 0xFFC9C7C0, a), 1);
        }
        hit((int) x, (int) y, (int) w, (int) h, r);
        return x + w;
    }

    // ------------------------------------------------------------------ utilitare

    private static String cfg(IModInfo m, String key) {
        try {
            if (m instanceof ModInfo) {
                Optional<Object> o = ((ModInfo) m).getConfigElement(key);
                if (o.isPresent()) return String.valueOf(o.get()).trim();
            }
        } catch (RuntimeException ignored) {
            // camp lipsa
        }
        return "";
    }

    /** Incarca (o data) logo-ul declarat in mods.toml. Intoarce {textura, latime, inaltime} sau {null}. */
    private Object[] logo(Minecraft mc, IModInfo m) {
        Object[] c = logos.get(m.getModId());
        if (c != null) return c;
        Object[] res = {null};
        try {
            if (m instanceof ModInfo) {
                Optional<String> file = ((ModInfo) m).getLogoFile();
                if (file.isPresent() && !file.get().isEmpty()) {
                    Optional<? extends net.minecraftforge.fml.packs.ModFileResourcePack> pack = ResourcePackLoader.getResourcePackFor(m.getModId());
                    if (pack.isPresent()) {
                        try (InputStream in = pack.get().getRootResource(file.get())) {
                            NativeImage img = NativeImage.read(in);
                            int iw = img.getWidth();
                            int ih = img.getHeight();
                            ResourceLocation rl = mc.getTextureManager().register("flowmods_" + m.getModId(), new DynamicTexture(img));
                            res = new Object[] {rl, iw, ih};
                        }
                    }
                }
            }
        } catch (Exception | LinkageError ignored) {
            // fara logo: folosim initiala
        }
        logos.put(m.getModId(), res);
        return res;
    }

    private static String clip(String s, float size, float maxW) {
        if (Gfx.width(s, size, false) <= maxW) return s;
        String out = s;
        while (out.length() > 1 && Gfx.width(out + "...", size, false) > maxW) out = out.substring(0, out.length() - 1);
        return out + "...";
    }

    private static List<String> wrap(String text, float size, float maxW) {
        List<String> lines = new ArrayList<>();
        for (String para : text.split("\n")) {
            String line = "";
            for (String word : para.trim().split("\\s+")) {
                if (word.isEmpty()) continue;
                String tryLine = line.isEmpty() ? word : line + " " + word;
                if (Gfx.width(tryLine, size, false) > maxW && !line.isEmpty()) {
                    lines.add(line);
                    line = word;
                } else {
                    line = tryLine;
                }
            }
            lines.add(line);
        }
        return lines;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scrollTarget -= (float) delta * 38f;
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) {
            onClose();
            return true;
        }
        if (!search.isFocused() && (key == 264 || key == 265)) { // sageti jos/sus
            List<IModInfo> list = filtered();
            int i = Math.max(0, list.indexOf(sel));
            i = Math.max(0, Math.min(list.size() - 1, i + (key == 264 ? 1 : -1)));
            if (!list.isEmpty()) {
                select(list.get(i));
                float ry = i * 38f;
                if (ry < scrollTarget) scrollTarget = ry;
                if (ry + 38 > scrollTarget + listRows * 38f) scrollTarget = ry + 38 - listRows * 38f;
            }
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }
}
