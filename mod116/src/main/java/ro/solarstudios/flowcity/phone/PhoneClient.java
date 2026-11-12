package ro.solarstudios.flowcity.phone;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import ro.solarstudios.flowcity.compat.RegisterKeyMappingsEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.client.ui.FlowSounds;
import ro.solarstudios.flowcity.client.ui.Theme;
import ro.solarstudios.flowcity.client.ui.Ui;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;
import ro.solarstudios.flowcity.notify.NotifyToasts;

/** Modulul client "phone" (ePhone): contacte, mesaje, apeluri; bannerul de apel primit si tasta de deschidere (P). */
public final class PhoneClient extends JsonClientModule {
    private static PhoneClient instance;
    public static KeyMapping openKey;

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
        openKey = new KeyMapping("key.flowcity.phone", GLFW.GLFW_KEY_P, "key.categories.flowcity");
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
        public void onKey(InputEvent.Key e) {
            if (e.getAction() != GLFW.GLFW_PRESS) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen != null || mc.player == null) return;
            PhoneState.Call c = PhoneState.call;
            if (c == null || !c.incoming || c.active) return;
            if (e.getKey() == GLFW.GLFW_KEY_Y) answer();
            else if (e.getKey() == GLFW.GLFW_KEY_N) decline();
        }

        @SubscribeEvent
        public void onRender(RenderGuiEvent.Post e) {
                PhoneState.Call c = PhoneState.call;
            Minecraft mc = Minecraft.getInstance();
            if (c == null || !c.incoming || c.active || mc.player == null || mc.screen instanceof PhoneScreen) return;
            try {
                GuiGraphics ms = e.getGuiGraphics();
                long now = System.currentTimeMillis();
                int w = 230;
                int x = (mc.getWindow().getGuiScaledWidth() - w) / 2;
                float in = Math.min(1f, (now - c.ringStart) / 300f);
                int y = 8 - Math.round((1f - in) * 50f);
                float pulse = 0.5f + 0.5f * (float) Math.sin(now / 180.0);
                Ui.frame(ms, x, y, w, 34, Theme.alpha(0xFF0A1220, 235), Theme.mix(Theme.LINE, Theme.OK, 0.5f + 0.4f * pulse));
                Ui.rect(ms, x + 8, y + 12, 10, 10, Theme.alpha(Theme.OK, (int) (150 + 100 * pulse)));
                Ui.textShadow(ms, "Apel de la " + c.with.name, x + 26, y + 7, Theme.TEXT);
                Ui.text(ms, "[Y] Raspunde    [N] Respinge", x + 26, y + 19, Theme.MUTED);
            } catch (RuntimeException ex) {
                PhoneState.call = null;
            }
        }
    }
}
