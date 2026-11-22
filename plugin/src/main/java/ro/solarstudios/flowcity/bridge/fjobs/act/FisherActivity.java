package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * PESCAR: sistemul de capturi inlocuieste prada vanilla. Fiecare prindere alege o specie dupa: apa (ocean/dulce, dupa bioma), momentul zilei,
 * nivelul jobului, echipamentul (treapta undita = Lure + Luck of the Sea) si daca stai intr-un loc de pescuit ("spot", bonus de specii rare).
 * Greutatea pestelui depinde de treapta undita; pestii grei pot SCAPA (rezistenta fata de undita). Captura se pastreaza intr-un cos
 * persistent (limita in kg); o vinzi la piata ("market", click dreapta sau buton din panou), cu pret pe kg si bonus de trofeu. Se tin recorduri
 * per specie.
 */
public final class FisherActivity extends BaseActivity<FisherActivity.St> {
    static final class Fish {
        String id;
        String name;
        int minG;
        int maxG;
        double perKg;
        int weight;
        int minLevel;
        int minRod;
        boolean spotOnly;
        String water;   // ocean | fresh | any
        String time;    // day | night | any
        double fight;
    }

    static final class St {
        String last = "";
        int catches;
        int escaped;
        boolean inSpot;
        long lastWarn;
    }

    public FisherActivity(FJobsApi api) {
        super(api);
    }

    @Override
    public String kind() {
        return "fisher";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"spot", "Locuri de pescuit (bonus; optional obligatoriu)"});
        l.add(new String[] {"market", "Piata de peste (vanzare)"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        return jd.stationList("market").isEmpty() ? "seteaza piata de peste unde se vinde captura ('market')." : null;
    }

    @Override
    protected St newState() {
        return new St();
    }

    private Map<String, Fish> fish(FJobDef jd) {
        Map<String, Fish> m = new LinkedHashMap<>();
        ConfigurationSection s = jd.conf == null ? null : jd.conf.getConfigurationSection("fish");
        if (s != null && !s.getKeys(false).isEmpty()) {
            for (String id : s.getKeys(false)) {
                ConfigurationSection c = s.getConfigurationSection(id);
                if (c == null) continue;
                Fish f = new Fish();
                f.id = id;
                f.name = c.getString("name", id);
                f.minG = Math.max(10, c.getInt("min-g", 200));
                f.maxG = Math.max(f.minG, c.getInt("max-g", 1500));
                f.perKg = Math.max(0, c.getDouble("price-per-kg", 6));
                f.weight = Math.max(1, c.getInt("weight", 20));
                f.minLevel = Math.max(0, c.getInt("min-level", 0));
                f.minRod = Math.max(0, c.getInt("min-rod", 0));
                f.spotOnly = c.getBoolean("spot-only", false);
                f.water = c.getString("water", "any");
                f.time = c.getString("time", "any");
                f.fight = Math.max(0, Math.min(0.95, c.getDouble("fight", 0)));
                m.put(id, f);
            }
        } else {
            Object[][] d = {
                {"sardine", "Sardina", 60, 220, 7.0, 40, 0, 0, false, "ocean", "any", 0.0},
                {"mackerel", "Macrou", 250, 900, 7.5, 32, 0, 0, false, "ocean", "any", 0.0},
                {"cod", "Cod", 800, 6000, 8.5, 22, 2, 1, false, "ocean", "any", 0.1},
                {"tuna", "Ton", 4000, 30000, 14.0, 8, 5, 2, true, "ocean", "day", 0.3},
                {"carp", "Crap", 800, 5000, 6.0, 38, 0, 0, false, "fresh", "any", 0.05},
                {"trout", "Pastrav", 300, 1800, 9.0, 24, 1, 0, false, "fresh", "day", 0.1},
                {"pike", "Stiuca", 1200, 8000, 9.5, 16, 3, 1, false, "fresh", "any", 0.18},
                {"catfish", "Somn", 4000, 30000, 7.0, 10, 4, 2, false, "fresh", "night", 0.25},
                {"sturgeon", "Nisetru", 8000, 60000, 38.0, 3, 8, 3, true, "any", "any", 0.4}};
            for (Object[] r : d) {
                Fish f = new Fish();
                f.id = (String) r[0];
                f.name = (String) r[1];
                f.minG = (Integer) r[2];
                f.maxG = (Integer) r[3];
                f.perKg = (Double) r[4];
                f.weight = (Integer) r[5];
                f.minLevel = (Integer) r[6];
                f.minRod = (Integer) r[7];
                f.spotOnly = (Boolean) r[8];
                f.water = (String) r[9];
                f.time = (String) r[10];
                f.fight = (Double) r[11];
                m.put(f.id, f);
            }
        }
        return m;
    }

    private int rodTier(ItemStack rod) {
        if (rod == null || rod.getType() != Material.FISHING_ROD) return 0;
        return rod.getEnchantmentLevel(Enchantment.LURE) + rod.getEnchantmentLevel(Enchantment.LUCK);
    }

    private boolean inSpot(Player p, FJobDef jd, org.bukkit.Location at) {
        double r = jd.cd("spot-radius", 25);
        for (FJobDef.Point z : jd.stationList("spot")) if (nearLoc(at, z, r)) return true;
        return false;
    }

    private double stockKg(Player p, FJobDef jd) {
        double g = 0;
        for (Fish f : fish(jd).values()) g += api.counter(p, "fisher.g." + f.id);
        return g / 1000.0;
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        s.inSpot = inSpot(p, jd, p.getLocation());
        mark(p, pts(jd, "market"), "END_ROD", 40);
        if (now % 3000 < 1000) mark(p, pts(jd, "spot"), "WATER_BUBBLE", 40);
    }

    @Override
    public void onFish(Player p, FJobDef jd, PlayerFishEvent e) {
        if (e.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        St s = state(p);
        Entity c = e.getCaught();
        if (!(c instanceof Item)) return;
        c.remove();
        e.setExpToDrop(0);
        long now = System.currentTimeMillis();
        boolean spot = inSpot(p, jd, e.getHook().getLocation());
        if (jd.cb("require-spot", false) && !spot) {
            Fx.bar(p, "§eAici nu se prinde nimic: mergi la un loc de pescuit.");
            return;
        }
        double maxKg = jd.cd("max-stock-kg", 60);
        if (stockKg(p, jd) >= maxKg) {
            Fx.bar(p, "§cCosul e plin (" + (int) maxKg + " kg). Vinde captura la piata.");
            return;
        }
        int lvl = api.jobLevel(p, jd.id);
        ItemStack rod = p.getInventory().getItemInMainHand().getType() == Material.FISHING_ROD ? p.getInventory().getItemInMainHand() : p.getInventory().getItemInOffHand();
        int tier = rodTier(rod);
        String biome = e.getHook().getLocation().getBlock().getBiome().name();
        boolean ocean = biome.contains("OCEAN") || biome.contains("BEACH");
        boolean day = p.getWorld().getTime() < 13000 || p.getWorld().getTime() > 23000;
        List<Fish> ok = new ArrayList<>();
        double total = 0;
        for (Fish f : fish(jd).values()) {
            if (f.minLevel > lvl || f.minRod > tier || (f.spotOnly && !spot)) continue;
            if ("ocean".equals(f.water) && !ocean) continue;
            if ("fresh".equals(f.water) && ocean) continue;
            if ("day".equals(f.time) && !day) continue;
            if ("night".equals(f.time) && day) continue;
            ok.add(f);
        }
        if (ok.isEmpty()) {
            Fx.bar(p, "§7Nu muscă nimic potrivit aici.");
            return;
        }
        double[] w = new double[ok.size()];
        for (int i = 0; i < ok.size(); i++) {
            Fish f = ok.get(i);
            boolean rare = f.weight <= 12;
            w[i] = f.weight * (rare ? 1 + 0.15 * tier + (spot ? 0.5 : 0) : 1);
            total += w[i];
        }
        double r = api.rnd().nextDouble() * total;
        Fish pick = ok.get(ok.size() - 1);
        for (int i = 0; i < ok.size(); i++) {
            r -= w[i];
            if (r <= 0) {
                pick = ok.get(i);
                break;
            }
        }
        if (api.rnd().nextDouble() < pick.fight - tier * 0.06) {
            s.escaped++;
            Fx.bar(p, "§c" + pick.name + " a scapat! Undita mai buna ajuta.");
            Fx.sound(p, "ENTITY_FISHING_BOBBER_SPLASH", 0.7f, 0.7f);
            api.addCounter(p, "fisher.escaped", 1);
            return;
        }
        double u = Math.pow(api.rnd().nextDouble(), Math.max(0.6, 1.8 - 0.1 * tier));
        int g = (int) (pick.minG + (pick.maxG - pick.minG) * u);
        double frac = (double) (g - pick.minG) / Math.max(1, pick.maxG - pick.minG);
        double trophy = frac >= 0.85 ? 1.5 : frac < 0.2 ? 0.8 : 1.0;
        long value = Math.round(pick.perKg * (g / 1000.0) * trophy * 100);
        api.addCounter(p, "fisher.n." + pick.id, 1);
        api.addCounter(p, "fisher.g." + pick.id, g);
        api.addCounter(p, "fisher.v." + pick.id, value);
        long best = api.counter(p, "fisher.best." + pick.id);
        String note = "";
        if (g > best) {
            api.addCounter(p, "fisher.best." + pick.id, g - best);
            note = best > 0 ? " §6(record nou!)" : " §6(prima captura)";
        }
        s.catches++;
        s.last = pick.name + " " + String.format(java.util.Locale.ROOT, "%.2f", g / 1000.0) + " kg";
        api.addCounter(p, "fisher.caught", 1);
        Diff d = diff(p, jd);
        api.pay(p, jd, 0, Math.round(jd.cd("catch-xp", 3) * d.xp * (trophy >= 1.5 ? 1.5 : 1)), false);
        Fx.bar(p, "§a" + (trophy >= 1.5 ? "§6TROFEU! §a" : "") + pick.name + " " + String.format(java.util.Locale.ROOT, "%.2f", g / 1000.0) + " kg" + note);
        Fx.sound(p, "ENTITY_EXPERIENCE_ORB_PICKUP", 0.6f, 1.6f);
        Fx.particle(p, "WATER_SPLASH", p.getLocation().add(0, 1, 0), 12, 0.5);
        api.refreshPanel(p);
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        if (nearest(p, pts(jd, "market"), jd.cd("market-radius", 6)) == null) return;
        e.setCancelled(true);
        if (cooling(p, "sell", 1200)) return;
        sell(p, jd);
    }

    private void sell(Player p, FJobDef jd) {
        Diff d = diff(p, jd);
        double value = 0;
        long n = 0;
        double kg = 0;
        for (Fish f : fish(jd).values()) {
            long cnt = api.counter(p, "fisher.n." + f.id);
            if (cnt <= 0) continue;
            value += api.counter(p, "fisher.v." + f.id) / 100.0;
            kg += api.counter(p, "fisher.g." + f.id) / 1000.0;
            n += cnt;
        }
        if (n == 0) {
            Fx.bar(p, "§eNu ai captura de vandut.");
            return;
        }
        api.clearCounters(p, "fisher.n.");
        api.clearCounters(p, "fisher.g.");
        api.clearCounters(p, "fisher.v.");
        double got = api.pay(p, jd, value * d.pay, Math.round(n * jd.cd("sell-xp", 1) * d.xp), true);
        api.addCounter(p, "fisher.sold", n);
        Fx.sound(p, "ENTITY_VILLAGER_TRADE", 0.8f, 1f);
        api.toast(p, "Captura vanduta: " + n + " pesti", String.format(java.util.Locale.ROOT, "%.1f kg  +", kg) + money(Math.max(0, got)));
        api.refreshPanel(p);
    }

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        double kg = stockKg(p, jd);
        double max = jd.cd("max-stock-kg", 60);
        lines.add("Cos: " + String.format(java.util.Locale.ROOT, "%.1f", kg) + " / " + (int) max + " kg");
        lines.add(s.inSpot ? "Loc de pescuit: DA (bonus specii rare)" : "Loc de pescuit: nu");
        if (!s.last.isEmpty()) lines.add("Ultima: " + s.last);
        lines.add("Prinse: " + s.catches + "  Scapate: " + s.escaped);
        return Math.min(1.0, kg / max);
    }

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        double kg = stockKg(p, jd);
        pn.status(String.format(java.util.Locale.ROOT, "Cos %.1f / %d kg", kg, (int) jd.cd("max-stock-kg", 60)));
        pn.obj("Pescuieste cu undita; speciile depind de apa, ora, nivel si undita.");
        pn.obj("Vinde captura la piata (click dreapta sau butonul de mai jos).");
        ItemStack rod = p.getInventory().getItemInMainHand();
        pn.stat("Treapta undita", String.valueOf(rodTier(rod)) + " (Lure + Luck of the Sea)");
        pn.stat("Prinse (total)", String.valueOf(api.counter(p, "fisher.caught")));
        pn.stat("Scapate", String.valueOf(api.counter(p, "fisher.escaped")));
        pn.stat("Vandute", String.valueOf(api.counter(p, "fisher.sold")));
        int lvl = api.jobLevel(p, jd.id);
        for (Fish f : fish(jd).values()) {
            long n = api.counter(p, "fisher.n." + f.id);
            long best = api.counter(p, "fisher.best." + f.id);
            boolean locked = f.minLevel > lvl;
            pn.reward(f.name + ": " + money(f.perKg) + "/kg" + (n > 0 ? "  (in cos: " + n + ")" : "") + (best > 0 ? "  record " + String.format(java.util.Locale.ROOT, "%.2f", best / 1000.0) + " kg" : "")
                + (locked ? "  [nivel " + f.minLevel + "]" : f.minRod > 0 ? "  [undita " + f.minRod + "]" : "") + (f.spotOnly ? "  [loc special]" : ""));
        }
        pn.btn("sell", "Vinde captura", true);
        pn.btn("gps", "Navigheaza la piata", !pts(jd, "market").isEmpty());
        pn.tip("Vrajeste unditele cu Lure / Luck of the Sea pentru specii mai mari.");
        pn.tip("Trofeu (peste ~85% din greutatea maxima) = pret x1.5.");
        if (!jd.stationList("spot").isEmpty()) pn.tip("Locurile de pescuit cresc sansa de specii rare" + (jd.cb("require-spot", false) ? " si sunt obligatorii." : "."));
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, JsonObject j) {
        if ("sell".equals(act)) {
            if (nearest(p, pts(jd, "market"), jd.cd("market-radius", 6)) == null) {
                api.msg(p, false, "Trebuie sa fii langa piata de peste.");
                return false;
            }
            if (cooling(p, "sell", 1200)) return false;
            sell(p, jd);
            return true;
        }
        if ("gps".equals(act) && !pts(jd, "market").isEmpty()) {
            api.gps(p, pts(jd, "market").get(0));
            return true;
        }
        return false;
    }
}
