package ro.solarstudios.flowcity.bridge.fjobs.act.elec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/** Statisticile si istoricul interventiilor per jucator, in fisiere separate (plugins/FlowCityBridge/fjobs-electrician/UUID.json). Scrierea e atomica (fisier temporar + mutare). */
public final class ElecStore {
    /** O intrare din istoric. */
    public static final class Entry {
        public long t;
        public String type = "";
        public String name = "";
        public String loc = "";
        public int diff;
        public boolean ok;
        public double pay;
        public long xp;
        public double q;
        public int sec;
        public int mistakes;
    }

    /** Datele unui jucator. */
    public static final class Data {
        public boolean starter;
        public int done;
        public int failed;
        public int abandoned;
        public double money;
        public long xp;
        public long seconds;
        public double bestQ;
        public int bestStreak;
        public int streak;
        public double qSum;
        public Map<String, Integer> byType = new HashMap<>();
        public List<Entry> history = new ArrayList<>();
    }

    private static final int MAX_HISTORY = 40;
    private final File dir;
    private final Logger log;
    private final Gson gson = new GsonBuilder().create();
    private final Map<UUID, Data> cache = new HashMap<>();

    public ElecStore(File pluginDir, Logger log) {
        this.dir = new File(pluginDir, "fjobs-electrician");
        this.log = log;
    }

    public Data get(UUID u) {
        Data d = cache.get(u);
        if (d != null) return d;
        File f = new File(dir, u + ".json");
        d = new Data();
        if (f.isFile()) {
            try (Reader rd = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
                Data x = gson.fromJson(rd, Data.class);
                if (x != null) d = x;
            } catch (IOException | RuntimeException e) {
                log.warning("Electrician: nu pot citi " + f.getName() + ": " + e);
            }
        }
        if (d.byType == null) d.byType = new HashMap<>();
        if (d.history == null) d.history = new ArrayList<>();
        cache.put(u, d);
        return d;
    }

    public void add(UUID u, Entry e) {
        Data d = get(u);
        d.history.add(0, e);
        while (d.history.size() > MAX_HISTORY) d.history.remove(d.history.size() - 1);
        d.seconds += Math.max(0, e.sec);
        if (e.ok) {
            d.done++;
            d.money += e.pay;
            d.xp += e.xp;
            d.qSum += e.q;
            d.bestQ = Math.max(d.bestQ, e.q);
            d.streak++;
            d.bestStreak = Math.max(d.bestStreak, d.streak);
            d.byType.merge(e.type, 1, Integer::sum);
        } else {
            d.failed++;
            d.streak = 0;
        }
        save(u);
    }

    public void save(UUID u) {
        Data d = cache.get(u);
        if (d == null) return;
        try {
            Files.createDirectories(dir.toPath());
            File tmp = new File(dir, u + ".json.tmp");
            try (Writer w = Files.newBufferedWriter(tmp.toPath(), StandardCharsets.UTF_8)) {
                gson.toJson(d, w);
            }
            Files.move(tmp.toPath(), new File(dir, u + ".json").toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException e) {
            log.warning("Electrician: nu pot salva datele jucatorului " + u + ": " + e);
        }
    }

    public void drop(UUID u) {
        cache.remove(u);
    }

    public void saveAll() {
        for (UUID u : new ArrayList<>(cache.keySet())) save(u);
    }
}
