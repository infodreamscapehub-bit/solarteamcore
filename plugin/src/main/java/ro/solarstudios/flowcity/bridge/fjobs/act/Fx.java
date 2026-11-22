package ro.solarstudios.flowcity.bridge.fjobs.act;

import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/** Sunete si particule dupa nume (configurabile); un nume invalid e ignorat in loc sa strice jobul. */
public final class Fx {
    private Fx() {}

    public static void sound(Player p, String name, float vol, float pitch) {
        if (p == null || name == null || name.isEmpty()) return;
        try {
            p.playSound(p.getLocation(), Sound.valueOf(name), vol, pitch);
        } catch (IllegalArgumentException ignored) {
            // nume necunoscut
        }
    }

    public static void sound(Player p, String name) {
        sound(p, name, 0.8f, 1f);
    }

    /** Particule vazute doar de jucator. */
    public static void particle(Player p, String name, Location at, int count, double spread) {
        if (p == null || at == null || at.getWorld() == null) return;
        try {
            p.spawnParticle(Particle.valueOf(name), at, count, spread, spread, spread, 0.01);
        } catch (IllegalArgumentException ignored) {
            // nume necunoscut sau necesita date suplimentare
        }
    }

    /** Particule vazute de toti cei din jur. */
    public static void particleAll(Location at, String name, int count, double spread) {
        if (at == null || at.getWorld() == null) return;
        try {
            at.getWorld().spawnParticle(Particle.valueOf(name), at, count, spread, spread, spread, 0.01);
        } catch (IllegalArgumentException ignored) {
            // nume necunoscut
        }
    }

    /** Mesaj scurt deasupra hotbar-ului. */
    public static void bar(Player p, String text) {
        if (p == null || text == null) return;
        p.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new net.md_5.bungee.api.chat.TextComponent(text));
    }

    public static void title(Player p, String title, String sub) {
        if (p != null) p.sendTitle(title == null ? "" : title, sub == null ? "" : sub, 5, 35, 10);
    }
}
