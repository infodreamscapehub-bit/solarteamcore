package ro.solarstudios.flowcity.cars;

import java.util.HashMap;
import java.util.Map;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;

/**
 * Motor si roti pe formatul real al modului de vehicule 0.45.x (1.16): NBT "HasEngine"/"EngineTier"(ordinal)/"HasWheels"/"WheelType"(ordinal).
 * Pastram si "EngineStack"/"WheelStack" (formatul vehiculelor noi) ca date proprii, ca sa nu pierdem informatia despre tipul de motor.
 */
public final class VehicleParts {
    private VehicleParts() {}

    public static final String[] TIERS = {"wood", "stone", "iron", "gold", "diamond"};
    public static final String[] WHEELS = {"standard", "sports", "racing", "off_road", "snow", "all_terrain", "plastic"};
    private static final Map<String, String> FAMILY = new HashMap<>();

    private static CompoundNBT stack(String id) {
        CompoundNBT s = new CompoundNBT();
        s.putString("id", new ResourceLocation(id).toString());
        s.putByte("Count", (byte) 1);
        return s;
    }

    /** Pune motorul/rotile pe NBT in ambele formate. "-" sau gol = nu schimba. */
    public static void apply(CompoundNBT nbt, String engine, String wheels) {
        if (engine != null && !engine.isEmpty() && !engine.equals("-")) {
            nbt.put("EngineStack", stack(engine));
            String path = new ResourceLocation(engine).getPath();
            int tier = 0;
            for (int i = 0; i < TIERS.length; i++) if (path.startsWith(TIERS[i] + "_")) tier = i;
            nbt.putBoolean("HasEngine", true);
            nbt.putInt("EngineTier", tier);
        }
        if (wheels != null && !wheels.isEmpty() && !wheels.equals("-")) {
            nbt.put("WheelStack", stack(wheels));
            String path = new ResourceLocation(wheels).getPath();
            for (int i = 0; i < WHEELS.length; i++) {
                if (path.equals(WHEELS[i] + "_wheel")) {
                    nbt.putBoolean("HasWheels", true);
                    nbt.putInt("WheelType", i);
                }
            }
        }
    }

    /** Familia motorului vehiculului (small/large/electric) prin getEngineType(); null daca vehiculul nu are motor. */
    public static String family(World w, String type) {
        if (FAMILY.containsKey(type)) return FAMILY.get(type).isEmpty() ? null : FAMILY.get(type);
        String fam = "";
        try {
            EntityType<?> t = EntityType.byString(type).orElse(null);
            Entity e = t == null ? null : t.create(w);
            if (e != null) {
                Object o = e.getClass().getMethod("getEngineType").invoke(e);
                String n = String.valueOf(o);
                if (n.startsWith("SMALL")) fam = "small";
                else if (n.startsWith("LARGE")) fam = "large";
                else if (n.startsWith("ELECTRIC")) fam = "electric";
                e.remove();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // vehicul fara motor
        }
        FAMILY.put(type, fam);
        return fam.isEmpty() ? null : fam;
    }

    /** Id-ul motorului montat ("-" daca nu are). */
    public static String engine(World w, String type, CompoundNBT nbt) {
        if (nbt.contains("EngineStack", 10) && !nbt.getCompound("EngineStack").getString("id").isEmpty()) {
            return nbt.getCompound("EngineStack").getString("id");
        }
        if (!nbt.getBoolean("HasEngine")) return "-";
        String fam = family(w, type);
        if (fam == null) return "-";
        int tier = Math.max(0, Math.min(TIERS.length - 1, nbt.getInt("EngineTier")));
        return "vehicle:" + TIERS[tier] + "_" + fam + "_engine";
    }

    /** Id-ul rotilor montate ("-" daca nu are). */
    public static String wheels(CompoundNBT nbt) {
        if (nbt.contains("WheelStack", 10) && !nbt.getCompound("WheelStack").getString("id").isEmpty()) {
            return nbt.getCompound("WheelStack").getString("id");
        }
        if (!nbt.getBoolean("HasWheels")) return "-";
        int w = nbt.getInt("WheelType");
        return "vehicle:" + WHEELS[Math.max(0, Math.min(WHEELS.length - 1, w))] + "_wheel";
    }
}
