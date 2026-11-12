package ro.solarstudios.flowcity.cars;

import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
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

/** Canalul Forge pentru garaje: clientul cere lista / actiuni, serverul raspunde cu JSON. */
public final class CarsNet {
    private CarsNet() {}

    public static SimpleChannel channel;

    public static void init() {
        channel = NetworkRegistry.newSimpleChannel(new ResourceLocation(FlowCity.MODID, "cars"), () -> "1", v -> true, v -> true);
        channel.registerMessage(0, Act.class, Act::write, Act::read, Act::handle);
        channel.registerMessage(1, Data.class, Data::write, Data::read, Data::handle);
    }

    /** Client -> server: act = "list" | "spawn" | "store" | "recover" | "sell". */
    public static void send(BlockPos garage, String act, int id) {
        if (channel != null) channel.sendToServer(new Act(garage, act, id));
    }

    static void reply(ServerPlayer p, String json) {
        channel.send(PacketDistributor.PLAYER.with(() -> p), new Data(json));
    }

    public static final class Act {
        final BlockPos pos;
        final String act;
        final int id;

        Act(BlockPos pos, String act, int id) {
            this.pos = pos;
            this.act = act;
            this.id = id;
        }

        static void write(Act a, FriendlyByteBuf b) {
            b.writeBlockPos(a.pos);
            b.writeUtf(a.act, 16);
            b.writeVarInt(a.id);
        }

        static Act read(FriendlyByteBuf b) {
            return new Act(b.readBlockPos(), b.readUtf(16), b.readVarInt());
        }

        static void handle(Act a, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> {
                ServerPlayer p = c.getSender();
                if (p != null) CarsServer.handle(p, a.pos, a.act, a.id);
            });
            c.setPacketHandled(true);
        }
    }

    public static final class Data {
        final String json;

        Data(String json) {
            this.json = json;
        }

        static void write(Data d, FriendlyByteBuf b) {
            b.writeUtf(d.json, 262144);
        }

        static Data read(FriendlyByteBuf b) {
            return new Data(b.readUtf(262144));
        }

        static void handle(Data d, Supplier<NetworkEvent.Context> ctx) {
            NetworkEvent.Context c = ctx.get();
            c.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ro.solarstudios.flowcity.cars.client.GarageScreen.receive(d.json)));
            c.setPacketHandled(true);
        }
    }
}
