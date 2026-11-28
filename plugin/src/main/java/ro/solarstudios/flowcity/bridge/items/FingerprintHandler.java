package ro.solarstudios.flowcity.bridge.items;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.fjobs.act.Items;
import ro.solarstudios.flowcity.bridge.fjobs.act.VehicleLink;
import ro.solarstudios.flowcity.bridge.mdt.MdtModule;

/**
 * Scanner de amprente. Jucatorii lasa urme pe obiectele atinse (usi, containere, ATM, vehicule), fara urme daca poarta manusi.
 * Politistul colecteaza urme de pe obiect sau ia amprenta unei persoane (proba "PERSON" = baza de date a politiei), apoi compara o
 * urma necunoscuta cu baza. O potrivire se atașeaza dosarului si se poate scrie in MDT. Probele sunt si iteme (proba_amprenta) si
 * inregistrari persistente in baza de date.
 */
public final class FingerprintHandler implements FlowCityItemActions.Handler, Listener {
    private FlowCityItemActions fx;
    private Prints prints;
    private Cases cases;
    private final Map<UUID, Long> selected = new HashMap<UUID, Long>();
    private final Map<UUID, Object[]> match = new HashMap<UUID, Object[]>();
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM HH:mm");

    @Override
    public String id() {
        return "scanner_amprente";
    }

    public Cases cases() {
        return cases;
    }

    @Override
    public void enable(FlowCityItemActions fx) {
        this.fx = fx;
        this.prints = new Prints(fx.cfg.getString("items.scanner_amprente.salt", "flowcity-prints"), fx.cfg.getInt("items.scanner_amprente.traces-per-object", 4));
        this.cases = fx.cases;
        fx.register(new Proba());
        Bukkit.getPluginManager().registerEvents(this, fx.plugin);
        Bukkit.getScheduler().runTaskTimerAsynchronously(fx.plugin, new Runnable() {
            @Override
            public void run() {
                prints.purge(System.currentTimeMillis(), traceAgeMs() * 2);
            }
        }, 6000L, 6000L);
    }

    private long traceAgeMs() {
        return Math.round(fx.cfg.getDouble("items.scanner_amprente.trace-age-minutes", 180) * 60_000.0);
    }

    // ------------------------------------------------------------------ urme lasate de jucatori

    private boolean gloves(Player p) {
        ItemStack off = p.getInventory().getItemInOffHand();
        if (off == null) return false;
        for (String m : fx.cfg.getStringList("items.scanner_amprente.glove-materials")) if (m.equalsIgnoreCase(off.getType().name())) return true;
        return false;
    }

    private boolean traceBlock(Block b) {
        String n = b.getType().name();
        for (String k : fx.cfg.getStringList("items.scanner_amprente.trace-blocks")) if (n.contains(k.toUpperCase(Locale.ROOT))) return true;
        return false;
    }

    static String place(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTouchBlock(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getClickedBlock() == null) return;
        Player p = e.getPlayer();
        if (gloves(p) || !traceBlock(e.getClickedBlock())) return;
        prints.leave(place(e.getClickedBlock()), p.getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTouchVehicle(PlayerInteractEntityEvent e) {
        Entity v = e.getRightClicked();
        if (gloves(e.getPlayer()) || !VehicleLink.isVehicleMod(v)) return;
        prints.leave("veh:" + v.getUniqueId(), e.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ utilizare scanner

    @Override
    public boolean onUse(final FlowCityItemActions.Ctx c, Block block) {
        if (block == null) {
            openMain(c.player, c.serial);
            return true;
        }
        collect(c, place(block), "bloc " + block.getType().name().toLowerCase(Locale.ROOT).replace('_', ' ') + " (" + block.getX() + "," + block.getY() + "," + block.getZ() + ")");
        return true;
    }

    @Override
    public boolean onUseEntity(FlowCityItemActions.Ctx c, Entity target) {
        if (!VehicleLink.isVehicleMod(target)) {
            c.err("Scannerul colecteaza urme de pe obiecte, vehicule sau persoane.");
            return true;
        }
        if (!c.near(target)) {
            c.err("Esti prea departe de vehicul.");
            return true;
        }
        collect(c, "veh:" + target.getUniqueId(), "vehicul");
        return true;
    }

    private void collect(final FlowCityItemActions.Ctx c, final String placeKey, final String label) {
        if (!c.ready("collect", 2000)) return;
        final Player officer = c.player;
        c.start("Colectezi urme", c.num("collect-seconds", 5) * 1000L, null, new Runnable() {
            @Override
            public void run() {
                String own = prints.hash(officer.getUniqueId());
                Set<String> hashes = new LinkedHashSet<String>();
                for (Prints.Trace t : prints.at(placeKey, System.currentTimeMillis(), traceAgeMs())) {
                    if (!t.hash.equals(own)) hashes.add(t.hash);
                }
                if (hashes.isEmpty()) {
                    c.info("Nu ai gasit urme utilizabile.");
                    c.log("COLLECT_NONE", null, label);
                    return;
                }
                long caseId = cases.ensure(officer);
                int n = 0;
                for (String h : hashes) {
                    if (n++ >= 3) break;
                    long id = fx.idb.next("fc_print");
                    fx.idb.exec("INSERT INTO fc_print(id,hash,kind,subject,subject_name,collector,collector_name,ts,place,case_id,note) VALUES (?,?,?,?,?,?,?,?,?,?,?)", id, h, "TRACE", "", "",
                        officer.getUniqueId().toString(), officer.getName(), System.currentTimeMillis(), label, caseId, "");
                    giveSample(officer, id, "TRACE", label, "necunoscut");
                    cases.attach(officer, caseId, "AMPRENTA", id, "Urma #" + id + " de pe " + label);
                    c.log("COLLECT", null, "proba #" + id + " " + label);
                }
                prints.wipe(placeKey);
                c.ok("Ai colectat " + Math.min(3, hashes.size()) + " proba(e). Dosar #" + caseId + ".");
            }
        });
    }

    @Override
    public boolean onUsePlayer(final FlowCityItemActions.Ctx c, final Player target) {
        if (target.equals(c.player)) return true;
        if (!c.near(target)) {
            c.err(target.getName() + " e prea departe.");
            return true;
        }
        if (!c.ready("person", 2000)) return true;
        final Player officer = c.player;
        c.start("Iei amprenta lui " + target.getName(), c.num("person-seconds", 4) * 1000L, new java.util.function.Supplier<String>() {
            @Override
            public String get() {
                if (!target.isOnline()) return "persoana s-a deconectat";
                if (!c.near(target)) return "persoana s-a departat";
                return null;
            }
        }, new Runnable() {
            @Override
            public void run() {
                long caseId = cases.ensure(officer);
                long id = fx.idb.next("fc_print");
                fx.idb.exec("INSERT INTO fc_print(id,hash,kind,subject,subject_name,collector,collector_name,ts,place,case_id,note) VALUES (?,?,?,?,?,?,?,?,?,?,?)", id, prints.hash(target.getUniqueId()), "PERSON",
                    target.getUniqueId().toString(), target.getName(), officer.getUniqueId().toString(), officer.getName(), System.currentTimeMillis(), "persoana", caseId, "");
                giveSample(officer, id, "PERSON", "persoana", target.getName());
                cases.attach(officer, caseId, "AMPRENTA", id, "Amprenta lui " + target.getName() + " (proba #" + id + ")");
                c.ok("Amprenta lui " + target.getName() + " a fost luata si salvata in baza (proba #" + id + ").");
                target.sendMessage("§7" + officer.getName() + " ti-a luat amprentele.");
                c.log("TAKE_PRINT", target, "proba #" + id);
            }
        });
        return true;
    }

    private void giveSample(Player officer, long id, String kind, String where, String who) {
        UUID owner = officer.getUniqueId();
        String serial = fx.serials.issue("proba_amprenta", owner, -1, System.currentTimeMillis());
        List<String> lore = new ArrayList<String>();
        lore.add("§7Tip: §f" + ("PERSON".equals(kind) ? "amprenta persoana" : "urma de pe obiect"));
        lore.add("§7Sursa: §f" + where);
        lore.add("§7Persoana: §f" + who);
        lore.add("§7Colectata: §f" + DATE.format(new Date()) + " de " + officer.getName());
        lore.add("§8Serial: " + serial);
        ItemStack it = ItemKit.make("proba_amprenta", "§bProba amprenta #" + id, lore, serial, 1);
        ItemKit.setData(it, "sample", Long.toString(id));
        Items.give(officer, it);
    }

    // ------------------------------------------------------------------ panouri

    private void openMain(Player p, String serial) {
        long cur = cases.current(p);
        Panel pn = new Panel("scan:main", "Scanner de amprente").sub("Baza politiei");
        pn.row("Dosar activ", cur > 0 ? "#" + cur : "niciunul", cur > 0 ? Panel.OK : Panel.WARN);
        pn.row("Foloseste scannerul", "click dreapta pe obiect / vehicul / persoana", Panel.INFO);
        pn.button("list", "Probele mele", Panel.INFO);
        pn.button("newcase", "Dosar nou", Panel.OK);
        if (cur > 0) pn.button("closecase", "Inchide dosarul", Panel.WARN);
        fx.openPanel(p, "scanner_amprente", pn, serial);
    }

    @Override
    public void onPanel(final FlowCityItemActions.Ctx c, String panelId, String button) {
        final Player p = c.player;
        if ("newcase".equals(button)) {
            long id = cases.open(p);
            c.ok("Dosar #" + id + " deschis.");
            openMain(p, c.serial);
        } else if ("closecase".equals(button)) {
            cases.close(p);
            c.ok("Dosar inchis.");
            openMain(p, c.serial);
        } else if ("list".equals(button)) {
            listSamples(c);
        } else if (button.startsWith("s") && button.length() > 1 && Character.isDigit(button.charAt(1))) {
            try {
                showSample(c, Long.parseLong(button.substring(1)));
            } catch (NumberFormatException ignored) {
                // buton invalid
            }
        } else {
            onSamplePanel(c, button);
        }
    }

    private void listSamples(final FlowCityItemActions.Ctx c) {
        final UUID u = c.player.getUniqueId();
        final String serial = c.serial;
        fx.idb.query(conn -> {
            List<Object[]> rows = new ArrayList<Object[]>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id,kind,subject_name,place,ts FROM fc_print WHERE collector=? ORDER BY id DESC LIMIT 6")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)});
                }
            }
            return rows;
        }, rows -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null) return;
            Panel pn = new Panel("scan:list", "Probele mele").sub(rows.size() + " recente");
            for (Object[] r : rows) {
                String who = "PERSON".equals(r[1]) ? String.valueOf(r[2]) : "necunoscut";
                pn.row("#" + r[0], who + " - " + r[3] + " (" + DATE.format(new Date((Long) r[4])) + ")", "PERSON".equals(r[1]) ? Panel.OK : Panel.INFO);
                pn.button("s" + r[0], "Proba #" + r[0], Panel.INFO);
            }
            if (rows.isEmpty()) pn.row("Nicio proba", "Colecteaza urme de pe obiecte sau ia amprenta unei persoane.", Panel.WARN);
            fx.openPanel(p, "scanner_amprente", pn, serial);
        });
    }

    /** Detalii despre o proba; pentru urme: compararea cu baza. */
    void showSample(final FlowCityItemActions.Ctx c, final long id) {
        final UUID u = c.player.getUniqueId();
        final String serial = c.serial;
        fx.idb.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT hash,kind,subject_name,place,ts,case_id,note,collector_name FROM fc_print WHERE id=?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return new Object[] {rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6), rs.getString(7), rs.getString(8)};
                }
            }
        }, row -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null || row == null) return;
            selected.put(u, id);
            boolean person = "PERSON".equals(row[1]);
            Panel pn = new Panel("scan:sample", "Proba #" + id).sub(person ? "amprenta persoana" : "urma de pe obiect");
            pn.row("Sursa", String.valueOf(row[3]));
            pn.row("Persoana", person ? String.valueOf(row[2]) : (String.valueOf(row[6]).isEmpty() ? "necunoscut" : String.valueOf(row[6])), person ? Panel.OK : Panel.WARN);
            pn.row("Colectata", DATE.format(new Date((Long) row[4])) + " de " + row[7]);
            pn.row("Dosar", row[5] != null && (Long) row[5] > 0 ? "#" + row[5] : "-");
            if (!person) pn.button("compare", "Compara cu baza", Panel.OK);
            Object[] m = match.get(u);
            if (m != null && (Long) m[0] == id) pn.button("mdt", "Scrie potrivirea in MDT", Panel.WARN);
            pn.button("attach", "Ataseaza la dosarul activ", Panel.INFO);
            pn.button("list", "Inapoi", Panel.INFO);
            fx.openPanel(p, "scanner_amprente", pn, serial);
        });
    }

    private void compare(final FlowCityItemActions.Ctx c, final long id) {
        final UUID u = c.player.getUniqueId();
        final String serial = c.serial;
        fx.idb.query(conn -> {
            String hash = null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT hash FROM fc_print WHERE id=? AND kind='TRACE'")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) hash = rs.getString(1);
                }
            }
            if (hash == null) return null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT id,subject,subject_name,collector_name,ts FROM fc_print WHERE kind='PERSON' AND hash=? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, hash);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return new Object[] {null};
                    return new Object[] {rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)};
                }
            }
        }, res -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null || res == null) return;
            Panel pn = new Panel("scan:match", "Comparare proba #" + id);
            if (res[0] == null) {
                pn.row("Rezultat", "Fara potrivire in baza de date", Panel.WARN);
                pn.row("Sfat", "Ia amprenta suspectului cu scannerul si compara din nou.", Panel.INFO);
                match.remove(u);
            } else {
                pn.row("Rezultat", "POTRIVIRE", Panel.OK);
                pn.row("Persoana", String.valueOf(res[2]), Panel.OK);
                pn.row("Amprenta luata", "proba #" + res[0] + " de " + res[3] + ", " + DATE.format(new Date((Long) res[4])));
                match.put(u, new Object[] {id, res[1], res[2]});
                fx.idb.exec("UPDATE fc_print SET note=? WHERE id=?", String.valueOf(res[2]), id);
                c.log("MATCH", null, "proba #" + id + " = " + res[2]);
                pn.button("mdt", "Scrie potrivirea in MDT", Panel.WARN);
            }
            pn.button("attach", "Ataseaza la dosarul activ", Panel.INFO);
            pn.button("list", "Inapoi", Panel.INFO);
            fx.openPanel(p, "scanner_amprente", pn, serial);
        });
    }

    private void toMdt(FlowCityItemActions.Ctx c) {
        Object[] m = match.get(c.player.getUniqueId());
        if (m == null) {
            c.err("Nu ai nicio potrivire de scris.");
            return;
        }
        long caseId = cases.ensure(c.player);
        MdtModule mdt = MdtModule.instance;
        boolean wrote = false;
        if (mdt != null) {
            try {
                wrote = mdt.addItemRecord(c.player, UUID.fromString(String.valueOf(m[1])), "Amprenta #" + m[0] + " potrivita (dosar #" + caseId + ")");
            } catch (IllegalArgumentException ignored) {
                // uuid invalid
            }
        }
        cases.attach(c.player, caseId, "POTRIVIRE", (Long) m[0], "Potrivire cu " + m[2]);
        if (wrote) c.ok("Scris in fisa MDT a lui " + m[2] + ".");
        else c.info("Persoana nu are fisa MDT (sau nu ai acces); potrivirea e in dosarul #" + caseId + ".");
        c.log("MDT_RECORD", null, "proba #" + m[0] + " -> " + m[2] + (wrote ? "" : " (fara fisa)"));
        match.remove(c.player.getUniqueId());
    }

    /** Proba ca item: click dreapta o deschide in panoul scannerului. */
    final class Proba implements FlowCityItemActions.Handler {
        @Override
        public String id() {
            return "proba_amprenta";
        }

        @Override
        public boolean onUse(FlowCityItemActions.Ctx c, Block block) {
            String sid = ItemKit.data(c.item, "sample");
            if (sid == null) {
                c.err("Proba fara date.");
                return true;
            }
            try {
                showSample(c, Long.parseLong(sid));
            } catch (NumberFormatException ex) {
                c.err("Proba fara date.");
            }
            return true;
        }

        @Override
        public void onPanel(FlowCityItemActions.Ctx c, String panelId, String button) {
            FingerprintHandler.this.onPanelFromProba(c, button);
        }
    }

    void onPanelFromProba(FlowCityItemActions.Ctx c, String button) {
        onSamplePanel(c, button);
    }

    private void onSamplePanel(FlowCityItemActions.Ctx c, String button) {
        Long sel = selected.get(c.player.getUniqueId());
        if ("compare".equals(button) && sel != null) compare(c, sel);
        else if ("mdt".equals(button)) toMdt(c);
        else if ("attach".equals(button) && sel != null) {
            long caseId = cases.ensure(c.player);
            cases.attach(c.player, caseId, "AMPRENTA", sel, "Proba #" + sel + " atasata manual");
            fx.idb.exec("UPDATE fc_print SET case_id=? WHERE id=?", caseId, sel);
            c.ok("Proba #" + sel + " a fost atasata dosarului #" + caseId + ".");
        } else if ("list".equals(button)) listSamples(c);
    }
}
