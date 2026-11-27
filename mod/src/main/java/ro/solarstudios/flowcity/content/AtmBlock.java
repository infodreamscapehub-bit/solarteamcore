package ro.solarstudios.flowcity.content;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import ro.solarstudios.flowcity.client.ClientHooks;

/** Bancomatul (ATM): click dreapta cu cardul bancar in mana deschide interfata ATM; fara card da eroare. */
public class AtmBlock extends HorizontalDirectionalBlock {
    public AtmBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(5.0F, 1200.0F).noOcclusion());
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
            // ATM-ul cere cardul bancar in mana (staff-ul cu nivel 2+ il ocoleste); pluginul verifica si el
            if (player.hasPermissions(2) || isCard(player.getMainHandItem()) || isCard(player.getOffhandItem())) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::openAtm);
            } else {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::noCard);
            }
        }
        return InteractionResult.sidedSuccess(world.isClientSide);
    }

    private static boolean isCard(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        ResourceLocation k = net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(s.getItem());
        return k != null && "flowcitycore".equals(k.getNamespace()) && k.getPath().startsWith("cardbancar");
    }
}
