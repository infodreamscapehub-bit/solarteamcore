package ro.solarstudios.flowcity.phone;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ui.FlowSounds;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;
import ro.solarstudios.flowcity.notify.NotifyToasts;

/** Modulul client "phone" (ePhone): contacte, mesaje, apeluri; bannerul de apel primit si tasta de deschidere (P). */
public final class PhoneClient extends JsonClientModule {
    private static PhoneClient instance;
    public static KeyBinding openKey;

    private PhoneClient() {
        on("state", d -> {
            PhoneState.unread = integer(d, "unread", 0);
            PhoneState.missed = integer(d, "missed", 0);
            if (PhoneState.unread > 0 && !shownUnread) {
                shownUnread = true;
                NotifyToasts.add("INFO", "Telefon", "Ai " + PhoneState.unread + " mesaje necitite.");
            }
        });
        on("contacts", d -> {
            PhoneState.contacts.clear();
            if (d.has("list")) for (JsonElement e : d.getAsJsonArray("list")) PhoneState.contacts.add(person(e.getAsJsonObject()));
            PhoneState.contactsLoaded = true;
        });
        on("threads", d -> {
            PhoneState.threads.clear();
            if (d.has("list")) {
                for (JsonElement e : d.getAsJsonArray("list")) {
                    JsonObject o = e.getAsJsonObject();
                    PhoneState.Thread t = new PhoneState.Thread();
                    t.uuid = str(o, "uuid", "");
                    t.name = str(o, "name", "?");
                    t.last = str(o, "last", "");
                    t.mine = bool(o, "mine", false);
                    t.online = bool(o, "online", false);
                    t.ts = lng(o, "ts", 0);
                    t.unread = integer(o, "unread", 0);
                    PhoneState.threads.add(t);
                }
            }
        });
        on("thread", d -> {
            PhoneState.thread.clear();
            PhoneState.threadWith = d.has("with") ? person(d.getAsJsonObject("with")) : null;
            if (d.has("list")) {
                for (JsonElement e : d.getAsJsonArray("list")) {
                    JsonObject o = e.getAsJsonObject();
                    PhoneState.Msg m = new PhoneState.Msg();
                    m.mine = bool(o, "mine", false);
                    m.text = str(o, "text", "");
                    m.ts = lng(o, "ts", 0);
                    PhoneState.thread.add(m);
                }
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof PhoneScreen) ((PhoneScreen) mc.screen).threadLoaded();
        });
        on("calls", d -> {
            PhoneState.calls.clear();
            if (d.has("list")) {
                for (JsonElement e : d.getAsJsonArray("list")) {
                    JsonObject o = e.getAsJsonObject();
                    PhoneState.CallLog c = new PhoneState.CallLog();
                    c.uuid = str(o, "uuid", "");
                    c.name = str(o, "name", "?");
                    c.out = bool(o, "out", false);
                    c.ts = lng(o, "ts", 0);
                    c.dur = lng(o, "dur", 0);
                    c.status = str(o, "status", "");
                    PhoneState.calls.add(c);
                }
            }
        });
        on("msg_in", d -> {
            String name = str(d, "name", "?");
            String text = str(d, "text", "");
            String uuid = str(d, "uuid", "");
            Minecraft mc = Minecraft.getInstance();
            boolean viewing = mc.screen instanceof PhoneScreen && ((PhoneScreen) mc.screen).viewingThread(uuid);
            if (viewing) {
                FlowSounds.play("message", 0.7F);
                requestThread(uuid);
            } else {
                PhoneState.unread++;
                NotifyToasts.add("INFO", "Mesaj de la " + name, text);
                FlowSounds.play("message", 0.9F);
                if (mc.screen instanceof PhoneScreen) requestThreads();
            }
        });
        on("call_state", d -> {
            String state = str(d, "state", "");
            PhoneState.Person who = d.has("with") ? person(d.getAsJsonObject("with")) : new PhoneState.Person();
            if ("ringing".equals(state)) {
                PhoneState.Call c = new PhoneState.Call();
                c.id = lng(d, "id", 0);
                c.with = who;
                c.incoming = bool(d, "incoming", false);
                PhoneState.call = c;
                PhoneState.lastRing = 0;
            } else if ("active".equals(state)) {
                if (PhoneState.call != null) {
                    PhoneState.call.active = true;
                    PhoneState.call.since = System.currentTimeMillis();
                }
            } else if ("ended".equals(state)) {
                String reason = str(d, "reason", "");
                long dur = lng(d, "duration", 0);
                boolean wasIncoming = PhoneState.call != null && PhoneState.call.incoming;
                PhoneState.call = null;
                String msg;
                if ("ANSWERED".equals(reason)) msg = "Apel terminat (" + Ui.duration(dur) + ")";
                else if ("MISSED".equals(reason)) msg = wasIncoming ? "Apel pierdut de la " + who.name : who.name + " nu a raspuns";
                else if ("DECLINED".equals(reason)) msg = wasIncoming ? "Apel respins" : who.name + " a respins apelul";
                else msg = "Apel anulat";
                NotifyToasts.add("INFO", "Telefon", msg);
                requestCalls();
            }
        });
        on("open", d -> Minecraft.getInstance().setScreen(new PhoneScreen()));
    }

    private static boolean shownUnread;

    private static PhoneState.Person person(JsonObject o) {
        PhoneState.Person p = new PhoneState.Person();
        p.uuid = str(o, "uuid", "");
        p.name = str(o, "name", "?");
        p.id = integer(o, "id", 0);
        p.online = bool(o, "online", false);
        return p;
    }

    @Override
    public String id() {
        return "phone";
    }

    @Override
    public void onLogout() {
        PhoneState.reset();
        shownUnread = false;
    }

    public static void register() {
        instance = new PhoneClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    /** Apelat din ClientInit.setup. */
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        openKey = new KeyBinding("key.flowcity.phone", GLFW.GLFW_KEY_P, "key.categories.flowcity");
        event.register(openKey);
    }

    // ------------------------------------------------------------------ cereri

    private static void req(String action, JsonObject o) {
        if (instance != null) instance.send(action, o == null ? new JsonObject() : o);
    }

    private static JsonObject with(String key, String val) {
        JsonObject o = new JsonObject();
        o.addProperty(key, val);
        return o;
    }

    public static void requestContacts() {
        req("contacts", null);
    }

    public static void requestThreads() {
        req("threads", null);
    }

    public static void requestThread(String uuid) {
        req("thread", with("uuid", uuid));
    }

    public static void requestCalls() {
        req("calls", null);
    }

    public static void addContact(String q) {
        req("contact_add", with("q", q));
    }

    public static void removeContact(String uuid) {
        req("contact_del", with("uuid", uuid));
    }

    public static void sendMessage(String to, String text) {
        JsonObject o = with("to", to);
        o.addProperty("text", text);
        req("send", o);
    }

    public static void call(String uuid) {
        req("call", with("uuid", uuid));
    }

    /** Suna dupa ID (cifre) sau nume, de pe tastatura. */
    public static void callQuery(String q) {
        req("call", with("q", q));
    }

    public static void answer() {
        req("call_answer", null);
    }

    public static void decline() {
        req("call_decline", null);
    }

    public static void hangup() {
        req("call_end", null);
    }

    // ------------------------------------------------------------------ evenimente

    public static final class Events {
        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;
            if (openKey != null && openKey.consumeClick() && mc.screen == null) mc.setScreen(new PhoneScreen());
            PhoneState.Call c = PhoneState.call;
            if (c != null && c.incoming && !c.active) {
                long now = System.currentTimeMillis();
                if (now - PhoneState.lastRing > 2400) {
                    PhoneState.lastRing = now;
                    FlowSounds.play("ring", 1.0F);
                }
            }
        }

        @SubscribeEvent
        public void onKey(InputEvent.KeyInputEvent e) {
            if (e.getAction() != GLFW.GLFW_PRESS) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null || mc.player == null) return;
            PhoneState.Call c = PhoneState.call;
            if (c == null || !c.incoming || c.active) return;
            if (e.getKey() == GLFW.GLFW_KEY_Y) answer();
            else if (e.getKey() == GLFW.GLFW_KEY_N) decline();
        }

        @SubscribeEvent
        public void onRender(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
                PhoneState.Call c = PhoneState.call;
            Minecraft mc = Minecraft.getInstance();
            if (c == null || !c.incoming || c.active || mc.player == null || mc.screen instanceof PhoneScreen) return;
            try {
                GuiGraphics g = new GuiGraphics(e.getMatrixStack());
                long now = System.currentTimeMillis();
                float w = 244f;
                float h = 54f;
                float x = (mc.getWindow().getGuiScaledWidth() - w) / 2f;
                float t0 = Math.min(1f, (now - c.ringStart) / 420f);
                float u = t0 - 1f;
                float back = Math.max(0f, 1f + 2.70158f * u * u * u + 1.70158f * u * u);
                float y = -h - 6f + back * (h + 16f);
                float pulse = 0.5f + 0.5f * (float) Math.sin(now / 200.0);
                int green = 0xFF4CC878;
                Gfx.rrect(g, x - 1, y + 3, w + 2, h + 2, 15, 0x66000000);
                Gfx.rrect(g, x, y, w, h, 14, Gfx.mixArgb(0xFF2A2E36, green, 0.35f + 0.35f * pulse));
                Gfx.rrect(g, x + 1, y + 1, w - 2, h - 2, 13, 0xFF1F2228, 0xFF121418);
                float ax = x + 28f;
                float ay = y + h / 2f;
                for (int k = 0; k < 2; k++) {
                    float p = (float) (((now / 1400.0) + k * 0.5) % 1.0);
                    Gfx.ring(g, ax, ay, 15 + p * 12, 16.5f + p * 12, Gfx.fade(green, (1f - p) * 0.4f), Gfx.fade(green, 0f));
                }
                int ac = PhoneScreen.avatarColor(c.with.name);
                Gfx.disc(g, ax, ay, 15, Gfx.mixArgb(ac, 0xFFFFFFFF, 0.2f), Gfx.mixArgb(ac, 0xFF000000, 0.25f), -3, -4);
                String ini = c.with.name == null || c.with.name.isEmpty() ? "?" : c.with.name.substring(0, 1).toUpperCase();
                Gfx.text(g, ini, ax, ay + 5, 14, 0xFFF4F1EA, 1, true, 0f);
                float tx = x + 52f;
                Gfx.text(g, "APEL PRIMIT", tx, y + 17, 6.5f, green, 0, false, 1f);
                String nm = c.with.name == null ? "?" : c.with.name;
                while (nm.length() > 1 && Gfx.width(nm, 10.5f, false) > w - 52 - 70) nm = nm.substring(0, nm.length() - 1);
                Gfx.text(g, nm, tx, y + 31, 10.5f, 0xFFF4F1EA, 0);
                // taste Y / N
                float kx = x + w - 56f;
                Gfx.rrect(g, kx, y + 14, 22, 26, 8, 0xFF1F4D31, 0xFF16382A);
                Gfx.text(g, "Y", kx + 11, y + 31, 10, 0xFF7BE3A0, 1, true, 0f);
                Gfx.rrect(g, kx + 26, y + 14, 22, 26, 8, 0xFF5A2522, 0xFF3F1A18);
                Gfx.text(g, "N", kx + 37, y + 31, 10, 0xFFF08A82, 1, true, 0f);
                Gfx.text(g, "r\u0103spunde", kx + 11, y + h - 5, 5.2f, 0xFF6F6D68, 1);
                Gfx.text(g, "respinge", kx + 37, y + h - 5, 5.2f, 0xFF6F6D68, 1);
            } catch (RuntimeException ex) {
                PhoneState.call = null;
            }
        }
    }
}
