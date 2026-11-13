package ro.solarstudios.flowcity.cars;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResultType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.world.World;
import net.minecraft.block.Block;
import net.minecraft.block.HorizontalBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateContainer;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * Blocul de dealer auto: click dreapta deschide catalogul. (Garajele sunt puncte setate cu /garaj, fara blocuri.)
 */
public class CarsBlock extends HorizontalBlock {
    private final boolean dealer;

    public CarsBlock(boolean dealer) {
        super(AbstractBlock.Properties.of(Material.METAL).strength(5.0F, 1200.0F));
        this.dealer = dealer;
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateContainer.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockItemUseContext ctx) {
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public ActionResultType use(BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockRayTraceResult hit) {
        if (world.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ro.solarstudios.flowcity.cars.client.CarsClient.request("dealer_block", new com.google.gson.JsonObject()));
        }
        return ActionResultType.sidedSuccess(world.isClientSide);
    }
}
