package ro.solarstudios.flowcity.cars;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * Blocul de dealer auto / garaj. Fata blocului indica unde apar masinile scoase din garaj (3 blocuri in fata).
 * Click dreapta deschide interfata.
 */
public class CarsBlock extends HorizontalDirectionalBlock {
    private final boolean dealer;

    public CarsBlock(boolean dealer) {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 1200.0F));
        this.dealer = dealer;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public InteractionResult use(BlockState state, Level world, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (world.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
                if (dealer) ro.solarstudios.flowcity.cars.client.DealerScreen.open(pos);
                else ro.solarstudios.flowcity.cars.client.GarageScreen.open(pos);
            });
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }
}
