package ro.solarstudios.flowcity.bridge.phone;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionDefault;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * ePhone: contacte, mesaje (SMS, si offline), apeluri cu sunat/raspuns/respins si istoric.
 * Tabele: core_phone_contacts, core_phone_msgs, core_phone_calls. Permisiune: flowcity.phone (implicit toti).
 */
public final class PhoneModule extends JsonModule {
    private static final int MAX_CONTACTS = 60;
    private static final int MAX_TEXT = 200;
    private static final long RING_MS = 25_000L;

    private static final class Call {
        final long id;
        final UUID caller;
        final UUID callee;
        final long startedAt = System.currentTimeMillis();
        long answeredAt;
        boolean active;

        Call(long id, UUID caller, UUID callee) {
            this.id = id;
            this.caller = caller;
            this.callee = callee;
        }

        UUID other(UUID me) {
            return me.equals(caller) ? callee : caller;
        }
    }

    private final Map<UUID, Call> calls = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastSend = new HashMap<>();
    private long nextCallId = 1;

    @Override
    public String id() {
        return "phone";
    }

    @Override
    protected void onEnable() {
        Commands.permission("flowcity.phone", "Foloseste telefonul", PermissionDefault.TRUE);
        Commands.permission("flowcity.phone.give", "Da telefon (/telefon da)", PermissionDefault.OP);
        Commands.permission("flowcity.phone.bypass", "Foloseste telefonul fara item", PermissionDefault.OP);
        try {
            plugin.db().async(c -> {
                try (Statement st = c.createStatement()) {
                    st.execute("CREATE TABLE IF NOT EXISTS core_phone_contacts (owner TEXT NOT NULL, contact TEXT NOT NULL, PRIMARY KEY(owner, contact))");
                    st.execute("CREATE TABLE IF NOT EXISTS core_phone_msgs (id INTEGER PRIMARY KEY AUTOINCREMENT, sender TEXT NOT NULL, receiver TEXT NOT NULL, text TEXT NOT NULL, ts INTEGER NOT NULL, read INTEGER NOT NULL DEFAULT 0)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_phone_msgs_r ON core_phone_msgs(receiver, read)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_phone_msgs_s ON core_phone_msgs(sender, id)");
                    st.execute("CREATE TABLE IF NOT EXISTS core_phone_calls (id INTEGER PRIMARY KEY AUTOINCREMENT, caller TEXT NOT NULL, callee TEXT NOT NULL, ts INTEGER NOT NULL, duration INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL)");
                    st.execute("CREATE INDEX IF NOT EXISTS idx_phone_calls ON core_phone_calls(caller, callee, id)");
                }
                return null;
            }).get(8, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().warning("ePhone: nu pot crea tabelele: " + e);
        }

        on("contacts", (p, j) -> sendContacts(p));
        on("contact_add", this::contactAdd);
        on("contact_del", this::contactDel);
        on("threads", (p, j) -> sendThreads(p));
        on("thread", (p, j) -> sendThread(p, uuid(j, "uuid")));
        on("send", (p, j) -> {
            if (needPhone(p)) sendMessage(p, j);
        });
        on("call", (p, j) -> {
            if (!needPhone(p)) return;
            UUID t = uuid(j, "uuid");
            if (t == null) {
                String q = str(j, "q", "").trim();
                if (!q.isEmpty() && q.length() <= 20) t = plugin.ids().resolveAny(q);
                if (t == null) {
                    plugin.channel().toast(p, "Nu exista niciun jucator cu acest ID.", true);
                    return;
                }
            }
            Player tp = Bukkit.getPlayer(t);
            if (tp != null && !hasPhone(tp)) {
                plugin.channel().toast(p, "Numarul nu este disponibil (nu are telefon).", true);
                return;
            }
            startCall(p, t);
        });
        on("call_answer", (p, j) -> {
            if (needPhone(p)) answer(p);
        });
        on("call_decline", (p, j) -> end(p, true));
        on("call_end", (p, j) -> end(p, false));
        on("calls", (p, j) -> sendCalls(p));

        Bukkit.getScheduler().runTaskTimer(plugin, this::tickRing, 20L, 20L);
        Commands.register(plugin, "telefon", "flowcity.phone", (s, c, l, a) -> {
            if (!(s instanceof Player)) return true;
            Player p = (Player) s;
            if (a.length >= 1 && a[0].equalsIgnoreCase("da")) {
                if (!s.hasPermission("flowcity.phone.give")) {
                    s.sendMessage("§cNu ai permisiunea.");
                    return true;
                }
                Player target = a.length >= 2 ? Bukkit.getPlayerExact(a[1]) : p;
                if (target == null) {
                    s.sendMessage("§cJucator offline.");
                    return true;
                }
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "give " + target.getName() + " " + PHONE_ITEMS[0]);
                s.sendMessage("§aTelefon dat lui " + target.getName() + ".");
                return true;
            }
            if (!needPhone(p)) return true;
            if (plugin.channel().hasMod(p)) send(p, "open", new JsonObject());
            else p.sendMessage("§cTelefonul necesita modul FlowCity pe client.");
            return true;
        }, null, "phone", "tel");
    }

    private static final String[] PHONE_ITEMS = {"flowcitycore:phone", "flowcitycore:phone1", "flowcitycore:phone2", "flowcitycore:phone3", "flowcitycore:phone4", "flowcitycore:phone5"};

    private static boolean isPhone(org.bukkit.inventory.ItemStack it) {
        if (it == null || it.getType() == org.bukkit.Material.AIR) return false;
        String n = it.getType().name().toLowerCase(java.util.Locale.ROOT);
        String key = "";
        try {
            key = it.getType().getKey().toString().toLowerCase(java.util.Locale.ROOT);
        } catch (RuntimeException | LinkageError ignored) {
            // materialele modate pot lipsi din registru
        }
        return key.startsWith("flowcitycore:phone") || n.contains("flowcitycore") && n.contains("phone");
    }

    /** Are telefon in inventar (sau bypass / cerinta dezactivata din config). */
    private boolean hasPhone(Player p) {
        if (!plugin.getConfig().getBoolean("phone.require-item", true) || p.hasPermission("flowcity.phone.bypass")) return true;
        if (isPhone(p.getInventory().getItemInOffHand())) return true;
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents()) if (isPhone(it)) return true;
        return false;
    }

    private boolean needPhone(Player p) {
        if (hasPhone(p)) return true;
        plugin.channel().toast(p, "Ai nevoie de un telefon in inventar.", true);
        return false;
    }

    @Override
    public void onClientReady(Player p) {
        sendState(p);
    }

    @Override
    public void onQuit(Player p) {
        Call c = calls.get(p.getUniqueId());
        if (c != null) finish(c, c.active ? "ENDED" : (c.caller.equals(p.getUniqueId()) ? "CANCELLED" : "DECLINED"), p.getUniqueId());
        lastSend.remove(p.getUniqueId());
    }

    private static UUID uuid(JsonObject j, String key) {
        try {
            return UUID.fromString(str(j, key, ""));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String nameOf(UUID u) {
        String n = plugin.ids().nameOf(u);
        if (n != null && !n.isEmpty()) return n;
        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(u);
        return op.getName() == null ? "?" : op.getName();
    }

    private JsonObject person(UUID u) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", u.toString());
        o.addProperty("name", nameOf(u));
        o.addProperty("id", plugin.ids().idOf(u));
        o.addProperty("online", Bukkit.getPlayer(u) != null);
        return o;
    }

    private boolean ok(Player p) {
        if (p.hasPermission("flowcity.phone")) return true;
        plugin.channel().toast(p, "Nu ai permisiunea de a folosi telefonul.", true);
        return false;
    }

    // ------------------------------------------------------------------ stare / necitite

    private void sendState(Player p) {
        final UUID me = p.getUniqueId();
        plugin.db().async(c -> {
            int unread = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM core_phone_msgs WHERE receiver=? AND read=0")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) unread = rs.getInt(1);
                }
            }
            int missed = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM core_phone_calls WHERE callee=? AND status='MISSED' AND ts>?")) {
                ps.setString(1, me.toString());
                ps.setLong(2, System.currentTimeMillis() - 86_400_000L);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) missed = rs.getInt(1);
                }
            }
            JsonObject o = new JsonObject();
            o.addProperty("unread", unread);
            o.addProperty("missed", missed);
            return o;
        }).whenComplete((o, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err == null && o != null && p.isOnline()) send(p, "state", o);
        }));
    }

    // ------------------------------------------------------------------ contacte

    private void sendContacts(Player p) {
        final UUID me = p.getUniqueId();
        plugin.db().async(c -> {
            List<UUID> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT contact FROM core_phone_contacts WHERE owner=?")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        try {
                            list.add(UUID.fromString(rs.getString(1)));
                        } catch (IllegalArgumentException ignored) {
                            // inregistrare invalida
                        }
                    }
                }
            }
            return list;
        }).whenComplete((list, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || list == null || !p.isOnline()) return;
            JsonArray arr = new JsonArray();
            for (UUID u : list) arr.add(person(u));
            JsonObject o = new JsonObject();
            o.add("list", arr);
            send(p, "contacts", o);
        }));
    }

    private void contactAdd(Player p, JsonObject j) {
        if (!ok(p)) return;
        String q = str(j, "q", "").trim();
        if (q.isEmpty() || q.length() > 20) return;
        UUID u = plugin.ids().resolveAny(q);
        if (u == null) {
            plugin.channel().toast(p, "Nu exista jucatorul '" + q + "'.", true);
            return;
        }
        if (u.equals(p.getUniqueId())) {
            plugin.channel().toast(p, "Nu te poti adauga pe tine.", true);
            return;
        }
        final UUID me = p.getUniqueId();
        plugin.db().async(c -> {
            int n = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM core_phone_contacts WHERE owner=?")) {
                ps.setString(1, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) n = rs.getInt(1);
                }
            }
            if (n >= MAX_CONTACTS) return false;
            try (PreparedStatement ps = c.prepareStatement("INSERT OR IGNORE INTO core_phone_contacts(owner, contact) VALUES (?,?)")) {
                ps.setString(1, me.toString());
                ps.setString(2, u.toString());
                ps.executeUpdate();
            }
            return true;
        }).whenComplete((done, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || done == null) return;
            if (!done) plugin.channel().toast(p, "Maxim " + MAX_CONTACTS + " contacte.", true);
            else plugin.channel().toast(p, nameOf(u) + " a fost adaugat.", false);
            sendContacts(p);
        }));
    }

    private void contactDel(Player p, JsonObject j) {
        UUID u = uuid(j, "uuid");
        if (u == null) return;
        final UUID me = p.getUniqueId();
        plugin.db().async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM core_phone_contacts WHERE owner=? AND contact=?")) {
                ps.setString(1, me.toString());
                ps.setString(2, u.toString());
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> sendContacts(p)));
    }

    // ------------------------------------------------------------------ mesaje

    private void sendThreads(Player p) {
        final UUID me = p.getUniqueId();
        plugin.db().async(c -> {
            Map<String, JsonObject> byOther = new LinkedHashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT sender, receiver, text, ts, read FROM core_phone_msgs WHERE sender=? OR receiver=? ORDER BY id DESC LIMIT 400")) {
                ps.setString(1, me.toString());
                ps.setString(2, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String s = rs.getString(1);
                        String r = rs.getString(2);
                        boolean mine = s.equals(me.toString());
                        String other = mine ? r : s;
                        JsonObject t = byOther.get(other);
                        if (t == null) {
                            t = new JsonObject();
                            t.addProperty("uuid", other);
                            t.addProperty("last", rs.getString(3));
                            t.addProperty("mine", mine);
                            t.addProperty("ts", rs.getLong(4));
                            t.addProperty("unread", 0);
                            byOther.put(other, t);
                        }
                        if (!mine && rs.getInt(5) == 0) t.addProperty("unread", t.get("unread").getAsInt() + 1);
                    }
                }
            }
            return new ArrayList<>(byOther.values());
        }).whenComplete((list, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || list == null || !p.isOnline()) return;
            JsonArray arr = new JsonArray();
            for (JsonObject t : list) {
                try {
                    UUID u = UUID.fromString(t.get("uuid").getAsString());
                    t.addProperty("name", nameOf(u));
                    t.addProperty("online", Bukkit.getPlayer(u) != null);
                    arr.add(t);
                } catch (IllegalArgumentException ignored) {
                    // invalid
                }
            }
            JsonObject o = new JsonObject();
            o.add("list", arr);
            send(p, "threads", o);
        }));
    }

    private void sendThread(Player p, UUID other) {
        if (other == null) return;
        final UUID me = p.getUniqueId();
        plugin.db().async(c -> {
            JsonArray arr = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT sender, text, ts FROM core_phone_msgs WHERE (sender=? AND receiver=?) OR (sender=? AND receiver=?) ORDER BY id DESC LIMIT 60")) {
                ps.setString(1, me.toString());
                ps.setString(2, other.toString());
                ps.setString(3, other.toString());
                ps.setString(4, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject m = new JsonObject();
                        m.addProperty("mine", rs.getString(1).equals(me.toString()));
                        m.addProperty("text", rs.getString(2));
                        m.addProperty("ts", rs.getLong(3));
                        arr.add(m);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE core_phone_msgs SET read=1 WHERE receiver=? AND sender=? AND read=0")) {
                ps.setString(1, me.toString());
                ps.setString(2, other.toString());
                ps.executeUpdate();
            }
            return arr;
        }).whenComplete((arr, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || arr == null || !p.isOnline()) return;
            // listaul vine descrescator; clientul le vrea cronologic
            JsonArray out = new JsonArray();
            for (int i = arr.size() - 1; i >= 0; i--) out.add(arr.get(i));
            JsonObject o = new JsonObject();
            o.add("with", person(other));
            o.add("list", out);
            send(p, "thread", o);
            sendState(p);
        }));
    }

    private void sendMessage(Player p, JsonObject j) {
        if (!ok(p)) return;
        UUID to = uuid(j, "to");
        String text = str(j, "text", "").replaceAll("[\\p{Cntrl}\\u00a7]", " ").trim();
        if (to == null || text.isEmpty()) return;
        if (text.length() > MAX_TEXT) text = text.substring(0, MAX_TEXT);
        if (to.equals(p.getUniqueId())) {
            plugin.channel().toast(p, "Nu poti trimite mesaje catre tine.", true);
            return;
        }
        if (plugin.ids().nameOf(to) == null && !Bukkit.getOfflinePlayer(to).hasPlayedBefore()) {
            plugin.channel().toast(p, "Destinatar necunoscut.", true);
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSend.get(p.getUniqueId());
        if (last != null && now - last < 800) return;
        lastSend.put(p.getUniqueId(), now);
        final UUID me = p.getUniqueId();
        final String fText = text;
        plugin.db().async(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO core_phone_msgs(sender, receiver, text, ts, read) VALUES (?,?,?,?,0)")) {
                ps.setString(1, me.toString());
                ps.setString(2, to.toString());
                ps.setString(3, fText);
                ps.setLong(4, now);
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((v, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null) {
                plugin.channel().toast(p, "Mesajul nu a putut fi trimis.", true);
                return;
            }
            Player rcv = Bukkit.getPlayer(to);
            if (rcv != null && plugin.channel().hasMod(rcv)) {
                JsonObject o = new JsonObject();
                o.addProperty("uuid", me.toString());
                o.addProperty("name", p.getName());
                o.addProperty("text", fText);
                o.addProperty("ts", now);
                send(rcv, "msg_in", o);
            } else if (rcv != null) {
                rcv.sendMessage("§b[SMS] §f" + p.getName() + "§7: " + fText);
            }
            if (p.isOnline()) sendThread(p, to);
        }));
    }

    // ------------------------------------------------------------------ apeluri

    private void callState(Player p, Call c, String state) {
        JsonObject o = new JsonObject();
        o.addProperty("state", state);
        o.addProperty("id", c.id);
        UUID other = c.other(p.getUniqueId());
        o.add("with", person(other));
        o.addProperty("incoming", c.callee.equals(p.getUniqueId()));
        o.addProperty("startedAt", c.active ? c.answeredAt : c.startedAt);
        send(p, "call_state", o);
    }

    private void startCall(Player p, UUID to) {
        if (!ok(p) || to == null) return;
        if (calls.containsKey(p.getUniqueId())) {
            plugin.channel().toast(p, "Esti deja intr-un apel.", true);
            return;
        }
        Player t = Bukkit.getPlayer(to);
        if (t == null || to.equals(p.getUniqueId())) {
            plugin.channel().toast(p, "Numarul nu este disponibil.", true);
            return;
        }
        if (!plugin.channel().hasMod(t) || calls.containsKey(to)) {
            plugin.channel().toast(p, "Linia este ocupata.", true);
            return;
        }
        Call c = new Call(nextCallId++, p.getUniqueId(), to);
        calls.put(p.getUniqueId(), c);
        calls.put(to, c);
        callState(p, c, "ringing");
        callState(t, c, "ringing");
    }

    private void answer(Player p) {
        Call c = calls.get(p.getUniqueId());
        if (c == null || c.active || !c.callee.equals(p.getUniqueId())) return;
        c.active = true;
        c.answeredAt = System.currentTimeMillis();
        voice("link", c.caller, c.callee);
        for (UUID u : new UUID[] {c.caller, c.callee}) {
            Player x = Bukkit.getPlayer(u);
            if (x != null) callState(x, c, "active");
        }
    }

    private void end(Player p, boolean decline) {
        Call c = calls.get(p.getUniqueId());
        if (c == null) return;
        String status = c.active ? "ANSWERED" : (decline && c.callee.equals(p.getUniqueId()) ? "DECLINED" : "CANCELLED");
        finish(c, status, p.getUniqueId());
    }

    /** Voce privata PlasmoVoice (daca modul e pe server): apelat prin reflexie, ca sa nu depinda pluginul de el. */
    private void voice(String op, UUID a, UUID b) {
        try {
            Class<?> k = Class.forName("ro.solarstudios.flowcity.voice.CallVoice");
            if ("link".equals(op)) k.getMethod("link", UUID.class, UUID.class).invoke(null, a, b);
            else k.getMethod("unlink", UUID.class).invoke(null, a);
        } catch (ClassNotFoundException e) {
            if (!voiceWarned) {
                voiceWarned = true;
                plugin.getLogger().info("ePhone: modul FlowCityCore nu e vizibil pe server - apelurile raman fara voce.");
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            plugin.getLogger().warning("ePhone voce: " + e);
        }
    }

    private boolean voiceWarned;

    private void finish(Call c, String status, UUID by) {
        calls.remove(c.caller);
        calls.remove(c.callee);
        voice("unlink", c.caller, c.callee);
        final long dur = c.active ? (System.currentTimeMillis() - c.answeredAt) / 1000 : 0;
        final String st = status;
        plugin.db().run(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("INSERT INTO core_phone_calls(caller, callee, ts, duration, status) VALUES (?,?,?,?,?)")) {
                ps.setString(1, c.caller.toString());
                ps.setString(2, c.callee.toString());
                ps.setLong(3, c.startedAt);
                ps.setLong(4, dur);
                ps.setString(5, st);
                ps.executeUpdate();
            }
            return null;
        });
        for (UUID u : new UUID[] {c.caller, c.callee}) {
            Player x = Bukkit.getPlayer(u);
            if (x == null) continue;
            JsonObject o = new JsonObject();
            o.addProperty("state", "ended");
            o.addProperty("id", c.id);
            o.addProperty("reason", st);
            o.addProperty("duration", dur);
            o.add("with", person(c.other(u)));
            send(x, "call_state", o);
        }
    }

    private void tickRing() {
        long now = System.currentTimeMillis();
        for (Call c : new ArrayList<>(calls.values())) {
            if (!c.active && now - c.startedAt > RING_MS && calls.containsKey(c.caller)) {
                finish(c, "MISSED", c.caller);
                Player callee = Bukkit.getPlayer(c.callee);
                if (callee != null) plugin.notifications().send(callee, ro.solarstudios.flowcity.bridge.notify.NotifyModule.Type.INFO,
                    "Apel pierdut", "De la " + nameOf(c.caller));
            }
        }
    }

    private void sendCalls(Player p) {
        final UUID me = p.getUniqueId();
        plugin.db().async(c -> {
            JsonArray arr = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT caller, callee, ts, duration, status FROM core_phone_calls WHERE caller=? OR callee=? ORDER BY id DESC LIMIT 25")) {
                ps.setString(1, me.toString());
                ps.setString(2, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        boolean out = rs.getString(1).equals(me.toString());
                        JsonObject o = new JsonObject();
                        o.addProperty("uuid", out ? rs.getString(2) : rs.getString(1));
                        o.addProperty("out", out);
                        o.addProperty("ts", rs.getLong(3));
                        o.addProperty("dur", rs.getLong(4));
                        o.addProperty("status", rs.getString(5));
                        arr.add(o);
                    }
                }
            }
            return arr;
        }).whenComplete((arr, err) -> Bukkit.getScheduler().runTask(plugin, () -> {
            if (err != null || arr == null || !p.isOnline()) return;
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.get(i).getAsJsonObject();
                try {
                    o.addProperty("name", nameOf(UUID.fromString(o.get("uuid").getAsString())));
                } catch (IllegalArgumentException e) {
                    o.addProperty("name", "?");
                }
            }
            JsonObject o = new JsonObject();
            o.add("list", arr);
            send(p, "calls", o);
        }));
    }
}
