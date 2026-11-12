package ro.solarstudios.flowcity.cars;

import net.minecraft.util.text.StringTextComponent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.command.CommandSource;
import net.minecraft.command.Commands;
import net.minecraft.command.arguments.EntityArgument;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.IntArrayNBT;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.vector.Vector3d;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Garajele pe server, in stil FiveM: puncte de meniu (marker + E), zona de parcare, locuri de spawn, pound si
 * garaje de factiune. Lucreaza cu NBT-ul generic al entitatii, deci merge cu orice vehicul (MrCrayfish sau altele).
 *
 * <p>Admin (nivel 2): /garaj creeaza public|pound &lt;nume&gt;, /garaj creeaza factiune &lt;factiune&gt; &lt;nume&gt;,
 * /garaj parcare &lt;id&gt; [raza], /garaj loc &lt;id&gt;, /garaj locuri &lt;id&gt; sterge, /garaj muta &lt;id&gt;, /garaj sterge &lt;id&gt;, /garaj lista.
 * Folosite de plugin: /fccars give, givefaction, impound, unpound, remove.
 * Membrii unei factiuni au tag-ul de scoreboard "fc_&lt;factiune&gt;" (pluginul il pune automat politistilor din MDT).
 */
public final class CarsServer {
    private CarsServer() {}

    private static final String TAG = "fcVehId";
    private static final double MENU_RANGE = 4;

    // ------------------------------------------------------------------ actiuni din interfata

    static void handle(ServerPlayerEntity p, String act, int a, int b) {
        GarageData data = GarageData.get(p.server);
        switch (act) {
            case "open":
                openMenu(p, data, data.garage(a), true, "");
                break;
            case "spawn":
                spawnFrom(p, data, data.garage(a), data.get(b));
                break;
            case "store":
                storeAt(p, data, data.garage(a));
                break;
            case "inspect":
                inspect(p, data, a);
                break;
            default:
                break;
        }
    }

    private static boolean sameDim(ServerPlayerEntity p, GarageData.Garage g) {
        return p.level.dimension().location().toString().equals(g.dim);
    }

    private static boolean nearMenu(ServerPlayerEntity p, GarageData.Garage g) {
        return sameDim(p, g) && p.position().distanceTo(new Vector3d(g.mx, g.my, g.mz)) <= MENU_RANGE + 2;
    }

    static boolean member(ServerPlayerEntity p, String faction) {
        return p.hasPermissions(2) || p.getTags().contains("fc_" + faction);
    }

    private static boolean canAccess(ServerPlayerEntity p, GarageData.Garage g) {
        return !GarageData.FACTION.equals(g.type) || member(p, g.faction);
    }

    private static boolean canUse(ServerPlayerEntity p, GarageData.Car c) {
        if (c == null) return false;
        if (c.owner != null) return c.owner.equals(p.getUUID());
        return member(p, c.faction);
    }

    /** Masinile vazute intr-un garaj: personale (public / pound) sau ale factiunii. */
    private static List<GarageData.Car> carsFor(ServerPlayerEntity p, GarageData data, GarageData.Garage g) {
        if (GarageData.FACTION.equals(g.type)) return data.ofFaction(g.faction);
        return data.of(p.getUUID());
    }

    private static void openMenu(ServerPlayerEntity p, GarageData data, GarageData.Garage g, boolean ok, String msg) {
        if (g == null || !nearMenu(p, g)) return;
        if (!canAccess(p, g)) {
            p.displayClientMessage(new StringTextComponent("Garajul e doar pentru " + g.faction + "."), true);
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", g.id);
        o.addProperty("name", g.name);
        o.addProperty("type", g.type);
        o.addProperty("faction", g.faction);
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        JsonArray arr = new JsonArray();
        for (GarageData.Car c : carsFor(p, data, g)) {
            CompoundNBT nbt = c.nbt;
            Entity live = c.out ? findLoaded(p.server, c.entity) : null;
            if (live != null) nbt = live.saveWithoutId(new CompoundNBT());
            JsonObject j = new JsonObject();
            j.addProperty("id", c.id);
            j.addProperty("type", c.type);
            j.addProperty("name", c.name);
            j.addProperty("plate", c.plate);
            j.addProperty("out", c.out);
            j.addProperty("loaded", live != null);
            j.addProperty("pound", c.pound);
            j.addProperty("reason", c.poundReason);
            if (live != null) {
                j.addProperty("lx", (int) live.getX());
                j.addProperty("lz", (int) live.getZ());
            }
            j.addProperty("fuel", nbt.getFloat("CurrentFuel"));
            j.addProperty("fuelCap", nbt.getFloat("FuelCapacity"));
            j.addProperty("needsFuel", nbt.getBoolean("RequiresFuel"));
            j.addProperty("health", nbt.getFloat("Health"));
            j.addProperty("maxHealth", nbt.getFloat("MaxHealth"));
            j.addProperty("color", color(nbt));
            arr.add(j);
        }
        o.add("cars", arr);
        CarsNet.reply(p, "garage", o.toString());
    }

    private static void spawnFrom(ServerPlayerEntity p, GarageData data, GarageData.Garage g, GarageData.Car c) {
        if (g == null || !nearMenu(p, g) || !canAccess(p, g) || !canUse(p, c)) return;
        String msg;
        boolean ok = false;
        if (GarageData.POUND.equals(g.type)) {
            msg = "La pound platesti taxa ca sa-ti scoti masina.";
        } else if (c.pound) {
            msg = "Masina e la pound. Mergi la pound sa o scoti.";
        } else if (c.out) {
            msg = "Masina e deja in oras.";
        } else if (GarageData.FACTION.equals(g.type) != (c.owner == null)) {
            msg = "Masina nu poate fi scoasa din acest garaj.";
        } else {
            Entity e = spawn(p.getLevel(), g, c);
            ok = e != null;
            msg = ok ? c.name + " (" + c.plate + ") te asteapta in parcare." : "Toate locurile de parcare sunt ocupate.";
            if (ok) data.setDirty();
        }
        openMenu(p, data, g, ok, msg);
    }

    private static void storeAt(ServerPlayerEntity p, GarageData data, GarageData.Garage g) {
        if (g == null || !sameDim(p, g) || GarageData.POUND.equals(g.type)) return;
        Entity v = p.getRootVehicle();
        if (v == p) {
            p.displayClientMessage(new StringTextComponent("Trebuie sa fii in vehicul."), true);
            return;
        }
        Vector3d zone = g.hasStore ? new Vector3d(g.sx, g.sy, g.sz) : new Vector3d(g.mx, g.my, g.mz);
        double r = g.hasStore ? g.radius + 1.5 : 8;
        if (v.position().distanceTo(zone) > r) return;
        GarageData.Car c = v.getPersistentData().contains(TAG) ? data.get(v.getPersistentData().getInt(TAG)) : null;
        if (c == null || !c.out || !v.getUUID().equals(c.entity)) {
            p.displayClientMessage(new StringTextComponent("Vehiculul nu e al tau."), true);
            return;
        }
        if (!canUse(p, c)) {
            p.displayClientMessage(new StringTextComponent("Vehiculul nu e al tau."), true);
            return;
        }
        if (GarageData.FACTION.equals(g.type) != (c.owner == null)) {
            p.displayClientMessage(new StringTextComponent(c.owner == null ? "Masinile factiunii se parcheaza la garajul factiunii." : "Aici se parcheaza doar masinile factiunii."), true);
            return;
        }
        storeEntity(v, c);
        data.setDirty();
        p.displayClientMessage(new StringTextComponent(c.name + " a fost parcata in " + g.name + "."), true);
    }

    private static void storeEntity(Entity v, GarageData.Car c) {
        v.ejectPassengers();
        c.nbt = strip(v.saveWithoutId(new CompoundNBT()));
        c.out = false;
        c.entity = null;
        v.discard();
    }

    /** Pune vehiculul pe primul loc de parcare liber (sau langa marker, daca nu sunt locuri setate). */
    private static Entity spawn(ServerWorld level, GarageData.Garage g, GarageData.Car c) {
        EntityType<?> type = EntityType.byString(c.type).orElse(null);
        if (type == null) return null;
        List<double[]> spots = new ArrayList<>(g.slots);
        if (spots.isEmpty()) {
            for (int d = 3; d <= 7; d += 2) spots.add(new double[] {g.mx + d, g.my, g.mz, 0});
        }
        for (double[] s : spots) {
            Entity e = type.create(level);
            if (e == null) return null;
            e.load(c.nbt.copy());
            e.moveTo(s[0], s[1], s[2], (float) s[3], 0f);
            if (!level.noCollision(e, e.getBoundingBox())) continue;
            e.getPersistentData().putInt(TAG, c.id);
            c.out = true;
            c.entity = e.getUUID();
            level.addFreshEntity(e);
            return e;
        }
        return null;
    }

    /** Politia se uita la un vehicul: numar, model, proprietar, stare. */
    private static void inspect(ServerPlayerEntity p, GarageData data, int entityId) {
        Entity e = p.level.getEntity(entityId);
        if (e == null || e.distanceTo(p) > 8) return;
        JsonObject o = new JsonObject();
        o.addProperty("entity", e.getUUID().toString());
        o.addProperty("model", e.getDisplayName().getString());
        GarageData.Car c = e.getPersistentData().contains(TAG) ? data.get(e.getPersistentData().getInt(TAG)) : null;
        if (c != null) {
            o.addProperty("plate", c.plate);
            o.addProperty("name", c.name);
            String owner = c.owner == null ? "Factiune: " + c.faction
                : nameOf(p.server, c.owner);
            o.addProperty("owner", owner);
        } else {
            o.addProperty("plate", "");
            o.addProperty("owner", "fara acte (nu e inregistrat)");
        }
        CarsNet.reply(p, "inspect", o.toString());
    }

    // ------------------------------------------------------------------ utilitare

    private static String nameOf(MinecraftServer server, UUID id) {
        com.mojang.authlib.GameProfile gp = server.getProfileCache().get(id);
        return gp == null ? "necunoscut" : gp.getName();
    }

    private static Entity findLoaded(MinecraftServer server, UUID id) {
        if (id == null) return null;
        for (ServerWorld l : server.getAllLevels()) {
            Entity e = l.getEntity(id);
            if (e != null && e.isAlive()) return e;
        }
        return null;
    }

    /** Scoate din NBT ce tine de pozitie / identitate, ca masina sa poata fi pusa oriunde. */
    private static CompoundNBT strip(CompoundNBT t) {
        for (String k : new String[] {"UUID", "Pos", "Motion", "Rotation", "Passengers", "FallDistance", "Fire", "Air", "OnGround", "PortalCooldown", "ForgeData"}) t.remove(k);
        return t;
    }

    private static int color(CompoundNBT nbt) {
        int[] c = nbt.getIntArray("Color");
        if (c.length == 3) return ((c[0] & 255) << 16) | ((c[1] & 255) << 8) | (c[2] & 255);
        return 0xFFFFFF;
    }

    /** Lista garajelor (pentru markere / harta) + factiunile jucatorului. */
    static void syncTo(ServerPlayerEntity p) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (GarageData.Garage g : GarageData.get(p.server).garages()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", g.id);
            j.addProperty("name", g.name);
            j.addProperty("type", g.type);
            j.addProperty("faction", g.faction);
            j.addProperty("dim", g.dim);
            j.addProperty("mx", g.mx);
            j.addProperty("my", g.my);
            j.addProperty("mz", g.mz);
            j.addProperty("store", g.hasStore);
            j.addProperty("sx", g.sx);
            j.addProperty("sy", g.sy);
            j.addProperty("sz", g.sz);
            j.addProperty("r", g.radius);
            j.addProperty("access", canAccess(p, g));
            arr.add(j);
        }
        o.add("garages", arr);
        JsonArray fac = new JsonArray();
        for (String t : p.getTags()) if (t.startsWith("fc_")) fac.add(t.substring(3));
        if (p.hasPermissions(2)) fac.add("admin");
        o.add("factions", fac);
        CarsNet.reply(p, "garages", o.toString());
    }

    static void syncAll(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) syncTo(p);
    }

    // ------------------------------------------------------------------ evenimente + comenzi

    public static final class Events {
        private int ticks;

        /** O masina veche (ex. dintr-un chunk descarcat) care a fost recuperata intre timp nu mai intra in lume. */
        @SubscribeEvent
        public void onJoin(EntityJoinWorldEvent e) {
            if (e.getWorld().isClientSide()) return;
            Entity en = e.getEntity();
            if (!en.getPersistentData().contains(TAG) || !(e.getWorld() instanceof ServerWorld)) return;
            ServerWorld sl = (ServerWorld) e.getWorld();
            GarageData.Car c = GarageData.get(sl.getServer()).get(en.getPersistentData().getInt(TAG));
            if (c == null || !c.out || !en.getUUID().equals(c.entity)) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
            if (e.getPlayer() instanceof ServerPlayerEntity) syncTo((ServerPlayerEntity) e.getPlayer());
        }

        @SubscribeEvent
        public void onDim(PlayerEvent.PlayerChangedDimensionEvent e) {
            if (e.getPlayer() instanceof ServerPlayerEntity) syncTo((ServerPlayerEntity) e.getPlayer());
        }

        /** Re-trimitem lista la 30 s (factiunile se pot schimba: politist nou in MDT etc.). */
        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END || ++ticks % 600 != 0) return;
            syncAll(e.getServer());
        }

        @SubscribeEvent
        public void onCommands(RegisterCommandsEvent e) {
            e.getDispatcher().register(garajCommand());
            e.getDispatcher().register(carsCommand());
        }
    }

    private static int fail(CommandContext<CommandSource> c, String msg) {
        c.getSource().sendFailure(new StringTextComponent(msg));
        return 0;
    }

    private static int ok(CommandContext<CommandSource> c, String msg) {
        c.getSource().sendSuccess(new StringTextComponent(msg), false);
        syncAll(c.getSource().getServer());
        return 1;
    }

    private static GarageData.Garage create(CommandContext<CommandSource> c, String type, String faction, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrException();
        GarageData data = GarageData.get(c.getSource().getServer());
        GarageData.Garage g = data.addGarage();
        g.name = name;
        g.type = type;
        g.faction = faction;
        g.dim = p.level.dimension().location().toString();
        g.mx = p.getX();
        g.my = p.getY();
        g.mz = p.getZ();
        data.setDirty();
        return g;
    }

    private static LiteralArgumentBuilder<CommandSource> garajCommand() {
        return Commands.literal("garaj").requires(s -> s.hasPermission(2))
            .then(Commands.literal("creeaza")
                .then(Commands.literal("public").then(Commands.argument("nume", StringArgumentType.greedyString()).executes(c -> {
                    GarageData.Garage g = create(c, GarageData.PUBLIC, "", StringArgumentType.getString(c, "nume"));
                    return ok(c, "Garaj #" + g.id + " creat. Seteaza zona de parcare (/garaj parcare " + g.id + ") si locurile (/garaj loc " + g.id + ").");
                })))
                .then(Commands.literal("pound").then(Commands.argument("nume", StringArgumentType.greedyString()).executes(c -> {
                    GarageData.Garage g = create(c, GarageData.POUND, "", StringArgumentType.getString(c, "nume"));
                    return ok(c, "Pound #" + g.id + " creat. Adauga locurile unde apar masinile (/garaj loc " + g.id + ").");
                })))
                .then(Commands.literal("factiune").then(Commands.argument("factiune", StringArgumentType.word())
                    .then(Commands.argument("nume", StringArgumentType.greedyString()).executes(c -> {
                        String f = StringArgumentType.getString(c, "factiune").toLowerCase();
                        GarageData.Garage g = create(c, GarageData.FACTION, f, StringArgumentType.getString(c, "nume"));
                        return ok(c, "Garaj de factiune #" + g.id + " (" + f + ") creat. Membrii au tag-ul fc_" + f + ".");
                    })))))
            .then(Commands.literal("parcare").then(Commands.argument("id", IntegerArgumentType.integer(1))
                .executes(c -> setStore(c, 4f))
                .then(Commands.argument("raza", FloatArgumentType.floatArg(2, 16)).executes(c -> setStore(c, FloatArgumentType.getFloat(c, "raza"))))))
            .then(Commands.literal("loc").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrException();
                GarageData data = GarageData.get(c.getSource().getServer());
                GarageData.Garage g = data.garage(IntegerArgumentType.getInteger(c, "id"));
                if (g == null) return fail(c, "Garajul nu exista.");
                g.slots.add(new double[] {p.getX(), p.getY(), p.getZ(), p.getYRot()});
                data.setDirty();
                return ok(c, "Loc de parcare #" + g.slots.size() + " adaugat la " + g.name + " (masina va fi intoarsa cum privesti acum).");
            })))
            .then(Commands.literal("locuri").then(Commands.argument("id", IntegerArgumentType.integer(1)).then(Commands.literal("sterge").executes(c -> {
                GarageData data = GarageData.get(c.getSource().getServer());
                GarageData.Garage g = data.garage(IntegerArgumentType.getInteger(c, "id"));
                if (g == null) return fail(c, "Garajul nu exista.");
                g.slots.clear();
                data.setDirty();
                return ok(c, "Locurile de parcare au fost sterse.");
            }))))
            .then(Commands.literal("muta").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrException();
                GarageData data = GarageData.get(c.getSource().getServer());
                GarageData.Garage g = data.garage(IntegerArgumentType.getInteger(c, "id"));
                if (g == null) return fail(c, "Garajul nu exista.");
                g.dim = p.level.dimension().location().toString();
                g.mx = p.getX();
                g.my = p.getY();
                g.mz = p.getZ();
                data.setDirty();
                return ok(c, "Markerul garajului a fost mutat aici.");
            })))
            .then(Commands.literal("sterge").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                GarageData data = GarageData.get(c.getSource().getServer());
                if (data.garage(IntegerArgumentType.getInteger(c, "id")) == null) return fail(c, "Garajul nu exista.");
                data.removeGarage(IntegerArgumentType.getInteger(c, "id"));
                return ok(c, "Garaj sters (masinile raman ale jucatorilor).");
            })))
            .then(Commands.literal("lista").executes(c -> {
                List<GarageData.Garage> gs = GarageData.get(c.getSource().getServer()).garages();
                if (gs.isEmpty()) return fail(c, "Nu exista garaje. Creeaza unul: /garaj creeaza public <nume>");
                for (GarageData.Garage g : gs) {
                    String line = "#" + g.id + " " + g.name + " [" + g.type + (g.faction.isEmpty() ? "" : " " + g.faction) + "] "
                        + (int) g.mx + " " + (int) g.my + " " + (int) g.mz + " | parcare: " + (g.hasStore ? "da" : "nu") + " | locuri: " + g.slots.size();
                    c.getSource().sendSuccess(new StringTextComponent(line), false);
                }
                return 1;
            }));
    }

    private static int setStore(CommandContext<CommandSource> c, float r) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = c.getSource().getPlayerOrException();
        GarageData data = GarageData.get(c.getSource().getServer());
        GarageData.Garage g = data.garage(IntegerArgumentType.getInteger(c, "id"));
        if (g == null) return fail(c, "Garajul nu exista.");
        g.hasStore = true;
        g.sx = p.getX();
        g.sy = p.getY();
        g.sz = p.getZ();
        g.radius = r;
        data.setDirty();
        return ok(c, "Zona de parcare a garajului " + g.name + " e aici (raza " + r + ").");
    }

    private static LiteralArgumentBuilder<CommandSource> carsCommand() {
        return Commands.literal("fccars").requires(s -> s.hasPermission(2))
            .then(Commands.literal("give").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("type", StringArgumentType.string())
                    .then(Commands.argument("color", IntegerArgumentType.integer(0, 0xFFFFFF))
                        .then(Commands.argument("engine", StringArgumentType.string())
                            .then(Commands.argument("wheels", StringArgumentType.string())
                                .then(Commands.argument("plate", StringArgumentType.string())
                                    .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                                        ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
                                        GarageData.Car car = give(c.getSource().getServer(), p.getUUID(), "", StringArgumentType.getString(c, "type"),
                                            IntegerArgumentType.getInteger(c, "color"), StringArgumentType.getString(c, "engine"),
                                            StringArgumentType.getString(c, "wheels"), StringArgumentType.getString(c, "plate"), StringArgumentType.getString(c, "name"));
                                        if (car == null) return fail(c, "Vehicul necunoscut.");
                                        p.displayClientMessage(new StringTextComponent(car.name + " (" + car.plate + ") te asteapta in orice garaj."), false);
                                        return 1;
                                    })))))))))
            .then(Commands.literal("givefaction").then(Commands.argument("faction", StringArgumentType.word())
                .then(Commands.argument("type", StringArgumentType.string())
                    .then(Commands.argument("color", IntegerArgumentType.integer(0, 0xFFFFFF))
                        .then(Commands.argument("engine", StringArgumentType.string())
                            .then(Commands.argument("wheels", StringArgumentType.string())
                                .then(Commands.argument("plate", StringArgumentType.string())
                                    .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                                        GarageData.Car car = give(c.getSource().getServer(), null, StringArgumentType.getString(c, "faction").toLowerCase(),
                                            StringArgumentType.getString(c, "type"), IntegerArgumentType.getInteger(c, "color"),
                                            StringArgumentType.getString(c, "engine"), StringArgumentType.getString(c, "wheels"),
                                            StringArgumentType.getString(c, "plate"), StringArgumentType.getString(c, "name"));
                                        if (car == null) return fail(c, "Vehicul necunoscut.");
                                        return ok(c, "Vehicul de factiune adaugat: #" + car.id + " " + car.name);
                                    })))))))))
            // ridicare la pound: politistul trebuie sa fie la max 10 blocuri de vehicul
            .then(Commands.literal("impound").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("entity", StringArgumentType.string())
                    .then(Commands.argument("reason", StringArgumentType.greedyString()).executes(c -> {
                        ServerPlayerEntity officer = EntityArgument.getPlayer(c, "player");
                        UUID id;
                        try {
                            id = UUID.fromString(StringArgumentType.getString(c, "entity"));
                        } catch (IllegalArgumentException ex) {
                            return fail(c, "UUID invalid.");
                        }
                        Entity v = officer.getLevel().getEntity(id);
                        if (v == null || v.distanceTo(officer) > 10 || v instanceof net.minecraft.entity.player.PlayerEntity) return fail(c, "Vehiculul nu e aproape.");
                        ResourceLocation key = ForgeRegistries.ENTITIES.getKey(v.getType());
                        GarageData data = GarageData.get(c.getSource().getServer());
                        GarageData.Car car = v.getPersistentData().contains(TAG) ? data.get(v.getPersistentData().getInt(TAG)) : null;
                        if (car == null && (key == null || !CarsCompat.MOD.equals(key.getNamespace()))) return fail(c, "Nu e un vehicul.");
                        if (car != null) {
                            storeEntity(v, car);
                            car.pound = true;
                            car.poundReason = StringArgumentType.getString(c, "reason");
                            data.setDirty();
                            ServerPlayerEntity owner = car.owner == null ? null : c.getSource().getServer().getPlayerList().getPlayer(car.owner);
                            if (owner != null) owner.displayClientMessage(new StringTextComponent("Masina ta " + car.name + " (" + car.plate + ") a fost ridicata la pound: " + car.poundReason), false);
                        } else {
                            v.ejectPassengers();
                            v.discard(); // vehicul fara acte (nu e din garaj): se ridica definitiv
                        }
                        officer.displayClientMessage(new StringTextComponent("Vehicul ridicat la pound."), true);
                        return 1;
                    })))))
            // scoatere de la pound dupa plata (pluginul ia taxa prin Vault)
            .then(Commands.literal("unpound").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("car", IntegerArgumentType.integer(1)).then(Commands.argument("garage", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
                    GarageData data = GarageData.get(c.getSource().getServer());
                    GarageData.Car car = data.get(IntegerArgumentType.getInteger(c, "car"));
                    GarageData.Garage g = data.garage(IntegerArgumentType.getInteger(c, "garage"));
                    if (car == null || g == null || !canUse(p, car) || !GarageData.POUND.equals(g.type)) return fail(c, "Masina nu poate fi scoasa.");
                    boolean lost = car.out && findLoaded(c.getSource().getServer(), car.entity) == null;
                    if (!car.pound && !lost) return fail(c, "Masina nu e la pound.");
                    car.pound = false;
                    car.poundReason = "";
                    car.out = false;
                    car.entity = null;
                    Entity e = spawn(p.getLevel(), g, car);
                    data.setDirty();
                    p.displayClientMessage(new StringTextComponent(e != null ? car.name + " te asteapta in parcarea pound-ului."
                        : car.name + " a fost eliberata; locurile sunt ocupate, o poti scoate din orice garaj."), true);
                    openMenu(p, data, g, true, "");
                    return 1;
                })))))
            .then(Commands.literal("remove").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                GarageData d = GarageData.get(c.getSource().getServer());
                if (d.get(IntegerArgumentType.getInteger(c, "id")) == null) return fail(c, "Nu exista.");
                d.remove(IntegerArgumentType.getInteger(c, "id"));
                return ok(c, "Sters.");
            })));
    }

    /** Creeaza o masina noua (personala sau de factiune): culoare, motor, roti, rezervor pe jumatate. */
    static GarageData.Car give(MinecraftServer server, UUID owner, String faction, String type, int rgb, String engine, String wheels, String plate, String name) {
        EntityType<?> t = EntityType.byString(type).orElse(null);
        if (t == null) return null;
        Entity e = t.create(server.overworld());
        if (e == null) return null;
        CompoundNBT init = e.saveWithoutId(new CompoundNBT());
        init.put("Color", new IntArrayNBT(new int[] {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255}));
        if (!engine.isEmpty() && !engine.equals("-")) init.put("EngineStack", stack(engine));
        if (!wheels.isEmpty() && !wheels.equals("-")) init.put("WheelStack", stack(wheels));
        e.load(init);
        CompoundNBT nbt = strip(e.saveWithoutId(new CompoundNBT()));
        if (nbt.contains("FuelCapacity")) nbt.putFloat("CurrentFuel", nbt.getFloat("FuelCapacity") * 0.5f);
        e.discard();
        return GarageData.get(server).add(owner, faction, type, name, plate, nbt);
    }

    private static CompoundNBT stack(String id) {
        CompoundNBT s = new CompoundNBT();
        s.putString("id", new ResourceLocation(id).toString());
        s.putByte("Count", (byte) 1);
        return s;
    }
}
