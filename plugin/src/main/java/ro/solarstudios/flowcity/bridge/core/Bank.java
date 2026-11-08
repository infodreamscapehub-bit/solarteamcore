package ro.solarstudios.flowcity.bridge.core;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.eadmin.Db;

/**
 * Contul bancar al jucatorului (separat de cash-ul din {@link Money}). Folosit de ATM (Hype) si de aplicatia Bank (ePhone).
 * Toate metodele se apeleaza pe thread-ul principal. Sumele sunt rotunjite la 2 zecimale. Persistenta in tabela core_bank.
 */
public final class Bank {
    private final FlowCityBridge plugin;
    private final Db db;
    private final Map<UUID, Double> cache = new ConcurrentHashMap<>();

    public Bank(FlowCityBridge plugin, Db db) {
        this.plugin = plugin;
        this.db = db;
        try {
            db.async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_bank (uuid TEXT PRIMARY KEY, balance REAL NOT NULL)");
                }
                return null;
            }).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Bank: nu pot crea tabela: " + e);
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    public double balance(UUID u) {
        Double cached = cache.get(u);
        if (cached != null) return cached;
        double v = 0;
        try {
            v = db.async(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT balance FROM core_bank WHERE uuid=?")) {
                    ps.setString(1, u.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? rs.getDouble(1) : 0.0;
                    }
                }
            }).get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Bank: citire esuata pentru " + u + ": " + e);
        }
        cache.put(u, v);
        return v;
    }

    public boolean has(UUID u, double amount) {
        return amount <= 0 || balance(u) + 1e-9 >= round(amount);
    }

    public boolean deposit(UUID u, double amount) {
        amount = round(amount);
        if (amount <= 0) return false;
        set(u, round(balance(u) + amount));
        return true;
    }

    public boolean withdraw(UUID u, double amount) {
        amount = round(amount);
        if (amount <= 0) return false;
        double b = balance(u);
        if (b + 1e-9 < amount) return false;
        set(u, round(b - amount));
        return true;
    }

    /** Transfer atomic (pe thread-ul principal). Fals daca expeditorul nu are destui bani. */
    public boolean transfer(UUID from, UUID to, double amount) {
        amount = round(amount);
        if (amount <= 0 || from.equals(to)) return false;
        if (!withdraw(from, amount)) return false;
        deposit(to, amount);
        return true;
    }

    private void set(UUID u, double v) {
        cache.put(u, v);
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO core_bank(uuid, balance) VALUES (?,?)")) {
                ps.setString(1, u.toString());
                ps.setDouble(2, v);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
