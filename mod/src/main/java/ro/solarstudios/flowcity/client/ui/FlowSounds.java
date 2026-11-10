package ro.solarstudios.flowcity.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.SimpleSound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;

/** Sunetele FlowCity (tonuri de notificare in stil telefon): notify, message, money, alert. Redate local, fara pozitie. */
public final class FlowSounds {
    private FlowSounds() {}

    public static void play(String name, float volume) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundHandler() == null) return;
        mc.getSoundHandler().play(new SimpleSound(new ResourceLocation("flowcitycore", name), SoundCategory.MASTER,
            volume, 1.0F, false, 0, ISound.AttenuationType.NONE, 0.0D, 0.0D, 0.0D, true));
    }
}
