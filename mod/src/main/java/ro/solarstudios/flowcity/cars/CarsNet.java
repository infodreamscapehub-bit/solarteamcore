package ro.solarstudios.flowcity.cars;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import ro.solarstudios.flowcity.FlowCity;

/**
 * Canalul Forge pentru garaje. Client -> server: actiune (act, a, b). Server -> client: (kind, json).
 * Actiuni: "open" (a = garaj), "spawn" (a = garaj, b = masina), "store" (a = garaj), "inspect" (a = entitate).
 * Tipuri raspuns: "garages" (lista pentru markere), "garage" (meniul unui garaj), "inspect" (vehicul vazut de politie).
 */
public final class CarsNet {
    private CarsNet() {}

    public static SimpleChannel channel;

    public static void init() {
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation(FlowCity.MODID, "cars"), () -> "2", v -> true, v -> true);
        channel.registerMessage(0, Act.class, Act::write, Act::read, Act::handle);
        channel.registerMessage(1, Data.class, Data::write, Data::read, Data::handle);
    }

    public static void send(String act, int a, int b) {
        if (channel != null) channel.sendToServer(new Act(act, a, b));
    }

    static void reply(ServerPlayer p, String kind, String json) {
        channel.send(PacketDistributor.PLAYER.with(() -> p), new Data(kind, json));
    }

    public static final class Act {
        final String act;
        final int a;
        final int b;

        Act(String act, int a, int b) {
            this.act = act;
            this.a = a;
            this.b = b;
        }

        static void write(Act m, FriendlyByteBuf buf) {
            buf.writeUtf(m.act, 16);
            buf.writeVarInt(m.a);
            buf.writeVarInt(m.b);
        }

        static Act read(FriendlyByteBuf buf) {
            return new Act(buf.readUtf(16), buf.readVarInt(), buf.readVarInt());
        }

        static void handle(Act m, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer p = c.getSender();
                if (p != null) CarsServer.handle(p, m.act, m.a, m.b);
            });
            c.setPacketHandled(true);
        }
    }

    public static final class Data {
        final String kind;
        final String json;

        Data(String kind, String json) {
            this.kind = kind;
            this.json = json;
        }

        static void write(Data d, FriendlyByteBuf buf) {
            buf.writeUtf(d.kind, 16);
            buf.writeUtf(d.json, 262144);
        }

        static Data read(FriendlyByteBuf buf) {
            return new Data(buf.readUtf(16), buf.readUtf(262144));
        }

        static void handle(Data d, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ro.solarstudios.flowcity.cars.client.GaragesClient.receive(d.kind, d.json)));
            c.setPacketHandled(true);
        }
    }
}
