package ro.solarstudios.flowcity.cars;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.StringTextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import ro.solarstudios.flowcity.content.ModContent;

/** Cheia unei masini din garaj: numele si numarul masinii + generatia cheii (cheile vechi nu mai merg). */
public final class CarKeyItem extends Item {
    public CarKeyItem() {
        super(ModContent.props().stacksTo(1));
    }

    public static ItemStack make(GarageData.Car c) {
        ItemStack st = new ItemStack(ModContent.CAR_KEY.get());
        CompoundNBT t = st.getOrCreateTag();
        t.putInt("CarId", c.id);
        t.putInt("KeyGen", c.keyGen);
        t.putString("CarName", c.name);
        t.putString("Plate", c.plate);
        return st;
    }

    public static boolean opens(ItemStack st, GarageData.Car c) {
        if (st.isEmpty() || st.getItem() != ModContent.CAR_KEY.get() || !st.hasTag()) return false;
        CompoundNBT t = st.getTag();
        return t.getInt("CarId") == c.id && t.getInt("KeyGen") == c.keyGen;
    }

    @Override
    public ITextComponent getName(ItemStack st) {
        CompoundNBT t = st.getTag();
        if (t != null && t.contains("CarName")) return new StringTextComponent("Cheie " + t.getString("CarName") + " (" + t.getString("Plate") + ")");
        return super.getName(st);
    }

    @Override
    public void appendHoverText(ItemStack st, @Nullable World w, List<ITextComponent> tip, ITooltipFlag flag) {
        tip.add(new StringTextComponent("Tasta U langa masina: incuie / descuie").withStyle(TextFormatting.GRAY));
        tip.add(new StringTextComponent("Fara cheie nu poti conduce masina.").withStyle(TextFormatting.DARK_GRAY));
    }
}
