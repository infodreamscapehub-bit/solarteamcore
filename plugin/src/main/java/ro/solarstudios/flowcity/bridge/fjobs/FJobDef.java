package ro.solarstudios.flowcity.bridge.fjobs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import ro.solarstudios.flowcity.bridge.hype.JobRules;

/** Definitia unui job FJobs (incarcata din fjobs.yml + suprascrieri din fjobs-admin.yml). Fara logica de joc. */
public final class FJobDef {
    /** Un punct din lume (ridicare / livrare / checkpoint). */
    public static final class Point {
        public String name = "Punct";
        public String world = "world";
        public double x;
        public double y = 64;
        public double z;

        public double dist(Point o) {
            return Math.sqrt((x - o.x) * (x - o.x) + (z - o.z) * (z - o.z));
        }
    }

    /** Livrari: ridici de la un punct de ridicare si duci la un punct de livrare. */
    public static final class Delivery {
        public final List<Point> pickups = new ArrayList<>();
        public final List<Point> drops = new ArrayList<>();
        public double radius = 4;
        public double basePay = 10;
        public double per100m = 4;
        public long xp = 6;
        public String item = "coletul";
        public double minSpeed = 30.0;   // m/s: sub aceasta viteza maxima nu platim (teleportari)
    }

    /** Ruta: treci prin checkpoint-uri in ordine; plata per punct + bonus la final. */
    public static final class Route {
        public final List<Point> points = new ArrayList<>();
        public double radius = 5;
        public double payPerPoint = 6;
        public long xpPerPoint = 3;
        public double completeBonus = 20;
        public long completeXp = 10;
        public double minSecondsPerPoint = 8;   // anti-abuz: nu platim daca ajungi mai repede de atat intre puncte
        public String label = "checkpoint";
    }

    public enum Status {
        READY,      // logica reala + configurata
        EXTERNAL,   // lucrul si plata se fac in alt sistem (taxi, mecanic, facțiuni); FJobs tine doar evidenta angajarii
        PREPARING   // nu are inca logica reala sau lipsesc punctele; nu se poate lua
    }

    public String id;
    public String name;
    public String icon = "work";
    public String category = "civil";     // civil | special
    public String group = "Diverse";
    public String desc = "";
    public String difficulty = "Usor";
    public double estPay = 0;             // estimat pe ora
    public int estMinutes = 30;           // durata tipica a unei ture
    public int minLevel = 0;              // nivel profesional global minim
    public long reqXp = 0;                // XP total minim (toate joburile)
    public int maxEmployees = 0;          // 0 = nelimitat
    public boolean active = true;
    public boolean premium = false;
    public String requiresPermission = "";
    public String requiresNote = "";
    public double payMultiplier = 1.0;
    public int limitPerMinute = 0;
    public String activity = "none";      // rules | delivery | route | external | none
    public String external = "";          // taxi | mecanic | politie | ems ...
    public String externalNote = "";
    public List<String> tasks = new ArrayList<>();
    public final JobRules rules = new JobRules();
    public Delivery delivery;
    public Route route;

    /** Nivel minim (al jobului) pentru o regula: deblocari de activitati (ex. minereuri rare). */
    private final Map<JobRules.Rule, Integer> minLevelByRule = new java.util.IdentityHashMap<>();

    public int minLevelOf(JobRules.Rule r) {
        Integer v = minLevelByRule.get(r);
        return v == null ? 0 : v;
    }

    public Status status() {
        if ("external".equals(activity)) return Status.EXTERNAL;
        if ("rules".equals(activity) && rules.size() > 0) return Status.READY;
        if ("delivery".equals(activity) && delivery != null && !delivery.pickups.isEmpty() && !delivery.drops.isEmpty()) return Status.READY;
        if ("route".equals(activity) && route != null && route.points.size() >= 2) return Status.READY;
        return Status.PREPARING;
    }

    public String statusNote() {
        switch (status()) {
            case READY:
                return "";
            case EXTERNAL:
                return externalNote.isEmpty() ? "Lucrezi prin sistemul dedicat; plata nu e urmarita aici." : externalNote;
            default:
                if ("delivery".equals(activity)) return "In pregatire: adminul trebuie sa seteze punctele de ridicare si de livrare.";
                if ("route".equals(activity)) return "In pregatire: adminul trebuie sa seteze cel putin 2 checkpoint-uri.";
                return "In pregatire: activitatea acestui job nu are inca logica de joc.";
        }
    }

    public static FJobDef parse(String id, ConfigurationSection s) {
        FJobDef j = new FJobDef();
        j.id = id.toLowerCase(Locale.ROOT);
        j.name = s.getString("name", id);
        j.icon = s.getString("icon", "work");
        j.category = "special".equalsIgnoreCase(s.getString("category", "civil")) ? "special" : "civil";
        j.group = s.getString("group", "Diverse");
        j.desc = s.getString("desc", "");
        j.difficulty = s.getString("difficulty", "Usor");
        j.estPay = Math.max(0, s.getDouble("est-pay", 0));
        j.estMinutes = Math.max(1, s.getInt("est-minutes", 30));
        j.minLevel = Math.max(0, s.getInt("min-level", 0));
        j.reqXp = Math.max(0, s.getLong("req-xp", 0));
        j.maxEmployees = Math.max(0, s.getInt("max-employees", 0));
        j.active = s.getBoolean("active", true);
        j.premium = s.getBoolean("premium", false);
        j.requiresPermission = s.getString("requires-permission", "");
        j.requiresNote = s.getString("requires-note", "");
        j.payMultiplier = Math.max(0, Math.min(10, s.getDouble("pay-multiplier", 1.0)));
        j.limitPerMinute = Math.max(0, s.getInt("limit-per-minute", 0));
        j.activity = s.getString("activity", "none").toLowerCase(Locale.ROOT);
        j.external = s.getString("external", "");
        j.externalNote = s.getString("external-note", "");
        j.tasks = new ArrayList<>(s.getStringList("tasks"));
        for (Map<?, ?> m : s.getMapList("rules")) {
            Object ev = m.get("event");
            Object mats = m.get("materials");
            if (ev == null || !(mats instanceof List)) continue;
            List<String> ms = new ArrayList<>();
            for (Object o : (List<?>) mats) ms.add(String.valueOf(o));
            double pay = m.get("pay") instanceof Number ? ((Number) m.get("pay")).doubleValue() : 0;
            long xp = m.get("xp") instanceof Number ? ((Number) m.get("xp")).longValue() : 0;
            boolean mature = Boolean.TRUE.equals(m.get("mature"));
            boolean under = Boolean.TRUE.equals(m.get("underwater"));
            boolean pc = !Boolean.FALSE.equals(m.get("placed-check"));
            int ml = m.get("min-level") instanceof Number ? ((Number) m.get("min-level")).intValue() : 0;
            JobRules.Rule rule = new JobRules.Rule(String.valueOf(ev), ms, pay, xp, mature, under, pc);
            j.rules.add(rule);
            j.minLevelByRule.put(rule, Math.max(0, ml));
        }
        ConfigurationSection ds = s.getConfigurationSection("delivery");
        if (ds != null) {
            Delivery dv = new Delivery();
            dv.radius = Math.max(1, ds.getDouble("radius", 4));
            dv.basePay = Math.max(0, ds.getDouble("base-pay", 10));
            dv.per100m = Math.max(0, ds.getDouble("per-100m", 4));
            dv.xp = Math.max(0, ds.getLong("xp", 6));
            dv.item = ds.getString("item", "coletul");
            dv.minSpeed = Math.max(5, ds.getDouble("max-speed", 30.0));
            readPoints(ds.getMapList("pickups"), dv.pickups);
            readPoints(ds.getMapList("drops"), dv.drops);
            j.delivery = dv;
        }
        ConfigurationSection rs = s.getConfigurationSection("route");
        if (rs != null) {
            Route r = new Route();
            r.radius = Math.max(1, rs.getDouble("radius", 5));
            r.payPerPoint = Math.max(0, rs.getDouble("pay-per-point", 6));
            r.xpPerPoint = Math.max(0, rs.getLong("xp-per-point", 3));
            r.completeBonus = Math.max(0, rs.getDouble("complete-bonus", 20));
            r.completeXp = Math.max(0, rs.getLong("complete-xp", 10));
            r.minSecondsPerPoint = Math.max(0, rs.getDouble("min-seconds-per-point", 8));
            r.label = rs.getString("label", "checkpoint");
            readPoints(rs.getMapList("points"), r.points);
            j.route = r;
        }
        return j;
    }

    public static void readPoints(List<Map<?, ?>> in, List<Point> out) {
        for (Map<?, ?> m : in) {
            Point pt = new Point();
            pt.name = String.valueOf(m.get("name") == null ? "Punct" : m.get("name"));
            pt.world = String.valueOf(m.get("world") == null ? "world" : m.get("world"));
            pt.x = m.get("x") instanceof Number ? ((Number) m.get("x")).doubleValue() : 0;
            pt.y = m.get("y") instanceof Number ? ((Number) m.get("y")).doubleValue() : 64;
            pt.z = m.get("z") instanceof Number ? ((Number) m.get("z")).doubleValue() : 0;
            out.add(pt);
        }
    }

    public static List<Map<String, Object>> writePoints(List<Point> pts) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Point pt : pts) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("name", pt.name);
            m.put("world", pt.world);
            m.put("x", Math.round(pt.x * 10) / 10.0);
            m.put("y", Math.round(pt.y * 10) / 10.0);
            m.put("z", Math.round(pt.z * 10) / 10.0);
            out.add(m);
        }
        return out;
    }
}
