package ro.solarstudios.flowcity.bridge.fjobs.act.elec;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;

/** Configul jobului Electrician citit din fjobs.yml (types / ranks / equipment / tuning), cu valori implicite daca lipseste ceva. */
public final class ElecCfg {
    /** Un tip de interventie. */
    public static final class Type {
        public String id;
        public String name;
        public String desc;
        public String symptom;
        public boolean enabled = true;
        public double reward = 60;
        public long xp = 20;
        public int seconds = 150;
        public int minRank;
        public int minDiff = 1;
        public int maxDiff = 5;
        public int volt = 230;
        public final List<String> stages = new ArrayList<>();
        public final Map<String, Integer> needs = new LinkedHashMap<>();
    }

    /** Un nivel profesional (legat de nivelul jobului). */
    public static final class Rank {
        public int idx;
        public String id;
        public String name;
        public int minLevel;
        public int maxDiff = 1;
        public double payBonus;
    }

    /** O piesa de echipament. */
    public static final class Equip {
        public String id;
        public String name;
        public String desc;
        public String material = "STONE";
        public double price = 50;
        public int pack = 1;
        public boolean consumable;
        public int minRank;
        public int max = 1;
    }

    public static final String[] STAGES = {"circuit", "fuse", "seq", "calib"};
    public static final String[] EQUIP_IDS = {"trusa", "multimetru", "cleste", "cablu", "siguranta", "protectie"};

    private final ConfigurationSection c;
    public final List<Type> types = new ArrayList<>();
    public final List<Rank> ranks = new ArrayList<>();
    public final Map<String, Equip> equip = new LinkedHashMap<>();

    public ElecCfg(FJobDef jd) {
        this.c = jd.conf;
        loadRanks();
        loadEquip();
        loadTypes();
    }

    public double d(String path, double def) {
        return c == null ? def : c.getDouble(path, def);
    }

    public int i(String path, int def) {
        return c == null ? def : c.getInt(path, def);
    }

    public boolean b(String path, boolean def) {
        return c == null ? def : c.getBoolean(path, def);
    }

    public String s(String path, String def) {
        return c == null ? def : c.getString(path, def);
    }

    public Type type(String id) {
        for (Type t : types) if (t.id.equals(id)) return t;
        return null;
    }

    public Rank rankFor(int level) {
        Rank best = ranks.get(0);
        for (Rank r : ranks) if (level >= r.minLevel) best = r;
        return best;
    }

    // ------------------------------------------------------------------ incarcare

    private void loadRanks() {
        ConfigurationSection rs = c == null ? null : c.getConfigurationSection("ranks");
        if (rs != null) {
            int n = 0;
            for (String id : rs.getKeys(false)) {
                ConfigurationSection r = rs.getConfigurationSection(id);
                if (r == null) continue;
                Rank k = new Rank();
                k.idx = n++;
                k.id = id.toLowerCase(Locale.ROOT);
                k.name = r.getString("name", id);
                k.minLevel = Math.max(0, r.getInt("min-level", 0));
                k.maxDiff = Math.max(1, Math.min(5, r.getInt("max-diff", 1)));
                k.payBonus = Math.max(0, Math.min(2, r.getDouble("pay-bonus", 0)));
                ranks.add(k);
            }
        }
        if (ranks.isEmpty()) {
            String[][] def = {{"ucenic", "Ucenic", "0", "1", "0"}, {"junior", "Electrician Junior", "4", "2", "0.05"},
                {"electrician", "Electrician", "8", "3", "0.10"}, {"senior", "Electrician Senior", "14", "4", "0.15"},
                {"specialist", "Specialist Electric", "22", "5", "0.22"}, {"inginer", "Inginer Electric", "32", "5", "0.30"}};
            for (int n = 0; n < def.length; n++) {
                Rank k = new Rank();
                k.idx = n;
                k.id = def[n][0];
                k.name = def[n][1];
                k.minLevel = Integer.parseInt(def[n][2]);
                k.maxDiff = Integer.parseInt(def[n][3]);
                k.payBonus = Double.parseDouble(def[n][4]);
                ranks.add(k);
            }
        }
    }

    private void loadEquip() {
        String[][] def = {
            {"trusa", "Trusa electrician", "ORANGE_SHULKER_BOX", "120", "1", "0", "0", "1", "Obligatorie pentru orice interventie."},
            {"multimetru", "Multimetru", "COMPARATOR", "90", "1", "0", "0", "1", "Masoara siguranta defecta (fara el ghicesti)."},
            {"cleste", "Cleste izolat", "SHEARS", "60", "1", "0", "1", "1", "+1 greseala permisa la conectarea circuitelor."},
            {"cablu", "Cablu electric", "STRING", "20", "4", "1", "0", "16", "Material consumat la cabluri deteriorate si instalatii."},
            {"siguranta", "Siguranta fuzibila", "REDSTONE", "15", "4", "1", "0", "24", "Se foloseste la inlocuirea sigurantelor arse."},
            {"protectie", "Echipament de protectie", "GOLDEN_HELMET", "200", "1", "0", "2", "1", "Fara soc electric la esec; obligatoriu la tensiune inalta."}};
        ConfigurationSection es = c == null ? null : c.getConfigurationSection("equipment");
        for (String[] r : def) {
            Equip e = new Equip();
            e.id = r[0];
            ConfigurationSection s = es == null ? null : es.getConfigurationSection(e.id);
            e.name = s == null ? r[1] : s.getString("name", r[1]);
            e.material = s == null ? r[2] : s.getString("material", r[2]);
            e.price = Math.max(0, Math.min(1_000_000, s == null ? Double.parseDouble(r[3]) : s.getDouble("price", Double.parseDouble(r[3]))));
            e.pack = Math.max(1, Math.min(64, s == null ? Integer.parseInt(r[4]) : s.getInt("pack", Integer.parseInt(r[4]))));
            e.consumable = "1".equals(r[5]);
            e.minRank = Math.max(0, Math.min(ranks.size() - 1, s == null ? Integer.parseInt(r[6]) : s.getInt("min-rank", Integer.parseInt(r[6]))));
            e.max = Math.max(1, Math.min(256, s == null ? Integer.parseInt(r[7]) : s.getInt("max", Integer.parseInt(r[7]))));
            e.desc = s == null ? r[8] : s.getString("desc", r[8]);
            equip.put(e.id, e);
        }
    }

    private void loadTypes() {
        // id, nume, descriere/simptom, reward, xp, secunde, rang minim, dificultate min, max, tensiune, etape, necesar
        String[][] def = {
            {"panou", "Panou electric defect", "Tabloul nu mai da curent; semnalizarile sunt stinse.", "70", "22", "150", "0", "1", "5", "230", "fuse,circuit", ""},
            {"retea", "Retea electrica intrerupta", "Un tronson de retea a cazut; cartierul e fara curent.", "95", "30", "180", "1", "1", "5", "400", "circuit,calib", ""},
            {"sigurante", "Sigurante arse", "Sigurantele au sarit dupa o suprasarcina.", "55", "18", "120", "0", "1", "4", "230", "fuse,seq", ""},
            {"cablu", "Cablu deteriorat", "Izolatia cablului e rupta; scurtcircuit intermitent.", "85", "26", "160", "1", "1", "5", "230", "circuit,calib", "cablu:1"},
            {"generator", "Generator defect", "Generatorul porneste si se opreste; tensiunea oscileaza.", "120", "36", "210", "2", "2", "5", "400", "seq,calib,fuse", ""},
            {"transformator", "Transformator defect", "Transformatorul de cartier s-a supraincalzit.", "170", "52", "260", "3", "3", "5", "20000", "fuse,circuit,calib,seq", "protectie:1"},
            {"instalatie", "Instalatie electrica avariata", "Instalatia cladirii e avariata; mai multe circuite nu functioneaza.", "140", "42", "240", "2", "2", "5", "230", "circuit,fuse,seq", "cablu:2"},
            {"iluminat", "Defectiune iluminat stradal", "Stalpii de pe strada nu se mai aprind.", "65", "20", "140", "0", "1", "4", "230", "circuit,seq", ""}};
        ConfigurationSection ts = c == null ? null : c.getConfigurationSection("types");
        for (String[] r : def) {
            Type t = new Type();
            t.id = r[0];
            ConfigurationSection s = ts == null ? null : ts.getConfigurationSection(t.id);
            t.name = s == null ? r[1] : s.getString("name", r[1]);
            t.symptom = s == null ? r[2] : s.getString("symptom", r[2]);
            t.desc = t.symptom;
            t.enabled = s == null || s.getBoolean("enabled", true);
            t.reward = Math.max(0, Math.min(100000, s == null ? Double.parseDouble(r[3]) : s.getDouble("reward", Double.parseDouble(r[3]))));
            t.xp = Math.max(0, Math.min(100000, s == null ? Long.parseLong(r[4]) : s.getLong("xp", Long.parseLong(r[4]))));
            t.seconds = Math.max(30, Math.min(1800, s == null ? Integer.parseInt(r[5]) : s.getInt("seconds", Integer.parseInt(r[5]))));
            t.minRank = Math.max(0, Math.min(ranks.size() - 1, s == null ? Integer.parseInt(r[6]) : s.getInt("min-rank", Integer.parseInt(r[6]))));
            t.minDiff = Math.max(1, Math.min(5, s == null ? Integer.parseInt(r[7]) : s.getInt("min-diff", Integer.parseInt(r[7]))));
            t.maxDiff = Math.max(t.minDiff, Math.min(5, s == null ? Integer.parseInt(r[8]) : s.getInt("max-diff", Integer.parseInt(r[8]))));
            t.volt = Math.max(1, s == null ? Integer.parseInt(r[9]) : s.getInt("volt", Integer.parseInt(r[9])));
            List<String> st = s != null && s.isList("stages") ? s.getStringList("stages") : new ArrayList<>(Arrays.asList(r[10].split(",")));
            for (String g : st) {
                String k = g.trim().toLowerCase(Locale.ROOT);
                if (Arrays.asList(STAGES).contains(k)) t.stages.add(k);
            }
            if (t.stages.isEmpty()) t.stages.add("circuit");
            if (t.stages.size() > 6) t.stages.subList(6, t.stages.size()).clear();
            ConfigurationSection ns = s == null ? null : s.getConfigurationSection("needs");
            if (ns != null) {
                for (String k : ns.getKeys(false)) if (equip.containsKey(k)) t.needs.put(k, Math.max(1, Math.min(16, ns.getInt(k, 1))));
            } else if (s == null && !r[11].isEmpty()) {
                String[] kv = r[11].split(":");
                t.needs.put(kv[0], Integer.parseInt(kv[1]));
            }
            types.add(t);
        }
    }

    public static String stageName(String g) {
        switch (g) {
            case "circuit":
                return "Conectare circuit";
            case "fuse":
                return "Siguranțe";
            case "seq":
                return "Secvență electrică";
            default:
                return "Calibrare tensiune";
        }
    }
}
