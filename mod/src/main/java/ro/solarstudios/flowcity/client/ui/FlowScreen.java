package ro.solarstudios.flowcity.client.ui;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import org.lwjgl.glfw.GLFW;

/**
 * Baza pentru ecranele FlowCity (mod imediat, fara widget-uri vanilla): panou centrat cu stilul {@link Theme},
 * zone clicabile reconstruite la fiecare frame, campuri de text, toast.
 *
 * <pre>
 *   public class MyScreen extends FlowScreen {
 *     MyScreen() { super("Titlu", 300, 200); }
 *     protected void draw(GuiGraphics ms, int mx, int my) {
 *       header(ms, "Titlu", "subtitlu", mx, my);
 *       button(ms, "OK", px + 10, py + 40, 50, 14, mx, my, true, Theme.ACCENT_DIM, () -> {});
 *     }
 *   }
 * </pre>
 * Coordonatele panoului: {@link #px}, {@link #py}, {@link #pw}, {@link #ph}.
 */
public abstract class FlowScreen extends Screen {
    protected static final int HEAD = 24;

    private static final class Hit {
        final int x;
        final int y;
        final int w;
        final int h;
        final Runnable action;

        Hit(int x, int y, int w, int h, Runnable action) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.action = action;
        }
    }

    private final List<Hit> hits = new ArrayList<>();
    private final int wantW;
    private final int wantH;
    private String toastText = "";
    private boolean toastError;
    private long toastUntil;

    protected int px;
    protected int py;
    protected int pw;
    protected int ph;

    protected FlowScreen(String title, int wantedWidth, int wantedHeight) {
        super(Component.literal(title));
        this.wantW = wantedWidth;
        this.wantH = wantedHeight;
    }

    /** Deseneaza continutul ecranului (panoul, fundalul si toast-ul sunt deja desenate/urmeaza). */
    protected abstract void draw(GuiGraphics ms, int mx, int my);

    /** Campurile de text active in momentul curent (pentru focus/taste). */
    protected List<EditBox> fields() {
        return Collections.emptyList();
    }

    /** Apasare Enter intr-un camp de text. */
    protected void onEnter(EditBox field) {}

    /** Daca ecranul tine jocul in pauza (implicit nu). */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Daca panoul are fundal intunecat si cadru (implicit da). False pentru ecrane full-screen proprii. */
    protected boolean framed() {
        return true;
    }

    // ------------------------------------------------------------------ ciclu de viata

    @Override
    public void tick() {
        for (EditBox f : fields()) f.tick();
    }

    @Override
    public void render(GuiGraphics ms, int mx, int my, float pt) {
        hits.clear();
        pw = Math.min(width - 16, wantW);
        ph = Math.min(height - 16, wantH);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        if (framed()) {
            Ui.rect(ms, 0, 0, width, height, Theme.DIM);
            Ui.round(ms, px - 1, py + 2, pw + 2, ph, 0x66000000);
            Ui.frame(ms, px, py, pw, ph, Theme.PANEL, Theme.LINE);
        }
        try {
            draw(ms, mx, my);
        } catch (RuntimeException ex) {
            // un bug de desenare nu trebuie sa dea crash jocului: logam o data si afisam mesaj
            if (!drawFailed) {
                drawFailed = true;
                org.apache.logging.log4j.LogManager.getLogger("FlowCity").error("Eroare la desenarea ecranului " + getClass().getSimpleName(), ex);
            }
            Ui.textCenter(ms, "Eroare interfata (vezi latest.log). Apasa ESC.", px + pw / 2, py + ph / 2, Theme.BAD);
        }
        drawToast(ms);
    }

    private boolean drawFailed;

    // ------------------------------------------------------------------ componente

    /** Antet standard: titlu in small caps, subtitlu discret, inchidere ca text simplu. */
    protected void header(GuiGraphics ms, String title, String subtitle, int mx, int my) {
        Ui.rect(ms, px + 1, py + 1, pw - 2, HEAD - 1, Theme.PANEL_2);
        Ui.rect(ms, px + 1, py + HEAD, pw - 2, 1, Theme.LINE);
        Ui.smallCaps(ms, title, px + 10, py + 8, Theme.TEXT);
        if (subtitle != null && !subtitle.isEmpty()) {
            Ui.text(ms, subtitle, px + 10 + Ui.smallCapsWidth(title) + 8, py + 8, Theme.FAINT);
        }
        int cx = px + pw - 20;
        boolean hover = Ui.in(mx, my, cx, py + 4, 16, 16);
        if (hover) Ui.round(ms, cx, py + 4, 16, 16, Theme.PANEL_3);
        // X desenat din doua diagonale de cate 1px
        int col = hover ? Theme.BAD : Theme.MUTED;
        for (int i = 0; i < 7; i++) {
            Ui.rect(ms, cx + 5 + i, py + 8 + i, 1, 1, col);
            Ui.rect(ms, cx + 11 - i, py + 8 + i, 1, 1, col);
        }
        hit(cx, py + 4, 16, 16, this::onClose);
    }

    /**
     * Buton plin, cu muchie inferioara mai inchisa (efect de apasare) si fara stralucire.
     * Pe accent textul e inchis la culoare, pe rest e deschis.
     */
    protected void button(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my,
                          boolean enabled, int color, Runnable action) {
        boolean hover = enabled && Ui.in(mx, my, x, y, w, h);
        int bg = !enabled ? Theme.DISABLED : (hover ? Theme.lighten(color, 14) : color);
        Ui.round(ms, x, y, w, h, bg);
        if (enabled) Ui.rect(ms, x + 1, y + h - 1, w - 2, 1, Theme.mix(bg, 0xFF000000, 0.35f));
        int fg = !enabled ? Theme.FAINT : (luma(bg) > 150 ? 0xFF1A1A1A : Theme.TEXT);
        Ui.textCenter(ms, Ui.clip(label, w - 6), x + w / 2, y + (h - 8) / 2, fg);
        if (enabled) hits.add(new Hit(x, y, w, h, action));
    }

    private static int luma(int c) {
        return (((c >> 16) & 0xFF) * 299 + ((c >> 8) & 0xFF) * 587 + (c & 0xFF) * 114) / 1000;
    }

    /** Buton secundar: doar contur, fundal transparent. */
    protected void ghost(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my, Runnable action) {
        boolean hover = Ui.in(mx, my, x, y, w, h);
        Ui.frame(ms, x, y, w, h, hover ? Theme.PANEL_3 : Theme.PANEL, hover ? Theme.MUTED : Theme.LINE);
        Ui.textCenter(ms, Ui.clip(label, w - 6), x + w / 2, y + (h - 8) / 2, hover ? Theme.TEXT : Theme.MUTED);
        hits.add(new Hit(x, y, w, h, action));
    }

    /** Buton fara text (ex. desenat de apelant), doar zona clicabila. */
    protected void hit(int x, int y, int w, int h, Runnable action) {
        hits.add(new Hit(x, y, w, h, action));
    }

    /** Fila (tab) cu subliniere cand e selectata. Intoarce latimea desenata. */
    protected int chip(GuiGraphics ms, String label, int x, int y, int w, int h, int mx, int my,
                       boolean selected, int color, Runnable action) {
        boolean hover = Ui.in(mx, my, x, y, w, h);
        int fg = selected ? Theme.TEXT : (hover ? Theme.TEXT : Theme.MUTED);
        Ui.textCenter(ms, label, x + w / 2, y + (h - 8) / 2 - 1, fg);
        if (selected) Ui.rect(ms, x + 2, y + h - 2, w - 4, 2, Theme.ACCENT);
        else if (hover) Ui.rect(ms, x + 2, y + h - 1, w - 4, 1, Theme.LINE);
        hits.add(new Hit(x, y, w, h, action));
        return w;
    }

    /** Eticheta de stare: punct colorat + text. Intoarce pozitia x urmatoare. */
    protected int tag(GuiGraphics ms, String label, int x, int y, int color) {
        Ui.rect(ms, x, y + 4, 4, 4, color);
        Ui.text(ms, label, x + 7, y + 2, Theme.MUTED);
        return x + 7 + Ui.width(label) + 10;
    }

    /** Camp de text cu indiciu gri cand e gol. */
    protected EditBox field(String hint, int maxLength, Consumer<String> onChange) {
        EditBox f = new EditBox(Minecraft.getInstance().font, 0, 0, 100, 14, Component.literal(hint));
        f.setMaxLength(maxLength);
        f.setBordered(false);
        f.setTextColor(Theme.TEXT);
        f.setSuggestion(hint);
        f.setResponder(s -> {
            f.setSuggestion(s.isEmpty() ? hint : null);
            if (onChange != null) onChange.accept(s);
        });
        return f;
    }

    /** Deseneaza un camp de text cu cadru la pozitia data. */
    protected void drawField(GuiGraphics ms, EditBox f, int x, int y, int w, int h, int mx, int my) {
        Ui.round(ms, x, y, w, h, Theme.PANEL_2);
        Ui.rect(ms, x + 1, y + h - 1, w - 2, 1, f.isFocused() ? Theme.ACCENT : Theme.LINE);
        f.setX(x + 5);
        f.setY(y + (h - 8) / 2);
        f.setWidth(w - 10);
        f.render(ms, mx, my, 0);
    }

    /** Focus fara dependenta de numele metodei: EditBox isi seteaza singur focusul la click. */
    protected void focus(EditBox f, boolean on) {
        f.mouseClicked(on ? f.getX() + 1 : -10000, on ? f.getY() + 1 : -10000, 0);
    }

    protected void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    public void toast(String message, boolean error) {
        this.toastText = message;
        this.toastError = error;
        this.toastUntil = System.currentTimeMillis() + 4000;
    }

    private void drawToast(GuiGraphics ms) {
        if (System.currentTimeMillis() > toastUntil || toastText.isEmpty()) return;
        int w = Math.min(Math.max(80, pw - 20), Ui.width(toastText) + 20);
        int x = px + (pw - w) / 2;
        int y = py + ph - 22;
        int color = toastError ? Theme.BAD : Theme.OK;
        Ui.round(ms, x, y, w, 16, Theme.PANEL_3);
        Ui.rect(ms, x, y + 1, 2, 14, color);
        Ui.textCenter(ms, Ui.clip(toastText, w - 12), x + w / 2 + 1, y + 4, Theme.TEXT);
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            boolean onField = false;
            for (EditBox f : fields()) {
                boolean inside = Ui.in((int) mx, (int) my, f.getX() - 4, f.getY() - 4, f.getWidth() + 8, 16);
                f.mouseClicked(mx, my, button);
                if (inside) {
                    focus(f, true);
                    onField = true;
                } else {
                    focus(f, false);
                }
            }
            if (onField) return true;
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (Ui.in((int) mx, (int) my, h.x, h.y, h.w, h.h)) {
                    click();
                    h.action.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        for (EditBox f : fields()) {
            if (f.isFocused()) {
                if (key == GLFW.GLFW_KEY_ESCAPE) {
                    focus(f, false);
                    return true;
                }
                if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                    onEnter(f);
                    return true;
                }
                f.keyPressed(key, scan, mods);
                return true; // cat timp scrii, tastele nu declanseaza alte actiuni
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        for (EditBox f : fields()) {
            if (f.isFocused() && f.charTyped(c, mods)) return true;
        }
        return super.charTyped(c, mods);
    }
}
