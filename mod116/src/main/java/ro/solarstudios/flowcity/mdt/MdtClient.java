package ro.solarstudios.flowcity.mdt;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ui.FlowSounds;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;
import ro.solarstudios.flowcity.notify.NotifyToasts;

/** Modulul client "mdt": terminalul de politie. Datele vin ca JSON de la server si sunt tinute in {@link MdtState}. */
public final class MdtClient extends JsonClientModule {
    private static MdtClient instance;
    private static KeyBinding bk1;
    private static KeyBinding bk2;
    private static KeyBinding bk3;
    private static KeyBinding panic;

    private MdtClient() {
        on("state", d -> {
            MdtState.state = d;
            MdtState.officer = true;
            Minecraft mc = Minecraft.getInstance();
            if (bool(d, "open", false) && !(mc.screen instanceof MdtScreen)) mc.setScreen(new MdtScreen());
        });
        on("citizen", d -> MdtState.citizen = d);
        on("found", d -> {
            MdtState.found = d.has("items") && d.get("items").isJsonArray() ? d.getAsJsonArray("items") : new JsonArray();
            MdtState.foundQuery = str(d, "q", "");
        });
        on("plate", d -> MdtState.plate = d);
        on("alert", d -> {
            int code = integer(d, "code", 1);
            String what = code == 99 ? "PANICA COD 99" : code == 1 ? "Backup urgent" : code == 2 ? "Backup transport" : "Backup urmarire";
            NotifyToasts.add("BAD", what, str(d, "name", "?") + " cere ajutor.");
            FlowSounds.play("notify", 0.9F);
            MdtState.lastAlertName = str(d, "name", "");
            MdtState.lastAlertX = num(d, "x", 0);
            MdtState.lastAlertZ = num(d, "z", 0);
        });
    }

    @Override
    public String id() {
        return "mdt";
    }

    @Override
    public void onLogout() {
        MdtState.reset();
    }

    public static void register() {
        instance = new MdtClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        bk1 = new KeyBinding("key.flowcity.mdt_bk1", GLFW.GLFW_KEY_KP_1, "key.categories.flowcity");
        bk2 = new KeyBinding("key.flowcity.mdt_bk2", GLFW.GLFW_KEY_KP_2, "key.categories.flowcity");
        bk3 = new KeyBinding("key.flowcity.mdt_bk3", GLFW.GLFW_KEY_KP_3, "key.categories.flowcity");
        panic = new KeyBinding("key.flowcity.mdt_panic", GLFW.GLFW_KEY_KP_0, "key.categories.flowcity");
        event.register(bk1);
        event.register(bk2);
        event.register(bk3);
        event.register(panic);
    }

    // ------------------------------------------------------------------ apeluri catre server

    public static void requestOpen() {
        if (instance != null) instance.send("open", new JsonObject());
    }

    /** Trimite o actiune cu perechi cheie/valoare (String sau numar). */
    public static void act(String action, Object... kv) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Object v = kv[i + 1];
            if (v instanceof Number) o.addProperty((String) kv[i], (Number) v);
            else if (v instanceof Boolean) o.addProperty((String) kv[i], (Boolean) v);
            else o.addProperty((String) kv[i], String.valueOf(v));
        }
        instance.send(action, o);
    }

    // ------------------------------------------------------------------ taste rapide (backup / panica)

    public static final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END || !MdtState.officer) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.screen != null) return;
            if (bk1 != null && bk1.consumeClick()) act("backup", "code", 1);
            if (bk2 != null && bk2.consumeClick()) act("backup", "code", 2);
            if (bk3 != null && bk3.consumeClick()) act("backup", "code", 3);
            if (panic != null && panic.consumeClick()) act("panic");
        }
    }
}
