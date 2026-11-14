package ro.solarstudios.flowcity.bridge.clothes;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.module.JsonModule;
import ro.solarstudios.flowcity.bridge.pay.PayModule;

/**
 * Garderoba: tinute (skin-uri PNG 64x64) cumparate sau deblocate prin permisiune (uniforme politie/medic/mecanic), echipate
 * din ecranul /haine. Serverul tine ce poarta fiecare jucator si trimite tuturor clientilor cu modul skin-ul ales; PNG-urile
 * se citesc din plugins/FlowCityBridge/skins/ si se transmit la cerere. Canal "clothes".
 */
public final class ClothesModule extends JsonModule implements Listener {
    private static final class Outfit {
        String id;
        String name;
        String cat;
        double price;
        String perm;
        String file;
        boolean slim;
    }

    private static final class Piece {
        String id;
        String name;
        String slot;
        double price;
        String perm;
        String file;
    }

    private static final java.util.List<String> SLOTS = java.util.Arrays.asList("cap", "fata", "top", "pantaloni", "pantofi", "accesoriu");
    private static final String[] DEFAULT_PIECES = {"sapca_neagra", "ochelari_soare", "caciula_iarna", "esarfa", "tricou_rosu", "pantaloni_jeans", "ghete"};

    private final Map<String, Piece> pieces = new LinkedHashMap<>();
    private final Map<String, Outfit> outfits = new LinkedHashMap<>();
    private YamlConfiguration cfg;
    private YamlConfiguration data;
    private File dataFile;

    @Override
    public String id() {
        return "clothes";
    }

    @Override
    protected void onEnable() {
        cfg = ModConfig.load(plugin, "clothes");
        File skins = new File(plugin.getDataFolder(), "skins");
        skins.mkdirs();
        for (String n : new String[] {"politist", "medic", "mecanic", "mos_craciun", "casual_iarna", "sport"}) {
            File f = new File(skins, n + ".png");
            if (!f.exists() && plugin.getResource("skins/" + n + ".png") != null) plugin.saveResource("skins/" + n + ".png", false);
        }
        File pdir = new File(skins, "pieces");
        pdir.mkdirs();
        for (String n : DEFAULT_PIECES) {
            File f = new File(pdir, n + ".png");
            if (!f.exists() && plugin.getResource("skins/pieces/" + n + ".png") != null) plugin.saveResource("skins/pieces/" + n + ".png", false);
        }
        dataFile = new File(plugin.getDataFolder(), "clothes-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        load();
        loadPieces();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        on("refresh", (p, j) -> open(p));
        on("buy", (p, j) -> buy(p, str(j, "id", "")));
        on("equip", (p, j) -> equip(p, str(j, "id", "")));
        on("fetch", (p, j) -> fetch(p, str(j, "id", "")));
        Commands.register(plugin, "haine", null, (s, c, l, a) -> {
            if (a.length >= 1 && "reload".equalsIgnoreCase(a[0]) && s.hasPermission("flowcity.clothes.admin")) {
                cfg = ModConfig.load(plugin, "clothes");
                load();
                loadPieces();
                s.sendMessage("§aGarderoba reincarcata: " + outfits.keySet() + " + piese " + pieces.keySet());
                return true;
            }
            if (a.length >= 2 && "open".equalsIgnoreCase(a[0])) {
                if (s instanceof Player && !s.hasPermission("flowcity.clothes.admin")) {
                    s.sendMessage("§cNu ai permisiunea.");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null) open(t);
                return true;
            }
            if (a.length >= 3 && "give".equalsIgnoreCase(a[0]) && s.hasPermission("flowcity.clothes.admin")) {
                Player t = Bukkit.getPlayerExact(a[1]);
                if (t != null && (outfits.containsKey(a[2]) || pieces.containsKey(a[2].replace("piece:", "")))) {
                    own(t.getUniqueId(), pieces.containsKey(a[2].replace("piece:", "")) && !outfits.containsKey(a[2]) ? "piece:" + a[2].replace("piece:", "") : a[2]);
                    s.sendMessage("§aTinuta " + a[2] + " data lui " + t.getName() + ".");
                }
                return true;
            }
            if (!(s instanceof Player)) {
                s.sendMessage("Folosire: haine open <jucator> | haine give <jucator> <tinuta> | haine reload");
                return true;
            }
            Player p = (Player) s;
            if (!plugin.channel().hasMod(p)) {
                p.sendMessage("§cGarderoba necesita modul FlowCity pe client.");
                return true;
            }
            if (a.length >= 1 && "scoate".equalsIgnoreCase(a[0])) {
                equip(p, "");
                return true;
            }
            open(p);
            return true;
        }, null, "garderoba", "wardrobe");
    }

    private void load() {
        outfits.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("outfits");
        if (sec == null) return;
        double mul = cfg.getDouble("shop-price-multiplier", 1.0);
        for (String k : sec.getKeys(false)) {
            ConfigurationSection o = sec.getConfigurationSection(k);
            if (o == null) continue;
            Outfit f = new Outfit();
            f.id = k;
            f.name = o.getString("name", k);
            f.cat = o.getString("category", "civil");
            f.price = o.getDouble("price", 0) * mul;
            f.perm = o.getString("perm", null);
            f.file = o.getString("file", k + ".png");
            f.slim = o.getBoolean("slim", false);
            outfits.put(k, f);
        }
    }

    private void loadPieces() {
        pieces.clear();
        ConfigurationSection sec = cfg.getConfigurationSection("pieces");
        if (sec == null) return;
        double mul = cfg.getDouble("shop-price-multiplier", 1.0);
        for (String k : sec.getKeys(false)) {
            ConfigurationSection o = sec.getConfigurationSection(k);
            if (o == null) continue;
            Piece f = new Piece();
            f.id = k;
            f.name = o.getString("name", k);
            f.slot = o.getString("slot", "accesoriu").toLowerCase(java.util.Locale.ROOT);
            if (!SLOTS.contains(f.slot)) f.slot = "accesoriu";
            f.price = o.getDouble("price", 0) * mul;
            f.perm = o.getString("perm", null);
            f.file = o.getString("file", "pieces/" + k + ".png");
            pieces.put(k, f);
        }
    }

    private static boolean isPiece(String id) {
        return id != null && id.startsWith("piece:");
    }

    private boolean usablePiece(Player p, Piece f) {
        if (f.perm != null && !f.perm.isEmpty()) return p.hasPermission(f.perm);
        return f.price <= 0 || owns(p.getUniqueId(), "piece:" + f.id);
    }

    private List<String> wornPieces(UUID u) {
        List<String> out = new ArrayList<>();
        for (String id : data.getStringList("wornp." + u)) if (pieces.containsKey(id)) out.add(id);
        return out;
    }

    private boolean owns(UUID u, String id) {
        return data.getStringList("owned." + u).contains(id);
    }

    private void own(UUID u, String id) {
        List<String> l = new ArrayList<>(data.getStringList("owned." + u));
        if (!l.contains(id)) l.add(id);
        data.set("owned." + u, l);
        save();
    }

    private void save() {
        try {
            data.save(dataFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot salva clothes-data.yml: " + e);
        }
    }

    private String worn(UUID u) {
        String id = data.getString("worn." + u, "");
        return outfits.containsKey(id) ? id : "";
    }

    private boolean usable(Player p, Outfit f) {
        if (f.perm != null && !f.perm.isEmpty() && p.hasPermission(f.perm)) return true;
        if (f.perm != null && !f.perm.isEmpty()) return false;
        return f.price <= 0 || owns(p.getUniqueId(), f.id);
    }

    private void open(Player p) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        String w = worn(p.getUniqueId());
        for (Outfit f : outfits.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", f.id);
            j.addProperty("name", f.name);
            j.addProperty("cat", f.cat);
            j.addProperty("price", (int) Math.round(f.price));
            boolean locked = f.perm != null && !f.perm.isEmpty() && !p.hasPermission(f.perm);
            j.addProperty("state", locked ? "locked" : usable(p, f) ? "owned" : "buy");
            j.addProperty("worn", f.id.equals(w));
            j.addProperty("kind", "outfit");
            arr.add(j);
        }
        List<String> wp = wornPieces(p.getUniqueId());
        for (Piece f : pieces.values()) {
            JsonObject j = new JsonObject();
            j.addProperty("id", "piece:" + f.id);
            j.addProperty("name", f.name);
            j.addProperty("cat", "piese");
            j.addProperty("slot", f.slot);
            j.addProperty("price", (int) Math.round(f.price));
            boolean locked = f.perm != null && !f.perm.isEmpty() && !p.hasPermission(f.perm);
            j.addProperty("state", locked ? "locked" : usablePiece(p, f) ? "owned" : "buy");
            j.addProperty("worn", wp.contains(f.id));
            j.addProperty("kind", "piece");
            arr.add(j);
        }
        o.add("outfits", arr);
        send(p, "open", o);
    }

    private void result(Player p, boolean ok, String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", ok);
        o.addProperty("msg", msg);
        send(p, "result", o);
    }

    private void buyPiece(Player p, String id) {
        Piece f = pieces.get(id.substring(6));
        if (f == null) return;
        if (f.perm != null && !f.perm.isEmpty()) {
            result(p, false, "Piesa se primeste doar de la job.");
            return;
        }
        if (usablePiece(p, f)) {
            result(p, false, "O ai deja.");
            return;
        }
        PayModule pay = PayModule.instance;
        if (pay == null) {
            result(p, false, "Plata indisponibila.");
            return;
        }
        pay.request(p, f.name, f.price, () -> {
            own(p.getUniqueId(), "piece:" + f.id);
            result(p, true, "Ai cumparat " + f.name + ".");
            open(p);
            return true;
        }, msg -> result(p, false, msg));
    }

    private void buy(Player p, String id) {
        if (isPiece(id)) {
            buyPiece(p, id);
            return;
        }
        Outfit f = outfits.get(id);
        if (f == null) return;
        if (f.perm != null && !f.perm.isEmpty()) {
            result(p, false, "Tinuta se primeste doar de la job.");
            return;
        }
        if (usable(p, f)) {
            result(p, false, "O ai deja.");
            return;
        }
        PayModule pay = PayModule.instance;
        if (pay == null) {
            result(p, false, "Plata indisponibila.");
            return;
        }
        pay.request(p, f.name, f.price, () -> {
            own(p.getUniqueId(), f.id);
            result(p, true, "Ai cumparat " + f.name + ".");
            open(p);
            return true;
        }, msg -> result(p, false, msg));
    }

    private void equipPiece(Player p, String id) {
        UUID u = p.getUniqueId();
        Piece f = pieces.get(id.substring(6));
        if (f == null || !usablePiece(p, f)) {
            result(p, false, "Nu poti purta aceasta piesa.");
            return;
        }
        List<String> wp = wornPieces(u);
        boolean was = wp.remove(f.id);
        if (!was) {
            wp.removeIf(x -> pieces.get(x) != null && pieces.get(x).slot.equals(f.slot));
            wp.add(f.id);
            data.set("worn." + u, null);
        }
        data.set("wornp." + u, wp.isEmpty() ? null : wp);
        save();
        announce(u);
        result(p, true, was ? "Piesa scoasa." : "Piesa echipata.");
        open(p);
    }

    private void equip(Player p, String id) {
        UUID u = p.getUniqueId();
        if (isPiece(id)) {
            equipPiece(p, id);
            return;
        }
        if (!id.isEmpty()) {
            Outfit f = outfits.get(id);
            if (f == null || !usable(p, f)) {
                result(p, false, "Nu poti purta aceasta tinuta.");
                return;
            }
        }
        data.set("worn." + u, id.isEmpty() ? null : id);
        data.set("wornp." + u, null);
        save();
        announce(u);
        result(p, true, id.isEmpty() ? "Tinuta scoasa." : "Tinuta echipata.");
        open(p);
    }

    private void announce(UUID u) {
        JsonObject o = new JsonObject();
        JsonObject m = new JsonObject();
        String id = worn(u);
        Outfit f = outfits.get(id);
        m.addProperty(u.toString(), f == null ? "" : id);
        o.add("look", m);
        JsonObject pm = new JsonObject();
        JsonArray pa = new JsonArray();
        for (String x : wornPieces(u)) pa.add(x);
        pm.add(u.toString(), pa);
        o.add("pieces", pm);
        o.addProperty("slim", f != null && f.slim);
        broadcast("look", o);
    }

    private void fetch(Player p, String id) {
        String fileName;
        boolean slim = false;
        String slot = "";
        if (isPiece(id)) {
            Piece pc = pieces.get(id.substring(6));
            if (pc == null) return;
            fileName = pc.file;
            slot = pc.slot;
        } else {
            Outfit f = outfits.get(id);
            if (f == null) return;
            fileName = f.file;
            slim = f.slim;
        }
        File file = new File(new File(plugin.getDataFolder(), "skins"), fileName);
        if (!file.isFile() || file.length() > 64 * 1024) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("slim", slim);
            o.addProperty("slot", slot);
            o.addProperty("png", Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath())));
            send(p, "tex", o);
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot citi skin-ul " + fileName + ": " + e);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            JsonObject all = new JsonObject();
            JsonObject allPieces = new JsonObject();
            for (Player q : Bukkit.getOnlinePlayers()) {
                String w = worn(q.getUniqueId());
                if (!w.isEmpty()) all.addProperty(q.getUniqueId().toString(), w);
                List<String> wp = wornPieces(q.getUniqueId());
                if (!wp.isEmpty()) {
                    JsonArray pa = new JsonArray();
                    for (String x : wp) pa.add(x);
                    allPieces.add(q.getUniqueId().toString(), pa);
                }
            }
            JsonObject cat = new JsonObject();
            JsonArray ca = new JsonArray();
            for (Outfit f : outfits.values()) {
                JsonObject c = new JsonObject();
                c.addProperty("id", f.id);
                c.addProperty("name", f.name);
                ca.add(c);
            }
            cat.add("outfits", ca);
            JsonArray cp = new JsonArray();
            for (Piece f : pieces.values()) {
                JsonObject c = new JsonObject();
                c.addProperty("id", f.id);
                c.addProperty("name", f.name);
                cp.add(c);
            }
            cat.add("pieces", cp);
            send(p, "catalog", cat);
            JsonObject o = new JsonObject();
            o.add("look", all);
            o.add("pieces", allPieces);
            send(p, "look", o);
            String mine = worn(p.getUniqueId());
            if (!mine.isEmpty() || !wornPieces(p.getUniqueId()).isEmpty()) announce(p.getUniqueId());
        }, 60L);
    }
}
