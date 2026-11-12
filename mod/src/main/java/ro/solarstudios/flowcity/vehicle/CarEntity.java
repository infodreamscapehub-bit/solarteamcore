package ro.solarstudios.flowcity.vehicle;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import ro.solarstudios.flowcity.content.ModContent;

/**
 * Masina FlowCity. Se conduce ca o barca: clientul soferului simuleaza miscarea, serverul o accepta (pachetul vanilla
 * "move vehicle"). Benzina, incuierea, farurile, sirena si locurile sunt pe server si se sincronizeaza la toti.
 * Locuri: 0 = sofer (stanga fata), 1 = dreapta fata, 2-3 = spate.
 */
public class CarEntity extends Entity {
    private static final EntityDataAccessor<Integer> COLOR = SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> VARIANT = SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> FUEL = SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Byte> FLAGS = SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.BYTE);
    private static final EntityDataAccessor<String> SEAT_MAP = SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.STRING);

    public static final int F_LIGHTS = 1;
    public static final int F_BEACON = 2;   // girofar (doar lumini)
    public static final int F_SIREN = 4;    // sunet sirena
    public static final int F_HORN = 8;
    public static final int F_LOCKED = 16;

    public static final int V_SEDAN = 0;
    public static final int V_POLICE = 1;
    public static final int V_TAXI = 2;
    public static final String[] VARIANT_NAMES = {"Sedan", "Politie", "Taxi"};

    /** Viteze in blocuri/tick (1.0 = 72 km/h). */
    public static final double MAX_FORWARD = 1.35;
    public static final double MAX_REVERSE = 0.35;
    private static final double ACCEL = 0.022;
    private static final double BRAKE = 0.06;
    private static final double COAST = 0.007;
    public static final int SEATS = 4;
    public static final float TANK = 50f;
    /** Consum: litri pe bloc parcurs, plus mers in gol (litri pe tick). */
    private static final float PER_BLOCK = 1f / 260f;
    private static final float IDLE = 0.0004f;

    private boolean inLeft;
    private boolean inRight;
    private boolean inUp;
    private boolean inDown;
    private boolean inBrake;
    private double speed;

    private int lerpSteps;
    private double lerpX;
    private double lerpY;
    private double lerpZ;
    private double lerpYRot;
    private double lerpXRot;

    private UUID owner;
    private UUID keyId = UUID.randomUUID();
    private final Map<Integer, Integer> seats = new HashMap<>();   // entityId -> loc (server)
    private final SimpleContainer trunk = new SimpleContainer(27) {
        @Override
        public boolean stillValid(Player p) {
            return !CarEntity.this.isRemoved() && p.distanceToSqr(CarEntity.this) < 64;
        }
    };
    private double lastX = Double.NaN;
    private double lastZ;
    /** Client: ticks pentru clipitul farurilor la incuiere. */
    public int flash;

    public CarEntity(EntityType<? extends CarEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(COLOR, 0xE8EEF9);
        this.entityData.define(VARIANT, V_SEDAN);
        this.entityData.define(FUEL, 30f);
        this.entityData.define(FLAGS, (byte) 0);
        this.entityData.define(SEAT_MAP, "");
    }

    // ------------------------------------------------------------------ stare

    public int getColor() {
        return this.entityData.get(COLOR);
    }

    public void setColor(int rgb) {
        this.entityData.set(COLOR, rgb & 0xFFFFFF);
    }

    public int getVariant() {
        return this.entityData.get(VARIANT);
    }

    public void setVariant(int v) {
        this.entityData.set(VARIANT, Mth.clamp(v, 0, VARIANT_NAMES.length - 1));
    }

    public boolean hasSiren() {
        return getVariant() == V_POLICE;
    }

    public float getFuel() {
        return this.entityData.get(FUEL);
    }

    public void setFuel(float f) {
        this.entityData.set(FUEL, Mth.clamp(f, 0f, TANK));
    }

    public boolean flag(int f) {
        return (this.entityData.get(FLAGS) & f) != 0;
    }

    private void setFlag(int f, boolean on) {
        byte b = this.entityData.get(FLAGS);
        this.entityData.set(FLAGS, (byte) (on ? b | f : b & ~f));
    }

    public UUID getKeyId() {
        return keyId;
    }

    public void setOwner(UUID owner) {
        this.owner = owner;
    }

    public String displayName() {
        return VARIANT_NAMES[Mth.clamp(getVariant(), 0, VARIANT_NAMES.length - 1)];
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Color")) setColor(tag.getInt("Color"));
        if (tag.contains("Variant")) setVariant(tag.getInt("Variant"));
        if (tag.contains("Fuel")) setFuel(tag.getFloat("Fuel"));
        if (tag.hasUUID("Owner")) owner = tag.getUUID("Owner");
        if (tag.hasUUID("KeyId")) keyId = tag.getUUID("KeyId");
        setFlag(F_LOCKED, tag.getBoolean("Locked"));
        setFlag(F_LIGHTS, tag.getBoolean("Lights"));
        if (tag.contains("Trunk")) trunk.fromTag(tag.getList("Trunk", 10));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Color", getColor());
        tag.putInt("Variant", getVariant());
        tag.putFloat("Fuel", getFuel());
        if (owner != null) tag.putUUID("Owner", owner);
        tag.putUUID("KeyId", keyId);
        tag.putBoolean("Locked", flag(F_LOCKED));
        tag.putBoolean("Lights", flag(F_LIGHTS));
        ListTag items = trunk.createTag();
        tag.put("Trunk", items);
    }

    /** Apelat de client din tick: tastele soferului. */
    public void setInput(boolean left, boolean right, boolean up, boolean down, boolean brake) {
        this.inLeft = left;
        this.inRight = right;
        this.inUp = up;
        this.inDown = down;
        this.inBrake = brake;
    }

    public boolean braking() {
        return inBrake || (inDown && speed > 0.02) || (inUp && speed < -0.02);
    }

    /** Viteza curenta in km/h (valoare absoluta, pentru HUD). */
    public double speedKmh() {
        return Math.abs(speed) * 72.0;
    }

    public double rawSpeed() {
        return speed;
    }

    public boolean reversing() {
        return speed < -0.02;
    }

    // ------------------------------------------------------------------ chei

    public boolean hasKey(Player p) {
        if (p.getAbilities().instabuild) return true;
        for (ItemStack st : p.getInventory().items) if (CarKeyItem.opens(st, keyId)) return true;
        for (ItemStack st : p.getInventory().offhand) if (CarKeyItem.opens(st, keyId)) return true;
        return false;
    }

    public ItemStack makeKey() {
        return CarKeyItem.forCar(keyId, displayName());
    }

    private static void say(Player p, String msg) {
        p.displayClientMessage(Component.literal(msg), true);
    }

    // ------------------------------------------------------------------ coliziuni / interactiune

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public ItemStack getPickResult() {
        return stackFor(getColor(), getVariant());
    }

    public static ItemStack stackFor(int rgb) {
        return stackFor(rgb, V_SEDAN);
    }

    public static ItemStack stackFor(int rgb, int variant) {
        ItemStack st = new ItemStack(ModContent.CAR_ITEM.get());
        st.getOrCreateTag().putInt("Color", rgb & 0xFFFFFF);
        st.getOrCreateTag().putInt("Variant", variant);
        return st;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (this.level().isClientSide) return InteractionResult.SUCCESS;
        if (held.getItem() instanceof CarKeyItem) {
            if (!CarKeyItem.opens(held, keyId)) {
                say(player, "Cheia nu se potriveste.");
                return InteractionResult.CONSUME;
            }
            toggleLock(player);
            return InteractionResult.CONSUME;
        }
        if (held.getItem() instanceof JerryCanItem) {
            float can = JerryCanItem.fuel(held);
            float room = TANK - getFuel();
            float pour = Math.min(can, room);
            if (pour <= 0.05f) {
                say(player, can <= 0 ? "Canistra e goala." : "Rezervorul e plin.");
            } else {
                setFuel(getFuel() + pour);
                JerryCanItem.setFuel(held, can - pour);
                say(player, String.format("Ai turnat %.1f L. Rezervor: %.0f / %.0f L", pour, getFuel(), TANK));
            }
            return InteractionResult.CONSUME;
        }
        if (player.isSecondaryUseActive()) {
            openTrunk(player);
            return InteractionResult.CONSUME;
        }
        if (flag(F_LOCKED) && !hasKey(player)) {
            say(player, "Masina e incuiata.");
            return InteractionResult.CONSUME;
        }
        if (this.getPassengers().size() >= SEATS) return InteractionResult.PASS;
        return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
    }

    private void toggleLock(Player player) {
        boolean lock = !flag(F_LOCKED);
        setFlag(F_LOCKED, lock);
        this.level().broadcastEntityEvent(this, (byte) 10);
        say(player, lock ? "Masina incuiata." : "Masina descuiata.");
    }

    private void openTrunk(Player player) {
        if (flag(F_LOCKED) && !hasKey(player)) {
            say(player, "Portbagajul e incuiat.");
            return;
        }
        player.openMenu(new SimpleMenuProvider((id, inv, p) -> ChestMenu.threeRows(id, inv, trunk), Component.literal("Portbagaj - " + displayName())));
    }

    @Override
    public void handleEntityEvent(byte id) {
        if (id == 10) {
            flash = 18;
            if (this.level().isClientSide) net.minecraftforge.fml.DistExecutor.unsafeRunWhenOn(net.minecraftforge.api.distmarker.Dist.CLIENT, () -> () -> VehicleSounds.lockBeep(this));
        } else {
            super.handleEntityEvent(id);
        }
    }

    /** Actiuni trimise de client (vezi {@link VehicleNet}). Ruleaza pe server. */
    public void handleAction(ServerPlayer p, int action, int arg) {
        boolean driver = getControllingPassenger() == p;
        switch (action) {
            case VehicleNet.HORN:
                if (driver) setFlag(F_HORN, arg != 0);
                break;
            case VehicleNet.LIGHTS:
                if (driver) setFlag(F_LIGHTS, !flag(F_LIGHTS));
                break;
            case VehicleNet.SIREN:
                if (!driver || !hasSiren()) break;
                // oprit -> girofar -> girofar + sirena -> oprit
                if (!flag(F_BEACON)) setFlag(F_BEACON, true);
                else if (!flag(F_SIREN)) setFlag(F_SIREN, true);
                else {
                    setFlag(F_BEACON, false);
                    setFlag(F_SIREN, false);
                }
                break;
            case VehicleNet.LOCK:
                if (hasKey(p)) toggleLock(p);
                else say(p, "Nu ai cheia acestei masini.");
                break;
            case VehicleNet.SEAT:
                if (this.hasPassenger(p)) nextSeat(p);
                break;
            case VehicleNet.TRUNK:
                if (!this.hasPassenger(p)) openTrunk(p);
                break;
            default:
                break;
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) return true;
        // doar jucatorii in creative o pot strica (mai tarziu: garaj / tractare)
        if (source.getEntity() instanceof Player p && p.getAbilities().instabuild) {
            for (int i = 0; i < trunk.getContainerSize(); i++) this.spawnAtLocation(trunk.removeItemNoUpdate(i));
            this.spawnAtLocation(stackFor(getColor(), getVariant()));
            this.discard();
        }
        return true;
    }

    // ------------------------------------------------------------------ locuri

    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().size() < SEATS;
    }

    @Override
    protected void addPassenger(Entity passenger) {
        super.addPassenger(passenger);
        if (this.level().isClientSide) return;
        boolean canDrive = !(passenger instanceof Player pl) || !flag(F_LOCKED) || hasKey(pl);
        int seat = -1;
        if (canDrive && !seats.containsValue(0)) seat = 0;
        for (int s = 1; s < SEATS && seat < 0; s++) if (!seats.containsValue(s)) seat = s;
        seats.put(passenger.getId(), Math.max(0, seat));
        syncSeats();
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (this.level().isClientSide) return;
        seats.remove(passenger.getId());
        if (getControllingPassenger() == null) setFlag(F_HORN, false);
        syncSeats();
    }

    private void nextSeat(ServerPlayer p) {
        int cur = seats.getOrDefault(p.getId(), 0);
        for (int i = 1; i < SEATS; i++) {
            int s = (cur + i) % SEATS;
            if (seats.containsValue(s)) continue;
            if (s == 0 && flag(F_LOCKED) && !hasKey(p)) continue;
            seats.put(p.getId(), s);
            syncSeats();
            return;
        }
        say(p, "Nu e niciun loc liber.");
    }

    private void syncSeats() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, Integer> e : seats.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append(':').append(e.getValue());
        }
        this.entityData.set(SEAT_MAP, sb.toString());
    }

    /** Locul unui pasager (din harta sincronizata). */
    public int seatOf(Entity passenger) {
        String m = this.entityData.get(SEAT_MAP);
        String id = passenger.getId() + ":";
        for (String part : m.split(",")) {
            if (part.startsWith(id)) {
                try {
                    return Integer.parseInt(part.substring(id.length()));
                } catch (NumberFormatException ignored) {
                    return -1;
                }
            }
        }
        return -1;
    }

    @Override
    public LivingEntity getControllingPassenger() {
        for (Entity e : this.getPassengers()) {
            if (seatOf(e) == 0 && e instanceof LivingEntity le) return le;
        }
        return null;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        if (!this.hasPassenger(passenger)) return;
        int seat = seatOf(passenger);
        if (seat < 0) seat = Math.min(SEATS - 1, this.getPassengers().indexOf(passenger));
        double side = (seat % 2 == 0) ? -0.42 : 0.42;     // sofer pe stanga
        double fwd = seat < 2 ? 0.35 : -0.75;
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw);
        double fz = Mth.cos(yaw);
        double rx = -Mth.cos(yaw);
        double rz = -Mth.sin(yaw);
        double x = this.getX() + fx * fwd + rx * side;
        double z = this.getZ() + fz * fwd + rz * side;
        move.accept(passenger, x, this.getY() + 0.28, z);
        passenger.setYRot(passenger.getYRot() + (this.getYRot() - this.yRotO));
        passenger.setYHeadRot(passenger.getYHeadRot() + (this.getYRot() - this.yRotO));
    }

    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        int seat = seatOf(passenger);
        if (seat < 0) seat = this.getPassengers().indexOf(passenger);
        double side = (seat % 2 == 0) ? -1.6 : 1.6;
        float yaw = this.getYRot() * Mth.DEG_TO_RAD;
        return new Vec3(this.getX() - Mth.cos(yaw) * side, this.getY(), this.getZ() - Mth.sin(yaw) * side);
    }

    // ------------------------------------------------------------------ miscare

    @Override
    public void lerpTo(double x, double y, double z, float yRot, float xRot, int steps, boolean teleport) {
        this.lerpX = x;
        this.lerpY = y;
        this.lerpZ = z;
        this.lerpYRot = yRot;
        this.lerpXRot = xRot;
        this.lerpSteps = 10;
    }

    @Override
    public void tick() {
        super.tick();
        if (flash > 0) flash--;
        tickLerp();
        if (this.isControlledByLocalInstance()) {
            drive();
        } else if (!this.level().isClientSide && getControllingPassenger() == null) {
            // parcata: cade si franeaza pe server
            speed *= 0.8;
            Vec3 d = this.getDeltaMovement();
            this.setDeltaMovement(d.x * 0.5, d.y - 0.08, d.z * 0.5);
            this.move(MoverType.SELF, this.getDeltaMovement());
            if (this.onGround()) this.setDeltaMovement(Vec3.ZERO);
        } else {
            this.setDeltaMovement(Vec3.ZERO);
        }
        if (!this.level().isClientSide) burnFuel();
        this.checkInsideBlocks();
    }

    /** Consumul se calculeaza pe server din distanta parcursa (clientul nu poate trisa). */
    private void burnFuel() {
        if (Double.isNaN(lastX)) {
            lastX = getX();
            lastZ = getZ();
        }
        double d = Math.hypot(getX() - lastX, getZ() - lastZ);
        lastX = getX();
        lastZ = getZ();
        if (getControllingPassenger() == null || getFuel() <= 0f) return;
        if (d > 4) d = 0; // teleport, nu condus
        setFuel(getFuel() - (float) (d * PER_BLOCK) - IDLE);
    }

    private void tickLerp() {
        if (this.isControlledByLocalInstance()) {
            this.lerpSteps = 0;
            this.syncPacketPositionCodec(this.getX(), this.getY(), this.getZ());
        }
        if (this.lerpSteps > 0) {
            double x = this.getX() + (this.lerpX - this.getX()) / this.lerpSteps;
            double y = this.getY() + (this.lerpY - this.getY()) / this.lerpSteps;
            double z = this.getZ() + (this.lerpZ - this.getZ()) / this.lerpSteps;
            double yr = Mth.wrapDegrees(this.lerpYRot - this.getYRot());
            this.setYRot(this.getYRot() + (float) yr / this.lerpSteps);
            this.setXRot(this.getXRot() + (float) (this.lerpXRot - this.getXRot()) / this.lerpSteps);
            this.lerpSteps--;
            this.setPos(x, y, z);
            this.setRot(this.getYRot(), this.getXRot());
        } else {
            this.reapplyPosition();
            this.setRot(this.getYRot(), this.getXRot());
        }
    }

    /** Fizica soferului: acceleratie, frana, franare de motor, directie dependenta de viteza. */
    private void drive() {
        boolean driver = getControllingPassenger() != null;
        boolean engine = getFuel() > 0f;
        if (driver) {
            if (inUp && !inDown) speed += speed < 0 ? BRAKE : (engine ? ACCEL * (1.0 - 0.55 * Math.min(1.0, speed / MAX_FORWARD)) : -COAST);
            else if (inDown && !inUp) speed -= speed > 0 ? BRAKE : (engine ? ACCEL * 0.6 : -COAST);
            else speed = coast(speed);
            if (inBrake) speed = coast(coast(coast(speed)));
            if (!engine) speed = coast(speed);
        } else {
            speed = coast(coast(speed));
        }
        speed = Mth.clamp(speed, -MAX_REVERSE, MAX_FORWARD);

        float turn = (inRight ? 1 : 0) - (inLeft ? 1 : 0);
        if (driver && turn != 0 && Math.abs(speed) > 0.02) {
            double grip = Math.min(1.0, Math.abs(speed) / 0.45);
            double rate = 3.6 * grip * (1.0 - 0.45 * Math.min(1.0, Math.abs(speed) / MAX_FORWARD));
            float yaw = this.getYRot() + (float) (turn * rate * Math.signum(speed));
            this.setYRot(yaw);
        }

        float yawRad = this.getYRot() * Mth.DEG_TO_RAD;
        double mx = -Mth.sin(yawRad) * speed;
        double mz = Mth.cos(yawRad) * speed;
        double my = this.onGround() ? -0.05 : this.getDeltaMovement().y - 0.08;
        this.setDeltaMovement(mx, my, mz);
        this.move(MoverType.SELF, this.getDeltaMovement());
        if (this.horizontalCollision) speed *= 0.35;
    }

    private static double coast(double v) {
        if (v > COAST) return v - COAST;
        if (v < -COAST) return v + COAST;
        return 0;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, net.minecraft.world.level.block.state.BlockState state, BlockPos pos) {
        // vehiculul nu primeste daune de cadere; pasagerii nu cad cat timp sunt calare
        this.resetFallDistance();
    }

    @Override
    public boolean isNoGravity() {
        return true; // gravitatia o aplicam manual
    }
}
