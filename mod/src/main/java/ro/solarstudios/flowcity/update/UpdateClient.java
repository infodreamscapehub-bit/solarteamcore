package ro.solarstudios.flowcity.update;

import net.minecraft.client.Minecraft;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;
import ro.solarstudios.flowcity.notify.NotifyToasts;

/** Modul client "update": primeste informatii de la server, anunta o singura data si deschide ecranul la /flowcity update. */
public final class UpdateClient extends JsonClientModule {
    private UpdateClient() {
        on("info", d -> {
            UpdateChecker.fromServer(integer(d, "build", 0), str(d, "notes", ""), lng(d, "modSize", 0));
            if (UpdateChecker.updateAvailable() && UpdateChecker.toastOnce()) {
                NotifyToasts.add("INFO", "Update FlowCity", "Build #" + UpdateChecker.latestBuild + " disponibil. Scrie /flowcity update.");
            }
        });
        on("open", d -> {
            UpdateChecker.checkAsync(false);
            Minecraft.getInstance().displayGuiScreen(new UpdateScreen(null));
        });
    }

    @Override
    public String id() {
        return "update";
    }

    public static void register() {
        ClientModules.register(new UpdateClient());
    }
}
