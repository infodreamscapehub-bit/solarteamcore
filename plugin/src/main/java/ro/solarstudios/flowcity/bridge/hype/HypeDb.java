package ro.solarstudios.flowcity.bridge.hype;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import ro.solarstudios.flowcity.bridge.eadmin.Db;

/** Persistenta modulului Hype (hype.db): joburi, sete, istoric ATM, ATM-uri fizice. Toate operatiile sunt asincrone. */
public final class HypeDb {
    /** O inregistrare din istoricul ATM. */
    public static final class AtmEntry {
        public final String type;   // DEPOSIT | WITHDRAW | TRANSFER_OUT | TRANSFER_IN
        public final double amount;
        public final double fee;
        public final String other;
        public final long ts;

        public AtmEntry(String type, double amount, double fee, String other, long ts) {
            this.type = type;
            this.amount = amount;
            this.fee = fee;
            this.other = other == null ? "" : other;
            this.ts = ts;
        }
    }

    /** Starea persistenta + cache a unui jucator (modificata doar pe thread-ul principal). */
    public static final class PlayerData {
        public final UUID uuid;
        public volatile boolean loaded;
        public String job;                       // id-ul jobului curent sau null
        public long changedAt;
        public final Map<String, Long> xp = new HashMap<>();
        public final Map<String, Double> earned = new HashMap<>();
        public double thirst = 100;
        public int id;                           // ID unic afisat in HUD
        public final List<AtmEntry> history = new ArrayList<>(); // cele mai noi primele
        public double withdrawnToday;
        public double transferredToday;
        public long dayNumber;
        public final Map<String, RateWindow> rates = new HashMap<>();
        public String lastState = "";
        public long lastStateAt;
        public long lastThirstWarn;
        public long lastThirstDamage;
        public boolean dirty;

        public PlayerData(UUID uuid) {
            this.uuid = uuid;
        }
    }

    private final Db db;

    public HypeDb(Db db) {
        this.db = db;
    }

    public Db raw() {
        return db;
    }

    public void init() throws Exception {
        db.async(c -> {
            try (Statement st = c.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS hype_job_current (uuid TEXT PRIMARY KEY, job TEXT, changed_at INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE TABLE IF NOT EXISTS hype_job_xp (uuid TEXT NOT NULL, job TEXT NOT NULL, xp INTEGER NOT NULL DEFAULT 0, earned REAL NOT NULL DEFAULT 0, PRIMARY KEY (uuid, job))");
                st.execute("CREATE TABLE IF NOT EXISTS hype_player_id (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT UNIQUE NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS hype_thirst (uuid TEXT PRIMARY KEY, value REAL NOT NULL)");
                st.execute("CREATE TABLE IF NOT EXISTS hype_atm_tx (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, fee REAL NOT NULL DEFAULT 0, other TEXT NOT NULL DEFAULT '', ts INTEGER NOT NULL)");
                st.execute("CREATE INDEX IF NOT EXISTS idx_atm_uuid ON hype_atm_tx(uuid, ts)");
                st.execute("CREATE TABLE IF NOT EXISTS hype_atm_blocks (world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, PRIMARY KEY (world, x, y, z))");
            }
            return null;
        }).get(8, TimeUnit.SECONDS);
    }

    // ------------------------------------------------------------------ jucator

    public CompletableFuture<PlayerData> load(UUID u, long dayStartMs) {
        return db.async(c -> {
            PlayerData d = new PlayerData(u);
            String id = u.toString();
            try (PreparedStatement ps = c.prepareStatement("SELECT job, changed_at FROM hype_job_current WHERE uuid=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        d.job = rs.getString(1);
                        d.changedAt = rs.getLong(2);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT job, xp, earned FROM hype_job_xp WHERE uuid=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        d.xp.put(rs.getString(1), rs.getLong(2));
                        d.earned.put(rs.getString(1), rs.getDouble(3));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO hype_player_id(uuid) VALUES (?)")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM hype_player_id WHERE uuid=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) d.id = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM hype_thirst WHERE uuid=?")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) d.thirst = rs.getDouble(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT type, amount, fee, other, ts FROM hype_atm_tx WHERE uuid=? ORDER BY id DESC LIMIT 20")) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) d.history.add(new AtmEntry(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getString(4), rs.getLong(5)));
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT type, SUM(amount) FROM hype_atm_tx WHERE uuid=? AND ts>=? AND type IN ('WITHDRAW','TRANSFER_OUT') GROUP BY type")) {
                ps.setString(1, id);
                ps.setLong(2, dayStartMs);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if ("WITHDRAW".equals(rs.getString(1))) d.withdrawnToday = rs.getDouble(2);
                        else d.transferredToday = rs.getDouble(2);
                    }
                }
            }
            d.dayNumber = HypeMath.dayNumber(dayStartMs);
            return d;
        });
    }

    /** Salveaza o copie a starii (apelat pe thread-ul principal; scrierea e asincrona). */
    public void save(PlayerData d) {
        final String id = d.uuid.toString();
        final String job = d.job;
        final long changed = d.changedAt;
        final Map<String, Long> xp = new HashMap<>(d.xp);
        final Map<String, Double> earned = new HashMap<>(d.earned);
        final double thirst = d.thirst;
        d.dirty = false;
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO hype_job_current(uuid, job, changed_at) VALUES (?,?,?)")) {
                ps.setString(1, id);
                ps.setString(2, job);
                ps.setLong(3, changed);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO hype_job_xp(uuid, job, xp, earned) VALUES (?,?,?,?)")) {
                for (Map.Entry<String, Long> e : xp.entrySet()) {
                    ps.setString(1, id);
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.setDouble(4, earned.containsKey(e.getKey()) ? earned.get(e.getKey()) : 0.0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT OR REPLACE INTO hype_thirst(uuid, value) VALUES (?,?)")) {
                ps.setString(1, id);
                ps.setDouble(2, thirst);
                ps.executeUpdate();
            }
            return null;
        });
    }

    // ------------------------------------------------------------------ ATM

    public void addTx(UUID u, String type, double amount, double fee, String other, long ts) {
        db.run(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO hype_atm_tx(uuid, type, amount, fee, other, ts) VALUES (?,?,?,?,?,?)")) {
                ps.setString(1, u.toString());
                ps.setString(2, type);
                ps.setDouble(3, amount);
                ps.setDouble(4, fee);
                ps.setString(5, other == null ? "" : other);
                ps.setLong(6, ts);
                ps.executeUpdate();
            }
            return null;
        });
    }

    public CompletableFuture<List<String[]>> loadAtmBlocks() {
        return db.async(c -> {
            List<String[]> out = new ArrayList<>();
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT world, x, y, z FROM hype_atm_blocks")) {
                while (rs.next()) out.add(new String[] {rs.getString(1), String.valueOf(rs.getInt(2)), String.valueOf(rs.getInt(3)), String.valueOf(rs.getInt(4))});
            }
            return out;
        });
    }

    public void setAtmBlock(String world, int x, int y, int z, boolean present) {
        db.run(c -> {
            String sql = present
                ? "INSERT OR IGNORE INTO hype_atm_blocks(world, x, y, z) VALUES (?,?,?,?)"
                : "DELETE FROM hype_atm_blocks WHERE world=? AND x=? AND y=? AND z=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.executeUpdate();
            }
            return null;
        });
    }
}
