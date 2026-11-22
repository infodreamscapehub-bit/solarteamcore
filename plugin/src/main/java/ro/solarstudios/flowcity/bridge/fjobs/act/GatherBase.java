package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/**
 * Baza pentru joburile de colectare (miner, taietor de lemne, fermier): resursele obtinute LEGAL in zone/loturi (spargere validata) creeaza o
 * "alocatie" persistenta per material; predarea la depozit/piata accepta doar iteme din inventar in limita alocatiei (nu poti vinde
 * iteme cumparate/furate) si le plateste dupa un tabel de preturi. Fiecare tura are un contract (obiectiv de colectare) cu bonus si termen.
 */
public abstract class GatherBase extends BaseActivity<GatherBase.G> {
    public static final class Contract {
        Material m;
        int need;
        int have;
        long deadline;
        long created;
    }

    public static final class G {
        Contract c;
        long nextContract;
        int collected;
        int sold;
        double soldMoney;
        long lastWarn;
    }

    protected GatherBase(FJobsApi api) {
        super(api);
    }

    @Override
    protected G newState() {
        return new G();
    }

    protected abstract String prefix();

    /** Materialul predat -> pret per bucata (config). */
    protected abstract Map<Material, Double> prices(FJobDef jd);

    protected abstract String depotKind();

    protected String have(Material m) {
        return prefix() + ".have." + m.name();
    }

    protected long allowance(Player p, Material m) {
        return api.counter(p, have(m));
    }

    protected void addAllowance(Player p, Material m, long n) {
        api.addCounter(p, have(m), n);
    }

    /** Materialele pe care le poate cere un contract. */
    protected List<Material> contractPool(Player p, FJobDef jd) {
        return new ArrayList<>(prices(jd).keySet());
    }

    @Override
    protected void onStart(Player p, FJobDef jd, G g) {
        g.nextContract = System.currentTimeMillis() + 1500;
    }

    protected void tickGather(Player p, FJobDef jd, G g, long now) {}

    @Override
    protected void tickJob(Player p, FJobDef jd, G g, long now) {
        if (g.c != null && g.c.deadline > 0 && now > g.c.deadline) {
            api.toast(p, jd.name, "Contractul a expirat.");
            g.c = null;
            g.nextContract = now + 4000;
        }
        if (g.c == null && now >= g.nextContract) newContract(p, jd, g, now);
        mark(p, pts(jd, depotKind()), "END_ROD", 40);
        tickGather(p, jd, g, now);
    }

    protected void newContract(Player p, FJobDef jd, G g, long now) {
        List<Material> pool = contractPool(p, jd);
        if (pool.isEmpty()) return;
        Diff d = diff(p, jd);
        Contract c = new Contract();
        c.m = pool.get(api.rnd().nextInt(pool.size()));
        c.need = Math.max(4, (int) (d.size * jd.cd("contract-per-size", 6)));
        c.created = now;
        c.deadline = now + (long) (jd.cd("contract-minutes", 15) * 60_000);
        g.c = c;
        api.toast(p, jd.name, "Contract nou: preda " + c.need + " x " + nice(c.m) + " la " + depotLabel() + ".");
        api.refreshPanel(p);
    }

    protected String depotLabel() {
        return "depozit";
    }

    protected static String nice(Material m) {
        String s = m.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    protected double sellMult(Player p, FJobDef jd, Material m, long count, FJobDef.Point at) {
        return 1.0;
    }

    /** Preda din inventar tot ce e permis de alocatie; intoarce nr. de iteme predate. */
    protected int handIn(Player p, FJobDef jd, G g, FJobDef.Point at) {
        Diff d = diff(p, jd);
        Map<Material, Double> pr = prices(jd);
        int total = 0;
        double money = 0;
        long xp = 0;
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Material, Double> e : pr.entrySet()) {
            Material m = e.getKey();
            long allow = allowance(p, m);
            if (allow <= 0) continue;
            int have = 0;
            for (ItemStack it : p.getInventory().getContents()) if (it != null && it.getType() == m) have += it.getAmount();
            int n = (int) Math.min(have, allow);
            if (n <= 0) continue;
            double mult = sellMult(p, jd, m, n, at);
            removeItems(p, m, n);
            addAllowance(p, m, -n);
            total += n;
            money += e.getValue() * n * mult;
            xp += Math.round(n * jd.cd("xp-per-item", 1.0));
            parts.add(n + " " + nice(m));
            if (g.c != null && g.c.m == m) g.c.have = Math.min(g.c.need, g.c.have + n);
        }
        if (total == 0) {
            Fx.bar(p, "§eNu ai nimic de predat (doar resursele obtinute in zonele de lucru se accepta).");
            return 0;
        }
        double got = api.pay(p, jd, money * d.pay, Math.round(xp * d.xp), true);
        g.sold += total;
        g.soldMoney += Math.max(0, got);
        api.addCounter(p, prefix() + ".sold", total);
        Fx.sound(p, "ENTITY_EXPERIENCE_ORB_PICKUP", 0.8f, 1.0f);
        api.toast(p, "Predat: " + String.join(", ", parts), "+" + money(Math.max(0, got)));
        if (g.c != null && g.c.have >= g.c.need) {
            double bonus = payBonus(p, jd, g, d);
            api.toast(p, "Contract indeplinit!", "Bonus " + money(Math.max(0, bonus)));
            g.c = null;
            g.nextContract = System.currentTimeMillis() + 4000;
        }
        api.refreshPanel(p);
        return total;
    }

    private double payBonus(Player p, FJobDef jd, G g, Diff d) {
        long el = System.currentTimeMillis() - g.c.created;
        long total = g.c.deadline - g.c.created;
        double speed = 1.0 + 0.3 * Math.max(0, 1.0 - (double) el / Math.max(1, total));
        return payQ(p, jd, jd.cd("contract-bonus", 40) * (g.c.need / 24.0 + 0.5), Math.round(jd.cd("contract-xp", 14)), d, speed, true);
    }

    private static void removeItems(Player p, Material m, int n) {
        ItemStack[] inv = p.getInventory().getContents();
        for (int i = 0; i < inv.length && n > 0; i++) {
            ItemStack it = inv[i];
            if (it == null || it.getType() != m) continue;
            int take = Math.min(n, it.getAmount());
            n -= take;
            if (it.getAmount() <= take) p.getInventory().setItem(i, null);
            else it.setAmount(it.getAmount() - take);
        }
    }

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        FJobDef.Point at = nearest(p, pts(jd, depotKind()), jd.cd("depot-radius", 6));
        if (at == null) return;
        interactDepot(p, jd, e, at);
    }

    protected void interactDepot(Player p, FJobDef jd, PlayerInteractEvent e, FJobDef.Point at) {
        e.setCancelled(true);
        if (cooling(p, "handin", 1200)) return;
        handIn(p, jd, state(p), at);
    }

    @Override
    protected double hud(Player p, FJobDef jd, G g, List<String> lines) {
        lines.add("Colectate (tura): " + g.collected + "   Predate: " + g.sold);
        if (g.c == null) {
            lines.add("Se pregateste un contract...");
            return -1;
        }
        lines.add("Contract: " + nice(g.c.m) + " " + g.c.have + "/" + g.c.need);
        if (g.c.deadline > 0) lines.add("Timp: " + time((g.c.deadline - System.currentTimeMillis()) / 1000));
        return (double) g.c.have / Math.max(1, g.c.need);
    }

    protected void fillCommon(Player p, FJobDef jd, G g, Panel pn) {
        pn.status(g.c == null ? "Fara contract" : "Contract: " + nice(g.c.m) + " " + g.c.have + "/" + g.c.need);
        if (g.c != null) pn.obj("Contract: preda " + (g.c.need - g.c.have) + " x " + nice(g.c.m) + " la " + depotLabel() + " (bonus la final).");
        pn.btn("gps", "Navigheaza la " + depotLabel(), !pts(jd, depotKind()).isEmpty());
        pn.btn("sell", "Preda resursele", true);
        pn.btn("newc", "Alt contract", g.c != null && g.c.have == 0);
        for (Map.Entry<Material, Double> e : prices(jd).entrySet()) {
            pn.reward(nice(e.getKey()) + ": " + money(e.getValue()) + " / buc (ai de predat " + allowance(p, e.getKey()) + ")");
        }
        pn.reward("Contract: bonus " + money(jd.cd("contract-bonus", 40)) + " + XP, mai mare daca il termini repede");
    }

    @Override
    public boolean action(Player p, FJobDef jd, String act, JsonObject j) {
        G g = state(p);
        if ("gps".equals(act)) {
            FJobDef.Point best = null;
            double bd = Double.MAX_VALUE;
            for (FJobDef.Point pt : pts(jd, depotKind())) {
                double d = dist(p, pt);
                if (d < bd) {
                    bd = d;
                    best = pt;
                }
            }
            if (best == null) return false;
            api.gps(p, best);
            return true;
        }
        if ("sell".equals(act)) {
            FJobDef.Point at = nearest(p, pts(jd, depotKind()), jd.cd("depot-radius", 6));
            if (at == null) {
                api.msg(p, false, "Trebuie sa fii langa " + depotLabel() + ".");
                return false;
            }
            if (cooling(p, "handin", 1200)) return false;
            handIn(p, jd, g, at);
            return true;
        }
        if ("newc".equals(act) && g.c != null && g.c.have == 0) {
            if (cooling(p, "newc", 60_000)) {
                api.msg(p, false, "Poti schimba contractul o data pe minut.");
                return false;
            }
            g.c = null;
            g.nextContract = System.currentTimeMillis() + 500;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ utilitare de config

    protected static Map<Material, Double> priceTable(FJobDef jd, String section, Map<String, Double> defaults) {
        Map<Material, Double> m = new LinkedHashMap<>();
        ConfigurationSection s = jd.conf == null ? null : jd.conf.getConfigurationSection(section);
        if (s != null && !s.getKeys(false).isEmpty()) {
            for (String k : s.getKeys(false)) {
                Material mat = Material.matchMaterial(k);
                if (mat != null) m.put(mat, Math.max(0, s.getDouble(k)));
            }
        } else {
            for (Map.Entry<String, Double> e : defaults.entrySet()) {
                Material mat = Material.matchMaterial(e.getKey());
                if (mat != null) m.put(mat, e.getValue());
            }
        }
        return m;
    }

    protected static int tier(Material tool) {
        if (tool == null) return -1;
        String n = tool.name();
        if (!n.endsWith("_PICKAXE") && !n.endsWith("_AXE") && !n.endsWith("_HOE") && !n.endsWith("_SHOVEL")) return -1;
        if (n.startsWith("WOODEN") || n.startsWith("GOLDEN")) return 0;
        if (n.startsWith("STONE")) return 1;
        if (n.startsWith("IRON")) return 2;
        if (n.startsWith("DIAMOND")) return 3;
        if (n.startsWith("NETHERITE")) return 4;
        return 0;
    }
}
