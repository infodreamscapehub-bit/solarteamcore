package ro.solarstudios.flowcity.content;

import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Direction;
import net.minecraft.util.Hand;
import net.minecraft.util.ActionResultType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItemUseContext;
import net.minecraft.world.World;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraft.block.HorizontalBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateContainer;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import ro.solarstudios.flowcity.client.ClientHooks;

/** Bancomatul (ATM): click dreapta cu cardul bancar in mana deschide interfata ATM; fara card da eroare. */
public class AtmBlock extends HorizontalBlock {
    public AtmBlock() {
        super(AbstractBlock.Properties.of(Material.METAL).strength(5.0F, 1200.0F).noOcclusion());
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
            // ATM-ul cere cardul bancar in mana (staff-ul cu nivel 2+ il ocoleste); pluginul verifica si el
            if (player.hasPermissions(2) || isCard(player.getMainHandItem()) || isCard(player.getOffhandItem())) {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::openAtm);
            } else {
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> ClientHooks::noCard);
            }
        }
        return ActionResultType.sidedSuccess(world.isClientSide);
    }

    private static boolean isCard(ItemStack s) {
        if (s == null || s.isEmpty()) return false;
        ResourceLocation k = s.getItem().getRegistryName();
        return k != null && "flowcitycore".equals(k.getNamespace()) && k.getPath().startsWith("cardbancar");
    }
}
