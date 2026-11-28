package ro.solarstudios.flowcity.radio;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import ro.solarstudios.flowcity.client.ui.FScreen;
import ro.solarstudios.flowcity.client.ui.FTheme;
import ro.solarstudios.flowcity.client.ui.FUi;
import ro.solarstudios.flowcity.compat.GuiGraphics;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Ecranul radioului: creeaza si administreaza statii personale, vezi statii publice, ale mele, oficiale, invitatii si istoric.
 * Toata logica si validarea sunt pe server; aici se trimit doar actiuni si se afiseaza starea primita.
 */
final class RadioScreen extends FScreen {
    private static final int LISTW = 230;
    private static final String[] TABS = {"Publice", "Ale mele", "Oficiale", "Invitații", "Istoric", "Admin"};
    private static final String[] TYPES = {"PUBLIC", "PRIVATE", "PASSWORD", "GROUP"};
    private static final String[] TYPE_RO = {"Publică", "Privată", "Cu parolă", "Grup"};

    private int tab;
    private int scroll;
    private int mscroll;
    private long selected = -1;
    private String selMember = "";
    private boolean creating;
    private int createType;
    private int ticks;

    RadioScreen() {
        super("Radio", 620, 380);
        setText("max", "10");
    }

    void result(boolean ok, String msg) {
        if (!msg.isEmpty()) toast(msg, !ok);
        if (ok && creating) creating = false;
    }

    @Override
    public void tick() {
        super.tick();
        if (++ticks % 40 == 0) RadioClient.act("refresh");
    }

    private static JsonArray arr(JsonObject o, String k) {
        return o != null && o.has(k) && o.get(k).isJsonArray() ? o.getAsJsonArray(k) : new JsonArray();
    }

    private static String s(JsonObject o, String k) {
        return JsonClientModule.str(o, k, "");
    }

    private JsonObject st() {
        return RadioClient.state;
    }

    private boolean admin() {
        return JsonClientModule.bool(st(), "admin", false);
    }

    private List<JsonObject> rows() {
        String key = tab == 0 ? "public" : tab == 1 ? "mine" : tab == 2 ? "official" : tab == 3 ? "invites" : tab == 5 ? "all" : "history";
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : arr(st(), key)) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        return out;
    }

    private JsonObject selectedStation() {
        for (String k : new String[] {"mine", "public", "official", "invites", "all"}) {
            for (JsonElement e : arr(st(), k)) {
                if (e.isJsonObject() && JsonClientModule.num(e.getAsJsonObject(), "id", -2) == selected) return e.getAsJsonObject();
            }
        }
        return null;
    }

    private JsonObject idObj() {
        JsonObject o = new JsonObject();
        o.addProperty("id", selected);
        return o;
    }

    @Override
    protected void onInputEnter(String id) {
        if ("q".equals(id)) {
            JsonObject o = new JsonObject();
            o.addProperty("q", text("q"));
            RadioClient.act("search", o);
        }
        blur();
    }

    @Override
    protected void onInputChanged(String id) {
        if ("q".equals(id) && tab == 0) {
            JsonObject o = new JsonObject();
            o.addProperty("q", text("q"));
            RadioClient.act("search", o);
        }
    }

    @Override
    protected void paint(GuiGraphics g, int mx, int my) {
        shell(g);
        JsonObject st = st();
        top(g, "Radio", clip(s(st, "voiceText"), 400, 6.4f, false), mx, my);
        float x0 = px + 16;
        float y0 = py + 58;

        // etichete
        float tx = x0;
        for (int i = 0; i < TABS.length; i++) {
            if (i == 5 && !admin()) continue;
            final int ti = i;
            String label = TABS[i] + (i == 3 && arr(st, "invites").size() > 0 ? " (" + arr(st, "invites").size() + ")" : "");
            tx += chipAt(g, "rt" + i, label, tx, y0, 18, tab == i, mx, my, () -> {
                tab = ti;
                scroll = 0;
                creating = false;
            });
        }
        // putere radio
        boolean power = JsonClientModule.bool(st, "power", false);
        boolean has = JsonClientModule.bool(st, "radio", false);
        float pw2 = 84f;
        btn(g, "rpow", power ? "Radio ON" : "Radio OFF", null, px + pw - 16 - pw2, y0 - 1, pw2, 20, power ? FUi.PRIMARY : FUi.GHOST, has, mx, my, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("on", !power);
            RadioClient.act("power", o);
        });

        float ly = y0 + 28;
        float lh = ph - (ly - py) - 14;
        drawList(g, x0, ly, lh, mx, my);

        float dx = x0 + LISTW + 16;
        float dw = px + pw - 16 - dx;
        card(g, dx, ly, dw, lh);
        if (creating) drawCreate(g, dx, ly, dw, lh, mx, my);
        else if (tab == 4) drawHistory(g, dx, ly, dw, lh);
        else drawDetail(g, dx, ly, dw, lh, mx, my);
        drawModal(g, "Închide stația", "Stația se închide pentru toți membrii. Frecvența devine liberă.", "Închide", "Anulează", true, mx, my);
    }

    // ------------------------------------------------------------------ lista

    private void drawList(GuiGraphics g, float x0, float ly, float lh, int mx, int my) {
        if (tab == 0) {
            input(g, "q", "Caută stație sau frecvență", x0, ly, LISTW, 20, 24, mx, my);
            ly += 26;
            lh -= 26;
        }
        float createH = 24f;
        if (tab == 1 || tab == 0) lh -= createH + 6;
        List<JsonObject> rows = rows();
        if (tab == 4) rows = new ArrayList<>();
        float rowH = 46f;
        int per = Math.max(1, (int) (lh / (rowH + 5)));
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rows.size() - per)));
        if (rows.isEmpty() && tab != 4) {
            FUi.empty(g, "search", tab == 3 ? "Nicio invitație" : "Nicio stație", "Nu există nimic în această listă.", x0 + LISTW / 2f, ly + lh / 2f - 10, LISTW);
        }
        float y = ly;
        for (int i = scroll; i < Math.min(rows.size(), scroll + per); i++) {
            JsonObject r = rows.get(i);
            long id = (long) JsonClientModule.num(r, "id", -1);
            boolean sel = id == selected;
            FUi.card(g, "rs" + id, x0, y, LISTW, rowH, true, mx, my);
            if (sel) FTheme.panel(g, x0, y, LISTW, rowH, FTheme.R_MD, FTheme.alpha(FTheme.accent, 30), FTheme.accent);
            tx(g, clip(s(r, "name"), LISTW - 90, 8.4f, true), x0 + 12, y + 17, 8.4f, FTheme.TEXT);
            tr(g, s(r, "freq"), x0 + LISTW - 10, y + 17, 7.4f, FTheme.SKY);
            t(g, JsonClientModule.integer(r, "members", 0) + "/" + JsonClientModule.integer(r, "max", 0) + " membri", x0 + 12, y + 38, 6.4f, FTheme.MUTED);
            String role = s(r, "role");
            if ("OWNER".equals(role)) pill(g, "OWNER", x0 + LISTW - 56, y + 25, FTheme.GOLD);
            else if ("CO_OWNER".equals(role)) pill(g, "CO-OWNER", x0 + LISTW - 66, y + 25, FTheme.GOLD);
            else if (JsonClientModule.bool(r, "locked", false)) pill(g, "PAROLĂ", x0 + LISTW - 52, y + 25, FTheme.WARN);
            hit((int) x0, (int) y, LISTW, (int) rowH, () -> {
                selected = id;
                selMember = "";
                creating = false;
                mscroll = 0;
            });
            y += rowH + 5;
        }
        if (rows.size() > per) FUi.scrollbar(g, x0 + LISTW + 3, ly, lh, rows.size(), per, scroll);
        if (tab == 0 || tab == 1) {
            boolean can = JsonClientModule.bool(st(), "radio", false);
            btn(g, "rcreate", "CREEAZĂ RADIO", "plus", x0, ly + lh + 6, LISTW, createH, FUi.PRIMARY, can, mx, my, () -> {
                creating = true;
                selected = -1;
            });
        }
    }

    // ------------------------------------------------------------------ creare

    private void drawCreate(GuiGraphics g, float x, float y, float w, float h, int mx, int my) {
        tx(g, "Creează o stație", x + 14, y + 22, 11f, FTheme.TEXT);
        t(g, "Ai " + JsonClientModule.integer(st(), "owned", 0) + " din " + JsonClientModule.integer(st(), "maxOwned", 0) + " stații permise.", x + 14, y + 34, 6.6f, FTheme.MUTED);
        float fy = y + 46;
        float fw = w - 28;
        t(g, "Nume", x + 14, fy + 6, 6.2f, FTheme.FAINT);
        input(g, "name", "Numele stației (3-24)", x + 14, fy + 9, fw, 20, 24, mx, my);
        fy += 38;
        t(g, "Frecvență (ex. 245.5)", x + 14, fy + 6, 6.2f, FTheme.FAINT);
        input(g, "freq", "Frecvență liberă", x + 14, fy + 9, fw / 2f - 4, 20, 6, mx, my);
        t(g, "Max membri", x + 14 + fw / 2f + 4, fy + 6, 6.2f, FTheme.FAINT);
        input(g, "max", "10", x + 14 + fw / 2f + 4, fy + 9, fw / 2f - 4, 20, 3, mx, my);
        fy += 38;
        t(g, "Tip", x + 14, fy + 6, 6.2f, FTheme.FAINT);
        float cx = x + 14;
        for (int i = 0; i < TYPES.length; i++) {
            final int ti = i;
            cx += chipAt(g, "rty" + i, TYPE_RO[i], cx, fy + 9, 18, createType == i, mx, my, () -> createType = ti);
        }
        fy += 38;
        boolean needPass = createType == 2;
        t(g, needPass ? "Parolă (obligatorie)" : "Parolă (opțional)", x + 14, fy + 6, 6.2f, FTheme.FAINT);
        input(g, "pass", "Parolă", x + 14, fy + 9, fw, 20, 24, mx, my);
        float by = y + h - 34;
        btn(g, "rok", "CREEAZĂ STAȚIA", "check", x + 14, by, fw / 2f, 24, FUi.PRIMARY, true, mx, my, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("name", text("name"));
            o.addProperty("freq", text("freq"));
            o.addProperty("max", text("max").isEmpty() ? "10" : text("max"));
            o.addProperty("type", TYPES[createType]);
            o.addProperty("pass", text("pass"));
            RadioClient.act("create", o);
        });
        btn(g, "rcancel", "Anulează", null, x + 14 + fw / 2f + 6, by, fw / 2f - 6, 24, FUi.GHOST, true, mx, my, () -> creating = false);
    }

    // ------------------------------------------------------------------ istoric

    private void drawHistory(GuiGraphics g, float x, float y, float w, float h) {
        tx(g, "Istoric", x + 14, y + 22, 11f, FTheme.TEXT);
        JsonArray a = arr(st(), "history");
        if (a.size() == 0) {
            FUi.empty(g, "clock", "Fără istoric", "Acțiunile tale pe stații apar aici.", x + w / 2f, y + h / 2f - 10, w - 40);
            return;
        }
        float ry = y + 42;
        int n = 0;
        for (JsonElement e : a) {
            if (!e.isJsonObject() || ry > y + h - 14) break;
            JsonObject r = e.getAsJsonObject();
            String when = new java.text.SimpleDateFormat("dd.MM HH:mm").format(new java.util.Date((long) JsonClientModule.num(r, "ts", 0)));
            t(g, when, x + 14, ry, 6.6f, FTheme.FAINT);
            t(g, label(s(r, "action")) + " · " + clip(s(r, "station"), w - 150, 6.8f, false) + " · " + s(r, "freq"), x + 78, ry, 6.8f, FTheme.TEXT);
            ry += 15;
            n++;
        }
    }

    private static String label(String a) {
        switch (a) {
            case "create": return "Creată";
            case "join": return "Alăturat";
            case "leave": return "Ieșit";
            case "kick": return "Scos";
            case "close": return "Închisă";
            default: return a;
        }
    }

    // ------------------------------------------------------------------ detalii statie

    private void drawDetail(GuiGraphics g, float x, float y, float w, float h, int mx, int my) {
        JsonObject active = st().has("active") && st().get("active").isJsonObject() ? st().getAsJsonObject("active") : null;
        JsonObject sel = selectedStation();
        if (sel == null) {
            if (active != null) {
                sel = active;
                selected = (long) JsonClientModule.num(active, "id", -1);
            } else {
                FUi.empty(g, "search", "Alege o stație", "Sau creează una nouă cu butonul CREEAZĂ RADIO.", x + w / 2f, y + h / 2f - 10, w - 40);
                return;
            }
        }
        final long sid = (long) JsonClientModule.num(sel, "id", -1);
        String role = s(sel, "role");
        boolean member = !role.isEmpty();
        boolean official = JsonClientModule.bool(sel, "official", false);
        boolean manage = "OWNER".equals(role) || "CO_OWNER".equals(role);
        boolean owner = "OWNER".equals(role);
        boolean isActive = active != null && JsonClientModule.num(active, "id", -2) == sid;
        boolean invited = false;
        for (JsonElement e : arr(st(), "invites")) if (e.isJsonObject() && JsonClientModule.num(e.getAsJsonObject(), "id", -2) == sid) invited = true;

        tx(g, clip(s(sel, "name"), w - 130, 11f, true), x + 14, y + 22, 11f, FTheme.TEXT);
        tr(g, s(sel, "freq") + " MHz", x + w - 14, y + 22, 9f, FTheme.SKY);
        float px2 = x + 14;
        px2 += pill(g, s(sel, "type"), px2, y + 31, FTheme.ACCENT) + 5;
        if (isActive) px2 += pill(g, "CONECTAT", px2, y + 31, FTheme.OK) + 5;
        if (!role.isEmpty()) pill(g, role.replace("_", "-"), px2, y + 31, FTheme.GOLD);
        t(g, "Owner: " + s(sel, "owner") + " · " + JsonClientModule.integer(sel, "members", 0) + "/" + JsonClientModule.integer(sel, "max", 0) + " membri", x + 14, y + 56, 6.8f, FTheme.MUTED);

        float by = y + 66;
        float bw = 104f;
        float bx = x + 14;
        boolean needPass = !member && JsonClientModule.bool(sel, "locked", false) && !invited && !official;
        if (needPass) input(g, "jpass", "Parola stației", x + 14, by, 150, 20, 24, mx, my);
        if (!member && !official) {
            float jx = needPass ? x + 170 : bx;
            final boolean inv = invited;
            btn(g, "rjoin", inv ? "Acceptă invitația" : "Alătură-te", "plus", jx, by, 118, 20, FUi.PRIMARY, true, mx, my, () -> {
                JsonObject o = idObj();
                o.addProperty("pass", text("jpass"));
                RadioClient.act(inv ? "accept" : "join", o);
            });
            if (inv) btn(g, "rdec", "Refuză", null, jx + 124, by, 80, 20, FUi.GHOST, true, mx, my, () -> RadioClient.act("decline", idObj()));
        } else {
            if (!isActive) btn(g, "rtune", "Conectează", "target", bx, by, bw, 20, FUi.PRIMARY, true, mx, my, () -> RadioClient.act("tune", idObj()));
            else btn(g, "roff", "Deconectează", null, bx, by, bw, 20, FUi.GHOST, true, mx, my, () -> RadioClient.act("off"));
            if (!official && !owner) btn(g, "rleave", "Părăsește", null, bx + bw + 6, by, 90, 20, FUi.GHOST, true, mx, my, () -> RadioClient.act("leave", idObj()));
            if (official) btn(g, "rofj", "Deschis conform jobului", null, bx + bw + 6, by, 150, 20, FUi.GHOST, false, mx, my, () -> {});
        }
        if (official || !member) return;

        // membri
        float my0 = by + 28;
        t(g, "Membri", x + 14, my0 + 6, 6.4f, FTheme.FAINT);
        JsonArray mem = arr(active != null && isActive ? active : sel, "memberList");
        float listH = manage ? 74f : h - (my0 - y) - 20f;
        int per = Math.max(1, (int) (listH / 16f));
        mscroll = Math.max(0, Math.min(mscroll, Math.max(0, mem.size() - per)));
        float ry = my0 + 10;
        if (mem.size() == 0 && !isActive) t(g, "Conectează-te ca să vezi membrii.", x + 14, ry + 10, 6.6f, FTheme.FAINT);
        for (int i = mscroll; i < Math.min(mem.size(), mscroll + per); i++) {
            JsonObject m = mem.get(i).getAsJsonObject();
            String n = s(m, "name");
            boolean hot = n.equals(selMember);
            if (hot) FTheme.panel(g, x + 12, ry - 1, w - 24, 15, 5, FTheme.alpha(FTheme.accent, 30), FTheme.accent);
            t(g, n, x + 18, ry + 9, 7f, JsonClientModule.bool(m, "online", false) ? FTheme.TEXT : FTheme.FAINT);
            String r = s(m, "role");
            if (!"MEMBER".equals(r)) t(g, r.replace("_", "-"), x + 110, ry + 9, 6.2f, FTheme.GOLD);
            tr(g, JsonClientModule.bool(m, "tuned", false) ? "conectat" : JsonClientModule.bool(m, "online", false) ? "online" : "offline", x + w - 18, ry + 9, 6.2f, JsonClientModule.bool(m, "tuned", false) ? FTheme.OK : FTheme.FAINT);
            hit((int) x + 12, (int) ry - 1, (int) w - 24, 15, () -> selMember = n);
            ry += 16f;
        }
        if (mem.size() > per) FUi.scrollbar(g, x + w - 10, my0 + 10, listH, mem.size(), per, mscroll);
        if (!manage) return;

        // administrare
        float ay = my0 + 10 + listH + 8;
        input(g, "val", "Valoare (nume, frecvență, parolă, max sau jucător)", x + 14, ay, w - 28, 20, 24, mx, my);
        ay += 26;
        float cx = x + 14;
        cx += action(g, "Redenumește", "rename", "name", cx, ay, mx, my);
        cx += action(g, "Frecvență", "setfreq", "freq", cx, ay, mx, my);
        cx += action(g, "Parolă", "setpass", "pass", cx, ay, mx, my);
        cx += action(g, "Max", "setmax", "max", cx, ay, mx, my);
        action(g, "Invită", "invite", "player", cx, ay, mx, my);
        ay += 26;
        cx = x + 14;
        boolean pick = !selMember.isEmpty();
        cx += member(g, "Promovează", "promote", cx, ay, pick && owner, mx, my);
        cx += member(g, "Retrogradează", "demote", cx, ay, pick && owner, mx, my);
        cx += member(g, "Scoate", "kick", cx, ay, pick, mx, my);
        cx += member(g, "Dă ownership", "transfer", cx, ay, pick && owner, mx, my);
        if (owner) {
            btn(g, "rclose", "Închide stația", "trash", x + w - 14 - 104, ay, 104, 20, FUi.DANGER, true, mx, my, () -> openModal(() -> RadioClient.act("close", idObj())));
        }
    }

    private float action(GuiGraphics g, String label, String act, String field, float x, float y, int mx, int my) {
        float w = FUi.chipWidth(label, 18, false) + 4;
        btn(g, "ra" + act, label, null, x, y, w, 20, FUi.SOFT, true, mx, my, () -> {
            JsonObject o = idObj();
            String v = text("val");
            o.addProperty(field, v);
            if ("setmax".equals(act)) o.addProperty("max", v.replaceAll("[^0-9]", ""));
            RadioClient.act(act, o);
        });
        return w + 5;
    }

    private float member(GuiGraphics g, String label, String act, float x, float y, boolean enabled, int mx, int my) {
        float w = FUi.chipWidth(label, 18, false) + 4;
        btn(g, "rm" + act, label, null, x, y, w, 20, "kick".equals(act) ? FUi.DANGER : FUi.GHOST, enabled, mx, my, () -> {
            JsonObject o = idObj();
            o.addProperty("player", selMember);
            RadioClient.act(act, o);
        });
        return w + 5;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        double lx = (mx - ox) / sc;
        if (lx > px + 16 + LISTW + 16) mscroll = Math.max(0, mscroll - (int) Math.signum(delta));
        else scroll = Math.max(0, scroll - (int) Math.signum(delta));
        return true;
    }
}
