package ro.solarstudios.flowcity.content;


import net.minecraft.entity.EntityType;
import net.minecraft.entity.EntityClassification;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Items;
import net.minecraft.block.Block;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.fml.RegistryObject;
import ro.solarstudios.flowcity.FlowCity;

/** Iteme si tab-ul creativ FlowCity. */
public final class ModContent {
    private ModContent() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, FlowCity.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, FlowCity.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITIES, FlowCity.MODID);
    /** Tab-ul creativ FlowCity (pe 1.16.5 itemele intra in tab prin Item.Properties().tab(TAB)). */
    public static final ItemGroup TAB = new ItemGroup("flowcity") {
        @Override
        public ItemStack makeIcon() {
            return PHONE != null && PHONE.isPresent() ? new ItemStack(PHONE.get()) : new ItemStack(Items.COMPASS);
        }
    };

    public static Item.Properties props() {
        return new Item.Properties().tab(TAB);
    }

    public static final RegistryObject<Item> MDT_TABLET = ITEMS.register("mdt_tablet", MdtItem::new);
    public static final RegistryObject<Item> CAR_KEY = ITEMS.register("cheie_masina", ro.solarstudios.flowcity.cars.CarKeyItem::new);
    public static final RegistryObject<Block> BANCOMAT = BLOCKS.register("bancomat", AtmBlock::new);
    public static final RegistryObject<Item> BANCOMAT_ITEM = ITEMS.register("bancomat", () -> new BlockItem(BANCOMAT.get(), props()));
    public static final RegistryObject<Block> DEALER = BLOCKS.register("dealer_auto", () -> new ro.solarstudios.flowcity.cars.CarsBlock(true));
    public static final RegistryObject<Item> DEALER_ITEM = ITEMS.register("dealer_auto", () -> new BlockItem(DEALER.get(), props()));
    public static final RegistryObject<Item> PHONE = ITEMS.register("phone", PhoneItem::new);


    static {
        ITEMS.register("harta", MapItem::new);
        for (int i = 1; i <= 5; i++) ITEMS.register("phone" + i, PhoneItem::new);
        ITEMS.register("sim_card", () -> new Item(props().stacksTo(16)));
        for (int i = 1; i <= 5; i++) ITEMS.register("cardbancar" + i, () -> new Item(props().stacksTo(1)));
        for (int n : new int[] {1, 5, 10, 20, 50, 100, 200, 500}) ITEMS.register(n + "_lei", () -> new Item(props()));
    }

    public static void register() {
        BLOCKS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ENTITIES.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
