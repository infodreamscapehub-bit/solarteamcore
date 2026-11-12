package ro.solarstudios.flowcity.vehicle;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import ro.solarstudios.flowcity.FlowCity;

/** Canalul Forge pentru actiunile din masina (claxon, faruri, sirena, incuiere, locuri, portbagaj). Client -> server. */
public final class VehicleNet {
    private VehicleNet() {}

    public static final int HORN = 1;
    public static final int LIGHTS = 2;
    public static final int SIREN = 3;
    public static final int LOCK = 4;
    public static final int SEAT = 5;
    public static final int TRUNK = 6;

    private static final String VERSION = "1";
    public static SimpleChannel channel;

    public static void init() {
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation(FlowCity.MODID, "vehicle"), () -> VERSION, v -> true, v -> true);
        channel.registerMessage(0, Action.class, Action::write, Action::read, Action::handle);
    }

    public static void send(int entityId, int action, int arg) {
        if (channel != null) channel.sendToServer(new Action(entityId, action, arg));
    }

    public static final class Action {
        final int entityId;
        final int action;
        final int arg;

        Action(int entityId, int action, int arg) {
            this.entityId = entityId;
            this.action = action;
            this.arg = arg;
        }

        static void write(Action a, FriendlyByteBuf buf) {
            buf.writeVarInt(a.entityId);
            buf.writeByte(a.action);
            buf.writeVarInt(a.arg);
        }

        static Action read(FriendlyByteBuf buf) {
            return new Action(buf.readVarInt(), buf.readByte(), buf.readVarInt());
        }

        static void handle(Action a, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer p = c.getSender();
                if (p == null) return;
                Entity e = p.level().getEntity(a.entityId);
                if (e instanceof CarEntity car && p.distanceToSqr(car) < 12 * 12) car.handleAction(p, a.action, a.arg);
            });
            c.setPacketHandled(true);
        }
    }
}
