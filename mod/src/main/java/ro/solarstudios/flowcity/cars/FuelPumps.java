package ro.solarstudios.flowcity.cars;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * Benzinarie: oferta (ce pompa si ce vehicul), alimentarea pe durata (nu instant) si raportarea catre plugin.
 *
 * <p>Fluxul complet: jucatorul da click dreapta pe pompa MrCrayfish (sau apasa tasta de interactiune intr-o zona de
 * benzinarie) -&gt; serverul verifica pozitia si vehiculul si retine o <b>oferta</b> -&gt; clientul deschide meniul si
 * trimite pluginului litrii + metoda de plata -&gt; pluginul ia banii si cheama {@code fccars fuelstart} -&gt; aici
 * pornim o <b>sesiune</b> care toarna benzina treptat si raporteaza pluginului cat a livrat
 * ({@code fcfuel report}). Daca sesiunea se intrerupe (jucatorul pleaca, vehiculul dispare, serverul se opreste),
 * raportul final spune cat s-a livrat, iar pluginul returneaza restul banilor.
 *
 * <p>Benzina MrCrayfish se masoara in "unitati": 1 litru = 1000 de unitati.
 */
public final class FuelPumps {
    private FuelPumps() {}

    /** Cat timp ramane valabila o oferta deschisa din meniu (ms). */
    private static final long OFFER_MS = 120_000L;
    /** Distanta maxima intre jucator si vehicul in timpul alimentarii. */
    private static final double MAX_DIST = 10.0;
    /**
     * Blocurile de pompa recunoscute. Implicit cele din MrCrayfish Vehicle Mod; pluginul poate trimite alta lista
     * din hype.yml (fuel.pump-blocks) prin {@code fccars pumpblocks}. Pe langa lista, se accepta si orice bloc din
     * spatiul "vehicle" al carui nume contine "pump" sau "fuel_drum" (acopera variantele de nume dintre versiuni).
     */
    private static final java.util.Set<String> PUMP_BLOCKS = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
        "vehicle:fuel_drum", "vehicle:gas_pump", "vehicle:gas_pump_tank",
        "vehicle:fluid_extractor", "vehicle:fluid_pump", "vehicle:industrial_fuel_drum"
    ));
    /** Daca lista vine din config, nu mai folosim potrivirea dupa tipar (adminul decide exact). */
    private static boolean fromConfig;

    /** Pluginul trimite lista din hype.yml. Lista goala = ramane cea implicita. */
    public static void setPumpBlocks(java.util.List<String> ids) {
        if (ids == null || ids.isEmpty()) return;
        PUMP_BLOCKS.clear();
        for (String s : ids) {
            String id = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (id.isEmpty()) continue;
            if (id.indexOf(':') < 0) id = "vehicle:" + id;
            PUMP_BLOCKS.add(id);
        }
        fromConfig = true;
    }

    /** Numele blocului la care se uita jucatorul si daca e recunoscut ca pompa (pentru diagnostic). */
    public static String lookingAt(ServerPlayer p) {
        net.minecraft.world.phys.HitResult r = p.pick(6.0D, 0.0F, false);
        if (!(r instanceof net.minecraft.world.phys.BlockHitResult)) return null;
        BlockPos pos = ((net.minecraft.world.phys.BlockHitResult) r).getBlockPos();
        ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(p.level().getBlockState(pos).getBlock());
        if (rl == null) return null;
        return rl + (isPump(p.level(), pos) ? " (recunoscut ca pompa)" : " (NU e in lista de pompe)");
    }

    /** O oferta: jucatorul a deschis meniul la o pompa, pentru un anume vehicul. */
    private static final class Offer {
        String pump;        // cheia pompei (statie sau bloc)
        String name;
        int entityId;
        long at;
    }

    /** O alimentare in curs: s-au luat banii, benzina curge treptat. */
    private static final class Session {
        UUID player;
        String pump;
        int entityId;
        int txId;
        float bought;       // unitati platite
        float delivered;    // unitati livrate pana acum
        float perTick;
        float reported;     // ultima valoare raportata pluginului
        int sinceReport;
    }

    private static final Map<UUID, Offer> offers = new HashMap<>();
    private static final Map<UUID, Session> sessions = new HashMap<>();
    private static final Map<String, UUID> busy = new HashMap<>();

    // ------------------------------------------------------------------ oferte

    private static boolean isPump(Level w, BlockPos pos) {
        ResourceLocation rl = ForgeRegistries.BLOCKS.getKey(w.getBlockState(pos).getBlock());
        if (rl == null) return false;
        String id = rl.toString();
        if (PUMP_BLOCKS.contains(id)) return true;
        if (fromConfig) return false;
        return "vehicle".equals(rl.getNamespace())
            && (rl.getPath().contains("pump") || rl.getPath().contains("fuel_drum"));
    }

    /** Vehiculul pe care il alimentam: cel pe care il conduce jucatorul, altfel cel mai apropiat vehicul cu rezervor. */
    private static Entity vehicleFor(ServerPlayer p, int wantedId) {
        if (wantedId > 0) {
            Entity e = p.level().getEntity(wantedId);
            if (e != null && e.isAlive() && e.distanceTo(p) <= MAX_DIST && CarsServer.hasTank(e)) return e;
        }
        Entity root = p.getRootVehicle();
        if (root != p && CarsServer.hasTank(root)) return root;
        Vec3 c = p.position();
        AABB box = new AABB(c.x - 7, c.y - 4, c.z - 7, c.x + 7, c.y + 4, c.z + 7);
        Entity best = null;
        double bd = Double.MAX_VALUE;
        for (Entity e : p.level().getEntities(null, box)) {
            if (!e.isAlive() || !CarsServer.hasTank(e)) continue;
            double d = e.distanceToSqr(c);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    /** Deschide meniul de alimentare pentru jucator (oferta + pachet catre client). Intoarce motivul refuzului sau null. */
    public static String open(ServerPlayer p, String pump, String name, int wantedId) {
        if (sessions.containsKey(p.getUUID())) return "Alimentezi deja la o pompa.";
        UUID who = busy.get(pump);
        if (who != null && !who.equals(p.getUUID())) return "Pompa este ocupata de alt jucator.";
        Entity v = vehicleFor(p, wantedId);
        if (v == null) return "Nu e niciun vehicul cu rezervor langa pompa.";
        float cap = CarsServer.fuelCapacity(v);
        if (cap <= 0) return "Vehiculul nu are rezervor.";
        float cur = CarsServer.currentFuel(v);
        Offer o = new Offer();
        o.pump = pump;
        o.name = name;
        o.entityId = v.getId();
        o.at = System.currentTimeMillis();
        offers.put(p.getUUID(), o);
        com.google.gson.JsonObject j = new com.google.gson.JsonObject();
        j.addProperty("name", name);
        j.addProperty("entity", v.getId());
        j.addProperty("vehicle", v.getType().getDescription().getString());
        j.addProperty("cap", cap / 1000f);
        j.addProperty("cur", cur / 1000f);
        CarsNet.reply(p, "fuel", j.toString());
        return null;
    }

    /** Oferta facuta la o zona de benzinarie FlowCity (tasta de interactiune). */
    static void openStation(ServerPlayer p, GarageData.Station st, int wantedId) {
        String err = open(p, "st" + st.id, st.name, wantedId);
        if (err != null) CarsServer.tell(p, err);
    }

    // ------------------------------------------------------------------ sesiuni

    /**
     * Porneste alimentarea dupa ce pluginul a incasat banii.
     *
     * @param units unitati platite (1 L = 1000)
     * @param seconds durata alimentarii
     * @return true daca sesiunea a pornit
     */
    public static boolean start(ServerPlayer p, int units, int txId, int seconds) {
        UUID u = p.getUUID();
        if (units <= 0 || sessions.containsKey(u)) return false;
        Offer o = offers.get(u);
        if (o == null || System.currentTimeMillis() - o.at > OFFER_MS) {
            offers.remove(u);
            return false;
        }
        UUID who = busy.get(o.pump);
        if (who != null && !who.equals(u)) return false;
        Entity v = p.level().getEntity(o.entityId);
        if (v == null || !v.isAlive() || v.distanceTo(p) > MAX_DIST) return false;
        float free = CarsServer.fuelCapacity(v) - CarsServer.currentFuel(v);
        if (free <= 1f) return false;
        Session s = new Session();
        s.player = u;
        s.pump = o.pump;
        s.entityId = o.entityId;
        s.txId = txId;
        s.bought = units;
        s.perTick = units / Math.max(20f, seconds * 20f);
        sessions.put(u, s);
        busy.put(o.pump, u);
        offers.remove(u);
        progress(p, s, false);
        return true;
    }

    private static void progress(ServerPlayer p, Session s, boolean done) {
        com.google.gson.JsonObject j = new com.google.gson.JsonObject();
        j.addProperty("tx", s.txId);
        j.addProperty("bought", s.bought / 1000f);
        j.addProperty("done", s.delivered / 1000f);
        j.addProperty("end", done);
        CarsNet.reply(p, "fuelprog", j.toString());
    }

    /** Raporteaza pluginului cat s-a livrat; la final pluginul returneaza diferenta. */
    private static void report(MinecraftServer server, String playerName, Session s, boolean done) {
        s.reported = s.delivered;
        s.sinceReport = 0;
        String cmd = "fcfuel report " + playerName + " " + s.txId + " " + Math.round(s.delivered) + " " + (done ? 1 : 0);
        try {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), cmd);
        } catch (RuntimeException ex) {
            // pluginul lipseste sau comanda nu a putut fi rulata: alimentarea continua, banii raman incasati
        }
    }

    private static void stop(MinecraftServer server, Session s, String reason) {
        sessions.remove(s.player);
        busy.remove(s.pump);
        ServerPlayer p = server.getPlayerList().getPlayer(s.player);
        String name = p != null ? p.getGameProfile().getName() : null;
        if (name == null) {
            com.mojang.authlib.GameProfile gp = server.getProfileCache().get(s.player).orElse(null);
            name = gp != null ? gp.getName() : null;
        }
        if (name != null) report(server, name, s, true);
        if (p != null) {
            progress(p, s, true);
            if (reason != null) CarsServer.tell(p, reason);
        }
    }

    /** Oprire la inchiderea serverului: raportam cat s-a livrat ca sa se returneze restul. */
    public static void shutdown(MinecraftServer server) {
        for (Session s : new ArrayList<>(sessions.values())) stop(server, s, null);
        sessions.clear();
        busy.clear();
        offers.clear();
    }

    static void tick(MinecraftServer server) {
        if (sessions.isEmpty()) return;
        List<Session> list = new ArrayList<>(sessions.values());
        for (Session s : list) {
            ServerPlayer p = server.getPlayerList().getPlayer(s.player);
            if (p == null) {
                stop(server, s, null);
                continue;
            }
            Entity v = p.level().getEntity(s.entityId);
            if (v == null || !v.isAlive()) {
                stop(server, s, "Vehiculul a disparut: alimentarea s-a oprit.");
                continue;
            }
            if (v.distanceTo(p) > MAX_DIST) {
                stop(server, s, "Te-ai indepartat de pompa: alimentarea s-a oprit.");
                continue;
            }
            float left = s.bought - s.delivered;
            float inc = Math.min(left, s.perTick);
            float free = CarsServer.fuelCapacity(v) - CarsServer.currentFuel(v);
            if (free <= 0.5f) {
                stop(server, s, "Rezervorul este plin.");
                continue;
            }
            inc = Math.min(inc, free);
            if (inc > 0 && !CarsServer.addFuelUnits(v, inc)) {
                stop(server, s, "Pompa nu poate alimenta acest vehicul.");
                continue;
            }
            s.delivered += inc;
            s.sinceReport++;
            if (s.delivered >= s.bought - 0.5f) {
                stop(server, s, null);
                continue;
            }
            if (s.sinceReport >= 20) report(server, p.getGameProfile().getName(), s, false);
            if (s.sinceReport % 4 == 0) progress(p, s, false);
        }
    }

    // ------------------------------------------------------------------ evenimente

    public static final class PumpEvents {
        @SubscribeEvent
        public void onRightClick(PlayerInteractEvent.RightClickBlock e) {
            if (e.getLevel().isClientSide() || !(e.getEntity() instanceof ServerPlayer)) return;
            ServerPlayer p = (ServerPlayer) e.getEntity();
            if (!isPump(e.getLevel(), e.getPos())) return;
            BlockPos b = e.getPos();
            String err = open(p, b.getX() + ":" + b.getY() + ":" + b.getZ(), "Benzinarie", 0);
            if (err != null) CarsServer.tell(p, err);
            e.setCanceled(true);
            e.setCancellationResult(net.minecraft.world.InteractionResult.SUCCESS);
        }

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null) tick(server);
        }

        @SubscribeEvent
        public void onStopping(net.minecraftforge.event.server.ServerStoppingEvent e) {
            shutdown(e.getServer());
        }
    }
}
