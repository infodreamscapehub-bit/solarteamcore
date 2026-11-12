package ro.solarstudios.flowcity.vehicle;

import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import ro.solarstudios.flowcity.content.ModContent;

/** Cheia unei masini: click dreapta pe masina (sau in aer, langa ea) o incuie / descuie. */
public class CarKeyItem extends Item {
    public CarKeyItem() {
        super(new Item.Properties().stacksTo(1));
    }

    public static ItemStack forCar(UUID keyId, String carName) {
        ItemStack st = new ItemStack(ModContent.CAR_KEY.get());
        st.getOrCreateTag().putUUID("CarKey", keyId);
        st.getOrCreateTag().putString("CarName", carName);
        st.setHoverName(Component.literal("Cheie " + carName).withStyle(s -> s.withItalic(false)));
        return st;
    }

    public static boolean opens(ItemStack st, UUID keyId) {
        return st.getItem() instanceof CarKeyItem && st.hasTag() && st.getTag().hasUUID("CarKey") && st.getTag().getUUID("CarKey").equals(keyId);
    }

    /** Click in aer: incuie / descuie cea mai apropiata masina a acestei chei (raza 12 blocuri), ca de la telecomanda. */
    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack st = player.getItemInHand(hand);
        if (level.isClientSide || !st.hasTag() || !st.getTag().hasUUID("CarKey")) return InteractionResultHolder.pass(st);
        UUID id = st.getTag().getUUID("CarKey");
        List<CarEntity> cars = level.getEntitiesOfClass(CarEntity.class, new AABB(player.blockPosition()).inflate(12), c -> c.getKeyId().equals(id));
        if (cars.isEmpty()) {
            player.displayClientMessage(Component.literal("Masina e prea departe."), true);
            return InteractionResultHolder.fail(st);
        }
        cars.get(0).handleAction((net.minecraft.server.level.ServerPlayer) player, VehicleNet.LOCK, 0);
        return InteractionResultHolder.consume(st);
    }

    @Override
    public void appendHoverText(ItemStack st, @Nullable Level level, List<Component> tip, TooltipFlag flag) {
        tip.add(Component.literal("Click dreapta: incuie / descuie (pana la 12 blocuri)").withStyle(ChatFormatting.GRAY));
    }
}
