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

/** Telefon: click dreapta deschide ePhone. */
public class PhoneItem extends Item {
    public PhoneItem() {
        super(new Item.Properties().group(ModContent.TAB).maxStackSize(1));
    }

    @Override
    public ActionResult<ItemStack> onItemRightClick(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getHeldItem(hand);
        if (world.isRemote) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::openPhone);
        return ActionResult.resultSuccess(stack);
    }
}
