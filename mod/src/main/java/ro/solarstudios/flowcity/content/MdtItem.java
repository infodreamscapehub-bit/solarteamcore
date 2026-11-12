package ro.solarstudios.flowcity.content;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import ro.solarstudios.flowcity.client.ClientHooks;

/** Tableta MDT a politiei: click dreapta deschide terminalul (serverul verifica accesul). */
public class MdtItem extends Item {
    public MdtItem() {
        super(new Item.Properties().group(ModContent.TAB).maxStackSize(1));
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::openMdt);
        return ActionResult.resultSuccess(stack);
    }
}
