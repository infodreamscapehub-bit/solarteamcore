package ro.solarstudios.flowcity.content;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import ro.solarstudios.flowcity.FlowCity;

/** Iteme si tab-ul creativ FlowCity. */
public final class ModContent {
    private ModContent() {}

    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, FlowCity.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FlowCity.MODID);

    public static final RegistryObject<Item> MDT_TABLET = ITEMS.register("mdt_tablet", MdtItem::new);
    public static final RegistryObject<Item> PHONE = ITEMS.register("phone", () -> new Item(new Item.Properties().stacksTo(1)));

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("flowcity", () -> CreativeModeTab.builder()
        .title(Component.translatable("itemGroup.flowcity"))
        // daca serverul nu are modul, registry-ul clientului pierde itemele FlowCity: nu crapam, folosim o iconita de rezerva
        .icon(() -> PHONE.isPresent() ? new ItemStack(PHONE.get()) : new ItemStack(Items.COMPASS))
        .displayItems((params, out) -> {
            for (RegistryObject<Item> i : ITEMS.getEntries()) out.accept(i.get());
        })
        .build());

    static {
        ITEMS.register("harta", () -> new Item(new Item.Properties().stacksTo(1)));
        for (int i = 1; i <= 5; i++) ITEMS.register("phone" + i, () -> new Item(new Item.Properties().stacksTo(1)));
        ITEMS.register("sim_card", () -> new Item(new Item.Properties().stacksTo(16)));
        for (int i = 1; i <= 5; i++) ITEMS.register("cardbancar" + i, () -> new Item(new Item.Properties().stacksTo(1)));
        for (int n : new int[] {1, 5, 10, 20, 50, 100, 200, 500}) ITEMS.register(n + "_lei", () -> new Item(new Item.Properties()));
    }

    public static void register() {
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        TABS.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
