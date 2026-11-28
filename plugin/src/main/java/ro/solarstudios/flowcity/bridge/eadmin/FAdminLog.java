package ro.solarstudios.flowcity.bridge.eadmin;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;

/** Jurnalul actiunilor de staff din FAdmin: cine, ce, asupra cui, cand, ce valori a schimbat si de ce. Tabela fadm_log. */
public final class FAdminLog {
    public static final class Row {
        public long id;
        public long ts;
        public String admin = "";
        public String adminUuid = "";
        public String level = "";
        public String action = "";
        public String target = "";
        public String targetUuid = "";
        public String oldVal = "";
        public String newVal = "";
        public String reason = "";
    }

    private final FlowCityBridge plugin;
    private final Db db;

    public FAdminLog(FlowCityBridge plugin, Db db) {
        this.plugin = plugin;
        this.db = db;
        db.run(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS fadm_log (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, admin TEXT NOT NULL, "
                    + "level TEXT NOT NULL DEFAULT '', action TEXT NOT NULL, target_uuid TEXT NOT NULL DEFAULT '', target_name TEXT NOT NULL DEFAULT '', "
                    + "old_val TEXT NOT NULL DEFAULT '', new_val TEXT NOT NULL DEFAULT '', reason TEXT NOT NULL DEFAULT '')");
                try {
                    st.execute("ALTER TABLE fadm_log ADD COLUMN admin_uuid TEXT NOT NULL DEFAULT ''");
                } catch (java.sql.SQLException ignored) {
                    // coloana exista deja
                }
                st.execute("CREATE INDEX IF NOT EXISTS idx_fadm_log_ts ON fadm_log(ts)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_fadm_log_target ON fadm_log(target_uuid)");
            }
            return null;
        });
    }

    private static String clip(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ').trim();
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Inregistreaza o actiune (asincron). */
    public void add(String admin, FAdminLevel level, String action, UUID target, String targetName, String oldVal, String newVal, String reason) {
        final long now = System.currentTimeMillis();
        final String lv = level == null ? "" : level.id;
        org.bukkit.entity.Player ap = admin == null ? null : org.bukkit.Bukkit.getPlayerExact(admin);
        final String au = ap == null ? "" : ap.getUniqueId().toString();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO fadm_log(ts, admin, level, action, target_uuid, target_name, old_val, new_val, reason, admin_uuid) VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, now);
                ps.setString(2, clip(admin, 32));
                ps.setString(3, lv);
                ps.setString(4, clip(action, 32));
                ps.setString(5, target == null ? "" : target.toString());
                ps.setString(6, clip(targetName, 32));
                ps.setString(7, clip(oldVal, 120));
                ps.setString(8, clip(newVal, 120));
                ps.setString(9, clip(reason, 200));
                ps.setString(10, au);
                ps.executeUpdate();
            }
            return null;
        });
        plugin.getLogger().info("FAdmin: " + admin + " " + action + (targetName == null || targetName.isEmpty() ? "" : " " + targetName)
            + (oldVal == null || oldVal.isEmpty() ? "" : " [" + oldVal + " -> " + newVal + "]") + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")"));
    }

    /** Ultimele actiuni, filtrate optional dupa text (admin / tinta / actiune) sau dupa tinta. */
    public CompletableFuture<List<Row>> query(String q, UUID target, int offset, int limit, int[] totalOut) {
        return db.async(c -> {
            StringBuilder where = new StringBuilder(" WHERE 1=1");
            List<String> args = new ArrayList<>();
            if (q != null && !q.trim().isEmpty()) {
                where.append(" AND (admin LIKE ? OR target_name LIKE ? OR action LIKE ? OR reason LIKE ?)");
                String like = "%" + q.trim().replace("%", "") + "%";
                for (int i = 0; i < 4; i++) args.add(like);
            }
            if (target != null) {
                where.append(" AND target_uuid=?");
                args.add(target.toString());
            }
            if (totalOut != null) {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM fadm_log" + where)) {
                    for (int i = 0; i < args.size(); i++) ps.setString(i + 1, args.get(i));
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) totalOut[0] = rs.getInt(1);
                    }
                }
            }
            List<Row> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id, ts, admin, level, action, target_uuid, target_name, old_val, new_val, reason, admin_uuid FROM fadm_log"
                + where + " ORDER BY id DESC LIMIT ? OFFSET ?")) {
                int i = 1;
                for (String a : args) ps.setString(i++, a);
                ps.setInt(i++, limit);
                ps.setInt(i, offset);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Row r = new Row();
                        r.id = rs.getLong(1);
                        r.ts = rs.getLong(2);
                        r.admin = rs.getString(3);
                        r.level = rs.getString(4);
                        r.action = rs.getString(5);
                        r.targetUuid = rs.getString(6);
                        r.target = rs.getString(7);
                        r.oldVal = rs.getString(8);
                        r.newVal = rs.getString(9);
                        r.reason = rs.getString(10);
                        r.adminUuid = rs.getString(11);
                        out.add(r);
                    }
                }
            }
            return out;
        });
    }
}
