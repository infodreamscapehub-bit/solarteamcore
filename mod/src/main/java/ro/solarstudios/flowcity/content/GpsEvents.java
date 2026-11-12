package ro.solarstudios.flowcity.content;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Cu Cheia GPS in mana se vad nodurile si legaturile din jur (verde = dublu sens, flacara = sens unic). Blocurile GPS se sparg doar cu cheia in mana. */
public final class GpsEvents {
    @SubscribeEvent
    public void onBreak(BlockEvent.BreakEvent e) {
        if (e.getLevel().isClientSide() || !GpsWrench.isGps(e.getState())) return;
        Player p = e.getPlayer();
        if (p == null) return;
        boolean wrench = p.getMainHandItem().getItem() instanceof GpsWrench || p.getOffhandItem().getItem() instanceof GpsWrench;
        if (!wrench || !(p.hasPermissions(2) || p.getAbilities().instabuild)) {
            e.setCanceled(true);
            if (p.hasPermissions(2)) p.displayClientMessage(Component.literal("\u00a7cTine Cheia GPS in mana ca sa spargi un bloc GPS."), true);
        }
    }

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient()) return;
        Player p = e.player;
        if (!(p instanceof ServerPlayer) || p.tickCount % 10 != 0) return;
        if (!(p.getMainHandItem().getItem() instanceof GpsWrench) && !(p.getOffhandItem().getItem() instanceof GpsWrench)) return;
        if (!(p.level() instanceof ServerLevel) || p.level().dimension() != Level.OVERWORLD) return;
        ServerLevel w = (ServerLevel) p.level();
        int n = 0;
        for (GpsNetwork.Node nd : GpsNetwork.near(p.getX(), p.getY(), p.getZ(), 24)) {
            if (n++ > 200) break;
            ParticleOptions part;
            switch (nd.type) {
                case GpsNetwork.ONEWAY: part = ParticleTypes.FLAME; break;
                case GpsNetwork.INTERSECTION: part = ParticleTypes.END_ROD; break;
                case GpsNetwork.TWOWAY: part = ParticleTypes.HAPPY_VILLAGER; break;
                default: part = ParticleTypes.COMPOSTER; break;
            }
            w.sendParticles((ServerPlayer) p, part, true, nd.x + 0.5, nd.y + 0.5, nd.z + 0.5, 1, 0, 0, 0, 0);
            if (nd.type == GpsNetwork.ONEWAY) {
                w.sendParticles((ServerPlayer) p, ParticleTypes.CRIT, true, nd.x + 0.5 + nd.dx * 0.6, nd.y + 0.5, nd.z + 0.5 + nd.dz * 0.6, 1, 0, 0, 0, 0);
            }
            // legaturi: dublu sens = verde (o data pe pereche), sens unic = flacari care se aglomereaza spre tinta
            for (long k : nd.out) {
                GpsNetwork.Node o = GpsNetwork.byKey(k);
                if (o == null) continue;
                boolean bi = o.out.contains(nd.key());
                if (bi && nd.key() > k) continue;
                double dx = o.x - nd.x;
                double dy = o.y - nd.y;
                double dz = o.z - nd.z;
                int steps = (int) Math.min(48, Math.max(2, Math.sqrt(dx * dx + dy * dy + dz * dz)));
                for (int i = 1; i < steps; i++) {
                    double r = i / (double) steps;
                    if (!bi) r = r * r * 0.4 + r * 0.6;
                    w.sendParticles((ServerPlayer) p, bi ? ParticleTypes.HAPPY_VILLAGER : ParticleTypes.SOUL_FIRE_FLAME, true,
                        nd.x + 0.5 + dx * r, nd.y + 0.25 + dy * r, nd.z + 0.5 + dz * r, 1, 0, 0, 0, 0);
                }
            }
        }
    }
}
