package ro.solarstudios.flowcity.bridge.pay;

import com.google.gson.JsonObject;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.hype.AtmService;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * Plata unificata: orice cumparare (dealer, tuning, pound, benzina, magazin...) cere jucatorului "cash sau card?".
 * Cash = bancnotele din inventar (+ restul din economie); card = soldul din banca, doar cu un card bancar in inventar.
 * Dupa ce banii sunt luati se ruleaza "fulfil" (livrarea); daca returneaza fals, banii se intorc. Jucatorii fara modul
 * platesc direct cash. Canal "pay": server -> client: prompt; client -> server: choose, cancel.
 */
public final class PayModule extends JsonModule {
    public static PayModule instance;
    private static final long TIMEOUT = 90_000L;

    private static final class Pending {
        final int id;
        final String label;
        final double amount;
        final Supplier<Boolean> fulfil;
        final Consumer<String> fail;
        final long at = System.currentTimeMillis();

        Pending(int id, String label, double amount, Supplier<Boolean> fulfil, Consumer<String> fail) {
            this.id = id;
            this.label = label;
            this.amount = amount;
            this.fulfil = fulfil;
            this.fail = fail;
        }
    }

    private final Map<UUID, Pending> pending = new HashMap<>();
    private int nextId = 1;

    @Override
    public String id() {
        return "pay";
    }

    @Override
    protected void onEnable() {
        instance = this;
        on("choose", this::choose);
        on("cancel", (p, j) -> {
            Pending pe = pending.get(p.getUniqueId());
            if (pe != null && pe.id == (int) num(j, "id", -1)) {
                pending.remove(p.getUniqueId());
                pe.fail.accept("Plata a fost anulata.");
            }
        });
    }

    @Override
    public void disable() {
        pending.clear();
        instance = null;
    }

    /** Rezultatul unei plati directe (metoda aleasa deja in interfata, fara promptul "cash sau card"). */
    public static final class Charge {
        public final boolean ok;
        public final String message;
        public final boolean card;

        Charge(boolean ok, String message, boolean card) {
            this.ok = ok;
            this.message = message;
            this.card = card;
        }
    }

    /**
     * Ia banii imediat, cu metoda ceruta de interfata ("cash" sau "card"). Cardul e doar un mijloc de acces la contul
     * bancar: banii se iau din banca, nu dintr-un sold separat. Intoarce rezultatul, fara sa trimita nimic clientului.
     */
    public Charge charge(Player p, String method, double amount, String label) {
        amount = Math.round(amount * 100.0) / 100.0;
        if (amount <= 0) return new Charge(true, "", false);
        UUID u = p.getUniqueId();
        boolean card = "card".equals(method);
        if (card) {
            if (!hasCard(p)) return new Charge(false, "Nu detii un card bancar valid.", true);
            if (!plugin.bank().has(u, amount)) return new Charge(false, "Fonduri insuficiente in cont.", true);
            if (!plugin.bank().withdraw(u, amount)) return new Charge(false, "Tranzactia a fost refuzata de banca.", true);
            return new Charge(true, label, true);
        }
        if (!plugin.money().has(u, amount)) return new Charge(false, "Nu ai destui bani cash.", false);
        if (!plugin.money().withdraw(u, amount)) return new Charge(false, "Tranzactia a esuat.", false);
        return new Charge(true, label, false);
    }

    /** Returneaza bani luati cu {@link #charge}, pe acelasi drum (cash sau banca). */
    public void refund(Player p, boolean card, double amount) {
        amount = Math.round(amount * 100.0) / 100.0;
        if (amount <= 0) return;
        if (card) plugin.bank().deposit(p.getUniqueId(), amount);
        else plugin.money().deposit(p.getUniqueId(), amount);
    }

    /** Returneaza bani unui jucator offline (sesiune intrerupta). */
    public void refund(UUID u, boolean card, double amount) {
        amount = Math.round(amount * 100.0) / 100.0;
        if (amount <= 0) return;
        if (card) plugin.bank().deposit(u, amount);
        else plugin.money().deposit(u, amount);
    }

    /** Jucatorul are un card bancar valid in inventar (sau permisiunea care tine loc de card). */
    public boolean cardOk(Player p) {
        return hasCard(p);
    }

    private boolean hasCard(Player p) {
        if (p.hasPermission("flowcity.hype.atm.nocard")) return true;
        if (AtmService.isCard(p.getInventory().getItemInOffHand())) return true;
        for (ItemStack it : p.getInventory().getContents()) if (AtmService.isCard(it)) return true;
        return false;
    }

    /**
     * Cere plata. fulfil ruleaza dupa ce banii au fost luati (pe thread-ul principal) si trebuie sa trimita singur
     * raspunsul catre client; returneaza fals daca livrarea a esuat (banii se intorc). fail primeste motivul cand plata
     * nu are loc (anulata, expirata, fonduri lipsa).
     */
    public void request(Player p, String label, double amount, Supplier<Boolean> fulfil, Consumer<String> fail) {
        amount = Math.round(amount * 100.0) / 100.0;
        if (amount <= 0) {
            fulfil.get();
            return;
        }
        Pending old = pending.remove(p.getUniqueId());
        if (old != null) old.fail.accept("Plata anterioara a fost inlocuita.");
        if (!plugin.channel().hasMod(p)) {
            // fara mod nu avem meniu: platim cash
            if (!plugin.money().has(p.getUniqueId(), amount) || !plugin.money().withdraw(p.getUniqueId(), amount)) {
                fail.accept("Nu ai destui bani cash.");
                return;
            }
            if (!safe(fulfil)) plugin.money().deposit(p.getUniqueId(), amount);
            return;
        }
        Pending pe = new Pending(nextId++, label, amount, fulfil, fail);
        pending.put(p.getUniqueId(), pe);
        JsonObject o = new JsonObject();
        o.addProperty("id", pe.id);
        o.addProperty("label", label);
        o.addProperty("amount", amount);
        o.addProperty("cash", plugin.money().balance(p.getUniqueId()));
        o.addProperty("bank", plugin.bank().balance(p.getUniqueId()));
        o.addProperty("card", hasCard(p));
        send(p, "prompt", o);
    }

    private static boolean safe(Supplier<Boolean> f) {
        try {
            return Boolean.TRUE.equals(f.get());
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private void choose(Player p, JsonObject j) {
        UUID u = p.getUniqueId();
        Pending pe = pending.get(u);
        if (pe == null || pe.id != (int) num(j, "id", -1)) return;
        pending.remove(u);
        if (System.currentTimeMillis() - pe.at > TIMEOUT) {
            pe.fail.accept("Plata a expirat. Incearca din nou.");
            return;
        }
        boolean card = "card".equals(str(j, "method", "cash"));
        if (card) {
            if (!hasCard(p)) {
                pe.fail.accept("Ai nevoie de un card bancar in inventar.");
                return;
            }
            if (!plugin.bank().has(u, pe.amount) || !plugin.bank().withdraw(u, pe.amount)) {
                pe.fail.accept("Nu ai destui bani in cont (card).");
                return;
            }
        } else if (!plugin.money().has(u, pe.amount) || !plugin.money().withdraw(u, pe.amount)) {
            pe.fail.accept("Nu ai destui bani cash.");
            return;
        }
        if (!safe(pe.fulfil)) {
            if (card) plugin.bank().deposit(u, pe.amount);
            else plugin.money().deposit(u, pe.amount);
        }
    }
}
