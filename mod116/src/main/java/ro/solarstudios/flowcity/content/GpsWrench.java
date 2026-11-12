package ro.solarstudios.flowcity.content;

import net.minecraft.network.chat.TranslatableComponent;

import net.minecraft.network.chat.TextComponent;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

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

    private static boolean admin(Player p) {
        return p.hasPermissions(2) || p.getAbilities().instabuild;
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level w = ctx.getLevel();
        Player p = ctx.getPlayer();
        BlockPos pos = ctx.getClickedPos();
        if (p == null) return InteractionResult.PASS;
        BlockState st = w.getBlockState(pos);
        if (!isGps(st)) return InteractionResult.PASS;
        if (w.isClientSide) return InteractionResult.SUCCESS;
        if (!admin(p)) {
            p.displayClientMessage(new TextComponent("\u00a7cDoar adminii pot folosi Cheia GPS."), true);
            return InteractionResult.SUCCESS;
        }
        if (w.dimension() != Level.OVERWORLD) {
            p.displayClientMessage(new TextComponent("\u00a7cReteaua GPS functioneaza doar in overworld."), true);
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = ctx.getItemInHand();
        CompoundTag tag = stack.getOrCreateTag();
        if (p.isShiftKeyDown()) {
            tag.remove("startX");
            tag.remove("startY");
            tag.remove("startZ");
            if (GpsNetwork.disconnectAll(pos)) {
                p.displayClientMessage(new TextComponent("\u00a7eToate conexiunile nodului " + fmt(pos) + " au fost sterse."), false);
                ((ServerLevel) w).sendParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.2, pos.getZ() + 0.5, 15, 0.3, 0, 0.3, 0.05);
            } else {
                p.displayClientMessage(new TextComponent("\u00a7cAcest bloc nu are conexiuni active."), true);
            }
            return InteractionResult.SUCCESS;
        }
        if (!tag.contains("startX")) {
            tag.putInt("startX", pos.getX());
            tag.putInt("startY", pos.getY());
            tag.putInt("startZ", pos.getZ());
            p.displayClientMessage(new TextComponent("\u00a7aPunct de pornire setat la: " + fmt(pos)), true);
            return InteractionResult.SUCCESS;
        }
        BlockPos start = new BlockPos(tag.getInt("startX"), tag.getInt("startY"), tag.getInt("startZ"));
        tag.remove("startX");
        tag.remove("startY");
        tag.remove("startZ");
        if (start.equals(pos)) {
            p.displayClientMessage(new TextComponent("\u00a7cConexiune anulata."), true);
            return InteractionResult.SUCCESS;
        }
        BlockState ss = w.getBlockState(start);
        if (!isGps(ss)) {
            p.displayClientMessage(new TextComponent("\u00a7cEroare: blocul de pornire nu mai exista!"), true);
            return InteractionResult.SUCCESS;
        }
        boolean oneWay = tag.getBoolean("oneWayMode") || ss.getBlock() == ModContent.GPS_ONEWAY.get() || st.getBlock() == ModContent.GPS_ONEWAY.get();
        if (GpsNetwork.connect(start, pos, !oneWay)) {
            p.displayClientMessage(new TextComponent("\u00a7aConexiune creata intre " + fmt(start) + " si " + fmt(pos) + (oneWay ? " (Sens Unic)" : " (Dublu Sens)")), false);
            line((ServerLevel) w, start, pos, ParticleTypes.HAPPY_VILLAGER);
        } else {
            p.displayClientMessage(new TextComponent("\u00a7cEroare la crearea conexiunii."), true);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level w, Player p, InteractionHand hand) {
        ItemStack stack = p.getItemInHand(hand);
        if (!w.isClientSide) {
            CompoundTag tag = stack.getOrCreateTag();
            boolean now = !tag.getBoolean("oneWayMode");
            tag.putBoolean("oneWayMode", now);
            p.displayClientMessage(new TextComponent("\u00a7e[eMaps] Mod conexiune: " + (now ? "\u00a7bSens Unic" : "\u00a7dDublu Sens")), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, w.isClientSide);
    }

    @Override
    public Component getName(ItemStack stack) {
        boolean one = stack.hasTag() && stack.getTag().getBoolean("oneWayMode");
        return new TranslatableComponent(getDescriptionId(stack)).append(one ? " \u00a7b(Sens Unic)" : " \u00a7d(Dublu Sens)");
    }

    private static String fmt(BlockPos p) {
        return p.getX() + " " + p.getY() + " " + p.getZ();
    }

    static void line(ServerLevel w, BlockPos a, BlockPos b, ParticleOptions part) {
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
