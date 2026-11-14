package ro.solarstudios.flowcity.clothes;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.NonNullList;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import ro.solarstudios.flowcity.content.ModContent;

/**
 * Imbracaminte (tab-ul creativ FlowCity): un articol pe tinuta, cu NBT "Outfit". Click dreapta = poarta tinuta (serverul verifica
 * daca o poti purta: cumparata sau permisiunea jobului). Click dreapta cu Shift = deschide garderoba.
 */
public final class ClothesItem extends Item {
    public ClothesItem() {
        super(ModContent.props().stacksTo(1));
    }

    public static ItemStack make(String id) {
        ItemStack st = new ItemStack(ModContent.CLOTHES.get());
        st.getOrCreateTag().putString("Outfit", id);
        return st;
    }

    public static ItemStack makePiece(String id) {
        ItemStack st = new ItemStack(ModContent.CLOTHES.get());
        st.getOrCreateTag().putString("Piece", id);
        return st;
    }

    @Override
    public void fillItemCategory(ItemGroup group, NonNullList<ItemStack> items) {
        if (group != ModContent.TAB && group != ItemGroup.TAB_SEARCH) return;
        for (String id : ClothesCatalog.OUTFITS.keySet()) items.add(make(id));
        for (String id : ClothesCatalog.PIECES.keySet()) items.add(makePiece(id));
    }

    @Override
    public ITextComponent getName(ItemStack st) {
        CompoundNBT t = st.getTag();
        if (t != null && t.contains("Piece")) {
            String n = ClothesCatalog.PIECES.get(t.getString("Piece"));
            return new StringTextComponent(n == null ? t.getString("Piece") : n);
        }
        if (t != null && t.contains("Outfit")) {
            String n = ClothesCatalog.OUTFITS.get(t.getString("Outfit"));
            return new StringTextComponent(n == null ? t.getString("Outfit") : n);
        }
        return new StringTextComponent("Îmbrăcăminte");
    }

    @Override
    public void appendHoverText(ItemStack st, @Nullable World w, List<ITextComponent> tip, ITooltipFlag flag) {
        boolean piece = st.hasTag() && st.getTag().contains("Piece");
        tip.add(new StringTextComponent(piece ? "Click dreapta: pune / scoate piesa" : "Click dreapta: poartă ținuta").withStyle(TextFormatting.GRAY));
        tip.add(new StringTextComponent("Shift + click: garderoba (/haine)").withStyle(TextFormatting.DARK_GRAY));
    }

    @Override
    public ActionResult<ItemStack> use(World level, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            final String id = !stack.hasTag() ? ""
                : stack.getTag().contains("Piece") ? "piece:" + stack.getTag().getString("Piece") : stack.getTag().getString("Outfit");
            final boolean sneak = player.isShiftKeyDown();
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClothesClient.useItem(id, sneak));
        }
        return ActionResult.sidedSuccess(stack, level.isClientSide);
    }
}
