package ro.solarstudios.flowcity.content;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalBlock;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.state.StateContainer;
import net.minecraft.util.ActionResultType;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import ro.solarstudios.flowcity.client.ClientHooks;

/** Bancomatul (ATM): click dreapta deschide interfata ATM. */
public class AtmBlock extends HorizontalBlock {
    public AtmBlock() {
        super(AbstractBlock.Properties.create(Material.IRON).hardnessAndResistance(5.0F, 1200.0F).notSolid());
        setDefaultState(getStateContainer().getBaseState().with(HORIZONTAL_FACING, net.minecraft.util.Direction.NORTH));
    }

    @Override
    protected void fillStateContainer(StateContainer.Builder<Block, BlockState> builder) {
        builder.add(HORIZONTAL_FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockItemUseContext ctx) {
        return getDefaultState().with(HORIZONTAL_FACING, ctx.getPlacementHorizontalFacing().getOpposite());
    }

    @Override
    public ActionResultType onBlockActivated(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockRayTraceResult hit) {
        if (world.isRemote) DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::openAtm);
        return ActionResultType.SUCCESS;
    }
}
