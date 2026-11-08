package ro.solarstudios.flowcity.hype;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.common.MinecraftForge;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client Hype-lite: primeste starea de la server, deschide ecranele ATM / Joburi, deseneaza HUD-ul. */
public final class HypeClient extends JsonClientModule {
    private static HypeClient instance;

    private HypeClient() {
        on("state", d -> HypeState.state = d);
        on("atm", this::onAtm);
        on("jobs", d -> {
            HypeState.jobs = d;
            HypeState.jobsAt = System.currentTimeMillis();
        });
        on("msg", d -> notice(str(d, "text", ""), bool(d, "ok", true)));
        on("earn", d -> {
            HypeState.earnText = "+$" + ro.solarstudios.flowcity.client.ui.Ui.money(num(d, "pay", 0)) + "  +" + integer(d, "xp", 0) + " XP";
            HypeState.earnUntil = System.currentTimeMillis() + 2500;
        });
        on("open", d -> {
            if ("jobs".equals(str(d, "screen", ""))) Minecraft.getInstance().displayGuiScreen(new JobsScreen());
        });
        on("hud_toggle", d -> {
            HypeState.hudOn = !HypeState.hudOn;
            notice(HypeState.hudOn ? "HUD pornit." : "HUD oprit.", true);
        });
    }

    @Override
    public String id() {
        return "hype";
    }

    @Override
    public void onLogout() {
        HypeState.reset();
    }

    /** Apelat din ClientInit.register(). */
    public static void register() {
        instance = new HypeClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new HypeHud());
    }

    public static void request(String action, JsonObject data) {
        if (instance != null) instance.send(action, data);
    }

    private void onAtm(JsonObject d) {
        HypeState.atm = d;
        Minecraft mc = Minecraft.getInstance();
        if (bool(d, "open", false) && !(mc.currentScreen instanceof AtmScreen)) mc.displayGuiScreen(new AtmScreen());
        String message = str(d, "message", "");
        if (!message.isEmpty() && mc.currentScreen instanceof AtmScreen) {
            ((FlowScreen) mc.currentScreen).toast(message, !bool(d, "ok", true));
        }
    }

    /** Mesaj scurt: toast in ecran daca e unul Flow deschis, altfel in actionbar. */
    private static void notice(String text, boolean ok) {
        if (text.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.currentScreen instanceof FlowScreen) ((FlowScreen) mc.currentScreen).toast(text, !ok);
        else if (mc.player != null) mc.player.sendStatusMessage(new StringTextComponent((ok ? "§a" : "§c") + text), true);
    }
}
