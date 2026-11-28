package ro.solarstudios.flowcity.bridge.radio.pv;

import java.util.logging.Logger;
import ro.solarstudios.flowcity.bridge.radio.VoiceBackend;
import ro.solarstudios.flowcity.bridge.radio.VoiceLink;
import su.plo.voice.api.server.PlasmoVoiceServer;

/** Inregistreaza addonul in Plasmo Voice. Incarcata prin reflexie, doar dupa ce clasa PlasmoVoiceServer exista. */
public final class PlasmoVoiceBoot implements VoiceBackend {
    private FlowRadioAddon addon;

    @Override
    public void load(VoiceLink link, Logger log) {
        addon = new FlowRadioAddon(link, log);
        PlasmoVoiceServer.getAddonsLoader().load(addon);
    }

    @Override
    public void shutdown() {
        if (addon != null) {
            try {
                PlasmoVoiceServer.getAddonsLoader().unload(addon);
            } catch (Throwable ignored) {
                // oprire: nu mai conteaza
            }
        }
    }
}
