package ro.solarstudios.flowcity.wheel;

import com.google.gson.JsonObject;
import net.minecraft.client.gui.GuiGraphics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;

/** eAdmin > Roata: lista premiilor (ce poate pica) cu sanse, editare, adaugare, stergere si reordonare. */
public final class WheelAdminScreen extends FlowScreen {
    private static final int ROW = 17;
    private static final int LIST_W = 196;

    private final EditBox material = field("material (ex. DIAMOND)", 40, null);
    private final EditBox name = field("nume (optional)", 24, null);
    private final EditBox amount = field("cantitate / suma", 10, null);
    private final EditBox weight = field("sansa (1-1000)", 4, null);
    private final EditBox color = field("#RRGGBB", 7, null);
    private final List<EditBox> all = new ArrayList<>();
    private long selected = -1;
    private boolean announce;
    private int scroll;

    public WheelAdminScreen() {
        super("Roata - premii", 450, 270);
        all.add(material);
        all.add(name);
        all.add(amount);
        all.add(weight);
        all.add(color);
        weight.setValue("10");
    }

    @Override
    protected void init() {
        JsonObject o = new JsonObject();
        WheelClient.sendAction("admin_list", o);
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
        material.setValue(p.material);
        name.setValue(p.name);
        amount.setValue(p.amount == Math.rint(p.amount) ? String.valueOf((long) p.amount) : String.valueOf(p.amount));
        weight.setValue(String.valueOf(p.weight));
        color.setValue(p.color);
        announce = p.announce;
    }

    private void clearForm() {
        selected = -1;
        material.setValue("");
        name.setValue("");
        amount.setValue("");
        weight.setValue("10");
        color.setValue("");
        announce = false;
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
        Item it = ForgeRegistries.ITEMS.getValue(new ResourceLocation("minecraft", material.toLowerCase(Locale.ROOT)));
        return it == null ? ItemStack.EMPTY : new ItemStack(it);
    }

    private void sendAdd(String type) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        if ("item".equals(type) && !material.getValue().trim().isEmpty()) o.addProperty("material", material.getValue().trim());
        if (!amount.getValue().trim().isEmpty()) {
            try {
                o.addProperty("amount", Double.parseDouble(amount.getValue().trim().replace(',', '.')));
            } catch (NumberFormatException e) {
                toast("Cantitate invalida.", true);
                return;
            }
        }
        o.addProperty("name", name.getValue().trim());
        o.addProperty("weight", intOr(weight.getValue(), 10));
        o.addProperty("color", color.getValue().trim());
        o.addProperty("announce", announce);
        WheelClient.sendAction("admin_add", o);
    }

    private void sendSave() {
        if (selected < 0) return;
        JsonObject o = new JsonObject();
        o.addProperty("id", selected);
        o.addProperty("name", name.getValue().trim());
        o.addProperty("weight", intOr(weight.getValue(), 10));
        o.addProperty("color", color.getValue().trim());
        o.addProperty("announce", announce);
        if (!amount.getValue().trim().isEmpty()) {
            try {
                o.addProperty("amount", Double.parseDouble(amount.getValue().trim().replace(',', '.')));
            } catch (NumberFormatException e) {
                toast("Cantitate invalida.", true);
                return;
            }
        }
        WheelClient.sendAction("admin_update", o);
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
    protected void draw(GuiGraphics ms, int mx, int my) {
        header(ms, "Roata norocului", "premii", mx, my);
        List<WheelState.AdminPrize> list = WheelState.admin;

        // ---- lista
        int lx = px + 8;
        int ly = py + HEAD + 8;
        Ui.text(ms, list.size() + "/" + WheelState.adminMax + " premii", lx, ly, Theme.MUTED);
        ly += 12;
        int rows = visibleRows();
        for (int i = 0; i < rows && i + scroll < list.size(); i++) {
            WheelState.AdminPrize p = list.get(i + scroll);
            int y = ly + i * ROW;
            boolean sel = p.id == selected;
            boolean hover = Ui.in(mx, my, lx, y, LIST_W, ROW - 1);
            Ui.frame(ms, lx, y, LIST_W, ROW - 1, sel ? Theme.PANEL_3 : (hover ? Theme.PANEL_2 : Theme.PANEL), sel ? Theme.ACCENT : Theme.LINE);
            Ui.rect(ms, lx + 2, y + 2, 3, ROW - 5, parse(p.color, Theme.ACCENT));
            ItemStack st = "item".equals(p.type) ? icon(p.material) : ItemStack.EMPTY;
            if (!st.isEmpty()) ms.renderItem(st, lx + 8, y);
            else Ui.text(ms, "bank".equals(p.type) ? "B" : "$", lx + 12, y + 4, Theme.WARN);
            Ui.text(ms, Ui.clip(p.name, 100), lx + 27, y + 4, Theme.TEXT);
            Ui.textRight(ms, String.format(Locale.US, "%.1f%%", p.pct), lx + LIST_W - 4, y + 4, Theme.MUTED);
            final WheelState.AdminPrize fp = p;
            hit(lx, y, LIST_W, ROW - 1, () -> select(fp));
        }
        if (list.size() > rows) {
            Ui.text(ms, "roata mouse = deruleaza", lx, py + ph - 14, Theme.FAINT);
        }

        // ---- panoul din dreapta
        int rx = px + LIST_W + 20;
        int rw = pw - LIST_W - 28;
        int y = py + HEAD + 8;
        Ui.text(ms, selected >= 0 ? "Editezi premiul selectat" : "Premiu nou", rx, y, Theme.ACCENT);
        y += 14;
        label(ms, "Material (item)", rx, y);
        drawField(ms, material, rx, y + 10, rw, 16, mx, my);
        y += 32;
        label(ms, "Nume afisat", rx, y);
        drawField(ms, name, rx, y + 10, rw, 16, mx, my);
        y += 32;
        int half = (rw - 6) / 2;
        label(ms, "Cantitate / suma", rx, y);
        label(ms, "Sansa (greutate)", rx + half + 6, y);
        drawField(ms, amount, rx, y + 10, half, 16, mx, my);
        drawField(ms, weight, rx + half + 6, y + 10, half, 16, mx, my);
        y += 32;
        label(ms, "Culoare", rx, y);
        drawField(ms, color, rx, y + 10, half, 16, mx, my);
        int sw = parse(color.getValue(), 0);
        if (sw != 0) Ui.rect(ms, rx + half + 6, y + 10, 16, 16, sw);
        chip(ms, announce ? "Anunt global: DA" : "Anunt global: NU", rx + half + 26, y + 10, rw - half - 26, 16, mx, my,
            announce, Theme.WARN, () -> announce = !announce);
        y += 34;

        int bw = (rw - 6) / 3;
        button(ms, "+ ITEM", rx, y, bw, 15, mx, my, selected < 0, Theme.OK, () -> sendAdd("item"));
        button(ms, "+ CASH", rx + bw + 3, y, bw, 15, mx, my, selected < 0, Theme.ACCENT_DIM, () -> sendAdd("money"));
        button(ms, "+ BANCA", rx + 2 * (bw + 3), y, bw, 15, mx, my, selected < 0, Theme.VIOLET, () -> sendAdd("bank"));
        y += 19;
        button(ms, "SALVEAZA", rx, y, bw, 15, mx, my, selected >= 0, Theme.OK, this::sendSave);
        button(ms, "STERGE", rx + bw + 3, y, bw, 15, mx, my, selected >= 0, 0xFF6B2F2B, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", selected);
            WheelClient.sendAction("admin_delete", o);
        });
        button(ms, "NOU", rx + 2 * (bw + 3), y, bw, 15, mx, my, true, Theme.PANEL_3, this::clearForm);
        y += 19;
        button(ms, "SUS", rx, y, bw, 15, mx, my, selected >= 0, Theme.PANEL_3, () -> move(-1));
        button(ms, "JOS", rx + bw + 3, y, bw, 15, mx, my, selected >= 0, Theme.PANEL_3, () -> move(1));
        y += 22;
        Ui.text(ms, "Item: tine-l in mana si lasa materialul gol.", rx, y, Theme.FAINT);
        Ui.text(ms, "Sansa = greutate; % se calculeaza din total.", rx, y + 10, Theme.FAINT);
    }

    private void label(GuiGraphics ms, String t, int x, int y) {
        Ui.text(ms, t, x, y, Theme.MUTED);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        int max = Math.max(0, WheelState.admin.size() - visibleRows());
        scroll = Math.max(0, Math.min(max, scroll - (int) Math.signum(delta)));
        return true;
    }
}
