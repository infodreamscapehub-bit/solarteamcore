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
 * Benzinarii: /benzinarie creeaza &lt;nume&gt;, raza, muta, sterge, lista. Chei: /fccars cheie &lt;jucator&gt; &lt;masina&gt;.
 * Folosite de plugin: /fccars give, givefaction, impound, unpound, fuel, remove.
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
            case "plate":
                plate(p, data, a);
                break;
            case "lock":
                toggleLock(p, data);
                break;
            case "tunelist":
                tuneList(p, data);
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
            j.addProperty("engine", VehicleParts.engine(p.getLevel(), c.type, nbt));
            j.addProperty("wheels", VehicleParts.wheels(nbt));
            arr.add(j);
        }
        o.add("cars", arr);
        CarsNet.reply(p, "garage", o.toString());
    }

    private static String stackId(CompoundNBT nbt, String key) {
        String id = nbt.contains(key, 10) ? nbt.getCompound(key).getString("id") : "";
        return id.isEmpty() ? "-" : id;
    }

    /** Masinile personale parcate in garaj (nu in oras, nu la pound): cele care pot fi tunate. */
    private static void tuneList(ServerPlayerEntity p, GarageData data) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (GarageData.Car c : data.of(p.getUUID())) {
            if (c.out || c.pound) continue;
            JsonObject j = new JsonObject();
            j.addProperty("id", c.id);
            j.addProperty("type", c.type);
            j.addProperty("name", c.name);
            j.addProperty("plate", c.plate);
            j.addProperty("color", color(c.nbt));
            j.addProperty("engine", VehicleParts.engine(p.getLevel(), c.type, c.nbt));
            j.addProperty("wheels", VehicleParts.wheels(c.nbt));
            arr.add(j);
        }
        o.add("cars", arr);
        CarsNet.reply(p, "tune_cars", o.toString());
    }

    private static final org.apache.logging.log4j.Logger LOG = org.apache.logging.log4j.LogManager.getLogger("FlowCity");

    private static boolean itemExists(String id) {
        try {
            return ForgeRegistries.ITEMS.containsKey(new ResourceLocation(id));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /** Daca itemul de motor nu exista in modul de vehicule, cauta cel mai apropiat motor al aceleiasi familii (altfel masina ar iesi fara motor). */
    private static String resolveEngine(String id) {
        if (id == null || id.isEmpty() || id.equals("-") || itemExists(id)) return id;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^vehicle:(?:[a-z]+_)?(small|large|electric)_engine$").matcher(id);
        if (m.matches()) {
            for (String t : new String[] {"iron_", "gold_", "diamond_", "stone_", "wood_", "netherite_", ""}) {
                String cand = "vehicle:" + t + m.group(1) + "_engine";
                if (itemExists(cand)) {
                    LOG.warn("Motorul {} nu exista in modul de vehicule; folosesc {}", id, cand);
                    return cand;
                }
            }
        }
        LOG.error("Motorul {} nu exista in modul de vehicule si nu am gasit inlocuitor: masina va iesi fara motor", id);
        return id;
    }

    private static final java.util.regex.Pattern TIER = java.util.regex.Pattern.compile("^vehicle:(wood_|stone_|iron_|gold_|diamond_)?(small|large|electric)_engine$");
    private static final java.util.Set<String> WHEELS = new java.util.HashSet<>(java.util.Arrays.asList(
        "vehicle:plastic_wheel", "vehicle:standard_wheel", "vehicle:sports_wheel", "vehicle:racing_wheel",
        "vehicle:off_road_wheel", "vehicle:all_terrain_wheel", "vehicle:snow_wheel"));

    private static String family(String engineId) {
        return engineId.replaceFirst("^vehicle:(wood_|stone_|iron_|gold_|diamond_)", "vehicle:");
    }

    /**
     * Tuning (dupa ce pluginul a luat banii): culoare, tier de motor si roti. fromEngine/fromWheels trebuie sa fie
     * starea reala a masinii (altfel cineva ar putea minti despre ce are deja si ar plati mai putin). mode "garage" cere sa fii
     * langa un garaj; "npc" e deschis de plugin (mecanicul).
     */
    private static int tune(CommandContext<CommandSource> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
        GarageData data = GarageData.get(c.getSource().getServer());
        GarageData.Car car = data.get(IntegerArgumentType.getInteger(c, "car"));
        if (car == null || car.owner == null || !car.owner.equals(p.getUUID())) return fail(c, "Masina nu e a ta.");
        if (car.out || car.pound) return fail(c, "Parcheaza masina in garaj ca sa o tunezi.");
        if ("garage".equals(StringArgumentType.getString(c, "mode"))) {
            boolean near = false;
            for (GarageData.Garage g : data.garages()) near |= nearMenu(p, g);
            if (!near) return fail(c, "Tuning-ul se face la garaj sau la mecanic.");
        }
        String curEngine = VehicleParts.engine(p.getLevel(), car.type, car.nbt);
        String curWheels = VehicleParts.wheels(car.nbt);
        if (!curEngine.equals(StringArgumentType.getString(c, "fromEngine")) || !curWheels.equals(StringArgumentType.getString(c, "fromWheels"))
            || color(car.nbt) != IntegerArgumentType.getInteger(c, "fromColor")) {
            return fail(c, "Masina s-a schimbat. Redeschide meniul de tuning.");
        }
        String engine = StringArgumentType.getString(c, "engine");
        String wheels = StringArgumentType.getString(c, "wheels");
        if (!engine.equals(curEngine)) {
            if (!itemExists(engine)) return fail(c, "Motorul " + engine + " nu exista in modul de vehicule.");
            if (curEngine.equals("-") || !TIER.matcher(engine).matches() || !family(engine).equals(family(curEngine))) return fail(c, "Motor invalid pentru aceasta masina.");
        }
        if (!wheels.equals(curWheels)) {
            if (curWheels.equals("-") || !WHEELS.contains(wheels)) return fail(c, "Roti invalide pentru aceasta masina.");
        }
        int rgb = IntegerArgumentType.getInteger(c, "color");
        car.nbt.put("Color", new IntArrayNBT(new int[] {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255}));
        VehicleParts.apply(car.nbt, engine.equals(curEngine) ? null : engine, wheels.equals(curWheels) ? null : wheels);
        data.setDirty();
        p.displayClientMessage(new StringTextComponent(car.name + " (" + car.plate + ") a fost tunata."), false);
        return 1;
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
            if (ok && c.owner != null && !hasKeyItem(p, c)) {
                giveKey(p, c, true);
                msg += " Ai primit o cheie noua (cele vechi nu mai merg).";
            }
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
        v.remove();
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

    // ------------------------------------------------------------------ chei + incuiere

    static GarageData.Car carOf(MinecraftServer server, Entity v) {
        if (v == null || !v.getPersistentData().contains(TAG)) return null;
        GarageData.Car c = GarageData.get(server).get(v.getPersistentData().getInt(TAG));
        return c != null && c.out && v.getUUID().equals(c.entity) ? c : null;
    }

    static boolean hasKeyItem(ServerPlayerEntity p, GarageData.Car c) {
        for (net.minecraft.item.ItemStack st : p.inventory.items) if (CarKeyItem.opens(st, c)) return true;
        for (net.minecraft.item.ItemStack st : p.inventory.offhand) if (CarKeyItem.opens(st, c)) return true;
        return false;
    }

    /** Cine poate conduce / incuia: cheia (personala) sau factiunea (masini de factiune). Adminii pot oricand. */
    static boolean hasKey(ServerPlayerEntity p, GarageData.Car c) {
        if (c == null) return true;
        if (p.hasPermissions(2)) return true;
        if (c.owner == null) return member(p, c.faction);
        return hasKeyItem(p, c);
    }

    static void giveKey(ServerPlayerEntity p, GarageData.Car c, boolean newGeneration) {
        if (newGeneration) c.keyGen++;
        net.minecraft.item.ItemStack st = CarKeyItem.make(c);
        if (!p.inventory.add(st)) p.drop(st, false);
        GarageData.get(p.server).setDirty();
    }

    private static final java.util.List<Object[]> BEEPS = new java.util.ArrayList<>();

    private static void beep(ServerWorld w, Entity v, float pitch) {
        w.playSound(null, v.getX(), v.getY(), v.getZ(), net.minecraft.util.SoundEvents.NOTE_BLOCK_BIT, net.minecraft.util.SoundCategory.PLAYERS, 0.9f, pitch);
    }

    /** Tasta U: incuie / descuie cea mai apropiata masina pentru care ai cheie (pana la 12 blocuri). */
    private static void toggleLock(ServerPlayerEntity p, GarageData data) {
        Entity best = null;
        GarageData.Car bestCar = null;
        double bd = 12 * 12;
        for (Entity v : p.level.getEntities((Entity) null, p.getBoundingBox().inflate(12), x -> x.getPersistentData().contains(TAG))) {
            GarageData.Car c = carOf(p.server, v);
            if (c == null || !hasKey(p, c)) continue;
            double d = v.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = v;
                bestCar = c;
            }
        }
        if (best == null) {
            p.displayClientMessage(new StringTextComponent("Nicio masina de-a ta in apropiere (sau nu ai cheia)."), true);
            return;
        }
        bestCar.locked = !bestCar.locked;
        data.setDirty();
        ServerWorld w = p.getLevel();
        beep(w, best, bestCar.locked ? 1.4f : 1.9f);
        if (bestCar.locked) BEEPS.add(new Object[] {w, best, 4});
        p.displayClientMessage(new StringTextComponent((bestCar.locked ? "Incuiata: " : "Descuiata: ") + bestCar.name + " (" + bestCar.plate + ")"), true);
        JsonObject o = new JsonObject();
        o.addProperty("id", best.getId());
        o.addProperty("locked", bestCar.locked);
        for (ServerPlayerEntity other : w.players()) {
            if (other.distanceToSqr(best) < 64 * 64) CarsNet.reply(other, "flash", o.toString());
        }
    }

    // ------------------------------------------------------------------ benzinarii

    private static GarageData.Station stationNear(ServerPlayerEntity p, GarageData data, int id) {
        GarageData.Station st = data.station(id);
        if (st == null || !p.level.dimension().location().toString().equals(st.dim)) return null;
        return p.position().distanceTo(new Vector3d(st.x, st.y, st.z)) <= st.radius + 4 ? st : null;
    }

    /** Pune benzina in vehicul (MrCrayfish: addFuel sau setCurrentFuel, prin reflexie). */
    private static boolean addFuel(Entity v, float units) {
        try {
            java.lang.reflect.Method m = findMethod(v.getClass(), "addFuel", float.class);
            if (m != null) {
                m.invoke(v, units);
                return true;
            }
            java.lang.reflect.Method cur = findMethod(v.getClass(), "getCurrentFuel");
            java.lang.reflect.Method cap = findMethod(v.getClass(), "getFuelCapacity");
            java.lang.reflect.Method set = findMethod(v.getClass(), "setCurrentFuel", float.class);
            if (cur == null || cap == null || set == null) return false;
            float now = ((Number) cur.invoke(v)).floatValue();
            float max = ((Number) cap.invoke(v)).floatValue();
            set.invoke(v, Math.min(max, now + units));
            return true;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return false;
        }
    }

    private static java.lang.reflect.Method findMethod(Class<?> c, String name, Class<?>... args) {
        while (c != null) {
            try {
                java.lang.reflect.Method m = c.getDeclaredMethod(name, args);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                c = c.getSuperclass();
            }
        }
        return null;
    }

    /** Bordul cere numarul masinii in care sta jucatorul. */
    private static void plate(ServerPlayerEntity p, GarageData data, int entityId) {
        Entity e = p.level.getEntity(entityId);
        if (e == null || e.distanceTo(p) > 8) return;
        GarageData.Car c = e.getPersistentData().contains(TAG) ? data.get(e.getPersistentData().getInt(TAG)) : null;
        JsonObject o = new JsonObject();
        o.addProperty("id", entityId);
        o.addProperty("plate", c == null || c.plate == null ? "" : c.plate);
        CarsNet.reply(p, "plate", o.toString());
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
            o.addProperty("itp", c.itpUntil);
            o.addProperty("ins", c.insUntil);
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
        syncTo(p, true);
    }

    static void syncTo(ServerPlayerEntity p, boolean force) {
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
        JsonArray sts = new JsonArray();
        for (GarageData.Station st : GarageData.get(p.server).stations()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", st.id);
            j.addProperty("name", st.name);
            j.addProperty("dim", st.dim);
            j.addProperty("x", st.x);
            j.addProperty("y", st.y);
            j.addProperty("z", st.z);
            j.addProperty("r", st.radius);
            sts.add(j);
        }
        o.add("stations", sts);
        String json = o.toString();
        // nu retrimitem aceeasi lista (periodic, la 30 s) daca nu s-a schimbat nimic pentru acest jucator
        if (!force && json.equals(LAST_SYNC.get(p.getUUID()))) return;
        LAST_SYNC.put(p.getUUID(), json);
        CarsNet.reply(p, "garages", json);
    }

    private static final java.util.Map<UUID, String> LAST_SYNC = new java.util.HashMap<>();

    static void syncAll(MinecraftServer server) {
        for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) syncTo(p, true);
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

        /** Masina incuiata: nu se poate urca nimeni (nici prin click, nici altfel). */
        @SubscribeEvent
        public void onInteract(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract e) {
            if (lockedFor(e.getPlayer(), e.getTarget())) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onInteractAt(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific e) {
            if (lockedFor(e.getPlayer(), e.getTarget())) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onMount(net.minecraftforge.event.entity.EntityMountEvent e) {
            if (!e.isMounting() || e.getWorldObj().isClientSide() || !(e.getEntityMounting() instanceof ServerPlayerEntity)) return;
            if (lockedFor((ServerPlayerEntity) e.getEntityMounting(), e.getEntityBeingMounted())) e.setCanceled(true);
        }

        private boolean lockedFor(net.minecraft.entity.player.PlayerEntity pl, Entity v) {
            if (!(pl instanceof ServerPlayerEntity) || v == null || pl.getVehicle() == v) return false;
            GarageData.Car c = carOf(((ServerPlayerEntity) pl).server, v);
            if (c == null || !c.locked) return false;
            pl.displayClientMessage(new StringTextComponent("Masina e incuiata."), true);
            return true;
        }

        /** Re-trimitem lista la 30 s (factiunile se pot schimba: politist nou in MDT etc.). */
        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            MinecraftServer server = net.minecraftforge.fml.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            // al doilea bip la incuiere
            for (java.util.Iterator<Object[]> it = BEEPS.iterator(); it.hasNext();) {
                Object[] b = it.next();
                int left = (Integer) b[2] - 1;
                b[2] = left;
                if (left <= 0) {
                    beep((ServerWorld) b[0], (Entity) b[1], 1.4f);
                    it.remove();
                }
            }
            ++ticks;
            // soferul fara cheie e dat jos din masina
            if (ticks % 10 == 0) {
                for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) {
                    Entity v = p.getVehicle();
                    if (v == null || v.getControllingPassenger() != p) continue;
                    GarageData.Car c = carOf(server, v);
                    if (c != null && !hasKey(p, c)) {
                        p.stopRiding();
                        p.displayClientMessage(new StringTextComponent("Nu ai cheia acestei masini."), true);
                    }
                }
            }
            if (ticks % 600 == 0) {
                for (ServerPlayerEntity p : server.getPlayerList().getPlayers()) syncTo(p, false);
                LAST_SYNC.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
            }
        }

        @SubscribeEvent
        public void onCommands(RegisterCommandsEvent e) {
            e.getDispatcher().register(garajCommand());
            e.getDispatcher().register(carsCommand());
            e.getDispatcher().register(stationCommand());
        }
    }

    /** Lista masinilor personale ale jucatorului pentru mecanic (ITP, asigurare, caroserie). */
    static void sendMechList(ServerPlayerEntity p, GarageData data) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (GarageData.Car c : data.of(p.getUUID())) {
            JsonObject j = new JsonObject();
            j.addProperty("id", c.id);
            j.addProperty("type", c.type);
            j.addProperty("name", c.name);
            j.addProperty("plate", c.plate);
            j.addProperty("hp", c.nbt.getFloat("Health"));
            j.addProperty("maxHp", c.nbt.getFloat("MaxHealth"));
            j.addProperty("itp", c.itpUntil);
            j.addProperty("ins", c.insUntil);
            j.addProperty("out", c.out);
            j.addProperty("pound", c.pound);
            arr.add(j);
        }
        o.add("cars", arr);
        CarsNet.reply(p, "mech", o.toString());
    }

    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    /** Servicii de mecanic (dupa ce pluginul a luat banii): daca intoarce 0, pluginul returneaza banii. */
    private static int service(CommandContext<CommandSource> c, ServerPlayerEntity p, int carId, String svc) {
        GarageData data = GarageData.get(c.getSource().getServer());
        GarageData.Car car = data.get(carId);
        if (car == null || car.owner == null || !car.owner.equals(p.getUUID())) return fail(c, "Masina nu e a ta.");
        long now = System.currentTimeMillis();
        switch (svc) {
            case "repair": {
                float max = car.nbt.getFloat("MaxHealth");
                if (max <= 0) return fail(c, "Aceasta masina nu are caroserie de reparat.");
                if (car.out) return fail(c, "Parcheaza masina in garaj ca sa o repari.");
                if (car.nbt.getFloat("Health") >= max - 0.01f) return fail(c, "Masina nu are nevoie de reparatie.");
                car.nbt.putFloat("Health", max);
                p.displayClientMessage(new StringTextComponent(car.name + " (" + car.plate + ") a fost reparata."), false);
                break;
            }
            case "itp":
                car.itpUntil = Math.max(now, car.itpUntil) + 14 * DAY_MS;
                p.displayClientMessage(new StringTextComponent("ITP facut pentru " + car.name + " (" + car.plate + ")."), false);
                break;
            case "rca7":
            case "rca30":
                car.insUntil = Math.max(now, car.insUntil) + ("rca7".equals(svc) ? 7 : 30) * DAY_MS;
                p.displayClientMessage(new StringTextComponent("Asigurare RCA activa pentru " + car.name + " (" + car.plate + ")."), false);
                break;
            default:
                return fail(c, "Serviciu necunoscut.");
        }
        data.setDirty();
        sendMechList(p, data);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSource> mechCommands() {
        return Commands.literal("service").then(Commands.argument("player", EntityArgument.player())
            .then(Commands.argument("car", IntegerArgumentType.integer(1)).then(Commands.argument("svc", StringArgumentType.word()).executes(c ->
                service(c, EntityArgument.getPlayer(c, "player"), IntegerArgumentType.getInteger(c, "car"), StringArgumentType.getString(c, "svc"))))));
    }

    private static LiteralArgumentBuilder<CommandSource> mechListCommand() {
        return Commands.literal("mechlist").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
            ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
            sendMechList(p, GarageData.get(c.getSource().getServer()));
            return 1;
        }));
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
                g.slots.add(new double[] {p.getX(), p.getY(), p.getZ(), p.yRot});
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

    private static LiteralArgumentBuilder<CommandSource> stationCommand() {
        return Commands.literal("benzinarie").requires(s -> s.hasPermission(2))
            .then(Commands.literal("creeaza").then(Commands.argument("nume", StringArgumentType.greedyString()).executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrException();
                GarageData data = GarageData.get(c.getSource().getServer());
                GarageData.Station st = data.addStation();
                st.name = StringArgumentType.getString(c, "nume");
                st.dim = p.level.dimension().location().toString();
                st.x = p.getX();
                st.y = p.getY();
                st.z = p.getZ();
                data.setDirty();
                return ok(c, "Benzinaria #" + st.id + " (" + st.name + ") e aici, raza " + st.radius + ". Schimba raza: /benzinarie raza " + st.id + " <raza>");
            })))
            .then(Commands.literal("raza").then(Commands.argument("id", IntegerArgumentType.integer(1))
                .then(Commands.argument("raza", FloatArgumentType.floatArg(2, 24)).executes(c -> {
                    GarageData data = GarageData.get(c.getSource().getServer());
                    GarageData.Station st = data.station(IntegerArgumentType.getInteger(c, "id"));
                    if (st == null) return fail(c, "Benzinaria nu exista.");
                    st.radius = FloatArgumentType.getFloat(c, "raza");
                    data.setDirty();
                    return ok(c, "Raza benzinariei " + st.name + ": " + st.radius);
                }))))
            .then(Commands.literal("muta").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                ServerPlayerEntity p = c.getSource().getPlayerOrException();
                GarageData data = GarageData.get(c.getSource().getServer());
                GarageData.Station st = data.station(IntegerArgumentType.getInteger(c, "id"));
                if (st == null) return fail(c, "Benzinaria nu exista.");
                st.dim = p.level.dimension().location().toString();
                st.x = p.getX();
                st.y = p.getY();
                st.z = p.getZ();
                data.setDirty();
                return ok(c, "Benzinaria " + st.name + " a fost mutata aici.");
            })))
            .then(Commands.literal("sterge").then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                GarageData data = GarageData.get(c.getSource().getServer());
                if (data.station(IntegerArgumentType.getInteger(c, "id")) == null) return fail(c, "Benzinaria nu exista.");
                data.removeStation(IntegerArgumentType.getInteger(c, "id"));
                return ok(c, "Benzinarie stearsa.");
            })))
            .then(Commands.literal("lista").executes(c -> {
                List<GarageData.Station> l = GarageData.get(c.getSource().getServer()).stations();
                if (l.isEmpty()) return fail(c, "Nu exista benzinarii. Creeaza una: /benzinarie creeaza <nume>");
                for (GarageData.Station st : l) c.getSource().sendSuccess(new StringTextComponent("#" + st.id + " " + st.name + " " + (int) st.x + " " + (int) st.y + " " + (int) st.z + " raza " + st.radius), false);
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
                                        giveKey(p, car, false);
                                        p.displayClientMessage(new StringTextComponent(car.name + " (" + car.plate + ") te asteapta in orice garaj. Ai primit cheia."), false);
                                        return 1;
                                    })))))))))
            .then(Commands.literal("tune").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("car", IntegerArgumentType.integer(1))
                    .then(Commands.argument("mode", StringArgumentType.word())
                        .then(Commands.argument("fromEngine", StringArgumentType.string())
                            .then(Commands.argument("fromWheels", StringArgumentType.string())
                                .then(Commands.argument("fromColor", IntegerArgumentType.integer(0, 0xFFFFFF))
                                .then(Commands.argument("color", IntegerArgumentType.integer(0, 0xFFFFFF))
                                    .then(Commands.argument("engine", StringArgumentType.string())
                                        .then(Commands.argument("wheels", StringArgumentType.string()).executes(CarsServer::tune)))))))))))
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
                            v.remove(); // vehicul fara acte (nu e din garaj): se ridica definitiv
                        }
                        officer.displayClientMessage(new StringTextComponent("Vehicul ridicat la pound."), true);
                        return 1;
                    })))))
            .then(mechCommands())
            .then(mechListCommand())
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
            // benzinarie: pluginul a luat deja banii; daca intoarce 0, pluginul ii returneaza
            .then(Commands.literal("fuel").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("station", IntegerArgumentType.integer(1))
                    .then(Commands.argument("entity", IntegerArgumentType.integer())
                        .then(Commands.argument("units", IntegerArgumentType.integer(1, 1_000_000)).executes(c -> {
                            ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
                            GarageData data = GarageData.get(c.getSource().getServer());
                            if (stationNear(p, data, IntegerArgumentType.getInteger(c, "station")) == null) return fail(c, "Jucatorul nu e la benzinarie.");
                            Entity v = p.level.getEntity(IntegerArgumentType.getInteger(c, "entity"));
                            if (v == null || v.distanceTo(p) > 10 || !addFuel(v, IntegerArgumentType.getInteger(c, "units"))) return fail(c, "Vehiculul nu poate fi alimentat.");
                            return 1;
                        }))))))
            // cheie noua pentru o masina (cele vechi nu mai merg)
            .then(Commands.literal("cheie").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("car", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerPlayerEntity p = EntityArgument.getPlayer(c, "player");
                    GarageData.Car car = GarageData.get(c.getSource().getServer()).get(IntegerArgumentType.getInteger(c, "car"));
                    if (car == null) return fail(c, "Masina nu exista.");
                    giveKey(p, car, true);
                    return ok(c, "Cheie noua pentru " + car.name + " (" + car.plate + ") data lui " + p.getName().getString() + ".");
                }))))
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
        engine = resolveEngine(engine);
        Entity e = t.create(server.overworld());
        if (e == null) return null;
        CompoundNBT init = e.saveWithoutId(new CompoundNBT());
        init.put("Color", new IntArrayNBT(new int[] {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255}));
        VehicleParts.apply(init, engine, wheels);
        e.load(init);
        CompoundNBT nbt = strip(e.saveWithoutId(new CompoundNBT()));
        if (!engine.equals("-") && !nbt.getBoolean("HasEngine")) {
            LOG.warn("give {}: motorul {} nu a fost preluat de vehicul; il pun direct in date", type, engine);
        }
        VehicleParts.apply(nbt, engine, wheels);
        if (nbt.contains("FuelCapacity")) nbt.putFloat("CurrentFuel", nbt.getFloat("FuelCapacity") * 0.5f);
        e.remove();
        return GarageData.get(server).add(owner, faction, type, name, plate, nbt);
    }

    private static CompoundNBT stack(String id) {
        CompoundNBT s = new CompoundNBT();
        s.putString("id", new ResourceLocation(id).toString());
        s.putByte("Count", (byte) 1);
        return s;
    }
}
