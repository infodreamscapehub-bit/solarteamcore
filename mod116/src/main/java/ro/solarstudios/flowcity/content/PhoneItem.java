package ro.solarstudios.flowcity.content;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import ro.solarstudios.flowcity.client.ClientHooks;

/** Telefon: click dreapta deschide ePhone. */
public class PhoneItem extends Item {
    public PhoneItem() {
        super(ro.solarstudios.flowcity.content.ModContent.props().stacksTo(1));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level world, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (world.isClientSide) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::openPhone);
        return InteractionResultHolder.sidedSuccess(stack, world.isClientSide);
    }
}
