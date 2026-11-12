package ro.solarstudios.flowcity.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Bloc GPS cu directie (sens unic / dublu sens). Directia = incotro te uiti cand il pui. */
public class GpsDirBlock extends HorizontalDirectionalBlock {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 2, 16);
    private final int type;

    public GpsDirBlock(int type) {
        super(GpsBlock.props());
        this.type = type;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection());
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx) {
        return SHAPE;
    }

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockGetter world, BlockPos pos, CollisionContext ctx) {
        return Shapes.empty();
    }

    @Override
    public void onPlace(BlockState state, Level world, BlockPos pos, BlockState old, boolean moving) {
        if (world.isClientSide || world.dimension() != Level.OVERWORLD) return;
        Direction d = state.getValue(FACING);
        GpsNetwork.add(pos, type, d.getStepX(), d.getStepZ());
    }

    @Override
    public void onRemove(BlockState state, Level world, BlockPos pos, BlockState now, boolean moving) {
        if (state.getBlock() != now.getBlock() && !world.isClientSide) GpsNetwork.remove(pos);
        super.onRemove(state, world, pos, now, moving);
    }
}
