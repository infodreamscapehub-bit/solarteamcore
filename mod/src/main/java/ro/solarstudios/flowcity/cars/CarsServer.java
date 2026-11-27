package ro.solarstudios.flowcity.cars;

import net.minecraft.network.chat.Component;

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
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
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
 * Folosite de plugin: /fccars give, givefaction, impound, unpound, fuel, sell, transfer, remove.
 * Membrii unei factiuni au tag-ul de scoreboard "fc_&lt;factiune&gt;" (pluginul il pune automat politistilor din MDT).
 */
public final class CarsServer {
    private CarsServer() {}

    private static final String TAG = "fcVehId";
    private static final double MENU_RANGE = 4;

    // ------------------------------------------------------------------ actiuni din interfata

    static void handle(ServerPlayer p, String act, int a, int b) {
        handle(p, act, a, b, "");
    }

    static void handle(ServerPlayer p, String act, int a, int b, String text) {
        GarageData data = GarageData.get(p.server);
        switch (act) {
            case "gadmin":
                gadmin(p, a, b, text);
                break;
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
            case "trip":
                resetTrip(p, data, a);
                break;
            case "fuelopen": {
                GarageData.Station st = stationNear(p, data, b);
                if (st == null) tell(p, "Nu esti la o benzinarie.");
                else FuelPumps.openStation(p, st, a);
                break;
            }
            default:
                break;
        }
    }

    private static boolean sameDim(ServerPlayer p, GarageData.Garage g) {
        return p.level().dimension().location().toString().equals(g.dim);
    }

    private static boolean nearMenu(ServerPlayer p, GarageData.Garage g) {
        return sameDim(p, g) && p.position().distanceTo(new Vec3(g.mx, g.my, g.mz)) <= MENU_RANGE + 2;
    }

    static boolean member(ServerPlayer p, String faction) {
        return p.hasPermissions(2) || p.getTags().contains("fc_" + faction);
    }

    private static boolean canAccess(ServerPlayer p, GarageData.Garage g) {
        return !GarageData.FACTION.equals(g.type) || member(p, g.faction);
    }

    private static boolean canUse(ServerPlayer p, GarageData.Car c) {
        if (c == null) return false;
        if (c.owner != null) return c.owner.equals(p.getUUID());
        return member(p, c.faction);
    }

    /** Masinile vazute intr-un garaj: personale (public / pound) sau ale factiunii. */
    private static List<GarageData.Car> carsFor(ServerPlayer p, GarageData data, GarageData.Garage g) {
        if (GarageData.FACTION.equals(g.type)) return data.ofFaction(g.faction);
        return data.of(p.getUUID());
    }

    private static void openMenu(ServerPlayer p, GarageData data, GarageData.Garage g, boolean ok, String msg) {
        if (g == null || !nearMenu(p, g)) return;
        if (!canAccess(p, g)) {
            p.displayClientMessage(Component.literal("Garajul e doar pentru " + g.faction + "."), true);
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
            j.addProperty("engine", VehicleParts.engine(p.level(), c.type, nbt));
            j.addProperty("wheels", VehicleParts.wheels(nbt));
            j.addProperty("odo", Math.round(c.odometerM));
            j.addProperty("trip", Math.round(c.tripM));
            arr.add(j);
        }
        o.add("cars", arr);
        CarsNet.reply(p, "garage", o.toString());
    }

    private static String stackId(CompoundTag nbt, String key) {
        String id = nbt.contains(key, 10) ? nbt.getCompound(key).getString("id") : "";
        return id.isEmpty() ? "-" : id;
    }

    /** Masinile personale parcate in garaj (nu in oras, nu la pound): cele care pot fi tunate. */
    private static void tuneList(ServerPlayer p, GarageData data) {
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
            j.addProperty("engine", VehicleParts.engine(p.level(), c.type, c.nbt));
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
    private static int tune(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        GarageData data = GarageData.get(c.getSource().getServer());
        GarageData.Car car = data.get(IntegerArgumentType.getInteger(c, "car"));
        if (car == null || car.owner == null || !car.owner.equals(p.getUUID())) return fail(c, "Masina nu e a ta.");
        if (car.out || car.pound) return fail(c, "Parcheaza masina in garaj ca sa o tunezi.");
        if ("garage".equals(StringArgumentType.getString(c, "mode"))) {
            boolean near = false;
            for (GarageData.Garage g : data.garages()) near |= nearMenu(p, g);
            if (!near) return fail(c, "Tuning-ul se face la garaj sau la mecanic.");
        }
        String curEngine = VehicleParts.engine(p.level(), car.type, car.nbt);
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
        car.nbt.put("Color", new IntArrayTag(new int[] {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255}));
        VehicleParts.apply(car.nbt, engine.equals(curEngine) ? null : engine, wheels.equals(curWheels) ? null : wheels);
        data.setDirty();
        p.displayClientMessage(Component.literal(car.name + " (" + car.plate + ") a fost tunata."), false);
        return 1;
    }

    private static void spawnFrom(ServerPlayer p, GarageData data, GarageData.Garage g, GarageData.Car c) {
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
            boolean boat = isBoat(c);
            Entity e = null;
            if (boat && g.boatSlots.isEmpty()) {
                msg = "Acest garaj nu are puncte de spawn pentru barci. Cere unui admin sa adauge unul.";
            } else {
                e = spawn(p.serverLevel(), g, c);
                msg = e != null ? c.name + " (" + c.plate + ") te asteapta in " + (boat ? "port." : "parcare.")
                    : boat ? "Toate punctele de spawn pentru barci sunt ocupate." : "Toate locurile de parcare sunt ocupate.";
            }
            ok = e != null;
            if (ok && c.owner != null && !hasKeyItem(p, c)) {
                giveKey(p, c, true);
                msg += " Ai primit o cheie noua (cele vechi nu mai merg).";
            }
            if (ok) data.setDirty();
        }
        openMenu(p, data, g, ok, msg);
    }

    private static void storeAt(ServerPlayer p, GarageData data, GarageData.Garage g) {
        if (g == null || !sameDim(p, g) || GarageData.POUND.equals(g.type)) return;
        Entity v = p.getRootVehicle();
        if (v == p) {
            p.displayClientMessage(Component.literal("Trebuie sa fii in vehicul."), true);
            return;
        }
        Vec3 zone = g.hasStore ? new Vec3(g.sx, g.sy, g.sz) : new Vec3(g.mx, g.my, g.mz);
        double r = g.hasStore ? g.radius + 1.5 : 8;
        if (v.position().distanceTo(zone) > r) return;
        GarageData.Car c = v.getPersistentData().contains(TAG) ? data.get(v.getPersistentData().getInt(TAG)) : null;
        if (c == null || !c.out || !v.getUUID().equals(c.entity)) {
            p.displayClientMessage(Component.literal("Vehiculul nu e al tau."), true);
            return;
        }
        if (!canUse(p, c)) {
            p.displayClientMessage(Component.literal("Vehiculul nu e al tau."), true);
            return;
        }
        if (GarageData.FACTION.equals(g.type) != (c.owner == null)) {
            p.displayClientMessage(Component.literal(c.owner == null ? "Masinile factiunii se parcheaza la garajul factiunii." : "Aici se parcheaza doar masinile factiunii."), true);
            return;
        }
        storeEntity(v, c);
        data.setDirty();
        p.displayClientMessage(Component.literal(c.name + " a fost parcata in " + g.name + "."), true);
    }

    private static void storeEntity(Entity v, GarageData.Car c) {
        v.ejectPassengers();
        c.nbt = strip(v.saveWithoutId(new CompoundTag()));
        c.out = false;
        c.entity = null;
        v.discard();
    }

    /** Pune vehiculul pe primul loc de parcare liber (sau langa marker, daca nu sunt locuri setate). */
    /** Masinile create inainte de fix pot sa nu aiba motor/roti in date: le completam (motor fier, roti standard) ca sa porneasca. */
    private static void heal(ServerLevel level, GarageData.Car c) {
        try {
            String fam = VehicleParts.family(level, c.type);
            if (fam == null) return;
            String eng = null;
            String whl = null;
            if (!c.nbt.getBoolean("HasEngine")) eng = resolveEngine("vehicle:iron_" + fam + "_engine");
            if (!c.nbt.getBoolean("HasWheels")) whl = "vehicle:standard_wheel";
            if (eng != null || whl != null) {
                VehicleParts.apply(c.nbt, eng, whl);
                LOG.info("Masina {} ({}): am completat motorul/rotile lipsa", c.id, c.type);
            }
        } catch (RuntimeException ex) {
            LOG.warn("heal {}: {}", c.type, ex.toString());
        }
    }

    /** Barca dupa numele din registru (MrCrayfish & co nu au API comun); completat in spawn() si cu instanceof BoatEntity. */
    static boolean isBoat(GarageData.Car c) {
        String t = c.type == null ? "" : c.type.toLowerCase(java.util.Locale.ROOT);
        return t.contains("boat") || t.contains("ship") || t.contains("jetski") || t.contains("jet_ski") || t.contains("yacht")
            || t.contains("kayak") || t.contains("canoe") || t.contains("speedboat");
    }

    private static Entity spawn(ServerLevel level, GarageData.Garage g, GarageData.Car c) {
        EntityType<?> type = EntityType.byString(c.type).orElse(null);
        if (type == null) return null;
        boolean boat = isBoat(c);
        if (!boat) {
            Entity probe = type.create(level);
            boat = probe instanceof net.minecraft.world.entity.vehicle.Boat;
        }
        List<double[]> spots = new ArrayList<>(boat ? g.boatSlots : g.slots);
        if (boat) {
            if (spots.isEmpty()) return null;
        } else if (spots.isEmpty()) {
            for (int d = 3; d <= 7; d += 2) spots.add(new double[] {g.mx + d, g.my, g.mz, 0});
        }
        for (double[] s : spots) {
            Entity e = type.create(level);
            if (e == null) return null;
            heal(level, c);
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

    static boolean hasKeyItem(ServerPlayer p, GarageData.Car c) {
        for (net.minecraft.world.item.ItemStack st : p.getInventory().items) if (CarKeyItem.opens(st, c)) return true;
        for (net.minecraft.world.item.ItemStack st : p.getInventory().offhand) if (CarKeyItem.opens(st, c)) return true;
        return false;
    }

    /** Cine poate conduce / incuia: cheia (personala) sau factiunea (masini de factiune). Adminii pot oricand. */
    static boolean hasKey(ServerPlayer p, GarageData.Car c) {
        if (c == null) return true;
        if (p.hasPermissions(2)) return true;
        if (c.owner == null) return member(p, c.faction);
        return hasKeyItem(p, c);
    }

    static void giveKey(ServerPlayer p, GarageData.Car c, boolean newGeneration) {
        if (newGeneration) c.keyGen++;
        net.minecraft.world.item.ItemStack st = CarKeyItem.make(c);
        if (!p.getInventory().add(st)) p.drop(st, false);
        GarageData.get(p.server).setDirty();
    }

    private static final java.util.List<Object[]> BEEPS = new java.util.ArrayList<>();

    private static void beep(ServerLevel w, Entity v, float pitch) {
        w.playSound(null, v.getX(), v.getY(), v.getZ(), net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BIT.value(), net.minecraft.sounds.SoundSource.PLAYERS, 0.9f, pitch);
    }

    /** Tasta U: incuie / descuie cea mai apropiata masina pentru care ai cheie (pana la 12 blocuri). */
    private static void toggleLock(ServerPlayer p, GarageData data) {
        Entity best = null;
        GarageData.Car bestCar = null;
        double bd = 12 * 12;
        for (Entity v : p.level().getEntities((Entity) null, p.getBoundingBox().inflate(12), x -> x.getPersistentData().contains(TAG))) {
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
            p.displayClientMessage(Component.literal("Nicio masina de-a ta in apropiere (sau nu ai cheia)."), true);
            return;
        }
        bestCar.locked = !bestCar.locked;
        data.setDirty();
        ServerLevel w = p.serverLevel();
        beep(w, best, bestCar.locked ? 1.4f : 1.9f);
        if (bestCar.locked) BEEPS.add(new Object[] {w, best, 4});
        p.displayClientMessage(Component.literal((bestCar.locked ? "Incuiata: " : "Descuiata: ") + bestCar.name + " (" + bestCar.plate + ")"), true);
        JsonObject o = new JsonObject();
        o.addProperty("id", best.getId());
        o.addProperty("locked", bestCar.locked);
        for (ServerPlayer other : w.players()) {
            if (other.distanceToSqr(best) < 64 * 64) CarsNet.reply(other, "flash", o.toString());
        }
    }

    // ------------------------------------------------------------------ benzinarii

    private static GarageData.Station stationNear(ServerPlayer p, GarageData data, int id) {
        GarageData.Station st = data.station(id);
        if (st == null || !p.level().dimension().location().toString().equals(st.dim)) return null;
        return p.position().distanceTo(new Vec3(st.x, st.y, st.z)) <= st.radius + 4 ? st : null;
    }

    /** Mesaj scurt deasupra barei de inventar. */
    static void tell(ServerPlayer p, String text) {
        p.displayClientMessage(Component.literal(text), true);
    }

    /** Benzina din rezervor, in unitati MrCrayfish (1 L = 1000). */
    static float currentFuel(Entity v) {
        Object o = callNum(v, "getCurrentFuel");
        return o instanceof Number ? ((Number) o).floatValue() : 0f;
    }

    /** Capacitatea rezervorului, in unitati MrCrayfish. */
    static float fuelCapacity(Entity v) {
        Object o = callNum(v, "getFuelCapacity");
        return o instanceof Number ? ((Number) o).floatValue() : 0f;
    }

    /** Vehiculul are rezervor si consuma benzina. */
    static boolean hasTank(Entity v) {
        if (v == null || !v.isAlive()) return false;
        Object req = callNum(v, "requiresFuel");
        if (req instanceof Boolean && !((Boolean) req)) return false;
        return fuelCapacity(v) > 0;
    }

    private static Object callNum(Entity v, String name) {
        try {
            java.lang.reflect.Method m = findMethod(v.getClass(), name);
            return m == null ? null : m.invoke(v);
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    /** Varianta publica in pachet pentru alimentarea treptata (vezi FuelPumps). */
    static boolean addFuelUnits(Entity v, float units) {
        return addFuel(v, units);
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

    // ------------------------------------------------------------------ chilometraj (calculat doar pe server)

    /**
     * Chilometrajul. Masinile din garaj (fcVehId) il tin in GarageData (calatoreste cu masina cand e parcata / scoasa / vanduta).
     * Vehiculele MrCrayfish neinregistrate (ex. puse de admin) il tin in datele persistente ale entitatii (fcOdo*), salvate cu chunk-ul.
     * Distanta = deplasarea reala a entitatii intre doua esantioane (0,5 s): sub 2 cm = stationare, peste 40 m = teleport (ignorat).
     * Clientul nu trimite niciodata valori: doar le primeste.
     */
    private static final String ODO_M = "fcOdoM";
    private static final String ODO_ID = "fcOdoId";
    private static final String ODO_SINCE = "fcOdoSince";
    /** Trip A / trip B ale vehiculelor neinregistrate (metri, in datele persistente ale entitatii). */
    private static final String TRIP_A = "fcTripA";
    private static final String TRIP_B = "fcTripB";
    private static final java.util.Map<String, Vec3> ODO_POS = new java.util.HashMap<>();
    /** Per sofer: cheia vehiculului, ultimele valori trimise (odo, trip A, trip B). */
    private static final java.util.Map<UUID, Object[]> ODO_SENT = new java.util.HashMap<>();
    private static final java.util.Map<UUID, Long> TRIP_RESET_AT = new java.util.HashMap<>();
    private static Class<?> poweredClass;
    private static boolean poweredChecked;

    private static boolean isVehicle(Entity v) {
        if (!poweredChecked) {
            poweredChecked = true;
            try {
                poweredClass = Class.forName("com.mrcrayfish.vehicle.entity.PoweredVehicleEntity");
            } catch (ClassNotFoundException | LinkageError e) {
                poweredClass = null;
            }
        }
        return poweredClass != null && poweredClass.isInstance(v);
    }

    private static String fmtDate(long ms) {
        if (ms <= 0) return "";
        return new java.text.SimpleDateFormat("dd.MM.yyyy").format(new java.util.Date(ms));
    }

    private static void sendOdo(ServerPlayer p, Entity v, String key, double odo, double tripA, double tripB, GarageData.Car c) {
        JsonObject o = new JsonObject();
        o.addProperty("eid", v.getId());
        o.addProperty("odo", Math.round(odo));
        o.addProperty("tripA", Math.round(tripA));
        o.addProperty("tripB", Math.round(tripB));
        Object[] prev = ODO_SENT.get(p.getUUID());
        if (prev == null || !key.equals(prev[0])) {
            // datele fixe ale vehiculului doar la prima trimitere pentru acest vehicul
            o.addProperty("meta", true);
            if (c != null) {
                o.addProperty("model", c.name);
                o.addProperty("plate", c.plate == null ? "" : c.plate);
                o.addProperty("owner", c.owner == null ? "Factiune " + c.faction : nameOf(p.server, c.owner));
                o.addProperty("reg", fmtDate(c.registeredAt));
                o.addProperty("vid", "FC-" + c.id);
            } else {
                o.addProperty("model", v.getDisplayName().getString());
                o.addProperty("plate", "");
                o.addProperty("owner", "Neinregistrat");
                o.addProperty("reg", fmtDate(v.getPersistentData().getLong(ODO_SINCE)));
                o.addProperty("vid", v.getPersistentData().getString(ODO_ID));
            }
        }
        CarsNet.reply(p, "odo", o.toString());
        ODO_SENT.put(p.getUUID(), new Object[] {key, odo, tripA, tripB});
    }

    /** La ~0,5 s: adauga distanta reala parcursa vehiculelor cu sofer si trimite soferului valorile (rar: la 50 m sau la schimbarea vehiculului). */
    /** Metri condusi de la ultimul raport catre plugin (misiunile de battlepass pe distanta). */
    private static final java.util.Map<UUID, Double> BP_METERS = new java.util.HashMap<>();

    /** Raporteaza pluginului distanta parcursa, rar (la fiecare 200 m). Daca pluginul lipseste, nu se intampla nimic. */
    private static void reportDistance(MinecraftServer server, ServerPlayer p, double meters) {
        UUID u = p.getUUID();
        double acc = BP_METERS.merge(u, meters, Double::sum);
        if (acc < 200) return;
        long whole = (long) acc;
        BP_METERS.put(u, acc - whole);
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),
                "fcbp drive " + p.getGameProfile().getName() + " " + whole);
        } catch (RuntimeException ex) {
            // pluginul lipseste sau comanda nu a putut fi rulata: kilometrajul propriu nu e afectat
        }
    }

    private static void odoTick(MinecraftServer server, GarageData data) {
        java.util.Set<String> active = new java.util.HashSet<>();
        java.util.Set<UUID> activePlayers = new java.util.HashSet<>();
        long now = System.currentTimeMillis();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            Entity v = p.getVehicle();
            if (v == null || v.getControllingPassenger() != p) continue;
            GarageData.Car c = carOf(server, v);
            if (c == null && (v.getPersistentData().contains(TAG) || !isVehicle(v))) continue;
            CompoundTag pd = v.getPersistentData();
            if (c == null && !pd.contains(ODO_ID)) {
                pd.putString(ODO_ID, "V-" + v.getUUID().toString().substring(0, 8).toUpperCase());
                pd.putLong(ODO_SINCE, now);
                pd.putDouble(ODO_M, 0);
            }
            String key = c != null ? "c" + c.id : "e" + v.getUUID();
            active.add(key);
            activePlayers.add(p.getUUID());
            Vec3 pos = v.position();
            Vec3 prev = ODO_POS.put(key, pos);
            if (prev != null) {
                double d = prev.distanceTo(pos);
                if (d > 0.02 && d <= 40) {
                    if (c != null) {
                        c.odometerM += d;
                        c.tripM += d;
                        c.tripBM += d;
                        data.setDirty();
                    } else {
                        pd.putDouble(ODO_M, pd.getDouble(ODO_M) + d);
                        pd.putDouble(TRIP_A, pd.getDouble(TRIP_A) + d);
                        pd.putDouble(TRIP_B, pd.getDouble(TRIP_B) + d);
                    }
                    reportDistance(server, p, d);
                }
            }
            double odo = c != null ? c.odometerM : pd.getDouble(ODO_M);
            double ta = c != null ? c.tripM : pd.getDouble(TRIP_A);
            double tb = c != null ? c.tripBM : pd.getDouble(TRIP_B);
            Object[] sent = ODO_SENT.get(p.getUUID());
            // rar: la fiecare 50 m parcursi sau cand se schimba vehiculul (clientul interpoleaza intre timp)
            if (sent == null || !key.equals(sent[0]) || Math.abs(odo - (Double) sent[1]) >= 50) sendOdo(p, v, key, odo, ta, tb, c);
        }
        ODO_POS.keySet().retainAll(active);
        ODO_SENT.keySet().retainAll(activePlayers);
        BP_METERS.keySet().retainAll(activePlayers);
        TRIP_RESET_AT.values().removeIf(t -> now - t > 60_000L);
    }

    /** Resetarea unui trip (0 = A, 1 = B): doar soferul vehiculului, validat pe server, cel mult o data pe secunda. */
    private static void resetTrip(ServerPlayer p, GarageData data, int which) {
        if (which != 0 && which != 1) return;
        Entity v = p.getVehicle();
        if (v == null || v.getControllingPassenger() != p) return;
        long now = System.currentTimeMillis();
        Long last = TRIP_RESET_AT.get(p.getUUID());
        if (last != null && now - last < 1000L) return;
        GarageData.Car c = carOf(p.server, v);
        CompoundTag pd = v.getPersistentData();
        if (c == null && !pd.contains(ODO_ID)) return;
        TRIP_RESET_AT.put(p.getUUID(), now);
        if (c != null) {
            if (which == 0) c.tripM = 0;
            else c.tripBM = 0;
            data.setDirty();
        } else {
            pd.putDouble(which == 0 ? TRIP_A : TRIP_B, 0);
        }
        String key = c != null ? "c" + c.id : "e" + v.getUUID();
        ODO_SENT.remove(p.getUUID());
        double odo = c != null ? c.odometerM : pd.getDouble(ODO_M);
        sendOdo(p, v, key, odo, c != null ? c.tripM : pd.getDouble(TRIP_A), c != null ? c.tripBM : pd.getDouble(TRIP_B), c);
        p.displayClientMessage(Component.literal("Trip " + (which == 0 ? "A" : "B") + " resetat."), true);
    }

    private static void plate(ServerPlayer p, GarageData data, int entityId) {
        Entity e = p.level().getEntity(entityId);
        if (e == null || e.distanceTo(p) > 8) return;
        GarageData.Car c = e.getPersistentData().contains(TAG) ? data.get(e.getPersistentData().getInt(TAG)) : null;
        JsonObject o = new JsonObject();
        o.addProperty("id", entityId);
        o.addProperty("plate", c == null || c.plate == null ? "" : c.plate);
        CarsNet.reply(p, "plate", o.toString());
    }

    /** Politia se uita la un vehicul: numar, model, proprietar, stare. */
    private static void inspect(ServerPlayer p, GarageData data, int entityId) {
        Entity e = p.level().getEntity(entityId);
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
            o.addProperty("odo", Math.round(c.odometerM));
            o.addProperty("reg", fmtDate(c.registeredAt));
        } else {
            if (e.getPersistentData().contains(ODO_ID)) o.addProperty("odo", Math.round(e.getPersistentData().getDouble(ODO_M)));
            o.addProperty("plate", "");
            o.addProperty("owner", "fara acte (nu e inregistrat)");
        }
        CarsNet.reply(p, "inspect", o.toString());
    }

    // ------------------------------------------------------------------ utilitare

    private static String nameOf(MinecraftServer server, UUID id) {
        com.mojang.authlib.GameProfile gp = server.getProfileCache() == null ? null : server.getProfileCache().get(id).orElse(null);
        return gp == null ? "necunoscut" : gp.getName();
    }

    private static Entity findLoaded(MinecraftServer server, UUID id) {
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

    private static int color(CompoundTag nbt) {
        int[] c = nbt.getIntArray("Color");
        if (c.length == 3) return ((c[0] & 255) << 16) | ((c[1] & 255) << 8) | (c[2] & 255);
        return 0xFFFFFF;
    }

    /** Lista garajelor (pentru markere / harta) + factiunile jucatorului. */
    static void syncTo(ServerPlayer p) {
        syncTo(p, true);
    }

    static void syncTo(ServerPlayer p, boolean force) {
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
            j.addProperty("slots", g.slots.size());
            j.addProperty("bslots", g.boatSlots.size());
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
        for (ServerPlayer p : server.getPlayerList().getPlayers()) syncTo(p, true);
    }

    // ------------------------------------------------------------------ evenimente + comenzi

    public static final class Events {
        private int ticks;

        /** O masina veche (ex. dintr-un chunk descarcat) care a fost recuperata intre timp nu mai intra in lume. */
        @SubscribeEvent
        public void onJoin(EntityJoinLevelEvent e) {
            if (e.getLevel().isClientSide()) return;
            Entity en = e.getEntity();
            if (!en.getPersistentData().contains(TAG) || !(e.getLevel() instanceof ServerLevel)) return;
            ServerLevel sl = (ServerLevel) e.getLevel();
            GarageData.Car c = GarageData.get(sl.getServer()).get(en.getPersistentData().getInt(TAG));
            if (c == null || !c.out || !en.getUUID().equals(c.entity)) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
            if (e.getEntity() instanceof ServerPlayer) syncTo((ServerPlayer) e.getEntity());
        }

        @SubscribeEvent
        public void onDim(PlayerEvent.PlayerChangedDimensionEvent e) {
            if (e.getEntity() instanceof ServerPlayer) syncTo((ServerPlayer) e.getEntity());
        }

        /** Masina incuiata: nu se poate urca nimeni (nici prin click, nici altfel). */
        @SubscribeEvent
        public void onInteract(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteract e) {
            if (lockedFor(e.getEntity(), e.getTarget())) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onInteractAt(net.minecraftforge.event.entity.player.PlayerInteractEvent.EntityInteractSpecific e) {
            if (lockedFor(e.getEntity(), e.getTarget())) e.setCanceled(true);
        }

        @SubscribeEvent
        public void onMount(net.minecraftforge.event.entity.EntityMountEvent e) {
            if (!e.isMounting() || e.getLevel().isClientSide() || !(e.getEntityMounting() instanceof ServerPlayer)) return;
            if (lockedFor((ServerPlayer) e.getEntityMounting(), e.getEntityBeingMounted())) e.setCanceled(true);
        }

        private boolean lockedFor(net.minecraft.world.entity.player.Player pl, Entity v) {
            if (!(pl instanceof ServerPlayer) || v == null || pl.getVehicle() == v) return false;
            GarageData.Car c = carOf(((ServerPlayer) pl).server, v);
            if (c == null || !c.locked) return false;
            pl.displayClientMessage(Component.literal("Masina e incuiata."), true);
            return true;
        }

        /** Re-trimitem lista la 30 s (factiunile se pot schimba: politist nou in MDT etc.). */
        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;
            // al doilea bip la incuiere
            for (java.util.Iterator<Object[]> it = BEEPS.iterator(); it.hasNext();) {
                Object[] b = it.next();
                int left = (Integer) b[2] - 1;
                b[2] = left;
                if (left <= 0) {
                    beep((ServerLevel) b[0], (Entity) b[1], 1.4f);
                    it.remove();
                }
            }
            ++ticks;
            // soferul fara cheie e dat jos din masina
            if (ticks % 10 == 0) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                    Entity v = p.getVehicle();
                    if (v == null || v.getControllingPassenger() != p) continue;
                    GarageData.Car c = carOf(server, v);
                    if (c != null && !hasKey(p, c)) {
                        p.stopRiding();
                        p.displayClientMessage(Component.literal("Nu ai cheia acestei masini."), true);
                    }
                }
            }
            if (ticks % 10 == 0) odoTick(server, GarageData.get(server));
            // salvare periodica (la 1 min) a garajelor/chilometrajului, ca un crash sa nu piarda mult
            if (ticks % 1200 == 0 && GarageData.get(server).isDirty()) {
                try {
                    server.overworld().getDataStorage().save();
                } catch (RuntimeException ex) {
                    LOG.warn("Salvarea garajelor a esuat: {}", ex.toString());
                }
            }
            if (ticks % 600 == 0) {
                for (ServerPlayer p : server.getPlayerList().getPlayers()) syncTo(p, false);
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
    static void sendMechList(ServerPlayer p, GarageData data) {
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
    private static int service(CommandContext<CommandSourceStack> c, ServerPlayer p, int carId, String svc) {
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
                p.displayClientMessage(Component.literal(car.name + " (" + car.plate + ") a fost reparata."), false);
                break;
            }
            case "itp":
                car.itpUntil = Math.max(now, car.itpUntil) + 14 * DAY_MS;
                p.displayClientMessage(Component.literal("ITP facut pentru " + car.name + " (" + car.plate + ")."), false);
                break;
            case "rca7":
            case "rca30":
                car.insUntil = Math.max(now, car.insUntil) + ("rca7".equals(svc) ? 7 : 30) * DAY_MS;
                p.displayClientMessage(Component.literal("Asigurare RCA activa pentru " + car.name + " (" + car.plate + ")."), false);
                break;
            default:
                return fail(c, "Serviciu necunoscut.");
        }
        data.setDirty();
        sendMechList(p, data);
        return 1;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mechCommands() {
        return Commands.literal("service").then(Commands.argument("player", EntityArgument.player())
            .then(Commands.argument("car", IntegerArgumentType.integer(1)).then(Commands.argument("svc", StringArgumentType.word()).executes(c ->
                service(c, EntityArgument.getPlayer(c, "player"), IntegerArgumentType.getInteger(c, "car"), StringArgumentType.getString(c, "svc"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> mechListCommand() {
        return Commands.literal("mechlist").then(Commands.argument("player", EntityArgument.player()).executes(c -> {
            ServerPlayer p = EntityArgument.getPlayer(c, "player");
            sendMechList(p, GarageData.get(c.getSource().getServer()));
            return 1;
        }));
    }

    // ------------------------------------------------------------------ vanzare (pluginul plateste / ia banii)

    /** Procentul caroseriei (0-100); 100 daca masina nu are caroserie. Aceeasi formula ca in meniul clientului. */
    static int hpPct(CompoundTag nbt) {
        float mh = nbt.getFloat("MaxHealth");
        if (mh <= 0) return 100;
        return Math.max(0, Math.min(100, Math.round(100f * nbt.getFloat("Health") / mh)));
    }

    /**
     * Verifica o vanzare: masina e a jucatorului, parcata in garaj (nu in oras, nu la pound), iar datele pe care pluginul a calculat
     * pretul (model, numar, motor, roti, caroserie) sunt cele reale. Intoarce masina sau null (mesajul de eroare e deja trimis).
     */
    private static GarageData.Car checkSale(CommandContext<CommandSourceStack> c, ServerPlayer owner, GarageData data) {
        GarageData.Car car = data.get(IntegerArgumentType.getInteger(c, "car"));
        if (car == null || car.owner == null || !car.owner.equals(owner.getUUID())) {
            fail(c, "Masina nu e a ta.");
            return null;
        }
        if (car.pound) {
            fail(c, "Masina e la pound, nu poate fi vanduta.");
            return null;
        }
        if (car.out) {
            fail(c, "Parcheaza masina in garaj ca s-o vinzi.");
            return null;
        }
        boolean same = car.type.equals(StringArgumentType.getString(c, "type"))
            && car.plate.equals(StringArgumentType.getString(c, "plate"))
            && VehicleParts.engine(owner.level(), car.type, car.nbt).equals(StringArgumentType.getString(c, "engine"))
            && VehicleParts.wheels(car.nbt).equals(StringArgumentType.getString(c, "wheels"))
            && Math.abs(hpPct(car.nbt) - IntegerArgumentType.getInteger(c, "hp")) <= 2;
        if (!same) {
            fail(c, "Masina s-a schimbat. Redeschide garajul.");
            return null;
        }
        return car;
    }

    /** Scoate din inventar cheile unei masini (cele vechi oricum nu mai merg dupa vanzare). */
    private static void removeKeys(ServerPlayer p, GarageData.Car car) {
        for (net.minecraft.world.item.ItemStack st : p.getInventory().items) if (CarKeyItem.opens(st, car)) st.shrink(st.getCount());
        for (net.minecraft.world.item.ItemStack st : p.getInventory().offhand) if (CarKeyItem.opens(st, car)) st.shrink(st.getCount());
    }

    /** Reimprospateaza meniul garajului de langa jucator (daca e la unul personal). */
    private static void reopen(ServerPlayer p, GarageData data) {
        for (GarageData.Garage g : data.garages()) {
            if (!GarageData.FACTION.equals(g.type) && nearMenu(p, g)) {
                openMenu(p, data, g, true, "");
                return;
            }
        }
    }

    /** Vanzare la dealer: dupa verificari sterge masina (pluginul plateste doar daca intoarce 1). */
    private static int sell(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer p = EntityArgument.getPlayer(c, "player");
        GarageData data = GarageData.get(c.getSource().getServer());
        GarageData.Car car = checkSale(c, p, data);
        if (car == null) return 0;
        boolean near = false;
        for (GarageData.Garage g : data.garages()) near |= nearMenu(p, g);
        if (!near) return fail(c, "Vinzi masina din meniul unui garaj.");
        removeKeys(p, car);
        data.remove(car.id);
        p.displayClientMessage(Component.literal(car.name + " (" + car.plate + ") a fost vanduta."), false);
        reopen(p, data);
        syncAll(c.getSource().getServer());
        return 1;
    }

    /** Vanzare intre jucatori (dupa ce cumparatorul a platit prin plugin): muta proprietatea si cheia. */
    private static int transfer(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer seller = EntityArgument.getPlayer(c, "player");
        ServerPlayer buyer = EntityArgument.getPlayer(c, "buyer");
        if (seller.getUUID().equals(buyer.getUUID())) return fail(c, "Nu poti vinde masina tie insuti.");
        GarageData data = GarageData.get(c.getSource().getServer());
        GarageData.Car car = checkSale(c, seller, data);
        if (car == null) return 0;
        int max = IntegerArgumentType.getInteger(c, "max");
        if (max > 0 && data.of(buyer.getUUID()).size() >= max) return fail(c, "Cumparatorul nu mai are loc in garaj (maxim " + max + " masini).");
        removeKeys(seller, car);
        car.owner = buyer.getUUID();
        giveKey(buyer, car, true); // cheie noua: cele vechi ale vanzatorului nu mai merg
        data.setDirty();
        buyer.displayClientMessage(Component.literal("Ai cumparat " + car.name + " (" + car.plate + "). Ai primit cheia, masina te asteapta in garaj."), false);
        seller.displayClientMessage(Component.literal("Ai vandut " + car.name + " (" + car.plate + ")."), false);
        reopen(seller, data);
        syncAll(c.getSource().getServer());
        return 1;
    }

    private static int fail(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendFailure(Component.literal(msg));
        return 0;
    }

    private static int ok(CommandContext<CommandSourceStack> c, String msg) {
        c.getSource().sendSuccess(() -> Component.literal(msg), false);
        syncAll(c.getSource().getServer());
        return 1;
    }

    // ------------------------------------------------------------------ operatii admin garaje (comenzi /garaj + ecranul /garaje)

    static GarageData.Garage createGarage(ServerPlayer p, String type, String faction, String name) {
        GarageData data = GarageData.get(p.server);
        GarageData.Garage g = data.addGarage();
        g.name = name;
        g.type = type;
        g.faction = faction;
        g.dim = p.level().dimension().location().toString();
        g.mx = p.getX();
        g.my = p.getY();
        g.mz = p.getZ();
        data.setDirty();
        return g;
    }

    static boolean deleteGarage(MinecraftServer server, int id) {
        GarageData data = GarageData.get(server);
        if (data.garage(id) == null) return false;
        data.removeGarage(id);
        return true;
    }

    static int deleteAllGarages(MinecraftServer server) {
        GarageData data = GarageData.get(server);
        List<GarageData.Garage> all = new ArrayList<>(data.garages());
        for (GarageData.Garage g : all) data.removeGarage(g.id);
        return all.size();
    }

    static boolean moveGarage(ServerPlayer p, int id) {
        GarageData data = GarageData.get(p.server);
        GarageData.Garage g = data.garage(id);
        if (g == null) return false;
        g.dim = p.level().dimension().location().toString();
        g.mx = p.getX();
        g.my = p.getY();
        g.mz = p.getZ();
        data.setDirty();
        return true;
    }

    static boolean setStoreHere(ServerPlayer p, int id, float r) {
        GarageData data = GarageData.get(p.server);
        GarageData.Garage g = data.garage(id);
        if (g == null) return false;
        g.hasStore = true;
        g.sx = p.getX();
        g.sy = p.getY();
        g.sz = p.getZ();
        g.radius = r;
        data.setDirty();
        return true;
    }

    /** Adauga un loc de spawn unde stai; intoarce numarul de locuri sau -1 daca garajul nu exista. */
    static int addSlotHere(ServerPlayer p, int id) {
        GarageData data = GarageData.get(p.server);
        GarageData.Garage g = data.garage(id);
        if (g == null) return -1;
        g.slots.add(new double[] {p.getX(), p.getY(), p.getZ(), p.getYRot()});
        data.setDirty();
        return g.slots.size();
    }

    /**
     * Ecranul de administrare (/garaje). op: 0 reimprospatare, 1 sterge (b = garaj), 2 sterge toate, 3 muta aici (b), 4 teleporteaza (b),
     * 5 creeaza public (text = nume), 6 creeaza pound (text = nume), 7 creeaza factiune (text = "factiune:nume"),
     * 8 zona de parcare aici (b), 9 loc de spawn aici (b), 10 sterge locurile (terestre + barci) (b), 11 punct spawn barca aici (b).
     */
    private static void gadmin(ServerPlayer p, int op, int id, String text) {
        if (!p.hasPermissions(2)) {
            p.displayClientMessage(Component.literal("\u00a7cNu ai permisiunea."), false);
            return;
        }
        GarageData data = GarageData.get(p.server);
        String res = null;
        String name = text == null ? "" : text.trim();
        switch (op) {
            case 0:
                syncTo(p, true);
                return;
            case 1:
                res = deleteGarage(p.server, id) ? "Garaj sters (masinile raman ale jucatorilor)." : "Garajul nu exista.";
                break;
            case 2:
                res = deleteAllGarages(p.server) + " garaje sterse (masinile raman ale jucatorilor).";
                break;
            case 3:
                res = moveGarage(p, id) ? "Markerul garajului a fost mutat aici." : "Garajul nu exista.";
                break;
            case 4: {
                GarageData.Garage g = data.garage(id);
                if (g == null) {
                    res = "Garajul nu exista.";
                    break;
                }
                ServerLevel w = p.server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, new ResourceLocation(g.dim)));
                if (w == null) w = p.serverLevel();
                p.teleportTo(w, g.mx, g.my + 0.5, g.mz, p.getYRot(), p.getXRot());
                res = "Teleportat la " + g.name + ".";
                break;
            }
            case 5:
            case 6:
                if (name.isEmpty()) {
                    res = "Scrie un nume pentru garaj.";
                    break;
                }
                GarageData.Garage n = createGarage(p, op == 5 ? GarageData.PUBLIC : GarageData.POUND, "", name);
                res = (op == 5 ? "Garaj #" : "Pound #") + n.id + " creat aici. Seteaza parcarea si locurile din ecran.";
                break;
            case 7: {
                int i = name.indexOf(':');
                String f = i > 0 ? name.substring(0, i).trim().toLowerCase() : "";
                String nm = i > 0 ? name.substring(i + 1).trim() : "";
                if (!f.matches("[a-z0-9_]{1,24}") || nm.isEmpty()) {
                    res = "Factiune invalida sau nume lipsa.";
                    break;
                }
                GarageData.Garage n2 = createGarage(p, GarageData.FACTION, f, nm);
                res = "Garaj de factiune #" + n2.id + " (" + f + ") creat. Membrii au tag-ul fc_" + f + ".";
                break;
            }
            case 8:
                res = setStoreHere(p, id, 4f) ? "Zona de parcare a garajului e aici (raza 4)." : "Garajul nu exista.";
                break;
            case 9: {
                int cnt = addSlotHere(p, id);
                res = cnt < 0 ? "Garajul nu exista." : "Loc de spawn #" + cnt + " adaugat (masina va fi intoarsa cum privesti acum).";
                break;
            }
            case 10: {
                GarageData.Garage g = data.garage(id);
                if (g == null) {
                    res = "Garajul nu exista.";
                    break;
                }
                g.slots.clear();
                g.boatSlots.clear();
                data.setDirty();
                res = "Locurile de spawn (terestre si barci) au fost sterse.";
                break;
            }
            case 11: {
                GarageData.Garage g = data.garage(id);
                if (g == null) {
                    res = "Garajul nu exista.";
                    break;
                }
                g.boatSlots.add(new double[] {p.getX(), p.getY(), p.getZ(), p.getYRot()});
                data.setDirty();
                res = "Punct spawn barca #" + g.boatSlots.size() + " adaugat (pune-l pe apa, barca va fi intoarsa cum privesti).";
                break;
            }
            default:
                break;
        }
        if (res != null) p.displayClientMessage(Component.literal("\u00a7b[Garaje] \u00a77" + res), false);
        syncAll(p.server);
    }

    private static GarageData.Garage create(CommandContext<CommandSourceStack> c, String type, String faction, String name) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        return createGarage(c.getSource().getPlayerOrException(), type, faction, name);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> garajCommand() {
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
                int cnt = addSlotHere(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "id"));
                if (cnt < 0) return fail(c, "Garajul nu exista.");
                return ok(c, "Loc de parcare #" + cnt + " adaugat (masina va fi intoarsa cum privesti acum).");
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
                if (!moveGarage(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "id"))) return fail(c, "Garajul nu exista.");
                return ok(c, "Markerul garajului a fost mutat aici.");
            })))
            .then(Commands.literal("sterge").then(Commands.literal("toate").executes(c -> {
                return ok(c, deleteAllGarages(c.getSource().getServer()) + " garaje sterse (masinile raman ale jucatorilor).");
            })).then(Commands.argument("id", IntegerArgumentType.integer(1)).executes(c -> {
                if (!deleteGarage(c.getSource().getServer(), IntegerArgumentType.getInteger(c, "id"))) return fail(c, "Garajul nu exista.");
                return ok(c, "Garaj sters (masinile raman ale jucatorilor).");
            })))
            .then(Commands.literal("lista").executes(c -> {
                List<GarageData.Garage> gs = GarageData.get(c.getSource().getServer()).garages();
                if (gs.isEmpty()) return fail(c, "Nu exista garaje. Creeaza unul: /garaj creeaza public <nume>");
                for (GarageData.Garage g : gs) {
                    String line = "#" + g.id + " " + g.name + " [" + g.type + (g.faction.isEmpty() ? "" : " " + g.faction) + "] "
                        + (int) g.mx + " " + (int) g.my + " " + (int) g.mz + " | parcare: " + (g.hasStore ? "da" : "nu") + " | locuri: " + g.slots.size();
                    c.getSource().sendSuccess(() -> Component.literal(line), false);
                }
                return 1;
            }));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> stationCommand() {
        return Commands.literal("benzinarie").requires(s -> s.hasPermission(2))
            .then(Commands.literal("creeaza").then(Commands.argument("nume", StringArgumentType.greedyString()).executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                GarageData data = GarageData.get(c.getSource().getServer());
                GarageData.Station st = data.addStation();
                st.name = StringArgumentType.getString(c, "nume");
                st.dim = p.level().dimension().location().toString();
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
                ServerPlayer p = c.getSource().getPlayerOrException();
                GarageData data = GarageData.get(c.getSource().getServer());
                GarageData.Station st = data.station(IntegerArgumentType.getInteger(c, "id"));
                if (st == null) return fail(c, "Benzinaria nu exista.");
                st.dim = p.level().dimension().location().toString();
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
                for (GarageData.Station st : l) c.getSource().sendSuccess(() -> Component.literal("#" + st.id + " " + st.name + " " + (int) st.x + " " + (int) st.y + " " + (int) st.z + " raza " + st.radius), false);
                return 1;
            }));
    }

    private static int setStore(CommandContext<CommandSourceStack> c, float r) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (!setStoreHere(c.getSource().getPlayerOrException(), IntegerArgumentType.getInteger(c, "id"), r)) return fail(c, "Garajul nu exista.");
        return ok(c, "Zona de parcare a garajului e aici (raza " + r + ").");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> carsCommand() {
        return Commands.literal("fccars").requires(s -> s.hasPermission(2))
            .then(Commands.literal("give").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("type", StringArgumentType.string())
                    .then(Commands.argument("color", IntegerArgumentType.integer(0, 0xFFFFFF))
                        .then(Commands.argument("engine", StringArgumentType.string())
                            .then(Commands.argument("wheels", StringArgumentType.string())
                                .then(Commands.argument("plate", StringArgumentType.string())
                                    .then(Commands.argument("name", StringArgumentType.greedyString()).executes(c -> {
                                        ServerPlayer p = EntityArgument.getPlayer(c, "player");
                                        GarageData.Car car = give(c.getSource().getServer(), p.getUUID(), "", StringArgumentType.getString(c, "type"),
                                            IntegerArgumentType.getInteger(c, "color"), StringArgumentType.getString(c, "engine"),
                                            StringArgumentType.getString(c, "wheels"), StringArgumentType.getString(c, "plate"), StringArgumentType.getString(c, "name"));
                                        if (car == null) return fail(c, "Vehicul necunoscut.");
                                        giveKey(p, car, false);
                                        p.displayClientMessage(Component.literal(car.name + " (" + car.plate + ") te asteapta in orice garaj. Ai primit cheia."), false);
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
            .then(Commands.literal("sell").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("car", IntegerArgumentType.integer(1))
                    .then(Commands.argument("type", StringArgumentType.string())
                        .then(Commands.argument("plate", StringArgumentType.string())
                            .then(Commands.argument("engine", StringArgumentType.string())
                                .then(Commands.argument("wheels", StringArgumentType.string())
                                    .then(Commands.argument("hp", IntegerArgumentType.integer(0, 100)).executes(CarsServer::sell)))))))))
            .then(Commands.literal("transfer").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("buyer", EntityArgument.player())
                    .then(Commands.argument("car", IntegerArgumentType.integer(1))
                        .then(Commands.argument("type", StringArgumentType.string())
                            .then(Commands.argument("plate", StringArgumentType.string())
                                .then(Commands.argument("engine", StringArgumentType.string())
                                    .then(Commands.argument("wheels", StringArgumentType.string())
                                        .then(Commands.argument("hp", IntegerArgumentType.integer(0, 100))
                                            .then(Commands.argument("max", IntegerArgumentType.integer(0, 1000)).executes(CarsServer::transfer)))))))))))
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
                        ServerPlayer officer = EntityArgument.getPlayer(c, "player");
                        UUID id;
                        try {
                            id = UUID.fromString(StringArgumentType.getString(c, "entity"));
                        } catch (IllegalArgumentException ex) {
                            return fail(c, "UUID invalid.");
                        }
                        Entity v = officer.serverLevel().getEntity(id);
                        if (v == null || v.distanceTo(officer) > 10 || v instanceof net.minecraft.world.entity.player.Player) return fail(c, "Vehiculul nu e aproape.");
                        ResourceLocation key = ForgeRegistries.ENTITY_TYPES.getKey(v.getType());
                        GarageData data = GarageData.get(c.getSource().getServer());
                        GarageData.Car car = v.getPersistentData().contains(TAG) ? data.get(v.getPersistentData().getInt(TAG)) : null;
                        if (car == null && (key == null || !CarsCompat.MOD.equals(key.getNamespace()))) return fail(c, "Nu e un vehicul.");
                        if (car != null) {
                            storeEntity(v, car);
                            car.pound = true;
                            car.poundReason = StringArgumentType.getString(c, "reason");
                            data.setDirty();
                            ServerPlayer owner = car.owner == null ? null : c.getSource().getServer().getPlayerList().getPlayer(car.owner);
                            if (owner != null) owner.displayClientMessage(Component.literal("Masina ta " + car.name + " (" + car.plate + ") a fost ridicata la pound: " + car.poundReason), false);
                        } else {
                            v.ejectPassengers();
                            v.discard(); // vehicul fara acte (nu e din garaj): se ridica definitiv
                        }
                        officer.displayClientMessage(Component.literal("Vehicul ridicat la pound."), true);
                        return 1;
                    })))))
            .then(mechCommands())
            .then(mechListCommand())
            // scoatere de la pound dupa plata (pluginul ia taxa prin Vault)
            .then(Commands.literal("unpound").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("car", IntegerArgumentType.integer(1)).then(Commands.argument("garage", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
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
                    Entity e = spawn(p.serverLevel(), g, car);
                    data.setDirty();
                    p.displayClientMessage(Component.literal(e != null ? car.name + " te asteapta in parcarea pound-ului."
                        : car.name + " a fost eliberata; locurile sunt ocupate, o poti scoate din orice garaj."), true);
                    openMenu(p, data, g, true, "");
                    return 1;
                })))))
            // benzinarie: pluginul a luat deja banii; daca intoarce 0, pluginul ii returneaza
            .then(Commands.literal("fuel").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("station", IntegerArgumentType.integer(1))
                    .then(Commands.argument("entity", IntegerArgumentType.integer())
                        .then(Commands.argument("units", IntegerArgumentType.integer(1, 1_000_000)).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            GarageData data = GarageData.get(c.getSource().getServer());
                            if (stationNear(p, data, IntegerArgumentType.getInteger(c, "station")) == null) return fail(c, "Jucatorul nu e la benzinarie.");
                            Entity v = p.level().getEntity(IntegerArgumentType.getInteger(c, "entity"));
                            if (v == null || v.distanceTo(p) > 10 || !addFuel(v, IntegerArgumentType.getInteger(c, "units"))) return fail(c, "Vehiculul nu poate fi alimentat.");
                            return 1;
                        }))))))
            // benzinarie 2.0: alimentare pe durata, cu raport catre plugin (vezi FuelPumps)
            .then(Commands.literal("pumpblocks").then(Commands.argument("ids", StringArgumentType.greedyString()).executes(c -> {
                java.util.List<String> ids = new java.util.ArrayList<>();
                for (String s : StringArgumentType.getString(c, "ids").split(",")) ids.add(s);
                FuelPumps.setPumpBlocks(ids);
                return 1;
            })))
            .then(Commands.literal("pumpid").executes(c -> {
                ServerPlayer p = c.getSource().getPlayerOrException();
                String id = FuelPumps.lookingAt(p);
                c.getSource().sendSuccess(() -> net.minecraft.network.chat.Component.literal(
                    id == null ? "Nu te uiti la niciun bloc." : "Bloc: " + id), false);
                return 1;
            }))
            .then(Commands.literal("fuelstart").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("units", IntegerArgumentType.integer(1, 1_000_000))
                    .then(Commands.argument("tx", IntegerArgumentType.integer(1))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 120)).executes(c -> {
                            ServerPlayer p = EntityArgument.getPlayer(c, "player");
                            if (!FuelPumps.start(p, IntegerArgumentType.getInteger(c, "units"),
                                IntegerArgumentType.getInteger(c, "tx"), IntegerArgumentType.getInteger(c, "seconds"))) {
                                return fail(c, "Pompa nu poate porni alimentarea.");
                            }
                            return 1;
                        }))))))
            // cheie noua pentru o masina (cele vechi nu mai merg)
            .then(Commands.literal("cheie").then(Commands.argument("player", EntityArgument.player())
                .then(Commands.argument("car", IntegerArgumentType.integer(1)).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
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
        CompoundTag init = e.saveWithoutId(new CompoundTag());
        init.put("Color", new IntArrayTag(new int[] {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255}));
        VehicleParts.apply(init, engine, wheels);
        e.load(init);
        CompoundTag nbt = strip(e.saveWithoutId(new CompoundTag()));
        if (!engine.equals("-") && !nbt.getBoolean("HasEngine")) {
            LOG.warn("give {}: motorul {} nu a fost preluat de vehicul; il pun direct in date", type, engine);
        }
        VehicleParts.apply(nbt, engine, wheels);
        if (nbt.contains("FuelCapacity")) nbt.putFloat("CurrentFuel", nbt.getFloat("FuelCapacity") * 0.5f);
        e.discard();
        return GarageData.get(server).add(owner, faction, type, name, plate, nbt);
    }

    private static CompoundTag stack(String id) {
        CompoundTag s = new CompoundTag();
        s.putString("id", new ResourceLocation(id).toString());
        s.putByte("Count", (byte) 1);
        return s;
    }
}
