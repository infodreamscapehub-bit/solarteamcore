package ro.solarstudios.flowcity.bridge.radio;

/** Un backend de voce (Plasmo Voice). Se incarca doar prin reflexie, ca pluginul sa mearga si fara el. */
public interface VoiceBackend {
    /** Incearca sa se inregistreze; arunca exceptie cu un mesaj clar daca nu poate. */
    void load(VoiceLink link, java.util.logging.Logger log) throws Exception;

    void shutdown();
}
