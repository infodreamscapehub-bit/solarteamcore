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

/** Tableta FAdmin: click dreapta deschide panoul (serverul verifica nivelul de staff; jucatorii normali primesc un refuz). */
public class AdminTabletItem extends Item {
    public AdminTabletItem() {
        super(ro.solarstudios.flowcity.content.ModContent.props().stacksTo(1));
    }

    @Override
    public ActionResult<ItemStack> use(World level, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::openAdminTablet);
        return ActionResult.sidedSuccess(stack, level.isClientSide);
    }
}
