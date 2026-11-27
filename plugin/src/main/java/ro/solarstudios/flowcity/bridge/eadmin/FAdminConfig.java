package ro.solarstudios.flowcity.bridge.eadmin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.fjobs.FJobsModule;

/**
 * Editorul de configurari din FAdmin. Nu expune fisiere intregi: doar un catalog fix de valori (taxe, preturi, salarii, cooldown-uri,
 * recompense, setari), grupate pe categorii cu nivel minim. Doar valori simple (numar / da-nu / text scurt); cheile cu comenzi sau
 * permisiuni nu se pot edita niciodata de aici. Modificarea pastreaza comentariile din fisier si reincarca modulul unde se poate.
 */
public final class FAdminConfig {
    /** O sursa de chei: fisierul, prefixul de cale si, optional, sufixul cheii (ex. ".price"). */
    private static final class Src {
        final String file;
        final String prefix;
        final String suffix;

        Src(String file, String prefix, String suffix) {
            this.file = file;
            this.prefix = prefix;
            this.suffix = suffix;
        }
    }

    private static final class Cat {
        final String id;
        final String label;
        final FAdminLevel.Cap cap;
        final List<Src> srcs = new ArrayList<>();

        Cat(String id, String label, FAdminLevel.Cap cap, String... specs) {
            this.id = id;
            this.label = label;
            this.cap = cap;
            for (String sp : specs) {
                String[] a = sp.split(":");
                String path = a[1];
                String suf = "";
                int at = path.indexOf('|');
                if (at >= 0) {
                    suf = path.substring(at + 1);
                    path = path.substring(0, at);
                }
                srcs.add(new Src(a[0], path, suf));
            }
        }
    }

    private static final List<Cat> CATS = Arrays.asList(
        new Cat("salarii", "Salarii", FAdminLevel.Cap.CFG_BASIC, "payday:base-salary", "payday:min-salary", "payday:groups", "payday:bonuses"),
        new Cat("taxe", "Taxe și dobânzi", FAdminLevel.Cap.CFG_BASIC, "taxi:company-cut-percent", "houses:sell-percent", "houses:rent",
            "hype:sell", "hype:mdt", "bank:loans", "bank:savings"),
        new Cat("preturi", "Prețuri", FAdminLevel.Cap.CFG_BASIC, "hype:fuel", "hype:garages", "taxi:fares", "battlepass:season", "hype:dealership.vehicles|.price"),
        new Cat("cooldown", "Cooldown-uri", FAdminLevel.Cap.CFG_BASIC, "payday:interval-minutes", "payday:afk-minutes", "houses:teleport-cooldown-seconds",
            "taxi:call-cooldown-seconds", "fjobs:settings.change-cooldown-minutes", "fjobs:settings.max-shift-minutes", "fjobs:settings.max-pause-minutes",
            "hype:dealership.restock-minutes"),
        new Cat("recompense", "Recompense", FAdminLevel.Cap.CFG_BASIC, "fjobs:settings.level-rewards", "fjobs:settings.shift-bonus", "battlepass:rewards|.amount",
            "battlepass:daily-count", "battlepass:weekly-count"),
        new Cat("setari", "Setări sistem", FAdminLevel.Cap.CFG_SYSTEM, "fjobs:settings", "houses:max-houses-per-player", "houses:max-roommates",
            "hype:dealership.default-stock", "hype:dealership.restock-amount", "taxi:request-ttl-seconds", "payday:enabled"));

    /** Cuvinte care fac o cheie needitabila (comenzi, permisiuni, lista de blocuri...). */
    private static final String[] DENY = {"command", "permission", "grant", "revoke", "pump-blocks", "timezone", "world", "material", "trash-block"};
    private static final int MAX_LEAVES = 300;

    private final FlowCityBridge plugin;

    public FAdminConfig(FlowCityBridge plugin) {
        this.plugin = plugin;
    }

    private static boolean denied(String path) {
        String low = path.toLowerCase(Locale.ROOT);
        for (String d : DENY) if (low.contains(d)) return true;
        return false;
    }

    private Cat cat(String id) {
        for (Cat c : CATS) if (c.id.equals(id)) return c;
        return null;
    }

    public JsonArray categories(FAdminLevel lv) {
        JsonArray a = new JsonArray();
        for (Cat c : CATS) {
            if (!lv.can(c.cap)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", c.id);
            o.addProperty("label", c.label);
            a.add(o);
        }
        return a;
    }

    private static String fileName(String f) {
        return f.replace("..", "").replace("/", "").replace("\\", "");
    }

    private static boolean known(String file) {
        for (Cat c : CATS) for (Src s : c.srcs) if (s.file.equals(file)) return true;
        return false;
    }

    /** Valorile editabile dintr-o categorie. */
    public JsonArray list(FAdminLevel lv, String catId) {
        JsonArray out = new JsonArray();
        Cat c = cat(catId);
        if (c == null || !lv.can(c.cap)) return out;
        for (Src s : c.srcs) {
            YamlConfiguration y = ModConfig.load(plugin, fileName(s.file));
            Object root = y.get(s.prefix);
            if (root == null) continue;
            if (root instanceof ConfigurationSection) collect(out, s, (ConfigurationSection) root, s.prefix);
            else add(out, s, s.prefix, root);
            if (out.size() >= MAX_LEAVES) break;
        }
        return out;
    }

    private void collect(JsonArray out, Src s, ConfigurationSection sec, String path) {
        for (String k : sec.getKeys(false)) {
            if (out.size() >= MAX_LEAVES) return;
            Object v = sec.get(k);
            String p = path + "." + k;
            if (v instanceof ConfigurationSection) collect(out, s, (ConfigurationSection) v, p);
            else add(out, s, p, v);
        }
    }

    private void add(JsonArray out, Src s, String path, Object v) {
        if (denied(path)) return;
        if (!s.suffix.isEmpty() && !path.endsWith(s.suffix)) return;
        String type;
        if (v instanceof Boolean) type = "bool";
        else if (v instanceof Number) type = "num";
        else if (v instanceof String && ((String) v).length() <= 80) type = "str";
        else return;
        JsonObject o = new JsonObject();
        o.addProperty("key", s.file + ":" + path);
        o.addProperty("file", s.file);
        o.addProperty("path", path);
        o.addProperty("type", type);
        o.addProperty("value", String.valueOf(v));
        out.add(o);
    }

    /** Rezultatul unei modificari: mesaj de eroare (null = ok), valoarea veche. */
    public static final class SetResult {
        public String error;
        public String oldValue = "";
        public String newValue = "";
        public String note = "";
    }

    public SetResult set(FAdminLevel lv, String key, String value) {
        SetResult r = new SetResult();
        int at = key.indexOf(':');
        if (at <= 0) {
            r.error = "Cheie invalidă.";
            return r;
        }
        String file = key.substring(0, at);
        String path = key.substring(at + 1);
        Cat found = null;
        for (Cat c : CATS) {
            if (!lv.can(c.cap)) continue;
            for (Src s : c.srcs) {
                if (!s.file.equals(file)) continue;
                if (!(path.equals(s.prefix) || path.startsWith(s.prefix + "."))) continue;
                if (!s.suffix.isEmpty() && !path.endsWith(s.suffix)) continue;
                found = c;
            }
        }
        if (found == null || !known(file) || denied(path)) {
            r.error = "Valoarea nu poate fi modificată cu nivelul tău.";
            return r;
        }
        YamlConfiguration y = ModConfig.load(plugin, fileName(file));
        Object cur = y.get(path);
        if (cur == null || cur instanceof ConfigurationSection) {
            r.error = "Valoarea nu există.";
            return r;
        }
        String yaml;
        Object parsed;
        String v = value == null ? "" : value.trim();
        if (cur instanceof Boolean) {
            if (!v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
                r.error = "Alege da sau nu.";
                return r;
            }
            parsed = Boolean.valueOf(v);
            yaml = v.toLowerCase(Locale.ROOT);
        } else if (cur instanceof Number) {
            double d;
            try {
                d = Double.parseDouble(v.replace(',', '.'));
            } catch (NumberFormatException e) {
                r.error = "Număr invalid.";
                return r;
            }
            if (Double.isNaN(d) || Double.isInfinite(d) || d < 0 || d > 1_000_000_000d) {
                r.error = "Număr în afara limitelor (0 – 1.000.000.000).";
                return r;
            }
            boolean whole = cur instanceof Integer || cur instanceof Long;
            if (whole && d != Math.rint(d)) {
                r.error = "Valoarea trebuie să fie număr întreg.";
                return r;
            }
            if (whole) {
                parsed = (long) d;
                yaml = String.valueOf((long) d);
            } else {
                parsed = d;
                yaml = d == Math.rint(d) && Math.abs(d) < 1e12 ? String.valueOf((long) d) : String.valueOf(d);
            }
        } else {
            if (v.length() > 80 || v.indexOf('\n') >= 0 || v.indexOf('"') >= 0 || v.indexOf('\\') >= 0 || v.indexOf('§') >= 0) {
                r.error = "Text invalid (max 80 caractere, fără ghilimele).";
                return r;
            }
            parsed = v;
            yaml = "\"" + v + "\"";
        }
        r.oldValue = String.valueOf(cur);
        r.newValue = String.valueOf(parsed);
        File f = new File(plugin.getDataFolder(), fileName(file) + ".yml");
        if (!rewrite(f, path, yaml)) {
            y.set(path, parsed);
            ModConfig.save(y, plugin, fileName(file));
        }
        r.note = reload(file);
        return r;
    }

    /** Inlocuieste valoarea cheii direct in text, ca sa pastreze comentariile. false daca nu gaseste cheia. */
    static boolean rewrite(File f, String path, String yamlValue) {
        try {
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            String[] want = path.split("\\.");
            int[] indents = new int[want.length + 1];
            String[] keys = new String[want.length + 1];
            int depth = 0;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("-")) continue;
                int colon = t.indexOf(':');
                if (colon <= 0) continue;
                int ind = line.length() - line.replaceAll("^\\s+", "").length();
                String key = t.substring(0, colon).trim();
                if ((key.startsWith("\"") && key.endsWith("\"")) || (key.startsWith("'") && key.endsWith("'"))) key = key.substring(1, key.length() - 1);
                while (depth > 0 && indents[depth - 1] >= ind) depth--;
                if (depth >= want.length) continue;
                indents[depth] = ind;
                keys[depth] = key;
                depth++;
                boolean match = depth == want.length;
                for (int d = 0; match && d < depth; d++) match = want[d].equals(keys[d]);
                if (!match) continue;
                String rest = line.substring(line.indexOf(':', ind) + 1);
                String body = rest.trim();
                if (body.isEmpty() || body.startsWith("#")) return false;   // sectiune, nu valoare simpla
                String comment = "";
                int cut;
                if (body.startsWith("\"") || body.startsWith("'")) {
                    char q = body.charAt(0);
                    int end = body.indexOf(q, 1);
                    cut = end < 0 ? body.length() : end + 1;
                    String tail = body.substring(cut);
                    int h = tail.indexOf('#');
                    comment = h >= 0 ? " " + tail.substring(h) : "";
                } else {
                    int h = body.indexOf(" #");
                    comment = h >= 0 ? body.substring(h) : "";
                }
                String head = line.substring(0, line.indexOf(':', ind) + 1);
                lines.set(i, head + " " + yamlValue + comment);
                Files.write(f.toPath(), lines, StandardCharsets.UTF_8);
                return true;
            }
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    private String reload(String file) {
        try {
            switch (file) {
                case "payday":
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "payday reload");
                    return "Reîncărcat.";
                case "bank":
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "banca reload");
                    return "Reîncărcat.";
                case "hype":
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "hype reload");
                    return "Reîncărcat.";
                case "fjobs":
                    if (FJobsModule.instance != null) {
                        FJobsModule.instance.adminReload();
                        return "Reîncărcat.";
                    }
                    return "Se aplică la restart.";
                default:
                    return "Se aplică la următorul restart.";
            }
        } catch (RuntimeException e) {
            return "Salvat; reîncărcarea a eșuat (se aplică la restart).";
        }
    }
}
