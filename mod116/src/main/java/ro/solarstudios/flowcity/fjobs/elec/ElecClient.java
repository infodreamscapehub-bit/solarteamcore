package ro.solarstudios.flowcity.fjobs.elec;

import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;

/** Starea clientului pentru jobul Electrician si trecerea intre ecrane (panou electric -> mini-joc -> rezultat). Serverul decide totul; aici doar afisam. */
public final class ElecClient {
    /** Ultimul panou electric primit (etape, stare, echipament). */
    static JsonObject board;
    /** Rezultatul final al ultimei interventii, pana e vazut. */
    static JsonObject result;
    static long boardAt;

    private ElecClient() {}

    public static boolean isElecScreen(Screen s) {
        return s instanceof ElecScreen;
    }

    public static void onBoard(JsonObject d) {
        board = d;
        boardAt = System.currentTimeMillis();
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        if (s instanceof ElecGameScreen) return;           // jocul se inchide singur si revine la panou
        if (s instanceof ElecBoardScreen) return;
        if (s == null || s instanceof ElecScreen || s instanceof ro.solarstudios.flowcity.fjobs.FJobsPanelScreen) mc.setScreen(new ElecBoardScreen());
    }

    public static void onGame(JsonObject d) {
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        if (!(s == null || s instanceof ElecScreen)) return;
        String g = ElecScreen.str(d, "game");
        ElecGameScreen next;
        switch (g) {
            case "circuit":
                next = new ElecCircuitScreen(d);
                break;
            case "fuse":
                next = new ElecFuseScreen(d);
                break;
            case "seq":
                next = new ElecSeqScreen(d);
                break;
            case "calib":
                next = new ElecCalibScreen(d);
                break;
            default:
                return;
        }
        mc.setScreen(next);
    }

    public static void onGameResult(JsonObject d) {
        Screen s = Minecraft.getInstance().screen;
        if (s instanceof ElecGameScreen) ((ElecGameScreen) s).result(d);
    }

    public static void onResult(JsonObject d) {
        result = d;
        board = null;
        Minecraft mc = Minecraft.getInstance();
        Screen s = mc.screen;
        if (s instanceof ElecGameScreen && ((ElecGameScreen) s).closing()) return;   // se inchide si deschide rezultatul
        if (s == null || s instanceof ElecScreen) mc.setScreen(new ElecResultScreen());
    }

    public static void onClose() {
        board = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ElecScreen) mc.setScreen(null);
    }

    /** Dupa un mini-joc: rezultatul final, altfel panoul electric, altfel nimic. */
    static void route() {
        Minecraft mc = Minecraft.getInstance();
        if (result != null) mc.setScreen(new ElecResultScreen());
        else if (board != null) mc.setScreen(new ElecBoardScreen());
        else mc.setScreen(null);
    }

    /** Inchide ecranele electricianului (X / ESC). Interventia ramane activa pe server. */
    public static void closeAll() {
        result = null;
        Minecraft.getInstance().setScreen(null);
    }

    public static void reset() {
        board = null;
        result = null;
    }
}
