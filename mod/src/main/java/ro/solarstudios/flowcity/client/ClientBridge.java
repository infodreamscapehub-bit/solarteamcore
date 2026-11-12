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
import net.minecraftforge.network.NetworkEvent;
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

    public static void toast(String message, boolean error) {
        ro.solarstudios.flowcity.notify.NotifyToasts.add(error ? "BAD" : "OK", error ? "Eroare" : "Gata", message);
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
}
