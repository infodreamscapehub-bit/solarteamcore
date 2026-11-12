package ro.solarstudios.flowcity.update;

import com.mojang.blaze3d.matrix.MatrixStack;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Util;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.client.ui.Wrap;

/** Ecran de update: versiune curenta vs noua, lista schimbarilor, descarcare cu bara de progres. */
public final class UpdateScreen extends FlowScreen {
    private final Screen parent;
    private final long openedAt = System.currentTimeMillis();

    public UpdateScreen(Screen parent) {
        super("Update FlowCity", 400, 250);
        this.parent = parent;
    }

    @Override
    protected void init() {
        UpdateChecker.checkAsync(true);
    }

    @Override
    public void closeScreen() {
        Minecraft.getInstance().displayGuiScreen(parent);
    }

    @Override
    protected void draw(MatrixStack ms, int mx, int my) {
        header(ms, "Update FlowCity", "", mx, my);
        long now = System.currentTimeMillis();
        int x = px + 14;
        int w = pw - 28;
        int y = py + HEAD + 12;

        int mine = UpdateChecker.currentBuild();
        int latest = UpdateChecker.latestBuild;
        boolean avail = UpdateChecker.updateAvailable();

        // versiuni
        int cw = (w - 8) / 2;
        card(ms, x, y, cw, 36, "VERSIUNEA TA", mine == 0 ? "dev" : "#" + mine, Theme.MUTED);
        card(ms, x + cw + 8, y, cw, 36, "ULTIMA VERSIUNE", latest == 0 ? (UpdateChecker.checking ? "..." : "?") : "#" + latest,
            avail ? Theme.OK : Theme.ACCENT);
        y += 44;

        String msg;
        int mc;
        if (UpdateChecker.checking && latest == 0) {
            msg = "Se verifica" + "...".substring(0, (int) ((now / 350) % 4));
            mc = Theme.MUTED;
        } else if (latest == 0) {
            msg = "Nu am putut verifica (fara internet sau GitHub indisponibil).";
            mc = Theme.WARN;
        } else if (avail) {
            msg = "Exista o versiune noua!";
            mc = Theme.OK;
        } else {
            msg = "Esti la zi.";
            mc = Theme.OK;
        }
        Ui.text(ms, msg, x, y, mc);
        y += 14;

        // schimbari
        Ui.text(ms, "SCHIMBARI", x, y, Theme.FAINT);
        Ui.rect(ms, x, y + 10, w, 1, Theme.LINE);
        y += 15;
        List<String> notes = UpdateChecker.notes;
        int limitY = py + ph - 44 - 34;
        for (String n : notes) {
            if (y + 10 > limitY) break;
            Ui.rect(ms, x + 1, y + 3, 3, 3, Theme.ACCENT);
            Ui.text(ms, Ui.clip(n, w - 14), x + 8, y, Theme.TEXT);
            y += 11;
        }
        if (notes.isEmpty()) Ui.text(ms, "-", x + 8, y, Theme.FAINT);

        // jos: progres + butoane
        int by = py + ph - 44;
        if (UpdateChecker.downloading || UpdateChecker.downloaded) {
            Ui.bar(ms, x, by - 14, w, 6, UpdateChecker.progress, UpdateChecker.downloaded ? Theme.OK : Theme.ACCENT);
        }
        String st = UpdateChecker.status;
        if (UpdateChecker.downloaded) {
            Ui.text(ms, "Gata! Inchide jocul si pune fisierul nou in mods/", x, by - 28, Theme.OK);
            Ui.text(ms, "(sterge-l pe cel vechi).", x, by - 38, Theme.MUTED);
        } else if (!st.isEmpty()) {
            Ui.text(ms, Ui.clip(st, w), x, by - 26, st.startsWith("Eroare") ? Theme.BAD : Theme.MUTED);
        }
        int bw = (w - 12) / 3;
        button(ms, UpdateChecker.downloading ? "SE DESCARCA..." : "DESCARCA UPDATE", x, by, bw, 18, mx, my,
            avail && !UpdateChecker.downloading && !UpdateChecker.downloaded, Theme.OK, UpdateChecker::download);
        button(ms, "DESCHIDE FOLDER", x + bw + 6, by, bw, 18, mx, my, UpdateChecker.downloaded, Theme.ACCENT_DIM, () -> {
            java.io.File d = UpdateChecker.updateDir();
            if (d.isDirectory()) Util.getOSType().openFile(d);
            java.io.File m = UpdateChecker.modsDir();
            if (m.isDirectory()) Util.getOSType().openFile(m);
        });
        button(ms, "INCHIDE", x + 2 * (bw + 6), by, bw, 18, mx, my, true, Theme.PANEL_3, this::closeScreen);
        Ui.text(ms, "Descarcarea vine din GitHub-ul FlowCity. Modul nu se instaleaza singur.", x, py + ph - 16, Theme.FAINT);
    }

    private void card(MatrixStack ms, int x, int y, int w, int h, String label, String value, int color) {
        Ui.frame(ms, x, y, w, h, Theme.PANEL, Theme.mix(Theme.LINE, color, 0.4f));
        Ui.rect(ms, x + 1, y + 1, w - 2, 2, color);
        Ui.text(ms, label, x + 7, y + 8, Theme.FAINT);
        ms.push();
        ms.translate(x + 7, y + 19, 0);
        ms.scale(1.4f, 1.4f, 1f);
        Ui.textShadow(ms, value, 0, 0, color);
        ms.pop();
    }
}
