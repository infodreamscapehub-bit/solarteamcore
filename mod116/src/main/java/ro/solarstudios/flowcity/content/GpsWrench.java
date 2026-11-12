package ro.solarstudios.flowcity.content;

import net.minecraft.util.text.TranslationTextComponent;

import net.minecraft.util.text.StringTextComponent;

import net.minecraft.util.math.BlockPos;
import net.minecraft.particles.IParticleData;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.world.server.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.ActionResult;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUseContext;
import net.minecraft.world.World;
import net.minecraft.block.BlockState;

/**
 * Cheia GPS (ca in EGK): click pe un bloc GPS = punct de start, click pe al doilea = legatura A-B.
 * Click in aer = comuta Dublu sens / Sens unic. Shift + click pe nod = sterge toate legaturile lui.
 * Pentru a sparge un bloc GPS ai cheia in mana (altfel e protejat).
 */
public class GpsWrench extends Item {
    public GpsWrench() {
        super(ro.solarstudios.flowcity.content.ModContent.props().stacksTo(1));
    }

    static boolean isGps(BlockState st) {
        return st.getBlock() instanceof GpsBlock || st.getBlock() instanceof GpsDirBlock;
    }

    private static boolean admin(PlayerEntity p) {
        return p.hasPermissions(2) || p.getAbilities().instabuild;
    }

    @Override
    public ActionResultType useOn(ItemUseContext ctx) {
        World w = ctx.getLevel();
        PlayerEntity p = ctx.getPlayer();
        BlockPos pos = ctx.getClickedPos();
        if (p == null) return ActionResultType.PASS;
        BlockState st = w.getBlockState(pos);
        if (!isGps(st)) return ActionResultType.PASS;
        if (w.isClientSide) return ActionResultType.SUCCESS;
        if (!admin(p)) {
            p.displayClientMessage(new StringTextComponent("\u00a7cDoar adminii pot folosi Cheia GPS."), true);
            return ActionResultType.SUCCESS;
        }
        if (w.dimension() != World.OVERWORLD) {
            p.displayClientMessage(new StringTextComponent("\u00a7cReteaua GPS functioneaza doar in overworld."), true);
            return ActionResultType.SUCCESS;
        }
        ItemStack stack = ctx.getItemInHand();
        CompoundNBT tag = stack.getOrCreateTag();
        if (p.isShiftKeyDown()) {
            tag.remove("startX");
            tag.remove("startY");
            tag.remove("startZ");
            if (GpsNetwork.disconnectAll(pos)) {
                p.displayClientMessage(new StringTextComponent("\u00a7eToate conexiunile nodului " + fmt(pos) + " au fost sterse."), false);
                ((ServerWorld) w).sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, 15, 0.3, 0, 0.3, 0.05);
            } else {
                p.displayClientMessage(new StringTextComponent("\u00a7cAcest bloc nu are conexiuni active."), true);
            }
            return ActionResultType.SUCCESS;
        }
        if (!tag.contains("startX")) {
            tag.putInt("startX", pos.getX());
            tag.putInt("startY", pos.getY());
            tag.putInt("startZ", pos.getZ());
            p.displayClientMessage(new StringTextComponent("\u00a7aPunct de pornire setat la: " + fmt(pos)), true);
            return ActionResultType.SUCCESS;
        }
        BlockPos start = new BlockPos(tag.getInt("startX"), tag.getInt("startY"), tag.getInt("startZ"));
        tag.remove("startX");
        tag.remove("startY");
        tag.remove("startZ");
        if (start.equals(pos)) {
            p.displayClientMessage(new StringTextComponent("\u00a7cConexiune anulata."), true);
            return ActionResultType.SUCCESS;
        }
        BlockState ss = w.getBlockState(start);
        if (!isGps(ss)) {
            p.displayClientMessage(new StringTextComponent("\u00a7cEroare: blocul de pornire nu mai exista!"), true);
            return ActionResultType.SUCCESS;
        }
        boolean oneWay = tag.getBoolean("oneWayMode") || ss.getBlock() == ModContent.GPS_ONEWAY.get() || st.getBlock() == ModContent.GPS_ONEWAY.get();
        if (GpsNetwork.connect(start, pos, !oneWay)) {
            p.displayClientMessage(new StringTextComponent("\u00a7aConexiune creata intre " + fmt(start) + " si " + fmt(pos) + (oneWay ? " (Sens Unic)" : " (Dublu Sens)")), false);
            line((ServerWorld) w, start, pos, ParticleTypes.HAPPY_VILLAGER);
        } else {
            p.displayClientMessage(new StringTextComponent("\u00a7cEroare la crearea conexiunii."), true);
        }
        return ActionResultType.SUCCESS;
    }

    @Override
    public ActionResult<ItemStack> use(World w, PlayerEntity p, Hand hand) {
        ItemStack stack = p.getItemInHand(hand);
        if (!w.isClientSide) {
            CompoundNBT tag = stack.getOrCreateTag();
            boolean now = !tag.getBoolean("oneWayMode");
            tag.putBoolean("oneWayMode", now);
            p.displayClientMessage(new StringTextComponent("\u00a7e[eMaps] Mod conexiune: " + (now ? "\u00a7bSens Unic" : "\u00a7dDublu Sens")), true);
        }
        return ActionResult.sidedSuccess(stack, w.isClientSide);
    }

    @Override
    public ITextComponent getName(ItemStack stack) {
        boolean one = stack.hasTag() && stack.getTag().getBoolean("oneWayMode");
        return new TranslationTextComponent(getDescriptionId(stack)).append(one ? " \u00a7b(Sens Unic)" : " \u00a7d(Dublu Sens)");
    }

    private static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    static void line(ServerWorld w, BlockPos a, BlockPos b, IParticleData part) {
        double dx = b.getX() - a.getX();
        double dy = b.getY() - a.getY();
        double dz = b.getZ() - a.getZ();
        double steps = Math.max(10, Math.sqrt(dx * dx + dy * dy + dz * dz) * 2);
        for (int i = 0; i <= steps; i++) {
            double r = i / steps;
            w.sendParticles(part, a.getX() + 0.5 + dx * r, a.getY() + 0.3 + dy * r, a.getZ() + 0.5 + dz * r, 1, 0, 0, 0, 0);
        }
    }
}
