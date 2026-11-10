package ro.solarstudios.flowcity.client.admin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.client.util.ScreenShotHelper;
import net.minecraft.util.ResourceLocation;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/** Client pentru eAdmin+ (modul "admx"): date joburi / IP, stare freeze, capturi de ecran (cerere + afisare). */
public final class AdminX extends JsonClientModule {
    private static AdminX instance;

    public static final class JobRow {
        public String job = "";
        public long xp;
        public double earned;
    }

    public static final class Other {
        public String name = "";
        public String ip = "";
    }

    public static final class IpRow {
        public String ip = "";
        public long ts;
    }

    // joburi
    public static String jobsUuid = "";
    public static String jobsCurrent = "";
    public static final List<JobRow> jobs = new ArrayList<>();
    // ip
    public static String ipsUuid = "";
    public static final List<IpRow> ipHistory = new ArrayList<>();
    public static final List<Other> ipOthers = new ArrayList<>();
    // freeze
    public static final Set<String> frozen = new HashSet<>();
    // SS primit
    public static String ssName = "";
    public static String ssUuid = "";
    public static int ssW;
    public static int ssH;
    public static ResourceLocation ssTex;
    public static long ssAt;
    public static String ssStatus = "Nicio captura.";
    private static String[] ssParts;
    private static int ssGot;
    private static DynamicTexture ssDyn;

    private AdminX() {
        on("jobs", d -> {
            jobsUuid = str(d, "uuid", "");
            jobsCurrent = str(d, "current", "");
            jobs.clear();
            if (d.has("rows")) {
                for (JsonElement e : d.getAsJsonArray("rows")) {
                    JsonObject o = e.getAsJsonObject();
                    JobRow r = new JobRow();
                    r.job = str(o, "job", "?");
                    r.xp = lng(o, "xp", 0);
                    r.earned = num(o, "earned", 0);
                    jobs.add(r);
                }
            }
        });
        on("ips", d -> {
            ipsUuid = str(d, "uuid", "");
            ipHistory.clear();
            ipOthers.clear();
            if (d.has("history")) {
                for (JsonElement e : d.getAsJsonArray("history")) {
                    IpRow r = new IpRow();
                    r.ip = str(e.getAsJsonObject(), "ip", "");
                    r.ts = lng(e.getAsJsonObject(), "ts", 0);
                    ipHistory.add(r);
                }
            }
            if (d.has("others")) {
                for (JsonElement e : d.getAsJsonArray("others")) {
                    Other o = new Other();
                    o.name = str(e.getAsJsonObject(), "name", "?");
                    o.ip = str(e.getAsJsonObject(), "ip", "");
                    ipOthers.add(o);
                }
            }
        });
        on("fz", d -> {
            if (bool(d, "on", false)) frozen.add(str(d, "uuid", ""));
            else frozen.remove(str(d, "uuid", ""));
        });
        on("ss_req", d -> capture());
        on("ss_chunk", d -> {
            int n = integer(d, "n", 0);
            int i = integer(d, "i", -1);
            if (n < 1 || n > 64 || i < 0 || i >= n) return;
            if (ssParts == null || ssParts.length != n || i == 0) {
                ssParts = new String[n];
                ssGot = 0;
            }
            if (ssParts[i] == null) ssGot++;
            ssParts[i] = str(d, "d", "");
            ssName = str(d, "name", "");
            ssUuid = str(d, "uuid", "");
            ssStatus = "Se primeste... " + ssGot + "/" + n;
            if (ssGot == n) assemble();
        });
    }

    @Override
    public String id() {
        return "admx";
    }

    @Override
    public void onLogout() {
        jobs.clear();
        ipHistory.clear();
        ipOthers.clear();
        frozen.clear();
        ssParts = null;
    }

    public static void register() {
        instance = new AdminX();
        ClientModules.register(instance);
    }

    // ------------------------------------------------------------------ cereri

    private static void req(String action, String uuid) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        instance.send(action, o);
    }

    public static void requestJobs(String uuid) {
        req("jobs", uuid);
    }

    public static void requestIps(String uuid) {
        req("ips", uuid);
    }

    public static void requestFrozen(String uuid) {
        req("fzq", uuid);
    }

    public static void freeze(String uuid) {
        req("freeze", uuid);
    }

    public static void spec(String uuid) {
        req("spec", uuid == null ? "" : uuid);
    }

    public static void inv(String uuid) {
        req("inv", uuid);
    }

    public static void requestSs(String uuid) {
        ssStatus = "Se asteapta captura...";
        ssParts = null;
        req("ss", uuid);
    }

    public static void cash(String uuid, String mode, double amount) {
        if (instance == null) return;
        JsonObject o = new JsonObject();
        o.addProperty("uuid", uuid);
        o.addProperty("mode", mode);
        o.addProperty("amount", amount);
        instance.send("cash", o);
    }

    public static void clearSs() {
        ssParts = null;
        ssW = 0;
        ssH = 0;
        ssName = "";
        ssStatus = "Nicio captura.";
        if (ssTex != null) {
            Minecraft.getInstance().getTextureManager().deleteTexture(ssTex);
            ssTex = null;
            ssDyn = null;
        }
    }

    // ------------------------------------------------------------------ captura (pe jucatorul cerut)

    private static void capture() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            try {
                int w = mc.getMainWindow().getFramebufferWidth();
                int h = mc.getMainWindow().getFramebufferHeight();
                NativeImage full = ScreenShotHelper.createScreenshot(w, h, mc.getFramebuffer());
                int tw = Math.min(w, 480);
                int th = Math.max(1, Math.round(h * (tw / (float) w)));
                byte[] png;
                while (true) {
                    NativeImage small = new NativeImage(tw, th, false);
                    full.resizeSubRectTo(0, 0, w, h, small);
                    png = small.getBytes();
                    small.close();
                    if (png.length <= 64 * 18000 || tw < 240) break;
                    tw = tw * 3 / 4;
                    th = Math.max(1, Math.round(h * (tw / (float) w)));
                }
                full.close();
                sendPng(png);
            } catch (Exception ex) {
                // captura esuata: nu trimitem nimic
            }
        });
    }

    private static void sendPng(byte[] png) {
        if (instance == null) return;
        final int chunk = 18000;
        int n = (png.length + chunk - 1) / chunk;
        if (n < 1 || n > 64) return;
        for (int i = 0; i < n; i++) {
            int from = i * chunk;
            int to = Math.min(png.length, from + chunk);
            byte[] part = java.util.Arrays.copyOfRange(png, from, to);
            JsonObject o = new JsonObject();
            o.addProperty("i", i);
            o.addProperty("n", n);
            o.addProperty("d", Base64.getEncoder().encodeToString(part));
            instance.send("ss_chunk", o);
        }
    }

    // ------------------------------------------------------------------ captura primita (pe staff)

    private static void assemble() {
        try {
            StringBuilder b = new StringBuilder();
            for (String p : ssParts) b.append(p);
            byte[] png = Base64.getDecoder().decode(b.toString().getBytes(StandardCharsets.US_ASCII));
            NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
            Minecraft mc = Minecraft.getInstance();
            mc.execute(() -> {
                if (ssTex != null) mc.getTextureManager().deleteTexture(ssTex);
                ssDyn = new DynamicTexture(img);
                ssTex = new ResourceLocation("flowcitycore", "dyn/ss");
                mc.getTextureManager().loadTexture(ssTex, ssDyn);
                ssW = img.getWidth();
                ssH = img.getHeight();
                ssAt = System.currentTimeMillis();
                ssStatus = "Captura de la " + ssName;
            });
        } catch (Exception e) {
            ssStatus = "Captura invalida.";
        } finally {
            ssParts = null;
        }
    }
}
