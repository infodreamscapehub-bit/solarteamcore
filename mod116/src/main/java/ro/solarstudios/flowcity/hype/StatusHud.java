package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.ClientPlayerEntity;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.HudLayout;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * HUD de status 2.0 (jos-stanga): patru indicatoare circulare cu iconite desenate din cod si procent.
 * Viata, armura si mancarea vin de la jucatorul local (sincronizate de joc de pe server); apa vine de la sistemul de sete
 * al serverului (modulul hype, thirst in starea HUD-ului). Nimic nu e calculat sau inventat pe client.
 * Setarile (pozitie, marime, opacitate, parti, procente/valori, animatii, spatiere, barele vanilla) sunt in editorul de HUD (F8),
 * elementul "Status jucator".
 */
public final class StatusHud {
    public static final String ID = "status";

    private static final int RED = 0xFFE5484D;
    private static final int GREY = 0xFFB8C0CC;
    private static final int ORANGE = 0xFFF59E0B;
    private static final int BLUE = 0xFF3B9BFF;

    private final float[] shown = {-1, -1, -1, -1};
    private long lastNs = System.nanoTime();
    private boolean failed;

    /** HUD-ul de status deseneaza apa (atunci panoul principal nu mai afiseaza bara de sete, ca sa nu fie dublata). */
    public static boolean showsWater() {
        HudLayout.El e = HudLayout.get(ID);
        return HypeState.hudOn && e != null && e.visible && HudLayout.part(ID, "water")
            && JsonClientModule.bool(HypeState.state, "thirstOn", false);
    }

    private static boolean active() {
        HudLayout.El e = HudLayout.get(ID);
        return HypeState.hudOn && e != null && e.visible;
    }

    /** Ascunde barele vanilla (viata, armura, mancare) cand HUD-ul de status le arata. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPre(RenderGameOverlayEvent.Pre e) {
        RenderGameOverlayEvent.ElementType t = e.getType();
        if (t != RenderGameOverlayEvent.ElementType.HEALTH && t != RenderGameOverlayEvent.ElementType.ARMOR
            && t != RenderGameOverlayEvent.ElementType.FOOD) return;
        if (!active() || !HudLayout.part(ID, "hidevanilla")) return;
        String key = t == RenderGameOverlayEvent.ElementType.HEALTH ? "health" : (t == RenderGameOverlayEvent.ElementType.ARMOR ? "armor" : "food");
        if (HudLayout.part(ID, key)) e.setCanceled(true);
    }

    @SubscribeEvent
    public void onPost(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL || failed) return;
        try {
            render(new GuiGraphics(e.getMatrixStack()));
        } catch (RuntimeException ex) {
            failed = true;
            org.apache.logging.log4j.LogManager.getLogger("FlowCity").error("Eroare la desenarea HUD-ului de status", ex);
        }
    }

    private void render(GuiGraphics g) {
        Minecraft mc = Minecraft.getInstance();
        ClientPlayerEntity p = mc.player;
        long ns = System.nanoTime();
        float dt = Math.min(0.1f, (ns - lastNs) / 1_000_000_000f);
        lastNs = ns;
        if (p == null || mc.options.hideGui || !active()) return;
        if (p.isCreative() || p.isSpectator()) return;

        JsonObject s = HypeState.state;
        boolean thirstOn = JsonClientModule.bool(s, "thirstOn", false) && s.has("thirst");
        float maxHp = Math.max(1f, p.getMaxHealth());
        float hp = Math.max(0f, p.getHealth());
        int armor = p.getArmorValue();
        int food = p.getFoodData().getFoodLevel();
        float water = thirstOn ? JsonClientModule.integer(s, "thirst", 100) : 0;

        boolean anim = HudLayout.part(ID, "anim");
        boolean pct = HudLayout.part(ID, "pct");
        boolean auto = HudLayout.part(ID, "auto");
        float[] target = {hp / maxHp * 100f, Math.min(20, armor) / 20f * 100f, food / 20f * 100f, water};
        String[] raw = {fmt(hp) + "/" + Math.round(maxHp), armor + "/20", food + "/20", Math.round(water) + "/100"};
        boolean[] show = {
            HudLayout.part(ID, "health"),
            HudLayout.part(ID, "armor") && !(auto && armor <= 0),
            HudLayout.part(ID, "food"),
            HudLayout.part(ID, "water") && thirstOn
        };
        int[] col = {RED, GREY, ORANGE, BLUE};

        int n = 0;
        for (boolean b : show) if (b) n++;
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        float d = 30f;
        float gap = HudLayout.part(ID, "wide") ? 8f : 3f;
        float w = Math.max(d, n * d + Math.max(0, n - 1) * gap);
        float h = d + 2;
        float x = 8f;
        float y = sh - h - 8f;
        // sub latimea hotbar-ului: urcam deasupra lui, ca sa nu-l acoperim
        if (x + w > sw / 2f - 95f) y = sh - h - 44f;
        if (!HudLayout.begin(g, ID, x, y, w, h)) return;
        try {
            long now = System.currentTimeMillis();
            float cx = x + d / 2f;
            for (int i = 0; i < 4; i++) {
                if (shown[i] < 0 || !anim) shown[i] = target[i];
                else shown[i] += (target[i] - shown[i]) * Math.min(1f, dt * 7f);
                if (!show[i]) continue;
                gauge(g, i, cx, y + h / 2f, d / 2f, shown[i], target[i], col[i], pct ? Math.round(target[i]) + "%" : raw[i], now, anim);
                cx += d + gap;
            }
        } finally {
            HudLayout.end(g);
        }
    }

    private static String fmt(float v) {
        return v == Math.floor(v) ? String.valueOf((int) v) : String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** Un indicator: inel de fundal, arc de valoare (de sus, in sensul acelor), iconita, valoarea dedesubt si avertizare. */
    private static void gauge(GuiGraphics g, int kind, float cx, float cy, float r, float value, float real, int color, String label,
        long now, boolean anim) {
        float v = Math.max(0f, Math.min(100f, value));
        // praguri: 70-100 normal, 30-69 avertizare discreta, 1-29 vizibila, 0 critic
        int c = color;
        float pulse = 0f;
        if (real < 70f && real >= 30f) c = Gfx.mixArgb(color, 0xFF000000, 0.12f);
        else if (real < 30f && real > 0f) {
            c = Gfx.mixArgb(color, 0xFFFFFFFF, 0.1f);
            pulse = anim ? (float) (0.5 + 0.5 * Math.sin(now / 180.0)) : 1f;
        } else if (real <= 0f) {
            c = 0xFFFF3B30;
            pulse = anim ? (float) (0.5 + 0.5 * Math.sin(now / 110.0)) : 1f;
        }
        // fundal discret (HUD transparent) + umbra pentru lizibilitate
        Gfx.disc(g, cx, cy + 0.6f, r + 1.2f, 0x55000000);
        Gfx.disc(g, cx, cy, r - 0.6f, 0xB00B0F15);
        if (pulse > 0f) Gfx.ring(g, cx, cy, r, r + 2.6f, Gfx.fade(c, 0.55f * pulse), Gfx.fade(c, 0f));
        Gfx.ring(g, cx, cy, r - 3.6f, r - 0.8f, 0x33FFFFFF, 0x33FFFFFF);
        if (v > 0.3f) {
            final int cc = real < 30f ? c : c;
            Gfx.arc(g, cx, cy, r - 2.2f, 2.8f, -90f, -90f + 360f * v / 100f, (px, py) -> cc, 1f);
        }
        // iconita
        int ic = real <= 0f ? c : (real < 30f ? Gfx.mixArgb(c, 0xFFFFFFFF, 0.25f * pulse) : c);
        icon(g, kind, cx, cy - 2.2f, ic);
        Gfx.text(g, label, cx, cy + 9.4f, label.length() > 4 ? 4.6f : 5.4f, 0xFFF4F7FB, 1, true, 0f);
    }

    /** Iconite desenate din cod: inima, vesta, furculita + cutit, picatura. */
    private static void icon(GuiGraphics g, int kind, float cx, float cy, int c) {
        switch (kind) {
            case 0: { // inima
                Gfx.disc(g, cx - 2.1f, cy - 1.2f, 2.5f, c);
                Gfx.disc(g, cx + 2.1f, cy - 1.2f, 2.5f, c);
                Gfx.poly(g, new float[] {cx, cy + 4.2f, cx - 4.5f, cy - 0.4f, cx + 4.5f, cy - 0.4f}, c);
                break;
            }
            case 1: { // vesta / scut
                Gfx.poly(g, new float[] {cx, cy + 4.8f, cx - 4.2f, cy + 1.2f, cx - 4.2f, cy - 3.2f, cx, cy - 4.6f, cx + 4.2f, cy - 3.2f, cx + 4.2f, cy + 1.2f}, c);
                Gfx.line(g, cx, cy - 3.4f, cx, cy + 3.6f, 0.8f, 0xAA0B0F15);
                break;
            }
            case 2: { // furculita + cutit
                Gfx.line(g, cx - 2.4f, cy - 4.4f, cx - 2.4f, cy + 4.6f, 1.1f, c);
                Gfx.line(g, cx - 3.8f, cy - 4.4f, cx - 3.8f, cy - 1.2f, 0.7f, c);
                Gfx.line(g, cx - 1.0f, cy - 4.4f, cx - 1.0f, cy - 1.2f, 0.7f, c);
                Gfx.line(g, cx - 3.8f, cy - 1.2f, cx - 1.0f, cy - 1.2f, 0.9f, c);
                Gfx.poly(g, new float[] {cx + 2.6f, cy - 4.6f, cx + 4.2f, cy - 2.2f, cx + 4.0f, cy + 0.6f, cx + 2.6f, cy + 0.6f}, c);
                Gfx.line(g, cx + 3.1f, cy + 0.4f, cx + 3.1f, cy + 4.6f, 1.1f, c);
                break;
            }
            default: { // picatura
                Gfx.disc(g, cx, cy + 1.4f, 3.3f, c);
                Gfx.poly(g, new float[] {cx, cy - 4.8f, cx - 3.1f, cy + 0.6f, cx + 3.1f, cy + 0.6f}, c);
                Gfx.disc(g, cx - 1.2f, cy + 1.0f, 0.9f, 0x99FFFFFF);
                break;
            }
        }
    }
}
