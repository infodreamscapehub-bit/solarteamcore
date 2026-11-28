package ro.solarstudios.flowcity.gps;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Meniul contextual al unei locatii / marker (click dreapta pe harta sau in bara laterala). Optiunile apar dupa permisiuni:
 * Editeaza / Sterge doar pentru admini (sau pentru propriile markere), Teleportare doar daca serverul a marcat jucatorul
 * ca fiind autorizat (OP + permisiune). Serverul RE-VERIFICA oricum la fiecare actiune; butonul ascuns nu e o protectie.
 */
final class GpsMenu {
    private final GpsScreen host;
    private GpsSidebar.Entry target;
    private float x;
    private float y;
    private float anim;
    private boolean confirmDelete;
    private boolean visible;

    GpsMenu(GpsScreen host) {
        this.host = host;
    }

    boolean isOpen() {
        return visible;
    }

    void open(GpsSidebar.Entry e, float mx, float my) {
        target = e;
        x = mx;
        y = my;
        anim = 0f;
        confirmDelete = false;
        visible = true;
    }

    void close() {
        visible = false;
        target = null;
        confirmDelete = false;
    }

    private static final class Item {
        final String icon;
        final String label;
        final int color;
        final Runnable action;
        final boolean keepOpen;

        Item(String icon, String label, int color, Runnable action, boolean keepOpen) {
            this.icon = icon;
            this.label = label;
            this.color = color;
            this.action = action;
            this.keepOpen = keepOpen;
        }
    }

    private List<Item> items() {
        final GpsSidebar.Entry e = target;
        List<Item> out = new ArrayList<>();
        boolean serverLoc = e.loc != null && !GpsScreen.PIN.equals(e.loc.id);
        boolean isPin = e.loc != null && GpsScreen.PIN.equals(e.loc.id);
        boolean canEdit = serverLoc && GpsData.admin || e.wp != null && (!e.wp.shared || GpsData.admin);
        if (canEdit) {
            out.add(new Item("pencil", "Editează", GpsTheme.AMBER, () -> host.editEntry(e), false));
            if (confirmDelete) {
                out.add(new Item("trash", "Confirmă ștergerea", GpsTheme.DANGER, () -> host.deleteEntry(e), false));
            } else {
                out.add(new Item("trash", "Șterge", GpsTheme.DANGER, () -> confirmDelete = true, true));
            }
        }
        out.add(new Item("nav", GpsNav.isTarget(host.idOf(e)) ? "Anulează GPS" : "Setează GPS", 0xFFFF4D4D, () -> host.navEntry(e), false));
        if (serverLoc) {
            boolean fav = GpsPrefs.fav.contains(e.loc.id);
            out.add(new Item("star-line", fav ? "Scoate din favorite" : "Adaugă la favorite", GpsTheme.GOLD, () -> host.toggleFav(e.loc), false));
        }
        if (isPin) out.add(new Item("flag", "Salvează ca marker", GpsTheme.SKY, () -> host.saveAsMarker(e), false));
        if (serverLoc && GpsData.canTp) out.add(new Item("target", "Teleportare", GpsTheme.SKY, () -> host.teleport(e), false));
        return out;
    }

    void draw(GuiGraphics g, int mx, int my, float dt, float s, int W, int H) {
        if (!visible || target == null) return;
        anim = GpsTheme.to(anim, 1f, dt, 16f);
        List<Item> items = items();
        float rowH = 24f * s;
        float w = 168f * s;
        float head = 30f * s;
        float h = head + items.size() * rowH + 6f * s;
        float px = Math.max(4f, Math.min(W - w - 4f, x));
        float py = Math.max(4f, Math.min(H - h - 4f, y));
        // click oriunde in afara meniului il inchide
        host.hitF(0, 0, W, H, this::close);
        float off = (1f - anim) * -6f * s;
        GpsTheme.shadow(g, px, py + off, w, h, GpsTheme.R_MD);
        Gfx.rrect(g, px - 1f, py + off - 1f, w + 2f, h + 2f, GpsTheme.R_MD + 1f, GpsTheme.fade(GpsTheme.alpha(GpsTheme.ORANGE, 120), anim));
        Gfx.rrect(g, px, py + off, w, h, GpsTheme.R_MD, GpsTheme.fade(0xF20C0E12, anim));
        host.hitF(px, py, w, h, () -> {});
        GpsUi.badge(g, target.icon, px + 18f * s, py + off + head / 2f, 8f * s, target.color);
        Gfx.text(g, GpsScreen.clip(target.name, 8.8f * s, true, w - 44f * s), px + 32f * s, py + off + head / 2f - 1f * s, 8.8f * s,
            GpsTheme.fade(GpsTheme.TEXT, anim), 0, true, 0f);
        String sub = host.distText(target.x, target.z);
        Gfx.text(g, Math.round(target.x) + ", " + Math.round(target.z) + (sub.isEmpty() ? "" : "  ·  " + sub), px + 32f * s,
            py + off + head / 2f + 9f * s, 6.8f * s, GpsTheme.fade(GpsTheme.MUTED, anim), 0, true, 0f);
        Gfx.rrect(g, px + 8f * s, py + off + head - 1f, w - 16f * s, 1f, 0, GpsTheme.fade(GpsTheme.LINE, anim));
        float iy = py + off + head + 2f * s;
        for (final Item it : items) {
            boolean hov = GpsUi.in(mx, my, px + 4f * s, iy, w - 8f * s, rowH);
            float t = GpsUi.hot("ctx" + it.label, hov);
            if (t > 0.01f) Gfx.rrect(g, px + 4f * s, iy, w - 8f * s, rowH - 2f * s, GpsTheme.R_SM, GpsTheme.fade(GpsTheme.alpha(it.color, 54), t * anim));
            GpsUi.icon(g, it.icon, px + 18f * s, iy + rowH / 2f - s, 11f * s, GpsTheme.fade(it.color, anim));
            Gfx.text(g, it.label, px + 32f * s, iy + rowH / 2f + 2.6f * s, 8.4f * s, GpsTheme.fade(GpsTheme.mix(0xFFD7DCE6, GpsTheme.TEXT, t), anim), 0, true, 0f);
            host.hitF(px + 4f * s, iy, w - 8f * s, rowH, () -> {
                it.action.run();
                if (!it.keepOpen) close();
            });
            iy += rowH;
        }
    }
}
