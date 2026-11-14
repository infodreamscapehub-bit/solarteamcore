package ro.solarstudios.flowcity.bridge.politie;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.docs.DocsModule;
import ro.solarstudios.flowcity.bridge.hype.DocLogic;
import ro.solarstudios.flowcity.bridge.mdt.MdtModule;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.notify.NotifyModule;
import ro.solarstudios.flowcity.bridge.pay.PayModule;

/**
 * Hub "Politie" pentru jucatori: amenzi, documente (permis / port-arma; buletinul se face la Primarie), cazier judiciar,
 * plangeri si sesizari, masinile mele, cerere de angajare. Ofiterii au in plus "Sesizari primite".
 * Comenzi: /politie (ecranul), /politie open <jucator> (NPC: CONSOLE politie open %player%). Fara mod, totul merge in chat.
 * Sesizarile se salveaza in plugins/FlowCityBridge/politie-data.yml.
 */
public final class PolitieModule extends JsonModule {
    private static final String[] CATS = {"AM", "A1", "A2", "A", "B1", "B", "BE", "C1", "C1E", "C", "CE", "D1", "D1E", "D", "DE", "Tr", "Tb", "Tv"};

    private YamlConfiguration cfg;
    private YamlConfiguration hype;
    private YamlConfiguration data;
    private File dataFile;
    private long nextId = 1;

    @Override
    public String id() {
        return "politie";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "politie");
        hype = ModConfig.load(plugin, "hype");
        dataFile = new File(plugin.getDataFolder(), "politie-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection items = data.getConfigurationSection("items");
        if (items != null) {
            for (String k : items.getKeys(false)) {
                try {
                    nextId = Math.max(nextId, Long.parseLong(k) + 1);
                } catch (NumberFormatException ignored) {
                    // cheie straina
                }
            }
        }
        on("refresh", (p, j) -> sendOpen(p));
        on("pay", (p, j) -> payFine(p, (long) num(j, "id", -1)));
        on("permis", (p, j) -> requestPermis(p, str(j, "categories", "")));
        on("duplicat", (p, j) -> requestDuplicat(p));
        on("portarma", (p, j) -> requestPortarma(p));
        on("cazier", (p, j) -> requestCazier(p));
        on("submit", (p, j) -> submit(p, str(j, "type", "PLANGERE"), str(j, "title", ""), str(j, "text", ""), str(j, "role", "")));
        on("mechlist", (p, j) -> mechlist(p));
        on("take", (p, j) -> setStatus(p, (long) num(j, "id", -1), "PREASA"));
        on("archive", (p, j) -> setStatus(p, (long) num(j, "id", -1), "ARHIVATA"));
        Commands.register(plugin, "politie", null, (s, c, l, a) -> {
            if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {
                if (s instanceof Player && !s.hasPermission("flowcity.politie.admin")) {
                    s.sendMessage("§cNu ai permisiunea.");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) open(t);
                return true;
            }
            if (!(s instanceof Player)) {
                s.sendMessage("Folosire: politie open <jucator>");
                return true;
            }
            Player p = (Player) s;
            if (a.length == 0) {
                open(p);
                return true;
            }
            String sub = a[0].toLowerCase(java.util.Locale.ROOT);
            String rest = a.length > 1 ? String.join(" ", java.util.Arrays.copyOfRange(a, 1, a.length)) : "";
            switch (sub) {
                case "permis": requestPermis(p, rest); break;
                case "duplicat": requestDuplicat(p); break;
                case "portarma": requestPortarma(p); break;
                case "cazier": requestCazier(p); break;
                case "masini": mechlist(p); break;
                case "plangere": submit(p, "PLANGERE", rest.length() > 40 ? rest.substring(0, 40) : rest, rest, ""); break;
                case "angajare":
                    submit(p, "ANGAJARE", "Cerere de angajare", a.length > 2 ? String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)) : "", a.length > 1 ? a[1] : "");
                    break;
                case "plateste":
                    try {
                        payFine(p, Long.parseLong(rest.trim()));
                    } catch (NumberFormatException e) {
                        p.sendMessage("§cId invalid.");
                    }
                    break;
                case "sesizari": listComplaintsChat(p); break;
                case "preia":
                case "arhiveaza":
                    try {
                        setStatus(p, Long.parseLong(rest.trim()), "preia".equals(sub) ? "PREASA" : "ARHIVATA");
                    } catch (NumberFormatException e) {
                        p.sendMessage("§cId invalid.");
                    }
                    break;
                default: open(p);
            }
            return true;
        }, null, "police");
    }

    // ------------------------------------------------------------------ utilitare

    private boolean officer(Player p) {
        MdtModule m = MdtModule.instance;
        return p.hasPermission(cfg.getString("officer-permission", "flowcity.politie.officer")) || (m != null && m.isOfficer(p));
    }

    private void result(Player p, boolean ok, String msg) {
        if (plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("ok", ok);
            o.addProperty("msg", msg);
            send(p, "result", o);
        } else {
            p.sendMessage((ok ? "§a" : "§c") + msg);
        }
    }

    private void saveData() {
        try {
            data.save(dataFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot salva politie-data.yml: " + e);
        }
    }

    private void paidCommands(Player p, String service, int amount) {
        for (String c : cfg.getStringList("payment-commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                c.replace("%player%", p.getName()).replace("%amount%", String.valueOf(amount)).replace("%service%", service));
        }
    }

    private static String clean(String s, int max) {
        if (s == null) return "";
        String t = s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("§.", "").trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    // ------------------------------------------------------------------ ecran

    private void open(Player p) {
        if (plugin.channel().hasMod(p)) {
            sendOpen(p);
            return;
        }
        MdtModule m = MdtModule.instance;
        DocsModule d = DocsModule.instance;
        p.sendMessage("§9[Politie] §fServicii pentru cetateni:");
        int fines = m == null ? 0 : m.unpaidFines(p.getUniqueId()).size();
        p.sendMessage("§7Amenzi neplatite: " + (fines == 0 ? "§anone" : "§c" + fines + " §7(/politie plateste <id>, lista cu /amenzi)"));
        if (d != null) {
            p.sendMessage("§7Buletin: " + (d.valid(p.getUniqueId(), "buletin") ? "§avalid" : "§cinexistent (se face la Primarie)")
                + " §7| Permis: " + (d.valid(p.getUniqueId(), "permis") ? "§avalid" : "§cnu")
                + " §7| Port-arma: " + (d.valid(p.getUniqueId(), "portarma") ? "§avalid" : "§cnu"));
        }
        p.sendMessage("§7/politie permis <categorii> (" + cfg.getInt("fees.permis", 300) + " lei) §8| §7/politie duplicat (" + cfg.getInt("fees.duplicat-permis", 100) + ")");
        p.sendMessage("§7/politie portarma (" + cfg.getInt("fees.portarma", 1500) + " lei) §8| §7/politie cazier (" + cfg.getInt("fees.cazier", 50) + " lei)");
        p.sendMessage("§7/politie plangere <text> §8| §7/politie angajare <rol> <mesaj> §8| §7/politie masini");
        p.sendMessage("§7Roluri angajare: " + String.join(", ", cfg.getStringList("angajare.roles")));
        if (officer(p)) p.sendMessage("§bOfiter: §7/politie sesizari, /politie preia <id>, /politie arhiveaza <id>");
    }

    private JsonObject docJson(UUID u, String type) {
        JsonObject o = new JsonObject();
        DocsModule d = DocsModule.instance;
        DocsModule.Doc doc = d == null ? null : d.get(u, type);
        o.addProperty("has", doc != null);
        o.addProperty("valid", doc != null && doc.expires > System.currentTimeMillis());
        if (doc != null) {
            o.addProperty("serie", doc.serie + " " + doc.numar);
            o.addProperty("expires", DocLogic.formatDate(doc.expires));
            o.addProperty("categories", doc.categories == null ? "" : doc.categories);
        }
        return o;
    }

    private void sendOpen(Player p) {
        UUID u = p.getUniqueId();
        MdtModule m = MdtModule.instance;
        JsonObject o = new JsonObject();
        o.addProperty("officer", officer(p));
        o.add("fines", m == null ? new JsonArray() : m.unpaidFines(u));
        JsonObject docs = new JsonObject();
        docs.add("buletin", docJson(u, "buletin"));
        docs.add("permis", docJson(u, "permis"));
        docs.add("portarma", docJson(u, "portarma"));
        o.add("docs", docs);
        o.add("cazier", m == null ? new JsonObject() : m.cazierSummary(u));
        JsonObject fees = new JsonObject();
        fees.addProperty("permis", cfg.getInt("fees.permis", 300));
        fees.addProperty("portarma", cfg.getInt("fees.portarma", 1500));
        fees.addProperty("cazier", cfg.getInt("fees.cazier", 50));
        fees.addProperty("duplicat", cfg.getInt("fees.duplicat-permis", 100));
        o.add("fees", fees);
        o.addProperty("minAge", cfg.getInt("conditions.min-age", 18));
        JsonArray cats = new JsonArray();
        for (String c : CATS) cats.add(c);
        o.add("cats", cats);
        JsonArray roles = new JsonArray();
        for (String r : cfg.getStringList("angajare.roles")) roles.add(r);
        o.add("roles", roles);
        o.add("mine", items(item -> u.toString().equals(item.getString("author"))));
        if (officer(p)) o.add("inbox", items(item -> !"ARHIVATA".equals(item.getString("status"))));
        send(p, "open", o);
    }

    private interface Filter {
        boolean ok(ConfigurationSection s);
    }

    private JsonArray items(Filter f) {
        JsonArray arr = new JsonArray();
        ConfigurationSection root = data.getConfigurationSection("items");
        if (root == null) return arr;
        List<Long> ids = new ArrayList<>();
        for (String k : root.getKeys(false)) {
            try {
                ids.add(Long.parseLong(k));
            } catch (NumberFormatException ignored) {
                // cheie straina
            }
        }
        java.util.Collections.sort(ids, java.util.Collections.reverseOrder());
        for (long id : ids) {
            ConfigurationSection s = root.getConfigurationSection(String.valueOf(id));
            if (s == null || !f.ok(s)) continue;
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("type", s.getString("type", ""));
            o.addProperty("title", s.getString("title", ""));
            o.addProperty("text", s.getString("text", ""));
            o.addProperty("role", s.getString("role", ""));
            o.addProperty("author", s.getString("authorName", ""));
            o.addProperty("status", s.getString("status", "NOUA"));
            o.addProperty("handler", s.getString("handler", ""));
            o.addProperty("date", DocLogic.formatDate(s.getLong("date", 0)));
            arr.add(o);
            if (arr.size() >= 60) break;
        }
        return arr;
    }

    // ------------------------------------------------------------------ amenzi

    private void payFine(Player p, long id) {
        MdtModule m = MdtModule.instance;
        PayModule pay = PayModule.instance;
        if (m == null || pay == null) {
            result(p, false, "Plata nu e disponibila.");
            return;
        }
        final int amount = m.fineAmount(p.getUniqueId(), id);
        if (amount < 0) {
            result(p, false, "Amenda nu mai exista sau e deja platita.");
            if (plugin.channel().hasMod(p)) sendOpen(p);
            return;
        }
        pay.request(p, "Amenda #" + id, amount, () -> {
            if (!m.payFineByCitizen(p.getUniqueId(), id)) return false;
            for (String c : hype.getStringList("mdt.fines-payment-commands")) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), c.replace("%player%", p.getName()).replace("%amount%", String.valueOf(amount)));
            }
            result(p, true, "Amenda de " + amount + " lei a fost platita.");
            if (plugin.channel().hasMod(p)) sendOpen(p);
            return true;
        }, msg -> result(p, false, msg));
    }

    // ------------------------------------------------------------------ documente

    /** Conditii comune: buletin valid, varsta minima, fara amenzi neplatite. Intoarce mesajul de eroare sau null. */
    private String eligibility(Player p, boolean portarma) {
        DocsModule d = DocsModule.instance;
        MdtModule m = MdtModule.instance;
        if (d == null) return "Serviciul de documente nu e disponibil.";
        UUID u = p.getUniqueId();
        if (!d.valid(u, "buletin")) return "Ai nevoie de buletin valid (se face la Primarie).";
        DocsModule.Doc b = d.get(u, "buletin");
        long birth = DocLogic.parseDate(b.birth);
        int min = cfg.getInt("conditions.min-age", 18);
        if (birth < 0 || DocLogic.ageYears(birth, System.currentTimeMillis()) < min) return "Trebuie sa ai minim " + min + " ani.";
        if (m != null && m.unpaidFines(u).size() > 0) return "Ai amenzi neplatite - achita-le mai intai.";
        if (portarma && m != null && cfg.getBoolean("conditions.portarma-clean-record", true)) {
            JsonObject c = m.cazierSummary(u);
            if (c.get("wanted").getAsBoolean()) return "Esti in urmarire - nu poti obtine port-arma.";
            if (c.getAsJsonArray("records").size() > 0) return "Cazierul nu e curat - nu poti obtine port-arma.";
        }
        return null;
    }

    private Map<String, String> fromBuletin(UUID u, String categories) {
        DocsModule.Doc b = DocsModule.instance.get(u, "buletin");
        Map<String, String> f = new LinkedHashMap<>();
        f.put("name", b.name);
        f.put("surname", b.surname);
        f.put("birth", b.birth);
        f.put("address", b.address);
        f.put("sex", b.sex);
        f.put("categories", categories == null ? "" : categories);
        return f;
    }

    private void requestPermis(Player p, String raw) {
        String err = eligibility(p, false);
        if (err != null) {
            result(p, false, err);
            return;
        }
        final String cats = DocLogic.normalizeCategories(raw);
        if (cats == null) {
            result(p, false, "Alege categorii valide (ex: B sau A,B).");
            return;
        }
        if (DocsModule.instance.valid(p.getUniqueId(), "permis")) {
            result(p, false, "Ai deja un permis valid (poti cere duplicat).");
            return;
        }
        charge(p, "Permis de conducere", cfg.getInt("fees.permis", 300), () -> {
            DocsModule.Doc d = DocsModule.instance.issue(p.getUniqueId(), "permis", fromBuletin(p.getUniqueId(), cats), "Politie");
            if (d == null) return false;
            result(p, true, "Permisul (" + cats + ") a fost emis.");
            return true;
        });
    }

    private void requestDuplicat(Player p) {
        String err = eligibility(p, false);
        if (err != null) {
            result(p, false, err);
            return;
        }
        final DocsModule.Doc old = DocsModule.instance.get(p.getUniqueId(), "permis");
        if (old == null) {
            result(p, false, "Nu ai niciun permis de duplicat.");
            return;
        }
        charge(p, "Duplicat permis", cfg.getInt("fees.duplicat-permis", 100), () -> {
            DocsModule.Doc d = DocsModule.instance.issue(p.getUniqueId(), "permis", fromBuletin(p.getUniqueId(), old.categories), "Politie");
            if (d == null) return false;
            result(p, true, "Duplicatul permisului a fost emis.");
            return true;
        });
    }

    private void requestPortarma(Player p) {
        String err = eligibility(p, true);
        if (err != null) {
            result(p, false, err);
            return;
        }
        if (DocsModule.instance.valid(p.getUniqueId(), "portarma")) {
            result(p, false, "Ai deja port-arma valid.");
            return;
        }
        charge(p, "Port-arma", cfg.getInt("fees.portarma", 1500), () -> {
            DocsModule.Doc d = DocsModule.instance.issue(p.getUniqueId(), "portarma", fromBuletin(p.getUniqueId(), ""), "Politie");
            if (d == null) return false;
            result(p, true, "Port-arma a fost emis.");
            return true;
        });
    }

    private void charge(Player p, String label, int fee, java.util.function.Supplier<Boolean> issue) {
        PayModule pay = PayModule.instance;
        if (pay == null) {
            result(p, false, "Plata nu e disponibila.");
            return;
        }
        pay.request(p, label, fee, () -> {
            if (!issue.get()) return false;
            paidCommands(p, label, fee);
            if (plugin.channel().hasMod(p)) sendOpen(p);
            return true;
        }, msg -> result(p, false, msg));
    }

    // ------------------------------------------------------------------ cazier

    private void requestCazier(Player p) {
        MdtModule m = MdtModule.instance;
        if (m == null) {
            result(p, false, "Cazierul nu e disponibil.");
            return;
        }
        charge(p, "Certificat cazier judiciar", cfg.getInt("fees.cazier", 50), () -> {
            deliverCazier(p, m.cazierSummary(p.getUniqueId()));
            return true;
        });
    }

    private void deliverCazier(Player p, JsonObject c) {
        DocsModule d = DocsModule.instance;
        DocsModule.Doc b = d == null ? null : d.get(p.getUniqueId(), "buletin");
        String name = b != null ? b.name + " " + b.surname : p.getName();
        List<String> lines = new ArrayList<>();
        JsonArray recs = c.getAsJsonArray("records");
        boolean wanted = c.get("wanted").getAsBoolean();
        if (wanted) lines.add("In urmarire: " + clean(c.get("reason").getAsString(), 80));
        for (int i = 0; i < recs.size() && lines.size() < 12; i++) {
            JsonObject r = recs.get(i).getAsJsonObject();
            lines.add(r.get("date").getAsString() + " - " + clean(r.get("text").getAsString(), 90));
        }
        boolean clear = lines.isEmpty();
        if (plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            o.addProperty("name", name);
            o.addProperty("date", DocLogic.formatDate(System.currentTimeMillis()));
            o.addProperty("clean", clear);
            JsonArray la = new JsonArray();
            for (String s : lines) la.add(s);
            o.add("lines", la);
            send(p, "cazier", o);
        } else {
            p.sendMessage("§9=== CERTIFICAT DE CAZIER JUDICIAR ===");
            p.sendMessage("§7Nume: §f" + name + " §7Data: §f" + DocLogic.formatDate(System.currentTimeMillis()));
            if (clear) p.sendMessage("§aFara antecedente penale.");
            else for (String s : lines) p.sendMessage("§c- §f" + s);
        }
    }

    // ------------------------------------------------------------------ sesizari / angajare

    private void submit(Player p, String typeIn, String titleIn, String textIn, String roleIn) {
        boolean job = "ANGAJARE".equalsIgnoreCase(typeIn);
        String title = clean(titleIn, 60);
        String text = clean(textIn, 400);
        String role = clean(roleIn, 30);
        if (job) {
            List<String> roles = cfg.getStringList("angajare.roles");
            String match = null;
            for (String r : roles) if (r.equalsIgnoreCase(role)) match = r;
            if (match == null) {
                result(p, false, "Alege un rol din lista.");
                return;
            }
            role = match;
            title = "Cerere angajare: " + role;
        } else if (title.length() < 3) {
            result(p, false, "Scrie un titlu (minim 3 caractere).");
            return;
        }
        if (text.length() < 5) {
            result(p, false, "Scrie un mesaj (minim 5 caractere).");
            return;
        }
        long pending = 0;
        ConfigurationSection root = data.getConfigurationSection("items");
        if (root != null) {
            for (String k : root.getKeys(false)) {
                ConfigurationSection s = root.getConfigurationSection(k);
                if (s != null && p.getUniqueId().toString().equals(s.getString("author")) && "NOUA".equals(s.getString("status"))) pending++;
            }
        }
        if (pending >= 5) {
            result(p, false, "Ai deja 5 sesizari in asteptare.");
            return;
        }
        long id = nextId++;
        String b = "items." + id + ".";
        String type = job ? "ANGAJARE" : "PLANGERE";
        data.set(b + "author", p.getUniqueId().toString());
        data.set(b + "authorName", p.getName());
        data.set(b + "type", type);
        data.set(b + "title", title);
        data.set(b + "text", text);
        data.set(b + "role", role);
        data.set(b + "date", System.currentTimeMillis());
        data.set(b + "status", "NOUA");
        saveData();
        result(p, true, (job ? "Cererea" : "Sesizarea") + " #" + id + " a fost trimisa.");
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.equals(p) || !officer(o)) continue;
            String head = (job ? "Cerere de angajare" : "Sesizare noua") + " #" + id;
            if (plugin.notifications() != null) plugin.notifications().send(o, NotifyModule.Type.INFO, head, title + " - " + p.getName());
            else o.sendMessage("§9[Politie] §f" + head + " §7de la " + p.getName());
            if (plugin.channel().hasMod(o)) sendOpen(o);
        }
        if (plugin.channel().hasMod(p)) sendOpen(p);
    }

    private void listComplaintsChat(Player p) {
        if (!officer(p)) {
            p.sendMessage("§cNu esti ofiter.");
            return;
        }
        JsonArray a = items(s -> !"ARHIVATA".equals(s.getString("status")));
        if (a.size() == 0) {
            p.sendMessage("§aNu sunt sesizari active.");
            return;
        }
        for (int i = 0; i < Math.min(15, a.size()); i++) {
            JsonObject o = a.get(i).getAsJsonObject();
            p.sendMessage("§7#" + o.get("id").getAsLong() + " §e" + o.get("status").getAsString() + " §f" + o.get("title").getAsString()
                + " §7(" + o.get("author").getAsString() + ", " + o.get("date").getAsString() + "): §f" + o.get("text").getAsString());
        }
    }

    private void setStatus(Player p, long id, String status) {
        if (!officer(p)) {
            result(p, false, "Nu esti ofiter.");
            return;
        }
        ConfigurationSection s = data.getConfigurationSection("items." + id);
        if (s == null) {
            result(p, false, "Sesizarea nu exista.");
            return;
        }
        data.set("items." + id + ".status", status);
        data.set("items." + id + ".handler", p.getName());
        saveData();
        result(p, true, "Sesizarea #" + id + ": " + status + ".");
        if (plugin.channel().hasMod(p)) sendOpen(p);
        String author = s.getString("author");
        if (author != null && "PREASA".equals(status)) {
            try {
                plugin.notifications().send(UUID.fromString(author), NotifyModule.Type.INFO, "Politie", "Sesizarea #" + id + " a fost preluata de " + p.getName() + ".");
            } catch (IllegalArgumentException ignored) {
                // uuid invalid
            }
        }
    }

    // ------------------------------------------------------------------ masini

    private void mechlist(Player p) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fccars mechlist " + p.getName());
    }
}
