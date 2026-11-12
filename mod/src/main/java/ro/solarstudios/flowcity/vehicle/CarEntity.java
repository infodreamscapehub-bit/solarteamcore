package ro.solarstudios.flowcity.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import ro.solarstudios.flowcity.content.ModContent;

/**
 * Masina FlowCity. Se conduce ca o barca: clientul soferului simuleaza miscarea, serverul o accepta (pachetul vanilla
 * "move vehicle"). Fara pachete proprii, deci nu depinde de pluginul bridge. Locuri: 0 = sofer (stanga), 1-3 = pasageri.
 */
public class CarEntity extends Entity {
    private static final EntityDataAccessor<Integer> COLOR = SynchedEntityData.defineId(CarEntity.class, EntityDataSerializers.INT);

    /** Viteze in blocuri/tick (1.0 = 72 km/h). */
    public static final double MAX_FORWARD = 1.35;
    public static final double MAX_REVERSE = 0.35;
    private static final double ACCEL = 0.022;
    private static final double BRAKE = 0.06;
    private static final double COAST = 0.007;
    public static final int SEATS = 4;

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

    public CarEntity(EntityType<? extends CarEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = true;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(COLOR, 0xE8EEF9);
    }

    public int getColor() {
        return this.entityData.get(COLOR);
    }

    public void setColor(int rgb) {
        this.entityData.set(COLOR, rgb & 0xFFFFFF);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        if (tag.contains("Color")) setColor(tag.getInt("Color"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Color", getColor());
    }

    /** Apelat de client din tick: tastele soferului. */
    public void setInput(boolean left, boolean right, boolean up, boolean down, boolean brake) {
        this.inLeft = left;
        this.inRight = right;
        this.inUp = up;
        this.inDown = down;
        this.inBrake = brake;
    }

    /** Viteza curenta in km/h (valoare absoluta, pentru HUD). */
    public double speedKmh() {
        return Math.abs(speed) * 72.0;
    }

    public boolean reversing() {
        return speed < -0.02;
    }

    // ------------------------------------------------------------------ coliziuni / interactiune

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public ItemStack getPickResult() {
        return stackFor(getColor());
    }

    public static ItemStack stackFor(int rgb) {
        ItemStack st = new ItemStack(ModContent.CAR_ITEM.get());
        st.getOrCreateTag().putInt("Color", rgb & 0xFFFFFF);
        return st;
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (player.isSecondaryUseActive()) return InteractionResult.PASS;
        if (this.getPassengers().size() >= SEATS) return InteractionResult.PASS;
        if (!this.level().isClientSide) return player.startRiding(this) ? InteractionResult.CONSUME : InteractionResult.PASS;
        return InteractionResult.SUCCESS;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide || this.isRemoved()) return true;
        // doar jucatorii in creative o pot strica (mai tarziu: cheie / garaj)
        if (source.getEntity() instanceof Player p && p.getAbilities().instabuild) {
            if (!p.getAbilities().instabuild) return true;
            this.spawnAtLocation(stackFor(getColor()));
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
    public LivingEntity getControllingPassenger() {
        Entity first = this.getFirstPassenger();
        return first instanceof LivingEntity le ? le : null;
    }

    @Override
    protected void positionRider(Entity passenger, MoveFunction move) {
        if (!this.hasPassenger(passenger)) return;
        int seat = Math.min(SEATS - 1, this.getPassengers().indexOf(passenger));
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
        int seat = this.getPassengers().indexOf(passenger);
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
        this.checkInsideBlocks();
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
        if (driver) {
            if (inUp && !inDown) speed += speed < 0 ? BRAKE : ACCEL * (1.0 - 0.55 * Math.min(1.0, speed / MAX_FORWARD));
            else if (inDown && !inUp) speed -= speed > 0 ? BRAKE : ACCEL * 0.6;
            else speed = coast(speed);
            if (inBrake) speed = coast(coast(coast(speed)));
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
