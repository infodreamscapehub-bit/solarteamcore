package ro.solarstudios.flowcity.fjobs;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ClientBridge;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.fjobs.elec.ElecClient;
import ro.solarstudios.flowcity.fjobs.elec.ElecPanelScreen;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;
import ro.solarstudios.flowcity.hype.HypeState;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Modulul client FlowCity Jobs: ecranul Job Center, tasta configurabila si mesajele de la server. Serverul e autoritar pentru tot. */
public final class FJobsClient extends JsonClientModule {
    private static FJobsClient instance;
    private static KeyBinding key;
    /** Ultimul pachet "data" primit (profil, joburi, istoric). */
    static JsonObject data;
    /** Ultimul panou de job primit (obiective, statistici, butoane). */
    static JsonObject panel;
    /** Ultimul clasament primit ("top"). */
    static JsonObject top;
    /** Ultimul magazin profesional primit ("shop"). */
    static JsonObject shop;
    private static boolean wantPanel;
    private static long panelAt;
    private static KeyBinding panelKey;

    private FJobsClient() {
        on("open", d -> {
            Minecraft mc = Minecraft.getInstance();
            if (!(mc.screen instanceof FJobsScreen)) mc.setScreen(new FJobsScreen());
        });
        on("data", d -> data = d);
        on("hud", FJobsHud::apply);
        on("panel", d -> {
            panel = d;
            panelAt = System.currentTimeMillis();
            Minecraft mc = Minecraft.getInstance();
            boolean elec = "electrician".equals(str(d, "kind", ""));
            if (wantPanel && elec && !(mc.screen instanceof ElecPanelScreen)) {
                wantPanel = false;
                mc.setScreen(new ElecPanelScreen());
            } else if (wantPanel && !elec && !(mc.screen instanceof FJobsPanelScreen)) {
                wantPanel = false;
                mc.setScreen(new FJobsPanelScreen());
            }
        });
        // jobul Electrician: panou electric, mini-jocuri, rezultat (serverul decide, clientul doar afiseaza)
        on("el_board", ElecClient::onBoard);
        on("el_game", ElecClient::onGame);
        on("el_gres", ElecClient::onGameResult);
        on("el_result", ElecClient::onResult);
        on("el_close", d -> ElecClient.onClose());
        on("mg_open", d -> {
            Minecraft mc = Minecraft.getInstance();
            FJobsMiniGameScreen s = new FJobsMiniGameScreen(d);
            FJobsMiniGameScreen.current = s;
            mc.setScreen(s);
        });
        on("mg_result", d -> {
            FJobsMiniGameScreen cur = FJobsMiniGameScreen.current;
            if (cur != null) cur.result(d);
        });
        on("top", d -> top = d);
        on("shop", d -> shop = d);
        on("prof", d -> {
            if (data != null) data.add("me", d);
        });
        on("msg", d -> {
            String text = str(d, "text", "");
            if (text.isEmpty()) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof FlowScreen) ((FlowScreen) mc.screen).toast(text, !bool(d, "ok", true));
        });
        on("earn", d -> HypeState.popup("+$" + ro.solarstudios.flowcity.client.ui.Ui.money(num(d, "pay", 0)) + "  +" + integer(d, "xp", 0) + " XP"));
    }

    @Override
    public String id() {
        return "fjobs";
    }

    @Override
    public void onLogout() {
        data = null;
        panel = null;
        wantPanel = false;
        ElecClient.reset();
        FJobsHud.clear();
    }

    public static void register() {
        instance = new FJobsClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Events());
        MinecraftForge.EVENT_BUS.register(new FJobsHud.Events());
    }

    public static void registerKeys(RegisterKeyMappingsEvent e) {
        key = new KeyBinding("key.flowcity.fjobs", GLFW.GLFW_KEY_J, "key.categories.flowcity");
        e.register(key);
        panelKey = new KeyBinding("key.flowcity.fjobs_panel", GLFW.GLFW_KEY_UNKNOWN, "key.categories.flowcity");
        e.register(panelKey);
    }

    /** Cere panoul jobului curent (serverul raspunde cu "panel"; ecranul se deschide la raspuns). */
    public static void openPanel() {
        wantPanel = true;
        request("panel", new JsonObject());
    }

    /** Cere datele si deschide Job Center (serverul raspunde cu "open" + "data"; daca modul lipseste, nu se intampla nimic). */
    public static void open() {
        request("req", new JsonObject());
        Minecraft mc = Minecraft.getInstance();
        if (!(mc.screen instanceof FJobsScreen)) mc.setScreen(new FJobsScreen());
    }

    /** Panoul jobului primit ultima oara (sau null). */
    public static JsonObject panelData() {
        return panel;
    }

    /** Momentul (ms) la care a venit ultimul panou. */
    public static long panelAt() {
        return panelAt;
    }

    /** Trimite o cerere catre modulul FJobs de pe server. */
    public static void req(String action, JsonObject o) {
        request(action, o);
    }

    /** Operatie a activitatii jobului curent (validata pe server, fara retrimiterea panoului). */
    public static void requestOp(JsonObject o) {
        request("job_op", o);
    }

    static void request(String action, JsonObject o) {
        if (instance != null && ClientBridge.connected) instance.send(action, o == null ? new JsonObject() : o);
    }

    static void closed() {
        request("close", new JsonObject());
    }

    public static final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END || key == null) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            while (key.consumeClick()) {
                if (mc.screen == null) open();
            }
            while (panelKey != null && panelKey.consumeClick()) {
                if (mc.screen == null) openPanel();
            }
        }
    }
}
