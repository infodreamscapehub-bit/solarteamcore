package ro.solarstudios.flowcity.client;

/** Apeluri doar pe client, din blocuri/iteme. */
public final class ClientHooks {
    private ClientHooks() {}

    public static void openMdt() {
        ro.solarstudios.flowcity.mdt.MdtClient.requestOpen();
    }
}
