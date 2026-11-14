package ro.solarstudios.flowcity.bridge;

import java.io.File;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import ro.solarstudios.flowcity.bridge.core.Bank;
import ro.solarstudios.flowcity.bridge.core.Money;
import ro.solarstudios.flowcity.bridge.core.PlayerIds;
import ro.solarstudios.flowcity.bridge.eadmin.Db;
import ro.solarstudios.flowcity.bridge.eadmin.EAdminCommand;
import ro.solarstudios.flowcity.bridge.eadmin.EAdminListener;
import ro.solarstudios.flowcity.bridge.eadmin.EAdminService;
import ro.solarstudios.flowcity.bridge.eadmin.VaultHook;
import ro.solarstudios.flowcity.bridge.hype.HypeModule;
import ro.solarstudios.flowcity.bridge.module.Modules;
import ro.solarstudios.flowcity.bridge.net.BridgeChannel;
import ro.solarstudios.flowcity.bridge.phone.PhoneModule;

/** FlowCityBridge - SolarStudios. Leaga serverul Mohist/Arclight de modul FlowCityCore din client. */
public final class FlowCityBridge extends JavaPlugin {
    private static FlowCityBridge instance;
    private Db db;
    private EAdminService service;
    private BridgeChannel channel;
    private VaultHook vault;
    private Money money;
    private Bank bank;
    private ro.solarstudios.flowcity.bridge.notify.NotifyModule notify;
    private PlayerIds ids;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        try {
            db = new Db(new File(getDataFolder(), "eadmin.db"), getLogger());
        } catch (Exception e) {
            getLogger().severe("Nu pot deschide baza de date eAdmin: " + e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        vault = new VaultHook();
        money = new Money(this, vault, db);
        bank = new Bank(this, db);
        ids = new PlayerIds(this, db);
        ids.enable();
        service = new EAdminService(this, db, vault);
        channel = new BridgeChannel(this, service);
        channel.register();
        EAdminListener listener = new EAdminListener(this, service, channel);
        getServer().getPluginManager().registerEvents(listener, this);

        PluginCommand cmd = getCommand("eadmin");
        if (cmd != null) {
            EAdminCommand handler = new EAdminCommand(this, service, channel);
            cmd.setExecutor(handler);
            cmd.setTabCompleter(handler);
        }
        Modules.register(this, new ro.solarstudios.flowcity.bridge.core.IdsModule());
        notify = new ro.solarstudios.flowcity.bridge.notify.NotifyModule();
        Modules.register(this, notify);
        Modules.register(this, new ro.solarstudios.flowcity.bridge.news.NewsModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.wheel.WheelModule());
        Modules.register(this, new HypeModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.eadmin.DashModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.eadmin.AdminPlusModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.update.UpdateModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.mdt.MdtModule());
        Modules.register(this, new PhoneModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.pay.PayModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.fines.FinesModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.help.HelpModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.payday.PaydayModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.clothes.ClothesModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.bank.BankModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.mecanic.MecanicModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.houses.HousesModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.lpsetup.LpSetupModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.cars.CarsModule());
        Modules.register(this, new ro.solarstudios.flowcity.bridge.shop.ShopModule());
        service.purgeOld(getConfig().getInt("eadmin.log-retention-days", 45));
        getLogger().info(service.serverName() + " bridge pornit.");
        // dupa ce toate pluginurile s-au incarcat, aratam ce economie folosim
        getServer().getScheduler().runTask(this, () -> getLogger().info("Economie cash: "
            + (vault.available() ? "Vault (" + vault.provider() + ")" : "registru intern (Vault/economie negasit)")
            + " | ID-uri atribuite: " + ids.count()));
    }

    @Override
    public void onDisable() {
        Modules.disableAll();
        if (channel != null) channel.unregister();
        if (db != null) db.close();
        instance = null;
    }

    public static FlowCityBridge getInstance() {
        return instance;
    }

    /** Baza de date SQLite comuna (plugins/FlowCityBridge/eadmin.db). Modulele isi creeaza propriile tabele. */
    public Db db() {
        return db;
    }

    /** Economia cash (Vault sau registru intern). */
    public Money money() {
        return money;
    }

    /** Contul bancar (ATM / aplicatia Bank din telefon). */
    public PlayerIds ids() {
        return ids;
    }

    public ro.solarstudios.flowcity.bridge.notify.NotifyModule notifications() {
        return notify;
    }

    public Bank bank() {
        return bank;
    }

    public VaultHook vault() {
        return vault;
    }

    public BridgeChannel channel() {
        return channel;
    }

    /** API pentru alte plugin-uri (ex. banca/ATM): service().logTransaction(...). */
    public EAdminService service() {
        return service;
    }
}
