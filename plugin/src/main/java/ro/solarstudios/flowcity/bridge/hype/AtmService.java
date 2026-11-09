package ro.solarstudios.flowcity.bridge.hype;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.hype.HypeDb.AtmEntry;
import ro.solarstudios.flowcity.bridge.hype.HypeDb.PlayerData;

/** ATM: depunere/retragere cash <-> banca si transfer bancar intre jucatori. Totul pe thread-ul principal (atomic). */
public final class AtmService {
    public static final class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    private final FlowCityBridge plugin;
    private final HypeDb hdb;
    private final Set<String> atmBlocks = new HashSet<>();
    private YamlConfiguration cfg;

    public AtmService(FlowCityBridge plugin, HypeDb hdb, YamlConfiguration cfg) {
        this.plugin = plugin;
        this.hdb = hdb;
        this.cfg = cfg;
    }

    public void reload(YamlConfiguration cfg) {
        this.cfg = cfg;
    }

    public void loadBlocks() {
        hdb.loadAtmBlocks().whenComplete((rows, err) -> {
            if (rows == null) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                atmBlocks.clear();
                for (String[] r : rows) atmBlocks.add(PlacedSet.key(r[0], Integer.parseInt(r[1]), Integer.parseInt(r[2]), Integer.parseInt(r[3])));
            });
        });
    }

    // ------------------------------------------------------------------ ATM-uri fizice

    private static String key(Location l) {
        return PlacedSet.key(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public boolean isAtmBlock(Location l) {
        return l != null && l.getWorld() != null && atmBlocks.contains(key(l));
    }

    public boolean setAtmBlock(Location l, boolean present) {
        String k = key(l);
        boolean changed = present ? atmBlocks.add(k) : atmBlocks.remove(k);
        if (changed) hdb.setAtmBlock(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), present);
        return changed;
    }

    public int atmCount() {
        return atmBlocks.size();
    }

    /** Poate jucatorul sa foloseasca ATM-ul acum (oriunde sau langa un ATM fizic)? */
    public boolean canUse(Player p) {
        if (cfg.getBoolean("atm.command-anywhere", true)) return true;
        double max = cfg.getDouble("atm.max-distance", 6);
        Location pl = p.getLocation();
        for (String k : atmBlocks) {
            String[] s = k.split(":");
            if (s.length != 4 || !s[0].equals(pl.getWorld().getName())) continue;
            double dx = Integer.parseInt(s[1]) + 0.5 - pl.getX();
            double dy = Integer.parseInt(s[2]) + 0.5 - pl.getY();
            double dz = Integer.parseInt(s[3]) + 0.5 - pl.getZ();
            if (dx * dx + dy * dy + dz * dz <= max * max) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ date pentru client

    public JsonObject data(Player p, PlayerData d) {
        rollDay(d);
        JsonObject o = new JsonObject();
        o.addProperty("cash", plugin.money().balance(p.getUniqueId()));
        o.addProperty("bank", plugin.bank().balance(p.getUniqueId()));
        o.addProperty("feePercent", cfg.getDouble("atm.fee-percent", 1.0));
        o.addProperty("feeMin", cfg.getDouble("atm.fee-min", 0.5));
        o.addProperty("minAmount", cfg.getDouble("atm.min-amount", 1.0));
        double dw = cfg.getDouble("atm.daily-withdraw-limit", 0);
        double dt = cfg.getDouble("atm.daily-transfer-limit", 0);
        o.addProperty("withdrawLeft", dw <= 0 ? -1 : Math.max(0, HypeMath.round2(dw - d.withdrawnToday)));
        o.addProperty("transferLeft", dt <= 0 ? -1 : Math.max(0, HypeMath.round2(dt - d.transferredToday)));
        JsonArray h = new JsonArray();
        for (AtmEntry e : d.history) {
            JsonObject j = new JsonObject();
            j.addProperty("t", e.type);
            j.addProperty("a", e.amount);
            j.addProperty("f", e.fee);
            j.addProperty("o", e.other);
            j.addProperty("ts", e.ts);
            h.add(j);
        }
        o.add("history", h);
        return o;
    }

    private void rollDay(PlayerData d) {
        long today = HypeMath.dayNumber(System.currentTimeMillis());
        if (d.dayNumber != today) {
            d.dayNumber = today;
            d.withdrawnToday = 0;
            d.transferredToday = 0;
        }
    }

    private double max() {
        return cfg.getDouble("atm.max-per-transaction", 1000000);
    }

    private double min() {
        return cfg.getDouble("atm.min-amount", 1.0);
    }

    private void record(PlayerData d, UUID u, String type, double amount, double fee, String other) {
        long now = System.currentTimeMillis();
        d.history.add(0, new AtmEntry(type, amount, fee, other, now));
        while (d.history.size() > 20) d.history.remove(d.history.size() - 1);
        hdb.addTx(u, type, amount, fee, other, now);
    }

    private static String where(Player p) {
        Location l = p.getLocation();
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    // ------------------------------------------------------------------ operatii

    public Result deposit(Player p, PlayerData d, double raw) {
        if (!canUse(p)) return new Result(false, "Trebuie sa fii langa un ATM.");
        double a = HypeMath.cleanAmount(raw, max());
        if (a < 0 || a < min()) return new Result(false, "Suma invalida (minim " + HypeMath.round2(min()) + ").");
        UUID u = p.getUniqueId();
        if (!plugin.money().has(u, a)) return new Result(false, "Nu ai destui bani cash.");
        if (!plugin.money().withdraw(u, a)) return new Result(false, "Tranzactie esuata.");
        if (!plugin.bank().deposit(u, a)) {
            plugin.money().deposit(u, a); // rambursare: banca a refuzat
            return new Result(false, "Tranzactie esuata.");
        }
        record(d, u, "DEPOSIT", a, 0, "");
        plugin.service().logTransaction(u, "ATM_DEPOSIT", a, "ATM", where(p));
        return new Result(true, "Ai depus $" + Fmt.money(a) + ".");
    }

    public Result withdraw(Player p, PlayerData d, double raw) {
        if (!canUse(p)) return new Result(false, "Trebuie sa fii langa un ATM.");
        double a = HypeMath.cleanAmount(raw, max());
        if (a < 0 || a < min()) return new Result(false, "Suma invalida (minim " + HypeMath.round2(min()) + ").");
        rollDay(d);
        double limit = cfg.getDouble("atm.daily-withdraw-limit", 0);
        if (limit > 0 && d.withdrawnToday + a > limit + 1e-9) {
            return new Result(false, "Limita zilnica de retragere: $" + Fmt.money(Math.max(0, limit - d.withdrawnToday)) + " ramasi.");
        }
        UUID u = p.getUniqueId();
        if (!plugin.bank().has(u, a)) return new Result(false, "Nu ai destui bani in banca.");
        if (!plugin.bank().withdraw(u, a)) return new Result(false, "Tranzactie esuata.");
        if (!plugin.money().deposit(u, a)) {
            plugin.bank().deposit(u, a); // rambursare: economia cash a refuzat
            return new Result(false, "Tranzactie esuata.");
        }
        d.withdrawnToday += a;
        record(d, u, "WITHDRAW", a, 0, "");
        plugin.service().logTransaction(u, "ATM_WITHDRAW", a, "ATM", where(p));
        return new Result(true, "Ai retras $" + Fmt.money(a) + ".");
    }

    public Result transfer(Player p, PlayerData d, String toName, double raw, PlayerData receiverData) {
        if (!canUse(p)) return new Result(false, "Trebuie sa fii langa un ATM.");
        double a = HypeMath.cleanAmount(raw, max());
        if (a < 0 || a < min()) return new Result(false, "Suma invalida (minim " + HypeMath.round2(min()) + ").");
        if (toName == null || toName.trim().isEmpty() || toName.length() > 16) return new Result(false, "Nume de jucator invalid.");
        OfflinePlayer target = Bukkit.getPlayerExact(toName);
        if (target == null) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(toName);
            if (off != null && (off.hasPlayedBefore() || off.isOnline())) target = off;
        }
        if (target == null) return new Result(false, "Jucatorul '" + toName + "' nu exista.");
        UUID u = p.getUniqueId();
        UUID t = target.getUniqueId();
        if (u.equals(t)) return new Result(false, "Nu poti transfera catre tine.");
        rollDay(d);
        double limit = cfg.getDouble("atm.daily-transfer-limit", 0);
        if (limit > 0 && d.transferredToday + a > limit + 1e-9) {
            return new Result(false, "Limita zilnica de transfer: $" + Fmt.money(Math.max(0, limit - d.transferredToday)) + " ramasi.");
        }
        double fee = HypeMath.fee(a, cfg.getDouble("atm.fee-percent", 1.0), cfg.getDouble("atm.fee-min", 0.5));
        double total = HypeMath.round2(a + fee);
        if (!plugin.bank().has(u, total)) return new Result(false, "Ai nevoie de $" + Fmt.money(total) + " in banca (suma + taxa $" + Fmt.money(fee) + ").");
        if (!plugin.bank().withdraw(u, total)) return new Result(false, "Tranzactie esuata.");
        if (!plugin.bank().deposit(t, a)) {
            plugin.bank().deposit(u, total); // rambursare completa
            return new Result(false, "Tranzactie esuata.");
        }
        d.transferredToday += a;
        String tname = target.getName() == null ? toName : target.getName();
        record(d, u, "TRANSFER_OUT", a, fee, tname);
        if (receiverData != null) record(receiverData, t, "TRANSFER_IN", a, 0, p.getName());
        else hdb.addTx(t, "TRANSFER_IN", a, 0, p.getName(), System.currentTimeMillis());
        plugin.service().logTransaction(u, "ATM_TRANSFER", -a, tname, where(p));
        plugin.service().logTransaction(t, "ATM_TRANSFER_IN", a, p.getName(), where(p));
        plugin.notify().send(t, ro.solarstudios.flowcity.bridge.notify.NotifyModule.Type.MONEY, "Transfer primit", "$" + Fmt.money(a) + " de la " + p.getName());
        return new Result(true, "Ai transferat $" + Fmt.money(a) + " catre " + tname + " (taxa $" + Fmt.money(fee) + ").");
    }
}
