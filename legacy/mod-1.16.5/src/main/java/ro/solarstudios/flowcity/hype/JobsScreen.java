package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.matrix.MatrixStack;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Alegerea jobului: lista cu nivel, progres si buton de selectare. */
public final class JobsScreen extends FlowScreen {
    public JobsScreen() {
        super("Joburi", 340, 220);
    }

    private void request(String action, String id) {
        JsonObject o = new JsonObject();
        if (id != null) o.addProperty("id", id);
        click();
        HypeClient.request(action, o);
    }

    @Override
    protected void draw(MatrixStack ms, int mx, int my) {
        JsonObject d = HypeState.jobs;
        String current = JsonClientModule.str(d, "current", "");
        long cd = JsonClientModule.lng(d, "cooldownMs", 0) - (System.currentTimeMillis() - HypeState.jobsAt);
        header(ms, "Joburi", "alege-ti meseria", mx, my);
        int x = px + 10;
        int y = py + HEAD + 8;
        int w = pw - 20;
        if (cd > 0 && !current.isEmpty()) {
            Ui.text(ms, "Poti schimba jobul peste " + Ui.duration(cd / 1000 + 1) , x, y, Theme.WARN);
        } else {
            Ui.text(ms, "Bonus: +" + Math.round(JsonClientModule.num(d, "bonus", 0) * 100) + "% plata pe nivel", x, y, Theme.MUTED);
        }
        if (!current.isEmpty()) {
            button(ms, "Paraseste", x + w - 64, y - 3, 64, 14, mx, my, cd <= 0, 0xFF5B2A35, () -> request("job_leave", null));
        }
        y += 14;
        if (!d.has("jobs") || !d.get("jobs").isJsonArray()) {
            Ui.text(ms, "Se incarca...", x, y, Theme.FAINT);
            return;
        }
        int rowH = Math.max(20, Math.min(30, (ph - HEAD - 36) / Math.max(1, d.getAsJsonArray("jobs").size())));
        for (JsonElement el : d.getAsJsonArray("jobs")) {
            if (!el.isJsonObject()) continue;
            JsonObject j = el.getAsJsonObject();
            String id = JsonClientModule.str(j, "id", "");
            boolean active = id.equals(current);
            Ui.frame(ms, x, y, w, rowH - 3, active ? Theme.mix(Theme.PANEL, Theme.ACCENT, 0.15f) : Theme.PANEL_2, active ? Theme.ACCENT : Theme.LINE);
            Ui.text(ms, JsonClientModule.str(j, "name", id), x + 6, y + 3, Theme.TEXT);
            Ui.text(ms, "Niv " + JsonClientModule.integer(j, "level", 1), x + 78, y + 3, Theme.ACCENT);
            Ui.text(ms, Ui.clip(JsonClientModule.str(j, "summary", ""), w - 250), x + 6, y + 12, Theme.FAINT);
            long need = JsonClientModule.lng(j, "xpNeed", 0);
            float frac = need <= 0 ? 1f : (float) JsonClientModule.lng(j, "xp", 0) / need;
            Ui.bar(ms, x + 120, y + 4, 70, 6, frac, Theme.VIOLET);
            Ui.textRight(ms, "$" + Ui.money(JsonClientModule.num(j, "earned", 0)), x + w - 72, y + 3, Theme.OK);
            boolean can = !active && (current.isEmpty() || cd <= 0);
            button(ms, active ? "Activ" : "Alege", x + w - 62, y + 2, 56, 14, mx, my, can, Theme.ACCENT_DIM, () -> request("job_select", id));
            y += rowH;
        }
    }
}
