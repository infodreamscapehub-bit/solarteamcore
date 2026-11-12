package ro.solarstudios.flowcity.client;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.hype.AtmScreen;
import ro.solarstudios.flowcity.hype.HypeClient;
import ro.solarstudios.flowcity.phone.PhoneScreen;

/** Apeluri doar pe client, din blocuri/iteme. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void openAtm() {
        HypeClient.request("atm_req", new JsonObject());
        Minecraft.getInstance().displayGuiScreen(new AtmScreen());
    }

    public static void openMap() {
        Minecraft.getInstance().displayGuiScreen(new ro.solarstudios.flowcity.maps.MapScreen());
    }

    public static void openPhone() {
        Minecraft.getInstance().displayGuiScreen(new PhoneScreen());
    }
}
