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
        dataFile = new File(plugin.getDataFolder(), "clothes-data.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        on("refresh", (p, j) -> open(p));
        on("buy", (p, j) -> buy(p, str(j, "id", "")));
        on("equip", (p, j) -> equip(p, str(j, "id", "")));
        on("fetch", (p, j) -> fetch(p, str(j, "id", "")));
        Commands.register(plugin, "haine", null, (s, c, l, a) -> {
            if (a.length >= 1 && "reload".equalsIgnoreCase(a[0]) && s.hasPermission("flowcity.clothes.admin")) {
                cfg = ModConfig.load(plugin, "clothes");
                load();
                s.sendMessage("§aGarderoba reincarcata: " + outfits.keySet());
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
                if (t != null && outfits.containsKey(a[2])) {
                    own(t.getUniqueId(), a[2]);
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

    private void buy(Player p, String id) {
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

    private void equip(Player p, String id) {
        UUID u = p.getUniqueId();
        if (!id.isEmpty()) {
            Outfit f = outfits.get(id);
            if (f == null || !usable(p, f)) {
                result(p, false, "Nu poti purta aceasta tinuta.");
                return;
            }
        }
        data.set("worn." + u, id.isEmpty() ? null : id);
        save();
        announce(u, id);
        result(p, true, id.isEmpty() ? "Tinuta scoasa." : "Tinuta echipata.");
        open(p);
    }

    private void announce(UUID u, String id) {
        JsonObject o = new JsonObject();
        JsonObject m = new JsonObject();
        Outfit f = outfits.get(id);
        m.addProperty(u.toString(), f == null ? "" : id);
        o.add("look", m);
        o.addProperty("slim", f != null && f.slim);
        broadcast("look", o);
    }

    private void fetch(Player p, String id) {
        Outfit f = outfits.get(id);
        if (f == null) return;
        File file = new File(new File(plugin.getDataFolder(), "skins"), f.file);
        if (!file.isFile() || file.length() > 64 * 1024) return;
        try {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("slim", f.slim);
            o.addProperty("png", Base64.getEncoder().encodeToString(Files.readAllBytes(file.toPath())));
            send(p, "tex", o);
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot citi skin-ul " + f.file + ": " + e);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        final Player p = e.getPlayer();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!p.isOnline()) return;
            JsonObject all = new JsonObject();
            JsonObject slims = new JsonObject();
            for (Player q : Bukkit.getOnlinePlayers()) {
                String w = worn(q.getUniqueId());
                if (!w.isEmpty()) {
                    all.addProperty(q.getUniqueId().toString(), w);
                    slims.addProperty(q.getUniqueId().toString(), outfits.get(w).slim);
                }
            }
            JsonObject o = new JsonObject();
            o.add("look", all);
            send(p, "look", o);
            String mine = worn(p.getUniqueId());
            if (!mine.isEmpty()) announce(p.getUniqueId(), mine);
        }, 60L);
    }
}
