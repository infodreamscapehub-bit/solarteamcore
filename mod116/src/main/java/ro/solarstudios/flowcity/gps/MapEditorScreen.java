package ro.solarstudios.flowcity.gps;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.widget.TextFieldWidget;
import org.lwjgl.glfw.GLFW;
import ro.solarstudios.flowcity.cars.client.Sk;
import ro.solarstudios.flowcity.client.ui.FlowScreen;
import ro.solarstudios.flowcity.client.ui.Gfx;
import ro.solarstudios.flowcity.compat.GuiGraphics;

/**
 * EDITOR HARTA (doar pentru admini): locatii, zone (poligoane reale), iconite, categorii si straturi, totul din interfata.
 * Fiecare modificare se trimite serverului (mesajul "edit"), care re-verifica permisiunea, valideaza, face backup si salveaza,
 * apoi retrimite configul tuturor jucatorilor. Harta e aceeasi (GpsMap) ca in GPS; camera si desenul sunt proprii.
 */
public final class MapEditorScreen extends FlowScreen {
    private static final float M = 12f;
    private static final int VIOLET = 0xFF8B7BFF;
    private static final float ROW = 28f;
    private static final int T_LOC = 0;
    /** Fila locatiilor, pentru deschiderea directa pe o locatie. */
    public static final int TAB_LOC = 0;
    private static final int T_ZONE = 1;
    private static final int T_ICON = 2;
    private static final int T_CAT = 3;
    private static final int T_LAY = 4;
    static final int T_ROAD = 5;
    private static final String[] TABS = {"LOCAȚII", "ZONE", "ICONIȚE", "CATEG.", "STRATURI", "DRUMURI"};
    private static final String[] PALETTE = {
        "#4DA3FF", "#8B7BFF", "#2FBF71", "#E5484D", "#F5A524", "#35C6E0", "#FF8FB1", "#C9D4E4", "#F0584C", "#62D98F", "#C792FF", "#FFC857"
    };
    private static final int D_NONE = 0;
    private static final int D_PAN = 1;
    private static final int D_LOC = 2;
    private static final int D_VERT = 3;
    private static final int D_ZONE = 4;
    private static final int D_NODE = 5;

    /** Ciorna unei locatii in editare (textul nume/descriere/X/Z sta in campurile de text). */
    private static final class DLoc {
        boolean isNew;
        String id = "";
        String cat = "";
        String iconRaw = "";
        String colorRaw = "";
        String dim = "minecraft:overworld";
        double x;
        double z;
        int size = 100;
        boolean enabled = true;
        boolean hidden;
    }

    private static final class DZone {
        boolean isNew;
        boolean closed;
        String id = "";
        String type = "LEGAL";
        String colorRaw = "";
        String outlineRaw = "";
        String dim = "minecraft:overworld";
        int alpha = 22;
        boolean enabled = true;
        final List<double[]> pts = new ArrayList<>();
        float[] px = new float[0];
        float[] pz = new float[0];
        float[] tris = new float[0];
        boolean dirty = true;
    }

    private static final class DCat {
        boolean isNew;
        String id = "";
        String icon = "pin";
        String color = "#4DA3FF";
        boolean enabled = true;
    }

    private static final class IconItem {
        String id;
        String name;
        String cat;
        boolean custom;
    }

    // camera
    double camX;
    double camZ;
    private double tgtX;
    private double tgtZ;
    private boolean anim;
    float zoom;
    private long lastNs;
    private final long born = System.currentTimeMillis();

    // layout
    private float panelX;
    private float panelW;
    float areaRight;
    float mapCx;
    float mapCy;
    float formLimit;

    // stare
    int tab = T_LOC;
    private int iconReturn = -1;
    private DLoc dLoc;
    private DZone dZone;
    private DCat dCat;
    private boolean placing;
    private boolean loading;
    private boolean dirtyLists = true;
    private boolean reloadPending;
    boolean pending;
    private long pendingUntil;
    private String note = "";
    private boolean noteOk = true;
    private long noteUntil;
    private String modalText;
    private Runnable modalYes;

    // import de waypoint-uri din fisierele jucatorului (vezi WaypointFiles)
    /** Fisierele gasite; nu e null cat timp e deschis selectorul. */
    private List<WaypointFiles.Source> impSources;
    private boolean impSearching;
    /** Ce a mai ramas de trimis din fisierul ales. */
    private List<WaypointFiles.Entry> impQueue;
    private String impLabel = "";
    private int impPart;
    private int impParts;

    private final List<GpsData.Loc> locRows = new ArrayList<>();
    private final List<GpsData.Zone> zoneRows = new ArrayList<>();
    private final List<GpsData.Cat> catRows = new ArrayList<>();
    private final List<IconItem> iconItems = new ArrayList<>();
    private int scrollLoc;
    private int scrollZone;
    private int scrollCat;
    private int scrollIcon;
    private int iconFilter;

    // mouse
    private boolean pressMap;
    private boolean moved;
    private double pressX;
    private double pressY;
    private double lastMx;
    private int dragKind = D_NONE;
    private int dragIdx = -1;
    private boolean sliderDrag;
    private float sliderX;
    private float sliderW = 1f;

    private final TextFieldWidget fSearch = field("Caută…", 40, s -> {
        dirtyLists = true;
        scrollLoc = 0;
        scrollZone = 0;
        scrollCat = 0;
    });
    private final TextFieldWidget fName = field("Nume", 40, null);
    private final TextFieldWidget fDesc = field("Descriere", 120, null);
    private final TextFieldWidget fX = field("X", 11, s -> xzTyped());
    private final TextFieldWidget fZ = field("Z", 11, s -> xzTyped());
    private final TextFieldWidget fY = field("Y", 6, null);
    private final TextFieldWidget fPerm = field("flowcity.perm", 48, null);
    private final TextFieldWidget fHex = field("#RRGGBB", 7, this::hexTyped);
    private final TextFieldWidget fGroup = field("Grup (filtru)", 24, null);
    final TextFieldWidget fStreet = field("Nume stradă", 32, null);
    private final RoadEditor road = new RoadEditor(this);

    public MapEditorScreen(double cx, double cz, float zoom) {
        super("Editor hartă", 520, 290);
        GpsPrefs.load();
        this.camX = cx;
        this.camZ = cz;
        this.zoom = Math.max(0.15f, Math.min(8f, zoom));
    }

    /** Deschide editorul direct pe o fila anume (ex. {@link #T_ZONE} din harta mare). */
    public MapEditorScreen(double cx, double cz, float zoom, int startTab) {
        this(cx, cz, zoom);
        this.tab = startTab;
    }

    /** Deschide editorul direct pe o locatie existenta (din meniul contextual al hartii). */
    public MapEditorScreen(double cx, double cz, float zoom, int startTab, String locId) {
        this(cx, cz, zoom, startTab);
        GpsData.Loc l = findLoc(locId);
        if (l != null) loadLoc(l);
    }

    /** Fila zonelor, pentru butonul de pe harta mare. */
    public static final int TAB_ZONE = T_ZONE;

    // ------------------------------------------------------------------ ciclu de viata

    @Override
    protected boolean framed() {
        return false;
    }

    @Override
    protected List<TextFieldWidget> fields() {
        List<TextFieldWidget> l = new ArrayList<>(6);
        if (modalText != null || impSources != null) return l;
        if (tab == T_LOC) {
            if (dLoc == null) {
                l.add(fSearch);
            } else {
                l.add(fName);
                l.add(fDesc);
                l.add(fX);
                l.add(fZ);
                l.add(fY);
                l.add(fPerm);
                l.add(fHex);
            }
        } else if (tab == T_ZONE) {
            if (dZone == null) {
                l.add(fSearch);
            } else {
                l.add(fName);
                l.add(fDesc);
            }
        } else if (tab == T_ROAD) {
            l.add(fStreet);
        } else if (tab == T_CAT) {
            if (dCat == null) {
                l.add(fSearch);
            } else {
                l.add(fName);
                l.add(fGroup);
                l.add(fHex);
            }
        }
        return l;
    }

    @Override
    public void tick() {
        super.tick();
        if (pending && System.currentTimeMillis() > pendingUntil) pending = false;
    }

    @Override
    public void removed() {
        GpsPrefs.zoom = Math.min(6f, zoom);
        GpsPrefs.save();
        GpsMap.flush();
        super.removed();
    }

    private void back() {
        if (road.dirty && modalText == null) {
            confirm("Ai modificări nesalvate la drumuri. Ieși?", "DA, IEȘI", () -> Minecraft.getInstance().setScreen(new GpsScreen()));
            return;
        }
        Minecraft.getInstance().setScreen(new GpsScreen());
    }

    public void dataChanged() {
        dirtyLists = true;
        if (reloadPending) {
            reloadPending = false;
            if (dLoc != null && !dLoc.isNew) {
                GpsData.Loc l = findLoc(dLoc.id);
                if (l == null) dLoc = null;
                else loadLoc(l);
            }
            if (dZone != null && !dZone.isNew) {
                GpsData.Zone z = findZone(dZone.id);
                if (z == null) dZone = null;
                else loadZone(z);
            }
            if (dCat != null && !dCat.isNew) {
                GpsData.Cat c = GpsData.catById.get(dCat.id);
                if (c == null) dCat = null;
                else loadCat(c);
            }
        }
        if (dLoc != null && !dLoc.isNew && findLoc(dLoc.id) == null) dLoc = null;
        if (dZone != null && !dZone.isNew && findZone(dZone.id) == null) dZone = null;
        if (dCat != null && !dCat.isNew && !GpsData.catById.containsKey(dCat.id)) dCat = null;
    }

    /** Raspunsul serverului la o editare. */
    public void onResult(String op, boolean ok, String msg) {
        pending = false;
        say(msg, ok);
        if (op.startsWith("road.") || op.startsWith("scan.")) {
            road.onResult(op, ok, msg);
            return;
        }
        if (op.equals("loc.import")) {
            if (!ok) {
                impQueue = null;
            } else if (impQueue != null && !impQueue.isEmpty()) {
                say(msg + " (" + impPart + "/" + impParts + ")", true);
                sendImportBatch();
            } else {
                impQueue = null;
                say(msg + (impParts > 1 ? " — " + impLabel + ", " + impParts + " pachete" : " — " + impLabel), true);
            }
            dirtyLists = true;
            reloadPending = true;
            return;
        }
        if (!ok) return;
        if (op.endsWith(".add") || op.endsWith(".del")) {
            if (op.startsWith("loc.")) dLoc = null;
            else if (op.startsWith("zone.")) dZone = null;
            else if (op.startsWith("cat.")) dCat = null;
            placing = false;
        } else if (op.endsWith(".set")) {
            reloadPending = true;
        }
        dirtyLists = true;
    }

    void say(String s, boolean ok) {
        note = s;
        noteOk = ok;
        noteUntil = System.currentTimeMillis() + 3200;
    }

    void send(String op, JsonObject o) {
        long now = System.currentTimeMillis();
        if (pending && now < pendingUntil) {
            say("Se așteaptă răspunsul serverului…", false);
            return;
        }
        pending = true;
        pendingUntil = now + 5000;
        GpsClient.sendEdit(op, o);
    }

    void confirm(String text, Runnable yes) {
        confirm(text, "DA, ȘTERGE", yes);
    }

    void confirm(String text, String yesLabel, Runnable yes) {
        modalText = text;
        modalYes = yes;
        modalYesLabel = yesLabel;
    }

    private String modalYesLabel = "DA, ȘTERGE";

    GpsData.Loc locNear(float mx, float my) {
        return locAt(mx, my);
    }

    boolean overPanelPublic(double mx, double my) {
        return overPanel(mx, my);
    }

    /** Reteaua de pe server s-a schimbat. */
    public void roadsChanged() {
        road.liveChanged();
    }

    public void scanProgress(int pct) {
        road.scanProgress(pct);
    }

    public void scanResult(JsonObject o) {
        road.scanResult(o);
    }

    String dim() {
        return GpsData.dimOf(Minecraft.getInstance());
    }

    // ------------------------------------------------------------------ date

    private GpsData.Loc findLoc(String id) {
        for (GpsData.Loc l : GpsData.locs) if (l.id.equals(id)) return l;
        return null;
    }

    private GpsData.Zone findZone(String id) {
        for (GpsData.Zone z : GpsData.zones) if (z.id.equals(id)) return z;
        return null;
    }

    private String catName(String id) {
        GpsData.Cat c = GpsData.catById.get(id);
        return c == null ? "-" : c.name;
    }

    private String catIcon(String id) {
        GpsData.Cat c = GpsData.catById.get(id);
        return c == null ? "pin" : c.icon;
    }

    private int catColor(String id) {
        GpsData.Cat c = GpsData.catById.get(id);
        return c == null ? 0xFF4DA3FF : c.color;
    }

    private void rebuildLists() {
        dirtyLists = false;
        String q = GpsData.norm(fSearch.getValue().trim());
        String[] toks = q.isEmpty() ? new String[0] : q.split("\\s+");
        String dim = dim();
        locRows.clear();
        for (GpsData.Loc l : GpsData.locs) {
            if (!l.dim.equals(dim)) continue;
            if (!matches(GpsData.norm(l.name + " " + l.catName), toks)) continue;
            locRows.add(l);
        }
        zoneRows.clear();
        for (GpsData.Zone z : GpsData.zones) {
            if (!z.dim.equals(dim)) continue;
            if (!matches(GpsData.norm(z.name + " " + GpsData.typeLabel(z.type)), toks)) continue;
            zoneRows.add(z);
        }
        catRows.clear();
        for (GpsData.Cat c : GpsData.cats) {
            if (!matches(GpsData.norm(c.name + " " + c.id), toks)) continue;
            catRows.add(c);
        }
        iconItems.clear();
        for (String[] b : GpsIcons.BUILTIN) {
            IconItem it = new IconItem();
            it.id = b[0];
            it.name = b[1];
            it.cat = b[2];
            iconItems.add(it);
        }
        for (GpsData.CIcon ci : GpsData.customIcons.values()) {
            IconItem it = new IconItem();
            it.id = ci.id;
            it.name = ci.name;
            it.cat = ci.category;
            it.custom = true;
            iconItems.add(it);
        }
    }

    private static boolean matches(String key, String[] toks) {
        for (String t : toks) if (!key.contains(t)) return false;
        return true;
    }

    private List<String> iconFilters() {
        List<String> f = new ArrayList<>();
        f.add("TOATE");
        for (String c : GpsIcons.LIB_CATEGORIES) f.add(c);
        f.add("PERSONALIZATE");
        return f;
    }

    // ------------------------------------------------------------------ ciorne

    private void setField(TextFieldWidget f, String v) {
        loading = true;
        f.setValue(v == null ? "" : v);
        loading = false;
    }

    private void clearDrafts() {
        dLoc = null;
        dZone = null;
        dCat = null;
        placing = false;
    }

    private void setTab(int i) {
        if (tab == i) return;
        if (!(tab == T_ICON && i == iconReturn)) clearDrafts();
        iconReturn = -1;
        tab = i;
        setField(fSearch, "");
        dirtyLists = true;
    }

    private void loadLoc(GpsData.Loc l) {
        DLoc d = new DLoc();
        d.id = l.id;
        d.cat = l.cat;
        d.iconRaw = l.iconRaw;
        d.colorRaw = l.colorRaw;
        d.dim = l.dim;
        d.x = l.x;
        d.z = l.z;
        d.size = l.size;
        d.enabled = l.enabled;
        d.hidden = l.hidden;
        dLoc = d;
        setField(fName, l.name);
        setField(fDesc, l.desc);
        setField(fX, String.valueOf(Math.round(l.x)));
        setField(fZ, String.valueOf(Math.round(l.z)));
        setField(fY, l.hasY ? String.valueOf(Math.round(l.y)) : "");
        setField(fPerm, l.perm == null ? "" : l.perm);
        setField(fHex, l.colorRaw);
    }

    private void newLocAt(double wx, double wz) {
        DLoc d = new DLoc();
        d.isNew = true;
        d.x = Math.round(wx);
        d.z = Math.round(wz);
        d.dim = dim();
        d.cat = GpsData.cats.isEmpty() ? "" : GpsData.cats.get(0).id;
        dLoc = d;
        placing = false;
        setField(fName, "");
        setField(fDesc, "");
        setField(fX, String.valueOf(Math.round(d.x)));
        setField(fZ, String.valueOf(Math.round(d.z)));
        setField(fY, "");
        setField(fPerm, "");
        setField(fHex, "");
    }

    /** „Adauga locatia de aici": X, Y, Z din lumea Minecraft a administratorului. */
    private void newLocHere() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        newLocAt(mc.player.getX(), mc.player.getZ());
        setField(fY, String.valueOf(Math.round(mc.player.getY())));
        focusOn(mc.player.getX(), mc.player.getZ());
        say("Coordonatele tale au fost preluate. Alege numele și categoria.", true);
    }

    /** Export: JSON complet + fisier de waypoint-uri (se poate reimporta cu IMPORTĂ din flowcity/waypoints.txt). */
    private void exportLocs() {
        try {
            java.nio.file.Path dir = net.minecraftforge.fml.loading.FMLPaths.GAMEDIR.get().resolve("flowcity");
            java.nio.file.Files.createDirectories(dir);
            JsonArray arr = new JsonArray();
            StringBuilder txt = new StringBuilder("#FlowCity GPS export\n");
            for (GpsData.Loc l : GpsData.locs) {
                JsonObject o = new JsonObject();
                o.addProperty("id", l.id);
                o.addProperty("name", l.name);
                o.addProperty("cat", l.cat);
                o.addProperty("desc", l.desc);
                o.addProperty("x", l.x);
                if (l.hasY) o.addProperty("y", l.y);
                o.addProperty("z", l.z);
                o.addProperty("dim", l.dim);
                o.addProperty("icon", l.iconRaw);
                o.addProperty("color", l.colorRaw);
                o.addProperty("size", l.size);
                o.addProperty("enabled", l.enabled);
                o.addProperty("hidden", l.hidden);
                if (l.perm != null && !l.perm.isEmpty()) o.addProperty("perm", l.perm);
                arr.add(o);
                String nm = l.name.replace(":", "^col^");
                String ini = nm.isEmpty() ? "L" : nm.substring(0, 1).toUpperCase();
                int ci = 0;
                int bd = Integer.MAX_VALUE;
                for (int i = 0; i < GpsWps.PALETTE.length; i++) {
                    int p = GpsWps.PALETTE[i];
                    int dr = ((p >> 16) & 255) - ((l.color >> 16) & 255);
                    int dg = ((p >> 8) & 255) - ((l.color >> 8) & 255);
                    int db = (p & 255) - (l.color & 255);
                    int dd = dr * dr + dg * dg + db * db;
                    if (dd < bd) {
                        bd = dd;
                        ci = i;
                    }
                }
                txt.append("waypoint:").append(nm).append(':').append(ini).append(':').append(Math.round(l.x)).append(':')
                    .append(l.hasY ? Math.round(l.y) : 64).append(':').append(Math.round(l.z)).append(':').append(ci)
                    .append(':').append(l.enabled ? "false" : "true").append(":0:gui.xaero_default:false:0:0:false\n");
            }
            java.nio.file.Files.write(dir.resolve("gps-export.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(arr)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(dir.resolve("gps-export.txt"), txt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            say("Exportat " + GpsData.locs.size() + " locații în flowcity/gps-export.json și gps-export.txt", true);
        } catch (java.io.IOException | RuntimeException e) {
            say("Export eșuat: " + e.getMessage(), false);
        }
    }

    private void loadZone(GpsData.Zone z) {
        DZone d = new DZone();
        d.id = z.id;
        d.type = z.type;
        d.colorRaw = z.colorRaw;
        d.outlineRaw = z.outlineRaw;
        d.dim = z.dim;
        d.alpha = z.alpha;
        d.enabled = z.enabled;
        d.closed = true;
        for (int i = 0; i < z.px.length; i++) d.pts.add(new double[] {z.px[i], z.pz[i]});
        dZone = d;
        setField(fName, z.name);
        setField(fDesc, z.desc);
    }

    private void newZone() {
        DZone d = new DZone();
        d.isNew = true;
        d.dim = dim();
        dZone = d;
        setField(fName, "");
        setField(fDesc, "");
    }

    private void loadCat(GpsData.Cat c) {
        DCat d = new DCat();
        d.id = c.id;
        d.icon = c.icon;
        d.color = String.format("#%06X", c.color & 0xFFFFFF);
        d.enabled = c.enabled;
        dCat = d;
        setField(fName, c.name);
        setField(fGroup, c.group);
        setField(fHex, d.color);
    }

    private void newCat() {
        DCat d = new DCat();
        d.isNew = true;
        dCat = d;
        setField(fName, "");
        setField(fGroup, "");
        setField(fHex, d.color);
    }

    private void xzTyped() {
        if (loading || dLoc == null) return;
        try {
            dLoc.x = Double.parseDouble(fX.getValue().trim());
        } catch (NumberFormatException ignored) {
            // lasam valoarea veche pana devine valida
        }
        try {
            dLoc.z = Double.parseDouble(fZ.getValue().trim());
        } catch (NumberFormatException ignored) {
            // la fel
        }
    }

    private static boolean isHex(String s) {
        return s != null && s.matches("#[0-9A-Fa-f]{6}");
    }

    private void hexTyped(String s) {
        if (loading || !isHex(s)) return;
        String h = s.toUpperCase();
        if (tab == T_LOC && dLoc != null) dLoc.colorRaw = h;
        else if (tab == T_CAT && dCat != null) dCat.color = h;
    }

    // ------------------------------------------------------------------ salvare

    private void saveLoc() {
        DLoc d = dLoc;
        if (d == null) return;
        String name = fName.getValue().trim();
        if (name.isEmpty()) {
            say("Numele e obligatoriu.", false);
            return;
        }
        if (d.cat.isEmpty()) {
            say("Alege o categorie (creeaz-o în tab-ul CATEG.).", false);
            return;
        }
        double x;
        double z;
        try {
            x = Double.parseDouble(fX.getValue().trim());
            z = Double.parseDouble(fZ.getValue().trim());
        } catch (NumberFormatException e) {
            say("Coordonate invalide.", false);
            return;
        }
        JsonObject o = new JsonObject();
        if (!d.isNew) o.addProperty("id", d.id);
        o.addProperty("name", name);
        o.addProperty("desc", fDesc.getValue().trim());
        o.addProperty("cat", d.cat);
        o.addProperty("x", x);
        o.addProperty("z", z);
        String ys = fY.getValue().trim();
        if (ys.isEmpty()) {
            o.add("y", com.google.gson.JsonNull.INSTANCE);
        } else {
            try {
                o.addProperty("y", Double.parseDouble(ys));
            } catch (NumberFormatException e) {
                say("Înălțime Y invalidă.", false);
                return;
            }
        }
        o.addProperty("perm", fPerm.getValue().trim());
        o.addProperty("dim", d.dim);
        o.addProperty("icon", d.iconRaw);
        o.addProperty("color", d.colorRaw);
        o.addProperty("size", d.size);
        o.addProperty("enabled", d.enabled);
        o.addProperty("hidden", d.hidden);
        send(d.isNew ? "loc.add" : "loc.set", o);
    }

    private void saveZone() {
        DZone d = dZone;
        if (d == null) return;
        if (d.pts.size() < 3) {
            say("Zona are nevoie de minim 3 puncte.", false);
            return;
        }
        String name = fName.getValue().trim();
        if (name.isEmpty()) {
            say("Numele e obligatoriu.", false);
            return;
        }
        d.closed = true;
        d.dirty = true;
        JsonObject o = new JsonObject();
        if (!d.isNew) o.addProperty("id", d.id);
        o.addProperty("name", name);
        o.addProperty("desc", fDesc.getValue().trim());
        o.addProperty("type", d.type);
        o.addProperty("color", d.colorRaw);
        o.addProperty("outline", d.outlineRaw);
        o.addProperty("alpha", d.alpha);
        o.addProperty("enabled", d.enabled);
        o.addProperty("dim", d.dim);
        JsonArray pts = new JsonArray();
        for (double[] p : d.pts) {
            JsonArray a = new JsonArray();
            a.add(Math.round(p[0]));
            a.add(Math.round(p[1]));
            pts.add(a);
        }
        o.add("pts", pts);
        send(d.isNew ? "zone.add" : "zone.set", o);
    }

    private void saveCat() {
        DCat d = dCat;
        if (d == null) return;
        String name = fName.getValue().trim();
        if (name.isEmpty()) {
            say("Numele e obligatoriu.", false);
            return;
        }
        String hex = fHex.getValue().trim();
        if (isHex(hex)) d.color = hex.toUpperCase();
        if (!isHex(d.color)) {
            say("Culoare invalidă (#RRGGBB).", false);
            return;
        }
        JsonObject o = new JsonObject();
        if (!d.isNew) o.addProperty("id", d.id);
        o.addProperty("name", name);
        o.addProperty("group", fGroup.getValue().trim());
        o.addProperty("icon", d.icon);
        o.addProperty("color", d.color);
        o.addProperty("enabled", d.enabled);
        send(d.isNew ? "cat.add" : "cat.set", o);
    }

    private void sendSetting(String key, double v) {
        JsonObject o = new JsonObject();
        o.addProperty(key, v);
        send("settings.set", o);
    }

    // ------------------------------------------------------------------ transformari

    float sx(double wx) {
        return (float) (mapCx + (wx - camX) * zoom);
    }

    float sy(double wz) {
        return (float) (mapCy + (wz - camZ) * zoom);
    }

    double wx(double sx) {
        return camX + (sx - mapCx) / zoom;
    }

    double wz(double sy) {
        return camZ + (sy - mapCy) / zoom;
    }

    void hitF(float x, float y, float w, float h, Runnable r) {
        hit(Math.round(x), Math.round(y), Math.max(1, Math.round(w)), Math.max(1, Math.round(h)), r);
    }

    private static float ease(float t) {
        t = Math.max(0f, Math.min(1f, t));
        float u = 1f - t;
        return 1f - u * u * u;
    }

    static String clip(String s, float size, boolean xl, float maxW) {
        if (s == null) return "";
        if (Gfx.width(s, size, xl) <= maxW) return s;
        String t = s;
        while (t.length() > 1 && Gfx.width(t + "...", size, xl) > maxW) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    private void zoomBy(float factor, float px0, float py0) {
        double ax = wx(px0);
        double az = wz(py0);
        zoom = Math.max(0.15f, Math.min(8f, zoom * factor));
        camX = ax - (px0 - mapCx) / zoom;
        camZ = az - (py0 - mapCy) / zoom;
        anim = false;
    }

    private void centerOnPlayer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        tgtX = mc.player.getX();
        tgtZ = mc.player.getZ();
        anim = true;
    }

    private void focusOn(double x, double z) {
        tgtX = x;
        tgtZ = z;
        anim = true;
    }

    private boolean overPanel(double mx, double my) {
        return mx >= panelX && my >= M && my <= height - M;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        lastMx = mx;
        if (modalText != null || impSources != null) return super.mouseClicked(mx, my, button);
        if (button == 0) {
            if (super.mouseClicked(mx, my, button)) return true;
            if (overPanel(mx, my)) return true;
            pressMap = true;
            moved = false;
            pressX = mx;
            pressY = my;
            dragKind = D_PAN;
            if (tab == T_LOC && dLoc != null && !placing) {
                float dx = sx(dLoc.x) - (float) mx;
                float dy = sy(dLoc.z) - (float) my;
                if (dx * dx + dy * dy < 14f * 14f) dragKind = D_LOC;
            } else if (tab == T_ROAD) {
                if (road.press(mx, my)) dragKind = D_NODE;
            } else if (tab == T_ZONE && dZone != null && dZone.closed) {
                int v = vertAt((float) mx, (float) my);
                if (v >= 0) {
                    dragKind = D_VERT;
                    dragIdx = v;
                } else {
                    int e = midAt((float) mx, (float) my);
                    if (e >= 0) {
                        double[] a = dZone.pts.get(e);
                        double[] b = dZone.pts.get((e + 1) % dZone.pts.size());
                        dZone.pts.add(e + 1, new double[] {(a[0] + b[0]) / 2.0, (a[1] + b[1]) / 2.0});
                        dZone.dirty = true;
                        dragKind = D_VERT;
                        dragIdx = e + 1;
                    } else {
                        refreshZone(dZone);
                        if (GpsData.inside(dZone.px, dZone.pz, (float) wx(mx), (float) wz(my))) dragKind = D_ZONE;
                    }
                }
            }
            return true;
        }
        if (button == 1 && !overPanel(mx, my)) {
            if (tab == T_ROAD) {
                road.rightClick(mx, my);
                return true;
            }
            if (tab == T_ZONE && dZone != null) {
                if (!dZone.closed) {
                    if (!dZone.pts.isEmpty()) {
                        dZone.pts.remove(dZone.pts.size() - 1);
                        dZone.dirty = true;
                    }
                } else {
                    int v = vertAt((float) mx, (float) my);
                    if (v >= 0) {
                        if (dZone.pts.size() > 3) {
                            dZone.pts.remove(v);
                            dZone.dirty = true;
                        } else {
                            say("O zonă are minim 3 puncte.", false);
                        }
                    }
                }
            }
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (modalText != null) return true;
        if (sliderDrag && button == 0) {
            alphaFrom((float) mx);
            return true;
        }
        if (pressMap && button == 0) {
            if (Math.abs(mx - pressX) + Math.abs(my - pressY) > 4) moved = true;
            if (moved) {
                if (dragKind == D_LOC && dLoc != null) {
                    dLoc.x = Math.round(wx(mx));
                    dLoc.z = Math.round(wz(my));
                    setField(fX, String.valueOf(Math.round(dLoc.x)));
                    setField(fZ, String.valueOf(Math.round(dLoc.z)));
                } else if (dragKind == D_NODE) {
                    road.drag(mx, my);
                } else if (dragKind == D_VERT && dZone != null && dragIdx >= 0 && dragIdx < dZone.pts.size()) {
                    double[] p = dZone.pts.get(dragIdx);
                    p[0] = Math.round(wx(mx));
                    p[1] = Math.round(wz(my));
                    dZone.dirty = true;
                } else if (dragKind == D_ZONE && dZone != null) {
                    for (double[] p : dZone.pts) {
                        p[0] += dx / zoom;
                        p[1] += dy / zoom;
                    }
                    dZone.dirty = true;
                } else {
                    camX -= dx / zoom;
                    camZ -= dy / zoom;
                    anim = false;
                }
            }
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (sliderDrag && button == 0) {
            sliderDrag = false;
            return true;
        }
        if (pressMap && button == 0) {
            pressMap = false;
            if (dragKind == D_NODE) road.release();
            if (!moved && modalText == null) mapClick((float) mx, (float) my);
            dragKind = D_NONE;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    private void mapClick(float mx, float my) {
        if (tab == T_ROAD) {
            road.click(mx, my);
            return;
        }
        if (tab == T_LOC) {
            if (placing) {
                newLocAt(wx(mx), wz(my));
                return;
            }
            if (dLoc != null && dLoc.isNew) {
                dLoc.x = Math.round(wx(mx));
                dLoc.z = Math.round(wz(my));
                setField(fX, String.valueOf(Math.round(dLoc.x)));
                setField(fZ, String.valueOf(Math.round(dLoc.z)));
                return;
            }
            GpsData.Loc best = locAt(mx, my);
            if (best != null) loadLoc(best);
        } else if (tab == T_ZONE) {
            if (dZone != null && !dZone.closed) {
                if (dZone.pts.size() >= 3) {
                    double[] f = dZone.pts.get(0);
                    float dx = sx(f[0]) - mx;
                    float dy = sy(f[1]) - my;
                    if (dx * dx + dy * dy < 11f * 11f) {
                        dZone.closed = true;
                        dZone.dirty = true;
                        say("Poligon închis. Ajustează punctele și salvează.", true);
                        return;
                    }
                }
                if (dZone.pts.size() >= 96) {
                    say("Maxim 96 de puncte.", false);
                    return;
                }
                dZone.pts.add(new double[] {Math.round(wx(mx)), Math.round(wz(my))});
                dZone.dirty = true;
            } else if (dZone == null) {
                GpsData.Zone z = zoneAt(mx, my);
                if (z != null) loadZone(z);
            }
        }
    }

    private GpsData.Loc locAt(float mx, float my) {
        String dim = dim();
        GpsData.Loc best = null;
        float bd = 14f * 14f;
        for (GpsData.Loc l : GpsData.locs) {
            if (!l.dim.equals(dim) || (!l.enabled && !GpsPrefs.showDisabled)) continue;
            if (!GpsPrefs.layerOn("locations") || !GpsPrefs.layerOn(GpsData.locLayer(l))) continue;
            float dx = sx(l.x) - mx;
            float dy = sy(l.z) - my;
            float d = dx * dx + dy * dy;
            if (d < bd) {
                bd = d;
                best = l;
            }
        }
        return best;
    }

    private GpsData.Zone zoneAt(float mx, float my) {
        String dim = dim();
        float x = (float) wx(mx);
        float z = (float) wz(my);
        for (int i = GpsData.zones.size() - 1; i >= 0; i--) {
            GpsData.Zone q = GpsData.zones.get(i);
            if (!q.dim.equals(dim) || (!q.enabled && !GpsPrefs.showDisabled) || !GpsPrefs.layerOn(GpsData.zoneLayer(q))) continue;
            if (x < q.minX || x > q.maxX || z < q.minZ || z > q.maxZ) continue;
            if (GpsData.inside(q.px, q.pz, x, z)) return q;
        }
        return null;
    }

    private int vertAt(float mx, float my) {
        if (dZone == null) return -1;
        for (int i = 0; i < dZone.pts.size(); i++) {
            double[] p = dZone.pts.get(i);
            float dx = sx(p[0]) - mx;
            float dy = sy(p[1]) - my;
            if (dx * dx + dy * dy < 9f * 9f) return i;
        }
        return -1;
    }

    private int midAt(float mx, float my) {
        if (dZone == null || dZone.pts.size() < 2) return -1;
        int n = dZone.pts.size();
        for (int i = 0; i < n; i++) {
            double[] a = dZone.pts.get(i);
            double[] b = dZone.pts.get((i + 1) % n);
            float dx = sx((a[0] + b[0]) / 2.0) - mx;
            float dy = sy((a[1] + b[1]) / 2.0) - my;
            if (dx * dx + dy * dy < 8f * 8f) return i;
        }
        return -1;
    }

    private void alphaFrom(float mx) {
        if (dZone == null) return;
        float f = Math.max(0f, Math.min(1f, (mx - sliderX) / sliderW));
        dZone.alpha = 5 + Math.round(f * 85f);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (modalText != null || impSources != null) return true;
        if (overPanel(mx, my)) {
            int d = -(int) Math.signum(delta) * 2;
            if (tab == T_LOC) scrollLoc = Math.max(0, scrollLoc + d);
            else if (tab == T_ZONE) scrollZone = Math.max(0, scrollZone + d);
            else if (tab == T_CAT) scrollCat = Math.max(0, scrollCat + d);
            else if (tab == T_ICON) scrollIcon = Math.max(0, scrollIcon + (d > 0 ? 1 : -1));
            else if (tab == T_ROAD) road.scrollBy(d * 14);
        } else {
            zoomBy(delta > 0 ? 1.2f : 1f / 1.2f, (float) mx, (float) my);
        }
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean fieldFocused = false;
        for (TextFieldWidget f : fields()) if (f.isFocused()) fieldFocused = true;
        if (impSources != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) impSources = null;
            return true;
        }
        if (modalText != null) {
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                modalText = null;
                modalYes = null;
            }
            return true;
        }
        if (!fieldFocused) {
            if (tab == T_ROAD && road.key(key, mods)) return true;
            if (key == GLFW.GLFW_KEY_ESCAPE) {
                if (tab == T_ROAD && road.escape()) return true;
                if (placing || dLoc != null || dZone != null || dCat != null) {
                    clearDrafts();
                } else {
                    back();
                }
                return true;
            }
            if (GpsClient.key != null && GpsClient.key.matches(key, scan)) {
                back();
                return true;
            }
            if (key == GLFW.GLFW_KEY_G) {
                GpsPrefs.grid = !GpsPrefs.grid;
                return true;
            }
            if (key == GLFW.GLFW_KEY_C) {
                centerOnPlayer();
                return true;
            }
        }
        return super.keyPressed(key, scan, mods);
    }

    // ------------------------------------------------------------------ desen

    private void refreshZone(DZone z) {
        if (!z.dirty) return;
        z.dirty = false;
        int n = z.pts.size();
        z.px = new float[n];
        z.pz = new float[n];
        for (int i = 0; i < n; i++) {
            z.px[i] = (float) z.pts.get(i)[0];
            z.pz[i] = (float) z.pts.get(i)[1];
        }
        z.tris = z.closed && n >= 3 ? GpsData.triangulate(z.px, z.pz) : new float[0];
    }

    @Override
    protected void draw(GuiGraphics g, int mx, int my) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        long ns = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.1f, (ns - lastNs) / 1e9f);
        lastNs = ns;
        float t = (System.currentTimeMillis() - born) / 1000f;
        panelW = Math.max(300f, Math.min(372f, width * 0.30f));
        panelX = width - panelW - M;
        areaRight = panelX - 6f;
        mapCx = areaRight / 2f;
        mapCy = height / 2f;
        if (anim) {
            camX += (tgtX - camX) * Math.min(1f, dt * 7f);
            camZ += (tgtZ - camZ) * Math.min(1f, dt * 7f);
            if (Math.abs(tgtX - camX) < 0.3 && Math.abs(tgtZ - camZ) < 0.3) anim = false;
        }
        if (dirtyLists) rebuildLists();

        Gfx.rrect(g, 0, 0, width, height, 0, 0xFF0C1C33, 0xFF08111F);
        drawGrid(g);
        GpsMap.draw(g, camX, camZ, zoom, mapCx, mapCy, width, height, 1f);
        g.fillGradient(0, 0, width, 60, 0xAA070F1C, 0x00070F1C);
        g.fillGradient(0, height - 70, width, height, 0x00070F1C, 0xAA070F1C);
        drawZones(g);
        road.drawMap(g, mx, my, t);
        drawLocs(g, t);
        drawPlayer(g, mc, t);
        drawMapUi(g, mc, mx, my);
        drawPanel(g, mx, my, t);
        drawModal(g, mx, my);
        drawImportPicker(g, mx, my);
    }

    private void drawGrid(GuiGraphics g) {
        if (zoom < 2.2f && !GpsPrefs.grid) return;
        double base = 16.0;
        while (base * zoom < 40.0) base *= 2.0;
        float step = (float) (base * zoom);
        float x0 = (float) (mapCx + (Math.floor((camX - mapCx / zoom) / base) * base - camX) * zoom);
        float y0 = (float) (mapCy + (Math.floor((camZ - mapCy / zoom) / base) * base - camZ) * zoom);
        int col = GpsPrefs.grid ? 0x2AFFFFFF : 0x0CFFFFFF;
        for (float x = x0; x < width; x += step) g.fill(Math.round(x), 0, Math.round(x) + 1, height, col);
        for (float y = y0; y < height; y += step) g.fill(0, Math.round(y), width, Math.round(y) + 1, col);
    }

    private static int withAlpha(int rgb, float a) {
        return (rgb & 0xFFFFFF) | (Math.max(0, Math.min(255, Math.round(a * 255f))) << 24);
    }

    private void drawZones(GuiGraphics g) {
        String dim = dim();
        for (GpsData.Zone z : GpsData.zones) {
            if (!z.dim.equals(dim)) continue;
            if (dZone != null && !dZone.isNew && z.id.equals(dZone.id)) continue;
            if (!z.enabled && !GpsPrefs.showDisabled) continue;
            if (!GpsPrefs.layerOn(GpsData.zoneLayer(z))) continue;
            float x0 = sx(z.minX);
            float x1 = sx(z.maxX);
            float y0 = sy(z.minZ);
            float y1 = sy(z.maxZ);
            if (x1 < 0 || y1 < 0 || x0 > width || y0 > height) continue;
            float k = z.enabled ? 1f : 0.4f;
            int fill = withAlpha(z.color, z.alpha / 100f * k);
            int edge = withAlpha(z.outline, 0.88f * k);
            float[] tr = z.tris;
            for (int i = 0; i + 5 < tr.length; i += 6) {
                Gfx.poly(g, new float[] {sx(tr[i]), sy(tr[i + 1]), sx(tr[i + 2]), sy(tr[i + 3]), sx(tr[i + 4]), sy(tr[i + 5])}, fill);
            }
            int n = z.px.length;
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                Gfx.line(g, sx(z.px[i]), sy(z.pz[i]), sx(z.px[j]), sy(z.pz[j]), 1.6f, edge);
            }
            if (zoom >= 0.45f && x1 - x0 > 50) {
                Gfx.textS(g, clip(z.name, 7.4f, true, x1 - x0 - 8), (x0 + x1) / 2f, (y0 + y1) / 2f + 3f, 7.4f, withAlpha(z.outline, k), 1, true, 0.6f);
            }
        }
        if (dZone != null) drawDraftZone(g);
    }

    private void drawDraftZone(GuiGraphics g) {
        DZone d = dZone;
        refreshZone(d);
        int base = GpsData.color(d.colorRaw, GpsData.typeColor(d.type));
        int outline = GpsData.color(d.outlineRaw, base);
        int fill = withAlpha(base, d.alpha / 100f);
        int edge = withAlpha(outline, 0.95f);
        float[] tr = d.tris;
        for (int i = 0; i + 5 < tr.length; i += 6) {
            Gfx.poly(g, new float[] {sx(tr[i]), sy(tr[i + 1]), sx(tr[i + 2]), sy(tr[i + 3]), sx(tr[i + 4]), sy(tr[i + 5])}, fill);
        }
        int n = d.px.length;
        int segs = d.closed ? n : n - 1;
        for (int i = 0; i < segs; i++) {
            int j = (i + 1) % n;
            Gfx.line(g, sx(d.px[i]), sy(d.pz[i]), sx(d.px[j]), sy(d.pz[j]), 2f, edge);
        }
        for (int i = 0; i < n; i++) {
            float x = sx(d.px[i]);
            float y = sy(d.pz[i]);
            boolean first = i == 0 && !d.closed && n >= 3;
            Gfx.disc(g, x, y, 5.5f, 0xFF081018);
            Gfx.disc(g, x, y, 4f, first ? 0xFF62D98F : 0xFFFFFFFF);
            Gfx.disc(g, x, y, 2f, outline);
        }
        if (d.closed && n >= 2) {
            for (int i = 0; i < n; i++) {
                int j = (i + 1) % n;
                float x = sx((d.px[i] + d.px[j]) / 2f);
                float y = sy((d.pz[i] + d.pz[j]) / 2f);
                Gfx.disc(g, x, y, 3.4f, 0xCC081018);
                Gfx.line(g, x - 2f, y, x + 2f, y, 1.2f, 0xFFC9D4E4);
                Gfx.line(g, x, y - 2f, x, y + 2f, 1.2f, 0xFFC9D4E4);
            }
        }
    }

    private void drawLocs(GuiGraphics g, float t) {
        String dim = dim();
        float r = Math.max(6.5f, Math.min(10f, 5f + zoom * 1.6f));
        boolean allOn = GpsPrefs.layerOn("locations");
        for (GpsData.Loc l : GpsData.locs) {
            if (!l.dim.equals(dim)) continue;
            if (dLoc != null && !dLoc.isNew && l.id.equals(dLoc.id)) continue;
            if (!l.enabled && !GpsPrefs.showDisabled) continue;
            if (!allOn || !GpsPrefs.layerOn(GpsData.locLayer(l))) continue;
            float x = sx(l.x);
            float y = sy(l.z);
            if (x < -20 || y < -20 || x > width + 20 || y > height + 20) continue;
            float a = !l.enabled ? 0.35f : (l.hidden ? 0.5f : 1f);
            GpsIcons.badge(g, l.icon, x, y, r * l.size / 100f, l.color, a, false);
            if (zoom >= 1.5f) Gfx.textS(g, clip(l.name, 7.2f, true, 110f), x, y + r + 9f, 7.2f, withAlpha(0xFFFFFF, a), 1, true, 0.3f);
        }
        if (dLoc != null) {
            DLoc d = dLoc;
            float x = sx(d.x);
            float y = sy(d.z);
            String icon = d.iconRaw.isEmpty() ? catIcon(d.cat) : d.iconRaw;
            int color = GpsData.color(d.colorRaw, catColor(d.cat));
            float ph = (t % 1.6f) / 1.6f;
            float rr = r * d.size / 100f;
            Gfx.ring(g, x, y, rr + 3f + ph * 12f, rr + 4f + ph * 12f, Gfx.fade(VIOLET, 0.8f * (1f - ph)), Gfx.fade(VIOLET, 0.8f * (1f - ph)));
            GpsIcons.badge(g, icon, x, y, rr + 2f, color, 1f, true);
            String nm = fName.getValue().trim();
            if (!nm.isEmpty()) Gfx.textS(g, clip(nm, 8f, true, 140f), x, y + rr + 12f, 8f, Sk.WHITE, 1, true, 0.3f);
        }
    }

    private void drawPlayer(GuiGraphics g, Minecraft mc, float t) {
        float x = sx(mc.player.getX());
        float y = sy(mc.player.getZ());
        if (x < -20 || y < -20 || x > width + 20 || y > height + 20) return;
        Gfx.disc(g, x, y, 8.5f, 0x88000000);
        double ang = Math.PI + Math.toRadians(mc.player.yRot);
        GpsIcons.arrow(g, x, y, 7.5f, ang, 0xFFFFFFFF, 0xFF1B6FD1);
    }

    // ------------------------------------------------------------------ butoane

    void btn(GuiGraphics g, String label, float x, float y, float w, float h, int style, int mx, int my, Runnable r) {
        boolean disabled = style == 3;
        boolean hov = !disabled && Sk.in(mx, my, x, y, w, h);
        int bg;
        int fg;
        if (disabled) {
            bg = 0xFF111A28;
            fg = Sk.FAINT;
        } else if (style == 0) {
            bg = hov ? 0xFF7DBBFF : Sk.ACC;
            fg = 0xFF061426;
        } else if (style == 2) {
            bg = hov ? 0xFFFF7A6E : Sk.BAD;
            fg = 0xFF1A0705;
        } else if (style == 4) {
            bg = hov ? 0xFFA597FF : VIOLET;
            fg = 0xFF0A0A22;
        } else {
            bg = hov ? 0xFF22344D : 0xFF14202F;
            fg = hov ? Sk.WHITE : 0xFFC9CBD0;
        }
        Sk.slant(g, x, y, w, h, 6f, bg);
        Gfx.text(g, clip(label, 8.2f, true, w - 14f), x + w / 2f + 1, y + h / 2f + 3.2f, 8.2f, fg, 1, true, 0.4f);
        if (!disabled) hitF(x, y, w, h, r);
    }

    private void sq(GuiGraphics g, float x, float y, float s, int mx, int my, Runnable r) {
        boolean hov = Sk.in(mx, my, x, y, s, s);
        Sk.cut(g, x, y, s, s, 6f, hov ? 0xF0223656 : 0xF0081018);
        Gfx.rrect(g, x, y + s - 2, s, 2, 0, hov ? Sk.ACC : 0xFF22344D);
        hitF(x, y, s, s, r);
    }

    void fld(GuiGraphics g, TextFieldWidget f, float x, float y, float w, float h, int mx, int my) {
        if (y + h > formLimit) {
            f.x = -1000;
            f.y = -1000;
            return;
        }
        Sk.slant(g, x, y, w, h, 4f, Sk.ROW);
        Gfx.rrect(g, x + 4, y + h - 1.6f, w - 8, 1.6f, 0, f.isFocused() ? Sk.ACC : 0xFF22344D);
        f.x = Math.round(x + 10);
        f.y = Math.round(y + (h - 8f) / 2f);
        f.setWidth(Math.round(w - 20));
        f.render(g.pose(), mx, my, 0f);
        hitF(x, y, w, h, () -> focus(f, true));
    }

    void label(GuiGraphics g, String s, float x, float y) {
        if (y > formLimit) return;
        Gfx.text(g, s, x, y + 7f, 6.6f, Sk.MUTED, 0, true, 1f);
    }

    void toggle(GuiGraphics g, String label, float x, float y, float w, boolean on, int mx, int my, Runnable r) {
        if (y + 22f > formLimit) return;
        boolean hv = Sk.in(mx, my, x, y, w, 22);
        if (hv) Sk.slant(g, x, y, w, 22, 5f, 0xFF1B2E47);
        Gfx.rrect(g, x + 6, y + 4, 14, 14, 3, on ? Sk.ACC : 0xFF22344D);
        if (on) {
            Gfx.line(g, x + 9, y + 11, x + 12.5f, y + 14.5f, 1.8f, 0xFF061426);
            Gfx.line(g, x + 12.5f, y + 14.5f, x + 18, y + 7, 1.8f, 0xFF061426);
        }
        Gfx.text(g, clip(label, 8.2f, true, w - 34f), x + 28, y + 15, 8.2f, on ? Sk.WHITE : Sk.MUTED, 0, true, 0f);
        hitF(x, y, w, 22, r);
    }

    private void arrowTri(GuiGraphics g, float cx, float cy, boolean right, int color) {
        if (right) Gfx.poly(g, new float[] {cx + 4f, cy, cx - 3f, cy - 5f, cx - 3f, cy + 5f}, color);
        else Gfx.poly(g, new float[] {cx - 4f, cy, cx + 3f, cy - 5f, cx + 3f, cy + 5f}, color);
    }

    void selector(GuiGraphics g, String title, String value, float x, float y, float w, int mx, int my, Runnable prev, Runnable next) {
        if (y + 32f > formLimit) return;
        label(g, title, x, y);
        float by = y + 10f;
        Sk.slant(g, x, by, w, 22, 4f, Sk.ROW);
        boolean hp = Sk.in(mx, my, x, by, 26, 22);
        boolean hn = Sk.in(mx, my, x + w - 26, by, 26, 22);
        arrowTri(g, x + 14, by + 11, false, hp ? Sk.WHITE : Sk.MUTED);
        arrowTri(g, x + w - 12, by + 11, true, hn ? Sk.WHITE : Sk.MUTED);
        Gfx.text(g, clip(value, 8.6f, true, w - 64f), x + w / 2f, by + 15f, 8.6f, Sk.WHITE, 1, true, 0f);
        hitF(x, by, 26, 22, prev);
        hitF(x + w - 26, by, 26, 22, next);
    }

    /** Paleta de culori: 12 culori + "implicit" (gol). Intoarce inaltimea folosita. */
    private float swatches(GuiGraphics g, float x, float y, float w, String current, boolean allowDefault, int mx, int my, java.util.function.Consumer<String> pick) {
        if (y + 16f > formLimit) return 18f;
        float s = 16f;
        float gap = 3f;
        float cx = x;
        int total = PALETTE.length + (allowDefault ? 1 : 0);
        for (int i = 0; i < total; i++) {
            boolean isDef = allowDefault && i == 0;
            String hex = isDef ? "" : PALETTE[i - (allowDefault ? 1 : 0)];
            boolean sel = current == null ? isDef : current.equalsIgnoreCase(hex);
            if (isDef) {
                Gfx.rrect(g, cx, y, s, s, 3, 0xFF14202F);
                Gfx.line(g, cx + 3, y + s - 3, cx + s - 3, y + 3, 1.4f, Sk.MUTED);
            } else {
                Gfx.rrect(g, cx, y, s, s, 3, GpsData.color(hex, 0xFFFFFFFF));
            }
            if (sel) Gfx.ring(g, cx + s / 2f, y + s / 2f, s / 2f + 1f, s / 2f + 2.4f, 0xFFFFFFFF, 0xFFFFFFFF);
            final String ph = hex;
            hitF(cx, y, s, s, () -> pick.accept(ph));
            cx += s + gap;
            if (cx + s > x + w + 1f) break;
        }
        return s + 4f;
    }

    // ------------------------------------------------------------------ interfata de pe harta

    private void drawMapUi(GuiGraphics g, Minecraft mc, int mx, int my) {
        btn(g, "ÎNAPOI LA GPS", M, M, 128, 34, 1, mx, my, this::back);
        long cxw = Math.round(wx(mx));
        long czw = Math.round(wz(my));
        String s = overPanel(mx, my) ? "X: " + Math.round(mc.player.getX()) + "   |   Z: " + Math.round(mc.player.getZ()) : "X: " + cxw + "   |   Z: " + czw;
        float w = Gfx.width(s, 10f, true) + 40f;
        float x = mapCx - w / 2f;
        Sk.cut(g, x, M, w, 30, 8, 0xF0081018);
        Gfx.rrect(g, x, M + 28, w, 2, 0, VIOLET);
        Gfx.disc(g, x + 14, M + 15, 3.4f, overPanel(mx, my) ? Sk.ACC : VIOLET);
        Gfx.text(g, s, mapCx + 5, M + 20.5f, 10f, Sk.WHITE, 1, true, 0.3f);
        Gfx.text(g, overPanel(mx, my) ? "poziția ta" : "cursor", mapCx, M + 44f, 7f, 0xFF9CB4D0, 1);

        float sz = 34f;
        float x0 = areaRight - M - sz;
        float y4 = height - M - sz;
        float y3 = y4 - sz - 6f;
        float y2 = y3 - sz - 6f;
        float y1 = y2 - sz - 6f;
        sq(g, x0, y1, sz, mx, my, () -> zoomBy(1.3f, mapCx, mapCy));
        Gfx.line(g, x0 + 11, y1 + sz / 2f - 1, x0 + sz - 11, y1 + sz / 2f - 1, 2f, Sk.WHITE);
        Gfx.line(g, x0 + sz / 2f, y1 + 10, x0 + sz / 2f, y1 + sz - 12, 2f, Sk.WHITE);
        sq(g, x0, y2, sz, mx, my, () -> zoomBy(1f / 1.3f, mapCx, mapCy));
        Gfx.line(g, x0 + 11, y2 + sz / 2f - 1, x0 + sz - 11, y2 + sz / 2f - 1, 2f, Sk.WHITE);
        sq(g, x0, y3, sz, mx, my, this::centerOnPlayer);
        Gfx.ring(g, x0 + sz / 2f, y3 + sz / 2f - 1, 6f, 7.6f, Sk.WHITE, Sk.WHITE);
        Gfx.disc(g, x0 + sz / 2f, y3 + sz / 2f - 1, 2.2f, VIOLET);
        sq(g, x0, y4, sz, mx, my, () -> GpsPrefs.grid = !GpsPrefs.grid);
        int gc = GpsPrefs.grid ? Sk.ACC : Sk.WHITE;
        Gfx.line(g, x0 + 12, y4 + 8, x0 + 12, y4 + sz - 8, 1.4f, gc);
        Gfx.line(g, x0 + sz - 12, y4 + 8, x0 + sz - 12, y4 + sz - 8, 1.4f, gc);
        Gfx.line(g, x0 + 8, y4 + 12, x0 + sz - 8, y4 + 12, 1.4f, gc);
        Gfx.line(g, x0 + 8, y4 + sz - 12, x0 + sz - 8, y4 + sz - 12, 1.4f, gc);

        // indicii contextuale
        String hint = null;
        if (placing) hint = "Click pe hartă pentru a alege punctul locației (ESC: renunță)";
        else if (tab == T_ROAD) hint = road.hint();
        else if (tab == T_ZONE && dZone != null && !dZone.closed) hint = "Click: adaugă punct  ·  click pe primul punct (verde): închide  ·  click dreapta: șterge ultimul";
        else if (tab == T_ZONE && dZone != null) hint = "Trage punctele sau zona  ·  click pe + : punct nou  ·  click dreapta pe punct: șterge";
        else if (tab == T_LOC && dLoc != null) hint = dLoc.isNew ? "Click pe hartă mută punctul  ·  trage marcajul pentru a-l muta" : "Trage marcajul ca să muți locația, apoi SALVEAZĂ";
        if (hint != null) {
            float hw = Math.min(areaRight - 40f, Gfx.width(hint, 7.6f, true) + 28f);
            float hx = mapCx - hw / 2f;
            float hy = height - M - 28f;
            Sk.cut(g, hx, hy, hw, 24, 6, 0xF0081018);
            Gfx.rrect(g, hx, hy, 3, 24, 0, VIOLET);
            Gfx.text(g, clip(hint, 7.6f, true, hw - 20f), mapCx, hy + 15.5f, 7.6f, Sk.WHITE, 1, true, 0f);
        }
        if (placing && !overPanel(mx, my)) {
            Gfx.line(g, mx - 10, my, mx + 10, my, 1.4f, VIOLET);
            Gfx.line(g, mx, my - 10, mx, my + 10, 1.4f, VIOLET);
        }
        if (System.currentTimeMillis() < noteUntil) {
            float nw = Gfx.width(note, 8.6f, true) + 28f;
            float nx = mapCx - nw / 2f;
            Sk.cut(g, nx, 66, nw, 22, 6, 0xF0081018);
            Gfx.rrect(g, nx, 66, 3, 22, 0, noteOk ? Sk.GOOD : Sk.BAD);
            Gfx.text(g, note, mapCx, 81, 8.6f, Sk.WHITE, 1, true, 0f);
        }
    }

    // ------------------------------------------------------------------ panou

    private void drawPanel(GuiGraphics g, int mx, int my, float t) {
        float x = panelX;
        float y = M;
        float w = panelW;
        float h = height - 2 * M;
        Sk.cut(g, x - 1, y - 1, w + 2, h + 2, 17, 0x888B7BFF);
        Sk.cut(g, x, y, w, h, 16, 0xF4081018);
        hitF(x, y, w, h, () -> {});
        Gfx.text(g, "EDITOR HARTĂ", x + 18, y + 30, 14f, Sk.WHITE, 0, true, 0.6f);
        Gfx.text(g, pending ? "se salvează…" : "admin", x + w - 16, y + 28, 7.4f, pending ? Sk.WARN : VIOLET, 2);
        Gfx.rrect(g, x + 16, y + 38, w - 32, 2, 0, Sk.ACC);
        Gfx.rrect(g, x + w - 16 - (w - 32) * 0.22f, y + 38, (w - 32) * 0.22f, 2, 0, VIOLET);

        float tw = (w - 28f) / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            float tx = x + 14 + i * tw;
            boolean sel = tab == i;
            boolean hv = Sk.in(mx, my, tx, y + 48, tw - 4, 24);
            Sk.slant(g, tx, y + 48, tw - 4, 24, 5f, sel ? VIOLET : hv ? 0xFF22344D : Sk.ROW);
            Gfx.text(g, TABS[i], tx + (tw - 4) / 2f + 2, y + 63.5f, 6.6f, sel ? 0xFF0A0A22 : Sk.MUTED, 1, true, 0.4f);
            final int ti = i;
            hitF(tx, y + 48, tw - 4, 24, () -> setTab(ti));
        }

        float cx = x + 16;
        float cw = w - 32;
        float cy = y + 82;
        float bottom = y + h - 14;
        formLimit = bottom - 42f;
        switch (tab) {
            case T_LOC:
                if (dLoc == null) drawLocList(g, cx, cy, cw, bottom, mx, my);
                else drawLocForm(g, cx, cy, cw, bottom, mx, my);
                break;
            case T_ZONE:
                if (dZone == null) drawZoneList(g, cx, cy, cw, bottom, mx, my);
                else drawZoneForm(g, cx, cy, cw, bottom, mx, my);
                break;
            case T_ICON:
                drawIconTab(g, cx, cy, cw, bottom, mx, my);
                break;
            case T_ROAD:
                road.drawPanel(g, cx, cy, cw, bottom, mx, my);
                break;
            case T_CAT:
                if (dCat == null) drawCatList(g, cx, cy, cw, bottom, mx, my);
                else drawCatForm(g, cx, cy, cw, bottom, mx, my);
                break;
            default:
                drawLayerTab(g, cx, cy, cw, bottom, mx, my);
                break;
        }
    }

    private int visibleRows(float top, float bottomList) {
        return Math.max(1, (int) ((bottomList - top) / ROW));
    }

    private void drawLocList(GuiGraphics g, float x, float y, float w, float bottom, int mx, int my) {
        formLimit = Float.MAX_VALUE;
        fld(g, fSearch, x, y, w, 26, mx, my);
        float top = y + 34;
        float listBottom = bottom - 42f;
        int rows = visibleRows(top, listBottom);
        scrollLoc = Math.max(0, Math.min(scrollLoc, Math.max(0, locRows.size() - rows)));
        if (locRows.isEmpty()) Gfx.text(g, GpsData.locs.isEmpty() ? "Nicio locație încă" : "Nicio locație găsită", x + w / 2f, top + 28, 8.6f, Sk.MUTED, 1);
        for (int i = 0; i < rows; i++) {
            int idx = scrollLoc + i;
            if (idx >= locRows.size()) break;
            final GpsData.Loc l = locRows.get(idx);
            float ry = top + i * ROW;
            boolean hv = Sk.in(mx, my, x, ry, w, ROW - 3);
            Sk.slant(g, x, ry, w, ROW - 3, 5f, hv ? Sk.ROW_HOT : Sk.ROW);
            GpsIcons.badge(g, l.icon, x + 20, ry + (ROW - 3) / 2f, 8f, l.color, l.enabled ? 1f : 0.4f, false);
            Gfx.text(g, clip(l.name, 8.6f, true, w - 130f), x + 38, ry + 12, 8.6f, l.enabled ? Sk.WHITE : Sk.MUTED, 0, true, 0f);
            Gfx.text(g, clip(l.catName + (l.enabled ? "" : " · inactiv") + (l.hidden ? " · ascunsă" : ""), 6.8f, false, w - 130f), x + 38, ry + 22, 6.8f, Sk.FAINT, 0);
            Gfx.text(g, Math.round(l.x) + ", " + Math.round(l.z), x + w - 10, ry + 17, 6.8f, Sk.MUTED, 2);
            hitF(x, ry, w, ROW - 3, () -> {
                loadLoc(l);
                focusOn(l.x, l.z);
            });
        }
        Gfx.text(g, locRows.size() + " locații", x, listBottom + 12, 7f, Sk.FAINT, 0);
        float bw2 = (w - 6f) / 2f;
        float bw4 = (w - 18f) / 4f;
        btn(g, "+ PE HARTĂ", x, bottom - 34, bw4, 34, placing ? 4 : 0, mx, my, () -> {
            placing = true;
            say("Click pe hartă pentru a alege punctul.", true);
        });
        btn(g, "+ AICI", x + bw4 + 6f, bottom - 34, bw4, 34, 0, mx, my, this::newLocHere);
        btn(g, "IMPORTĂ", x + (bw4 + 6f) * 2f, bottom - 34, bw4, 34, impSearching ? 3 : 1, mx, my, this::startImport);
        btn(g, "EXPORT", x + (bw4 + 6f) * 3f, bottom - 34, bw4, 34, 1, mx, my, this::exportLocs);
    }

    /** Cate waypoint-uri intr-un mesaj catre server (serverul accepta pana la 300). */
    private static final int IMP_BATCH = 150;

    /** Cauta fisierele de waypoint-uri ale jucatorului; daca nu gaseste, incearca fisierul de pe server. */
    private void startImport() {
        if (impSearching || impQueue != null) return;
        impSearching = true;
        say("Caut fișiere de waypoint-uri…", true);
        Thread t = new Thread(() -> {
            final List<WaypointFiles.Source> found = WaypointFiles.find();
            Minecraft.getInstance().execute(() -> {
                impSearching = false;
                if (Minecraft.getInstance().screen != this) return;
                if (found.isEmpty()) {
                    // nimic local: ramane varianta veche, fisierul pus pe server
                    say("Niciun fișier local (XaeroWaypoints/ sau flowcity/waypoints.txt lângă joc); "
                        + "încerc fișierul de pe server…", false);
                    send("import.locs", new JsonObject());
                    return;
                }
                impSources = found;
            });
        }, "FlowCity-waypoints");
        t.setDaemon(true);
        t.start();
    }

    /** Porneste trimiterea waypoint-urilor dintr-un fisier ales. */
    private void beginImport(WaypointFiles.Source s) {
        impSources = null;
        impQueue = new ArrayList<>(s.entries);
        impLabel = s.label;
        impPart = 0;
        impParts = (impQueue.size() + IMP_BATCH - 1) / IMP_BATCH;
        sendImportBatch();
    }

    /** Trimite urmatorul pachet; serverul valideaza si raspunde, apoi continuam din {@link #onResult}. */
    private void sendImportBatch() {
        if (impQueue == null) return;
        if (impQueue.isEmpty()) {
            impQueue = null;
            return;
        }
        int n = Math.min(IMP_BATCH, impQueue.size());
        JsonArray items = new JsonArray();
        for (int i = 0; i < n; i++) {
            WaypointFiles.Entry e = impQueue.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("n", e.name);
            o.addProperty("x", e.x);
            o.addProperty("y", e.y);
            o.addProperty("z", e.z);
            if (e.color >= 0) o.addProperty("c", e.color);
            if (!e.label.isEmpty()) o.addProperty("l", e.label);
            if (e.disabled) o.addProperty("d", true);
            items.add(o);
        }
        impQueue.subList(0, n).clear();
        impPart++;
        JsonObject msg = new JsonObject();
        msg.add("items", items);
        msg.addProperty("part", impPart);
        msg.addProperty("parts", impParts);
        pending = false; // pachetele merg unul dupa altul, raspunsul tocmai a sosit
        send("loc.import", msg);
    }

    /** Selectorul de fisiere de waypoint-uri gasite pe calculatorul jucatorului. */
    private void drawImportPicker(GuiGraphics g, int mx, int my) {
        if (impSources == null) return;
        g.fill(0, 0, width, height, 0xB0040810);
        hitF(0, 0, width, height, () -> {});
        int rows = Math.min(impSources.size(), 8);
        float w = 380f;
        float h = 96f + rows * 30f;
        float x = (width - w) / 2f;
        float y = (height - h) / 2f;
        Sk.cut(g, x - 1, y - 1, w + 2, h + 2, 14, 0xAA7DBBFF);
        Sk.cut(g, x, y, w, h, 14, 0xF8081018);
        Gfx.text(g, "IMPORTĂ WAYPOINT-URI", x + 20, y + 28, 9f, Sk.ACC, 0, true, 1.4f);
        Gfx.text(g, "Alege fișierul de pe calculatorul tău:", x + 20, y + 48, 7.6f, Sk.MUTED, 0);
        float ry = y + 58;
        for (int i = 0; i < rows; i++) {
            final WaypointFiles.Source s = impSources.get(i);
            boolean hov = Sk.in(mx, my, x + 16, ry, w - 32, 28);
            Sk.cut(g, x + 16, ry, w - 32, 28, 8, hov ? 0xFF16293F : 0xFF0E1A28);
            Gfx.text(g, clip(s.label, 8f, false, w - 110f), x + 26, ry + 18, 8f, Sk.WHITE, 0);
            Gfx.text(g, s.entries.size() + " wp", x + w - 26, ry + 18, 7.6f, Sk.ACC, 2);
            hitF(x + 16, ry, w - 32, 28, () -> beginImport(s));
            ry += 30;
        }
        btn(g, "ANULEAZĂ", x + 20, y + h - 44, 140, 32, 1, mx, my, () -> impSources = null);
        btn(g, "DE PE SERVER", x + w - 180, y + h - 44, 160, 32, 1, mx, my, () -> {
            impSources = null;
            say("Se importă din gps/import/waypoints.txt…", true);
            send("import.locs", new JsonObject());
        });
    }

    private void drawLocForm(GuiGraphics g, float x, float y, float w, float bottom, int mx, int my) {
        final DLoc d = dLoc;
        btn(g, "ÎNAPOI", x, y, 84, 24, 1, mx, my, this::clearDrafts);
        Gfx.text(g, d.isNew ? "LOCAȚIE NOUĂ" : "EDITARE LOCAȚIE", x + w, y + 16, 8f, VIOLET, 2, true, 1f);
        y += 32;
        label(g, "NUME", x, y);
        fld(g, fName, x, y + 10, w, 22, mx, my);
        y += 38;
        label(g, "DESCRIERE", x, y);
        fld(g, fDesc, x, y + 10, w, 22, mx, my);
        y += 38;
        final List<GpsData.Cat> cs = GpsData.cats;
        selector(g, "CATEGORIE", catName(d.cat), x, y, w, mx, my, () -> d.cat = cycleCat(cs, d.cat, -1), () -> d.cat = cycleCat(cs, d.cat, 1));
        y += 38;
        // iconita + marime
        float half = (w - 10f) / 2f;
        if (y + 34f <= formLimit) {
            label(g, "ICONIȚĂ", x, y);
            String icon = d.iconRaw.isEmpty() ? catIcon(d.cat) : d.iconRaw;
            GpsIcons.badge(g, icon, x + 14, y + 22, 10f, GpsData.color(d.colorRaw, catColor(d.cat)), 1f, false);
            btn(g, d.iconRaw.isEmpty() ? "ALEGE" : "SCHIMBĂ", x + 32, y + 10, half - 32, 24, 1, mx, my, () -> {
                iconReturn = T_LOC;
                tab = T_ICON;
                scrollIcon = 0;
            });
            label(g, "MĂRIME " + d.size + "%", x + half + 10, y);
            btn(g, "-", x + half + 10, y + 10, 30, 24, 1, mx, my, () -> d.size = Math.max(50, d.size - 10));
            btn(g, "+", x + w - 30, y + 10, 30, 24, 1, mx, my, () -> d.size = Math.min(200, d.size + 10));
            Gfx.rrect(g, x + half + 44, y + 20, w - half - 78, 4, 2, 0xFF22344D);
            Gfx.rrect(g, x + half + 44, y + 20, (w - half - 78) * (d.size - 50) / 150f, 4, 2, Sk.ACC);
        }
        y += 42;
        label(g, "CULOARE", x, y);
        float used = swatches(g, x, y + 10, w, d.colorRaw, true, mx, my, h -> {
            d.colorRaw = h;
            setField(fHex, h);
        });
        y += 10 + used + 2;
        fld(g, fHex, x, y, half, 22, mx, my);
        y += 30;
        label(g, "X", x, y);
        label(g, "Z", x + half + 10, y);
        fld(g, fX, x, y + 10, half, 22, mx, my);
        fld(g, fZ, x + half + 10, y + 10, half, 22, mx, my);
        y += 38;
        if (y + 34f <= formLimit) {
            label(g, "Y (TELEPORTARE, OPȚIONAL)", x, y);
            label(g, "VIZIBIL DOAR CU PERMISIUNEA", x + half + 10, y);
            fld(g, fY, x, y + 10, half, 22, mx, my);
            fld(g, fPerm, x + half + 10, y + 10, half, 22, mx, my);
            y += 38;
        }
        toggle(g, "Activă", x, y, half, d.enabled, mx, my, () -> d.enabled = !d.enabled);
        toggle(g, "Ascunsă pe hartă", x + half + 10, y, half, d.hidden, mx, my, () -> d.hidden = !d.hidden);
        // butoane fixe jos
        float by = bottom - 34;
        if (d.isNew) {
            btn(g, "ADAUGĂ LOCAȚIA", x, by, w * 0.62f, 34, pending ? 3 : 0, mx, my, this::saveLoc);
            btn(g, "ANULEAZĂ", x + w * 0.62f + 6, by, w * 0.38f - 6, 34, 1, mx, my, this::clearDrafts);
        } else {
            btn(g, "SALVEAZĂ", x, by, w * 0.5f - 3, 34, pending ? 3 : 0, mx, my, this::saveLoc);
            btn(g, "ȘTERGE", x + w * 0.5f + 3, by, w * 0.5f - 3, 34, 2, mx, my, () -> confirm("Ștergi locația '" + fName.getValue().trim() + "'?", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", d.id);
                send("loc.del", o);
            }));
        }
    }

    private static String cycleCat(List<GpsData.Cat> cs, String cur, int dir) {
        if (cs.isEmpty()) return cur;
        int i = -1;
        for (int k = 0; k < cs.size(); k++) if (cs.get(k).id.equals(cur)) i = k;
        i = (i + dir + cs.size()) % cs.size();
        return cs.get(i).id;
    }

    private void drawZoneList(GuiGraphics g, float x, float y, float w, float bottom, int mx, int my) {
        formLimit = Float.MAX_VALUE;
        fld(g, fSearch, x, y, w, 26, mx, my);
        float top = y + 34;
        float listBottom = bottom - 42f;
        int rows = visibleRows(top, listBottom);
        scrollZone = Math.max(0, Math.min(scrollZone, Math.max(0, zoneRows.size() - rows)));
        if (zoneRows.isEmpty()) Gfx.text(g, GpsData.zones.isEmpty() ? "Nicio zonă încă" : "Nicio zonă găsită", x + w / 2f, top + 28, 8.6f, Sk.MUTED, 1);
        for (int i = 0; i < rows; i++) {
            int idx = scrollZone + i;
            if (idx >= zoneRows.size()) break;
            final GpsData.Zone z = zoneRows.get(idx);
            float ry = top + i * ROW;
            boolean hv = Sk.in(mx, my, x, ry, w, ROW - 3);
            Sk.slant(g, x, ry, w, ROW - 3, 5f, hv ? Sk.ROW_HOT : Sk.ROW);
            Gfx.rrect(g, x + 10, ry + 6, 14, 14, 3, z.enabled ? z.color : Gfx.fade(z.color, 0.35f));
            Gfx.text(g, clip(z.name, 8.6f, true, w - 120f), x + 34, ry + 12, 8.6f, z.enabled ? Sk.WHITE : Sk.MUTED, 0, true, 0f);
            Gfx.text(g, GpsData.typeLabel(z.type) + (z.enabled ? "" : " · inactivă"), x + 34, ry + 22, 6.8f, Sk.FAINT, 0);
            Gfx.text(g, z.px.length + " puncte", x + w - 10, ry + 17, 6.8f, Sk.MUTED, 2);
            hitF(x, ry, w, ROW - 3, () -> {
                loadZone(z);
                focusOn((z.minX + z.maxX) / 2.0, (z.minZ + z.maxZ) / 2.0);
            });
        }
        Gfx.text(g, zoneRows.size() + " zone", x, listBottom + 12, 7f, Sk.FAINT, 0);
        btn(g, "+ ZONĂ NOUĂ (DESENEAZĂ)", x, bottom - 34, w, 34, 0, mx, my, () -> {
            newZone();
            say("Click pe hartă pentru a marca punctele zonei.", true);
        });
    }

    private void drawZoneForm(GuiGraphics g, float x, float y, float w, float bottom, int mx, int my) {
        final DZone d = dZone;
        btn(g, "ÎNAPOI", x, y, 84, 24, 1, mx, my, this::clearDrafts);
        Gfx.text(g, d.isNew ? "ZONĂ NOUĂ" : "EDITARE ZONĂ", x + w, y + 16, 8f, VIOLET, 2, true, 1f);
        y += 32;
        label(g, "NUME", x, y);
        fld(g, fName, x, y + 10, w, 22, mx, my);
        y += 38;
        label(g, "DESCRIERE", x, y);
        fld(g, fDesc, x, y + 10, w, 22, mx, my);
        y += 38;
        selector(g, "TIP ZONĂ", GpsData.typeLabel(d.type), x, y, w, mx, my, () -> d.type = cycleType(d.type, -1), () -> d.type = cycleType(d.type, 1));
        y += 38;
        label(g, "CULOARE INTERIOR", x, y);
        float u1 = swatches(g, x, y + 10, w, d.colorRaw, true, mx, my, h -> d.colorRaw = h);
        y += 10 + u1 + 4;
        label(g, "CULOARE CONTUR", x, y);
        float u2 = swatches(g, x, y + 10, w, d.outlineRaw, true, mx, my, h -> d.outlineRaw = h);
        y += 10 + u2 + 4;
        if (y + 30f <= formLimit) {
            label(g, "TRANSPARENȚĂ " + d.alpha + "%", x, y);
            sliderX = x;
            sliderW = w;
            Gfx.rrect(g, x, y + 16, w, 6, 3, 0xFF22344D);
            float f = (d.alpha - 5) / 85f;
            int base = GpsData.color(d.colorRaw, GpsData.typeColor(d.type));
            Gfx.rrect(g, x, y + 16, Math.max(6f, w * f), 6, 3, base);
            Gfx.disc(g, x + w * f, y + 19, 6f, 0xFFFFFFFF);
            hitF(x - 4, y + 10, w + 8, 18, () -> {
                sliderDrag = true;
                alphaFrom((float) lastMx);
            });
        }
        y += 34;
        toggle(g, "Activă", x, y, w * 0.4f, d.enabled, mx, my, () -> d.enabled = !d.enabled);
        // unelte poligon
        if (y + 26f <= formLimit) {
            float bx = x + w * 0.4f + 6f;
            float bw = (w - w * 0.4f - 6f - 12f) / 3f;
            if (!d.closed) {
                btn(g, "ÎNCHIDE", bx, y - 1, bw, 24, d.pts.size() >= 3 ? 4 : 3, mx, my, () -> {
                    d.closed = true;
                    d.dirty = true;
                });
                btn(g, "ANULEAZĂ PUNCT", bx + bw + 6, y - 1, bw * 2 + 6, 24, d.pts.isEmpty() ? 3 : 1, mx, my, () -> {
                    d.pts.remove(d.pts.size() - 1);
                    d.dirty = true;
                });
            } else {
                btn(g, "-10%", bx, y - 1, bw, 24, 1, mx, my, () -> scaleZone(d, 0.9));
                btn(g, "+10%", bx + bw + 6, y - 1, bw, 24, 1, mx, my, () -> scaleZone(d, 1.1));
                btn(g, "REDESENEAZĂ", bx + 2 * bw + 12, y - 1, bw, 24, 1, mx, my, () -> {
                    d.pts.clear();
                    d.closed = false;
                    d.dirty = true;
                    say("Marchează punctele din nou.", true);
                });
            }
        }
        y += 30;
        if (y + 14f <= formLimit) {
            Gfx.text(g, d.pts.size() + " puncte  ·  arie ~" + Math.round(Math.abs(area(d))) + " blocuri²", x, y + 8, 7.4f, Sk.MUTED, 0);
        }
        float by = bottom - 34;
        if (d.isNew) {
            btn(g, "SALVEAZĂ ZONA", x, by, w * 0.62f, 34, pending || d.pts.size() < 3 ? 3 : 0, mx, my, this::saveZone);
            btn(g, "ANULEAZĂ", x + w * 0.62f + 6, by, w * 0.38f - 6, 34, 1, mx, my, this::clearDrafts);
        } else {
            btn(g, "SALVEAZĂ", x, by, w * 0.5f - 3, 34, pending ? 3 : 0, mx, my, this::saveZone);
            btn(g, "ȘTERGE", x + w * 0.5f + 3, by, w * 0.5f - 3, 34, 2, mx, my, () -> confirm("Ștergi zona '" + fName.getValue().trim() + "'?", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", d.id);
                send("zone.del", o);
            }));
        }
    }

    private static double area(DZone d) {
        double a = 0;
        int n = d.pts.size();
        for (int i = 0, k = n - 1; i < n; k = i++) a += d.pts.get(k)[0] * d.pts.get(i)[1] - d.pts.get(i)[0] * d.pts.get(k)[1];
        return a / 2.0;
    }

    private void scaleZone(DZone d, double f) {
        if (d.pts.isEmpty()) return;
        double cx = 0;
        double cz = 0;
        for (double[] p : d.pts) {
            cx += p[0];
            cz += p[1];
        }
        cx /= d.pts.size();
        cz /= d.pts.size();
        for (double[] p : d.pts) {
            p[0] = Math.round(cx + (p[0] - cx) * f);
            p[1] = Math.round(cz + (p[1] - cz) * f);
        }
        d.dirty = true;
    }

    private static String cycleType(String cur, int dir) {
        int i = 0;
        for (int k = 0; k < GpsData.ZONE_TYPES.length; k++) if (GpsData.ZONE_TYPES[k].equals(cur)) i = k;
        i = (i + dir + GpsData.ZONE_TYPES.length) % GpsData.ZONE_TYPES.length;
        return GpsData.ZONE_TYPES[i];
    }

    private void drawIconTab(GuiGraphics g, float x, float y, float w, float bottom, int mx, int my) {
        formLimit = Float.MAX_VALUE;
        String target = dLoc != null ? "locația " + clip(fName.getValue().trim().isEmpty() ? "nouă" : fName.getValue().trim(), 8f, true, 140f)
            : dCat != null ? "categoria " + clip(fName.getValue().trim().isEmpty() ? "nouă" : fName.getValue().trim(), 8f, true, 140f) : null;
        if (target != null) Gfx.text(g, "Aplică pe: " + target, x, y + 8, 8f, Sk.WHITE, 0, true, 0f);
        else Gfx.text(g, "Alege întâi o locație sau o categorie", x, y + 8, 8f, Sk.WARN, 0, true, 0f);
        y += 16;
        final List<String> filters = iconFilters();
        if (iconFilter >= filters.size()) iconFilter = 0;
        selector(g, "CATEGORIE ICONIȚE", filters.get(iconFilter), x, y, w, mx, my, () -> {
            iconFilter = (iconFilter - 1 + filters.size()) % filters.size();
            scrollIcon = 0;
        }, () -> {
            iconFilter = (iconFilter + 1) % filters.size();
            scrollIcon = 0;
        });
        y += 40;
        List<IconItem> shown = new ArrayList<>();
        String f = filters.get(iconFilter);
        for (IconItem it : iconItems) {
            if ("TOATE".equals(f) || ("PERSONALIZATE".equals(f) && it.custom) || f.equals(it.cat)) shown.add(it);
        }
        float tile = 56f;
        int cols = Math.max(1, (int) ((w + 6f) / (tile + 6f)));
        float listBottom = bottom - 42f;
        int rowsVis = Math.max(1, (int) ((listBottom - y) / (tile + 6f)));
        int total = shown.size() + (dLoc != null ? 1 : 0);
        int rowsTotal = (total + cols - 1) / cols;
        scrollIcon = Math.max(0, Math.min(scrollIcon, Math.max(0, rowsTotal - rowsVis)));
        for (int i = 0; i < rowsVis * cols; i++) {
            int idx = scrollIcon * cols + i;
            if (idx >= total) break;
            float tx = x + (i % cols) * (tile + 6f);
            float ty = y + (i / cols) * (tile + 6f);
            boolean implicit = dLoc != null && idx == 0;
            IconItem it = implicit ? null : shown.get(idx - (dLoc != null ? 1 : 0));
            boolean hv = Sk.in(mx, my, tx, ty, tile, tile);
            String curIcon = dLoc != null ? dLoc.iconRaw : dCat != null ? dCat.icon : "";
            boolean sel = implicit ? curIcon.isEmpty() : it.id.equals(curIcon);
            Sk.slant(g, tx, ty, tile, tile, 6f, sel ? 0xFF1B2E47 : hv ? Sk.ROW_HOT : Sk.ROW);
            if (sel) Gfx.rrect(g, tx + 6, ty + tile - 3, tile - 12, 2, 0, VIOLET);
            if (implicit) {
                Gfx.text(g, "IMPLICIT", tx + tile / 2f + 2, ty + 28, 6.8f, Sk.MUTED, 1, true, 0.4f);
                Gfx.text(g, "(categorie)", tx + tile / 2f + 2, ty + 40, 6f, Sk.FAINT, 1);
            } else {
                int col = dLoc != null ? GpsData.color(dLoc.colorRaw, catColor(dLoc.cat)) : dCat != null ? GpsData.color(dCat.color, 0xFF4DA3FF) : 0xFF4DA3FF;
                GpsIcons.badge(g, it.id, tx + tile / 2f + 2, ty + 22, 13f, col, 1f, false);
                Gfx.text(g, clip(it.name, 6.4f, true, tile - 6f), tx + tile / 2f + 2, ty + tile - 8, 6.4f, it.custom ? VIOLET : Sk.MUTED, 1, true, 0f);
            }
            final String pickId = implicit ? "" : it.id;
            hitF(tx, ty, tile, tile, () -> applyIcon(pickId));
        }
        Gfx.text(g, shown.size() + " iconițe (vectoriale, desenate de mod)", x, listBottom + 12, 6.8f, Sk.FAINT, 0);
        btn(g, "REÎNCARCĂ ICONIȚE DIN FIȘIER (icons.json)", x, bottom - 34, w, 34, pending ? 3 : 1, mx, my, () -> send("reload", new JsonObject()));
    }

    private void applyIcon(String id) {
        if (dLoc != null) {
            dLoc.iconRaw = id;
        } else if (dCat != null) {
            if (id.isEmpty()) return;
            dCat.icon = id;
        } else {
            say("Selectează întâi o locație sau o categorie.", false);
            return;
        }
        if (iconReturn >= 0) {
            tab = iconReturn;
            iconReturn = -1;
        }
    }

    private void drawCatList(GuiGraphics g, float x, float y, float w, float bottom, int mx, int my) {
        formLimit = Float.MAX_VALUE;
        fld(g, fSearch, x, y, w, 26, mx, my);
        float top = y + 34;
        float listBottom = bottom - 42f;
        int rows = visibleRows(top, listBottom);
        scrollCat = Math.max(0, Math.min(scrollCat, Math.max(0, catRows.size() - rows)));
        if (catRows.isEmpty()) Gfx.text(g, "Nicio categorie", x + w / 2f, top + 28, 8.6f, Sk.MUTED, 1);
        for (int i = 0; i < rows; i++) {
            int idx = scrollCat + i;
            if (idx >= catRows.size()) break;
            final GpsData.Cat c = catRows.get(idx);
            float ry = top + i * ROW;
            boolean hv = Sk.in(mx, my, x, ry, w, ROW - 3);
            Sk.slant(g, x, ry, w, ROW - 3, 5f, hv ? Sk.ROW_HOT : Sk.ROW);
            GpsIcons.badge(g, c.icon, x + 20, ry + (ROW - 3) / 2f, 8f, c.color, c.enabled ? 1f : 0.4f, false);
            Gfx.text(g, clip(c.name, 8.6f, true, w - 120f), x + 38, ry + 12, 8.6f, c.enabled ? Sk.WHITE : Sk.MUTED, 0, true, 0f);
            int n = 0;
            for (GpsData.Loc l : GpsData.locs) if (l.cat.equals(c.id)) n++;
            Gfx.text(g, (c.group.isEmpty() ? "fără grup" : c.group) + (c.enabled ? "" : " · ascunsă"), x + 38, ry + 22, 6.8f, Sk.FAINT, 0);
            Gfx.text(g, n + " locații", x + w - 10, ry + 17, 6.8f, Sk.MUTED, 2);
            hitF(x, ry, w, ROW - 3, () -> loadCat(c));
        }
        Gfx.text(g, catRows.size() + " categorii", x, listBottom + 12, 7f, Sk.FAINT, 0);
        btn(g, "+ CATEGORIE NOUĂ", x, bottom - 34, w, 34, 0, mx, my, this::newCat);
    }

    private void drawCatForm(GuiGraphics g, float x, float y, float w, float bottom, int mx, int my) {
        final DCat d = dCat;
        btn(g, "ÎNAPOI", x, y, 84, 24, 1, mx, my, this::clearDrafts);
        Gfx.text(g, d.isNew ? "CATEGORIE NOUĂ" : "EDITARE CATEGORIE", x + w, y + 16, 8f, VIOLET, 2, true, 1f);
        y += 32;
        label(g, "NUME", x, y);
        fld(g, fName, x, y + 10, w, 22, mx, my);
        y += 38;
        label(g, "GRUP (FILTRU ÎN PANOUL GPS)", x, y);
        fld(g, fGroup, x, y + 10, w, 22, mx, my);
        y += 38;
        float half = (w - 10f) / 2f;
        if (y + 34f <= formLimit) {
            label(g, "ICONIȚĂ", x, y);
            GpsIcons.badge(g, d.icon, x + 14, y + 22, 10f, GpsData.color(d.color, 0xFF4DA3FF), 1f, false);
            btn(g, "SCHIMBĂ", x + 32, y + 10, half - 32, 24, 1, mx, my, () -> {
                iconReturn = T_CAT;
                tab = T_ICON;
                scrollIcon = 0;
            });
        }
        y += 42;
        label(g, "CULOARE", x, y);
        float used = swatches(g, x, y + 10, w, d.color, false, mx, my, h -> {
            d.color = h;
            setField(fHex, h);
        });
        y += 10 + used + 2;
        fld(g, fHex, x, y, half, 22, mx, my);
        y += 32;
        toggle(g, "Afișată pe hartă și în listă", x, y, w, d.enabled, mx, my, () -> d.enabled = !d.enabled);
        float by = bottom - 34;
        if (d.isNew) {
            btn(g, "ADAUGĂ CATEGORIA", x, by, w * 0.62f, 34, pending ? 3 : 0, mx, my, this::saveCat);
            btn(g, "ANULEAZĂ", x + w * 0.62f + 6, by, w * 0.38f - 6, 34, 1, mx, my, this::clearDrafts);
        } else {
            btn(g, "SALVEAZĂ", x, by, w * 0.5f - 3, 34, pending ? 3 : 0, mx, my, this::saveCat);
            btn(g, "ȘTERGE", x + w * 0.5f + 3, by, w * 0.5f - 3, 34, 2, mx, my, () -> confirm("Ștergi categoria '" + fName.getValue().trim() + "'? (doar dacă nu are locații)", () -> {
                JsonObject o = new JsonObject();
                o.addProperty("id", d.id);
                send("cat.del", o);
            }));
        }
    }

    private void drawLayerTab(GuiGraphics g, float x, float y, float w, float bottom, int mx, int my) {
        formLimit = Float.MAX_VALUE;
        Gfx.text(g, "STRATURI HARTĂ", x, y + 8, 7.4f, Sk.ACC, 0, true, 1.4f);
        y += 16;
        float half = (w - 8f) / 2f;
        for (int i = 0; i < GpsScreen.LAYERS.length; i++) {
            final String id = GpsScreen.LAYERS[i][0];
            float tx = x + (i % 2) * (half + 8f);
            float ty = y + (i / 2) * 26f;
            toggle(g, GpsScreen.LAYERS[i][1], tx, ty, half, GpsPrefs.layerOn(id), mx, my, () -> GpsPrefs.toggleLayer(id));
        }
        y += ((GpsScreen.LAYERS.length + 1) / 2) * 26f + 8f;
        toggle(g, "Grilă pe hartă (G)", x, y, half, GpsPrefs.grid, mx, my, () -> GpsPrefs.grid = !GpsPrefs.grid);
        toggle(g, "Arată dezactivatele", x + half + 8f, y, half, GpsPrefs.showDisabled, mx, my, () -> GpsPrefs.showDisabled = !GpsPrefs.showDisabled);
        y += 36;
        Gfx.text(g, "SETĂRI HARTĂ (pentru toți jucătorii)", x, y + 8, 7.4f, Sk.ACC, 0, true, 1.4f);
        y += 18;
        Gfx.text(g, "Distanță de sosire: " + Math.round(GpsData.arrive) + " blocuri", x, y + 15, 8.2f, Sk.WHITE, 0, true, 0f);
        btn(g, "-", x + w - 66, y, 30, 22, 1, mx, my, () -> sendSetting("arrive", Math.max(2, GpsData.arrive - 2)));
        btn(g, "+", x + w - 30, y, 30, 22, 1, mx, my, () -> sendSetting("arrive", Math.min(100, GpsData.arrive + 2)));
        y += 28;
        Gfx.text(g, "Zoom implicit: " + String.format("%.1f", GpsData.defaultZoom), x, y + 15, 8.2f, Sk.WHITE, 0, true, 0f);
        btn(g, "-", x + w - 66, y, 30, 22, 1, mx, my, () -> sendSetting("zoom", Math.max(0.2, Math.round((GpsData.defaultZoom - 0.2) * 10.0) / 10.0)));
        btn(g, "+", x + w - 30, y, 30, 22, 1, mx, my, () -> sendSetting("zoom", Math.min(6, Math.round((GpsData.defaultZoom + 0.2) * 10.0) / 10.0)));
        y += 28;
        toggle(g, "Zone RP vizibile implicit", x, y, w, GpsData.showZonesDefault, mx, my, () -> {
            JsonObject o = new JsonObject();
            o.addProperty("showZones", !GpsData.showZonesDefault);
            send("settings.set", o);
        });
        y += 30;
        Gfx.text(g, "Datele se salvează pe server în plugins/FlowCityBridge/gps/ (cu backup automat).", x, y + 8, 6.6f, Sk.FAINT, 0);
        btn(g, "REÎNCARCĂ DIN FIȘIERE", x, bottom - 34, w, 34, pending ? 3 : 1, mx, my, () -> send("reload", new JsonObject()));
    }

    // ------------------------------------------------------------------ confirmare

    private void drawModal(GuiGraphics g, int mx, int my) {
        if (modalText == null) return;
        g.fill(0, 0, width, height, 0xB0040810);
        hitF(0, 0, width, height, () -> {});
        float w = 340f;
        float h = 124f;
        float x = (width - w) / 2f;
        float y = (height - h) / 2f;
        Sk.cut(g, x - 1, y - 1, w + 2, h + 2, 14, 0xAAE5484D);
        Sk.cut(g, x, y, w, h, 14, 0xF8081018);
        Gfx.text(g, "CONFIRMARE", x + 20, y + 28, 9f, Sk.BAD, 0, true, 1.4f);
        Gfx.text(g, clip(modalText, 9.4f, true, w - 40f), x + 20, y + 56, 9.4f, Sk.WHITE, 0, true, 0f);
        btn(g, "ANULEAZĂ", x + 20, y + h - 48, 140, 34, 1, mx, my, () -> {
            modalText = null;
            modalYes = null;
        });
        btn(g, modalYesLabel, x + w - 160, y + h - 48, 140, 34, 2, mx, my, () -> {
            Runnable r = modalYes;
            modalText = null;
            modalYes = null;
            if (r != null) r.run();
        });
    }
}
