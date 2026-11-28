package ro.solarstudios.flowcity.bridge.items;

import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.taxi.TaxiModule;

/**
 * Aparatul de taxat (itemul existent din FJobs, FLOWCITYCORE_TX_APARAT): arata tariful, distanta si timpul cursei reale din modulul
 * Taxi (cursa soferului sau a clientului) si le poate trimite pasagerului. Nu are propriul tarif: foloseste exact cursa si formula
 * din TaxiModule (taxi.yml), deci nu poate arata o suma diferita de cea platita.
 */
public final class TaxiMeterHandler implements FlowCityItemActions.Handler {
    private FlowCityItemActions fx;

    @Override
    public String id() {
        return "taximetru";
    }

    @Override
    public String legacyMaterial() {
        return "FLOWCITYCORE_TX_APARAT";
    }

    @Override
    public void enable(FlowCityItemActions fx) {
        this.fx = fx;
    }

    @Override
    public boolean onUse(FlowCityItemActions.Ctx c, Block block) {
        show(c.player);
        return true;
    }

    private void show(Player p) {
        TaxiModule t = TaxiModule.instance;
        Panel pn = new Panel("taxi:meter", "Taximetru");
        if (t == null) {
            pn.row("Taxi", "modulul Taxi nu e pornit", Panel.BAD);
            fx.openPanel(p, "taximetru", pn, null);
            return;
        }
        int[] m = t.meter(p.getUniqueId());
        if (m == null) {
            pn.sub(t.onDuty(p.getUniqueId()) ? "la datorie" : "in afara serviciului");
            pn.row("Cursa", "nicio cursa in desfasurare", Panel.INFO);
            pn.row("Cum se porneste", "/taxi duty, apoi /taxi accept si /taxi start", Panel.INFO);
        } else {
            UUID other = t.meterOther(p.getUniqueId());
            Player o = other == null ? null : Bukkit.getPlayer(other);
            pn.sub("cursa in desfasurare");
            pn.row("Tarif", m[0] + " lei", Panel.OK);
            pn.row("Distanta", String.format(Locale.ROOT, "%.2f km", m[1] / 100.0));
            pn.row("Timp", (m[2] / 60) + " min " + (m[2] % 60) + " s");
            pn.row("Asteptare", m[3] + " s");
            pn.row("Client / sofer", o == null ? "-" : o.getName());
            pn.button("show", "Arata tariful pasagerului", Panel.INFO);
            pn.button("stop", "Opreste cursa (plata)", Panel.WARN);
        }
        pn.button("refresh", "Actualizeaza", Panel.INFO);
        fx.openPanel(p, "taximetru", pn, null);
    }

    @Override
    public boolean onUsePlayer(FlowCityItemActions.Ctx c, Player target) {
        tell(c.player, target);
        return true;
    }

    private void tell(Player driver, Player to) {
        TaxiModule t = TaxiModule.instance;
        int[] m = t == null ? null : t.meter(driver.getUniqueId());
        UUID other = t == null ? null : t.meterOther(driver.getUniqueId());
        if (m == null || other == null || !other.equals(to.getUniqueId())) {
            driver.sendMessage("§cAcea persoana nu e clientul cursei tale.");
            return;
        }
        to.sendMessage("§e[Taximetru] §7Tarif: §f" + m[0] + " lei §7| " + String.format(Locale.ROOT, "%.2f", m[1] / 100.0) + " km | " + (m[2] / 60) + " min");
        driver.sendMessage("§aAi aratat tariful lui " + to.getName() + ".");
    }

    @Override
    public void onPanel(FlowCityItemActions.Ctx c, String panelId, String button) {
        Player p = c.player;
        TaxiModule t = TaxiModule.instance;
        if (t == null) return;
        if ("stop".equals(button)) {
            t.meterStop(p);
            fx.log(p, "taximetru", "STOP", null, null, "cursa oprita din aparat");
        } else if ("show".equals(button)) {
            UUID other = t.meterOther(p.getUniqueId());
            Player o = other == null ? null : Bukkit.getPlayer(other);
            if (o != null) tell(p, o);
        }
        show(p);
    }
}
