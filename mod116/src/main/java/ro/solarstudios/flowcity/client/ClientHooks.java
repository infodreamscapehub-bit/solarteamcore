package ro.solarstudios.flowcity.client;

/** Apeluri doar pe client, din blocuri/iteme. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void openAtm() {
        ro.solarstudios.flowcity.hype.HypeClient.request("atm_req", new com.google.gson.JsonObject());
        net.minecraft.client.Minecraft.getInstance().setScreen(new ro.solarstudios.flowcity.hype.AtmScreen());
    }

    public static void openPhone() {
        net.minecraft.client.Minecraft.getInstance().setScreen(new ro.solarstudios.flowcity.phone.PhoneScreen());
    }

    public static void openMdt() {
        ro.solarstudios.flowcity.mdt.MdtClient.requestOpen();
    }


    public static void noCard() {
        ro.solarstudios.flowcity.notify.NotifyToasts.add("BAD", "ATM", "Introdu cardul bancar! \u021aine cardul \u00een m\u00e2n\u0103 \u0219i d\u0103 click pe ATM.");
    }
}
