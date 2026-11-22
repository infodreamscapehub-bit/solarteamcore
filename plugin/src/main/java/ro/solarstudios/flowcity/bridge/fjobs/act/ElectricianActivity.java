package ro.solarstudios.flowcity.bridge.fjobs.act;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.EntityEffect;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import ro.solarstudios.flowcity.bridge.fjobs.FJobDef;
import ro.solarstudios.flowcity.bridge.fjobs.act.elec.ElecCfg;
import ro.solarstudios.flowcity.bridge.fjobs.act.elec.ElecGame;
import ro.solarstudios.flowcity.bridge.fjobs.act.elec.ElecStore;

/**
 * ELECTRICIAN (job complet): panou cu tura/interventii/echipament/statistici, oferte de interventii generate din config (8 tipuri,
 * dificultate 1-5 dupa rang), deplasare la locatie, panou electric (GUI) cu etape, patru mini-jocuri validate pe server (circuit,
 * siguranta + multimetru, secventa progresiva, calibrare), echipament real (item-uri cu eticheta, consumabile), niveluri (Ucenic ->
 * Inginer Electric), istoric si statistici persistente. Plata, XP, etapele si timpii se decid DOAR aici; clientul trimite intentii.
 */
public final class ElectricianActivity extends BaseActivity<ElectricianActivity.St> {
    private static final String TAG = "fj_elec";

    static final class Offer {
        String id;
        String type;
        FJobDef.Point loc;
        int diff;
        double reward;
        long xp;
        int sec;
        long expires;
    }

    static final class Run {
        Offer o;
        List<String> stages = new ArrayList<>();
        List<Double> qs = new ArrayList<>();
        int idx;
        boolean arrived;
        long acceptAt;
        long arriveAt;
        int retries;
        int mistakes;
        ElecGame game;
    }

    static final class St {
        final List<Offer> offers = new ArrayList<>();
        final Map<String, Long> lock = new HashMap<>();
        Run run;
        long nextGen;
        long noAcceptUntil;
        long rerollAt;
        int doneShift;
        int failShift;
        double earnedShift;
        long seq;
    }

    private final ElecStore store;
    private ElecCfg cc;
    private FJobDef ccJd;
    private int ccVer;
    private int cfgVer;

    public ElectricianActivity(FJobsApi api) {
        super(api);
        this.store = new ElecStore(api.plugin().getDataFolder(), api.plugin().getLogger());
    }

    @Override
    public String kind() {
        return "electrician";
    }

    @Override
    public List<String[]> stationKinds() {
        List<String[]> l = new ArrayList<>();
        l.add(new String[] {"panou", "Panouri electrice defecte"});
        l.add(new String[] {"retea", "Retele electrice intrerupte"});
        l.add(new String[] {"sigurante", "Sigurante arse"});
        l.add(new String[] {"cablu", "Cabluri deteriorate"});
        l.add(new String[] {"generator", "Generatoare"});
        l.add(new String[] {"transformator", "Transformatoare"});
        l.add(new String[] {"instalatie", "Instalatii avariate"});
        l.add(new String[] {"iluminat", "Iluminat stradal"});
        l.add(new String[] {"panel", "Puncte generale (orice tip fara puncte proprii)"});
        return l;
    }

    @Override
    public String missing(FJobDef jd) {
        for (String[] k : stationKinds()) if (!jd.stationList(k[0]).isEmpty()) return null;
        return "adminul trebuie sa seteze puncte de interventie (Administrare -> Electrician -> puncte).";
    }

    @Override
    protected St newState() {
        return new St();
    }

    private ElecCfg cfg(FJobDef jd) {
        if (cc == null || ccJd != jd || ccVer != cfgVer) {
            cc = new ElecCfg(jd);
            ccJd = jd;
            ccVer = cfgVer;
        }
        return cc;
    }

    // ------------------------------------------------------------------ echipament (iteme reale)

    private int count(Player p, String id) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) if (id.equals(Items.tag(api.plugin(), it, TAG))) n += it.getAmount();
        return n;
    }

    private void take(Player p, String id, int n) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize() && n > 0; i++) {
            ItemStack it = inv.getItem(i);
            if (it == null || !id.equals(Items.tag(api.plugin(), it, TAG))) continue;
            int t = Math.min(n, it.getAmount());
            n -= t;
            if (it.getAmount() <= t) inv.setItem(i, null);
            else it.setAmount(it.getAmount() - t);
        }
    }

    private void give(Player p, ElecCfg c, ElecCfg.Equip e, int amount) {
        ItemStack it = Items.make(api.plugin(), Items.mat(e.material, Material.PAPER), "§e⚡ §f" + e.name,
            Items.lore("§7" + e.desc, "§8Echipament electrician", e.consumable ? "§7Material consumabil" : "§7Unealta reutilizabila"), TAG, e.id);
        it.setAmount(Math.max(1, Math.min(64, amount)));
        Items.give(p, it);
    }

    // ------------------------------------------------------------------ ciclu de viata

    @Override
    protected void onStart(Player p, FJobDef jd, St s) {
        ElecCfg c = cfg(jd);
        ElecStore.Data d = store.get(p.getUniqueId());
        if (!d.starter && c.b("starter.enabled", true)) {
            d.starter = true;
            for (ElecCfg.Equip e : c.equip.values()) {
                int n = c.i("starter." + e.id, "trusa".equals(e.id) ? 1 : "siguranta".equals(e.id) || "cablu".equals(e.id) ? 2 : 0);
                if (n > 0) give(p, c, e, n);
            }
            store.save(p.getUniqueId());
            api.toast(p, jd.name, "Ai primit trusa de start. Echipament nou: din panoul jobului.");
        }
        long now = System.currentTimeMillis();
        s.nextGen = now;
        for (int i = 0; i < 3; i++) gen(p, jd, s, now);
        Fx.title(p, "§e⚡ " + jd.name, "§7Deschide panoul jobului pentru interventii");
        Fx.sound(p, "BLOCK_NOTE_BLOCK_PLING", 0.7f, 1.3f);
    }

    @Override
    protected void onEnd(Player p, FJobDef jd, St s) {
        store.save(p.getUniqueId());
    }

    @Override
    protected void onQuit(Player p, St s) {
        if (p != null) store.save(p.getUniqueId());
        else store.saveAll();
    }

    @Override
    public void shutdown() {
        super.shutdown();
        store.saveAll();
    }

    @Override
    protected boolean busy(Player p, St s) {
        return s.run != null;
    }

    /** Numele nivelului profesional apare in HUD in loc de dificultate. */
    @Override
    protected Diff diff(Player p, FJobDef jd) {
        Diff d = new Diff();
        d.id = "rank";
        d.name = cfg(jd).rankFor(api.jobLevel(p, jd.id)).name;
        return d;
    }

    // ------------------------------------------------------------------ oferte

    private List<FJobDef.Point> sites(FJobDef jd, ElecCfg.Type t) {
        List<FJobDef.Point> l = jd.stationList(t.id);
        return l.isEmpty() ? jd.stationList("panel") : l;
    }

    private void gen(Player p, FJobDef jd, St s, long now) {
        ElecCfg c = cfg(jd);
        ElecCfg.Rank rk = c.rankFor(api.jobLevel(p, jd.id));
        List<ElecCfg.Type> ok = new ArrayList<>();
        for (ElecCfg.Type t : c.types) {
            if (t.enabled && t.minRank <= rk.idx && t.minDiff <= rk.maxDiff && !sites(jd, t).isEmpty()) ok.add(t);
        }
        if (ok.isEmpty()) return;
        for (int tries = 0; tries < 6; tries++) {
            ElecCfg.Type t = ok.get(api.rnd().nextInt(ok.size()));
            List<FJobDef.Point> cand = new ArrayList<>();
            for (FJobDef.Point pt : sites(jd, t)) {
                Long lk = s.lock.get(pt.name);
                if (lk != null && lk > now) continue;
                boolean used = s.run != null && s.run.o.loc == pt;
                for (Offer o : s.offers) if (o.loc == pt) used = true;
                if (!used) cand.add(pt);
            }
            if (cand.isEmpty()) continue;
            int hi = Math.min(t.maxDiff, rk.maxDiff);
            int d = t.minDiff + (int) Math.floor(Math.pow(api.rnd().nextDouble(), 1.5) * (hi - t.minDiff + 1));
            d = Math.max(t.minDiff, Math.min(hi, d));
            Offer o = new Offer();
            o.id = Long.toString(++s.seq, 36);
            o.type = t.id;
            o.loc = cand.get(api.rnd().nextInt(cand.size()));
            o.diff = d;
            o.reward = Math.round(t.reward * (1 + 0.35 * (d - 1)) * 100) / 100.0;
            o.xp = Math.round(t.xp * (1 + 0.3 * (d - 1)));
            o.sec = (int) Math.round(t.seconds * (0.85 + 0.18 * d));
            o.expires = now + (long) (c.d("offer-ttl-seconds", 420) * 1000);
            s.offers.add(o);
            return;
        }
    }

    @Override
    protected void tickJob(Player p, FJobDef jd, St s, long now) {
        ElecCfg c = cfg(jd);
        boolean changed = false;
        for (int i = s.offers.size() - 1; i >= 0; i--) {
            Offer o = s.offers.get(i);
            ElecCfg.Type t = c.type(o.type);
            if (o.expires < now || t == null || !t.enabled) {
                s.offers.remove(i);
                changed = true;
            }
        }
        if (s.offers.size() < c.i("max-offers", 5) && now >= s.nextGen) {
            int before = s.offers.size();
            gen(p, jd, s, now);
            s.nextGen = now + (long) (c.d("gen-interval-seconds", 8) * 1000);
            changed |= s.offers.size() != before;
        }
        Run r = s.run;
        if (r != null) {
            long limit = (long) (c.d("run-timeout-seconds", 900) * 1000);
            if (now - r.acceptAt > limit) {
                failRun(p, jd, s, "Timpul pentru interventie a expirat.", false);
                return;
            }
            if (!r.arrived) mark(p, java.util.Collections.singletonList(r.o.loc), "END_ROD", 60);
            else if (r.game == null) mark(p, java.util.Collections.singletonList(r.o.loc), "FIREWORKS_SPARK", 30);
        }
        if (changed) api.refreshPanel(p);
    }

    // ------------------------------------------------------------------ interactiune la locatie

    @Override
    public void onInteract(Player p, FJobDef jd, PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getClickedBlock() == null) return;
        St s = state(p);
        Run r = s.run;
        if (r == null) return;
        if (!nearLoc(e.getClickedBlock().getLocation(), r.o.loc, cfg(jd).d("interact-radius", 4.0))) return;
        e.setCancelled(true);
        if (cooling(p, "open", 1200)) return;
        openBoard(p, jd, s);
    }

    private boolean atSite(Player p, FJobDef jd, Run r) {
        return near(p, r.o.loc, cfg(jd).d("interact-radius", 4.0) + 8);
    }

    private void openBoard(Player p, FJobDef jd, St s) {
        Run r = s.run;
        if (r == null) return;
        ElecCfg c = cfg(jd);
        if (!r.arrived) {
            ElecCfg.Type t = c.type(r.o.type);
            String miss = missingFor(p, c, t, r.o.diff);
            if (!miss.isEmpty()) {
                api.toast(p, "Echipament lipsa", miss);
                Fx.sound(p, "ENTITY_VILLAGER_NO", 0.6f, 1f);
                return;
            }
            if (t != null) for (Map.Entry<String, Integer> n : t.needs.entrySet()) if (!"protectie".equals(n.getKey()) && !isTool(n.getKey())) take(p, n.getKey(), n.getValue());
            r.arrived = true;
            r.arriveAt = System.currentTimeMillis();
            Fx.sound(p, "BLOCK_LEVER_CLICK", 0.8f, 0.9f);
            Fx.sound(p, "BLOCK_BEACON_ACTIVATE", 0.4f, 1.6f);
            api.toast(p, jd.name, "Ai ajuns la " + r.o.loc.name + ". Panoul electric e deschis.");
            api.refreshPanel(p);
        }
        sendBoard(p, jd, s);
    }

    private static boolean isTool(String id) {
        return "trusa".equals(id) || "multimetru".equals(id) || "cleste".equals(id) || "protectie".equals(id);
    }

    /** Ce lipseste pentru o interventie (gol = totul e in regula). */
    private String missingFor(Player p, ElecCfg c, ElecCfg.Type t, int diff) {
        List<String> miss = new ArrayList<>();
        if (count(p, "trusa") < 1) miss.add(c.equip.get("trusa").name);
        if (t != null) {
            for (Map.Entry<String, Integer> n : t.needs.entrySet()) {
                if (count(p, n.getKey()) < n.getValue()) miss.add(c.equip.get(n.getKey()).name + " x" + n.getValue());
            }
        }
        if (diff >= c.i("protection-from-diff", 4) && count(p, "protectie") < 1 && (t == null || !t.needs.containsKey("protectie"))) {
            miss.add(c.equip.get("protectie").name);
        }
        if (miss.isEmpty()) return "";
        return "Iti lipseste: " + String.join(", ", miss) + ". Cumpara din Echipament.";
    }

    // ------------------------------------------------------------------ actiuni (panou / board / mini-jocuri)

    @Override
    public boolean action(Player p, FJobDef jd, String act, JsonObject j) {
        if (act == null || !act.startsWith("el_")) return false;
        St s = state(p);
        boolean inShift = api.working(p) != null;
        switch (act) {
            case "el_buy":
                return buy(p, jd, j);
            case "el_adm":
                return admin(p, jd, j);
            default:
                break;
        }
        if (!inShift) {
            api.msg(p, false, "Porneste tura ca sa lucrezi.");
            return false;
        }
        switch (act) {
            case "el_accept":
                return accept(p, jd, s, str(j, "id"));
            case "el_reroll":
                return reroll(p, jd, s);
            case "el_abandon":
                return abandon(p, jd, s);
            case "el_open":
                if (s.run != null && atSite(p, jd, s.run)) {
                    openBoard(p, jd, s);
                    return true;
                }
                api.msg(p, false, s.run == null ? "Nu ai o interventie activa." : "Mergi la locatie ca sa deschizi panoul electric.");
                return false;
            case "el_gps":
                if (s.run != null) {
                    api.gps(p, s.run.o.loc);
                    return true;
                }
                return false;
            case "el_stage":
                return stage(p, jd, s);
            case "el_g":
                return gameOp(p, jd, s, j);
            default:
                return false;
        }
    }

    private static String str(JsonObject j, String k) {
        return j != null && j.has(k) && j.get(k).isJsonPrimitive() ? j.get(k).getAsString() : "";
    }

    private static int num(JsonObject j, String k, int def) {
        try {
            return j != null && j.has(k) ? j.get(k).getAsInt() : def;
        } catch (RuntimeException e) {
            return def;
        }
    }

    private boolean accept(Player p, FJobDef jd, St s, String id) {
        long now = System.currentTimeMillis();
        ElecCfg c = cfg(jd);
        if (s.run != null) {
            api.msg(p, false, "Ai deja o interventie activa.");
            return false;
        }
        if (now < s.noAcceptUntil) {
            api.msg(p, false, "Asteapta cateva secunde inainte de o noua interventie.");
            return false;
        }
        Offer o = null;
        for (Offer x : s.offers) if (x.id.equals(id)) o = x;
        if (o == null || o.expires < now) {
            api.msg(p, false, "Interventia nu mai e disponibila.");
            api.refreshPanel(p);
            return false;
        }
        ElecCfg.Type t = c.type(o.type);
        ElecCfg.Rank rk = c.rankFor(api.jobLevel(p, jd.id));
        if (t == null || !t.enabled || t.minRank > rk.idx || o.diff > rk.maxDiff) {
            s.offers.remove(o);
            api.msg(p, false, "Nivelul tau nu permite aceasta interventie.");
            api.refreshPanel(p);
            return false;
        }
        String miss = missingFor(p, c, t, o.diff);
        if (!miss.isEmpty()) {
            api.msg(p, false, miss);
            return false;
        }
        Run r = new Run();
        r.o = o;
        r.stages.addAll(t.stages);
        r.acceptAt = now;
        s.run = r;
        s.offers.remove(o);
        api.gps(p, o.loc);
        api.toast(p, jd.name, t.name + " - " + o.loc.name + ". Mergi la locatie si da click dreapta pe panou.");
        Fx.sound(p, "BLOCK_NOTE_BLOCK_BELL", 0.8f, 1.2f);
        api.refreshPanel(p);
        return true;
    }

    private boolean reroll(Player p, FJobDef jd, St s) {
        long now = System.currentTimeMillis();
        if (s.run != null) {
            api.msg(p, false, "Termina interventia curenta mai intai.");
            return false;
        }
        if (now - s.rerollAt < 20_000) {
            api.msg(p, false, "Lista se poate reimprospata o data la 20 de secunde.");
            return false;
        }
        s.rerollAt = now;
        s.offers.clear();
        s.nextGen = now;
        for (int i = 0; i < 3; i++) gen(p, jd, s, now);
        api.refreshPanel(p);
        return true;
    }

    private boolean abandon(Player p, FJobDef jd, St s) {
        if (s.run == null) return false;
        ElecStore.Data d = store.get(p.getUniqueId());
        d.abandoned++;
        d.streak = 0;
        store.save(p.getUniqueId());
        s.run = null;
        s.noAcceptUntil = System.currentTimeMillis() + 10_000;
        api.send(p, "el_close", new JsonObject());
        api.toast(p, jd.name, "Ai renuntat la interventie.");
        api.refreshPanel(p);
        return true;
    }

    private boolean stage(Player p, FJobDef jd, St s) {
        Run r = s.run;
        if (r == null || !r.arrived) {
            api.msg(p, false, "Deschide panoul electric la locatie.");
            return false;
        }
        if (!atSite(p, jd, r)) {
            api.msg(p, false, "Esti prea departe de panou.");
            return false;
        }
        if (r.idx >= r.stages.size()) return false;
        ElecCfg c = cfg(jd);
        if (r.game == null) {
            String g = r.stages.get(r.idx);
            if ("fuse".equals(g) && count(p, "siguranta") < 1) {
                api.msg(p, false, "Ai nevoie de cel putin o " + c.equip.get("siguranta").name + " (cumpara din Echipament).");
                return false;
            }
            r.game = new ElecGame(g, r.o.diff, count(p, "cleste") > 0, api.rnd(), Long.toString(System.nanoTime(), 36));
            Fx.sound(p, "BLOCK_LEVER_CLICK", 0.7f, 1.2f);
        }
        JsonObject o = r.game.openData();
        o.addProperty("title", ElecCfg.stageName(r.stages.get(r.idx)));
        o.addProperty("stageNo", r.idx + 1);
        o.addProperty("stageCount", r.stages.size());
        o.addProperty("pliers", count(p, "cleste") > 0);
        o.addProperty("multimeter", count(p, "multimetru") > 0);
        o.addProperty("fuses", count(p, "siguranta"));
        o.addProperty("mistakesUsed", r.game.mistakes);
        api.send(p, "el_game", o);
        return true;
    }

    private boolean gameOp(Player p, FJobDef jd, St s, JsonObject j) {
        Run r = s.run;
        if (r == null || r.game == null || !r.arrived) return false;
        if (!r.game.id().equals(str(j, "token"))) return false;
        if (!atSite(p, jd, r)) {
            api.msg(p, false, "Esti prea departe de panou.");
            return false;
        }
        final Player pl = p;
        ElecGame.Reply rep = r.game.op(str(j, "op"), j, new ElecGame.Ctx() {
            @Override
            public boolean hasMultimeter() {
                return count(pl, "multimetru") > 0;
            }

            @Override
            public int fuses() {
                return count(pl, "siguranta");
            }

            @Override
            public void useFuse() {
                take(pl, "siguranta", 1);
            }
        });
        JsonObject o = new JsonObject();
        o.addProperty("token", r.game.id());
        o.addProperty("game", r.game.kind);
        o.addProperty("op", str(j, "op"));
        o.addProperty("ok", rep.ok);
        o.addProperty("text", rep.text);
        o.addProperty("fin", rep.fin);
        o.addProperty("win", rep.win);
        o.addProperty("mistakes", r.game.mistakes);
        o.addProperty("left", Math.max(0, r.game.maxMistakes - r.game.mistakes + 1));
        for (Map.Entry<String, com.google.gson.JsonElement> e : rep.extra.entrySet()) o.add(e.getKey(), e.getValue());
        Fx.sound(p, rep.ok ? "BLOCK_NOTE_BLOCK_BIT" : "BLOCK_NOTE_BLOCK_BASS", 0.5f, rep.ok ? 1.5f : 0.7f);
        if (!rep.fin) {
            if (!rep.ok && !rep.text.isEmpty()) Fx.particle(p, "SMOKE_NORMAL", p.getLocation().add(0, 1.6, 0), 6, 0.2);
            api.send(p, "el_gres", o);
            return true;
        }
        if (rep.win) {
            r.qs.add(r.game.quality);
            r.mistakes += r.game.mistakes;
            r.idx++;
            r.game = null;
            o.addProperty("stageDone", true);
            api.send(p, "el_gres", o);
            Fx.sound(p, "ENTITY_PLAYER_LEVELUP", 0.4f, 1.8f);
            Fx.particle(p, "FIREWORKS_SPARK", p.getLocation().add(0, 1.4, 0), 14, 0.4);
            if (r.idx >= r.stages.size()) complete(p, jd, s);
            else sendBoard(p, jd, s);
            return true;
        }
        // etapa esuata
        r.mistakes += r.game.mistakes;
        r.game = null;
        ElecCfg c = cfg(jd);
        if (r.retries < c.i("stage-retries", 1)) {
            r.retries++;
            o.addProperty("retry", true);
            api.send(p, "el_gres", o);
            shock(p, jd, c, false);
            api.toast(p, jd.name, "Etapa a esuat. Mai ai " + (c.i("stage-retries", 1) - r.retries + 1 > 0 ? "o ultima incercare" : "nicio incercare") + " la aceasta interventie.");
            sendBoard(p, jd, s);
            return true;
        }
        api.send(p, "el_gres", o);
        failRun(p, jd, s, rep.text.isEmpty() ? "Etapa a esuat." : rep.text, true);
        return true;
    }

    // ------------------------------------------------------------------ rezultat

    private void shock(Player p, FJobDef jd, ElecCfg c, boolean big) {
        if (count(p, "protectie") > 0) {
            api.msg(p, true, "Echipamentul de protectie te-a ferit de soc.");
            return;
        }
        double dmg = c.d("shock-damage", 4) * (big ? 1.5 : 1);
        p.setHealth(Math.max(1.0, p.getHealth() - dmg));
        p.playEffect(EntityEffect.HURT);
        Fx.sound(p, "ENTITY_LIGHTNING_BOLT_IMPACT", 0.5f, 1.6f);
        Fx.particle(p, "CRIT_MAGIC", p.getLocation().add(0, 1.2, 0), 25, 0.4);
        api.msg(p, false, "Ai luat un soc electric! Foloseste echipament de protectie.");
    }

    private void failRun(Player p, FJobDef jd, St s, String why, boolean shock) {
        Run r = s.run;
        if (r == null) return;
        long now = System.currentTimeMillis();
        ElecCfg c = cfg(jd);
        ElecCfg.Type t = c.type(r.o.type);
        ElecStore.Entry e = new ElecStore.Entry();
        e.t = now;
        e.type = r.o.type;
        e.name = t == null ? r.o.type : t.name;
        e.loc = r.o.loc.name;
        e.diff = r.o.diff;
        e.ok = false;
        e.sec = (int) ((now - r.acceptAt) / 1000);
        e.mistakes = r.mistakes;
        store.add(p.getUniqueId(), e);
        api.addCounter(p, "electrician.failed", 1);
        s.failShift++;
        s.lock.put(r.o.loc.name, now + (long) (c.d("fail-lock-seconds", 120) * 1000));
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("title", "INTERVENȚIE EȘUATĂ");
        o.addProperty("text", why);
        o.addProperty("name", e.name);
        o.addProperty("loc", e.loc);
        o.addProperty("diff", e.diff);
        o.addProperty("sec", e.sec);
        o.addProperty("mistakes", e.mistakes);
        o.add("stages", stageResults(r, c, false));
        s.run = null;
        s.noAcceptUntil = now + 6000;
        if (shock) shock(p, jd, c, true);
        Fx.sound(p, "ENTITY_VILLAGER_NO", 0.7f, 0.9f);
        api.send(p, "el_result", o);
        api.refreshPanel(p);
    }

    private JsonArray stageResults(Run r, ElecCfg c, boolean win) {
        JsonArray a = new JsonArray();
        for (int i = 0; i < r.stages.size(); i++) {
            JsonObject o = new JsonObject();
            o.addProperty("g", r.stages.get(i));
            o.addProperty("name", ElecCfg.stageName(r.stages.get(i)));
            o.addProperty("ok", i < r.qs.size());
            o.addProperty("q", i < r.qs.size() ? r.qs.get(i) : 0);
            a.add(o);
        }
        return a;
    }

    private void complete(Player p, FJobDef jd, St s) {
        Run r = s.run;
        long now = System.currentTimeMillis();
        ElecCfg c = cfg(jd);
        ElecCfg.Type t = c.type(r.o.type);
        int levelBefore = api.jobLevel(p, jd.id);
        ElecCfg.Rank rkBefore = c.rankFor(levelBefore);
        double sum = 0;
        for (double q : r.qs) sum += q;
        double q = r.qs.isEmpty() ? 0.5 : sum / r.qs.size();
        q -= 0.06 * r.retries;
        int elapsed = (int) ((now - r.arriveAt) / 1000);
        double over = r.o.sec <= 0 ? 0 : (elapsed - r.o.sec) / (double) r.o.sec;
        double timeF = over <= 0 ? 1.0 : Math.max(0.8, 1.0 - 0.2 * Math.min(1.0, over));
        q = Math.max(0.3, Math.min(1.0, q * timeF));
        double raw = r.o.reward * (1 + rkBefore.payBonus) * (0.55 + 0.6 * q);
        long xp = Math.round(r.o.xp * (0.6 + 0.4 * q));
        double pay = api.pay(p, jd, raw, xp, true);
        int levelAfter = api.jobLevel(p, jd.id);
        ElecCfg.Rank rkAfter = c.rankFor(levelAfter);
        ElecStore.Entry e = new ElecStore.Entry();
        e.t = now;
        e.type = r.o.type;
        e.name = t == null ? r.o.type : t.name;
        e.loc = r.o.loc.name;
        e.diff = r.o.diff;
        e.ok = true;
        e.pay = Math.max(0, pay);
        e.xp = xp;
        e.q = q;
        e.sec = (int) ((now - r.acceptAt) / 1000);
        e.mistakes = r.mistakes;
        store.add(p.getUniqueId(), e);
        api.addCounter(p, "electrician.done", 1);
        api.addCounter(p, "electrician.fault." + r.o.type, 1);
        s.doneShift++;
        if (pay > 0) s.earnedShift += pay;
        s.lock.put(r.o.loc.name, now + (long) (c.d("site-cooldown-seconds", 90) * 1000));
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("title", "INTERVENȚIE REUȘITĂ");
        o.addProperty("text", pay < 0 ? "Limita de castig pe minut a fost atinsa: plata nu a fost acordata." : "Curentul a fost restabilit.");
        o.addProperty("name", e.name);
        o.addProperty("loc", e.loc);
        o.addProperty("diff", e.diff);
        o.addProperty("pay", Math.max(0, pay));
        o.addProperty("xp", xp);
        o.addProperty("q", q);
        o.addProperty("stars", q >= 0.85 ? 3 : q >= 0.65 ? 2 : 1);
        o.addProperty("sec", e.sec);
        o.addProperty("est", r.o.sec);
        o.addProperty("mistakes", e.mistakes);
        o.addProperty("streak", store.get(p.getUniqueId()).streak);
        if (rkAfter.idx > rkBefore.idx) o.addProperty("rankUp", rkAfter.name);
        o.add("stages", stageResults(r, c, true));
        s.run = null;
        s.noAcceptUntil = now + 4000;
        Fx.sound(p, "ENTITY_PLAYER_LEVELUP", 0.8f, 1.2f);
        Fx.sound(p, "BLOCK_BEACON_POWER_SELECT", 0.5f, 1.4f);
        Fx.particle(p, "FIREWORKS_SPARK", p.getLocation().add(0, 1.6, 0), 40, 0.6);
        if (rkAfter.idx > rkBefore.idx) {
            api.toast(p, "Rang nou!", rkAfter.name);
            Fx.title(p, "§e⚡ " + rkAfter.name, "§7Ai deblocat interventii mai grele");
        }
        api.send(p, "el_result", o);
        api.refreshPanel(p);
    }

    // ------------------------------------------------------------------ cumparare echipament si administrare

    private boolean buy(Player p, FJobDef jd, JsonObject j) {
        ElecCfg c = cfg(jd);
        ElecCfg.Equip e = c.equip.get(str(j, "id"));
        if (e == null) return false;
        ElecCfg.Rank rk = c.rankFor(api.jobLevel(p, jd.id));
        if (rk.idx < e.minRank) {
            api.msg(p, false, e.name + " se deblocheaza la rangul " + c.ranks.get(e.minRank).name + ".");
            return false;
        }
        int packs = e.consumable ? Math.max(1, Math.min(8, num(j, "qty", 1))) : 1;
        int amount = e.pack * packs;
        if (count(p, e.id) + amount > e.max) {
            api.msg(p, false, "Ai deja suficient din " + e.name + " (maxim " + e.max + ").");
            return false;
        }
        double price = e.price * packs;
        if (price > 0 && !api.plugin().money().withdraw(p.getUniqueId(), price)) {
            api.msg(p, false, "Nu ai destui bani (" + money(price) + ").");
            return false;
        }
        give(p, c, e, amount);
        Fx.sound(p, "ENTITY_EXPERIENCE_ORB_PICKUP", 0.6f, 1.2f);
        api.msg(p, true, "Ai cumparat " + e.name + (amount > 1 ? " x" + amount : "") + " pentru " + money(price) + ".");
        api.refreshPanel(p);
        return true;
    }

    private boolean admin(Player p, FJobDef jd, JsonObject j) {
        if (!api.isAdmin(p)) {
            api.msg(p, false, "Nu ai permisiunea de administrare.");
            return false;
        }
        ElecCfg c = cfg(jd);
        String op = str(j, "op");
        String id = str(j, "id").toLowerCase(Locale.ROOT);
        double v = 0;
        try {
            v = j.has("value") ? j.get("value").getAsDouble() : 0;
        } catch (RuntimeException ex) {
            v = 0;
        }
        if (Double.isNaN(v) || Double.isInfinite(v)) return false;
        switch (op) {
            case "price":
                if (!c.equip.containsKey(id)) return false;
                api.setConf(jd, "equipment." + id + ".price", Math.round(Math.max(0, Math.min(1_000_000, v)) * 100) / 100.0);
                break;
            case "reward":
                if (c.type(id) == null) return false;
                api.setConf(jd, "types." + id + ".reward", Math.round(Math.max(0, Math.min(100000, v)) * 100) / 100.0);
                break;
            case "xp":
                if (c.type(id) == null) return false;
                api.setConf(jd, "types." + id + ".xp", (long) Math.max(0, Math.min(100000, v)));
                break;
            case "toggle": {
                ElecCfg.Type t = c.type(id);
                if (t == null) return false;
                api.setConf(jd, "types." + id + ".enabled", !t.enabled);
                break;
            }
            default:
                return false;
        }
        cfgVer++;
        api.msg(p, true, "Salvat.");
        api.refreshPanel(p);
        return true;
    }

    // ------------------------------------------------------------------ HUD

    @Override
    protected double hud(Player p, FJobDef jd, St s, List<String> lines) {
        Run r = s.run;
        if (r == null) {
            lines.add("Interventii disponibile: " + s.offers.size());
            lines.add("Reusite in tura: " + s.doneShift);
            lines.add("Deschide panoul jobului");
            return -1;
        }
        ElecCfg.Type t = cfg(jd).type(r.o.type);
        lines.add((t == null ? r.o.type : t.name) + " - " + r.o.loc.name);
        if (!r.arrived) {
            double d = dist(p, r.o.loc);
            lines.add(d == Double.MAX_VALUE ? "Mergi la locatie" : "Mergi la locatie (" + Math.round(d) + " m)");
        } else {
            lines.add("Etapa " + Math.min(r.idx + 1, r.stages.size()) + "/" + r.stages.size() + (r.game != null ? " (in curs)" : ""));
        }
        lines.add("Reusite in tura: " + s.doneShift);
        if (!r.arrived) return 1.0 - Math.min(1.0, (System.currentTimeMillis() - r.acceptAt) / (cfg(jd).d("run-timeout-seconds", 900) * 1000.0));
        return r.stages.isEmpty() ? -1 : (double) r.idx / r.stages.size();
    }

    // ------------------------------------------------------------------ panou

    @Override
    protected void fillPanel(Player p, FJobDef jd, St s, Panel pn) {
        pn.status("Electrician");
    }

    private JsonObject equipState(Player p) {
        JsonObject e = new JsonObject();
        e.addProperty("trusa", count(p, "trusa"));
        e.addProperty("multimetru", count(p, "multimetru"));
        e.addProperty("cleste", count(p, "cleste"));
        e.addProperty("cablu", count(p, "cablu"));
        e.addProperty("siguranta", count(p, "siguranta"));
        e.addProperty("protectie", count(p, "protectie"));
        return e;
    }

    private void sendBoard(Player p, FJobDef jd, St s) {
        Run r = s.run;
        if (r == null) return;
        ElecCfg c = cfg(jd);
        ElecCfg.Type t = c.type(r.o.type);
        JsonObject b = new JsonObject();
        b.addProperty("id", r.o.id);
        b.addProperty("type", r.o.type);
        b.addProperty("name", t == null ? r.o.type : t.name);
        b.addProperty("symptom", t == null ? "" : t.symptom);
        b.addProperty("loc", r.o.loc.name);
        b.addProperty("diff", r.o.diff);
        b.addProperty("volt", t == null ? 230 : t.volt);
        b.addProperty("idx", r.idx);
        b.addProperty("busy", r.game != null);
        b.addProperty("percent", r.stages.isEmpty() ? 0 : Math.round(100f * r.idx / r.stages.size()));
        b.addProperty("elapsed", (System.currentTimeMillis() - r.arriveAt) / 1000);
        b.addProperty("est", r.o.sec);
        b.addProperty("mistakes", r.mistakes);
        b.addProperty("retriesLeft", Math.max(0, c.i("stage-retries", 1) - r.retries));
        b.addProperty("seed", Math.abs(r.o.id.hashCode()));
        b.addProperty("reward", r.o.reward);
        JsonArray st = new JsonArray();
        for (int i = 0; i < r.stages.size(); i++) {
            JsonObject o = new JsonObject();
            o.addProperty("g", r.stages.get(i));
            o.addProperty("name", ElecCfg.stageName(r.stages.get(i)));
            o.addProperty("st", i < r.idx ? "done" : i == r.idx ? (r.game != null ? "active" : "next") : "todo");
            if (i < r.qs.size()) o.addProperty("q", r.qs.get(i));
            st.add(o);
        }
        b.add("stages", st);
        b.add("equip", equipState(p));
        api.send(p, "el_board", b);
    }

    @Override
    public JsonObject panel(Player p, FJobDef jd) {
        St s = state(p);
        ElecCfg c = cfg(jd);
        long now = System.currentTimeMillis();
        int lvl = api.jobLevel(p, jd.id);
        ElecCfg.Rank rk = c.rankFor(lvl);
        ElecStore.Data d = store.get(p.getUniqueId());
        JsonObject o = new JsonObject();
        o.addProperty("kind", kind());
        o.addProperty("job", jd.name);
        o.addProperty("id", jd.id);
        o.addProperty("level", lvl);
        o.addProperty("status", s.run != null ? "Interventie activa" : "Disponibil");
        o.add("objectives", new JsonArray());
        o.add("stats", new JsonArray());
        o.add("buttons", new JsonArray());
        o.add("rewards", new JsonArray());
        o.add("tips", new JsonArray());
        o.add("diffs", new JsonArray());
        JsonObject el = new JsonObject();
        el.addProperty("name", p.getName());
        JsonObject info = api.jobInfo(p);
        el.addProperty("xp", info.has("jobXp") ? info.get("jobXp").getAsLong() : 0);
        el.addProperty("xpNeed", info.has("jobXpNeed") ? info.get("jobXpNeed").getAsLong() : 0);
        el.addProperty("cash", info.has("cash") ? info.get("cash").getAsDouble() : 0);
        el.addProperty("shiftSec", info.has("shiftSec") ? info.get("shiftSec").getAsLong() : 0);
        el.addProperty("admin", api.isAdmin(p));
        JsonObject rkj = new JsonObject();
        rkj.addProperty("idx", rk.idx);
        rkj.addProperty("name", rk.name);
        rkj.addProperty("maxDiff", rk.maxDiff);
        rkj.addProperty("bonus", rk.payBonus);
        ElecCfg.Rank next = rk.idx + 1 < c.ranks.size() ? c.ranks.get(rk.idx + 1) : null;
        if (next != null) {
            rkj.addProperty("next", next.name);
            rkj.addProperty("nextLevel", next.minLevel);
        }
        el.add("rank", rkj);
        JsonArray ra = new JsonArray();
        for (ElecCfg.Rank k : c.ranks) {
            JsonObject r = new JsonObject();
            r.addProperty("name", k.name);
            r.addProperty("minLevel", k.minLevel);
            r.addProperty("maxDiff", k.maxDiff);
            ra.add(r);
        }
        el.add("ranks", ra);
        // oferte
        JsonArray oa = new JsonArray();
        for (Offer of : s.offers) {
            ElecCfg.Type t = c.type(of.type);
            if (t == null) continue;
            JsonObject x = new JsonObject();
            x.addProperty("id", of.id);
            x.addProperty("type", of.type);
            x.addProperty("name", t.name);
            x.addProperty("desc", t.symptom);
            x.addProperty("loc", of.loc.name);
            double dd = dist(p, of.loc);
            x.addProperty("dist", dd == Double.MAX_VALUE ? -1 : Math.round(dd));
            x.addProperty("diff", of.diff);
            x.addProperty("reward", Math.round(of.reward * (1 + rk.payBonus) * 100) / 100.0);
            x.addProperty("xp", of.xp);
            x.addProperty("sec", of.sec);
            x.addProperty("left", Math.max(0, (of.expires - now) / 1000));
            x.addProperty("volt", t.volt);
            JsonArray gs = new JsonArray();
            for (String g : t.stages) gs.add(ElecCfg.stageName(g));
            x.add("stages", gs);
            x.addProperty("why", missingFor(p, c, t, of.diff));
            oa.add(x);
        }
        el.add("offers", oa);
        // interventia activa
        Run r = s.run;
        if (r != null) {
            ElecCfg.Type t = c.type(r.o.type);
            JsonObject a = new JsonObject();
            a.addProperty("id", r.o.id);
            a.addProperty("name", t == null ? r.o.type : t.name);
            a.addProperty("loc", r.o.loc.name);
            a.addProperty("diff", r.o.diff);
            a.addProperty("arrived", r.arrived);
            double dd = dist(p, r.o.loc);
            a.addProperty("dist", dd == Double.MAX_VALUE ? -1 : Math.round(dd));
            a.addProperty("idx", r.idx);
            a.addProperty("percent", r.stages.isEmpty() ? 0 : Math.round(100f * r.idx / r.stages.size()));
            a.addProperty("reward", r.o.reward);
            a.addProperty("xp", r.o.xp);
            a.addProperty("est", r.o.sec);
            a.addProperty("elapsed", (now - r.acceptAt) / 1000);
            a.addProperty("near", atSite(p, jd, r));
            JsonArray sg = new JsonArray();
            for (int i = 0; i < r.stages.size(); i++) {
                JsonObject g = new JsonObject();
                g.addProperty("name", ElecCfg.stageName(r.stages.get(i)));
                g.addProperty("st", i < r.idx ? "done" : i == r.idx ? "next" : "todo");
                sg.add(g);
            }
            a.add("stages", sg);
            el.add("run", a);
        }
        // istoric
        JsonArray ha = new JsonArray();
        for (ElecStore.Entry e : d.history) {
            JsonObject h = new JsonObject();
            h.addProperty("t", e.t);
            h.addProperty("name", e.name);
            h.addProperty("loc", e.loc);
            h.addProperty("diff", e.diff);
            h.addProperty("ok", e.ok);
            h.addProperty("pay", e.pay);
            h.addProperty("xp", e.xp);
            h.addProperty("q", e.q);
            h.addProperty("sec", e.sec);
            ha.add(h);
        }
        el.add("history", ha);
        // statistici
        JsonObject sj = new JsonObject();
        sj.addProperty("done", d.done);
        sj.addProperty("failed", d.failed);
        sj.addProperty("abandoned", d.abandoned);
        sj.addProperty("money", d.money);
        sj.addProperty("xp", d.xp);
        sj.addProperty("seconds", d.seconds);
        sj.addProperty("avgQ", d.done == 0 ? 0 : d.qSum / d.done);
        sj.addProperty("bestQ", d.bestQ);
        sj.addProperty("streak", d.streak);
        sj.addProperty("bestStreak", d.bestStreak);
        sj.addProperty("doneShift", s.doneShift);
        sj.addProperty("failShift", s.failShift);
        sj.addProperty("earnedShift", s.earnedShift);
        JsonArray bt = new JsonArray();
        for (ElecCfg.Type t : c.types) {
            JsonObject b = new JsonObject();
            b.addProperty("name", t.name);
            b.addProperty("n", d.byType.getOrDefault(t.id, 0));
            bt.add(b);
        }
        sj.add("byType", bt);
        el.add("stats", sj);
        // echipament
        JsonArray ea = new JsonArray();
        for (ElecCfg.Equip e : c.equip.values()) {
            JsonObject x = new JsonObject();
            x.addProperty("id", e.id);
            x.addProperty("name", e.name);
            x.addProperty("desc", e.desc);
            x.addProperty("price", e.price);
            x.addProperty("pack", e.pack);
            x.addProperty("consumable", e.consumable);
            x.addProperty("have", count(p, e.id));
            x.addProperty("max", e.max);
            x.addProperty("minRank", e.minRank);
            x.addProperty("rankName", c.ranks.get(e.minRank).name);
            x.addProperty("locked", rk.idx < e.minRank);
            ea.add(x);
        }
        el.add("equip", ea);
        // administrare
        if (api.isAdmin(p)) {
            JsonArray ta = new JsonArray();
            for (ElecCfg.Type t : c.types) {
                JsonObject x = new JsonObject();
                x.addProperty("id", t.id);
                x.addProperty("name", t.name);
                x.addProperty("enabled", t.enabled);
                x.addProperty("reward", t.reward);
                x.addProperty("xp", t.xp);
                x.addProperty("points", jd.stationList(t.id).size());
                ta.add(x);
            }
            el.add("adm", ta);
            el.addProperty("admGeneral", jd.stationList("panel").size());
        }
        o.add("el", el);
        return o;
    }
}
