package ro.solarstudios.flowcity.vehicle;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;
import javax.annotation.Nullable;
import ro.solarstudios.flowcity.content.ModContent;

/** Masina ca obiect: click pe un bloc o pune jos (culoarea e in NBT). */
public class CarItem extends Item {
    public CarItem() {
        super(new Item.Properties().stacksTo(1));
    }

    @Override
    public InteractionResult useOn(UseOnContext ctx) {
        Level level = ctx.getLevel();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        ItemStack st = ctx.getItemInHand();
        BlockPos pos = ctx.getClickedPos().relative(ctx.getClickedFace());
        CarEntity car = new CarEntity(ModContent.CAR.get(), level);
        car.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        car.setYRot(ctx.getPlayer() == null ? 0 : ctx.getPlayer().getYRot());
        int rgb = st.hasTag() && st.getTag().contains("Color") ? st.getTag().getInt("Color") : 0xE8EEF9;
        car.setColor(rgb);
        if (st.hasTag()) car.setVariant(st.getTag().getInt("Variant"));
        if (st.hasTag() && st.getTag().contains("Fuel")) car.setFuel(st.getTag().getFloat("Fuel"));
        if (!level.noCollision(car, car.getBoundingBox())) return InteractionResult.FAIL;
        if (ctx.getPlayer() != null) {
            car.setOwner(ctx.getPlayer().getUUID());
            ItemStack key = car.makeKey();
            if (!ctx.getPlayer().getInventory().add(key)) ctx.getPlayer().drop(key, false);
        }
        level.addFreshEntity(car);
        if (ctx.getPlayer() == null || !ctx.getPlayer().getAbilities().instabuild) st.shrink(1);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack st, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        int v = st.hasTag() ? st.getTag().getInt("Variant") : 0;
        tip.add(Component.literal(VehicleTypes.get(v).name).withStyle(net.minecraft.ChatFormatting.GOLD));
        tip.add(Component.literal("Click pe sol: pui masina si primesti cheia.").withStyle(net.minecraft.ChatFormatting.GRAY));
    }
}
