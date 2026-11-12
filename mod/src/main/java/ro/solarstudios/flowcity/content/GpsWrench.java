package ro.solarstudios.flowcity.content;

import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUseContext;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.world.World;

/** Cheia GPS: click pe un bloc GPS = info; shift + click = il sterge. Cu cheia in mana se vad nodurile retelei (particule). */
public class GpsWrench extends Item {
    public GpsWrench() {
        super(new Item.Properties().group(ModContent.TAB).maxStackSize(1));
    }

    @Override
    public ActionResultType onItemUse(ItemUseContext ctx) {
        World w = ctx.getWorld();
        PlayerEntity p = ctx.getPlayer();
        BlockPos pos = ctx.getPos();
        if (p == null) return ActionResultType.PASS;
        BlockState st = w.getBlockState(pos);
        boolean gps = st.getBlock() instanceof GpsBlock || st.getBlock() instanceof GpsDirBlock;
        if (!gps) return ActionResultType.PASS;
        if (!w.isRemote) {
            if (p.isSneaking()) {
                w.destroyBlock(pos, false);
                p.sendStatusMessage(new StringTextComponent("§cNod GPS sters. Noduri in retea: " + GpsNetwork.count()), true);
            } else {
                p.sendStatusMessage(new StringTextComponent("§aNod GPS §7(" + st.getBlock().getRegistryName().getPath() + ")  §8Noduri in retea: " + GpsNetwork.count()), true);
            }
        }
        return ActionResultType.SUCCESS;
    }
}
