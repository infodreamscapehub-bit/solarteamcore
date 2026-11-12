package ro.solarstudios.flowcity.content;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Bloc de retea GPS fara orientare (segment/curba, intersectie). Invizibil in joc; nodurile se vad cu Cheia GPS in mana. */
public class GpsBlock extends Block {
    private static final VoxelShape SHAPE = Block.box(0, 0, 0, 16, 2, 16);
    private final int type;

    public GpsBlock(int type) {
        super(props());
        this.type = type;
    }

    static BlockBehaviour.Properties props() {
        return BlockBehaviour.Properties.of().mapColor(MapColor.NONE).strength(0.5F, 3600000.0F).noOcclusion()
            .pushReaction(PushReaction.BLOCK).isValidSpawn((s, r, p, e) -> false);
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
        if (!world.isClientSide && world.dimension() == Level.OVERWORLD) GpsNetwork.add(pos, type, 0, 0);
    }

    @Override
    public void onRemove(BlockState state, Level world, BlockPos pos, BlockState now, boolean moving) {
        if (state.getBlock() != now.getBlock() && !world.isClientSide) GpsNetwork.remove(pos);
        super.onRemove(state, world, pos, now, moving);
    }
}
