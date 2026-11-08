package ro.solarstudios.flowcity.bridge.eadmin;

import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;

/** Legatura cu Vault prin reflexie (fara dependenta la compilare). */
public final class VaultHook {
    private Object economy;
    private Method getBalance;
    private Method withdrawPlayer;
    private Method depositPlayer;
    private Method has;
    private Method txSuccess;

    public VaultHook() {
        try {
            if (Bukkit.getPluginManager().getPlugin("Vault") == null) return;
            Class<?> eco = Class.forName("net.milkbowl.vault.economy.Economy");
            @SuppressWarnings({"unchecked", "rawtypes"})
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration((Class) eco);
            if (rsp == null) return;
            economy = rsp.getProvider();
            getBalance = eco.getMethod("getBalance", OfflinePlayer.class);
            withdrawPlayer = eco.getMethod("withdrawPlayer", OfflinePlayer.class, double.class);
            depositPlayer = eco.getMethod("depositPlayer", OfflinePlayer.class, double.class);
            has = eco.getMethod("has", OfflinePlayer.class, double.class);
            Class<?> resp = Class.forName("net.milkbowl.vault.economy.EconomyResponse");
            txSuccess = resp.getMethod("transactionSuccess");
        } catch (Throwable ignored) {
            economy = null;
        }
    }

    public boolean available() {
        return economy != null;
    }

    /** Scoate bani din contul jucatorului. Intoarce true daca tranzactia a reusit. Thread principal. */
    public boolean withdraw(OfflinePlayer p, double amount) {
        if (economy == null || amount <= 0) return false;
        try {
            return (Boolean) txSuccess.invoke(withdrawPlayer.invoke(economy, p, amount));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Adauga bani in contul jucatorului. Intoarce true daca tranzactia a reusit. Thread principal. */
    public boolean deposit(OfflinePlayer p, double amount) {
        if (economy == null || amount <= 0) return false;
        try {
            return (Boolean) txSuccess.invoke(depositPlayer.invoke(economy, p, amount));
        } catch (Throwable t) {
            return false;
        }
    }

    /** True daca jucatorul are cel putin suma data. */
    public boolean has(OfflinePlayer p, double amount) {
        if (economy == null) return false;
        try {
            return (Boolean) has.invoke(economy, p, amount);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Sold sau -1 daca nu exista economie. Se apeleaza pe thread-ul principal. */
    public double balance(OfflinePlayer p) {
        if (economy == null) return -1;
        try {
            Object v = getBalance.invoke(economy, p);
            return ((Number) v).doubleValue();
        } catch (Throwable t) {
            return -1;
        }
    }
}
