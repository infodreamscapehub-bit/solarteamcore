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

/** Tableta MDT a politiei: click dreapta deschide terminalul (serverul verifica accesul). */
public class MdtItem extends Item {
    public MdtItem() {
        super(ro.solarstudios.flowcity.content.ModContent.props().stacksTo(1));
    }

    @Override
    public ActionResult<ItemStack> use(World level, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::openMdt);
        return ActionResult.sidedSuccess(stack, level.isClientSide);
    }
}
