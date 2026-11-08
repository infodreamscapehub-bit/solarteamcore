package ro.solarstudios.flowcity.bridge.core;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.eadmin.Db;
import ro.solarstudios.flowcity.bridge.eadmin.VaultHook;

/**
 * Economia "cash" a jucatorului. Foloseste Vault daca exista, altfel un registru intern (tabela core_money).
 * Toate metodele se apeleaza pe thread-ul principal. Sumele sunt rotunjite la 2 zecimale.
 */
public final class Money {
    private final FlowCityBridge plugin;
    private final VaultHook vault;
    private final Db db;
    private final Map<UUID, Double> ledger = new ConcurrentHashMap<>();

    public Money(FlowCityBridge plugin, VaultHook vault, Db db) {
        this.plugin = plugin;
        this.vault = vault;
        this.db = db;
        try {
            db.async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_money (uuid TEXT PRIMARY KEY, balance REAL NOT NULL)");
                }
                return null;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Money: nu pot crea tabela: " + e);
        }
    }

    public boolean usesVault() {
        return vault.available();
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private OfflinePlayer off(UUID u) {
        return Bukkit.getOfflinePlayer(u);
    }

    public double balance(UUID u) {
        if (vault.available()) {
            double b = vault.balance(off(u));
            return b < 0 ? 0 : b;
        }
        return ledgerGet(u);
    }

    public boolean has(UUID u, double amount) {
        return amount <= 0 || balance(u) + 1e-9 >= round(amount);
    }

    /** Scoate bani. Fals daca nu are destui sau suma e invalida. */
    public boolean withdraw(UUID u, double amount) {
        amount = round(amount);
        if (amount <= 0) return false;
        if (vault.available()) return vault.withdraw(off(u), amount);
        double b = ledgerGet(u);
        if (b + 1e-9 < amount) return false;
        ledgerSet(u, round(b - amount));
        return true;
    }

    public boolean deposit(UUID u, double amount) {
        amount = round(amount);
        if (amount <= 0) return false;
        if (vault.available()) return vault.deposit(off(u), amount);
        ledgerSet(u, round(ledgerGet(u) + amount));
        return true;
    }

    private double ledgerGet(UUID u) {
        Double cached = ledger.get(u);
        if (cached != null) return cached;
        double v = 0;
        try {
            v = db.async(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM core_money WHERE uuid=?")) {
                    ps.setString(1, u.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getDouble(1) : 0.0;
                    }
                }
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Money: citire esuata pentru " + u + ": " + e);
        }
        ledger.put(u, v);
        return v;
    }

    private void ledgerSet(UUID u, double v) {
        ledger.put(u, v);
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO core_money(uuid, balance) VALUES (?,?)")) {
                ps.setString(1, u.toString());
                ps.setDouble(2, v);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
