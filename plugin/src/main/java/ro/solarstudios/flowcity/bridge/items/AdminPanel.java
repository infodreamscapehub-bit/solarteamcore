package ro.solarstudios.flowcity.bridge.items;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Panou admin pentru iteme (permisiunea flowcity.items.admin): emitere, eliberare/revive pe cel mai apropiat jucator, ultimele actiuni. */
public final class AdminPanel implements FlowCityItemActions.Handler {
    private FlowCityItemActions fx;

    @Override
    public String id() {
        return "_admin";
    }

    @Override
    public void enable(FlowCityItemActions fx) {
        this.fx = fx;
    }

    public static void open(final FlowCityItemActions fx, final Player p) {
        final List<String> ids = new ArrayList<String>();
        for (String id : fx.ids()) if (!id.startsWith("_") && fx.section(id) != null) ids.add(id);
        fx.log.recent(6).whenComplete((rows, err) -> Bukkit.getScheduler().runTask(fx.plugin, () -> {
            if (!p.isOnline()) return;
            Panel pn = new Panel("items:admin", "Iteme FlowCity").sub(ids.size() + " tipuri");
            pn.row("Seriale emise", Integer.toString(fx.serials.size()));
            if (rows != null) {
                for (ItemLog.Row r : rows) pn.row(r.item + " " + r.action, r.actor + (r.target == null || r.target.isEmpty() ? "" : " -> " + r.target) + ": " + r.detail, "DENIED".equals(r.action) || "DUPLICATE".equals(r.action) ? Panel.BAD : Panel.INFO);
            }
            pn.button("release", "Elibereaza cel mai apropiat (catuse)", Panel.WARN);
            pn.button("revive", "Ridica cel mai apropiat (la pamant)", Panel.WARN);
            for (String id : ids) pn.button("g:" + id, "Da: " + ChatStrip.strip(fx.name(id)), Panel.OK);
            fx.openPanel(p, "_admin", pn, null);
        }));
    }

    private Player nearest(Player p) {
        Player best = null;
        double bd = 12 * 12;
        for (Player q : p.getWorld().getPlayers()) {
            if (q.equals(p)) continue;
            double d = q.getLocation().distanceSquared(p.getLocation());
            if (d < bd) {
                bd = d;
                best = q;
            }
        }
        return best;
    }

    @Override
    public void onPanel(FlowCityItemActions.Ctx c, String panelId, String button) {
        Player p = c.player;
        if (!p.hasPermission("flowcity.items.admin")) {
            c.err("Nu ai permisiunea.");
            return;
        }
        if (button.startsWith("g:")) {
            String id = button.substring(2);
            c.info(fx.give(p, id) ? "Ai primit " + ChatStrip.strip(fx.name(id)) + "." : "Item necunoscut.");
        } else if ("release".equals(button)) {
            Player t = nearest(p);
            CuffsHandler ch = (CuffsHandler) fx.handler("catuse");
            c.info(t != null && ch != null && ch.adminRelease(t, p.getName()) ? "Eliberat " + t.getName() + "." : "Nimeni incatusat in apropiere.");
        } else if ("revive".equals(button)) {
            Player t = nearest(p);
            c.info(t != null && MedicalHandlers.adminRevive(t, p.getName()) ? "Ridicat " + t.getName() + "." : "Nimeni la pamant in apropiere.");
        }
        fx.log(p, "_admin", "ADMIN", null, null, button);
        open(fx, p);
    }

}
