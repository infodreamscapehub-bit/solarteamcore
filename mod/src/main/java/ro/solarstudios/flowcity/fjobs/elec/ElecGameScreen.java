package ro.solarstudios.flowcity.fjobs.elec;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.client.ui.Gfx;
import net.minecraft.client.gui.GuiGraphics;
import ro.solarstudios.flowcity.fjobs.FJobsClient;

/** Baza celor patru mini-jocuri: antet cu etapa, greseli si timp, zona de joc, mesajul serverului, bannerul de final. */
public abstract class ElecGameScreen extends ElecScreen {
    protected final JsonObject spec;
    protected final String token;
    protected final int maxMistakes;
    protected int mistakes;
    protected String message = "";
    protected int messageColor = MUT;
    protected final long opened = System.currentTimeMillis();
    private boolean closing;
    private boolean win;
    private long closeAt;
    private boolean routed;

    protected ElecGameScreen(JsonObject spec, String title) {
        super(title);
        this.spec = spec;
        this.token = str(spec, "token");
        this.maxMistakes = (int) num(spec, "maxMistakes");
        this.mistakes = (int) num(spec, "mistakesUsed");
    }

    boolean closing() {
        return closing;
    }

    /** Raspuns de la server pentru o operatie: text, greseli, final; restul il trateaza jocul. */
    void result(JsonObject d) {
        if (!token.equals(str(d, "token"))) return;
        String text = str(d, "text");
        boolean ok = bool(d, "ok");
        if (!text.isEmpty()) {
            message = text;
            messageColor = ok ? GOOD : BAD;
        }
        if (d.has("mistakes")) mistakes = (int) num(d, "mistakes");
        if (bool(d, "fin")) {
            closing = true;
            win = bool(d, "win");
            closeAt = System.currentTimeMillis() + (win ? 1300 : 1900);
            if (win) sndWin();
            else sndBad();
        } else if (!ok && !text.isEmpty()) {
            sndBad();
        }
        onResult(d);
    }

    protected abstract void onResult(JsonObject d);

    protected abstract void play(GuiGraphics ms, float t, float x, float y, float w, float h);

    protected boolean locked() {
        return closing;
    }

    protected void send(String op, String... kv) {
        JsonObject o = new JsonObject();
        o.addProperty("act", "el_g");
        o.addProperty("token", token);
        o.addProperty("op", op);
        for (int i = 0; i + 1 < kv.length; i += 2) o.addProperty(kv[i], kv[i + 1]);
        FJobsClient.requestOp(o);
    }

    protected void sendAns(String op, int[] ans) {
        JsonObject o = new JsonObject();
        o.addProperty("act", "el_g");
        o.addProperty("token", token);
        o.addProperty("op", op);
        JsonArray a = new JsonArray();
        for (int v : ans) a.add(v);
        o.add("ans", a);
        FJobsClient.requestOp(o);
    }

    @Override
    protected void content(GuiGraphics ms, float t) {
        if (closing && !routed && System.currentTimeMillis() > closeAt) {
            routed = true;
            ElecClient.route();
            return;
        }
        int no = (int) num(spec, "stageNo");
        int cnt = (int) num(spec, "stageCount");
        head(ms, str(spec, "title").toUpperCase(), "ETAPA " + no + " / " + cnt + "  ·  DIFICULTATE " + (int) num(spec, "diff"), true);
        // greseli + timp (dreapta sus)
        Gfx.text(ms, "GREȘELI", W - 160, 17, 5.4f, MUT, 2, true, 0.6f);
        pips(ms, W - 150, 14, maxMistakes + 1, mistakes);
        double el = (System.currentTimeMillis() - opened) / 1000.0;
        Gfx.text(ms, "TIMP " + mmss(el), W - 160, 29, 5.8f, el > num(spec, "limitSec") * 0.8 ? BAD : TXT, 2, true, 0.5f);
        box(ms, 14, 44, W - 28, 282, null);
        play(ms, t, 22, 52, W - 44, 266);
        if (!message.isEmpty()) {
            Gfx.rrect(ms, 14, 332, W - 28, 20, 4, 0xFF0C0F14);
            Gfx.rrect(ms, 14, 332, 3, 20, 1, messageColor);
            Gfx.text(ms, clipN(message, W - 60, 7f), 26, 345, 7f, messageColor, 0);
        }
        if (closing) {
            Gfx.rrect(ms, 14, 44, W - 28, 282, 5, win ? 0xCC06210F : 0xCC2A0A08);
            Gfx.text(ms, win ? "ETAPĂ COMPLETĂ" : "ETAPĂ EȘUATĂ", W / 2f, 176, 20, win ? GOOD : BAD, 1, true, 2f);
            bolt(ms, W / 2f, 140, 16, win ? GOOD : BAD);
        }
    }
}
