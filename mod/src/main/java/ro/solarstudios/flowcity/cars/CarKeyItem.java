package ro.solarstudios.flowcity.cars;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.level.Level;
import ro.solarstudios.flowcity.content.ModContent;

/** Cheia unei masini din garaj: numele si numarul masinii + generatia cheii (cheile vechi nu mai merg). */
public final class CarKeyItem extends Item {
    public CarKeyItem() {
        super(ModContent.props().stacksTo(1));
    }

    public static ItemStack make(GarageData.Car c) {
        ItemStack st = new ItemStack(ModContent.CAR_KEY.get());
        CompoundTag t = st.getOrCreateTag();
        t.putInt("CarId", c.id);
        t.putInt("KeyGen", c.keyGen);
        t.putString("CarName", c.name);
        t.putString("Plate", c.plate);
        return st;
    }

    public static boolean opens(ItemStack st, GarageData.Car c) {
        if (st.isEmpty() || st.getItem() != ModContent.CAR_KEY.get() || !st.hasTag()) return false;
        CompoundTag t = st.getTag();
        return t.getInt("CarId") == c.id && t.getInt("KeyGen") == c.keyGen;
    }

    @Override
    public Component getName(ItemStack st) {
        CompoundTag t = st.getTag();
        if (t != null && t.contains("CarName")) return Component.literal("Cheie " + t.getString("CarName") + " (" + t.getString("Plate") + ")");
        return super.getName(st);
    }

    @Override
    public void appendHoverText(ItemStack st, @Nullable Level w, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("Tasta U langa masina: incuie / descuie").withStyle(ChatFormatting.GRAY));
        tip.add(Component.literal("Fara cheie nu poti conduce masina.").withStyle(ChatFormatting.DARK_GRAY));
    }
}
