package ro.solarstudios.flowcity.content;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import ro.solarstudios.flowcity.FlowCity;

/** Iteme si tab-ul creativ FlowCity. */
public final class ModContent {
    private ModContent() {}

    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, FlowCity.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, FlowCity.MODID);
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, FlowCity.MODID);
    public static final DeferredRegister<CreativeModeTab> TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, FlowCity.MODID);

    public static final RegistryObject<Item> MDT_TABLET = ITEMS.register("mdt_tablet", MdtItem::new);
    public static final RegistryObject<Block> BANCOMAT = BLOCKS.register("bancomat", AtmBlock::new);
    public static final RegistryObject<Item> BANCOMAT_ITEM = ITEMS.register("bancomat", () -> new BlockItem(BANCOMAT.get(), new Item.Properties()));
    public static final RegistryObject<Block> GPS_PATH = BLOCKS.register("gps_path", () -> new GpsBlock(GpsNetwork.PATH));
    public static final RegistryObject<Block> GPS_INTERSECTION = BLOCKS.register("gps_intersection", () -> new GpsBlock(GpsNetwork.INTERSECTION));
    public static final RegistryObject<Block> GPS_ONEWAY = BLOCKS.register("gps_oneway", () -> new GpsDirBlock(GpsNetwork.ONEWAY));
    public static final RegistryObject<Block> GPS_TWOWAY = BLOCKS.register("gps_twoway", () -> new GpsDirBlock(GpsNetwork.TWOWAY));
    public static final RegistryObject<EntityType<ro.solarstudios.flowcity.vehicle.CarEntity>> CAR = ENTITIES.register("car",
        () -> EntityType.Builder.<ro.solarstudios.flowcity.vehicle.CarEntity>of(ro.solarstudios.flowcity.vehicle.CarEntity::new, MobCategory.MISC)
            .sized(1.9f, 1.5f).clientTrackingRange(10).updateInterval(1).build("car"));
    public static final RegistryObject<Item> CAR_ITEM = ITEMS.register("car_spawn", ro.solarstudios.flowcity.vehicle.CarItem::new);
    public static final RegistryObject<Item> CAR_KEY = ITEMS.register("car_key", ro.solarstudios.flowcity.vehicle.CarKeyItem::new);
    public static final RegistryObject<Item> JERRY_CAN = ITEMS.register("canistra", ro.solarstudios.flowcity.vehicle.JerryCanItem::new);
    public static final RegistryObject<Block> GAS_PUMP = BLOCKS.register("pompa_benzina", ro.solarstudios.flowcity.vehicle.GasPumpBlock::new);
    public static final RegistryObject<Item> GAS_PUMP_ITEM = ITEMS.register("pompa_benzina", () -> new BlockItem(GAS_PUMP.get(), new Item.Properties()));
    public static final RegistryObject<Item> PHONE = ITEMS.register("phone", PhoneItem::new);

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("flowcity", () -> CreativeModeTab.builder()
        .title(Component.translatable("itemGroup.flowcity"))
        // daca serverul nu are modul, registry-ul clientului pierde itemele FlowCity: nu crapam, folosim o iconita de rezerva
        .icon(() -> PHONE.isPresent() ? new ItemStack(PHONE.get()) : new ItemStack(Items.COMPASS))
        .displayItems((params, out) -> {
            for (RegistryObject<Item> i : ITEMS.getEntries()) {
                if (i == CAR_ITEM) {
                    ro.solarstudios.flowcity.vehicle.VehicleTypes.Type[] types = ro.solarstudios.flowcity.vehicle.VehicleTypes.TYPES;
                    for (int v = 0; v < types.length; v++) {
                        out.accept(ro.solarstudios.flowcity.vehicle.CarEntity.stackFor(types[v].defaultColor, v));
                        // civilele si in cateva culori
                        if (types[v].livery.body < 0) {
                            for (int rgb : new int[] {0x1B1F2A, 0x9AA3AE, 0x2E9D5B, 0x6B4A2E}) if (rgb != types[v].defaultColor) out.accept(ro.solarstudios.flowcity.vehicle.CarEntity.stackFor(rgb, v));
                        }
                    }
                } else if (i == CAR_KEY) {
                    // cheile se primesc odata cu masina
                } else if (i == JERRY_CAN) {
                    ItemStack can = new ItemStack(JERRY_CAN.get());
                    ro.solarstudios.flowcity.vehicle.JerryCanItem.setFuel(can, ro.solarstudios.flowcity.vehicle.JerryCanItem.CAPACITY);
                    out.accept(new ItemStack(JERRY_CAN.get()));
                    out.accept(can);
                } else out.accept(i.get());
            }
        })
        .build());

    static {
        ITEMS.register("harta", MapItem::new);
        ITEMS.register("gps_wrench", GpsWrench::new);
        for (String b : new String[] {"gps_path", "gps_intersection", "gps_oneway", "gps_twoway"})
            ITEMS.register(b, () -> new BlockItem(ForgeRegistries.BLOCKS.getValue(new net.minecraft.resources.ResourceLocation(FlowCity.MODID, b)), new Item.Properties()));
        for (int i = 1; i <= 5; i++) ITEMS.register("phone" + i, PhoneItem::new);
        ITEMS.register("sim_card", () -> new Item(new Item.Properties().stacksTo(16)));
        for (int i = 1; i <= 5; i++) ITEMS.register("cardbancar" + i, () -> new Item(new Item.Properties().stacksTo(1)));
        for (int n : new int[] {1, 5, 10, 20, 50, 100, 200, 500}) ITEMS.register(n + "_lei", () -> new Item(new Item.Properties()));
    }

    public static void register() {
        BLOCKS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ITEMS.register(FMLJavaModLoadingContext.get().getModEventBus());
        ENTITIES.register(FMLJavaModLoadingContext.get().getModEventBus());
        TABS.register(FMLJavaModLoadingContext.get().getModEventBus());
    }
}
