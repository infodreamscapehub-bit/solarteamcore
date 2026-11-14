package ro.solarstudios.flowcity.bridge.bank;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.pay.PayModule;

/**
 * Banca: credit (o singura datorie activa, auto-debit la scadenta, penalizare pe zi) si seif de economii cu dobanda zilnica.
 * Banii stau in contul bancar (plugin.bank()). Date in plugins/FlowCityBridge/bank-data.yml. Canal "bank".
 */
public final class BankModule extends JsonModule implements Listener {
    private static final long DAY = 86_400_000L;

    private static final class Offer {
        double amount;
        int days;
        double rate;
        double minHours;
    }

    private YamlConfiguration cfg;
    private YamlConfiguration data;
    private File dataFile;
    private final List<Offer> offers = new ArrayList<>();

    @Override
    public String id() {
        return "bank";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "bank");
        dataFile = new File(plugin.getDataFolder(), "bank-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        loadOffers();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L * 60, 20L * 60);
        on("refresh", (p, j) -> sendState(p));
        on("take", (p, j) -> take(p, (int) num(j, "i", -1)));
        on("repay", (p, j) -> repay(p, num(j, "amount", 0), bool(j, "all", false), bool(j, "cash", false)));
        on("save_in", (p, j) -> saveIn(p, num(j, "amount", 0), bool(j, "all", false)));
        on("save_out", (p, j) -> saveOut(p, num(j, "amount", 0), bool(j, "all", false)));
        Commands.register(plugin, "banca", null, (s, c, l, a) -> {
            if (a.length >= 1 && "reload".equalsIgnoreCase(a[0]) && s.hasPermission("flowcity.bank.admin")) {
                cfg = ModConfig.load(plugin, "bank");
                loadOffers();
                s.sendMessage("§aBanca reincarcata.");
                return true;
            }
            if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {
                if (s instanceof Player && !s.hasPermission("flowcity.bank.admin")) {
                    s.sendMessage("§cNu ai permisiunea.");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) open(t);
                return true;
            }
            if (!(s instanceof Player)) {
                s.sendMessage("Folosire: banca open <jucator>");
                return true;
            }
            Player p = (Player) s;
            if (a.length >= 1) {
                textCommand(p, a);
                return true;
            }
            open(p);
            return true;
        }, null, "bank", "credit");
    }

    // ------------------------------------------------------------------ config / date

    private void loadOffers() {
        offers.clear();
        for (Map<?, ?> m : cfg.getMapList("loans.offers")) {
            try {
                Offer o = new Offer();
                o.amount = Double.parseDouble(String.valueOf(m.get("amount")));
                o.days = Integer.parseInt(String.valueOf(m.get("days")));
                o.rate = Double.parseDouble(String.valueOf(m.get("rate")));
                Object mh = m.get("min-hours");
                o.minHours = mh == null ? 0 : Double.parseDouble(String.valueOf(mh));
                if (o.amount > 0 && o.days > 0) offers.add(o);
            } catch (NumberFormatException ignored) {
                // oferta invalida
            }
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot salva bank-data.yml: " + e);
        }
    }

    private boolean hasLoan(UUID u) {
        return data.contains("loans." + u + ".owed");
    }

    private double owed(UUID u) {
        return data.getDouble("loans." + u + ".owed", 0);
    }

    private long due(UUID u) {
        return data.getLong("loans." + u + ".due", 0);
    }

    private double savings(UUID u) {
        return data.getDouble("savings." + u + ".balance", 0);
    }

    private void setSavings(UUID u, double v) {
        if (v <= 0) data.set("savings." + u, null);
        else {
            data.set("savings." + u + ".balance", round(v));
            if (!data.contains("savings." + u + ".since")) data.set("savings." + u + ".since", System.currentTimeMillis());
        }
    }

    /** Aplica dobanda pe zilele pline trecute de la ultima aplicare. */
    private void accrue(UUID u) {
        double bal = savings(u);
        if (bal <= 0) return;
        long since = data.getLong("savings." + u + ".since", System.currentTimeMillis());
        long now = System.currentTimeMillis();
        long days = (now - since) / DAY;
        if (days <= 0) return;
        double rate = cfg.getDouble("savings.daily-interest-percent", 0.5) / 100.0;
        double max = cfg.getDouble("savings.max-balance", 500000);
        for (long i = 0; i < Math.min(days, 365); i++) bal = Math.min(max, bal * (1 + rate));
        data.set("savings." + u + ".balance", round(bal));
        data.set("savings." + u + ".since", since + days * DAY);
        save();
    }

    private void tick() {
        long now = System.currentTimeMillis();
        List<String> keys = new ArrayList<>();
        ConfigurationSection loans = data.getConfigurationSection("loans");
        if (loans != null) keys.addAll(loans.getKeys(false));
        for (String key : keys) {
            UUID u;
            try {
                u = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (!hasLoan(u) || now < due(u)) continue;
            Player p = Bukkit.getPlayer(u);
            double debt = owed(u);
            if (plugin.bank().withdraw(u, debt)) {
                data.set("loans." + u, null);
                save();
                if (p != null) {
                    p.sendMessage("§9[Banca] §fCreditul de §e" + (int) Math.round(debt) + " lei §fa fost retras automat din cont.");
                    sendState(p);
                }
                continue;
            }
            long last = data.getLong("loans." + u + ".penalized", due(u));
            long days = (now - last) / DAY;
            if (days <= 0) continue;
            double pen = cfg.getDouble("loans.overdue-penalty-percent-per-day", 5) / 100.0;
            double cap = data.getDouble("loans." + u + ".principal", debt) * cfg.getDouble("loans.max-debt-multiplier", 2.5);
            double nd = debt;
            for (long i = 0; i < days; i++) nd = Math.min(Math.max(cap, debt), nd * (1 + pen));
            data.set("loans." + u + ".owed", round(nd));
            data.set("loans." + u + ".penalized", last + days * DAY);
            save();
            if (p != null) {
                p.sendMessage("§9[Banca] §cCreditul tau e restant! §fDatorie: §e" + (int) Math.round(nd) + " lei §7(/banca)");
                sendState(p);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) accrue(p.getUniqueId());
    }

    // ------------------------------------------------------------------ stare

    private double hours(Player p) {
        return p.getStatistic(Statistic.PLAY_ONE_MINUTE) / 72000.0;
    }

    private void sendState(Player p) {
        if (!plugin.channel().hasMod(p)) return;
        UUID u = p.getUniqueId();
        accrue(u);
        JsonObject o = new JsonObject();
        o.addProperty("bank", plugin.bank().balance(u));
        o.addProperty("cash", plugin.money().balance(u));
        o.addProperty("hours", hours(p));
        JsonArray arr = new JsonArray();
        for (int i = 0; i < offers.size(); i++) {
            Offer f = offers.get(i);
            JsonObject j = new JsonObject();
            j.addProperty("i", i);
            j.addProperty("amount", f.amount);
            j.addProperty("days", f.days);
            j.addProperty("rate", f.rate);
            j.addProperty("minHours", f.minHours);
            j.addProperty("total", round(f.amount * (1 + f.rate / 100.0)));
            arr.add(j);
        }
        o.add("offers", arr);
        if (hasLoan(u)) {
            JsonObject l = new JsonObject();
            l.addProperty("owed", owed(u));
            l.addProperty("principal", data.getDouble("loans." + u + ".principal", 0));
            l.addProperty("total", data.getDouble("loans." + u + ".total", owed(u)));
            l.addProperty("due", due(u));
            l.addProperty("taken", data.getLong("loans." + u + ".taken", 0));
            l.addProperty("overdue", System.currentTimeMillis() >= due(u));
            o.add("loan", l);
        }
        o.addProperty("savings", savings(u));
        o.addProperty("savingsRate", cfg.getDouble("savings.daily-interest-percent", 0.5));
        send(p, "state", o);
    }

    private void open(Player p) {
        if (plugin.channel().hasMod(p)) {
            sendState(p);
            JsonObject o = new JsonObject();
            send(p, "open", o);
            return;
        }
        UUID u = p.getUniqueId();
        accrue(u);
        p.sendMessage("§9[Banca] §fCont: §a" + (int) plugin.bank().balance(u) + " lei §7| Economii: §a" + (int) savings(u) + " lei");
        if (hasLoan(u)) p.sendMessage("§fCredit: §e" + (int) Math.round(owed(u)) + " lei §7(/banca plateste <suma|tot>)");
        else {
            for (int i = 0; i < offers.size(); i++) {
                Offer f = offers.get(i);
                p.sendMessage("§7" + (i + 1) + ". §e" + (int) f.amount + " lei §7pe " + f.days + " zile, dobanda " + f.rate + "% §7(/banca credit " + (i + 1) + ")");
            }
        }
        p.sendMessage("§7/banca depune <suma|tot>  /banca retrage <suma|tot>");
    }

    private void result(Player p, boolean ok, String msg) {
        if (plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("msg", msg);
            send(p, "result", o);
            sendState(p);
        } else {
            p.sendMessage((ok ? "§a" : "§c") + msg);
        }
    }

    private void textCommand(Player p, String[] a) {
        String c = a[0].toLowerCase();
        String v = a.length > 1 ? a[1] : "";
        boolean all = "tot".equalsIgnoreCase(v);
        double amount = 0;
        if (!all && !v.isEmpty()) {
            try {
                amount = Double.parseDouble(v.replace(',', '.'));
            } catch (NumberFormatException e) {
                p.sendMessage("§cSuma invalida.");
                return;
            }
        }
        switch (c) {
            case "credit":
                take(p, (int) amount - 1);
                break;
            case "plateste":
                repay(p, amount, all, false);
                break;
            case "depune":
                saveIn(p, amount, all);
                break;
            case "retrage":
                saveOut(p, amount, all);
                break;
            default:
                open(p);
        }
    }

    // ------------------------------------------------------------------ actiuni

    private void take(Player p, int i) {
        UUID u = p.getUniqueId();
        if (i < 0 || i >= offers.size()) {
            result(p, false, "Oferta invalida.");
            return;
        }
        if (hasLoan(u)) {
            result(p, false, "Ai deja un credit activ.");
            return;
        }
        Offer f = offers.get(i);
        if (hours(p) + 1e-9 < f.minHours) {
            result(p, false, "Ai nevoie de " + (int) f.minHours + " ore jucate pentru acest credit.");
            return;
        }
        double total = round(f.amount * (1 + f.rate / 100.0));
        if (!plugin.bank().deposit(u, f.amount)) {
            result(p, false, "Creditul nu a putut fi acordat.");
            return;
        }
        long now = System.currentTimeMillis();
        data.set("loans." + u + ".owed", total);
        data.set("loans." + u + ".principal", f.amount);
        data.set("loans." + u + ".total", total);
        data.set("loans." + u + ".taken", now);
        data.set("loans." + u + ".due", now + f.days * DAY);
        save();
        result(p, true, "Credit acordat: " + (int) f.amount + " lei in cont. De returnat: " + (int) Math.round(total) + " lei.");
    }

    private void repay(Player p, double amount, boolean all, boolean cash) {
        UUID u = p.getUniqueId();
        if (!hasLoan(u)) {
            result(p, false, "Nu ai niciun credit.");
            return;
        }
        double debt = owed(u);
        double amt = round(all ? debt : Math.min(amount, debt));
        if (amt <= 0) {
            result(p, false, "Suma invalida.");
            return;
        }
        if (!cash) {
            if (!plugin.bank().withdraw(u, amt)) {
                result(p, false, "Nu ai destui bani in cont.");
                return;
            }
            applyRepay(p, amt);
            return;
        }
        PayModule pay = PayModule.instance;
        if (pay == null) {
            result(p, false, "Plata indisponibila.");
            return;
        }
        pay.request(p, "Rambursare credit", amt, () -> {
            applyRepay(p, amt);
            return true;
        }, msg -> result(p, false, msg));
    }

    private void applyRepay(Player p, double amt) {
        UUID u = p.getUniqueId();
        double left = round(owed(u) - amt);
        if (left <= 0.009) {
            data.set("loans." + u, null);
            save();
            result(p, true, "Credit rambursat integral.");
        } else {
            data.set("loans." + u + ".owed", left);
            save();
            result(p, true, "Ai platit " + (int) Math.round(amt) + " lei. Ramas: " + (int) Math.round(left) + " lei.");
        }
    }

    private void saveIn(Player p, double amount, boolean all) {
        UUID u = p.getUniqueId();
        accrue(u);
        double max = cfg.getDouble("savings.max-balance", 500000);
        double amt = round(all ? plugin.bank().balance(u) : amount);
        amt = Math.min(amt, Math.max(0, max - savings(u)));
        if (amt <= 0) {
            result(p, false, "Suma invalida sau seiful e plin.");
            return;
        }
        if (!plugin.bank().withdraw(u, amt)) {
            result(p, false, "Nu ai destui bani in cont.");
            return;
        }
        boolean fresh = savings(u) <= 0;
        setSavings(u, savings(u) + amt);
        if (fresh) data.set("savings." + u + ".since", System.currentTimeMillis());
        save();
        result(p, true, "Ai pus " + (int) Math.round(amt) + " lei la economii.");
    }

    private void saveOut(Player p, double amount, boolean all) {
        UUID u = p.getUniqueId();
        accrue(u);
        double bal = savings(u);
        double amt = round(all ? bal : Math.min(amount, bal));
        if (amt <= 0) {
            result(p, false, "Nu ai economii de retras.");
            return;
        }
        plugin.bank().deposit(u, amt);
        setSavings(u, bal - amt);
        save();
        result(p, true, "Ai retras " + (int) Math.round(amt) + " lei din economii.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline() && hasLoan(p.getUniqueId()) && System.currentTimeMillis() >= due(p.getUniqueId())) {
                p.sendMessage("§9[Banca] §cAi un credit restant: §e" + (int) Math.round(owed(p.getUniqueId())) + " lei §7(/banca)");
            }
        }, 100L);
    }
}
