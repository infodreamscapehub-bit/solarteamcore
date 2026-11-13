package ro.solarstudios.flowcity.cars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.ListNBT;
import net.minecraft.nbt.INBT;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.storage.WorldSavedData;

/**
 * Garajele si masinile (salvate in lumea principala, data/flowcity_garage.dat).
 * Garajul e un punct pe harta (fara blocuri): marker de meniu, zona de parcare si locuri de spawn, ca pe FiveM.
 * Masina pastreaza NBT-ul complet al vehiculului, deci revine exact cum a fost parcata.
 */
public final class GarageData extends WorldSavedData {
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

        CompoundNBT save() {
            CompoundNBT t = new CompoundNBT();
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
            ListNBT l = new ListNBT();
            for (double[] s : slots) {
                CompoundNBT c = new CompoundNBT();
                c.putDouble("X", s[0]);
                c.putDouble("Y", s[1]);
                c.putDouble("Z", s[2]);
                c.putDouble("Yaw", s[3]);
                l.add(c);
            }
            t.put("Slots", l);
            return t;
        }

        static Garage load(CompoundNBT t) {
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
            ListNBT l = t.getList("Slots", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND);
            for (int i = 0; i < l.size(); i++) {
                CompoundNBT c = l.getCompound(i);
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
        public CompoundNBT nbt = new CompoundNBT();
        /** In lume (scoasa din garaj). */
        public boolean out;
        /** UUID-ul entitatii din lume cand e scoasa (pentru a evita dublurile). */
        public UUID entity;
        /** La pound (ridicata de politie). */
        public boolean pound;
        public String poundReason = "";
        /** Incuiata (nu se poate urca nimeni). */
        public boolean locked;
        /** Generatia cheii: cand se face o cheie noua, cele vechi nu mai merg. */
        public int keyGen;

        CompoundNBT save() {
            CompoundNBT t = new CompoundNBT();
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
            t.putBoolean("Locked", locked);
            t.putInt("KeyGen", keyGen);
            return t;
        }

        static Car load(CompoundNBT t) {
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
            c.locked = t.getBoolean("Locked");
            c.keyGen = t.getInt("KeyGen");
            return c;
        }
    }

    /** Benzinarie: zona in care, din masina, apesi E si alimentezi. */
    public static final class Station {
        public int id;
        public String name = "Benzinarie";
        public String dim = "minecraft:overworld";
        public double x, y, z;
        public float radius = 6f;

        CompoundNBT save() {
            CompoundNBT t = new CompoundNBT();
            t.putInt("Id", id);
            t.putString("Name", name);
            t.putString("Dim", dim);
            t.putDouble("X", x);
            t.putDouble("Y", y);
            t.putDouble("Z", z);
            t.putFloat("Radius", radius);
            return t;
        }

        static Station load(CompoundNBT t) {
            Station s = new Station();
            s.id = t.getInt("Id");
            s.name = t.getString("Name");
            s.dim = t.getString("Dim");
            s.x = t.getDouble("X");
            s.y = t.getDouble("Y");
            s.z = t.getDouble("Z");
            s.radius = t.contains("Radius") ? t.getFloat("Radius") : 6f;
            return s;
        }
    }

    private final Map<Integer, Station> stations = new LinkedHashMap<>();
    private int nextStation = 1;
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
    public void load(CompoundNBT tag) {
        GarageData d = this;
        d.cars.clear();
        d.garages.clear();
        d.nextId = Math.max(1, tag.getInt("NextId"));
        d.nextGarage = Math.max(1, tag.getInt("NextGarage"));
        d.stations.clear();
        d.nextStation = Math.max(1, tag.getInt("NextStation"));
        ListNBT sl = tag.getList("Stations", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < sl.size(); i++) {
            Station st = Station.load(sl.getCompound(i));
            d.stations.put(st.id, st);
            d.nextStation = Math.max(d.nextStation, st.id + 1);
        }
        ListNBT list = tag.getList("Cars", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Car c = Car.load(list.getCompound(i));
            d.cars.put(c.id, c);
            d.nextId = Math.max(d.nextId, c.id + 1);
        }
        ListNBT gl = tag.getList("Garages", net.minecraftforge.common.util.Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < gl.size(); i++) {
            Garage g = Garage.load(gl.getCompound(i));
            d.garages.put(g.id, g);
            d.nextGarage = Math.max(d.nextGarage, g.id + 1);
        }
    }

    @Override
    public CompoundNBT save(CompoundNBT tag) {
        tag.putInt("NextId", nextId);
        tag.putInt("NextGarage", nextGarage);
        ListNBT list = new ListNBT();
        for (Car c : cars.values()) list.add(c.save());
        tag.put("Cars", list);
        ListNBT gl = new ListNBT();
        for (Garage g : garages.values()) gl.add(g.save());
        tag.put("Garages", gl);
        tag.putInt("NextStation", nextStation);
        ListNBT sl = new ListNBT();
        for (Station st : stations.values()) sl.add(st.save());
        tag.put("Stations", sl);
        return tag;
    }

    // ------------------------------------------------------------------ masini

    public Car add(UUID owner, String faction, String type, String name, String plate, CompoundNBT nbt) {
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

    // ------------------------------------------------------------------ benzinarii

    public Station addStation() {
        Station st = new Station();
        st.id = nextStation++;
        stations.put(st.id, st);
        setDirty();
        return st;
    }

    public Station station(int id) {
        return stations.get(id);
    }

    public void removeStation(int id) {
        stations.remove(id);
        setDirty();
    }

    public List<Station> stations() {
        return new ArrayList<>(stations.values());
    }
}
