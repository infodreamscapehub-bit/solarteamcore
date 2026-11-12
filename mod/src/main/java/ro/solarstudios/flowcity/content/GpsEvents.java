package ro.solarstudios.flowcity.content;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.particles.IParticleData;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Cu Cheia GPS in mana, jucatorul vede nodurile retelei din jur ca particule (sens unic = flacara + varf in directia sensului). */
public final class GpsEvents {
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || e.side.isClient()) return;
        PlayerEntity p = e.player;
        if (!(p instanceof ServerPlayerEntity) || p.ticksExisted % 10 != 0) return;
        if (!(p.getHeldItemMainhand().getItem() instanceof GpsWrench) && !(p.getHeldItemOffhand().getItem() instanceof GpsWrench)) return;
        if (!(p.world instanceof ServerWorld) || p.world.getDimensionKey() != net.minecraft.world.World.OVERWORLD) return;
        ServerWorld w = (ServerWorld) p.world;
        int n = 0;
        for (GpsNetwork.Node nd : GpsNetwork.near(p.getPosX(), p.getPosY(), p.getPosZ(), 24)) {
            if (n++ > 200) break;
            IParticleData part;
            switch (nd.type) {
                case GpsNetwork.ONEWAY: part = ParticleTypes.FLAME; break;
                case GpsNetwork.INTERSECTION: part = ParticleTypes.END_ROD; break;
                case GpsNetwork.TWOWAY: part = ParticleTypes.HAPPY_VILLAGER; break;
                default: part = ParticleTypes.COMPOSTER; break;
            }
            w.spawnParticle((ServerPlayerEntity) p, part, true, nd.x + 0.5, nd.y + 0.5, nd.z + 0.5, 1, 0, 0, 0, 0);
            if (nd.type == GpsNetwork.ONEWAY) {
                w.spawnParticle((ServerPlayerEntity) p, ParticleTypes.CRIT, true, nd.x + 0.5 + nd.dx * 0.6, nd.y + 0.5, nd.z + 0.5 + nd.dz * 0.6, 1, 0, 0, 0, 0);
            }
        }
    }
}
