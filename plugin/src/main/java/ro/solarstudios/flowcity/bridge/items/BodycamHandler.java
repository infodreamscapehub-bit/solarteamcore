package ro.solarstudios.flowcity.bridge.items;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Bodycam: cat timp e pornita (si ramane la jucator), serverul tine o sesiune persistenta cu ora de start/sfarsit, colegii aflati in
 * apropiere si "marcajele" automate puse de actiunile cu iteme (catuse, amprente, defibrilator etc.) si de apelurile radio. Un coleg
 * care foloseste propria bodycam pe ofiter poate confirma inregistrarea (raport coleg), iar sesiunea se ataseaza dosarului.
 * Nu se inregistreaza video: sesiunea e un jurnal de eveniment-timp-loc, ceea ce un server fara captura video poate garanta.
 */
public final class BodycamHandler implements FlowCityItemActions.Handler {
    static final class Session {
        long id;
        UUID officer;
        String name;
        long start;
        String serial;
        int marks;
        long lastMark;
        final Set<String> nearby = new LinkedHashSet<String>();
    }

    private FlowCityItemActions fx;
    private final Map<UUID, Session> active = new HashMap<UUID, Session>();
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM HH:mm");
    private static final SimpleDateFormat HM = new SimpleDateFormat("HH:mm:ss");

    @Override
    public String id() {
        return "bodycam";
    }

    @Override
    public void enable(FlowCityItemActions fx) {
        this.fx = fx;
        Bukkit.getScheduler().runTaskTimer(fx.plugin, new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, 100L, 100L);
    }

    @Override
    public void disable() {
        for (Session s : new ArrayList<Session>(active.values())) stop(s, "oprire server");
    }

    @Override
    public void onQuit(Player p) {
        Session s = active.get(p.getUniqueId());
        if (s != null) stop(s, "deconectat");
    }

    boolean recording(Player p) {
        return active.containsKey(p.getUniqueId());
    }

    private void tick() {
        long now = System.currentTimeMillis();
        long max = Math.round(fx.cfg.getDouble("items.bodycam.max-minutes", 120) * 60_000.0);
        double radius = fx.cfg.getDouble("items.bodycam.nearby-radius", 12);
        for (Session s : new ArrayList<Session>(active.values())) {
            Player p = Bukkit.getPlayer(s.officer);
            if (p == null || !p.isOnline()) {
                stop(s, "deconectat");
                continue;
            }
            if (fx.findBySerial(p, s.serial) == null) {
                stop(s, "bodycam pierduta");
                p.sendMessage("§cBodycam oprita: nu o mai ai la tine.");
                continue;
            }
            if (!fx.allowed(p, "bodycam") || fx.serials.validate(s.serial, "bodycam") != Serials.Check.OK) {
                stop(s, "drept pierdut");
                continue;
            }
            if (now - s.start > max) {
                stop(s, "durata maxima");
                p.sendMessage("§eBodycam oprita: durata maxima atinsa.");
                continue;
            }
            for (Player q : p.getWorld().getPlayers()) {
                if (q.equals(p) || s.nearby.size() >= 16) continue;
                if (q.getLocation().distanceSquared(p.getLocation()) <= radius * radius) s.nearby.add(q.getName());
            }
        }
    }

    /** Apelat din jurnalul framework-ului: adauga un marcaj in sesiunea activa a actorului (cel mult unul la 2 secunde). */
    void autoMark(Player actor, String text) {
        Session s = active.get(actor.getUniqueId());
        if (s == null) return;
        long now = System.currentTimeMillis();
        if (now - s.lastMark < 2000L || s.marks >= 300) return;
        s.lastMark = now;
        s.marks++;
        mark(s, actor, text);
    }

    private void mark(Session s, Player at, String text) {
        fx.idb.exec("INSERT INTO fc_mark(session,ts,world,x,y,z,text) VALUES (?,?,?,?,?,?,?)", s.id, System.currentTimeMillis(), at.getWorld().getName(), at.getLocation().getBlockX(),
            at.getLocation().getBlockY(), at.getLocation().getBlockZ(), text.length() > 120 ? text.substring(0, 120) : text);
    }

    private void start(FlowCityItemActions.Ctx c) {
        Player p = c.player;
        long id = fx.idb.next("fc_bodycam");
        Session s = new Session();
        s.id = id;
        s.officer = p.getUniqueId();
        s.name = p.getName();
        s.start = System.currentTimeMillis();
        s.serial = c.serial;
        active.put(s.officer, s);
        fx.idb.exec("INSERT INTO fc_bodycam(id,officer,officer_name,start,end,status,note,verified_by,nearby,marks) VALUES (?,?,?,?,0,'REC','','','',0)", id, s.officer.toString(), s.name, s.start);
        mark(s, p, "Bodycam pornita");
        c.log("START", null, "sesiune #" + id);
        c.ok("Bodycam pornita (sesiune #" + id + ").");
        FlowCityItemActions.snd(p, "minecraft:block.note_block.pling", 0.7f, 1.6f);
    }

    private void stop(Session s, String reason) {
        if (active.remove(s.officer) == null) return;
        long end = System.currentTimeMillis();
        fx.idb.exec("UPDATE fc_bodycam SET end=?, status='ENDED', nearby=?, marks=?, note=? WHERE id=?", end, String.join(",", s.nearby), s.marks, reason, s.id);
        Player p = Bukkit.getPlayer(s.officer);
        fx.log(p, "bodycam", "STOP", null, null, "sesiune #" + s.id + " (" + reason + ")");
    }

    @Override
    public boolean onUse(FlowCityItemActions.Ctx c, Block block) {
        Session s = active.get(c.player.getUniqueId());
        if (s == null) {
            if (!c.ready("toggle", 1500)) return true;
            start(c);
            return true;
        }
        openMain(c, s);
        return true;
    }

    private void openMain(FlowCityItemActions.Ctx c, Session s) {
        Panel pn = new Panel("cam:main", "Bodycam").sub(s == null ? "oprita" : "REC sesiune #" + s.id);
        if (s != null) {
            pn.row("Pornita", HM.format(new Date(s.start)), Panel.OK);
            pn.row("Durata", ((System.currentTimeMillis() - s.start) / 60000) + " min");
            pn.row("Marcaje", Integer.toString(s.marks));
            pn.row("In apropiere", s.nearby.isEmpty() ? "-" : String.join(", ", s.nearby));
            pn.button("stop", "Opreste inregistrarea", Panel.BAD);
        } else {
            pn.button("start", "Porneste", Panel.OK);
        }
        pn.button("list", "Inregistrarile mele", Panel.INFO);
        fx.openPanel(c.player, "bodycam", pn, c.serial);
    }

    @Override
    public void onPanel(final FlowCityItemActions.Ctx c, String panelId, String button) {
        Player p = c.player;
        if ("stop".equals(button)) {
            Session s = active.get(p.getUniqueId());
            if (s != null) stop(s, "oprita manual");
            c.ok("Bodycam oprita.");
            openMain(c, null);
        } else if ("start".equals(button)) {
            if (!active.containsKey(p.getUniqueId()) && c.ready("toggle", 1500)) start(c);
            openMain(c, active.get(p.getUniqueId()));
        } else if ("list".equals(button)) {
            list(c);
        } else if (button.startsWith("r") && button.length() > 1 && Character.isDigit(button.charAt(1))) {
            detail(c, Long.parseLong(button.substring(1)));
        } else if (button.startsWith("a") && button.length() > 1) {
            attach(c, Long.parseLong(button.substring(1)));
        }
    }

    private void list(final FlowCityItemActions.Ctx c) {
        final UUID u = c.player.getUniqueId();
        final String serial = c.serial;
        fx.idb.query(conn -> {
            List<Object[]> rows = new ArrayList<Object[]>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id,start,end,status,marks,verified_by FROM fc_bodycam WHERE officer=? ORDER BY id DESC LIMIT 6")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[] {rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getString(6)});
                }
            }
            return rows;
        }, rows -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null) return;
            Panel pn = new Panel("cam:list", "Inregistrari bodycam").sub(rows.size() + " recente");
            for (Object[] r : rows) {
                long end = (Long) r[2];
                String when = DATE.format(new Date((Long) r[1])) + (end > 0 ? " (" + ((end - (Long) r[1]) / 60000) + " min)" : " (in curs)");
                pn.row("#" + r[0], when + ", " + r[4] + " marcaje" + (String.valueOf(r[5]).isEmpty() ? "" : ", verificata de " + r[5]), String.valueOf(r[5]).isEmpty() ? Panel.INFO : Panel.OK);
                pn.button("r" + r[0], "Sesiunea #" + r[0], Panel.INFO);
            }
            if (rows.isEmpty()) pn.row("Nimic", "Nu ai inregistrari inca.", Panel.WARN);
            fx.openPanel(p, "bodycam", pn, serial);
        });
    }

    private void detail(final FlowCityItemActions.Ctx c, final long sid) {
        final UUID u = c.player.getUniqueId();
        final String serial = c.serial;
        fx.idb.query(conn -> {
            Object[] head = null;
            List<String[]> marks = new ArrayList<String[]>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT start,end,status,nearby,verified_by,note,marks FROM fc_bodycam WHERE id=? AND officer=?")) {
                ps.setLong(1, sid);
                ps.setString(2, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) head = new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7)};
                }
            }
            if (head == null) return null;
            try (PreparedStatement ps = conn.prepareStatement("SELECT ts,x,y,z,text FROM fc_mark WHERE session=? ORDER BY id DESC LIMIT 6")) {
                ps.setLong(1, sid);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) marks.add(new String[] {HM.format(new Date(rs.getLong(1))), rs.getInt(2) + "," + rs.getInt(3) + "," + rs.getInt(4), rs.getString(5)});
                }
            }
            return new Object[] {head, marks};
        }, res -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null || res == null) return;
            Object[] h = (Object[]) res[0];
            @SuppressWarnings("unchecked")
            List<String[]> marks = (List<String[]>) res[1];
            Panel pn = new Panel("cam:detail", "Sesiunea #" + sid).sub(String.valueOf(h[2]));
            pn.row("Start", DATE.format(new Date((Long) h[0])));
            pn.row("Sfarsit", (Long) h[1] > 0 ? DATE.format(new Date((Long) h[1])) : "in curs");
            pn.row("Colegi in apropiere", String.valueOf(h[3]).isEmpty() ? "-" : String.valueOf(h[3]));
            pn.row("Verificata de coleg", String.valueOf(h[4]).isEmpty() ? "nu" : String.valueOf(h[4]), String.valueOf(h[4]).isEmpty() ? Panel.WARN : Panel.OK);
            for (String[] m : marks) pn.row(m[0] + " (" + m[1] + ")", m[2]);
            pn.button("a" + sid, "Ataseaza la dosarul activ", Panel.OK);
            pn.button("list", "Inapoi", Panel.INFO);
            fx.openPanel(p, "bodycam", pn, serial);
        });
    }

    private void attach(FlowCityItemActions.Ctx c, long sid) {
        long caseId = fx.cases.ensure(c.player);
        fx.cases.attach(c.player, caseId, "BODYCAM", sid, "Inregistrare bodycam #" + sid + " (" + c.player.getName() + ")");
        c.ok("Sesiunea #" + sid + " atasata dosarului #" + caseId + ".");
        c.log("ATTACH", null, "sesiune #" + sid + " dosar #" + caseId);
    }

    /** Raport de coleg: o a doua bodycam pe ofiterul care a inregistrat confirma cea mai recenta sesiune a lui. */
    @Override
    public boolean onUsePlayer(final FlowCityItemActions.Ctx c, final Player target) {
        if (target.equals(c.player)) {
            onUse(c, null);
            return true;
        }
        if (!c.near(target)) {
            c.err(target.getName() + " e prea departe.");
            return true;
        }
        if (!c.ready("verify", 3000)) return true;
        final Player verifier = c.player;
        final UUID tu = target.getUniqueId();
        final Session live = active.get(tu);
        fx.idb.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id,verified_by FROM fc_bodycam WHERE officer=? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, tu.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Object[] {rs.getLong(1), rs.getString(2)} : null;
                }
            }
        }, row -> {
            if (!verifier.isOnline()) return;
            if (row == null) {
                verifier.sendMessage("§c" + target.getName() + " nu are nicio inregistrare bodycam.");
                return;
            }
            long sid = (Long) row[0];
            if (!String.valueOf(row[1]).isEmpty()) {
                verifier.sendMessage("§7Sesiunea #" + sid + " a fost deja verificata de " + row[1] + ".");
                return;
            }
            fx.idb.exec("UPDATE fc_bodycam SET verified_by=? WHERE id=?", verifier.getName(), sid);
            long caseId = fx.cases.ensure(verifier);
            fx.cases.attach(verifier, caseId, "BODYCAM", sid, "Raport coleg: sesiunea #" + sid + " a lui " + target.getName() + " verificata de " + verifier.getName() + (live != null ? " (in curs)" : ""));
            c.ok("Ai confirmat sesiunea #" + sid + " a lui " + target.getName() + ". Atasata dosarului #" + caseId + ".");
            target.sendMessage("§a" + verifier.getName() + " ti-a verificat inregistrarea bodycam (#" + sid + ").");
            c.log("VERIFY", target, "sesiune #" + sid);
        });
        return true;
    }
}
