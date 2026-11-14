package ro.solarstudios.flowcity.clothes;

import com.google.gson.JsonObject;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.player.AbstractClientPlayerEntity;
import net.minecraft.client.network.play.NetworkPlayerInfo;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.NativeImage;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.ResourceLocation;
import org.lwjgl.opengl.GL11;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import ro.solarstudios.flowcity.module.ClientModules;
import ro.solarstudios.flowcity.module.JsonClientModule;

/**
 * Garderoba, partea de client: primeste ce poarta fiecare jucator, cere de la server PNG-ul tinutei, il inregistreaza ca textura
 * si il pune peste skin-ul jucatorului (in NetworkPlayerInfo). Fara tinuta, skin-ul original ramane neatins.
 */
public final class ClothesClient extends JsonClientModule {
    private static ClothesClient instance;
    private static final Map<UUID, String> LOOK = new HashMap<>();
    private static final Map<String, ResourceLocation> TEX = new HashMap<>();
    private static final Map<String, Boolean> SLIM = new HashMap<>();
    private static final Set<String> ASKED = new HashSet<>();
    private static final Map<UUID, Object[]> ORIGINAL = new HashMap<>();
    /** Piesele purtate de fiecare jucator (id-uri fara prefix). */
    private static final Map<UUID, List<String>> PIECES = new HashMap<>();
    /** Straturile PNG ale pieselor ("piece:id" -> imagine) si slotul lor, folosite la compunerea skin-ului. */
    private static final Map<String, NativeImage> PIECE_IMG = new HashMap<>();
    private static final Map<String, String> PIECE_SLOT = new HashMap<>();
    /** Skin-uri compuse (cheie: baza + piese) deja inregistrate ca textura. */
    private static final Map<String, ResourceLocation> COMBO = new HashMap<>();
    /** Ultima textura pusa de noi pentru fiecare jucator (ca sa deosebim un skin nou venit de la Mojang). */
    private static final Map<UUID, ResourceLocation> APPLIED = new HashMap<>();
    private static final java.util.List<String> SLOT_ORDER = java.util.Arrays.asList("pantaloni", "pantofi", "top", "fata", "cap", "accesoriu");
    private static Field texturesField;
    private static Field modelField;
    private static boolean reflectDone;

    private ClothesClient() {
        on("open", d -> Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof ClothesScreen) ((ClothesScreen) mc.screen).update(d);
            else mc.setScreen(new ClothesScreen(d));
        }));
        on("result", d -> Minecraft.getInstance().execute(() -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.screen instanceof ClothesScreen) ((ClothesScreen) mc.screen).result(bool(d, "ok", false), str(d, "msg", ""));
        }));
        on("look", d -> Minecraft.getInstance().execute(() -> {
            JsonObject m = d.has("look") && d.get("look").isJsonObject() ? d.getAsJsonObject("look") : new JsonObject();
            for (Map.Entry<String, com.google.gson.JsonElement> e : m.entrySet()) {
                try {
                    UUID u = UUID.fromString(e.getKey());
                    String id = e.getValue().getAsString();
                    if (id.isEmpty()) LOOK.remove(u);
                    else LOOK.put(u, id);
                } catch (IllegalArgumentException | UnsupportedOperationException ignored) {
                    // intrare invalida
                }
            }
            if (d.has("pieces") && d.get("pieces").isJsonObject()) {
                for (Map.Entry<String, com.google.gson.JsonElement> e : d.getAsJsonObject("pieces").entrySet()) {
                    try {
                        UUID u = UUID.fromString(e.getKey());
                        List<String> l = new ArrayList<>();
                        for (com.google.gson.JsonElement x : e.getValue().getAsJsonArray()) l.add(x.getAsString());
                        if (l.isEmpty()) PIECES.remove(u);
                        else PIECES.put(u, l);
                    } catch (IllegalArgumentException | IllegalStateException | UnsupportedOperationException ignored) {
                        // intrare invalida
                    }
                }
            }
        }));
        on("catalog", d -> Minecraft.getInstance().execute(() -> {
            if (!d.has("outfits") || !d.get("outfits").isJsonArray()) return;
            ClothesCatalog.OUTFITS.clear();
            for (com.google.gson.JsonElement e : d.getAsJsonArray("outfits")) {
                JsonObject o = e.getAsJsonObject();
                ClothesCatalog.OUTFITS.put(str(o, "id", ""), str(o, "name", ""));
            }
            if (d.has("pieces") && d.get("pieces").isJsonArray()) {
                ClothesCatalog.PIECES.clear();
                for (com.google.gson.JsonElement e : d.getAsJsonArray("pieces")) {
                    JsonObject o = e.getAsJsonObject();
                    ClothesCatalog.PIECES.put(str(o, "id", ""), str(o, "name", ""));
                }
            }
        }));
        on("tex", d -> Minecraft.getInstance().execute(() -> {
            String id = str(d, "id", "");
            try {
                byte[] png = Base64.getDecoder().decode(str(d, "png", ""));
                NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
                if (id.startsWith("piece:")) {
                    PIECE_IMG.put(id, img);
                    PIECE_SLOT.put(id, str(d, "slot", "accesoriu"));
                    return;
                }
                ResourceLocation rl = new ResourceLocation("flowcity", "clothes/" + safe(id));
                Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
                TEX.put(id, rl);
                SLIM.put(id, bool(d, "slim", false));
            } catch (Exception ex) {
                // PNG invalid: nu mai incercam la nesfarsit
            }
        }));
    }

    @Override
    public String id() {
        return "clothes";
    }

    public static void register() {
        instance = new ClothesClient();
        ClientModules.register(instance);
        MinecraftForge.EVENT_BUS.register(new Events());
    }

    static String safe(String id) {
        return id.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_");
    }

    static void send(String action, String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        if (instance != null) instance.send(action, o);
    }

    /** Click dreapta pe un articol de imbracaminte (apelat doar pe client). */
    public static void useItem(String id, boolean sneak) {
        if (sneak || id.isEmpty()) {
            refresh();
            return;
        }
        send("equip", id);
    }

    static void refresh() {
        if (instance != null) instance.send("refresh", new JsonObject());
    }

    private static Field find(String... names) {
        for (String n : names) {
            try {
                return ObfuscationReflectionHelper.findField(NetworkPlayerInfo.class, n);
            } catch (RuntimeException ignored) {
                // alt nume
            }
        }
        return null;
    }

    /** Citeste pixelii texturii (64x64) de pe GPU; null daca nu are dimensiunea asteptata. */
    private static NativeImage downloadBase(Minecraft mc, ResourceLocation rl) {
        try {
            mc.getTextureManager().bind(rl);
            int w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            int h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (w != 64 || h != 64) return null;
            NativeImage img = new NativeImage(64, 64, false);
            img.downloadTexture(0, false);
            return img;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** alpha-blend: peste "base" (ABGR) cu "ov" (ABGR). */
    private static int blend(int base, int ov) {
        int a = ov >>> 24;
        if (a == 0) return base;
        if (a == 255) return ov;
        int ba = base >>> 24;
        int r = ((ov & 255) * a + (base & 255) * (255 - a)) / 255;
        int g = (((ov >> 8) & 255) * a + ((base >> 8) & 255) * (255 - a)) / 255;
        int b = (((ov >> 16) & 255) * a + ((base >> 16) & 255) * (255 - a)) / 255;
        int oa = Math.max(ba, a);
        return (oa << 24) | (b << 16) | (g << 8) | r;
    }

    /** Skin-ul de baza + piesele (in ordinea slotului) ca textura noua; null daca lipsesc date (se cer de la server). */
    private static ResourceLocation composite(Minecraft mc, ResourceLocation base, List<String> pieces) {
        List<String> ids = new ArrayList<>();
        for (String p : pieces) ids.add("piece:" + p);
        boolean missing = false;
        for (String id : ids) {
            if (!PIECE_IMG.containsKey(id)) {
                missing = true;
                if (ASKED.add(id)) send("fetch", id);
            }
        }
        if (missing) return null;
        ids.sort((x, y) -> {
            int c = Integer.compare(SLOT_ORDER.indexOf(PIECE_SLOT.get(x)), SLOT_ORDER.indexOf(PIECE_SLOT.get(y)));
            return c != 0 ? c : x.compareTo(y);
        });
        String key = base + "|" + String.join(",", ids);
        ResourceLocation cached = COMBO.get(key);
        if (cached != null) return cached;
        NativeImage img = downloadBase(mc, base);
        if (img == null) return null;
        for (String id : ids) {
            NativeImage ov = PIECE_IMG.get(id);
            int w = Math.min(64, ov.getWidth());
            int h = Math.min(64, ov.getHeight());
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) img.setPixelRGBA(x, y, blend(img.getPixelRGBA(x, y), ov.getPixelRGBA(x, y)));
            }
        }
        if (COMBO.size() >= 48) {
            for (ResourceLocation old : COMBO.values()) mc.getTextureManager().release(old);
            COMBO.clear();
        }
        ResourceLocation rl = new ResourceLocation("flowcity", "clothes/combo_" + Integer.toHexString(key.hashCode()) + "_" + COMBO.size());
        mc.getTextureManager().register(rl, new DynamicTexture(img));
        COMBO.put(key, rl);
        return rl;
    }

    @SuppressWarnings("unchecked")
    private static void apply(Minecraft mc) {
        if (!reflectDone) {
            reflectDone = true;
            texturesField = find("field_187107_a", "textureLocations");
            modelField = find("field_178863_g", "skinModel");
        }
        if (texturesField == null || mc.getConnection() == null || mc.level == null) return;
        Set<UUID> online = new HashSet<>();
        for (PlayerEntity pl : mc.level.players()) {
            if (!(pl instanceof AbstractClientPlayerEntity)) continue;
            UUID u = pl.getUUID();
            online.add(u);
            NetworkPlayerInfo info = mc.getConnection().getPlayerInfo(u);
            if (info == null) continue;
            String id = LOOK.get(u);
            List<String> pcs = PIECES.get(u);
            boolean hasPieces = pcs != null && !pcs.isEmpty();
            try {
                Map<MinecraftProfileTexture.Type, ResourceLocation> map = (Map<MinecraftProfileTexture.Type, ResourceLocation>) texturesField.get(info);
                if (map == null) continue;
                ResourceLocation cur = map.get(MinecraftProfileTexture.Type.SKIN);
                if (id == null && !hasPieces) {
                    ResourceLocation ours = APPLIED.remove(u);
                    Object[] o = ORIGINAL.remove(u);
                    if (ours != null && ours.equals(cur) && o != null) {
                        if (o[0] != null) map.put(MinecraftProfileTexture.Type.SKIN, (ResourceLocation) o[0]);
                        if (modelField != null) modelField.set(info, o[1]);
                    }
                    continue;
                }
                // un skin care nu e al nostru (de ex. tocmai descarcat de la Mojang) devine noua baza
                if (cur != null && !cur.equals(APPLIED.get(u))) {
                    ORIGINAL.put(u, new Object[] {cur, modelField == null ? null : modelField.get(info)});
                }
                ResourceLocation want;
                String model = null;
                if (id != null) {
                    want = TEX.get(id);
                    if (want == null) {
                        if (ASKED.add(id)) send("fetch", id);
                        continue;
                    }
                    model = Boolean.TRUE.equals(SLIM.get(id)) ? "slim" : "default";
                } else {
                    Object[] o = ORIGINAL.get(u);
                    ResourceLocation base = o == null ? null : (ResourceLocation) o[0];
                    if (base == null) continue;
                    want = composite(mc, base, pcs);
                    if (want == null) continue;
                }
                if (want.equals(cur)) continue;
                map.put(MinecraftProfileTexture.Type.SKIN, want);
                APPLIED.put(u, want);
                if (model != null && modelField != null) modelField.set(info, model);
            } catch (IllegalAccessException | RuntimeException ex) {
                texturesField = null;
                return;
            }
        }
        ORIGINAL.keySet().retainAll(online);
        APPLIED.keySet().retainAll(online);
    }

    public static final class Events {
        private int tick;

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            if (++tick % 5 != 0) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || LOOK.isEmpty() && PIECES.isEmpty() && ORIGINAL.isEmpty()) return;
            apply(mc);
        }

        @SubscribeEvent
        public void onLogout(ClientPlayerNetworkEvent.LoggedOutEvent e) {
            LOOK.clear();
            PIECES.clear();
            ORIGINAL.clear();
            APPLIED.clear();
            ASKED.clear();
        }
    }
}
