package ro.solarstudios.flowcity.content;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalBlock;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.state.StateContainer;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.World;

/** Bloc GPS cu directie (sens unic / dublu sens). Directia = incotro te uiti cand il pui. */
public class GpsDirBlock extends HorizontalBlock {
    private static final VoxelShape SHAPE = Block.makeCuboidShape(0, 0, 0, 16, 2, 16);
    private final int type;

    public GpsDirBlock(int type) {
        super(GpsBlock.props());
        this.type = type;
        setDefaultState(getStateContainer().getBaseState().with(HORIZONTAL_FACING, Direction.NORTH));
    }

    @Override
    protected void fillStateContainer(StateContainer.Builder<Block, BlockState> builder) {
        builder.add(HORIZONTAL_FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockItemUseContext ctx) {
        return getDefaultState().with(HORIZONTAL_FACING, ctx.getPlacementHorizontalFacing());
    }

    @Override
    public VoxelShape getShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext ctx) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, IBlockReader world, BlockPos pos, ISelectionContext ctx) {
        return VoxelShapes.empty();
    }

    @Override
    public void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState old, boolean moving) {
        if (world.isRemote || world.getDimensionKey() != World.OVERWORLD) return;
        Direction d = state.get(HORIZONTAL_FACING);
        GpsNetwork.add(pos, type, d.getXOffset(), d.getZOffset());
    }

    @Override
    public void onReplaced(BlockState state, World world, BlockPos pos, BlockState now, boolean moving) {
        if (!state.isIn(now.getBlock()) && !world.isRemote) GpsNetwork.remove(pos);
        super.onReplaced(state, world, pos, now, moving);
    }
}
