package ro.solarstudios.flowcity.bridge.items;

/** Scoate codurile de culoare din text (&a, §a). */
final class ChatStrip {
    private ChatStrip() {}

    static String strip(String s) {
        return s == null ? "" : s.replaceAll("[&§][0-9a-fk-or]", "");
    }
}
