package ro.solarstudios.flowcity.vehicle;

import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/** Canistra de benzina (20 L). Se umple la pompa; click dreapta pe masina = torni in rezervor. */
public class JerryCanItem extends Item {
    public static final float CAPACITY = 20f;

    public JerryCanItem() {
        super(new Item.Properties().stacksTo(1));
    }

    public static float fuel(ItemStack st) {
        return st.hasTag() ? st.getTag().getFloat("Fuel") : 0f;
    }

    public static void setFuel(ItemStack st, float f) {
        st.getOrCreateTag().putFloat("Fuel", Math.max(0f, Math.min(CAPACITY, f)));
    }

    @Override
    public boolean isBarVisible(ItemStack st) {
        return true;
    }

    @Override
    public int getBarWidth(ItemStack st) {
        return Math.round(13f * fuel(st) / CAPACITY);
    }

    @Override
    public int getBarColor(ItemStack st) {
        return 0xE9A23B;
    }

    @Override
    public void appendHoverText(ItemStack st, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal(String.format("%.1f / %.0f L", fuel(st), CAPACITY)).withStyle(ChatFormatting.GOLD));
        tip.add(Component.literal("Click dreapta pe masina: torni benzina").withStyle(ChatFormatting.GRAY));
    }
}
