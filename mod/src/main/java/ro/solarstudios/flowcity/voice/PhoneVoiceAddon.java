package ro.solarstudios.flowcity.voice;

import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.AddonLoaderScope;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.event.EventSubscribe;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.capture.ServerActivation;
import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.source.ServerDirectSource;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEndEvent;
import su.plo.voice.api.server.event.audio.source.PlayerSpeakEvent;
import su.plo.voice.api.server.player.VoiceServerPlayer;
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket;
import su.plo.voice.proto.packets.udp.serverbound.PlayerAudioPacket;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Addon PlasmoVoice: cat timp doi jucatori sunt intr-un apel ({@link CallVoice}), vocea fiecaruia ajunge doar la celalalt,
 * indiferent de distanta, iar cei din jur nu o mai aud.
 */
@Addon(id = "flowcity-phone", name = "FlowCity Phone", scope = AddonLoaderScope.SERVER, version = "1.0.0", authors = {"SolarStudios"})
public final class PhoneVoiceAddon implements AddonInitializer {
    @InjectPlasmoVoice
    private PlasmoVoiceServer voiceServer;

    private ServerSourceLine line;
    /** receptor -> sursa directa folosita pentru a-i trimite vocea partenerului. */
    private final Map<UUID, ServerDirectSource> sources = new ConcurrentHashMap<>();

    @Override
    public void onAddonInitialize() {
        line = voiceServer.getSourceLineManager()
            .createBuilder(this, "flowcity_phone", "Telefon", "plasmovoice:textures/icons/speaker.png", 5)
            .withPlayers(false)
            .build();
        CallVoice.onUnlink = this::dropSource;
    }

    @Override
    public void onAddonShutdown() {
        CallVoice.onUnlink = null;
        for (UUID u : sources.keySet()) dropSource(u);
    }

    private void dropSource(UUID receiver) {
        ServerDirectSource s = sources.remove(receiver);
        if (s != null && line != null) {
            try {
                line.removeSource(s);
            } catch (RuntimeException ignored) {
                // sursa deja eliminata
            }
        }
    }

    private ServerDirectSource source(UUID receiver, VoiceServerPlayer player) {
        return sources.computeIfAbsent(receiver, k -> line.createDirectSource(player, false));
    }

    @EventSubscribe
    public void onSpeak(PlayerSpeakEvent event) {
        UUID speaker = event.getPlayer().getInstance().getUuid();
        UUID to = CallVoice.partner(speaker);
        if (to == null) return;
        Optional<VoiceServerPlayer> target = voiceServer.getPlayerManager().getPlayerById(to);
        if (!target.isPresent() || !target.get().hasVoiceChat()) return;
        // taiem difuzarea normala (proximitate) - vocea merge doar spre celalalt
        event.setResult(ServerActivation.Result.HANDLED);
        event.setCancelled(true);
        PlayerAudioPacket in = event.getPacket();
        ServerDirectSource src = source(to, target.get());
        SourceAudioPacket out = new SourceAudioPacket(in.getSequenceNumber(), (byte) src.getState(), in.getData(), src.getId(), (short) 0);
        src.sendAudioPacket(out);
    }

    @EventSubscribe
    public void onSpeakEnd(PlayerSpeakEndEvent event) {
        UUID speaker = event.getPlayer().getInstance().getUuid();
        UUID to = CallVoice.partner(speaker);
        if (to == null) return;
        event.setResult(ServerActivation.Result.HANDLED);
        event.setCancelled(true);
        ServerDirectSource src = sources.get(to);
        if (src != null) src.sendAudioEnd(System.nanoTime());
    }
}
