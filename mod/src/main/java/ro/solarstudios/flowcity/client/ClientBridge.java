package ro.solarstudios.flowcity.client;

import io.netty.buffer.Unpooled;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.network.play.ClientPlayNetHandler;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.CCustomPayloadPacket;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.network.NetworkEvent;
import ro.solarstudios.flowcity.client.admin.AdminData;
import ro.solarstudios.flowcity.client.admin.AdminPanelScreen;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.net.Bridge;

/** Trimitere si primire de pachete pe canalul flowcity:bridge (doar client). */
public final class ClientBridge {
    private ClientBridge() {}

    /** Momentul (ms) la care am cerut deschiderea panoului; 0 = nicio cerere in asteptare. */
    public static long pendingOpenAt;

    public static void registerHandler() {
        Bridge.channel.registerObject(new Handler());
    }

    // ------------------------------------------------------------------ primire

    public static final class Handler {
        @SubscribeEvent
        public void onPayload(NetworkEvent.ServerCustomPayloadEvent e) {
            NetworkEvent.Context ctx = e.getSource().get();
            ctx.setPacketHandled(true);
            final Runnable task;
            try {
                PacketBuffer buf = e.getPayload();
                if (buf == null || buf.readableBytes() < 1) return;
                int op = buf.readUnsignedByte();
                switch (op) {
                    case Bridge.S_OPEN: {
                        final boolean ok = buf.readBoolean();
                        final String staff = buf.readString(64);
                        final String server = buf.readString(64);
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
                        final String msg = buf.readString(256);
                        final boolean error = buf.readBoolean();
                        task = () -> toast(msg, error);
                        break;
                    }
                    case Bridge.S_MODULE: {
                        final String moduleId = buf.readString(64);
                        final String action = buf.readString(64);
                        // copie proprie: buffer-ul retea nu e valid dupa ce iese din handler
                        final PacketBuffer copy = new PacketBuffer(Unpooled.copiedBuffer(buf.readBytes(buf.readableBytes())));
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
        Screen s = Minecraft.getInstance().currentScreen;
        return s instanceof AdminPanelScreen ? (AdminPanelScreen) s : null;
    }

    private static void onOpen(boolean ok, String staff, String server) {
        pendingOpenAt = 0;
        Minecraft mc = Minecraft.getInstance();
        if (!ok) return;
        if (current() == null) mc.displayGuiScreen(new AdminPanelScreen(staff, server));
    }

    public static void toast(String message, boolean error) {
        AdminPanelScreen s = current();
        if (s != null) {
            s.onToast(message, error);
        } else {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendStatusMessage(new StringTextComponent((error ? "§c" : "§a") + message), true);
            }
        }
    }

    // ------------------------------------------------------------------ trimitere

    private static void send(int op, Consumer<PacketBuffer> body) {
        ClientPlayNetHandler conn = Minecraft.getInstance().getConnection();
        if (conn == null) return;
        PacketBuffer buf = new PacketBuffer(Unpooled.buffer());
        buf.writeByte(op);
        if (body != null) body.accept(buf);
        conn.sendPacket(new CCustomPayloadPacket(Bridge.ID, buf));
    }

    /**
     * Trimite un mesaj catre un modul de server. Limita pachetului client->server este ~32 KB:
     * pentru date mari (ex. harta) trimite pe bucati mici.
     */
    public static void sendModule(String moduleId, String action, Consumer<PacketBuffer> body) {
        send(Bridge.C_MODULE, b -> {
            b.writeString(moduleId);
            b.writeString(action);
            if (body != null) body.accept(b);
        });
    }

    public static void sendHello() {
        send(Bridge.C_HELLO, b -> b.writeVarInt(Bridge.PROTOCOL));
    }

    public static void sendOpenRequest() {
        pendingOpenAt = System.currentTimeMillis();
        send(Bridge.C_OPEN_REQUEST, null);
    }

    public static void requestList(String query, int page) {
        send(Bridge.C_PLAYER_LIST, b -> b.writeString(query).writeInt(page));
    }

    public static void requestProfile(String uuid) {
        send(Bridge.C_PROFILE, b -> b.writeString(uuid));
    }

    public static void requestLogs(String uuid, String type, int page) {
        send(Bridge.C_LOGS, b -> b.writeString(uuid).writeString(type).writeInt(page));
    }

    public static void sanction(String uuid, String type, int minutes, String reason) {
        send(Bridge.C_SANCTION, b -> {
            b.writeString(uuid);
            b.writeString(type);
            b.writeInt(minutes);
            b.writeString(reason);
        });
    }

    public static void revoke(int id) {
        send(Bridge.C_REVOKE, b -> b.writeInt(id));
    }

    public static void noteAdd(String uuid, String text) {
        send(Bridge.C_NOTE_ADD, b -> b.writeString(uuid).writeString(text));
    }

    public static void noteDel(int id) {
        send(Bridge.C_NOTE_DEL, b -> b.writeInt(id));
    }

    public static void action(String action, String uuid) {
        send(Bridge.C_ACTION, b -> b.writeString(action).writeString(uuid));
    }
}
