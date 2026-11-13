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

    private double baseBalance(UUID u) {
        if (vault.available()) {
            double b = vault.balance(off(u));
            return b < 0 ? 0 : b;
        }
        return ledgerGet(u);
    }

    private boolean baseWithdraw(UUID u, double amount) {
        if (vault.available()) return vault.withdraw(off(u), amount);
        double b = ledgerGet(u);
        if (b + 1e-9 < amount) return false;
        ledgerSet(u, round(b - amount));
        return true;
    }

    private boolean baseDeposit(UUID u, double amount) {
        if (vault.available()) return vault.deposit(off(u), amount);
        ledgerSet(u, round(ledgerGet(u) + amount));
        return true;
    }

    /** Jucatorul online (doar pe thread-ul principal: inventarul nu se atinge din alt thread). */
    private org.bukkit.entity.Player online(UUID u) {
        if (!Bills.available() || !Bukkit.isPrimaryThread()) return null;
        return Bukkit.getPlayer(u);
    }

    /** Cash total = bancnotele din inventar + banii din economie (salarii etc.). */
    public double balance(UUID u) {
        double base = baseBalance(u);
        org.bukkit.entity.Player p = online(u);
        return p == null ? base : round(base + Bills.value(p));
    }

    public boolean has(UUID u, double amount) {
        return amount <= 0 || balance(u) + 1e-9 >= round(amount);
    }

    /** Scoate bani: intai din bancnote (cu rest in bancnote), apoi din economie. Fals daca nu are destui. */
    public boolean withdraw(UUID u, double amount) {
        amount = round(amount);
        if (amount <= 0) return false;
        org.bukkit.entity.Player p = online(u);
        if (p == null) return baseWithdraw(u, amount);
        long bills = Bills.value(p);
        if (bills <= 0) return baseWithdraw(u, amount);
        if (round(baseBalance(u) + bills) + 1e-9 < amount) return false;
        long whole = (long) Math.ceil(amount - 1e-9);
        if (bills >= whole) {
            if (!Bills.take(p, whole)) return baseWithdraw(u, amount);
            double refund = round(whole - amount); // bani in plus pentru ca bancnotele sunt in lei intregi
            if (refund > 0) baseDeposit(u, refund);
            return true;
        }
        // bancnotele nu ajung: le dam pe toate si completam din economie
        long all = Bills.takeAll(p);
        if (!baseWithdraw(u, round(amount - all))) {
            Bills.give(p, all);
            return false;
        }
        return true;
    }

    /** Depune in economie (salarii, rambursari). */
    public boolean deposit(UUID u, double amount) {
        amount = round(amount);
        if (amount <= 0) return false;
        return baseDeposit(u, amount);
    }

    /** Da banii ca bancnote in inventar (retragere de la ATM); restul sub 1 leu merge in economie. */
    public boolean depositBills(org.bukkit.entity.Player p, double amount) {
        amount = round(amount);
        if (amount <= 0) return false;
        if (!Bills.available() || !Bukkit.isPrimaryThread()) return baseDeposit(p.getUniqueId(), amount);
        long whole = (long) Math.floor(amount + 1e-9);
        double cents = round(amount - whole);
        Bills.give(p, whole);
        if (cents > 0) baseDeposit(p.getUniqueId(), cents);
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
