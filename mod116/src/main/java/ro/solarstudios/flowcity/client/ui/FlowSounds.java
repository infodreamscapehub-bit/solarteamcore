package ro.solarstudios.flowcity.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;


/** Sunetele FlowCity (tonuri de notificare in stil telefon): notify, message, money, alert. Redate local, fara pozitie. */
public final class FlowSounds {
    private FlowSounds() {}

    /** Multiplicator global de volum (setat din telefon > Setari). */
    public static float volume = 1.0F;

    public static void play(String name, float vol) {
        if (FlowSounds.volume <= 0.01F) return;
        vol *= FlowSounds.volume;
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() == null) return;
        mc.getSoundManager().play(new SimpleSoundInstance(new ResourceLocation("flowcitycore", name), SoundSource.MASTER,
            vol, 1.0F, false, 0, SoundInstance.Attenuation.NONE, 0.0D, 0.0D, 0.0D, true));
    }
}
