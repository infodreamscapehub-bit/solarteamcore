package ro.solarstudios.flowcity.bridge.items;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.eadmin.Db;

/** Tabelele itemelor (dosare, probe, bodycam, poze, comenzi de piese) in SQLite-ul comun; id-urile se dau din memorie. */
public final class ItemDb {
    private static final String[] TABLES = {"fc_case", "fc_evidence", "fc_print", "fc_bodycam", "fc_photo", "fc_order"};
    private final FlowCityBridge plugin;
    public final Db db;
    private final Map<String, AtomicLong> seq = new HashMap<String, AtomicLong>();

    public ItemDb(FlowCityBridge plugin) {
        this.plugin = plugin;
        this.db = plugin.db();
        try {
            db.async((Db.SqlFn<Void>) c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS fc_case (id INTEGER PRIMARY KEY, title TEXT NOT NULL, officer TEXT NOT NULL, officer_name TEXT NOT NULL, ts INTEGER NOT NULL, open INTEGER NOT NULL DEFAULT 1)");
                    st.execute("CREATE TABLE IF NOT EXISTS fc_evidence (id INTEGER PRIMARY KEY, case_id INTEGER NOT NULL, kind TEXT NOT NULL, ref INTEGER NOT NULL, summary TEXT NOT NULL, by_name TEXT NOT NULL, ts INTEGER NOT NULL)");
                    st.execute("CREATE TABLE IF NOT EXISTS fc_print (id INTEGER PRIMARY KEY, hash TEXT NOT NULL, kind TEXT NOT NULL, subject TEXT NOT NULL DEFAULT '', subject_name TEXT NOT NULL DEFAULT '', collector TEXT NOT NULL, collector_name TEXT NOT NULL, ts INTEGER NOT NULL, place TEXT NOT NULL DEFAULT '', case_id INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '')");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_fc_print_hash ON fc_print(hash)");
                    st.execute("CREATE TABLE IF NOT EXISTS fc_bodycam (id INTEGER PRIMARY KEY, officer TEXT NOT NULL, officer_name TEXT NOT NULL, start INTEGER NOT NULL, end INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'REC', note TEXT NOT NULL DEFAULT '', verified_by TEXT NOT NULL DEFAULT '', nearby TEXT NOT NULL DEFAULT '', marks INTEGER NOT NULL DEFAULT 0)");
                    st.execute("CREATE TABLE IF NOT EXISTS fc_mark (id INTEGER PRIMARY KEY AUTOINCREMENT, session INTEGER NOT NULL, ts INTEGER NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, text TEXT NOT NULL)");
                    st.execute("CREATE TABLE IF NOT EXISTS fc_photo (id INTEGER PRIMARY KEY, owner TEXT NOT NULL, owner_name TEXT NOT NULL, ts INTEGER NOT NULL, world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, caption TEXT NOT NULL DEFAULT '', people TEXT NOT NULL DEFAULT '', case_id INTEGER NOT NULL DEFAULT 0, published INTEGER NOT NULL DEFAULT 0)");
                    st.execute("CREATE TABLE IF NOT EXISTS fc_order (id INTEGER PRIMARY KEY, player TEXT NOT NULL, item TEXT NOT NULL, qty INTEGER NOT NULL, due INTEGER NOT NULL, delivered INTEGER NOT NULL DEFAULT 0, plate TEXT NOT NULL DEFAULT '')");
                    // sesiunile ramase deschise dupa un restart se inchid
                    st.execute("UPDATE fc_bodycam SET status='ENDED', end=start WHERE status='REC' AND end=0");
                }
                for (String t : TABLES) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(MAX(id),0) FROM " + t); ResultSet rs = ps.executeQuery()) {
                        seq.put(t, new AtomicLong(rs.next() ? rs.getLong(1) : 0));
                    }
                }
                return null;
            }).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("Tabelele itemelor nu s-au creat: " + e);
            for (String t : TABLES) if (!seq.containsKey(t)) seq.put(t, new AtomicLong(0));
        }
    }

    public long next(String table) {
        AtomicLong a = seq.get(table);
        return a == null ? 0 : a.incrementAndGet();
    }

    /** Executa o interogare in thread-ul DB, apoi da rezultatul pe thread-ul principal. */
    public <T> void query(Db.SqlFn<T> fn, final Consumer<T> onMain) {
        db.async(fn).whenComplete((res, err) -> {
            if (err != null) return;
            Bukkit.getScheduler().runTask(plugin, () -> onMain.accept(res));
        });
    }

    public void exec(final String sql, final Object... args) {
        db.run((Connection c) -> {
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
