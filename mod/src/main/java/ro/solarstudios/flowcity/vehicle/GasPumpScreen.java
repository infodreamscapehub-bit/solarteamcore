package ro.solarstudios.flowcity.vehicle;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;

/** Interfata pompei: alegi cati litri, vezi pretul, platesti cash sau cu cardul. Alimenteaza masina sau canistra din mana. */
public final class GasPumpScreen extends FlowScreen {
    private final BlockPos pos;
    private CarEntity car;
    private boolean canMode;
    private float liters = 10;
    private boolean card;
    private boolean waiting;
    private long fillStart;
    private float fillFrom;
    private float fillTo;

    private GasPumpScreen(BlockPos pos) {
        super("Pompa", 250, 190);
        this.pos = pos;
        VehicleClient.request("pump", new JsonObject());
    }

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new GasPumpScreen(pos));
    }

    private CarEntity findCar() {
        Minecraft mc = Minecraft.getInstance();
        CarEntity best = null;
        double bd = 8 * 8;
        for (CarEntity c : mc.level.getEntitiesOfClass(CarEntity.class, new AABB(pos).inflate(8))) {
            double d = c.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (d < bd) {
                bd = d;
                best = c;
            }
        }
        return best;
    }

    private static boolean holdingCan() {
        return Minecraft.getInstance().player.getMainHandItem().getItem() instanceof JerryCanItem;
    }

    private float current() {
        if (canMode) return JerryCanItem.fuel(Minecraft.getInstance().player.getMainHandItem());
        return car == null ? 0 : car.getFuel();
    }

    private float capacity() {
        return canMode ? JerryCanItem.CAPACITY : CarEntity.TANK;
    }

    void result(boolean ok, String msg, float added) {
        waiting = false;
        toast(msg, !ok);
        if (ok) {
            fillFrom = current();
            fillTo = Math.min(capacity(), fillFrom + added);
            fillStart = System.currentTimeMillis();
            VehicleSounds.pump();
        }
    }

    @Override
    protected void draw(GuiGraphics ms, int mx, int my) {
        header(ms, "Benzinarie", "", mx, my);
        if (car == null || car.isRemoved()) car = findCar();
        if (!holdingCan()) canMode = false;
        int x = px + 12;
        int y = py + HEAD + 10;
        int w = pw - 24;

        // ce alimentam
        if (holdingCan()) {
            chip(ms, "Masina", x, y, 52, 14, mx, my, !canMode, Theme.ACCENT, () -> canMode = false);
            chip(ms, "Canistra", x + 56, y, 56, 14, mx, my, canMode, Theme.ACCENT, () -> canMode = true);
            y += 20;
        }
        if (!canMode && car == null) {
            Ui.text(ms, "Nicio masina langa pompa.", x, y + 4, Theme.MUTED);
            Ui.text(ms, "Parcheaza la cel mult 8 blocuri.", x, y + 16, Theme.FAINT);
            return;
        }
        String what = canMode ? "Canistra" : car.displayName();
        float cur = current();
        long since = System.currentTimeMillis() - fillStart;
        if (fillStart > 0 && since < 1600) cur = fillFrom + (fillTo - fillFrom) * (since / 1600f);
        float cap = capacity();
        Ui.text(ms, what, x, y, Theme.TEXT);
        Ui.textRight(ms, String.format("%.1f / %.0f L", cur, cap), x + w, y, Theme.MUTED);
        y += 12;
        // rezervor + ce adaugi (zona deschisa)
        float room = Math.max(0, cap - cur);
        liters = Math.max(0, Math.min(liters, (float) Math.floor(room)));
        Ui.round(ms, x, y, w, 8, Theme.PANEL_3);
        int fw = Math.round((w - 2) * cur / cap);
        int aw = Math.round((w - 2) * liters / cap);
        Ui.rect(ms, x + 1, y + 1, fw, 6, Theme.ACCENT_DIM);
        Ui.rect(ms, x + 1 + fw, y + 1, Math.min(aw, w - 2 - fw), 6, Theme.ACCENT);
        y += 18;

        // cantitate
        Ui.text(ms, "Cantitate", x, y + 4, Theme.MUTED);
        int bx = x + 62;
        ghost(ms, "-5", bx, y, 24, 16, mx, my, () -> liters = Math.max(0, liters - 5));
        ghost(ms, "-1", bx + 26, y, 22, 16, mx, my, () -> liters = Math.max(0, liters - 1));
        Ui.textCenter(ms, (int) liters + " L", bx + 74, y + 4, Theme.TEXT);
        ghost(ms, "+1", bx + 100, y, 22, 16, mx, my, () -> liters += 1);
        ghost(ms, "+5", bx + 124, y, 24, 16, mx, my, () -> liters += 5);
        y += 20;
        final float fullRoom = room;
        ghost(ms, "Plin", bx, y, 148, 14, mx, my, () -> liters = (float) Math.floor(fullRoom));
        y += 22;

        // plata
        double total = liters * VehicleClient.price;
        Ui.rect(ms, x, y - 4, w, 1, Theme.LINE);
        Ui.text(ms, "Pret", x, y + 2, Theme.MUTED);
        Ui.textRight(ms, Ui.money(VehicleClient.price) + " lei / L", x + w, y + 2, Theme.TEXT);
        y += 13;
        Ui.text(ms, "Total", x, y + 2, Theme.MUTED);
        Ui.textRight(ms, Ui.money(total) + " lei", x + w, y + 2, Theme.ACCENT);
        y += 16;
        chip(ms, "Cash " + Ui.money(VehicleClient.cash), x, y, 90, 14, mx, my, !card, Theme.ACCENT, () -> card = false);
        chip(ms, "Card " + Ui.money(VehicleClient.bank), x + 94, y, 90, 14, mx, my, card, Theme.ACCENT, () -> card = true);
        double funds = card ? VehicleClient.bank : VehicleClient.cash;
        boolean can = !waiting && liters >= 1 && funds >= total;
        String label = waiting ? "Se proceseaza..." : (funds < total ? "Fonduri insuficiente" : "Alimenteaza");
        button(ms, label, px + pw - 12 - 100, py + ph - 26, 100, 18, mx, my, can, Theme.ACCENT, this::pay);
    }

    private void pay() {
        JsonObject o = new JsonObject();
        o.addProperty("liters", (int) liters);
        o.addProperty("method", card ? "card" : "cash");
        o.addProperty("can", canMode);
        if (!canMode) o.addProperty("car", car.getUUID().toString());
        waiting = true;
        VehicleClient.request("fuel_buy", o);
    }
}
