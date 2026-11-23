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
    private SimStore sims;
    private final Map<UUID, Integer> lastSignal = new HashMap<>();
    private final Map<UUID, Long> lastSimOp = new HashMap<>();
    private final Map<UUID, Long> lastStoreOp = new HashMap<>();
    private AppStore store;
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

        sims = new SimStore(plugin);
        store = new AppStore(plugin);
        on("store.req", (p, j) -> sendStore(p));
        on("store.install", this::storeInstall);
        on("store.remove", this::storeRemove);
        on("store.order", (p, j) -> {
            if (!storeRate(p) || !j.has("apps") || !j.get("apps").isJsonArray()) return;
            AppStore.Data d = store.cached(p.getUniqueId());
            if (d != null && !store.reorder(p.getUniqueId(), d, j.getAsJsonArray("apps"))) sendStore(p);
        });
        on("notes.req", (p, j) -> store.load(p.getUniqueId(), d -> sendNotes(p, d)));
        on("notes.save", (p, j) -> {
            if (!storeRate(p) || !j.has("notes") || !j.get("notes").isJsonArray()) return;
            store.load(p.getUniqueId(), d -> {
                store.saveNotes(p.getUniqueId(), d, j.getAsJsonArray("notes"));
                sendNotes(p, d);
            });
        });
        on("sim.info", (p, j) -> sendSim(p));
        on("sim.insert", this::simInsert);
        on("sim.eject", (p, j) -> simEject(p));
        on("sim.buy", this::simBuy);
        on("prefs.set", (p, j) -> sims.savePrefs(p.getUniqueId(), Math.max(0, Math.min(15, (int) num(j, "wallpaper", 0))), bool(j, "light", false)));
        Bukkit.getScheduler().runTaskTimer(plugin, this::tickSignal, 100L, 100L);
        on("contacts", (p, j) -> sendContacts(p));
        on("contact_add", this::contactAdd);
        on("contact_del", this::contactDel);
        on("threads", (p, j) -> sendThreads(p));
        on("thread", (p, j) -> sendThread(p, uuid(j, "uuid")));
        on("send", (p, j) -> {
            if (needLine(p)) sendMessage(p, j);
        });
        on("call", (p, j) -> dial(p, uuid(j, "uuid"), str(j, "q", "").trim()));
        on("call_answer", (p, j) -> {
            if (needLine(p)) answer(p);
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
        Commands.register(plugin, "apel", "flowcity.phone", (s, c, l, a) -> {
            Player p = callerOf(s);
            if (p == null) return true;
            if (a.length == 0) {
                p.sendMessage("\u00a77Folosire: /apel <jucator|ID|07xxxxxxxx|112>");
                return true;
            }
            dial(p, null, String.join(" ", a).trim());
            return true;
        }, null, "suna");
        Commands.register(plugin, "raspunde", "flowcity.phone", (s, c, l, a) -> {
            Player p = callerOf(s);
            if (p == null) return true;
            if (!calls.containsKey(p.getUniqueId())) p.sendMessage("\u00a7cNu ai niciun apel.");
            else if (needLine(p)) answer(p);
            return true;
        }, null);
        Commands.register(plugin, "inchide", "flowcity.phone", (s, c, l, a) -> {
            Player p = callerOf(s);
            if (p == null) return true;
            if (!calls.containsKey(p.getUniqueId())) p.sendMessage("\u00a7cNu ai niciun apel.");
            else end(p, false);
            return true;
        }, null);
        Commands.register(plugin, "respinge", "flowcity.phone", (s, c, l, a) -> {
            Player p = callerOf(s);
            if (p == null) return true;
            if (!calls.containsKey(p.getUniqueId())) p.sendMessage("\u00a7cNu ai niciun apel.");
            else end(p, true);
            return true;
        }, null);
    }

    private Player callerOf(org.bukkit.command.CommandSender s) {
        if (!(s instanceof Player)) return null;
        Player p = (Player) s;
        if (!plugin.channel().hasMod(p)) {
            p.sendMessage("\u00a7cApelurile necesita modul FlowCity pe client.");
            return null;
        }
        return p;
    }

    private long ringMs() {
        return Math.max(5L, plugin.getConfig().getLong("phone.call.ring-timeout-seconds", 30L)) * 1000L;
    }

    /** Apel dupa UUID, ID, nume, numar de telefon (cartela SIM) sau 112 (urgente). */
    private void dial(Player p, UUID t, String q) {
        if (!needPhone(p)) return;
        String clean = q.replaceAll("[\\s.\\-]", "");
        boolean numeric = clean.matches("\\d+");
        if (t == null && numeric && clean.equals("112")) {
            if (plugin.getConfig().getBoolean("phone.sim.emergency-without-sim", true) || sims.activeOf(p.getUniqueId()) != null) {
                emergency(p);
            } else {
                needLine(p);
            }
            return;
        }
        if (!needLine(p)) return;
        if (t == null) {
            if (numeric && NumberLogic.validDial(clean)) {
                t = sims.holderOfNumber(clean);
            } else if (!q.isEmpty() && q.length() <= 20) {
                t = plugin.ids().resolveAny(q);
            }
            if (t == null) {
                plugin.channel().toast(p, "Numarul format nu exista.", true);
                return;
            }
        }
        if (sims.activeOf(t) == null) {
            plugin.channel().toast(p, "Numarul nu este disponibil (fara SIM activ).", true);
            return;
        }
        Player tp = Bukkit.getPlayer(t);
        if (tp != null && !hasPhone(tp)) {
            plugin.channel().toast(p, "Numarul nu este disponibil (nu are telefon).", true);
            return;
        }
        startCall(p, t);
    }

    private final Map<UUID, Long> lastEmergency = new HashMap<>();

    /** 112: anunta jucatorii cu permisiunile de urgenta (politie / EMS) cu locatia apelantului. */
    private void emergency(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastEmergency.get(p.getUniqueId());
        if (last != null && now - last < 30_000L) {
            plugin.channel().toast(p, "Ai sunat deja la 112. Asteapta ajutorul.", true);
            return;
        }
        lastEmergency.put(p.getUniqueId(), now);
        List<String> perms = plugin.getConfig().getStringList("phone.call.emergency-permissions");
        if (perms.isEmpty()) {
            perms = new ArrayList<>();
            perms.add("flowcity.politie.officer");
            perms.add("flowcity.ems.officer");
        }
        org.bukkit.Location l = p.getLocation();
        String where = (l.getWorld() == null ? "?" : l.getWorld().getName()) + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
        String msg = "\u00a7c\u00a7l[112] \u00a7f" + p.getName() + " (#" + plugin.ids().idOf(p.getUniqueId()) + ") cere ajutor la \u00a7e" + where;
        int n = 0;
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (o.getUniqueId().equals(p.getUniqueId())) continue;
            for (String perm : perms) {
                if (o.hasPermission(perm)) {
                    o.sendMessage(msg);
                    if (plugin.notifications() != null) {
                        plugin.notifications().send(o, ro.solarstudios.flowcity.bridge.notify.NotifyModule.Type.INFO, "Urgenta 112", p.getName() + " - " + where);
                    }
                    n++;
                    break;
                }
            }
        }
        plugin.channel().toast(p, n > 0 ? "Serviciul de urgenta a fost anuntat." : "Serviciul de urgenta: nimeni nu este de serviciu acum.", n == 0);
        p.sendMessage(n > 0 ? "\u00a7a[112] Ajutorul a fost anuntat. Ramai pe loc." : "\u00a7e[112] Nu este nimeni de serviciu in acest moment.");
    }

    /** Factureaza apelul (daca phone.call.cost-per-minute > 0): intai din banca, apoi cash. */
    private void charge(UUID u, double amount) {
        boolean ok = false;
        if (plugin.bank() != null) ok = plugin.bank().withdraw(u, amount);
        if (!ok && plugin.money() != null) ok = plugin.money().withdraw(u, amount);
        Player x = Bukkit.getPlayer(u);
        if (x != null) plugin.channel().toast(x, ok ? "Apel facturat: $" + Math.round(amount) : "Nu ai avut bani pentru costul apelului.", !ok);
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

    /** Telefon + cartela SIM activa + semnal: conditia pentru SMS si apeluri (verificata mereu pe server). */
    private boolean needLine(Player p) {
        if (!needPhone(p)) return false;
        SimStore.Sim s = sims.installed(p.getUniqueId());
        if (s == null) {
            plugin.channel().toast(p, "Fara SIM: introdu o cartela din Telefon > SIM.", true);
            return false;
        }
        if (!s.active()) {
            plugin.channel().toast(p, "Cartela SIM este blocata.", true);
            return false;
        }
        if (signalOf(p) <= 0) {
            plugin.channel().toast(p, "Fara semnal in aceasta zona.", true);
            return false;
        }
        return true;
    }

    /** 0..4. Zonele fara semnal se definesc in config (phone.signal.dead-zones: "lume,x,z,raza"). */
    private int signalOf(Player p) {
        org.bukkit.Location l = p.getLocation();
        if (l.getWorld() == null) return 4;
        for (String z : plugin.getConfig().getStringList("phone.signal.dead-zones")) {
            String[] a = z.split(",");
            if (a.length < 4) continue;
            try {
                if (!a[0].trim().equalsIgnoreCase(l.getWorld().getName())) continue;
                double dx = l.getX() - Double.parseDouble(a[1].trim());
                double dz = l.getZ() - Double.parseDouble(a[2].trim());
                double r = Double.parseDouble(a[3].trim());
                if (dx * dx + dz * dz <= r * r) return 0;
            } catch (NumberFormatException ignored) {
                // intrare invalida in config
            }
        }
        return 4;
    }

    private void tickSignal() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!plugin.channel().hasMod(p)) continue;
            int sig = signalOf(p);
            Integer last = lastSignal.put(p.getUniqueId(), sig);
            if (last == null || last != sig) {
                JsonObject o = new JsonObject();
                o.addProperty("signal", sig);
                send(p, "signal", o);
                if (sig == 0) {
                    Call c = calls.get(p.getUniqueId());
                    if (c != null) end(p, false);
                }
            }
        }
    }

    // ------------------------------------------------------------------ SIM (introducere / extragere / cumparare)

    private List<String> operators() {
        List<String> l = plugin.getConfig().getStringList("phone.sim.operators");
        if (l.isEmpty()) {
            l = new ArrayList<>();
            l.add("Orange");
            l.add("Vodafone");
            l.add("Digi");
        }
        return l;
    }

    private String numberFormat() {
        return plugin.getConfig().getString("phone.sim.number-format", "07## ### ###");
    }

    private boolean simRate(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastSimOp.get(p.getUniqueId());
        if (last != null && now - last < 700) return false;
        lastSimOp.put(p.getUniqueId(), now);
        return true;
    }

    private void sendSim(Player p) {
        JsonObject o = new JsonObject();
        SimStore.Sim s = sims.installed(p.getUniqueId());
        if (s != null) o.add("installed", simJson(s));
        JsonArray inv = new JsonArray();
        for (SimStore.Sim x : sims.inInventory(p)) {
            JsonObject j = simJson(x);
            String state;
            if (!x.active()) state = "BLOCKED";
            else if (x.installed != null) state = "USED";
            else if (sims.findValidItem(p, x) == null) state = "INVALID";
            else state = "FREE";
            j.addProperty("state", state);
            inv.add(j);
        }
        o.add("inv", inv);
        o.addProperty("blank", plugin.getConfig().getBoolean("phone.sim.accept-blank", true) ? sims.blankCount(p) : 0);
        o.addProperty("price", plugin.getConfig().getDouble("phone.sim.price", 200.0));
        JsonArray ops = new JsonArray();
        for (String op : operators()) ops.add(op);
        o.add("operators", ops);
        o.addProperty("signal", signalOf(p));
        send(p, "sim", o);
    }

    private JsonObject simJson(SimStore.Sim s) {
        JsonObject j = new JsonObject();
        j.addProperty("id", s.id);
        j.addProperty("number", NumberLogic.phoneDisplay(s.number));
        j.addProperty("operator", s.operator);
        j.addProperty("status", s.status);
        j.addProperty("since", s.activated);
        return j;
    }

    private void simInsert(Player p, JsonObject j) {
        if (!ok(p) || !simRate(p)) return;
        String id = str(j, "id", "");
        if ("NEW".equals(id)) {
            simInsertBlank(p, j);
            return;
        }
        SimStore.Sim s = sims.get(id);
        if (s == null) {
            plugin.channel().toast(p, "Cartela nu exista sau nu mai este valida.", true);
            return;
        }
        if (sims.installed(p.getUniqueId()) != null) {
            plugin.channel().toast(p, "Telefonul are deja o cartela. Scoate-o mai intai.", true);
            return;
        }
        if (!s.active()) {
            plugin.channel().toast(p, "Cartela este blocata.", true);
            return;
        }
        if (s.installed != null) {
            plugin.channel().toast(p, "Cartela este deja folosita de alt telefon.", true);
            return;
        }
        if (!plugin.getConfig().getBoolean("phone.sim.allow-transfer", true) && s.owner != null && !s.owner.equals(p.getUniqueId())) {
            plugin.channel().toast(p, "Cartela apartine altui jucator.", true);
            return;
        }
        org.bukkit.inventory.ItemStack it = sims.findValidItem(p, s);
        if (it == null) {
            plugin.channel().toast(p, "Cartela nu este in inventar (sau este o copie invalida).", true);
            return;
        }
        if (it.getAmount() > 1) it.setAmount(it.getAmount() - 1);
        else p.getInventory().removeItem(it);
        sims.install(s, p.getUniqueId());
        plugin.channel().toast(p, "Cartela SIM instalata: " + NumberLogic.phoneDisplay(s.number), false);
        sendSim(p);
        sendState(p);
    }

    /** Cartela "Card SIM" luata din creative/mod (fara ID): serverul o transforma intr-o cartela reala (numar unic) si o instaleaza. */
    private void simInsertBlank(Player p, JsonObject j) {
        if (!plugin.getConfig().getBoolean("phone.sim.accept-blank", true)) {
            plugin.channel().toast(p, "Cartelele noi nu sunt acceptate. Cumpara una din telefon.", true);
            return;
        }
        if (!needPhone(p)) return;
        if (sims.installed(p.getUniqueId()) != null) {
            plugin.channel().toast(p, "Telefonul are deja o cartela. Scoate-o mai intai.", true);
            return;
        }
        if (sims.blankCount(p) <= 0) {
            plugin.channel().toast(p, "Nu ai nicio cartela noua in inventar.", true);
            return;
        }
        List<String> ops = operators();
        String op = str(j, "operator", ops.get(0));
        for (String o : ops) if (o.equalsIgnoreCase(op)) op = o;
        SimStore.Sim s = sims.create(p.getUniqueId(), op, numberFormat());
        if (s == null) {
            plugin.channel().toast(p, "Nu s-a putut genera un numar liber.", true);
            return;
        }
        if (!sims.takeBlank(p)) {
            plugin.channel().toast(p, "Cartela nu mai este in inventar.", true);
            return;
        }
        sims.install(s, p.getUniqueId());
        plugin.channel().toast(p, "Cartela SIM activata: " + NumberLogic.phoneDisplay(s.number), false);
        sendSim(p);
        sendState(p);
    }

    private void simEject(Player p) {
        if (!simRate(p)) return;
        SimStore.Sim s = sims.installed(p.getUniqueId());
        if (s == null) {
            plugin.channel().toast(p, "Nu ai nicio cartela in telefon.", true);
            return;
        }
        if (p.getInventory().firstEmpty() < 0) {
            plugin.channel().toast(p, "Inventar plin: fa loc pentru cartela.", true);
            return;
        }
        Call c = calls.get(p.getUniqueId());
        if (c != null) end(p, false);
        sims.eject(s);
        p.getInventory().addItem(sims.makeItem(s));
        plugin.channel().toast(p, "Cartela SIM a fost scoasa.", false);
        sendSim(p);
        sendState(p);
    }

    private void simBuy(Player p, JsonObject j) {
        if (!ok(p) || !simRate(p)) return;
        if (!needPhone(p)) return;
        List<String> ops = operators();
        String op = str(j, "operator", ops.get(0));
        boolean known = false;
        for (String o : ops) if (o.equalsIgnoreCase(op)) {
            op = o;
            known = true;
            break;
        }
        if (!known) {
            plugin.channel().toast(p, "Operator invalid.", true);
            return;
        }
        int maxOwned = Math.max(1, plugin.getConfig().getInt("phone.sim.max-in-inventory", 3));
        if (sims.inInventory(p).size() >= maxOwned) {
            plugin.channel().toast(p, "Ai deja prea multe cartele in inventar.", true);
            return;
        }
        if (p.getInventory().firstEmpty() < 0) {
            plugin.channel().toast(p, "Inventar plin.", true);
            return;
        }
        double price = Math.max(0, plugin.getConfig().getDouble("phone.sim.price", 200.0));
        if (price > 0 && !plugin.money().has(p.getUniqueId(), price)) {
            plugin.channel().toast(p, "Nu ai destui bani pentru o cartela ($" + Math.round(price) + ").", true);
            return;
        }
        SimStore.Sim s = sims.create(p.getUniqueId(), op, numberFormat());
        if (s == null) {
            plugin.channel().toast(p, "Nu s-a putut genera un numar liber. Incearca din nou.", true);
            return;
        }
        if (price > 0 && !plugin.money().withdraw(p.getUniqueId(), price)) {
            sims.setStatus(s, "VOID");
            plugin.channel().toast(p, "Plata a esuat. Nu s-a retras nimic.", true);
            return;
        }
        p.getInventory().addItem(sims.makeItem(s));
        plugin.channel().toast(p, "Ai cumparat o cartela " + op + ": " + NumberLogic.phoneDisplay(s.number), false);
        sendSim(p);
    }

    // ------------------------------------------------------------------ App Store

    private boolean storeRate(Player p) {
        long now = System.currentTimeMillis();
        Long last = lastStoreOp.get(p.getUniqueId());
        if (last != null && now - last < 350) return false;
        lastStoreOp.put(p.getUniqueId(), now);
        return true;
    }

    private void sendStore(Player p) {
        store.load(p.getUniqueId(), d -> send(p, "store", store.json(p.getUniqueId(), d)));
    }

    private void sendNotes(Player p, AppStore.Data d) {
        JsonObject o = new JsonObject();
        o.add("notes", d.notes);
        send(p, "notes", o);
    }

    private void storeInstall(Player p, JsonObject j) {
        if (!ok(p) || !storeRate(p)) return;
        final String id = str(j, "id", "");
        store.load(p.getUniqueId(), d -> {
            String err = store.install(p.getUniqueId(), d, id);
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("ok", err == null);
            o.addProperty("msg", err == null ? "" : err);
            send(p, "store.res", o);
            send(p, "store", store.json(p.getUniqueId(), d));
        });
    }

    private void storeRemove(Player p, JsonObject j) {
        if (!ok(p) || !storeRate(p)) return;
        final String id = str(j, "id", "");
        store.load(p.getUniqueId(), d -> {
            String err = store.uninstall(p.getUniqueId(), d, id);
            if (err != null) plugin.channel().toast(p, err, true);
            send(p, "store", store.json(p.getUniqueId(), d));
        });
    }

    @Override
    public void onClientReady(Player p) {
        sendStore(p);
        sendState(p);
        sims.loadPrefs(p.getUniqueId(), (w, light) -> {
            JsonObject o = new JsonObject();
            o.addProperty("wallpaper", w);
            o.addProperty("light", light);
            send(p, "prefs", o);
        });
        sendSim(p);
    }

    @Override
    public void onQuit(Player p) {
        Call c = calls.get(p.getUniqueId());
        if (c != null) finish(c, c.active ? "ENDED" : (c.caller.equals(p.getUniqueId()) ? "CANCELLED" : "DECLINED"), p.getUniqueId());
        lastSend.remove(p.getUniqueId());
        lastSignal.remove(p.getUniqueId());
        lastStoreOp.remove(p.getUniqueId());
        if (store != null) store.forget(p.getUniqueId());
        lastSimOp.remove(p.getUniqueId());
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
        SimStore.Sim sim = sims.installed(u);
        o.addProperty("number", sim == null ? "" : NumberLogic.phoneDisplay(sim.number));
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
            if (err == null && o != null && p.isOnline()) {
                SimStore.Sim sim = sims.installed(me);
                o.addProperty("number", sim == null ? "" : NumberLogic.phoneDisplay(sim.number));
                o.addProperty("operator", sim == null ? "" : sim.operator);
                o.addProperty("simStatus", sim == null ? "NONE" : sim.status);
                o.addProperty("signal", signalOf(p));
                send(p, "state", o);
            }
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
        if (sims.activeOf(to) == null) {
            plugin.channel().toast(p, "Destinatarul nu are o cartela SIM activa.", true);
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
            } else if (plugin.notifications() != null) {
                plugin.notifications().send(to, ro.solarstudios.flowcity.bridge.notify.NotifyModule.Type.INFO, "Mesaj de la " + p.getName(), fText, null, "sms");
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
        if (sims.activeOf(to) == null || signalOf(t) <= 0) {
            plugin.channel().toast(p, "Numarul nu este disponibil (fara SIM sau semnal).", true);
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
        boolean hint = plugin.getConfig().getBoolean("phone.call.voice-hint", false);
        for (UUID u : new UUID[] {c.caller, c.callee}) {
            Player x = Bukkit.getPlayer(u);
            if (x == null) continue;
            callState(x, c, "active");
            if (hint) x.sendMessage("\u00a77[Telefon] Folositi chatul vocal pentru a vorbi.");
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
        double perMin = plugin.getConfig().getDouble("phone.call.cost-per-minute", 0.0);
        if (c.active && perMin > 0 && dur > 0) charge(c.caller, Math.ceil(dur / 60.0) * perMin);
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
            long maxMin = plugin.getConfig().getLong("phone.call.max-minutes", 0L);
            if (c.active && maxMin > 0 && now - c.answeredAt > maxMin * 60_000L && calls.containsKey(c.caller)) {
                finish(c, "ANSWERED", c.caller);
                continue;
            }
            if (!c.active && now - c.startedAt > ringMs() && calls.containsKey(c.caller)) {
                finish(c, "MISSED", c.caller);
                plugin.notifications().send(c.callee, ro.solarstudios.flowcity.bridge.notify.NotifyModule.Type.INFO,
                    "Apel pierdut", "De la " + nameOf(c.caller), null, "call");
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
