package ro.solarstudios.flowcity.cars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Garajele si masinile (salvate in lumea principala, data/flowcity_garage.dat).
 * Garajul e un punct pe harta (fara blocuri): marker de meniu, zona de parcare si locuri de spawn, ca pe FiveM.
 * Masina pastreaza NBT-ul complet al vehiculului, deci revine exact cum a fost parcata.
 */
public final class GarageData extends SavedData {
    private static final String NAME = "flowcity_garage";

    public static final String PUBLIC = "public";
    public static final String POUND = "pound";
    public static final String FACTION = "factiune";

    public static final class Garage {
        public int id;
        public String name = "Garaj";
        public String type = PUBLIC;
        public String faction = "";
        public String dim = "minecraft:overworld";
        public double mx, my, mz;               // marker meniu
        public boolean hasStore;
        public double sx, sy, sz;               // zona de parcare
        public float radius = 4f;
        public final List<double[]> slots = new ArrayList<>(); // x, y, z, yaw

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", id);
            t.putString("Name", name);
            t.putString("Type", type);
            t.putString("Faction", faction);
            t.putString("Dim", dim);
            t.putDouble("MX", mx);
            t.putDouble("MY", my);
            t.putDouble("MZ", mz);
            t.putBoolean("HasStore", hasStore);
            t.putDouble("SX", sx);
            t.putDouble("SY", sy);
            t.putDouble("SZ", sz);
            t.putFloat("Radius", radius);
            ListTag l = new ListTag();
            for (double[] s : slots) {
                CompoundTag c = new CompoundTag();
                c.putDouble("X", s[0]);
                c.putDouble("Y", s[1]);
                c.putDouble("Z", s[2]);
                c.putDouble("Yaw", s[3]);
                l.add(c);
            }
            t.put("Slots", l);
            return t;
        }

        static Garage load(CompoundTag t) {
            Garage g = new Garage();
            g.id = t.getInt("Id");
            g.name = t.getString("Name");
            g.type = t.getString("Type");
            g.faction = t.getString("Faction");
            g.dim = t.getString("Dim");
            g.mx = t.getDouble("MX");
            g.my = t.getDouble("MY");
            g.mz = t.getDouble("MZ");
            g.hasStore = t.getBoolean("HasStore");
            g.sx = t.getDouble("SX");
            g.sy = t.getDouble("SY");
            g.sz = t.getDouble("SZ");
            g.radius = t.contains("Radius") ? t.getFloat("Radius") : 4f;
            ListTag l = t.getList("Slots", Tag.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundTag c = l.getCompound(i);
                g.slots.add(new double[] {c.getDouble("X"), c.getDouble("Y"), c.getDouble("Z"), c.getDouble("Yaw")});
            }
            return g;
        }
    }

    public static final class Car {
        public int id;
        /** Proprietarul (null = masina de factiune). */
        public UUID owner;
        public String faction = "";
        public String type = "";
        public String name = "";
        public String plate = "";
        public CompoundTag nbt = new CompoundTag();
        /** In lume (scoasa din garaj). */
        public boolean out;
        /** UUID-ul entitatii din lume cand e scoasa (pentru a evita dublurile). */
        public UUID entity;
        /** La pound (ridicata de politie). */
        public boolean pound;
        public String poundReason = "";

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", id);
            if (owner != null) t.putUUID("Owner", owner);
            t.putString("Faction", faction);
            t.putString("Type", type);
            t.putString("Name", name);
            t.putString("Plate", plate);
            t.put("Nbt", nbt);
            t.putBoolean("Out", out);
            if (entity != null) t.putUUID("Entity", entity);
            t.putBoolean("Pound", pound);
            t.putString("PoundReason", poundReason);
            return t;
        }

        static Car load(CompoundTag t) {
            Car c = new Car();
            c.id = t.getInt("Id");
            if (t.hasUUID("Owner")) c.owner = t.getUUID("Owner");
            c.faction = t.getString("Faction");
            c.type = t.getString("Type");
            c.name = t.getString("Name");
            c.plate = t.getString("Plate");
            c.nbt = t.getCompound("Nbt");
            c.out = t.getBoolean("Out");
            if (t.hasUUID("Entity")) c.entity = t.getUUID("Entity");
            c.pound = t.getBoolean("Pound");
            c.poundReason = t.getString("PoundReason");
            return c;
        }
    }

    private final Map<Integer, Car> cars = new HashMap<>();
    private final Map<Integer, Garage> garages = new LinkedHashMap<>();
    private int nextId = 1;
    private int nextGarage = 1;

    public GarageData() {
        super(NAME);
    }

    public static GarageData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(GarageData::new, NAME);
    }

    @Override
    public void load(CompoundTag tag) {
        GarageData d = this;
        d.cars.clear();
        d.garages.clear();
        d.nextId = Math.max(1, tag.getInt("NextId"));
        d.nextGarage = Math.max(1, tag.getInt("NextGarage"));
        ListTag list = tag.getList("Cars", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Car c = Car.load(list.getCompound(i));
            d.cars.put(c.id, c);
            d.nextId = Math.max(d.nextId, c.id + 1);
        }
        ListTag gl = tag.getList("Garages", Tag.TAG_COMPOUND);
        for (int i = 0; i < gl.size(); i++) {
            Garage g = Garage.load(gl.getCompound(i));
            d.garages.put(g.id, g);
            d.nextGarage = Math.max(d.nextGarage, g.id + 1);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("NextId", nextId);
        tag.putInt("NextGarage", nextGarage);
        ListTag list = new ListTag();
        for (Car c : cars.values()) list.add(c.save());
        tag.put("Cars", list);
        ListTag gl = new ListTag();
        for (Garage g : garages.values()) gl.add(g.save());
        tag.put("Garages", gl);
        return tag;
    }

    // ------------------------------------------------------------------ masini

    public Car add(UUID owner, String faction, String type, String name, String plate, CompoundTag nbt) {
        Car c = new Car();
        c.id = nextId++;
        c.owner = owner;
        c.faction = faction == null ? "" : faction;
        c.type = type;
        c.name = name;
        c.plate = plate;
        c.nbt = nbt;
        cars.put(c.id, c);
        setDirty();
        return c;
    }

    public Car get(int id) {
        return cars.get(id);
    }

    public void remove(int id) {
        cars.remove(id);
        setDirty();
    }

    public List<Car> all() {
        return new ArrayList<>(cars.values());
    }

    public List<Car> of(UUID owner) {
        List<Car> l = new ArrayList<>();
        for (Car c : cars.values()) if (owner.equals(c.owner)) l.add(c);
        l.sort((a, b) -> Integer.compare(a.id, b.id));
        return l;
    }

    public List<Car> ofFaction(String faction) {
        List<Car> l = new ArrayList<>();
        for (Car c : cars.values()) if (c.owner == null && faction.equals(c.faction)) l.add(c);
        l.sort((a, b) -> Integer.compare(a.id, b.id));
        return l;
    }

    // ------------------------------------------------------------------ garaje

    public Garage addGarage() {
        Garage g = new Garage();
        g.id = nextGarage++;
        garages.put(g.id, g);
        setDirty();
        return g;
    }

    public Garage garage(int id) {
        return garages.get(id);
    }

    public void removeGarage(int id) {
        garages.remove(id);
        setDirty();
    }

    public List<Garage> garages() {
        return new ArrayList<>(garages.values());
    }
}
