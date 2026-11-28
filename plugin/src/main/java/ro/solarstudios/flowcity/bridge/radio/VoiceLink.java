package ro.solarstudios.flowcity.bridge.radio;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * Puntea dintre modulul de statii (thread-ul principal) si Plasmo Voice (thread-urile lui de retea). Modulul publica periodic un
 * instantaneu imuabil: cine are voie sa vorbeasca pe ce statie si cine o asculta. Plasmo Voice citeste doar instantaneul, fara
 * sa atinga Bukkit sau inventarele (care nu sunt thread-safe). Nu depinde de Plasmo Voice: exista si fara el.
 */
public final class VoiceLink {
    /** Instantaneu: vorbitor -> statie, statie -> ascultatori (fara vorbitor), plus o versiune care creste cand se schimba ascultatorii. */
    public static final class Snapshot {
        public final Map<UUID, Long> speakerStation;
        public final Map<Long, List<UUID>> listeners;
        public final long version;

        public Snapshot(Map<UUID, Long> speakerStation, Map<Long, List<UUID>> listeners, long version) {
            this.speakerStation = Collections.unmodifiableMap(new HashMap<UUID, Long>(speakerStation));
            this.listeners = Collections.unmodifiableMap(new HashMap<Long, List<UUID>>(listeners));
            this.version = version;
        }
    }

    public static final Snapshot EMPTY = new Snapshot(new HashMap<UUID, Long>(), new HashMap<Long, List<UUID>>(), 0);

    private volatile Snapshot snapshot = EMPTY;
    private volatile BiConsumer<UUID, Boolean> talkSink;
    private volatile String status = "Voce: Plasmo Voice nu e pornit (se foloseste radio text).";
    private volatile boolean voiceActive;

    public Snapshot snapshot() {
        return snapshot;
    }

    public void publish(Snapshot s) {
        this.snapshot = s;
    }

    /** Modulul isi inregistreaza receptorul pentru "cine vorbeste" (apelat din thread-ul de voce; modulul trece pe thread-ul principal). */
    public void onTalk(BiConsumer<UUID, Boolean> sink) {
        this.talkSink = sink;
    }

    /** Apelat din Plasmo Voice cand un jucator incepe/inceteaza sa transmita. */
    public void talk(UUID speaker, boolean on) {
        BiConsumer<UUID, Boolean> s = talkSink;
        if (s != null) s.accept(speaker, on);
    }

    public boolean voiceActive() {
        return voiceActive;
    }

    public String status() {
        return status;
    }

    public void setStatus(boolean active, String text) {
        this.voiceActive = active;
        this.status = text;
    }
}
