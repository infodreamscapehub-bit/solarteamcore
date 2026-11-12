package ro.solarstudios.flowcity.hype;

import net.minecraft.util.text.StringTextComponent;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.common.MinecraftForge;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client Hype-lite: primeste starea de la server, deschide ecranele ATM / Joburi, deseneaza HUD-ul. */
public final class HypeClient extends JsonClientModule {
    private static HypeClient instance;

    private HypeClient() {
        on("state", d -> {
            int before = integer(HypeState.state, "level", 0);
            HypeState.state = d;
            if (before > 0 && integer(d, "level", 0) > before) HypeState.levelUpAt = System.currentTimeMillis();
        });
        on("atm", this::onAtm);
        on("jobs", d -> {
            HypeState.jobs = d;
            HypeState.jobsAt = System.currentTimeMillis();
        });
        on("msg", d -> notice(str(d, "text", ""), bool(d, "ok", true)));
        on("earn", d -> HypeState.popup("+$" + ro.solarstudios.flowcity.client.ui.Ui.money(num(d, "pay", 0)) + "  +" + integer(d, "xp", 0) + " XP"));
        on("open", d -> {
            if ("jobs".equals(str(d, "screen", ""))) Minecraft.getInstance().setScreen(new JobsScreen());
        });
        on("hud_toggle", d -> {
            if ("xmas".equals(str(d, "mode", ""))) {
                ro.solarstudios.flowcity.client.ui.Xmas.enabled = !ro.solarstudios.flowcity.client.ui.Xmas.enabled;
                notice(ro.solarstudios.flowcity.client.ui.Xmas.enabled ? "Decor de Craciun pornit." : "Decor de Craciun oprit.", true);
                return;
            }
            if ("bg".equals(str(d, "mode", ""))) {
                int[] steps = {0, 40, 90, 150};
                int next = 0;
                for (int i = 0; i < steps.length; i++) if (HypeState.hudBg == steps[i]) next = (i + 1) % steps.length;
                HypeState.hudBg = steps[next];
                notice(next == 0 ? "HUD transparent." : "Fundal HUD: " + (next * 100 / 3) + "%.", true);
                return;
            }
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
        if (bool(d, "open", false) && !(mc.screen instanceof AtmScreen)) mc.setScreen(new AtmScreen());
        String message = str(d, "message", "");
        if (!message.isEmpty() && mc.screen instanceof AtmScreen) {
            ((FlowScreen) mc.screen).toast(message, !bool(d, "ok", true));
        }
    }

    /** Mesaj scurt: toast in ecran daca e unul Flow deschis, altfel in actionbar. */
    private static void notice(String text, boolean ok) {
        if (text.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof FlowScreen) ((FlowScreen) mc.screen).toast(text, !ok);
        else if (mc.player != null) mc.player.displayClientMessage(new StringTextComponent((ok ? "§a" : "§c") + text), true);
    }
}
