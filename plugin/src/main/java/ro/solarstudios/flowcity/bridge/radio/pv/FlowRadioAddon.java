package ro.solarstudios.flowcity.bridge.radio.pv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;
import su.plo.slib.api.permission.PermissionDefault;
import su.plo.voice.api.addon.AddonInitializer;
import su.plo.voice.api.addon.InjectPlasmoVoice;
import su.plo.voice.api.addon.annotation.Addon;
import su.plo.voice.api.server.PlasmoVoiceServer;
import su.plo.voice.api.server.audio.capture.PlayerActivationInfo;
import su.plo.voice.api.server.audio.capture.ServerActivation;
import su.plo.voice.api.server.audio.line.ServerSourceLine;
import su.plo.voice.api.server.audio.source.ServerBroadcastSource;
import su.plo.voice.api.server.player.VoicePlayer;
import ro.solarstudios.flowcity.bridge.radio.VoiceLink;

/**
 * Addonul Plasmo Voice pentru statiile radio FlowCity. Inregistreaza o linie sonora "flowcity_radio" si o activare push-to-talk
 * "FlowCity Radio" (jucatorul isi alege tasta in setarile Plasmo Voice). Cand cineva transmite, cadrele audio primite de server
 * sunt retransmise printr-o sursa de tip broadcast doar catre cei acordati pe aceeasi statie (instantaneul din {@link VoiceLink});
 * ceilalti jucatori, inclusiv cei de pe alte statii private, nu aud nimic. Proximity (vocea normala) ramane neatinsa.
 *
 * <p>Clasa e incarcata doar daca Plasmo Voice exista (vezi {@code PlasmoVoiceBoot}); orice referinta la su.plo.* sta aici.
 */
@Addon(id = "pv-addon-flowcity-radio", name = "FlowCity Radio", version = "1.0.0", authors = {"FlowCity"})
public final class FlowRadioAddon implements AddonInitializer {
    private static final String LINE = "flowcity_radio";
    private static final String PERM = "flowcity.radio.voice";

    @InjectPlasmoVoice
    private PlasmoVoiceServer voice;

    private final VoiceLink link;
    private final Logger log;
    private ServerSourceLine line;
    private ServerActivation activation;
    private final Map<Long, ServerBroadcastSource> sources = new HashMap<Long, ServerBroadcastSource>();
    private final Map<Long, Long> sourceVersion = new HashMap<Long, Long>();

    public FlowRadioAddon(VoiceLink link, Logger log) {
        this.link = link;
        this.log = log;
    }

    @Override
    public void onAddonInitialize() {
        line = voice.getSourceLineManager()
            .createBuilder(this, LINE, "FlowCity Radio", "plasmovoice:textures/icons/speaker_priority.png", 20)
            .withPlayers(false)
            .build();
        activation = voice.getActivationManager()
            .createBuilder(this, LINE, "FlowCity Radio", "plasmovoice:textures/icons/microphone_priority.png", PERM, 20)
            .setPermissionDefault(PermissionDefault.TRUE)
            .setProximity(false)
            .setTransitive(false)
            .build();

        activation.onPlayerActivationStart(player -> link.talk(player.getInstance().getUuid(), true));
        activation.onPlayerActivationEnd((player, packet) -> {
            UUID id = player.getInstance().getUuid();
            link.talk(id, false);
            ServerBroadcastSource src = sources.get(stationOf(id));
            if (src != null) src.sendAudioEnd(packet.getSequenceNumber());
            return ServerActivation.Result.HANDLED;
        });
        activation.onPlayerActivation((player, packet) -> {
            UUID id = player.getInstance().getUuid();
            Long station = stationOf(id);
            if (station == null) return ServerActivation.Result.IGNORED; // nu e acordat pe nicio statie sau radioul e oprit
            ServerBroadcastSource src = source(station);
            src.sendAudioFrame(packet.getData(), packet.getSequenceNumber(), new PlayerActivationInfo(player, packet));
            return ServerActivation.Result.HANDLED;
        });
        link.setStatus(true, "Voce: Plasmo Voice activ (leaga tasta \"FlowCity Radio\" in setarile Plasmo Voice).");
        log.info("Plasmo Voice: addon FlowCity Radio inregistrat (linie + activare push-to-talk).");
    }

    @Override
    public void onAddonShutdown() {
        for (ServerBroadcastSource s : sources.values()) {
            try {
                s.remove();
            } catch (RuntimeException ignored) {
                // sursa deja scoasa
            }
        }
        sources.clear();
        link.setStatus(false, "Voce: Plasmo Voice oprit.");
    }

    private Long stationOf(UUID speaker) {
        return link.snapshot().speakerStation.get(speaker);
    }

    /** Sursa de broadcast a statiei; lista ascultatorilor se reimprospateaza doar cand instantaneul s-a schimbat. */
    private ServerBroadcastSource source(long station) {
        ServerBroadcastSource src = sources.get(station);
        if (src == null) {
            src = line.createBroadcastSource(false);
            sources.put(station, src);
        }
        VoiceLink.Snapshot snap = link.snapshot();
        Long seen = sourceVersion.get(station);
        if (seen == null || seen != snap.version) {
            List<UUID> ids = snap.listeners.get(station);
            List<VoicePlayer> players = new ArrayList<VoicePlayer>();
            if (ids != null) {
                for (UUID u : ids) {
                    Optional<? extends VoicePlayer> p = voice.getPlayerManager().getPlayerById(u);
                    if (p.isPresent()) players.add(p.get());
                }
            }
            src.setPlayers(players);
            sourceVersion.put(station, snap.version);
        }
        return src;
    }

    /** Sursele statiilor care nu mai exista se scot (apelat periodic de modul prin instantaneu). */
    public void prune(java.util.Set<Long> alive) {
        for (Long id : new ArrayList<Long>(sources.keySet())) {
            if (!alive.contains(id)) {
                ServerBroadcastSource s = sources.remove(id);
                sourceVersion.remove(id);
                try {
                    s.remove();
                } catch (RuntimeException ignored) {
                    // deja scoasa
                }
            }
        }
    }

    public static List<UUID> none() {
        return Collections.emptyList();
    }
}
