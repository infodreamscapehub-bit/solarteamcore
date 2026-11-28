package ro.solarstudios.flowcity.bridge.items;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.fjobs.act.Items;
import ro.solarstudios.flowcity.bridge.news.NewsModule;

/**
 * Camera foto: fiecare poza e o inregistrare persistenta (cine, cand, unde, ce persoane erau in cadru - in fata camerei, cu vizibilitate
 * directa) si un item "fotografie" cu serial, care poate fi dat altcuiva. O poza se ataseaza dosarului unui ofiter sau se publica la
 * CNN de un jurnalist (aceleasi verificari ca ecranul CNN). Nu e o captura de ecran: serverul nu poate primi imagini de la client.
 */
public final class CameraHandler implements FlowCityItemActions.Handler {
    private FlowCityItemActions fx;
    private static final SimpleDateFormat DATE = new SimpleDateFormat("dd/MM HH:mm");

    @Override
    public String id() {
        return "camera_foto";
    }

    @Override
    public void enable(FlowCityItemActions fx) {
        this.fx = fx;
        fx.register(new Photo());
        Commands.register(fx.plugin, "foto", null, (s, c, l, a) -> {
            if (!(s instanceof Player)) return true;
            caption((Player) s, String.join(" ", a));
            return true;
        }, null);
    }

    private final java.util.Map<UUID, Long> lastPhoto = new java.util.HashMap<UUID, Long>();

    private void caption(final Player p, String textIn) {
        String text = textIn == null ? "" : textIn.replaceAll("[\\p{Cntrl}]", " ").replace("§", "").trim();
        Long id = lastPhoto.get(p.getUniqueId());
        if (id == null) {
            p.sendMessage("§7Fa mai intai o poza cu camera. Folosire: /foto <descriere>");
            return;
        }
        if (text.length() < 3) {
            p.sendMessage("§7Folosire: /foto <descriere> (seteaza descrierea ultimei poze).");
            return;
        }
        if (text.length() > 100) text = text.substring(0, 100);
        fx.idb.exec("UPDATE fc_photo SET caption=? WHERE id=? AND owner=?", text, id, p.getUniqueId().toString());
        p.sendMessage("§aDescrierea pozei #" + id + " a fost salvata.");
    }

    // ------------------------------------------------------------------ poza

    @Override
    public boolean onUse(final FlowCityItemActions.Ctx c, Block block) {
        if (c.sneaking()) {
            gallery(c);
            return true;
        }
        if (!c.ready("shot", c.num("cooldown-seconds", 4) * 1000L)) return true;
        c.start("Faci poza", c.num("shot-seconds", 2) * 1000L, null, new Runnable() {
            @Override
            public void run() {
                shoot(c);
            }
        });
        return true;
    }

    private void shoot(FlowCityItemActions.Ctx c) {
        Player p = c.player;
        if (!c.charge()) {
            c.err("Aparatul nu mai are film.");
            return;
        }
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        double range = c.dbl("frame-range", 25);
        List<String> people = new ArrayList<String>();
        for (Player q : p.getWorld().getPlayers()) {
            if (q.equals(p)) continue;
            Vector to = q.getEyeLocation().toVector().subtract(eye.toVector());
            double d = to.length();
            if (d > range || d < 0.5) continue;
            if (to.normalize().dot(dir) < 0.75) continue;
            if (!p.hasLineOfSight(q)) continue;
            if (people.size() < 8) people.add(q.getName());
        }
        long id = fx.idb.next("fc_photo");
        Location l = p.getLocation();
        String auto = "Poza la " + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ();
        fx.idb.exec("INSERT INTO fc_photo(id,owner,owner_name,ts,world,x,y,z,caption,people,case_id,published) VALUES (?,?,?,?,?,?,?,?,?,?,0,0)", id, p.getUniqueId().toString(), p.getName(),
            System.currentTimeMillis(), l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), auto, String.join(",", people));
        lastPhoto.put(p.getUniqueId(), id);
        fx.fx.flash(p);
        FlowCityItemActions.snd(p, "minecraft:block.note_block.snare", 0.8f, 1.7f);
        if (c.flag("give-print", true)) {
            String serial = fx.serials.issue("fotografie", p.getUniqueId(), -1, System.currentTimeMillis());
            List<String> lore = new ArrayList<String>();
            lore.add("§7Facuta de: §f" + p.getName());
            lore.add("§7Data: §f" + DATE.format(new Date()));
            lore.add("§7In cadru: §f" + (people.isEmpty() ? "nimeni" : String.join(", ", people)));
            lore.add("§8Serial: " + serial);
            ItemStack it = ItemKit.make("fotografie", "§bFotografie #" + id, lore, serial, 1);
            ItemKit.setData(it, "photo", Long.toString(id));
            Items.give(p, it);
        }
        c.ok("Poza #" + id + " salvata" + (people.isEmpty() ? "." : " (in cadru: " + String.join(", ", people) + ")."));
        c.info("Scrie /foto <descriere> ca sa-i dai un titlu.");
        c.log("PHOTO", null, "poza #" + id + (people.isEmpty() ? "" : " cu " + String.join(",", people)));
    }

    // ------------------------------------------------------------------ galerie / detalii

    private void gallery(final FlowCityItemActions.Ctx c) {
        final UUID u = c.player.getUniqueId();
        final String serial = c.serial;
        fx.idb.query(conn -> {
            List<Object[]> rows = new ArrayList<Object[]>();
            try (PreparedStatement ps = conn.prepareStatement("SELECT id,ts,caption,published FROM fc_photo WHERE owner=? ORDER BY id DESC LIMIT 6")) {
                ps.setString(1, u.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) rows.add(new Object[] {rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)});
                }
            }
            return rows;
        }, rows -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null) return;
            Panel pn = new Panel("cam:gallery", "Pozele mele").sub(rows.size() + " recente");
            for (Object[] r : rows) {
                pn.row("#" + r[0], DATE.format(new Date((Long) r[1])) + " - " + r[2], (Integer) r[3] == 1 ? Panel.OK : Panel.INFO);
                pn.button("v" + r[0], "Poza #" + r[0], Panel.INFO);
            }
            if (rows.isEmpty()) pn.row("Nicio poza", "Click dreapta cu camera ca sa faci o poza.", Panel.WARN);
            fx.openPanel(p, "camera_foto", pn, serial);
        });
    }

    void detail(final FlowCityItemActions.Ctx c, final long pid) {
        final UUID u = c.player.getUniqueId();
        final String serial = c.serial;
        fx.idb.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT owner_name,ts,x,y,z,caption,people,published,case_id FROM fc_photo WHERE id=?")) {
                ps.setLong(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Object[] {rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getString(7), rs.getInt(8), rs.getLong(9)} : null;
                }
            }
        }, r -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null || r == null) return;
            Panel pn = new Panel("cam:photo", "Fotografie #" + pid).sub(DATE.format(new Date((Long) r[1])));
            pn.row("Descriere", String.valueOf(r[5]));
            pn.row("Facuta de", String.valueOf(r[0]));
            pn.row("Locatie", r[2] + ", " + r[3] + ", " + r[4]);
            pn.row("In cadru", String.valueOf(r[6]).isEmpty() ? "nimeni" : String.valueOf(r[6]));
            pn.row("CNN", (Integer) r[7] == 1 ? "publicata" : "nepublicata", (Integer) r[7] == 1 ? Panel.OK : Panel.INFO);
            pn.row("Dosar", (Long) r[8] > 0 ? "#" + r[8] : "-");
            sel.put(u, pid);
            if (Rules.hasAny(p::hasPermission, fx.cfg.getStringList("items.camera_foto.attach-permissions"))) pn.button("attach", "Ataseaza la dosarul activ", Panel.OK);
            if (Rules.hasAny(p::hasPermission, fx.cfg.getStringList("items.camera_foto.publish-permissions")) && (Integer) r[7] == 0) pn.button("publish", "Publica la CNN", Panel.WARN);
            pn.button("gallery", "Galerie", Panel.INFO);
            fx.openPanel(p, "camera_foto", pn, serial);
        });
    }

    private final java.util.Map<UUID, Long> sel = new java.util.HashMap<UUID, Long>();

    @Override
    public void onPanel(final FlowCityItemActions.Ctx c, String panelId, String button) {
        Player p = c.player;
        Long pid = sel.get(p.getUniqueId());
        if ("gallery".equals(button)) {
            gallery(c);
        } else if (button.startsWith("v") && button.length() > 1) {
            detail(c, Long.parseLong(button.substring(1)));
        } else if ("attach".equals(button) && pid != null) {
            long caseId = fx.cases.ensure(p);
            fx.cases.attach(p, caseId, "FOTO", pid, "Fotografie #" + pid);
            fx.idb.exec("UPDATE fc_photo SET case_id=? WHERE id=?", caseId, pid);
            c.ok("Poza #" + pid + " a fost atasata dosarului #" + caseId + ".");
            c.log("ATTACH", null, "poza #" + pid + " dosar #" + caseId);
        } else if ("publish".equals(button) && pid != null) {
            publish(c, pid);
        }
    }

    private void publish(final FlowCityItemActions.Ctx c, final long pid) {
        final UUID u = c.player.getUniqueId();
        if (!Rules.hasAny(c.player::hasPermission, fx.cfg.getStringList("items.camera_foto.publish-permissions"))) {
            c.err("Nu ai dreptul sa publici stiri.");
            return;
        }
        if (!c.ready("publish", 5000)) return;
        fx.idb.query(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("SELECT caption,people,x,y,z,published FROM fc_photo WHERE id=?")) {
                ps.setLong(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new Object[] {rs.getString(1), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), rs.getInt(6)} : null;
                }
            }
        }, r -> {
            Player p = Bukkit.getPlayer(u);
            if (p == null || r == null) return;
            if ((Integer) r[5] == 1) {
                p.sendMessage("§7Poza e deja publicata.");
                return;
            }
            NewsModule news = NewsModule.instance;
            if (news == null) {
                p.sendMessage("§cCNN nu e pornit pe server.");
                return;
            }
            String title = String.valueOf(r[0]);
            String body = "Foto de " + p.getName() + " la " + r[2] + ", " + r[3] + ", " + r[4] + (String.valueOf(r[1]).isEmpty() ? "" : ". In cadru: " + r[1]);
            news.publishFromItem(p, fx.cfg.getString("items.camera_foto.news-category", "eveniment"), title, body);
            fx.idb.exec("UPDATE fc_photo SET published=1 WHERE id=?", pid);
            fx.log(p, "camera_foto", "PUBLISH", null, null, "poza #" + pid);
        });
    }

    /** Fotografia ca item: click dreapta o arata. */
    final class Photo implements FlowCityItemActions.Handler {
        @Override
        public String id() {
            return "fotografie";
        }

        @Override
        public boolean onUse(FlowCityItemActions.Ctx c, Block block) {
            String pid = ItemKit.data(c.item, "photo");
            if (pid == null) {
                c.err("Fotografie fara date.");
                return true;
            }
            try {
                detail(c, Long.parseLong(pid));
            } catch (NumberFormatException ex) {
                c.err("Fotografie fara date.");
            }
            return true;
        }
    }
}
