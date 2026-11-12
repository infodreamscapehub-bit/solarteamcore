package ro.solarstudios.flowcity.content;

import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.material.Material;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.shapes.ISelectionContext;
import net.minecraft.util.math.shapes.VoxelShape;
import net.minecraft.util.math.shapes.VoxelShapes;
import net.minecraft.world.IBlockReader;
import net.minecraft.world.World;

/** Bloc de retea GPS fara orientare (segment/curba, intersectie). Invizibil in joc; nodurile se vad cu Cheia GPS in mana. */
public class GpsBlock extends Block {
    private static final VoxelShape SHAPE = Block.makeCuboidShape(0, 0, 0, 16, 2, 16);
    private final int type;

    public GpsBlock(int type) {
        super(props());
        this.type = type;
    }

    static AbstractBlock.Properties props() {
        return AbstractBlock.Properties.create(Material.MISCELLANEOUS).hardnessAndResistance(0.5F, 3600000.0F).notSolid()
            .setAllowsSpawn((s, r, p, e) -> false);
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
        if (!world.isRemote && world.getDimensionKey() == World.OVERWORLD) GpsNetwork.add(pos, type, 0, 0);
    }

    @Override
    public void onReplaced(BlockState state, World world, BlockPos pos, BlockState now, boolean moving) {
        if (state.getBlock() != now.getBlock() && !world.isRemote) GpsNetwork.remove(pos);
        super.onReplaced(state, world, pos, now, moving);
    }
}
