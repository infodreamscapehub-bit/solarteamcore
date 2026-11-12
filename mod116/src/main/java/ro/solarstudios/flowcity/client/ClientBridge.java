package ro.solarstudios.flowcity.client;

import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.network.NetworkEvent;
import net.minecraft.client.gui.screens.Screen;
import ro.solarstudios.flowcity.client.admin.AdminData;
import ro.solarstudios.flowcity.client.admin.AdminPanelScreen;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.net.Bridge;

/** Trimitere si primire de pachete pe canalul flowcity:bridge (doar client). */
public final class ClientBridge {
    private ClientBridge() {}

    /** Momentul (ms) la care am cerut deschiderea panoului; 0 = nicio cerere in asteptare. */
    public static long pendingOpenAt;

    /** Devine true cand serverul a raspuns cel putin o data pe canal (handshake reusit). */
    public static volatile boolean connected;

    public static void registerHandler() {
        Bridge.channel.registerObject(new Handler());
    }

    // ------------------------------------------------------------------ primire

    public static final class Handler {
        @SubscribeEvent
        public void onPayload(NetworkEvent.ServerCustomPayloadEvent e) {
            NetworkEvent.Context ctx = e.getSource().get();
            ctx.setPacketHandled(true);
            connected = true;
            final Runnable task;
            try {
                FriendlyByteBuf buf = e.getPayload();
                if (buf == null || buf.readableBytes() < 1) return;
                int op = buf.readUnsignedByte();
                switch (op) {
                    case Bridge.S_OPEN: {
                        final boolean ok = buf.readBoolean();
                        final String staff = buf.readUtf(64);
                        final String server = buf.readUtf(64);
                        task = () -> onOpen(ok, staff, server);
                        break;
                    }
                    case Bridge.S_PLAYER_LIST: {
                        final AdminData.PlayerList l = AdminData.readPlayerList(buf);
                        task = () -> {
                            AdminPanelScreen s = current();
                            if (s != null) s.onList(l);
                        };
                        break;
                    }
                    case Bridge.S_PROFILE: {
                        final AdminData.Profile p = AdminData.readProfile(buf);
                        task = () -> {
                            AdminPanelScreen s = current();
                            if (s != null) s.onProfile(p);
                        };
                        break;
                    }
                    case Bridge.S_LOGS: {
                        final AdminData.Logs l = AdminData.readLogs(buf);
                        task = () -> {
                            AdminPanelScreen s = current();
                            if (s != null) s.onLogs(l);
                        };
                        break;
                    }
                    case Bridge.S_TOAST: {
                        final String msg = buf.readUtf(256);
                        final boolean error = buf.readBoolean();
                        task = () -> toast(msg, error);
                        break;
                    }
                    case Bridge.S_MODULE: {
                        final String moduleId = buf.readUtf(64);
                        final String action = buf.readUtf(64);
                        // copie proprie: buffer-ul retea nu e valid dupa ce iese din handler
                        final FriendlyByteBuf copy = new FriendlyByteBuf(Unpooled.copiedBuffer(buf.readBytes(buf.readableBytes())));
                        task = () -> {
                            try {
                                ClientModules.dispatch(moduleId, action, copy);
                            } finally {
                                copy.release();
                            }
                        };
                        break;
                    }
                    default:
                        return;
                }
            } catch (RuntimeException ex) {
                // pachet corupt/necunoscut: il ignoram, nu stricam conexiunea
                return;
            }
            ctx.enqueueWork(task);
        }
    }

    private static AdminPanelScreen current() {
        Screen s = Minecraft.getInstance().screen;
        return s instanceof AdminPanelScreen ? (AdminPanelScreen) s : null;
    }

    private static void onOpen(boolean ok, String staff, String server) {
        pendingOpenAt = 0;
        if (!ok) return;
        if (current() == null) Minecraft.getInstance().setScreen(new AdminPanelScreen(staff, server));
    }

    public static void toast(String message, boolean error) {
        AdminPanelScreen s = current();
        if (s != null) s.onToast(message, error);
        else ro.solarstudios.flowcity.notify.NotifyToasts.add(error ? "BAD" : "OK", error ? "Eroare" : "Gata", message);
    }

    // ------------------------------------------------------------------ trimitere

    private static void send(int op, Consumer<FriendlyByteBuf> body) {
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn == null) return;
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeByte(op);
        if (body != null) body.accept(buf);
        conn.send(new ServerboundCustomPayloadPacket(Bridge.ID, buf));
    }

    /**
     * Trimite un mesaj catre un modul de server. Limita pachetului client->server este ~32 KB:
     * pentru date mari (ex. harta) trimite pe bucati mici.
     */
    public static void sendModule(String moduleId, String action, Consumer<FriendlyByteBuf> body) {
        send(Bridge.C_MODULE, b -> {
            b.writeUtf(moduleId);
            b.writeUtf(action);
            if (body != null) body.accept(b);
        });
    }

    public static void sendHello() {
        // Bukkit trimite pachete de plugin doar pe canale pe care clientul le-a "inregistrat" (minecraft:register).
        // Clientul Forge nu o face singur, asa ca o facem noi; altfel serverul ignora tacit tot ce trimite pluginul.
        ClientPacketListener conn = Minecraft.getInstance().getConnection();
        if (conn != null) {
            FriendlyByteBuf reg = new FriendlyByteBuf(Unpooled.buffer());
            reg.writeBytes((Bridge.ID.toString() + "\0").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            conn.send(new ServerboundCustomPayloadPacket(new ResourceLocation("minecraft", "register"), reg));
        }
        send(Bridge.C_HELLO, b -> b.writeVarInt(Bridge.PROTOCOL));
    }

    public static void sendOpenRequest() {
        pendingOpenAt = System.currentTimeMillis();
        send(Bridge.C_OPEN_REQUEST, null);
    }

    public static void requestList(String query, int page) {
        send(Bridge.C_PLAYER_LIST, b -> b.writeUtf(query).writeInt(page));
    }

    public static void requestProfile(String uuid) {
        send(Bridge.C_PROFILE, b -> b.writeUtf(uuid));
    }

    public static void requestLogs(String uuid, String type, int page) {
        send(Bridge.C_LOGS, b -> b.writeUtf(uuid).writeUtf(type).writeInt(page));
    }

    public static void sanction(String uuid, String type, int minutes, String reason) {
        send(Bridge.C_SANCTION, b -> {
            b.writeUtf(uuid);
            b.writeUtf(type);
            b.writeInt(minutes);
            b.writeUtf(reason);
        });
    }

    public static void revoke(int id) {
        send(Bridge.C_REVOKE, b -> b.writeInt(id));
    }

    public static void noteAdd(String uuid, String text) {
        send(Bridge.C_NOTE_ADD, b -> b.writeUtf(uuid).writeUtf(text));
    }

    public static void noteDel(int id) {
        send(Bridge.C_NOTE_DEL, b -> b.writeInt(id));
    }

    public static void action(String action, String uuid) {
        send(Bridge.C_ACTION, b -> b.writeUtf(action).writeUtf(uuid));
    }
}
