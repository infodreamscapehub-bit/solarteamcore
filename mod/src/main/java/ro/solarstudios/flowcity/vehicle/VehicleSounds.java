package ro.solarstudios.flowcity.vehicle;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import ro.solarstudios.flowcity.FlowCity;

/** Sunetele masinilor (doar client): motor cu turatie dupa viteza, claxon, sirena, bip de incuiere, pompa. */
public final class VehicleSounds {
    private VehicleSounds() {}

    static final SoundEvent ENGINE = ev("vehicle.engine");
    static final SoundEvent HORN = ev("vehicle.horn");
    static final SoundEvent SIREN = ev("vehicle.siren");
    static final SoundEvent LOCK = ev("vehicle.car_lock");
    static final SoundEvent PUMP = ev("vehicle.fuel_pump");

    private static SoundEvent ev(String name) {
        return SoundEvent.createVariableRangeEvent(new ResourceLocation(FlowCity.MODID, name));
    }

    private static final Map<Integer, Loop> ENGINES = new HashMap<>();
    private static final Map<Integer, Loop> HORNS = new HashMap<>();
    private static final Map<Integer, Loop> SIRENS = new HashMap<>();

    public static void lockBeep(Entity car) {
        Minecraft.getInstance().getSoundManager().play(new SimpleSoundInstance(LOCK, SoundSource.NEUTRAL, 0.7f, 1f,
            RandomSource.create(), car.getX(), car.getY(), car.getZ()));
    }

    public static void pump() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(PUMP, 1f, 0.6f));
    }

    /** Apelat la fiecare tick de client: porneste / opreste buclele pentru masinile din jur. */
    public static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            ENGINES.clear();
            HORNS.clear();
            SIRENS.clear();
            return;
        }
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof CarEntity car) || car.distanceToSqr(mc.player) > 64 * 64) continue;
            if (car.getControllingPassenger() != null && car.getFuel() > 0f) ensure(mc, ENGINES, car, 0);
            if (car.flag(CarEntity.F_HORN)) ensure(mc, HORNS, car, 1);
            if (car.flag(CarEntity.F_SIREN)) ensure(mc, SIRENS, car, 2);
        }
        clean(ENGINES);
        clean(HORNS);
        clean(SIRENS);
    }

    private static void ensure(Minecraft mc, Map<Integer, Loop> map, CarEntity car, int kind) {
        Loop l = map.get(car.getId());
        if (l != null && !l.isStopped()) return;
        l = new Loop(car, kind);
        map.put(car.getId(), l);
        mc.getSoundManager().play(l);
    }

    private static void clean(Map<Integer, Loop> map) {
        for (Iterator<Loop> it = map.values().iterator(); it.hasNext(); ) if (it.next().isStopped()) it.remove();
    }

    /** O bucla legata de o masina: urmareste pozitia si se opreste singura. */
    static final class Loop extends AbstractTickableSoundInstance {
        private final CarEntity car;
        private final int kind;
        private float rpm;

        Loop(CarEntity car, int kind) {
            super(kind == 0 ? ENGINE : (kind == 1 ? HORN : SIREN), SoundSource.NEUTRAL, SoundInstance.createUnseededRandom());
            this.car = car;
            this.kind = kind;
            this.looping = true;
            this.delay = 0;
            this.volume = kind == 0 ? 0.01f : (kind == 1 ? 0.9f : 1.6f);
            this.pitch = 1f;
            this.x = car.getX();
            this.y = car.getY();
            this.z = car.getZ();
        }

        @Override
        public void tick() {
            if (car.isRemoved()) {
                stop();
                return;
            }
            this.x = car.getX();
            this.y = car.getY() + 0.6;
            this.z = car.getZ();
            if (kind == 1) {
                if (!car.flag(CarEntity.F_HORN)) stop();
                return;
            }
            if (kind == 2) {
                if (!car.flag(CarEntity.F_SIREN)) stop();
                return;
            }
            // motor: merge doar cu sofer si benzina; turatia urca lin dupa viteza
            boolean running = car.getControllingPassenger() != null && car.getFuel() > 0f;
            double v = Math.hypot(car.getX() - car.xo, car.getZ() - car.zo);
            float target = running ? (float) Math.min(1.0, v / car.maxForward()) : 0f;
            rpm += (target - rpm) * 0.15f;
            this.pitch = 0.55f + rpm * 1.15f;
            this.volume = running ? 0.35f + rpm * 0.45f : Math.max(0f, this.volume - 0.05f);
            if (!running && this.volume <= 0.01f) stop();
        }
    }
}
