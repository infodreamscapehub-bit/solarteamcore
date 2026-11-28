package ro.solarstudios.flowcity.bridge.items;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import ro.solarstudios.flowcity.bridge.eadmin.Db;

/** Jurnal persistent (SQLite) al actiunilor cu iteme: cine, ce item, ce actiune, asupra cui, unde. */
public final class ItemLog {
    public static final class Row {
        public long id;
        public long ts;
        public String actor;
        public String item;
        public String action;
        public String target;
        public String detail;
    }

    private final Db db;

    public ItemLog(Db db, int retentionDays) {
        this.db = db;
        db.run(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS fc_item_log (id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL, actor_name TEXT NOT NULL, item TEXT NOT NULL, action TEXT NOT NULL, target TEXT NOT NULL DEFAULT '', target_name TEXT NOT NULL DEFAULT '', detail TEXT NOT NULL DEFAULT '', world TEXT NOT NULL DEFAULT '', x INTEGER NOT NULL DEFAULT 0, y INTEGER NOT NULL DEFAULT 0, z INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_item_log_ts ON fc_item_log(ts)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_item_log_actor ON fc_item_log(actor, ts)");
            }
            if (retentionDays > 0) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM fc_item_log WHERE ts < ?")) {
                    ps.setLong(1, System.currentTimeMillis() - retentionDays * 86_400_000L);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public void add(UUID actor, String actorName, String item, String action, UUID target, String targetName, String detail,
                    String world, int x, int y, int z) {
        final long ts = System.currentTimeMillis();
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO fc_item_log(ts,actor,actor_name,item,action,target,target_name,detail,world,x,y,z) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
                ps.setLong(1, ts);
                ps.setString(2, actor == null ? "-" : actor.toString());
                ps.setString(3, actorName == null ? "-" : actorName);
                ps.setString(4, item == null ? "-" : item);
                ps.setString(5, action);
                ps.setString(6, target == null ? "" : target.toString());
                ps.setString(7, targetName == null ? "" : targetName);
                ps.setString(8, detail == null ? "" : (detail.length() > 300 ? detail.substring(0, 300) : detail));
                ps.setString(9, world == null ? "" : world);
                ps.setInt(10, x);
                ps.setInt(11, y);
                ps.setInt(12, z);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** Ultimele {@code limit} intrari (pentru panoul de admin). */
    public CompletableFuture<List<Row>> recent(int limit) {
        return db.async(c -> {
            List<Row> out = new ArrayList<Row>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id,ts,actor_name,item,action,target_name,detail FROM fc_item_log ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, Math.max(1, Math.min(200, limit)));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Row r = new Row();
                        r.id = rs.getLong(1);
                        r.ts = rs.getLong(2);
                        r.actor = rs.getString(3);
                        r.item = rs.getString(4);
                        r.action = rs.getString(5);
                        r.target = rs.getString(6);
                        r.detail = rs.getString(7);
                        out.add(r);
                    }
                }
            }
            return out;
        });
    }
}
