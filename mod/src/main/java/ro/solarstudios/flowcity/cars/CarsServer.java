package ro.solarstudios.flowcity.cars;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import ro.solarstudios.flowcity.content.ModContent;

/**
 * Garajele pe server: scoate / parcheaza / recupereaza masinile jucatorilor. Lucreaza cu NBT-ul generic al entitatii,
 * deci merge cu orice vehicul (MrCrayfish sau altele) fara sa depinda de clasele lor.
 * Comanda /fccars give (nivel 2) e folosita de plugin dupa plata la dealer.
 */
public final class CarsServer {
    private static final String TAG = "fcVehId";
    private static final double GARAGE_RANGE = 24;

    // ------------------------------------------------------------------ actiuni din interfata

    static void handle(ServerPlayer p, BlockPos pos, String act, int id) {
        ServerLevel level = p.serverLevel();
        BlockState st = level.getBlockState(pos);
        if (!st.is(ModContent.GARAGE.get()) || p.distanceToSqr(Vec3.atCenterOf(pos)) > 10 * 10) return;
        GarageData data = GarageData.get(p.server);
        GarageData.Car car = id > 0 ? data.get(id) : null;
        if (car != null && !car.owner.equals(p.getUUID())) car = null;
        String msg = "";
        boolean ok = true;
        switch (act) {
            case "spawn":
                if (car == null || car.out) break;
                Entity e = spawn(level, pos, st, car);
                if (e == null) {
                    ok = false;
                    msg = "Nu e loc liber in fata garajului.";
                } else {
                    data.setDirty();
                    msg = car.name + " te asteapta in fata garajului.";
                }
                break;
            case "store": {
                Entity v = findToStore(p, pos, data);
                if (v == null) {
                    ok = false;
                    msg = "Adu masina langa garaj (sau stai in ea).";
                    break;
                }
                GarageData.Car c = data.get(v.getPersistentData().getInt(TAG));
                v.ejectPassengers();
                c.nbt = strip(v.saveWithoutId(new CompoundTag()));
                c.out = false;
                c.entity = null;
                v.discard();
                data.setDirty();
                msg = c.name + " a fost parcata.";
                break;
            }
            case "recover":
                if (car == null || !car.out) break;
                Entity live = findLoaded(p.server, car.entity);
                if (live != null) {
                    ok = false;
                    msg = "Masina e in oras, la X " + (int) live.getX() + "  Z " + (int) live.getZ() + ".";
                    break;
                }
                car.out = false;
                car.entity = null;
                data.setDirty();
                msg = car.name + " a fost adusa inapoi in garaj.";
                break;
            default:
                break;
        }
        sendList(p, pos, data, ok, msg);
    }

    private static Entity spawn(ServerLevel level, BlockPos garage, BlockState st, GarageData.Car car) {
        EntityType<?> type = EntityType.byString(car.type).orElse(null);
        if (type == null) return null;
        Direction face = st.hasProperty(HorizontalDirectionalBlock.FACING) ? st.getValue(HorizontalDirectionalBlock.FACING) : Direction.NORTH;
        for (int d = 3; d <= 8; d++) {
            BlockPos at = garage.relative(face, d);
            Entity e = type.create(level);
            if (e == null) return null;
            e.load(car.nbt.copy());
            e.moveTo(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, face.toYRot(), 0f);
            if (!level.noCollision(e, e.getBoundingBox())) continue;
            e.getPersistentData().putInt(TAG, car.id);
            car.out = true;
            car.entity = e.getUUID();
            level.addFreshEntity(e);
            return e;
        }
        return null;
    }

    private static Entity findToStore(ServerPlayer p, BlockPos garage, GarageData data) {
        Vec3 g = Vec3.atCenterOf(garage);
        Entity root = p.getRootVehicle();
        if (root != p && owned(root, p, data) && root.position().distanceTo(g) <= GARAGE_RANGE) return root;
        Entity best = null;
        double bd = GARAGE_RANGE * GARAGE_RANGE;
        for (Entity e : p.serverLevel().getEntities((Entity) null, new AABB(garage).inflate(GARAGE_RANGE), x -> owned(x, p, data))) {
            double d = e.distanceToSqr(g);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private static boolean owned(Entity e, ServerPlayer p, GarageData data) {
        if (!e.getPersistentData().contains(TAG)) return false;
        GarageData.Car c = data.get(e.getPersistentData().getInt(TAG));
        return c != null && c.owner.equals(p.getUUID()) && c.out && e.getUUID().equals(c.entity);
    }

    private static Entity findLoaded(net.minecraft.server.MinecraftServer server, UUID id) {
        if (id == null) return null;
        for (ServerLevel l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null && e.isAlive()) return e;
        }
        return null;
    }

    /** Scoate din NBT ce tine de pozitie / identitate, ca masina sa poata fi pusa oriunde. */
    private static CompoundTag strip(CompoundTag t) {
        for (String k : new String[] {"UUID", "Pos", "Motion", "Rotation", "Passengers", "FallDistance", "Fire", "Air", "OnGround", "PortalCooldown", "ForgeData"}) t.remove(k);
        return t;
    }

    private static void sendList(ServerPlayer p, BlockPos garage, GarageData data, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("x", garage.getX());
        o.addProperty("y", garage.getY());
        o.addProperty("z", garage.getZ());
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        JsonArray arr = new JsonArray();
        for (GarageData.Car c : data.of(p.getUUID())) {
            CompoundTag nbt = c.nbt;
            Entity live = c.out ? findLoaded(p.server, c.entity) : null;
            if (live != null) nbt = live.saveWithoutId(new CompoundTag());
            JsonObject j = new JsonObject();
            j.addProperty("id", c.id);
            j.addProperty("type", c.type);
            j.addProperty("name", c.name);
            j.addProperty("plate", c.plate);
            j.addProperty("out", c.out);
            j.addProperty("loaded", live != null);
            if (live != null) j.addProperty("dist", (int) live.position().distanceTo(Vec3.atCenterOf(garage)));
            j.addProperty("fuel", nbt.getFloat("CurrentFuel"));
            j.addProperty("fuelCap", nbt.getFloat("FuelCapacity"));
            j.addProperty("needsFuel", nbt.getBoolean("RequiresFuel"));
            j.addProperty("health", nbt.getFloat("Health"));
            j.addProperty("maxHealth", nbt.getFloat("MaxHealth"));
            j.addProperty("color", color(nbt));
            arr.add(j);
        }
        o.add("cars", arr);
        CarsNet.reply(p, o.toString());
    }

    private static int color(CompoundTag nbt) {
        int[] c = nbt.getIntArray("Color");
        if (c.length == 3) return ((c[0] & 255) << 16) | ((c[1] & 255) << 8) | (c[2] & 255);
        return 0xFFFFFF;
    }

    // ------------------------------------------------------------------ anti-dubluri + comanda

    public static final class Events {
        /** O masina veche (ex. dintr-un chunk descarcat) care a fost recuperata intre timp nu mai intra in lume. */
        @SubscribeEvent
        public void onJoin(EntityJoinLevelEvent e) {
            if (e.getLevel().isClientSide()) return;
            Entity en = e.getEntity();
            if (!en.getPersistentData().contains(TAG) || !(e.getLevel() instanceof ServerLevel sl)) return;
            GarageData.Car c = GarageData.get(sl.getServer()).get(en.getPersistentData().getInt(TAG));
            if (c == null || !c.out || !en.getUUID().equals(c.entity)) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onCommands(RegisterCommandsEvent e) {
            e.getDispatcher().register(Commands.literal("fccars").requires(s -> s.hasPermission(2))
                .then(Commands.literal("give")
                    .then(Commands.argument("player", EntityArgument.player())
                        .then(Commands.argument("type", StringArgumentType.string())
                            .then(Commands.argument("color", IntegerArgumentType.integer(0, 0xFFFFFF))
                                .then(Commands.argument("engine", StringArgumentType.string())
                                    .then(Commands.argument("wheels", StringArgumentType.string())
                                        .then(Commands.argument("plate", StringArgumentType.string())
                                            .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                                                ServerPlayer p = EntityArgument.getPlayer(c, "player");
                                                String type = StringArgumentType.getString(c, "type");
                                                GarageData.Car car = give(p, type, IntegerArgumentType.getInteger(c, "color"),
                                                    StringArgumentType.getString(c, "engine"), StringArgumentType.getString(c, "wheels"),
                                                    StringArgumentType.getString(c, "plate"), StringArgumentType.getString(c, "name"));
                                                if (car == null) {
                                                    c.getSource().sendFailure(Component.literal("Vehicul necunoscut: " + type));
                                                    return 0;
                                                }
                                                p.displayClientMessage(Component.literal(car.name + " (" + car.plate + ") te asteapta la garaj."), false);
                                                return 1;
                                            })))))))))
                .then(Commands.literal("remove")
                    .then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                        GarageData d = GarageData.get(c.getSource().getServer());
                        if (d.get(IntegerArgumentType.getInteger(c, "id")) == null) return 0;
                        d.remove(IntegerArgumentType.getInteger(c, "id"));
                        return 1;
                    }))));
        }
    }

    /** Creeaza o masina noua in garajul jucatorului: culoare, motor, roti, rezervor pe jumatate. */
    static GarageData.Car give(ServerPlayer p, String type, int rgb, String engine, String wheels, String plate, String name) {
        EntityType<?> t = EntityType.byString(type).orElse(null);
        if (t == null) return null;
        Entity e = t.create(p.serverLevel());
        if (e == null) return null;
        CompoundTag init = e.saveWithoutId(new CompoundTag());
        init.put("Color", new IntArrayTag(new int[] {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255}));
        if (!engine.isEmpty() && !engine.equals("-")) init.put("EngineStack", stack(engine));
        if (!wheels.isEmpty() && !wheels.equals("-")) init.put("WheelStack", stack(wheels));
        e.load(init);
        CompoundTag nbt = strip(e.saveWithoutId(new CompoundTag()));
        if (nbt.contains("FuelCapacity")) nbt.putFloat("CurrentFuel", nbt.getFloat("FuelCapacity") * 0.5f);
        e.discard();
        return GarageData.get(p.server).add(p.getUUID(), type, name, plate, nbt);
    }

    private static CompoundTag stack(String id) {
        CompoundTag s = new CompoundTag();
        s.putString("id", new ResourceLocation(id).toString());
        s.putByte("Count", (byte) 1);
        return s;
    }
}
