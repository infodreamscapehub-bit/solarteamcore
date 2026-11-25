package ro.solarstudios.flowcity.beginner;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.gui.widget.TextFieldWidget;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * Meniul staff pentru badge-ul de incepator: setari (prag, text, iconita, culori, distanta, inaltime, afisare timp), previzualizare
 * live si lista jucatorilor online cu statutul lor. Salveaza prin server (validat acolo, permisiunea flowcity.beginner.admin).
 */
public final class BeginnerScreen extends FlowScreen {
    private static final String[] ICONS = {"star", "spark", "diamond", "clover", "cross", "arrow", "none"};
    private static final String[] PRESETS = {"#4DD2FF", "#3D8BFF", "#2EE6A6", "#7CFF6B", "#FFC93D", "#FF8A3D", "#FF4D6D", "#C77DFF"};

    private boolean enabled = BeginnerClient.enabled;
    private boolean showTime = BeginnerClient.showTime;
    private boolean hideWithId = BeginnerClient.hideWithId;
    private double hours = BeginnerClient.hours;
    private int distance = BeginnerClient.distance;
    private double offset = BeginnerClient.offset;
    private String icon = BeginnerClient.icon;
    private int scroll;

    private final TextFieldWidget fText = field("Text badge", 16, null);
    private final TextFieldWidget fColor = field("#RRGGBB", 7, null);
    private final TextFieldWidget fTime = field("#RRGGBB", 7, null);
    private final TextFieldWidget fSearch = field("Caută jucător…", 16, s -> scroll = 0);

    public BeginnerScreen() {
        super("Începători", 560, 312);
        fText.setValue(BeginnerClient.text);
        fColor.setValue(BeginnerClient.color);
        fTime.setValue(BeginnerClient.timeColor);
    }

    @Override
    protected List<TextFieldWidget> fields() {
        return Arrays.asList(fText, fColor, fTime, fSearch);
    }

    private void toggle(GuiGraphics g, String label, boolean on, int x, int y, int w, int mx, int my, Runnable flip) {
        boolean hov = Ui.in(mx, my, x, y, w, 16);
        if (hov) Ui.round(g, x - 3, y, w + 6, 16, Theme.PANEL_3);
        Ui.text(g, label, x, y + 4, on ? Theme.TEXT : Theme.MUTED);
        int sx = x + w - 26;
        Ui.round(g, sx, y + 3, 24, 10, on ? Theme.ACCENT : Theme.PANEL_3);
        Ui.rect(g, on ? sx + 14 : sx + 2, y + 5, 8, 6, 0xFFFFFFFF);
        hit(x - 3, y, w + 6, 16, flip);
    }

    private void stepper(GuiGraphics g, String label, String value, int x, int y, int w, int mx, int my, Runnable dec, Runnable inc, Runnable dec2, Runnable inc2) {
        Ui.text(g, label, x, y + 4, Theme.TEXT);
        int bx = x + w - 104;
        button(g, "-", bx, y, 16, 16, mx, my, true, Theme.PANEL_3, dec2);
        button(g, "<", bx + 18, y, 16, 16, mx, my, true, Theme.PANEL_3, dec);
        Ui.textCenter(g, value, bx + 52, y + 4, Theme.ACCENT);
        button(g, ">", bx + 70, y, 16, 16, mx, my, true, Theme.PANEL_3, inc);
        button(g, "+", bx + 88, y, 16, 16, mx, my, true, Theme.PANEL_3, inc2);
    }

    private static String fmtHours(double h) {
        return (Math.round(h * 10.0) / 10.0) + " h";
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        header(g, "Începători", "badge deasupra capului", mx, my);
        int x0 = px + 12;
        int w0 = 262;
        int y = py + HEAD + 10;

        toggle(g, "Sistem activ", enabled, x0, y, w0, mx, my, () -> enabled = !enabled);
        y += 18;
        toggle(g, "Arată timpul rămas pe badge", showTime, x0, y, w0, mx, my, () -> showTime = !showTime);
        y += 18;
        toggle(g, "Ascunde când se afișează #ID", hideWithId, x0, y, w0, mx, my, () -> hideWithId = !hideWithId);
        y += 22;
        stepper(g, "Prag ore jucate", fmtHours(hours), x0, y, w0, mx, my,
                () -> hours = Math.max(0.5, hours - 0.5), () -> hours = Math.min(2000, hours + 0.5),
                () -> hours = Math.max(0.5, hours - 5), () -> hours = Math.min(2000, hours + 5));
        y += 20;
        stepper(g, "Distanță afișare", distance + " bl", x0, y, w0, mx, my,
                () -> distance = Math.max(4, distance - 2), () -> distance = Math.min(64, distance + 2),
                () -> distance = Math.max(4, distance - 8), () -> distance = Math.min(64, distance + 8));
        y += 20;
        stepper(g, "Înălțime deasupra numelui", String.format("%.2f", offset), x0, y, w0, mx, my,
                () -> offset = Math.max(-1.0, Math.round((offset - 0.05) * 100.0) / 100.0), () -> offset = Math.min(2.0, Math.round((offset + 0.05) * 100.0) / 100.0),
                () -> offset = Math.max(-1.0, Math.round((offset - 0.25) * 100.0) / 100.0), () -> offset = Math.min(2.0, Math.round((offset + 0.25) * 100.0) / 100.0));
        y += 22;

        Ui.text(g, "Iconiță", x0, y + 4, Theme.TEXT);
        int idx = Math.max(0, Arrays.asList(ICONS).indexOf(icon));
        button(g, "<", x0 + w0 - 104, y, 16, 16, mx, my, true, Theme.PANEL_3, () -> icon = ICONS[(Arrays.asList(ICONS).indexOf(icon) + ICONS.length - 1) % ICONS.length]);
        Ui.textCenter(g, "none".equals(icon) ? "fără" : BeginnerClient.glyph(icon) + " " + icon, x0 + w0 - 52, y + 4, Theme.ACCENT);
        button(g, ">", x0 + w0 - 20, y, 16, 16, mx, my, true, Theme.PANEL_3, () -> icon = ICONS[(Arrays.asList(ICONS).indexOf(icon) + 1) % ICONS.length]);
        y += 22;

        Ui.text(g, "Text badge", x0, y, Theme.MUTED);
        drawField(g, fText, x0, y + 10, w0, 18, mx, my);
        y += 34;

        Ui.text(g, "Culori rapide", x0, y, Theme.MUTED);
        for (int i = 0; i < PRESETS.length; i++) {
            final String hex = PRESETS[i];
            int sx = x0 + i * 32;
            int c = 0xFF000000 | BeginnerClient.parseColor(hex, 0xFFFFFF);
            boolean sel = hex.equalsIgnoreCase(fColor.getValue());
            Ui.round(g, sx - 1, y + 9, 28, 16, sel ? Theme.TEXT : Theme.LINE);
            Ui.round(g, sx, y + 10, 26, 14, c);
            hit(sx, y + 10, 26, 14, () -> fColor.setValue(hex));
        }
        y += 30;
        Ui.text(g, "Culoare text", x0, y, Theme.MUTED);
        Ui.text(g, "Culoare timp", x0 + 134, y, Theme.MUTED);
        drawField(g, fColor, x0, y + 10, 124, 18, mx, my);
        drawField(g, fTime, x0 + 134, y + 10, 124, 18, mx, my);

        // dreapta: previzualizare + lista
        int x1 = px + 292;
        int w1 = pw - 304;
        int yy = py + HEAD + 10;
        Ui.text(g, "Previzualizare", x1, yy, Theme.MUTED);
        Ui.round(g, x1, yy + 10, w1, 74, 0xFF0C0E12);
        int cx = x1 + w1 / 2;
        int textColor = 0xFF000000 | BeginnerClient.parseColor(fColor.getValue(), 0x4DD2FF);
        int timeColor = 0xFF000000 | BeginnerClient.parseColor(fTime.getValue(), 0xB8C4D6);
        String line = BeginnerClient.label(icon, fText.getValue().trim());
        int ty = yy + 24;
        if (enabled) {
            Ui.round(g, cx - Ui.width(line) / 2 - 4, ty - 3, Ui.width(line) + 8, 14, 0x66000000);
            Ui.textCenter(g, line, cx, ty, textColor);
            if (showTime) Ui.textCenter(g, BeginnerClient.remaining(Math.max(0, (int) (hours * 30.0)), hours), cx, ty + 14, timeColor);
        } else {
            Ui.textCenter(g, "(dezactivat)", cx, ty, Theme.FAINT);
        }
        Ui.textCenter(g, hideWithId ? "#12  NumeJucător" : "NumeJucător", cx, yy + 62, Theme.MUTED);

        yy += 92;
        Ui.text(g, "Jucători online", x1, yy, Theme.MUTED);
        drawField(g, fSearch, x1, yy + 10, w1, 18, mx, my);
        int ly = yy + 34;
        int bottom = py + ph - 36;
        String q = fSearch.getValue().trim().toLowerCase();
        List<BeginnerClient.Row> shown = new ArrayList<>();
        for (BeginnerClient.Row r : BeginnerClient.rows) {
            if (q.isEmpty() || r.name.toLowerCase().contains(q)) shown.add(r);
        }
        int rowH = 14;
        int cap = Math.max(1, (bottom - ly) / rowH);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, shown.size() - cap)));
        Ui.scissor(x1, ly, w1, cap * rowH);
        for (int i = 0; i < cap && scroll + i < shown.size(); i++) {
            BeginnerClient.Row r = shown.get(scroll + i);
            int ry = ly + i * rowH;
            if (i % 2 == 0) Ui.rect(g, x1, ry, w1, rowH, 0x14FFFFFF);
            Ui.text(g, Ui.clip(r.name, w1 - 120), x1 + 4, ry + 3, Theme.TEXT);
            Ui.textRight(g, fmtPlayed(r.min), x1 + w1 - 70, ry + 3, Theme.MUTED);
            Ui.textRight(g, r.beginner ? "ÎNCEPĂTOR" : "veteran", x1 + w1 - 4, ry + 3, r.beginner ? Theme.ACCENT : Theme.OK);
        }
        Ui.endScissor();
        if (shown.isEmpty()) Ui.textCenter(g, BeginnerClient.rows.isEmpty() ? "Se încarcă…" : "Niciun jucător găsit", x1 + w1 / 2, ly + 10, Theme.FAINT);

        // jos
        int by = py + ph - 28;
        button(g, "SALVEAZĂ", px + 12, by, 110, 20, mx, my, true, Theme.ACCENT, this::save);
        ghost(g, "ACTUALIZEAZĂ LISTA", px + 130, by, 130, 20, mx, my, BeginnerClient::requestList);
        ghost(g, "ÎNCHIDE", px + pw - 92, by, 80, 20, mx, my, this::onClose);
    }

    private static String fmtPlayed(int min) {
        return (min / 60) + "h " + (min % 60) + "m";
    }

    private void save() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", enabled);
        o.addProperty("hours", hours);
        o.addProperty("text", fText.getValue().trim());
        o.addProperty("icon", icon);
        o.addProperty("showTime", showTime);
        o.addProperty("color", fColor.getValue().trim());
        o.addProperty("timeColor", fTime.getValue().trim());
        o.addProperty("distance", distance);
        o.addProperty("offset", offset);
        o.addProperty("hideWithId", hideWithId);
        BeginnerClient.sendCfg(o);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        scroll = Math.max(0, scroll - (int) Math.signum(delta) * 2);
        return true;
    }
}
