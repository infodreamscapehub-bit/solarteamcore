package ro.solarstudios.flowcity.content;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fml.RegistryObject;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import ro.solarstudios.flowcity.FlowCity;

/** Blocuri si iteme din EGK: bancomat, telefoane, carduri, bancnote. */
public final class ModContent {
    private ModContent() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, FlowCity.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, FlowCity.MODID);

    public static final ItemGroup TAB = new ItemGroup("flowcity") {
        @Override
        public ItemStack createIcon() {
            return new ItemStack(PHONE.get());
        }
    };

    public static final RegistryObject<Block> BANCOMAT = BLOCKS.register("bancomat", AtmBlock::new);
    public static final RegistryObject<Item> BANCOMAT_ITEM = ITEMS.register("bancomat",
        () -> new net.minecraft.item.BlockItem(BANCOMAT.get(), new Item.Properties().group(TAB)));

    public static final RegistryObject<Item> PHONE = ITEMS.register("phone", PhoneItem::new);

    static {
        for (int i = 1; i <= 5; i++) ITEMS.register("phone" + i, PhoneItem::new);
        ITEMS.register("sim_card", () -> new Item(new Item.Properties().group(TAB).maxStackSize(16)));
        for (int i = 1; i <= 5; i++) ITEMS.register("cardbancar" + i, () -> new Item(new Item.Properties().group(TAB).maxStackSize(1)));
        for (int n : new int[]{1, 5, 10, 20, 50, 100, 200, 500}) ITEMS.register(n + "_lei", () -> new Item(new Item.Properties().group(TAB)));
    }

    public static void register() {
        BLOCKS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
