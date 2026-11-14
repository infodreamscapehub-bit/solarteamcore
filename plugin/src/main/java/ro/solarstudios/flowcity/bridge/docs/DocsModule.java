package ro.solarstudios.flowcity.bridge.docs;

import com.google.gson.JsonObject;
import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.hype.DocLogic;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.pay.PayModule;

/**
 * Documente (buletin, permis, port-arma) si Primaria. Buletinul se face la Primarie (/primarie, NPC: CONSOLE primarie open %player%),
 * celelalte le elibereaza personalul cu flowcity.hype.doc (/doc give). /buletin /permis /portarma ti le arata, /arata le arata
 * jucatorului din apropiere. Canal "docs": server -> client: primarie, view, result; client -> server: apply, renew, duplicate, refresh, view, show.
 */
public final class DocsModule extends JsonModule {
    public static DocsModule instance;

    public static final class Doc {
        public String type;
        public UUID owner;
        public String name;
        public String surname;
        public String birth;
        public String address;
        public String sex;
        public String serie;
        public String numar;
        public String categories;
        public long issued;
        public long expires;
        public String issuer;
        public String cnp = "";
        public String birthPlace = "";
    }

    private static final String[] TYPES = {"buletin", "permis", "portarma"};

    private YamlConfiguration hype;
    private YamlConfiguration prim;
    private YamlConfiguration data;
    private File dataFile;
    private final Random rnd = new Random();
    private final Set<String> serials = new HashSet<>();

    @Override
    public String id() {
        return "docs";
    }

    @Override
    protected void onEnable() {
        instance = this;
        hype = ModConfig.load(plugin, "hype");
        prim = ModConfig.load(plugin, "primarie");
        dataFile = new File(plugin.getDataFolder(), "docs-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection all = data.getConfigurationSection("docs");
        if (all != null) {
            for (String u : all.getKeys(false)) {
                ConfigurationSection per = all.getConfigurationSection(u);
                if (per == null) continue;
                for (String t : per.getKeys(false)) {
                    String s = per.getString(t + ".serie", "") + " " + per.getString(t + ".numar", "");
                    serials.add(s);
                }
            }
        }
        on("refresh", (p, j) -> openPrimarie(p));
        on("apply", (p, j) -> apply(p, str(j, "name", ""), str(j, "surname", ""), str(j, "birth", ""), str(j, "address", ""), str(j, "sex", ""), str(j, "birthPlace", "")));
        on("renew", (p, j) -> reissue(p, true));
        on("duplicate", (p, j) -> reissue(p, false));
        on("view", (p, j) -> show(p, p, str(j, "type", "buletin")));
        on("show", (p, j) -> showNearest(p, str(j, "type", "buletin")));
        registerCommands();
    }

    // ------------------------------------------------------------------ API pentru alte module

    private static String type(String t) {
        if (t == null) return null;
        String x = t.toLowerCase(java.util.Locale.ROOT).replace("-", "").replace("ă", "a").replace("â", "a");
        for (String k : TYPES) if (k.equals(x)) return k;
        return null;
    }

    public Doc get(UUID u, String type) {
        String t = type(type);
        if (t == null) return null;
        ConfigurationSection s = data.getConfigurationSection("docs." + u + "." + t);
        if (s == null) return null;
        Doc d = new Doc();
        d.type = t;
        d.owner = u;
        d.name = s.getString("name", "");
        d.surname = s.getString("surname", "");
        d.birth = s.getString("birth", "");
        d.address = s.getString("address", "");
        d.sex = s.getString("sex", "");
        d.serie = s.getString("serie", "");
        d.numar = s.getString("numar", "");
        d.categories = s.getString("categories", "");
        d.issued = s.getLong("issued", 0);
        d.expires = s.getLong("expires", 0);
        d.issuer = s.getString("issuer", "");
        d.birthPlace = s.getString("birthPlace", "FlowCity");
        d.cnp = s.getString("cnp", "");
        if (d.cnp.isEmpty()) d.cnp = fakeCnp(u, d.birth, d.sex);
        return d;
    }

    public boolean valid(UUID u, String type) {
        Doc d = get(u, type);
        return d != null && d.expires > System.currentTimeMillis();
    }

    private int years(String t) {
        switch (t) {
            case "permis": return Math.max(1, hype.getInt("documents.permis-valid-years", 10));
            case "portarma": return Math.max(1, hype.getInt("documents.portarma-valid-years", 5));
            default: return Math.max(1, hype.getInt("documents.buletin-valid-years", 10));
        }
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot salva docs-data.yml: " + e);
        }
    }

    private void write(Doc d) {
        String b = "docs." + d.owner + "." + d.type + ".";
        data.set(b + "name", d.name);
        data.set(b + "surname", d.surname);
        data.set(b + "birth", d.birth);
        data.set(b + "address", d.address);
        data.set(b + "sex", d.sex);
        data.set(b + "serie", d.serie);
        data.set(b + "numar", d.numar);
        data.set(b + "categories", d.categories);
        data.set(b + "issued", d.issued);
        data.set(b + "expires", d.expires);
        data.set(b + "issuer", d.issuer);
        data.set(b + "birthPlace", d.birthPlace);
        data.set(b + "cnp", d.cnp);
        save();
    }

    /** CNP FICTIV (judet "99" = nu exista), cu cifra de control reala ca sa arate credibil. */
    static String fakeCnp(UUID u, String birth, String sex) {
        long b = DocLogic.parseDate(birth);
        if (b < 0) return "";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(b);
        int y = c.get(java.util.Calendar.YEAR);
        boolean f = "F".equalsIgnoreCase(sex);
        int sd = y >= 2000 ? (f ? 6 : 5) : (f ? 2 : 1);
        int nnn = Math.floorMod(u.hashCode(), 900) + 100;
        String base = String.format("%d%02d%02d%02d99%03d", sd, y % 100, c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH), nnn);
        int[] key = {2, 7, 9, 1, 4, 6, 3, 5, 8, 2, 7, 9};
        int sum = 0;
        for (int i = 0; i < 12; i++) sum += (base.charAt(i) - '0') * key[i];
        int r = sum % 11;
        return base + (r == 10 ? 1 : r);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** Emite (sau inlocuieste) documentul: serie/numar noi, valabilitate din hype.yml. Campuri: name, surname, birth, address, sex, categories. */
    public Doc issue(UUID u, String type, Map<String, String> fields, String issuer) {
        String t = type(type);
        if (t == null) return null;
        Doc old = get(u, t);
        if (old != null) serials.remove(old.serie + " " + old.numar);
        String[] sn = DocLogic.generate("permis".equals(t), rnd, serials::contains);
        if (sn == null) return null;
        Doc d = new Doc();
        d.type = t;
        d.owner = u;
        d.name = nz(fields.get("name"));
        d.surname = nz(fields.get("surname"));
        d.birth = nz(fields.get("birth"));
        d.address = nz(fields.get("address"));
        d.sex = nz(fields.get("sex"));
        d.categories = nz(fields.get("categories"));
        d.serie = sn[0];
        d.numar = sn[1];
        d.issued = System.currentTimeMillis();
        d.expires = DocLogic.addYears(d.issued, years(t));
        d.issuer = issuer == null ? "" : issuer;
        String bp = nz(fields.get("birthPlace")).replaceAll("[\\p{Cntrl}§&]", "").trim();
        d.birthPlace = bp.isEmpty() ? "FlowCity" : bp;
        d.cnp = fakeCnp(u, d.birth, d.sex);
        serials.add(d.serie + " " + d.numar);
        write(d);
        return d;
    }

    public JsonObject toJson(Doc d) {
        JsonObject o = new JsonObject();
        o.addProperty("type", d.type);
        o.addProperty("name", d.name);
        o.addProperty("surname", d.surname);
        o.addProperty("birth", d.birth);
        o.addProperty("address", d.address);
        o.addProperty("sex", d.sex);
        o.addProperty("serie", d.serie);
        o.addProperty("numar", d.numar);
        o.addProperty("categories", d.categories);
        o.addProperty("issued", d.issued);
        o.addProperty("expires", d.expires);
        o.addProperty("issuedStr", DocLogic.formatDate(d.issued));
        o.addProperty("expiresStr", DocLogic.formatDate(d.expires));
        o.addProperty("expired", d.expires <= System.currentTimeMillis());
        o.addProperty("issuer", d.issuer);
        o.addProperty("cnp", d.cnp);
        o.addProperty("birthPlace", d.birthPlace);
        o.addProperty("citizenship", "Română / ROU");
        o.addProperty("ownerUuid", String.valueOf(d.owner));
        return o;
    }

    private static String typeName(String t) {
        return "permis".equals(t) ? "Permis de conducere" : "portarma".equals(t) ? "Port-arma" : "Buletin";
    }

    /** Arata documentul lui owner jucatorului viewer (ecran cu modul, text in chat fara). */
    public void show(Player owner, Player viewer, String type) {
        String t = type(type);
        if (t == null) {
            owner.sendMessage("§cTip necunoscut. Foloseste: buletin, permis sau portarma.");
            return;
        }
        Doc d = get(owner.getUniqueId(), t);
        if (d == null) {
            owner.sendMessage("§cNu ai " + typeName(t).toLowerCase() + ".");
            return;
        }
        if (plugin.channel().hasMod(viewer)) {
            JsonObject o = toJson(d);
            o.addProperty("owner", owner.getName());
            o.addProperty("self", owner == viewer);
            send(viewer, "view", o);
        } else {
            viewer.sendMessage("§9[" + typeName(t) + " - " + owner.getName() + "]");
            viewer.sendMessage("§f" + d.surname + " " + d.name + " §7| nascut " + d.birth + ("-".equals(d.sex) || d.sex.isEmpty() ? "" : " | " + d.sex));
            viewer.sendMessage("§7Serie/Nr: §f" + d.serie + " " + d.numar + " §7| " + (d.categories.isEmpty() ? "" : "categorii " + d.categories + " | ")
                + "expira " + DocLogic.formatDate(d.expires) + (d.expires <= System.currentTimeMillis() ? " §c(EXPIRAT)" : ""));
            if (!d.address.isEmpty()) viewer.sendMessage("§7Adresa: §f" + d.address);
        }
        if (owner != viewer) owner.sendMessage("§aI-ai aratat " + typeName(t).toLowerCase() + "ul lui " + viewer.getName() + ".");
    }

    private void showNearest(Player p, String type) {
        Player best = null;
        double bd = Double.MAX_VALUE;
        double max = hype.getDouble("documents.show-distance", 4);
        for (Player q : p.getWorld().getPlayers()) {
            if (q == p || !p.canSee(q)) continue;
            double d = q.getLocation().distance(p.getLocation());
            if (d <= max && d < bd) {
                bd = d;
                best = q;
            }
        }
        if (best == null) {
            p.sendMessage("§cNu e nimeni langa tine (max " + (int) max + " blocuri).");
            return;
        }
        show(p, best, type);
    }

    // ------------------------------------------------------------------ Primarie

    private double fee(String k, double def) {
        return Math.max(0, prim.getDouble("fees." + k, def));
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

    private void openPrimarie(Player p) {
        Doc d = get(p.getUniqueId(), "buletin");
        if (plugin.channel().hasMod(p)) {
            JsonObject o = new JsonObject();
            if (d != null) o.add("buletin", toJson(d));
            JsonObject pr = new JsonObject();
            pr.addProperty("buletin", fee("buletin", 150));
            pr.addProperty("duplicat", fee("duplicat", 100));
            pr.addProperty("reinnoire", fee("reinnoire", 100));
            o.add("prices", pr);
            o.addProperty("money", plugin.money().balance(p.getUniqueId()));
            send(p, "primarie", o);
            return;
        }
        p.sendMessage("§9[Primaria Flow City]");
        if (d == null) {
            p.sendMessage("§fNu ai buletin. Taxa: §e" + (int) fee("buletin", 150) + " lei");
            p.sendMessage("§7/primarie cere <nume> <prenume> <dd.MM.yyyy> <M|F> <adresa...>");
        } else {
            p.sendMessage("§fAi buletin §e" + d.serie + " " + d.numar + " §7(expira " + DocLogic.formatDate(d.expires) + ")");
            p.sendMessage("§7/primarie duplicat (" + (int) fee("duplicat", 100) + " lei)  |  /primarie reinnoire (" + (int) fee("reinnoire", 100) + " lei, doar daca a expirat)");
        }
    }

    private void apply(Player p, String name, String surname, String birth, String address, String sex, String birthPlace) {
        final UUID u = p.getUniqueId();
        if (get(u, "buletin") != null) {
            result(p, false, "Ai deja buletin. Foloseste reinnoire sau duplicat.");
            return;
        }
        String n = DocLogic.cleanName(name);
        String s = DocLogic.cleanName(surname);
        if (n == null || s == null) {
            result(p, false, "Nume sau prenume invalid (3-40 litere).");
            return;
        }
        long b = DocLogic.parseDate(birth);
        long now = System.currentTimeMillis();
        if (b < 0 || b > now) {
            result(p, false, "Data nasterii invalida (zz.ll.aaaa).");
            return;
        }
        int age = DocLogic.ageYears(b, now);
        if (age < prim.getInt("min-age", 14) || age > 110) {
            result(p, false, "Varsta nu permite eliberarea unui buletin.");
            return;
        }
        if (!DocLogic.validSex(sex)) {
            result(p, false, "Alege sexul: M sau F.");
            return;
        }
        String a = address == null ? "" : address.replaceAll("[\\p{Cntrl}§&]", "").trim().replaceAll("\\s+", " ");
        int maxA = hype.getInt("documents.address-max", 60);
        if (a.length() < 3 || a.length() > maxA) {
            result(p, false, "Adresa trebuie sa aiba 3-" + maxA + " caractere.");
            return;
        }
        final Map<String, String> f = new LinkedHashMap<>();
        f.put("name", n);
        f.put("surname", s);
        f.put("birth", DocLogic.formatDate(b));
        f.put("address", a);
        f.put("sex", sex.toUpperCase(java.util.Locale.ROOT));
        String bpl = birthPlace == null ? "" : birthPlace.replaceAll("[\\p{Cntrl}§&]", "").trim();
        f.put("birthPlace", bpl.length() > 30 ? bpl.substring(0, 30) : bpl);
        pay(p, "Buletin", fee("buletin", 150), () -> {
            if (get(u, "buletin") != null) return false;
            Doc d = issue(u, "buletin", f, "Primaria Flow City");
            if (d == null) return false;
            result(p, true, "Buletin eliberat: " + d.serie + " " + d.numar);
            openPrimarie(p);
            return true;
        });
    }

    private void reissue(Player p, boolean renew) {
        final UUID u = p.getUniqueId();
        final Doc old = get(u, "buletin");
        if (old == null) {
            result(p, false, "Nu ai buletin. Cere unul nou.");
            return;
        }
        boolean expired = old.expires <= System.currentTimeMillis();
        if (renew && !expired) {
            result(p, false, "Buletinul nu a expirat inca. Poti cere un duplicat.");
            return;
        }
        double price = renew ? fee("reinnoire", 100) : fee("duplicat", 100);
        pay(p, renew ? "Reinnoire buletin" : "Duplicat buletin", price, () -> {
            Doc cur = get(u, "buletin");
            if (cur == null) return false;
            Map<String, String> f = new LinkedHashMap<>();
            f.put("name", cur.name);
            f.put("surname", cur.surname);
            f.put("birth", cur.birth);
            f.put("address", cur.address);
            f.put("sex", cur.sex);
            f.put("birthPlace", cur.birthPlace);
            long keep = cur.expires;
            Doc d = issue(u, "buletin", f, "Primaria Flow City");
            if (d == null) return false;
            if (!renew && keep > System.currentTimeMillis()) {
                d.expires = keep;
                write(d);
            }
            result(p, true, (renew ? "Buletin reinnoit: " : "Duplicat eliberat: ") + d.serie + " " + d.numar);
            openPrimarie(p);
            return true;
        });
    }

    private void pay(Player p, String label, double amount, java.util.function.Supplier<Boolean> fulfil) {
        PayModule pm = PayModule.instance;
        if (pm == null) {
            result(p, false, "Plata nu e disponibila.");
            return;
        }
        pm.request(p, label, amount, fulfil, msg -> result(p, false, msg));
    }

    // ------------------------------------------------------------------ comenzi

    private void registerCommands() {
        Commands.register(plugin, "primarie", null, (s, c, l, a) -> {
            if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {
                if (s instanceof Player && !s.hasPermission("flowcity.hype.doc")) {
                    s.sendMessage("§cNu ai permisiunea.");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) openPrimarie(t);
                return true;
            }
            if (!(s instanceof Player)) {
                s.sendMessage("Folosire: primarie open <jucator>");
                return true;
            }
            Player p = (Player) s;
            if (a.length >= 1 && "reload".equalsIgnoreCase(a[0]) && p.hasPermission("flowcity.hype.admin")) {
                hype = ModConfig.load(plugin, "hype");
                prim = ModConfig.load(plugin, "primarie");
                p.sendMessage("§aPrimaria reincarcata.");
                return true;
            }
            if (a.length >= 1 && "duplicat".equalsIgnoreCase(a[0])) {
                reissue(p, false);
                return true;
            }
            if (a.length >= 1 && ("reinnoire".equalsIgnoreCase(a[0]) || "reinoire".equalsIgnoreCase(a[0]))) {
                reissue(p, true);
                return true;
            }
            if (a.length >= 6 && "cere".equalsIgnoreCase(a[0])) {
                StringBuilder ad = new StringBuilder();
                for (int i = 5; i < a.length; i++) ad.append(i > 5 ? " " : "").append(a[i]);
                apply(p, a[1], a[2], a[3], ad.toString(), a[4], "");
                return true;
            }
            openPrimarie(p);
            return true;
        }, null);

        for (final String t : TYPES) {
            Commands.register(plugin, t, null, (s, c, l, a) -> {
                if (!(s instanceof Player)) return true;
                Player p = (Player) s;
                if (get(p.getUniqueId(), t) == null) {
                    p.sendMessage("§cNu ai " + typeName(t).toLowerCase() + "." + ("buletin".equals(t) ? " Il faci la Primarie (/primarie)." : ""));
                    return true;
                }
                show(p, p, t);
                return true;
            }, null);
        }

        Commands.register(plugin, "arata", null, (s, c, l, a) -> {
            if (!(s instanceof Player)) return true;
            Player p = (Player) s;
            if (a.length < 1 || type(a[0]) == null) {
                p.sendMessage("§7Folosire: /arata <buletin|permis|portarma> [jucator]");
                return true;
            }
            if (get(p.getUniqueId(), a[0]) == null) {
                p.sendMessage("§cNu ai acest document.");
                return true;
            }
            if (a.length >= 2) {
                Player t = Bukkit.getPlayerExact(a[1]);
                double max = hype.getDouble("documents.show-distance", 4);
                if (t == null || t == p || !t.getWorld().equals(p.getWorld()) || t.getLocation().distance(p.getLocation()) > max) {
                    p.sendMessage("§cJucatorul nu e langa tine.");
                    return true;
                }
                show(p, t, a[0]);
            } else {
                showNearest(p, a[0]);
            }
            return true;
        }, null);

        Commands.register(plugin, "doc", null, (s, c, l, a) -> {
            if (!s.hasPermission("flowcity.hype.doc")) {
                s.sendMessage("§cNu ai permisiunea.");
                return true;
            }
            if (a.length >= 3 && "remove".equalsIgnoreCase(a[0])) {
                Player t = Bukkit.getPlayerExact(a[1]);
                String ty = type(a[2]);
                if (t == null || ty == null) {
                    s.sendMessage("§cJucator offline sau tip invalid.");
                    return true;
                }
                Doc old = get(t.getUniqueId(), ty);
                if (old == null) {
                    s.sendMessage("§cNu are acest document.");
                    return true;
                }
                serials.remove(old.serie + " " + old.numar);
                data.set("docs." + t.getUniqueId() + "." + ty, null);
                save();
                s.sendMessage("§aDocument scos.");
                return true;
            }
            if (a.length >= 3 && "categorii".equalsIgnoreCase(a[0])) {
                Player t = Bukkit.getPlayerExact(a[1]);
                String cats = DocLogic.normalizeCategories(a[2]);
                Doc d = t == null ? null : get(t.getUniqueId(), "permis");
                if (d == null || cats == null) {
                    s.sendMessage("§cJucator fara permis sau categorii invalide.");
                    return true;
                }
                d.categories = cats;
                write(d);
                s.sendMessage("§aCategorii: " + cats);
                return true;
            }
            if (a.length >= 7 && "give".equalsIgnoreCase(a[0])) {
                Player t = Bukkit.getPlayerExact(a[1]);
                String ty = type(a[2]);
                String n = DocLogic.cleanName(a[3]);
                String sn = DocLogic.cleanName(a[4]);
                long b = DocLogic.parseDate(a[5]);
                if (t == null || ty == null || n == null || sn == null || b < 0) {
                    s.sendMessage("§cFolosire: /doc give <jucator> <buletin|permis|portarma> <nume> <prenume> <dd.MM.yyyy> <adresa...>");
                    return true;
                }
                StringBuilder ad = new StringBuilder();
                for (int i = 6; i < a.length; i++) ad.append(i > 6 ? " " : "").append(a[i]);
                Map<String, String> f = new LinkedHashMap<>();
                f.put("name", n);
                f.put("surname", sn);
                f.put("birth", DocLogic.formatDate(b));
                f.put("address", ad.toString().replaceAll("[\\p{Cntrl}§&]", "").trim());
                f.put("sex", "-");
                if ("permis".equals(ty)) f.put("categories", "B");
                Doc d = issue(t.getUniqueId(), ty, f, s.getName());
                if (d == null) {
                    s.sendMessage("§cNu pot genera seria.");
                    return true;
                }
                s.sendMessage("§aEmis " + typeName(ty) + " " + d.serie + " " + d.numar + " pentru " + t.getName() + (("permis".equals(ty)) ? " (categorii B; schimba cu /doc categorii)" : ""));
                t.sendMessage("§aAi primit: " + typeName(ty) + " (" + d.serie + " " + d.numar + ").");
                return true;
            }
            s.sendMessage("§7/doc give <jucator> <tip> <nume> <prenume> <dd.MM.yyyy> <adresa...>  |  /doc remove <jucator> <tip>  |  /doc categorii <jucator> <B,C>");
            return true;
        }, null);
    }
}
