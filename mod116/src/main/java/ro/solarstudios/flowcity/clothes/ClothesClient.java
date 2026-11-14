package ro.solarstudios.flowcity.clothes;

import com.google.gson.JsonObject;
import com.mojang.authlib.minecraft.MinecraftProfileTexture;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.Base64;
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
        }));
        on("tex", d -> Minecraft.getInstance().execute(() -> {
            String id = str(d, "id", "");
            try {
                byte[] png = Base64.getDecoder().decode(str(d, "png", ""));
                NativeImage img = NativeImage.read(new ByteArrayInputStream(png));
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
            try {
                Map<MinecraftProfileTexture.Type, ResourceLocation> map = (Map<MinecraftProfileTexture.Type, ResourceLocation>) texturesField.get(info);
                if (map == null) continue;
                if (id == null) {
                    Object[] o = ORIGINAL.remove(u);
                    if (o != null) {
                        if (o[0] != null) map.put(MinecraftProfileTexture.Type.SKIN, (ResourceLocation) o[0]);
                        if (modelField != null) modelField.set(info, o[1]);
                    }
                    continue;
                }
                ResourceLocation rl = TEX.get(id);
                if (rl == null) {
                    if (ASKED.add(id)) send("fetch", id);
                    continue;
                }
                ResourceLocation cur = map.get(MinecraftProfileTexture.Type.SKIN);
                if (rl.equals(cur)) continue;
                if (!ORIGINAL.containsKey(u)) ORIGINAL.put(u, new Object[] {cur, modelField == null ? null : modelField.get(info)});
                map.put(MinecraftProfileTexture.Type.SKIN, rl);
                if (modelField != null) modelField.set(info, Boolean.TRUE.equals(SLIM.get(id)) ? "slim" : "default");
            } catch (IllegalAccessException | RuntimeException ex) {
                texturesField = null;
                return;
            }
        }
        ORIGINAL.keySet().retainAll(online);
    }

    public static final class Events {
        private int tick;

        @SubscribeEvent
        public void onTick(TickEvent.ClientTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            if (++tick % 5 != 0) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null || LOOK.isEmpty() && ORIGINAL.isEmpty()) return;
            apply(mc);
        }

        @SubscribeEvent
        public void onLogout(ClientPlayerNetworkEvent.LoggedOutEvent e) {
            LOOK.clear();
            ORIGINAL.clear();
            ASKED.clear();
        }
    }
}
