package ro.solarstudios.flowcity.battlepass;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.EditBox;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Editorul de battlepass (doar pentru staff): SEZON (nume, perioada, niveluri, XP pe nivel, cost premium),
 * NIVELURI (recompensa FREE si PREMIUM a fiecarui nivel) si MISIUNI (zilnice / saptamanale, cu tipul sursei reale).
 * Totul se salveaza pe server, in battlepass.yml.
 */
public final class BattlepassAdminScreen extends FlowScreen {
    private static final String[] TABS = {"SEZON", "NIVELURI", "MISIUNI"};
    private static final String[] REWARD_TYPES = {"", "money", "bank", "item", "xp", "command"};
    private static final String[] MISSION_TYPES = {"missions", "actions", "earn", "xp", "hours", "drive", "fuel", "shop", "phone"};
    /** Ce masoara fiecare tip de misiune, ca adminul sa stie ce pune la "obiectiv". */
    private static final String[] MISSION_HINT = {
        "misiuni de job terminate", "actiuni platite in tura", "bani castigati ($)", "XP de job",
        "minute lucrate in tura", "metri condusi", "litri alimentati", "articole cumparate", "deschideri de telefon"
    };

    // sezon
    private final EditBox sName = field("nume sezon", 32, null);
    private final EditBox sDescr = field("descriere", 96, null);
    private final EditBox sStart = field("inceput (AAAA-LL-ZZ)", 10, null);
    private final EditBox sEnd = field("sfarsit (AAAA-LL-ZZ)", 10, null);
    private final EditBox sLevels = field("niveluri", 3, null);
    private final EditBox sXp = field("XP / nivel", 6, null);
    private final EditBox sCost = field("cost premium", 10, null);
    private final EditBox sNewId = field("id sezon nou", 16, null);
    // recompensa
    private final EditBox rAmount = field("cantitate / suma", 10, null);
    private final EditBox rMaterial = field("material", 48, null);
    private final EditBox rCommand = field("comanda (fara /)", 180, null);
    private final EditBox rLabel = field("eticheta (optional)", 32, null);
    // misiune
    private final EditBox mId = field("id", 24, null);
    private final EditBox mLabel = field("text afisat", 48, null);
    private final EditBox mTarget = field("obiectiv", 9, null);
    private final EditBox mXp = field("XP", 6, null);
    private final EditBox mJob = field("job (optional)", 24, null);

    private final List<EditBox> all = new ArrayList<>();
    private int tab;
    private int scroll;
    private int selLevel = 1;
    private String selTrack = "free";
    private String rType = "money";
    private String mSection = "daily";
    private String mType = "actions";
    private boolean loaded;

    public BattlepassAdminScreen() {
        super("Battlepass - editor", 540, 300);
        java.util.Collections.addAll(all, sName, sDescr, sStart, sEnd, sLevels, sXp, sCost, sNewId,
            rAmount, rMaterial, rCommand, rLabel, mId, mLabel, mTarget, mXp, mJob);
    }

    @Override
    protected void init() {
        BattlepassClient.requestAdmin();
    }

    @Override
    protected List<EditBox> fields() {
        return all;
    }

    /** Date noi de la server. */
    public void refreshed() {
        loadSeason();
        loadReward();
    }

    private void loadSeason() {
        BattlepassClient.AdminSeason s = BattlepassClient.adminSeason;
        sName.setValue(s.name);
        sDescr.setValue(s.descr);
        sStart.setValue(s.start);
        sEnd.setValue(s.end);
        sLevels.setValue(String.valueOf(s.levels));
        sXp.setValue(String.valueOf(s.xpPerLevel));
        sCost.setValue(String.valueOf((long) s.premiumCost));
        loaded = true;
    }

    private void loadReward() {
        rAmount.setValue("");
        rMaterial.setValue("");
        rCommand.setValue("");
        for (BattlepassClient.Reward r : BattlepassClient.adminRewards) {
            if (r.level == selLevel) {
                rLabel.setValue("free".equals(selTrack) ? r.free : r.premium);
                return;
            }
        }
        rLabel.setValue("");
    }

    private static int intOr(String s, int d) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return d;
        }
    }

    private static double dblOr(String s, double d) {
        try {
            return Double.parseDouble(s.trim().replace(',', '.'));
        } catch (RuntimeException e) {
            return d;
        }
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        if (!loaded && BattlepassClient.adminLoaded) refreshed();
        header(g, "Battlepass", "editor · sezon " + BattlepassClient.adminSeason.id, mx, my);
        for (EditBox f : all) {
            f.setVisible(false);
            f.setX(-1000);
            f.setY(-1000);
        }
        int x0 = px + 12;
        int y = py + HEAD + 8;
        int cx = x0;
        for (int i = 0; i < TABS.length; i++) {
            final int fi = i;
            cx += chip(g, TABS[i], cx, y, 72, 15, mx, my, tab == i, Theme.ACCENT, () -> {
                tab = fi;
                scroll = 0;
            }) + 4;
        }
        y += 22;
        if (tab == 0) season(g, mx, my, x0, y);
        else if (tab == 1) levels(g, mx, my, x0, y);
        else missions(g, mx, my, x0, y);
    }

    private void show(EditBox f) {
        f.setVisible(true);
    }

    // ------------------------------------------------------------------ SEZON

    private void season(GuiGraphics g, int mx, int my, int x, int y) {
        int w = (pw - 36) / 2;
        BattlepassClient.AdminSeason s = BattlepassClient.adminSeason;
        Ui.text(g, s.running ? "Sezon in desfasurare" : "In afara perioadei: progresul e oprit", x, y,
            s.running ? Theme.OK : Theme.WARN);
        y += 14;
        Ui.text(g, "Nume", x, y, Theme.MUTED);
        Ui.text(g, "Descriere", x + w + 12, y, Theme.MUTED);
        show(sName);
        show(sDescr);
        drawField(g, sName, x, y + 10, w, 16, mx, my);
        drawField(g, sDescr, x + w + 12, y + 10, w, 16, mx, my);
        y += 32;
        Ui.text(g, "Inceput (gol = fara limita)", x, y, Theme.MUTED);
        Ui.text(g, "Sfarsit (gol = fara limita)", x + w + 12, y, Theme.MUTED);
        show(sStart);
        show(sEnd);
        drawField(g, sStart, x, y + 10, w, 16, mx, my);
        drawField(g, sEnd, x + w + 12, y + 10, w, 16, mx, my);
        y += 32;
        int third = (pw - 48) / 3;
        Ui.text(g, "Niveluri", x, y, Theme.MUTED);
        Ui.text(g, "XP pe nivel", x + third + 12, y, Theme.MUTED);
        Ui.text(g, "Cost premium ($)", x + 2 * (third + 12), y, Theme.MUTED);
        show(sLevels);
        show(sXp);
        show(sCost);
        drawField(g, sLevels, x, y + 10, third, 16, mx, my);
        drawField(g, sXp, x + third + 12, y + 10, third, 16, mx, my);
        drawField(g, sCost, x + 2 * (third + 12), y + 10, third, 16, mx, my);
        y += 34;
        button(g, BattlepassClient.adminSeason.enabled ? "BATTLEPASS PORNIT" : "BATTLEPASS OPRIT", x, y, w, 16, mx, my,
            true, BattlepassClient.adminSeason.enabled ? Theme.OK : Theme.PANEL_3, () -> {
                JsonObject o = new JsonObject();
                o.addProperty("enabled", !BattlepassClient.adminSeason.enabled);
                BattlepassClient.action("admin_season", o);
            });
        button(g, "SALVEAZA SEZONUL", x + w + 12, y, w, 16, mx, my, true, Theme.ACCENT, this::saveSeason);
        y += 26;
        Ui.text(g, "Sezon nou: progresul vechi ramane salvat sub id-ul lui.", x, y, Theme.FAINT);
        y += 12;
        show(sNewId);
        drawField(g, sNewId, x, y, w, 16, mx, my);
        button(g, "CREEAZA SEZON", x + w + 12, y, w, 16, mx, my, !sNewId.getValue().trim().isEmpty(), Theme.WARN, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("newId", sNewId.getValue().trim());
            o.addProperty("name", sName.getValue().trim());
            BattlepassClient.action("admin_season", o);
            sNewId.setValue("");
        });
    }

    private void saveSeason() {
        JsonObject o = new JsonObject();
        o.addProperty("name", sName.getValue().trim());
        o.addProperty("descr", sDescr.getValue().trim());
        o.addProperty("start", sStart.getValue().trim());
        o.addProperty("end", sEnd.getValue().trim());
        o.addProperty("levels", intOr(sLevels.getValue(), 30));
        o.addProperty("xpPerLevel", intOr(sXp.getValue(), 100));
        o.addProperty("premiumCost", dblOr(sCost.getValue(), 0));
        BattlepassClient.action("admin_season", o);
    }

    // ------------------------------------------------------------------ NIVELURI

    private void levels(GuiGraphics g, int mx, int my, int x, int y) {
        int listW = 180;
        int rows = (ph - HEAD - 50) / 15;
        List<BattlepassClient.Reward> list = BattlepassClient.adminRewards;
        Ui.text(g, "Nivel · FREE · PREMIUM", x, y, Theme.MUTED);
        int ly = y + 12;
        for (int i = 0; i < rows && i + scroll < list.size(); i++) {
            BattlepassClient.Reward r = list.get(i + scroll);
            int yy = ly + i * 15;
            boolean sel = r.level == selLevel;
            Ui.round(g, x, yy, listW, 14, sel ? Theme.PANEL_3 : Theme.PANEL_2);
            Ui.text(g, String.valueOf(r.level), x + 5, yy + 3, sel ? Theme.ACCENT : Theme.TEXT);
            Ui.text(g, Ui.clip(r.free.isEmpty() ? "-" : r.free, 62), x + 28, yy + 3, Theme.TEXT);
            Ui.text(g, Ui.clip(r.premium.isEmpty() ? "-" : r.premium, 62), x + 108, yy + 3, Theme.WARN);
            final int lv = r.level;
            hit(x, yy, listW, 14, () -> {
                selLevel = lv;
                loadReward();
            });
        }
        if (list.size() > rows) Ui.text(g, "roata mouse = deruleaza", x, py + ph - 14, Theme.FAINT);

        int rx = x + listW + 14;
        int rw = pw - listW - 38;
        int ry = y;
        Ui.text(g, "Nivelul " + selLevel, rx, ry, Theme.ACCENT);
        ry += 12;
        int half = (rw - 6) / 2;
        chip(g, "FREE", rx, ry, half, 14, mx, my, "free".equals(selTrack), Theme.ACCENT, () -> {
            selTrack = "free";
            loadReward();
        });
        chip(g, "PREMIUM", rx + half + 6, ry, half, 14, mx, my, "premium".equals(selTrack), Theme.WARN, () -> {
            selTrack = "premium";
            loadReward();
        });
        ry += 20;
        int tw = (rw - 15) / 6;
        int tx = rx;
        for (String t : REWARD_TYPES) {
            final String ft = t;
            chip(g, t.isEmpty() ? "-" : t, tx, ry, tw, 13, mx, my, rType.equals(t), Theme.ACCENT, () -> rType = ft);
            tx += tw + 3;
        }
        ry += 19;
        Ui.text(g, "command".equals(rType) ? "Valoare (%amount%)" : "Cantitate / suma", rx, ry, Theme.MUTED);
        show(rAmount);
        drawField(g, rAmount, rx, ry + 10, half, 15, mx, my);
        if ("item".equals(rType)) {
            Ui.text(g, "Material", rx + half + 6, ry, Theme.MUTED);
            show(rMaterial);
            drawField(g, rMaterial, rx + half + 6, ry + 10, half, 15, mx, my);
        }
        ry += 30;
        if ("command".equals(rType)) {
            Ui.text(g, "Comanda de consola (fara /, %player%)", rx, ry, Theme.MUTED);
            show(rCommand);
            drawField(g, rCommand, rx, ry + 10, rw, 15, mx, my);
            ry += 30;
        }
        Ui.text(g, "Eticheta afisata (gol = generata)", rx, ry, Theme.MUTED);
        show(rLabel);
        drawField(g, rLabel, rx, ry + 10, rw, 15, mx, my);
        ry += 32;
        button(g, "SALVEAZA RECOMPENSA", rx, ry, rw, 16, mx, my, !rType.isEmpty(), Theme.ACCENT, this::saveReward);
        ry += 20;
        button(g, "STERGE RECOMPENSA", rx, ry, rw, 16, mx, my, true, Theme.PANEL_3, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("level", selLevel);
            o.addProperty("track", selTrack);
            o.addProperty("type", "");
            BattlepassClient.action("admin_reward", o);
        });
    }

    private void saveReward() {
        JsonObject o = new JsonObject();
        o.addProperty("level", selLevel);
        o.addProperty("track", selTrack);
        o.addProperty("type", rType);
        o.addProperty("amount", dblOr(rAmount.getValue(), 0));
        o.addProperty("material", rMaterial.getValue().trim());
        o.addProperty("command", rCommand.getValue().trim());
        o.addProperty("label", rLabel.getValue().trim());
        BattlepassClient.action("admin_reward", o);
    }

    // ------------------------------------------------------------------ MISIUNI

    private void missions(GuiGraphics g, int mx, int my, int x, int y) {
        List<BattlepassClient.Mission> list = "daily".equals(mSection) ? BattlepassClient.adminDaily : BattlepassClient.adminWeekly;
        int listW = 196;
        chip(g, "ZILNICE", x, y, 90, 14, mx, my, "daily".equals(mSection), Theme.ACCENT, () -> {
            mSection = "daily";
            scroll = 0;
        });
        chip(g, "SAPTAMANALE", x + 96, y, 96, 14, mx, my, "weekly".equals(mSection), Theme.ACCENT, () -> {
            mSection = "weekly";
            scroll = 0;
        });
        y += 20;
        int rows = (ph - HEAD - 60) / 15;
        for (int i = 0; i < rows && i + scroll < list.size(); i++) {
            BattlepassClient.Mission m = list.get(i + scroll);
            int yy = y + i * 15;
            boolean sel = m.id.equals(mId.getValue());
            Ui.round(g, x, yy, listW, 14, sel ? Theme.PANEL_3 : Theme.PANEL_2);
            Ui.text(g, Ui.clip(m.label.isEmpty() ? m.id : m.label, 108), x + 5, yy + 3, Theme.TEXT);
            Ui.textRight(g, m.type + " " + m.target, x + listW - 5, yy + 3, Theme.MUTED);
            final BattlepassClient.Mission fm = m;
            hit(x, yy, listW, 14, () -> {
                mId.setValue(fm.id);
                mLabel.setValue(fm.label);
                mTarget.setValue(String.valueOf(fm.target));
                mXp.setValue(String.valueOf(fm.xp));
                mJob.setValue(fm.job);
                mType = fm.type;
            });
        }
        if (list.size() > rows) Ui.text(g, "roata mouse = deruleaza", x, py + ph - 14, Theme.FAINT);

        int rx = x + listW + 14;
        int rw = pw - listW - 38;
        int ry = y;
        int tw = (rw - 24) / 5;
        int tx = rx;
        int line = 0;
        for (int i = 0; i < MISSION_TYPES.length; i++) {
            if (i == 5) {
                tx = rx;
                line++;
            }
            final String ft = MISSION_TYPES[i];
            chip(g, MISSION_TYPES[i], tx, ry + line * 16, tw, 13, mx, my, mType.equals(ft), Theme.ACCENT, () -> mType = ft);
            tx += tw + 6;
        }
        ry += 36;
        int idx = java.util.Arrays.asList(MISSION_TYPES).indexOf(mType);
        Ui.text(g, "Obiectiv: " + (idx >= 0 ? MISSION_HINT[idx] : ""), rx, ry, Theme.FAINT);
        ry += 12;
        int half = (rw - 6) / 2;
        Ui.text(g, "ID (unic)", rx, ry, Theme.MUTED);
        Ui.text(g, "Job (optional)", rx + half + 6, ry, Theme.MUTED);
        show(mId);
        show(mJob);
        drawField(g, mId, rx, ry + 10, half, 15, mx, my);
        drawField(g, mJob, rx + half + 6, ry + 10, half, 15, mx, my);
        ry += 30;
        Ui.text(g, "Text afisat", rx, ry, Theme.MUTED);
        show(mLabel);
        drawField(g, mLabel, rx, ry + 10, rw, 15, mx, my);
        ry += 30;
        Ui.text(g, "Obiectiv", rx, ry, Theme.MUTED);
        Ui.text(g, "XP acordat", rx + half + 6, ry, Theme.MUTED);
        show(mTarget);
        show(mXp);
        drawField(g, mTarget, rx, ry + 10, half, 15, mx, my);
        drawField(g, mXp, rx + half + 6, ry + 10, half, 15, mx, my);
        ry += 32;
        boolean ok = !mId.getValue().trim().isEmpty();
        button(g, "SALVEAZA", rx, ry, half, 16, mx, my, ok, Theme.ACCENT, () -> sendMission(false));
        button(g, "STERGE", rx + half + 6, ry, half, 16, mx, my, ok, Theme.PANEL_3, () -> sendMission(true));
    }

    private void sendMission(boolean remove) {
        JsonObject o = new JsonObject();
        o.addProperty("section", mSection);
        o.addProperty("id", mId.getValue().trim().toLowerCase(Locale.ROOT));
        o.addProperty("remove", remove);
        o.addProperty("type", mType);
        o.addProperty("job", mJob.getValue().trim());
        o.addProperty("target", dblOr(mTarget.getValue(), 1));
        o.addProperty("xp", intOr(mXp.getValue(), 10));
        o.addProperty("label", mLabel.getValue().trim());
        BattlepassClient.action("admin_mission", o);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        int size = tab == 1 ? BattlepassClient.adminRewards.size()
            : "daily".equals(mSection) ? BattlepassClient.adminDaily.size() : BattlepassClient.adminWeekly.size();
        int rows = (ph - HEAD - 60) / 15;
        scroll = Math.max(0, Math.min(Math.max(0, size - rows), scroll - (int) Math.signum(delta)));
        return true;
    }
}
