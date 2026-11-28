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

    /** Proprietatile implicite ale unui item FlowCity (pe 1.20.1 tab-ul creativ se umple din displayItems). */
    public static Item.Properties props() {
        return new Item.Properties();
    }

    public static final RegistryObject<Item> MDT_TABLET = ITEMS.register("mdt_tablet", MdtItem::new);
    public static final RegistryObject<Item> ADMIN_TABLET = ITEMS.register("admin_tablet", AdminTabletItem::new);
    public static final RegistryObject<Item> CAR_KEY = ITEMS.register("cheie_masina", ro.solarstudios.flowcity.cars.CarKeyItem::new);
    public static final RegistryObject<Block> BANCOMAT = BLOCKS.register("bancomat", AtmBlock::new);
    public static final RegistryObject<Item> BANCOMAT_ITEM = ITEMS.register("bancomat", () -> new BlockItem(BANCOMAT.get(), new Item.Properties()));
    public static final RegistryObject<Block> GPS_PATH = BLOCKS.register("gps_path", () -> new GpsBlock(GpsNetwork.PATH));
    public static final RegistryObject<Block> GPS_INTERSECTION = BLOCKS.register("gps_intersection", () -> new GpsBlock(GpsNetwork.INTERSECTION));
    public static final RegistryObject<Block> GPS_ONEWAY = BLOCKS.register("gps_oneway", () -> new GpsDirBlock(GpsNetwork.ONEWAY));
    public static final RegistryObject<Block> GPS_TWOWAY = BLOCKS.register("gps_twoway", () -> new GpsDirBlock(GpsNetwork.TWOWAY));
    public static final RegistryObject<Block> DEALER = BLOCKS.register("dealer_auto", () -> new ro.solarstudios.flowcity.cars.CarsBlock(true));
    public static final RegistryObject<Item> DEALER_ITEM = ITEMS.register("dealer_auto", () -> new BlockItem(DEALER.get(), new Item.Properties()));
    public static final RegistryObject<Item> PHONE = ITEMS.register("phone", PhoneItem::new);

    public static final RegistryObject<CreativeModeTab> TAB = TABS.register("flowcity", () -> CreativeModeTab.builder()
        .title(Component.translatable("itemGroup.flowcity"))
        // daca serverul nu are modul, registry-ul clientului pierde itemele FlowCity: nu crapam, folosim o iconita de rezerva
        .icon(() -> PHONE.isPresent() ? new ItemStack(PHONE.get()) : new ItemStack(Items.COMPASS))
        .displayItems((params, out) -> {
            for (RegistryObject<Item> i : ITEMS.getEntries()) {
                if (i.isPresent()) out.accept(i.get());
            }
        })
        .build());

    /**
     * Itemele profesionale FJobs (texturi proprii, pixel-art original): id + marimea stivei.
     * Functia lor e pe server (FJobs gear, eticheta PDC).
     */
    private static final String[][] JOB_ITEMS = {
        {"el_trusa", "1"},
        {"el_surubelnita", "1"},
        {"el_cleste", "1"},
        {"el_multimetru", "1"},
        {"el_cablu", "16"},
        {"el_siguranta", "24"},
        {"el_manusi", "1"},
        {"lanterna", "1"},
        {"gm_burete", "1"},
        {"gm_solutie", "16"},
        {"gm_racleta", "1"},
        {"gm_laveta", "4"},
        {"gm_ham", "1"},
        {"gm_galeata", "1"},
        {"gu_sac", "16"},
        {"gu_cleste", "1"},
        {"gu_cos", "1"},
        {"cu_colet", "16"},
        {"cu_scanner", "1"},
        {"cu_geanta", "1"},
        {"cu_documente", "16"},
        {"cu_eticheta", "32"},
        {"tx_aparat", "1"},
        {"tx_permis", "1"},
        {"au_bilet", "64"},
        {"au_card", "1"},
        {"me_trusa", "1"},
        {"me_cheie", "1"},
        {"me_ciocan", "1"},
        {"me_cric", "1"},
        {"me_piese", "16"},
        {"me_ulei", "16"},
        {"me_canistra", "1"},
        {"pe_momeala", "32"},
        {"pe_plasa", "1"},
        {"pe_lada", "1"},
        {"fe_ingrasamant", "16"},
        {"fe_lada", "1"},
        {"fe_secera", "1"},
        {"mi_geanta", "1"},
        {"co_ruleta", "1"},
        {"co_mistrie", "1"},
        {"po_geanta", "1"},
        {"po_scrisoare", "32"},
        {"po_colet_mic", "32"},
        {"ca_documente", "16"},
        {"ca_scanner", "1"},
        {"ca_chei", "1"},
        {"ma_carucior", "1"},
        {"ma_palet", "16"},
        {"vesta_reflect", "1"},
        {"manusi_lucru", "1"}
    };

    /** Itemele de echipament cu functie reala (framework-ul de pe server: serial unic in PDC). Fiecare e unic, deci nu se stivuieste. */
    private static final String[] EQUIPMENT = {
        "catuse", "scanner_amprente", "proba_amprenta", "bodycam", "radio", "geanta_medicala", "defibrilator",
        "scanner_diagnoza", "trusa_reparatii", "piesa_auto", "camera_foto", "fotografie"
    };

    static {
        for (String id : EQUIPMENT) ITEMS.register(id, () -> new Item(new Item.Properties().stacksTo(1)));
        for (String[] ji : JOB_ITEMS) {
            final int stack = Integer.parseInt(ji[1]);
            ITEMS.register(ji[0], () -> new Item(props().stacksTo(stack)));
        }
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
