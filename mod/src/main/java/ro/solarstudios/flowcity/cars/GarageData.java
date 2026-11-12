package ro.solarstudios.flowcity.cars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Masinile jucatorilor (salvate in lumea principala, fisierul data/flowcity_garage.dat).
 * Fiecare masina pastreaza NBT-ul complet al vehiculului (culoare, benzina, viata, motor, roti), deci la scoatere
 * revine exact cum a fost parcata.
 */
public final class GarageData extends SavedData {
    private static final String NAME = "flowcity_garage";

    public static final class Car {
        public int id;
        public UUID owner;
        public String type = "";
        public String name = "";
        public String plate = "";
        public CompoundTag nbt = new CompoundTag();
        /** In lume (scoasa din garaj). */
        public boolean out;
        /** UUID-ul entitatii din lume cand e scoasa (pentru a evita dublurile). */
        public UUID entity;

        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("Id", id);
            t.putUUID("Owner", owner);
            t.putString("Type", type);
            t.putString("Name", name);
            t.putString("Plate", plate);
            t.put("Nbt", nbt);
            t.putBoolean("Out", out);
            if (entity != null) t.putUUID("Entity", entity);
            return t;
        }

        static Car load(CompoundTag t) {
            Car c = new Car();
            c.id = t.getInt("Id");
            c.owner = t.getUUID("Owner");
            c.type = t.getString("Type");
            c.name = t.getString("Name");
            c.plate = t.getString("Plate");
            c.nbt = t.getCompound("Nbt");
            c.out = t.getBoolean("Out");
            if (t.hasUUID("Entity")) c.entity = t.getUUID("Entity");
            return c;
        }
    }

    private final Map<Integer, Car> cars = new HashMap<>();
    private int nextId = 1;

    public static GarageData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(GarageData::load, GarageData::new, NAME);
    }

    private static GarageData load(CompoundTag tag) {
        GarageData d = new GarageData();
        d.nextId = Math.max(1, tag.getInt("NextId"));
        ListTag list = tag.getList("Cars", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            Car c = Car.load(list.getCompound(i));
            d.cars.put(c.id, c);
            d.nextId = Math.max(d.nextId, c.id + 1);
        }
        return d;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        tag.putInt("NextId", nextId);
        ListTag list = new ListTag();
        for (Car c : cars.values()) list.add(c.save());
        tag.put("Cars", list);
        return tag;
    }

    public Car add(UUID owner, String type, String name, String plate, CompoundTag nbt) {
        Car c = new Car();
        c.id = nextId++;
        c.owner = owner;
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

    public List<Car> of(UUID owner) {
        List<Car> l = new ArrayList<>();
        for (Car c : cars.values()) if (c.owner.equals(owner)) l.add(c);
        l.sort((a, b) -> Integer.compare(a.id, b.id));
        return l;
    }
}
