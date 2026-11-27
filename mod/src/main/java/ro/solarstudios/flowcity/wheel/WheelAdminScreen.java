package ro.solarstudios.flowcity.wheel;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Ui;

/**
 * eAdmin &gt; Roata: trei file - PREMII (ce poate pica, cu sanse, tip, descriere, iconita, activ/inactiv),
 * SETARI (cooldown, cost, rotiri gratuite, maxim pe zi, animatie, pornit/oprit) si STATISTICI
 * (cate rotiri, ce premii pica des, cine a castigat). Tema de iarna FlowCity.
 */
public final class WheelAdminScreen extends FlowScreen {
    private static final int ROW = 17;
    private static final int LIST_W = 196;
    private static final String[] TABS = {"PREMII", "SETARI", "STATISTICI"};
    /** Tipurile acceptate de server, cu eticheta lor. */
    private static final String[][] TYPES = {
        {"item", "Item"}, {"money", "Cash"}, {"bank", "Banca"},
        {"xp", "XP"}, {"bpxp", "XP pass"}, {"bplevel", "Nivel pass"}, {"command", "Special"}
    };

    private final EditBox material = field("material (ex. DIAMOND)", 48, null);
    private final EditBox name = field("nume (optional)", 24, null);
    private final EditBox descr = field("descriere (optional)", 48, null);
    private final EditBox amount = field("cantitate / suma", 10, null);
    private final EditBox weight = field("sansa (1-1000)", 4, null);
    private final EditBox color = field("#RRGGBB", 7, null);
    private final EditBox icon = field("iconita (ex. EMERALD)", 48, null);
    private final EditBox command = field("comanda, fara / (%player%)", 180, null);
    // setari
    private final EditBox cCooldown = field("ore", 6, null);
    private final EditBox cCost = field("cost rotire", 10, null);
    private final EditBox cFree = field("gratuite/zi", 3, null);
    private final EditBox cMax = field("max/zi", 3, null);
    private final EditBox cAnim = field("secunde", 3, null);

    private final List<EditBox> all = new ArrayList<>();
    private long selected = -1;
    private String newType = "item";
    private boolean announce;
    private boolean enabled = true;
    private boolean cfgEnabled = true;
    private int tab;
    private int scroll;
    private boolean cfgLoaded;

    public WheelAdminScreen() {
        super("Roata - administrare", 466, 282);
        all.add(material);
        all.add(name);
        all.add(descr);
        all.add(amount);
        all.add(weight);
        all.add(color);
        all.add(icon);
        all.add(command);
        all.add(cCooldown);
        all.add(cCost);
        all.add(cFree);
        all.add(cMax);
        all.add(cAnim);
        weight.setValue("10");
    }

    @Override
    protected void init() {
        WheelClient.sendAction("admin_list", new JsonObject());
    }

    @Override
    protected List<EditBox> fields() {
        return all;
    }

    /** Date noi de la server. */
    public void refreshed() {
        if (selected >= 0 && find(selected) == null) selected = -1;
        int max = Math.max(0, WheelState.admin.size() - visibleRows());
        if (scroll > max) scroll = max;
        if (!cfgLoaded) loadCfg();
    }

    private void loadCfg() {
        WheelState.Cfg c = WheelState.cfg;
        cCooldown.setValue(trimNum(c.cooldownHours));
        cCost.setValue(trimNum(c.cost));
        cFree.setValue(String.valueOf(c.freeSpins));
        cMax.setValue(String.valueOf(c.maxPerDay));
        cAnim.setValue(String.valueOf(c.animSeconds));
        cfgEnabled = c.enabled;
        cfgLoaded = true;
    }

    private static String trimNum(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private WheelState.AdminPrize find(long id) {
        for (WheelState.AdminPrize p : WheelState.admin) if (p.id == id) return p;
        return null;
    }

    private int visibleRows() {
        return Math.max(4, (ph - HEAD - 50) / ROW);
    }

    private void select(WheelState.AdminPrize p) {
        selected = p.id;
        newType = p.type;
        material.setValue(p.material);
        name.setValue(p.name);
        descr.setValue(p.descr);
        amount.setValue(trimNum(p.amount));
        weight.setValue(String.valueOf(p.weight));
        color.setValue(p.color);
        icon.setValue(p.icon);
        command.setValue(p.command);
        announce = p.announce;
        enabled = p.enabled;
    }

    private void clearForm() {
        selected = -1;
        material.setValue("");
        name.setValue("");
        descr.setValue("");
        amount.setValue("");
        weight.setValue("10");
        color.setValue("");
        icon.setValue("");
        command.setValue("");
        announce = false;
        enabled = true;
    }

    private static int parse(String hex, int fallback) {
        try {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return 0xFF000000 | Integer.parseInt(h, 16);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    private static ItemStack icon(String material) {
        if (material == null || material.isEmpty()) return ItemStack.EMPTY;
        String id = material.toLowerCase(Locale.ROOT);
        ResourceLocation rl = id.indexOf(':') > 0 ? new ResourceLocation(id) : new ResourceLocation("minecraft", id);
        Item it = ForgeRegistries.ITEMS.getValue(rl);
        return it == null ? ItemStack.EMPTY : new ItemStack(it);
    }

    /** Iconita unui premiu: cea aleasa de admin, altfel itemul premiului. */
    private static ItemStack prizeIcon(WheelState.AdminPrize p) {
        ItemStack st = icon(p.icon);
        if (!st.isEmpty()) return st;
        return "item".equals(p.type) ? icon(p.material) : ItemStack.EMPTY;
    }

    private static String typeShort(String type) {
        for (String[] t : TYPES) if (t[0].equals(type)) return t[1];
        return type;
    }

    private void fill(JsonObject o, boolean forAdd) {
        if (forAdd) o.addProperty("type", newType);
        if ("item".equals(newType) && !material.getValue().trim().isEmpty()) o.addProperty("material", material.getValue().trim());
        if (!amount.getValue().trim().isEmpty()) {
            o.addProperty("amount", Double.parseDouble(amount.getValue().trim().replace(',', '.')));
        }
        o.addProperty("name", name.getValue().trim());
        o.addProperty("descr", descr.getValue().trim());
        o.addProperty("icon", icon.getValue().trim());
        o.addProperty("command", command.getValue().trim());
        o.addProperty("weight", intOr(weight.getValue(), 10));
        o.addProperty("color", color.getValue().trim());
        o.addProperty("announce", announce);
    }

    private void sendAdd() {
        JsonObject o = new JsonObject();
        try {
            fill(o, true);
        } catch (NumberFormatException e) {
            toast("Cantitate invalida.", true);
            return;
        }
        WheelClient.sendAction("admin_add", o);
    }

    private void sendSave() {
        if (selected < 0) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", selected);
        try {
            fill(o, false);
        } catch (NumberFormatException e) {
            toast("Cantitate invalida.", true);
            return;
        }
        o.addProperty("enabled", enabled);
        WheelClient.sendAction("admin_update", o);
    }

    private void sendCfg() {
        JsonObject o = new JsonObject();
        o.addProperty("enabled", cfgEnabled);
        try {
            o.addProperty("cooldown-hours", Double.parseDouble(cCooldown.getValue().trim().replace(',', '.')));
            o.addProperty("cost", Double.parseDouble(cCost.getValue().trim().replace(',', '.')));
        } catch (NumberFormatException e) {
            toast("Numar invalid.", true);
            return;
        }
        o.addProperty("free-spins", intOr(cFree.getValue(), 1));
        o.addProperty("max-per-day", intOr(cMax.getValue(), 1));
        o.addProperty("animation-seconds", intOr(cAnim.getValue(), 6));
        cfgLoaded = false;
        WheelClient.sendAction("admin_cfg", o);
    }

    private static int intOr(String s, int d) {
        try {
            return Integer.parseInt(s.trim());
        } catch (RuntimeException e) {
            return d;
        }
    }

    private void move(int dir) {
        if (selected < 0) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", selected);
        o.addProperty("dir", dir);
        WheelClient.sendAction("admin_move", o);
    }

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected void header(GuiGraphics ms, String title, String subtitle, int mx, int my) {
        Gfx.text(ms, title.toUpperCase(Locale.ROOT), px + 14, py + 17, 11, Sk.WHITE, 0, true, 0f);
        int tx = px + 24 + (int) Gfx.width(title.toUpperCase(Locale.ROOT), 11, true);
        for (int i = 0; i < TABS.length; i++) {
            final int fi = i;
            int w = 62;
            chip(ms, TABS[i], tx, py + 11, w, 15, mx, my, tab == i, Sk.WARN, () -> {
                tab = fi;
                if (fi == 2 && !WheelState.statsLoaded) WheelClient.sendAction("admin_stats", new JsonObject());
            });
            tx += w + 4;
        }
        Gfx.rrect(ms, px + 10, py + HEAD, pw - 20, 1, 0, 0xFF22344D);
        Sk.close(ms, px + pw - 28, py + 2, mx, my);
        hit(px + pw - 28, py + 2, 20, 20, this::onClose);
    }

    @Override
    protected void button(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my,
                          boolean enabled, int color, Runnable action) {
        boolean hover = enabled && Ui.in(mx, my, x, y, w, h);
        int bg;
        int fg = 0xFF061426;
        if (!enabled) {
            bg = 0xFF14202F;
            fg = Sk.FAINT;
        } else if (color == Sk.ACC) {
            bg = hover ? 0xFF7DBBFF : Sk.ACC;
        } else if (color == Sk.GOOD || color == Sk.BAD) {
            bg = Gfx.mixArgb(color, 0xFFFFFFFF, hover ? 0.18f : 0f);
        } else {
            bg = hover ? 0xFF22344D : 0xFF1B2839;
            fg = hover ? Sk.WHITE : 0xFFC9CBD0;
        }
        Sk.slant(ms, x, y, w, h, Math.min(5f, h / 3f), bg);
        Ui.textCenter(ms, Ui.clip(label, w - 10), x + w / 2 + 1, y + (h - 8) / 2, fg);
        if (enabled) hit(x, y, w, h, action);
    }

    @Override
    protected int chip(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my,
                       boolean selected, int color, Runnable action) {
        boolean hover = Ui.in(mx, my, x, y, w, h);
        Sk.slant(ms, x, y, w, h, Math.min(4f, h / 3f), selected ? Sk.WARN : (hover ? Sk.ROW_HOT : Sk.ROW));
        Ui.textCenter(ms, label, x + w / 2 + 1, y + (h - 8) / 2, selected ? 0xFF061426 : (hover ? Sk.WHITE : Sk.MUTED));
        hit(x, y, w, h, action);
        return w;
    }

    @Override
    protected void drawField(GuiGraphics ms, EditBox f, int x, int y, int w, int h, int mx, int my) {
        Ui.rect(ms, x, y, w, h, 0xFF0B1422);
        Ui.rect(ms, x, y + h - 1, w, 1, f.isFocused() ? Sk.ACC : 0xFF22344D);
        f.x = x + 5;
        f.y = y + (h - 8) / 2;
        f.setWidth(w - 10);
        f.render(ms.pose(), mx, my, 0);
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        Sk.background(ms, 0, 0, width, height, 1f);
        Sk.cut(ms, px, py, pw, ph, 12f, Sk.PANEL);
        header(ms, "Roata norocului", "", mx, my);
        // campurile nedesenate in fila curenta se muta in afara ecranului, ca sa nu prinda click-uri
        for (EditBox f : all) {
            f.setVisible(false);
            f.x = -1000;
            f.y = -1000;
        }
        if (tab == 0) drawPrizes(ms, mx, my);
        else if (tab == 1) drawSettings(ms, mx, my);
        else drawStats(ms, mx, my);
    }

    // ------------------------------------------------------------------ fila PREMII

    private void drawPrizes(GuiGraphics ms, int mx, int my) {
        List<WheelState.AdminPrize> list = WheelState.admin;
        int lx = px + 8;
        int ly = py + HEAD + 8;
        int on = 0;
        for (WheelState.AdminPrize p : list) if (p.enabled) on++;
        Ui.text(ms, list.size() + "/" + WheelState.adminMax + " premii · " + on + " active", lx, ly, Sk.MUTED);
        ly += 12;
        int rows = visibleRows();
        for (int i = 0; i < rows && i + scroll < list.size(); i++) {
            WheelState.AdminPrize p = list.get(i + scroll);
            int y = ly + i * ROW;
            boolean sel = p.id == selected;
            boolean hover = Ui.in(mx, my, lx, y, LIST_W, ROW - 1);
            Sk.slant(ms, lx, y, LIST_W, ROW - 1, 4f, sel || hover ? Sk.ROW_HOT : Sk.ROW);
            if (sel) Ui.rect(ms, lx + 5, y + ROW - 2, LIST_W - 10, 1, Sk.ACC);
            Ui.rect(ms, lx + 6, y + 2, 3, ROW - 5, p.enabled ? parse(p.color, Sk.ACC) : 0xFF3A4150);
            ItemStack st = prizeIcon(p);
            if (!st.isEmpty()) ms.renderItem(st, lx + 8, y);
            else Ui.text(ms, typeShort(p.type).substring(0, 1), lx + 12, y + 4, Sk.WARN);
            Ui.text(ms, Ui.clip(p.name, 92), lx + 27, y + 4, p.enabled ? Sk.WHITE : Sk.FAINT);
            Ui.textRight(ms, p.enabled ? String.format(Locale.US, "%.1f%%", p.pct) : "oprit", lx + LIST_W - 8, y + 4, Sk.MUTED);
            final WheelState.AdminPrize fp = p;
            hit(lx, y, LIST_W, ROW - 1, () -> select(fp));
        }
        if (list.size() > rows) Ui.text(ms, "roata mouse = deruleaza", lx, py + ph - 14, Sk.FAINT);

        // panoul din dreapta
        int rx = px + LIST_W + 20;
        int rw = pw - LIST_W - 28;
        int y = py + HEAD + 8;
        Ui.text(ms, selected >= 0 ? "Editezi premiul selectat" : "Premiu nou", rx, y, Sk.ACC);
        y += 12;
        // tipuri
        int cx = rx;
        for (String[] t : TYPES) {
            int w = 40;
            if (cx + w > rx + rw) {
                cx = rx;
                y += 15;
            }
            final String ft = t[0];
            boolean on2 = newType.equals(t[0]);
            chip(ms, t[1], cx, y, w, 13, mx, my, on2, Sk.WARN, () -> {
                if (selected < 0) newType = ft;
                else toast("Tipul nu se schimba la un premiu existent (sterge-l si adauga altul).", true);
            });
            cx += w + 3;
        }
        y += 18;
        int half = (rw - 6) / 2;
        boolean isItem = "item".equals(newType);
        boolean isCmd = "command".equals(newType);
        label(ms, isItem ? "Material (gol = itemul din mana)" : "Material (doar la tip Item)", rx, y);
        show(material);
        drawField(ms, material, rx, y + 9, rw, 15, mx, my);
        y += 28;
        label(ms, "Nume afisat", rx, y);
        label(ms, "Iconita (item)", rx + half + 6, y);
        show(name);
        show(icon);
        drawField(ms, name, rx, y + 9, half, 15, mx, my);
        drawField(ms, icon, rx + half + 6, y + 9, half, 15, mx, my);
        y += 28;
        label(ms, "Descriere (apare pe roata)", rx, y);
        show(descr);
        drawField(ms, descr, rx, y + 9, rw, 15, mx, my);
        y += 28;
        label(ms, isCmd ? "Valoare (%amount%)" : "Cantitate / suma", rx, y);
        label(ms, "Sansa (greutate)", rx + half + 6, y);
        show(amount);
        show(weight);
        drawField(ms, amount, rx, y + 9, half, 15, mx, my);
        drawField(ms, weight, rx + half + 6, y + 9, half, 15, mx, my);
        y += 28;
        if (isCmd) {
            label(ms, "Comanda de consola (fara /)", rx, y);
            show(command);
            drawField(ms, command, rx, y + 9, rw, 15, mx, my);
        } else {
            label(ms, "Culoare", rx, y);
            show(color);
            drawField(ms, color, rx, y + 9, half - 20, 15, mx, my);
            int sw = parse(color.getValue(), 0);
            if (sw != 0) Ui.rect(ms, rx + half - 17, y + 9, 15, 15, sw);
            chip(ms, announce ? "Anunt: DA" : "Anunt: NU", rx + half + 6, y + 9, half, 15, mx, my, announce, Sk.WARN,
                () -> announce = !announce);
        }
        y += 28;
        if (isCmd) {
            chip(ms, announce ? "Anunt: DA" : "Anunt: NU", rx, y, half, 15, mx, my, announce, Sk.WARN, () -> announce = !announce);
            show(color);
            drawField(ms, color, rx + half + 6, y, half, 15, mx, my);
            y += 20;
        }
        if (selected >= 0) {
            chip(ms, enabled ? "ACTIV pe roata" : "DEZACTIVAT", rx, y, rw, 14, mx, my, enabled, Sk.GOOD, () -> enabled = !enabled);
            y += 18;
        }

        int bw = (rw - 6) / 3;
        button(ms, "ADAUGA", rx, y, bw, 15, mx, my, selected < 0, Sk.GOOD, this::sendAdd);
        button(ms, "SALVEAZA", rx + bw + 3, y, bw, 15, mx, my, selected >= 0, Sk.ACC, this::sendSave);
        button(ms, "NOU", rx + 2 * (bw + 3), y, bw, 15, mx, my, true, 0xFF1B2839, this::clearForm);
        y += 19;
        button(ms, "STERGE", rx, y, bw, 15, mx, my, selected >= 0, Sk.BAD, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", selected);
            WheelClient.sendAction("admin_delete", o);
        });
        button(ms, "SUS", rx + bw + 3, y, bw, 15, mx, my, selected >= 0, 0xFF1B2839, () -> move(-1));
        button(ms, "JOS", rx + 2 * (bw + 3), y, bw, 15, mx, my, selected >= 0, 0xFF1B2839, () -> move(1));
        y += 20;
        Ui.text(ms, hintFor(newType), rx, y, Sk.FAINT);
        Ui.text(ms, "Sansa = greutate; % se calculeaza din premiile active.", rx, y + 9, Sk.FAINT);
    }

    private static String hintFor(String type) {
        switch (type) {
            case "item":
                return "Item: tine-l in mana si lasa materialul gol.";
            case "bpxp":
                return "XP pass: XP de battlepass, nu XP de Minecraft.";
            case "bplevel":
                return "Nivel pass: niveluri intregi de battlepass.";
            case "command":
                return "Special: ex. fccars give %player% \"logan\" ... (vehicul/voucher).";
            case "xp":
                return "XP: experienta vanilla, data direct jucatorului.";
            default:
                return "Cash intra in buzunar, Banca in cont.";
        }
    }

    private void show(EditBox f) {
        f.setVisible(true);
    }

    // ------------------------------------------------------------------ fila SETARI

    private void drawSettings(GuiGraphics ms, int mx, int my) {
        if (!cfgLoaded) loadCfg();
        int x = px + 16;
        int y = py + HEAD + 14;
        int w = (pw - 44) / 2;
        Ui.text(ms, "Setarile rotii se salveaza pe server si se aplica tuturor.", x, y, Sk.MUTED);
        y += 16;
        chip(ms, cfgEnabled ? "ROATA PORNITA" : "ROATA OPRITA", x, y, w, 18, mx, my, cfgEnabled,
            cfgEnabled ? Sk.GOOD : Sk.BAD, () -> cfgEnabled = !cfgEnabled);
        y += 26;
        label(ms, "Cooldown intre rotiri (ore)", x, y);
        label(ms, "Cost rotire ($, 0 = gratuit)", x + w + 12, y);
        show(cCooldown);
        show(cCost);
        drawField(ms, cCooldown, x, y + 10, w, 16, mx, my);
        drawField(ms, cCost, x + w + 12, y + 10, w, 16, mx, my);
        y += 34;
        label(ms, "Rotiri gratuite pe zi", x, y);
        label(ms, "Maxim rotiri pe zi", x + w + 12, y);
        show(cFree);
        show(cMax);
        drawField(ms, cFree, x, y + 10, w, 16, mx, my);
        drawField(ms, cMax, x + w + 12, y + 10, w, 16, mx, my);
        y += 34;
        label(ms, "Durata animatiei (secunde)", x, y);
        show(cAnim);
        drawField(ms, cAnim, x, y + 10, w, 16, mx, my);
        y += 36;
        button(ms, "SALVEAZA SETARILE", x, y, w, 17, mx, my, true, Sk.GOOD, this::sendCfg);
        y += 26;
        Ui.text(ms, "Primele rotiri ale zilei sunt gratuite (cate ai pus mai sus);", x, y, Sk.FAINT);
        Ui.text(ms, "dupa ele se plateste costul, pana la maximul zilnic.", x, y + 10, Sk.FAINT);
        Ui.text(ms, "Premiile dezactivate raman in lista, dar nu mai pica.", x, y + 24, Sk.FAINT);
    }

    // ------------------------------------------------------------------ fila STATISTICI

    private void drawStats(GuiGraphics ms, int mx, int my) {
        int x = px + 16;
        int y = py + HEAD + 12;
        if (!WheelState.statsLoaded) {
            Ui.text(ms, "Se incarca...", x, y, Sk.MUTED);
            return;
        }
        Ui.text(ms, "Total rotiri: " + WheelState.statSpins + "   ·   jucatori: " + WheelState.statPlayers, x, y, Sk.WHITE);
        y += 16;
        int half = (pw - 44) / 2;
        Ui.text(ms, "CELE MAI CASTIGATE", x, y, Sk.ACC);
        Ui.text(ms, "ULTIMELE CASTIGURI", x + half + 12, y, Sk.ACC);
        y += 12;
        for (int i = 0; i < WheelState.statTop.size() && i < 8; i++) {
            WheelState.Stat s = WheelState.statTop.get(i);
            int yy = y + i * 13;
            Sk.slant(ms, x, yy, half, 12, 3f, Sk.ROW);
            Ui.text(ms, Ui.clip(s.name, half - 38), x + 6, yy + 2, Sk.WHITE);
            Ui.textRight(ms, String.valueOf(s.n), x + half - 6, yy + 2, Sk.WARN);
        }
        for (int i = 0; i < WheelState.statLast.size() && i < 8; i++) {
            WheelState.Stat s = WheelState.statLast.get(i);
            int yy = y + i * 13;
            int sx = x + half + 12;
            Sk.slant(ms, sx, yy, half, 12, 3f, Sk.ROW);
            Ui.text(ms, Ui.clip(s.who, 54), sx + 6, yy + 2, Sk.WHITE);
            Ui.textRight(ms, Ui.clip(s.name, half - 70), sx + half - 6, yy + 2, Sk.MUTED);
        }
        int by = py + ph - 26;
        button(ms, "REIMPROSPATEAZA", x, by, 110, 15, mx, my, true, 0xFF1B2839,
            () -> WheelClient.sendAction("admin_stats", new JsonObject()));
    }

    private void label(GuiGraphics ms, String t, int x, int y) {
        Ui.text(ms, t, x, y, Sk.MUTED);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        if (tab != 0) return true;
        int max = Math.max(0, WheelState.admin.size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        return true;
    }
}
