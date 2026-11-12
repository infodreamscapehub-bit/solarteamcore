package ro.solarstudios.flowcity.content;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Direction;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.World;
import net.minecraft.block.Block;
import net.minecraft.block.HorizontalBlock;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateContainer;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.util.math.shapes.VoxelShape;

/** Bloc GPS cu directie (sens unic / dublu sens). Directia = incotro te uiti cand il pui. */
public class GpsDirBlock extends HorizontalBlock {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 2, 16);
    private final int type;

    public GpsDirBlock(int type) {
        super(GpsBlock.props());
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateContainer.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockItemUseContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
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
    public void onPlace(BlockState state, World world, BlockPos pos, BlockState old, boolean moving) {
        if (world.isClientSide || world.dimension() != World.OVERWORLD) return;
        Direction d = state.getValue(FACING);
        GpsNetwork.add(pos, type, d.getStepX(), d.getStepZ());
    }

    @Override
    public void onRemove(BlockState state, World world, BlockPos pos, BlockState now, boolean moving) {
        if (state.getBlock() != now.getBlock() && !world.isClientSide) GpsNetwork.remove(pos);
        super.onRemove(state, world, pos, now, moving);
    }

    @Override
    public net.minecraft.block.material.PushReaction getPistonPushReaction(net.minecraft.block.BlockState state) {
        return net.minecraft.block.material.PushReaction.BLOCK;
    }
}
