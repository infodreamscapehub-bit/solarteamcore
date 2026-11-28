package ro.solarstudios.flowcity.bank;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client "bank": starea contului/creditului/economiilor si rezultatul actiunilor. */
public final class BankClient extends JsonClientModule {
    private static BankClient instance;
    static JsonObject state = new JsonObject();

    private BankClient() {
        on("state", d -> Minecraft.getInstance().execute(() -> {
            state = d;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof FlowBankScreen) ((FlowBankScreen) mc.screen).update();
        }));
        on("open", d -> Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof FlowBankScreen)) {
                mc.setScreen(new FlowBankScreen());
            }
        }));
        on("result", d -> Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof FlowBankScreen) ((FlowBankScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
        }));
    }

    @Override
    public String id() {
        return "bank";
    }

    public static void register() {
        instance = new BankClient();
        ClientModules.register(instance);
    }

    /** Cere starea creditului/economiilor (la deschiderea ecranului din ATM). */
    static void refresh() {
        if (instance != null) instance.send("refresh", new JsonObject());
    }

    static void take(int i) {
        JsonObject o = new JsonObject();
        o.addProperty("i", i);
        if (instance != null) instance.send("take", o);
    }

    static void repay(double amount, boolean all, boolean cash) {
        JsonObject o = new JsonObject();
        o.addProperty("amount", amount);
        o.addProperty("all", all);
        o.addProperty("cash", cash);
        if (instance != null) instance.send("repay", o);
    }

    static void saveIn(double amount, boolean all) {
        JsonObject o = new JsonObject();
        o.addProperty("amount", amount);
        o.addProperty("all", all);
        if (instance != null) instance.send("save_in", o);
    }

    static void saveOut(double amount, boolean all) {
        JsonObject o = new JsonObject();
        o.addProperty("amount", amount);
        o.addProperty("all", all);
        if (instance != null) instance.send("save_out", o);
    }
}

