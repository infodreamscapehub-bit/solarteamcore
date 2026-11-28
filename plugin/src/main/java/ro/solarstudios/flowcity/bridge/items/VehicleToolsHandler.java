package ro.solarstudios.flowcity.bridge.items;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.fjobs.act.VehicleLink;

/**
 * Scannerul de diagnoza citeste starea REALA a vehiculului din mod (caroserie, combustibil, kilometraj, ITP, RCA, motor, roti), iar trusa
 * de reparatii schimba starea reala a caroseriei (Health in datele masinii). Serverul ceruse modul prin comenzile "fccars diag/repair";
 * modul raspunde cu "fcitem vehreport" iar raspunsul e acceptat doar de la consola si doar pentru o tranzactie deschisa de acel jucator.
 * Piesele auto se comanda dupa diagnoza (se platesc, se livreaza dupa o intarziere, persistent in fc_order) si se consuma la reparatie.
 */
public final class VehicleToolsHandler {
    private VehicleToolsHandler() {}

    private static final class Pending {
        UUID player;
        String kind;
        String serial;
        String itemId;
        String partSerial;
        long at;
        String entity;
    }

    private static FlowCityItemActions fx;
    private static int txSeq = 0;
    private static final Map<Integer, Pending> PENDING = new HashMap<Integer, Pending>();
    private static final Map<UUID, JsonObject> LAST = new HashMap<UUID, JsonObject>();
    private static final Map<UUID, Long> LAST_AT = new HashMap<UUID, Long>();
    private static boolean timer;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM/yyyy");

    static void init(FlowCityItemActions f) {
        fx = f;
        if (timer) return;
        timer = true;
        Bukkit.getScheduler().runTaskTimer(f.plugin, new Runnable() {
            @Override
            public void run() {
                deliverOrders();
                long now = System.currentTimeMillis();
                PENDING.values().removeIf(p -> now - p.at > 40_000L);
            }
        }, 200L, 400L);
    }

    private static boolean console(String cmd) {
        try {
            return Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static int open(Player p, String kind, String serial, String itemId, String entity, String partSerial) {
        Pending pd = new Pending();
        pd.player = p.getUniqueId();
        pd.kind = kind;
        pd.serial = serial;
        pd.itemId = itemId;
        pd.entity = entity;
        pd.partSerial = partSerial;
        pd.at = System.currentTimeMillis();
        int tx = ++txSeq;
        PENDING.put(tx, pd);
        return tx;
    }

    // ------------------------------------------------------------------ raspunsul modului

    /** Apelat din "/fcitem vehreport" (doar consola). */
    public static void report(ItemsModule m, String player, String txS, String b64) {
        final JsonObject o;
        final int tx;
        try {
            tx = Integer.parseInt(txS);
            o = new JsonParser().parse(ItemsModule.unb64(b64)).getAsJsonObject();
        } catch (RuntimeException ex) {
            return;
        }
        final Pending pd = PENDING.remove(tx);
        if (pd == null) return;
        final Player p = Bukkit.getPlayerExact(player);
        if (p == null || !p.getUniqueId().equals(pd.player)) {
            if ("repair".equals(pd.kind) && !bool(o, "ok")) refundLater(pd.player, "piesa_auto", 1, "reparatie anulata (jucator offline)");
            return;
        }
        Bukkit.getScheduler().runTask(fx.plugin, new Runnable() {
            @Override
            public void run() {
                if ("diag".equals(pd.kind)) onDiag(p, pd, o);
                else if ("repair".equals(pd.kind)) onRepair(p, pd, o);
            }
        });
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsBoolean();
    }

    private static String str(JsonObject o, String k, String d) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? d : e.getAsString();
    }

    private static long lng(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? 0 : e.getAsLong();
    }

    // ------------------------------------------------------------------ diagnoza

    private static void onDiag(Player p, Pending pd, JsonObject o) {
        if (!bool(o, "ok")) {
            p.sendMessage("§c" + str(o, "err", "Diagnoza a esuat."));
            return;
        }
        if (!fx.charge(p, pd.itemId, pd.serial)) {
            p.sendMessage("§cScannerul nu mai are utilizari.");
            return;
        }
        LAST.put(p.getUniqueId(), o);
        LAST_AT.put(p.getUniqueId(), System.currentTimeMillis());
        fx.log(p, "scanner_diagnoza", "SCAN", null, null, str(o, "plate", "fara numar") + " caroserie " + lng(o, "body") + "%");
        showDiag(p, pd.serial, o);
    }

    private static void showDiag(Player p, String serial, JsonObject o) {
        long now = System.currentTimeMillis();
        long body = lng(o, "body");
        long fuel = lng(o, "fuel");
        Panel pn = new Panel("veh:diag", "Diagnoza vehicul").sub(str(o, "name", str(o, "model", "vehicul")) + (str(o, "plate", "").isEmpty() ? "" : " - " + str(o, "plate", "")));
        pn.row("Proprietar", str(o, "owner", "necunoscut"));
        if (bool(o, "hasBody")) pn.bar("Caroserie", body + "%", (int) body, body >= 70 ? Panel.OK : body >= 35 ? Panel.WARN : Panel.BAD);
        else pn.row("Caroserie", "nu are (nu se poate deteriora)", Panel.INFO);
        if (fuel >= 0) pn.bar("Combustibil", fuel + "%", (int) fuel, fuel >= 25 ? Panel.OK : fuel >= 10 ? Panel.WARN : Panel.BAD);
        pn.row("Motor / roti", str(o, "engine", "-") + " / " + str(o, "wheels", "-"));
        pn.row("Kilometraj", (lng(o, "odo") / 1000.0) + " km");
        long itp = lng(o, "itp");
        long ins = lng(o, "ins");
        pn.row("ITP", itp > now ? "valabil pana la " + DATE.format(new Date(itp)) : "EXPIRAT", itp > now ? Panel.OK : Panel.BAD);
        pn.row("RCA", ins > now ? "valabil pana la " + DATE.format(new Date(ins)) : "EXPIRAT", ins > now ? Panel.OK : Panel.BAD);
        List<String> faults = new ArrayList<String>();
        if (bool(o, "hasBody") && body < fx.cfg.getInt("items.scanner_diagnoza.fault-body-below", 70)) faults.add("caroserie deteriorata");
        if (fuel >= 0 && fuel < 10) faults.add("rezervor aproape gol");
        if (itp <= now) faults.add("ITP expirat");
        if (ins <= now) faults.add("RCA expirat");
        pn.row("Probleme", faults.isEmpty() ? "niciuna" : String.join(", ", faults), faults.isEmpty() ? Panel.OK : Panel.WARN);
        int price = fx.cfg.getInt("items.piesa_auto.price", 250);
        pn.row("Piesa auto", price + " lei, livrare in " + fx.cfg.getInt("items.piesa_auto.delivery-minutes", 2) + " min", Panel.INFO);
        pn.button("order1", "Comanda 1 piesa", Panel.OK);
        pn.button("order3", "Comanda 3 piese", Panel.OK);
        pn.button("attach", "Ataseaza la dosar", Panel.INFO);
        pn.button("orders", "Comenzile mele", Panel.INFO);
        fx.openPanel(p, "scanner_diagnoza", pn, serial);
    }

    // ------------------------------------------------------------------ reparatie

    private static void onRepair(Player p, Pending pd, JsonObject o) {
        if (!bool(o, "ok")) {
            refundLater(p.getUniqueId(), "piesa_auto", 1, "reparatie esuata");
            Bukkit.getScheduler().runTaskLater(fx.plugin, () -> deliverOrders(), 5L);
            p.sendMessage("§c" + str(o, "err", "Reparatia a esuat.") + " Piesa ti-a fost returnata.");
            return;
        }
        if (!fx.charge(p, pd.itemId, pd.serial)) p.sendMessage("§eTrusa nu mai are utilizari.");
        fx.log(p, "trusa_reparatii", "REPAIR", null, null, str(o, "plate", "fara numar") + ": " + lng(o, "before") + "% -> " + lng(o, "after") + "%");
        p.sendMessage("§aReparat: caroserie " + lng(o, "before") + "% -> " + lng(o, "after") + "%.");
        FlowCityItemActions.snd(p, "minecraft:block.anvil.use", 0.6f, 1.4f);
    }

    // ------------------------------------------------------------------ comenzi piese

    private static void refundLater(UUID player, String item, int qty, String note) {
        fx.idb.exec("INSERT INTO fc_order(id,player,item,qty,due,delivered,plate) VALUES (?,?,?,?,?,0,?)", fx.idb.next("fc_order"), player.toString(), item, qty, System.currentTimeMillis(), note);
    }

    private static void order(final Player p, int qty) {
        JsonObject last = LAST.get(p.getUniqueId());
        Long at = LAST_AT.get(p.getUniqueId());
        if (last == null || at == null || System.currentTimeMillis() - at > 10 * 60_000L) {
            p.sendMessage("§cFa o diagnoza noua inainte sa comanzi piese.");
            return;
        }
        if (!fx.cooldowns.tryUse(p.getUniqueId() + ":order", 3000L)) return;
        int price = fx.cfg.getInt("items.piesa_auto.price", 250);
        final int total = price * qty;
        if (!fx.plugin.money().withdraw(p.getUniqueId(), total)) {
            p.sendMessage("§cNu ai " + total + " lei pentru comanda.");
            return;
        }
        long due = System.currentTimeMillis() + fx.cfg.getInt("items.piesa_auto.delivery-minutes", 2) * 60_000L;
        fx.idb.exec("INSERT INTO fc_order(id,player,item,qty,due,delivered,plate) VALUES (?,?,?,?,?,0,?)", fx.idb.next("fc_order"), p.getUniqueId().toString(), "piesa_auto", qty, due, str(last, "plate", ""));
        fx.log(p, "scanner_diagnoza", "ORDER", null, null, qty + " piese, " + total + " lei");
        p.sendMessage("§aAi comandat " + qty + " piesa(e) auto pentru " + total + " lei. Livrare in " + fx.cfg.getInt("items.piesa_auto.delivery-minutes", 2) + " min.");
    }

    private static void listOrders(final Player p, final String serial) {
        final UUID u = p.getUniqueId();
        fx.idb.query(conn -> {
            List<Object[]> rows = new ArrayList<Object[]>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT qty,due,delivered,plate FROM fc_order WHERE player=? AND item='piesa_auto' ORDER BY id DESC LIMIT 6")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[] {rs.getInt(1), rs.getLong(2), rs.getInt(3), rs.getString(4)});
                }
            }
            return rows;
        }, rows -> {
            Player q = Bukkit.getPlayer(u);
            if (q == null) return;
            Panel pn = new Panel("veh:orders", "Comenzile mele de piese");
            long now = System.currentTimeMillis();
            for (Object[] r : rows) {
                boolean done = (Integer) r[2] == 1;
                long due = (Long) r[1];
                pn.row(r[0] + "x piesa", done ? "livrata" : (due <= now ? "se livreaza" : "in " + Math.max(1, (due - now) / 60000) + " min"), done ? Panel.OK : Panel.WARN);
            }
            if (rows.isEmpty()) pn.row("Nicio comanda", "Fa o diagnoza si comanda piese.", Panel.INFO);
            pn.button("back", "Inapoi", Panel.INFO);
            fx.openPanel(q, "scanner_diagnoza", pn, serial);
        });
    }

    static void deliverOrders() {
        if (fx == null) return;
        final long now = System.currentTimeMillis();
        fx.idb.query(conn -> {
            List<Object[]> rows = new ArrayList<Object[]>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id,player,item,qty FROM fc_order WHERE delivered=0 AND due<=? LIMIT 50")) {
                ps.setLong(1, now);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4)});
                }
            }
            return rows;
        }, rows -> {
            for (Object[] r : rows) {
                Player p;
                try {
                    p = Bukkit.getPlayer(UUID.fromString((String) r[1]));
                } catch (IllegalArgumentException ex) {
                    continue;
                }
                if (p == null) continue;
                int qty = (Integer) r[3];
                int given = 0;
                for (int i = 0; i < qty; i++) if (fx.give(p, (String) r[2])) given++;
                fx.idb.exec("UPDATE fc_order SET delivered=1 WHERE id=?", r[0]);
                if (given > 0) p.sendMessage("§aTi-au sosit " + given + "x piesa auto.");
            }
        });
    }

    // ------------------------------------------------------------------ handlere

    private static boolean vehicleTarget(FlowCityItemActions.Ctx c, Entity target) {
        if (!VehicleLink.isVehicleMod(target)) {
            c.err("Foloseste itemul pe un vehicul.");
            return false;
        }
        if (!c.near(target)) {
            c.err("Esti prea departe de vehicul.");
            return false;
        }
        return true;
    }

    private static java.util.function.Supplier<String> targetOk(final FlowCityItemActions.Ctx c, final Entity target) {
        return new java.util.function.Supplier<String>() {
            @Override
            public String get() {
                if (!target.isValid()) return "vehiculul a disparut";
                if (!c.near(target)) return "te-ai departat de vehicul";
                return null;
            }
        };
    }

    public static final class Scanner implements FlowCityItemActions.Handler {
        @Override
        public String id() {
            return "scanner_diagnoza";
        }

        @Override
        public void enable(FlowCityItemActions f) {
            init(f);
        }

        @Override
        public boolean onUse(FlowCityItemActions.Ctx c, org.bukkit.block.Block block) {
            c.info("Click dreapta pe un vehicul ca sa-l scanezi.");
            return true;
        }

        @Override
        public boolean onUseEntity(final FlowCityItemActions.Ctx c, final Entity target) {
            if (!vehicleTarget(c, target)) return true;
            if (!c.ready("scan", 2000)) return true;
            c.start("Scanezi vehiculul", c.num("scan-seconds", 6) * 1000L, targetOk(c, target), new Runnable() {
                @Override
                public void run() {
                    int tx = open(c.player, "diag", c.serial, c.id, target.getUniqueId().toString(), null);
                    if (!console("fccars diag " + c.player.getName() + " " + target.getUniqueId() + " " + tx)) {
                        PENDING.remove(tx);
                        c.err("Modulul vehiculelor nu a raspuns.");
                    }
                }
            });
            return true;
        }

        @Override
        public void onPanel(FlowCityItemActions.Ctx c, String panelId, String button) {
            JsonObject last = LAST.get(c.player.getUniqueId());
            if ("order1".equals(button)) order(c.player, 1);
            else if ("order3".equals(button)) order(c.player, 3);
            else if ("orders".equals(button)) listOrders(c.player, c.serial);
            else if ("back".equals(button) && last != null) showDiag(c.player, c.serial, last);
            else if ("attach".equals(button) && last != null) {
                long caseId = fx.cases.ensure(c.player);
                fx.cases.attach(c.player, caseId, "VEHICUL", 0, "Diagnoza " + str(last, "plate", "fara numar") + ": caroserie " + lng(last, "body") + "%");
                c.ok("Diagnoza a fost atasata dosarului #" + caseId + ".");
                c.log("ATTACH", null, "dosar #" + caseId);
            }
        }
    }

    public static final class Repair implements FlowCityItemActions.Handler {
        @Override
        public String id() {
            return "trusa_reparatii";
        }

        @Override
        public void enable(FlowCityItemActions f) {
            init(f);
        }

        @Override
        public boolean onUse(FlowCityItemActions.Ctx c, org.bukkit.block.Block block) {
            c.info("Ai nevoie de o piesa auto si de un vehicul deteriorat. Click dreapta pe vehicul.");
            return true;
        }

        @Override
        public boolean onUseEntity(final FlowCityItemActions.Ctx c, final Entity target) {
            if (!vehicleTarget(c, target)) return true;
            if (fx.findById(c.player, "piesa_auto") == null) {
                c.err("Ai nevoie de o piesa auto (comand-o cu scannerul de diagnoza).");
                return true;
            }
            if (!c.ready("repair", 2000)) return true;
            c.start("Reperi vehiculul", c.num("repair-seconds", 10) * 1000L, targetOk(c, target), new Runnable() {
                @Override
                public void run() {
                    ItemStack part = fx.findById(c.player, "piesa_auto");
                    if (part == null) {
                        c.err("Piesa nu mai e in inventar.");
                        return;
                    }
                    String ps = ItemKit.serialOf(part);
                    // piesa se consuma inainte; daca modul refuza reparatia, se returneaza (vezi onRepair)
                    part.setAmount(0);
                    if (ps != null) fx.serials.revoke(ps);
                    int tx = open(c.player, "repair", c.serial, c.id, target.getUniqueId().toString(), ps);
                    int pct = Math.max(5, Math.min(100, c.num("repair-percent", 35)));
                    if (!console("fccars repair " + c.player.getName() + " " + target.getUniqueId() + " " + tx + " " + pct)) {
                        PENDING.remove(tx);
                        refundLater(c.player.getUniqueId(), "piesa_auto", 1, "modul vehicule indisponibil");
                        deliverOrders();
                        c.err("Modulul vehiculelor nu a raspuns; piesa ti-a fost returnata.");
                    }
                }
            });
            return true;
        }
    }

    /** Piesa auto: consumabil emis la livrarea unei comenzi. */
    public static final class Part implements FlowCityItemActions.Handler {
        @Override
        public String id() {
            return "piesa_auto";
        }

        @Override
        public void enable(FlowCityItemActions f) {
            init(f);
        }

        @Override
        public boolean onUse(FlowCityItemActions.Ctx c, org.bukkit.block.Block block) {
            c.info("Piesa auto: foloseste-o cu trusa de reparatii pe un vehicul deteriorat.");
            return true;
        }
    }

}
