package ro.solarstudios.flowcity.content;

import net.minecraft.util.Hand;
import net.minecraft.util.ActionResult;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import ro.solarstudios.flowcity.client.ClientHooks;

/** Harta GPS: click dreapta deschide harta mare (fara comenzi). */
public class MapItem extends Item {
    public MapItem() {
        super(ro.solarstudios.flowcity.content.ModContent.props().stacksTo(1));
    }

    @Override
    public ActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::openMap);
        return ActionResult.sidedSuccess(stack, world.isClientSide);
    }
}
