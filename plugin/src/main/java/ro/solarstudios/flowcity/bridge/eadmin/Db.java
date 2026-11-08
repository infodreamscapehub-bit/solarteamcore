package ro.solarstudios.flowcity.bridge.eadmin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/** SQLite intr-un singur thread: nicio operatie de DB nu ruleaza pe thread-ul serverului. */
public final class Db {
    public interface SqlFn<T> {
        T apply(Connection c) throws Exception;
    }

    private final Connection conn;
    private final ExecutorService executor;
    private final Logger log;

    public Db(File file, Logger log) throws Exception {
        this.log = log;
        Class.forName("org.sqlite.JDBC");
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        this.conn = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "FlowCityBridge-DB");
            t.setDaemon(true);
            return t;
        });
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA synchronous=NORMAL");
        }
        createSchema();
    }

    private void createSchema() throws SQLException {
        String[] ddl = new String[] {
            "CREATE TABLE IF NOT EXISTS eadmin_players (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, last_ip TEXT NOT NULL DEFAULT '', play_seconds INTEGER NOT NULL DEFAULT 0)",
            "CREATE TABLE IF NOT EXISTS eadmin_notes (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, admin_name TEXT NOT NULL, note_text TEXT NOT NULL, timestamp INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS eadmin_chat_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, message TEXT NOT NULL, timestamp INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS eadmin_command_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, command TEXT NOT NULL, timestamp INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS eadmin_connection_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, ip_address TEXT NOT NULL, event_type TEXT NOT NULL, coords TEXT NOT NULL, dimension TEXT NOT NULL, timestamp INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS eadmin_creative_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, action_type TEXT NOT NULL, item_id TEXT NOT NULL, count INTEGER NOT NULL, details TEXT NOT NULL, timestamp INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS eadmin_inventory_logs (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, action TEXT NOT NULL, item_id TEXT NOT NULL, count INTEGER NOT NULL, container_type TEXT NOT NULL, coords TEXT NOT NULL, timestamp INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS eadmin_transactions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, target_name TEXT NOT NULL, timestamp INTEGER NOT NULL, location TEXT)",
            "CREATE TABLE IF NOT EXISTS eadmin_flags (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, severity TEXT NOT NULL, trigger_type TEXT NOT NULL, details TEXT NOT NULL, timestamp INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS eadmin_sanctions (id INTEGER PRIMARY KEY AUTOINCREMENT, player_uuid TEXT NOT NULL, type TEXT NOT NULL, admin_name TEXT NOT NULL, reason TEXT NOT NULL, duration_minutes INTEGER NOT NULL, timestamp INTEGER NOT NULL, expires_at INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0, deleted_by TEXT NOT NULL DEFAULT '')",
            "CREATE INDEX IF NOT EXISTS idx_players_name ON eadmin_players(name)",
            "CREATE INDEX IF NOT EXISTS idx_players_ip ON eadmin_players(last_ip)",
            "CREATE INDEX IF NOT EXISTS idx_notes_uuid ON eadmin_notes(player_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_chat_uuid ON eadmin_chat_logs(player_uuid, timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_cmd_uuid ON eadmin_command_logs(player_uuid, timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_conn_uuid ON eadmin_connection_logs(player_uuid, timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_creative_uuid ON eadmin_creative_logs(player_uuid, timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_inv_uuid ON eadmin_inventory_logs(player_uuid, timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_tx_uuid ON eadmin_transactions(player_uuid, timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_flags_uuid ON eadmin_flags(player_uuid, timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_sanctions_uuid ON eadmin_sanctions(player_uuid, active)"
        };
        try (Statement st = conn.createStatement()) {
            for (String s : ddl) st.execute(s);
        }
    }

    /** Ruleaza pe thread-ul DB si intoarce rezultatul asincron. Erorile sunt logate. */
    public <T> CompletableFuture<T> async(SqlFn<T> fn) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    future.complete(fn.apply(conn));
                } catch (Throwable t) {
                    log.warning("Eroare DB: " + t);
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    /** Fire-and-forget (loguri). */
    public void run(SqlFn<Void> fn) {
        async(fn);
    }

    public void close() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        try {
            conn.close();
        } catch (SQLException ignored) {
            // inchidere la oprire
        }
    }
}
