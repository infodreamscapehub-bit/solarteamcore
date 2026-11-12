package ro.solarstudios.flowcity.vehicle;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Comenzi de server pentru vehicule (nivel 2, folosite si de pluginul bridge dupa plata):
 * /fcveh fuel &lt;uuid-masina&gt; &lt;litri&gt;, /fcveh fuelcan &lt;jucator&gt; &lt;litri&gt;,
 * /fcveh key &lt;jucator&gt; (cheie dublura pentru masina in care sta jucatorul), /fcveh variant &lt;jucator&gt; &lt;0-11&gt;.
 */
public final class VehicleCommand {
    @SubscribeEvent
    public void onRegister(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("fcveh").requires(s -> s.hasPermission(2))
            .then(Commands.literal("fuel")
                .then(Commands.argument("car", StringArgumentType.word())
                    .then(Commands.argument("liters", FloatArgumentType.floatArg(0, 200)).executes(c -> {
                        CarEntity car = find(c.getSource(), StringArgumentType.getString(c, "car"));
                        if (car == null) {
                            c.getSource().sendFailure(Component.literal("Masina nu a fost gasita."));
                            return 0;
                        }
                        car.setFuel(car.getFuel() + FloatArgumentType.getFloat(c, "liters"));
                        c.getSource().sendSuccess(() -> Component.literal("Rezervor: " + Math.round(car.getFuel()) + " L"), false);
                        return 1;
                    }))))
            .then(Commands.literal("fuelcan")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("liters", FloatArgumentType.floatArg(0, 50)).executes(c -> {
                        ServerPlayer p = EntityArgument.getPlayer(c, "player");
                        ItemStack st = p.getMainHandItem();
                        if (!(st.getItem() instanceof JerryCanItem)) {
                            c.getSource().sendFailure(Component.literal("Jucatorul nu tine o canistra."));
                            return 0;
                        }
                        JerryCanItem.setFuel(st, JerryCanItem.fuel(st) + FloatArgumentType.getFloat(c, "liters"));
                        return 1;
                    }))))
            .then(Commands.literal("key")
                .then(Commands.argument("player", EntityArgument.player()).executes(c -> {
                    ServerPlayer p = EntityArgument.getPlayer(c, "player");
                    if (!(p.getVehicle() instanceof CarEntity car)) {
                        c.getSource().sendFailure(Component.literal("Jucatorul nu e intr-o masina."));
                        return 0;
                    }
                    ItemStack key = car.makeKey();
                    if (!p.getInventory().add(key)) p.drop(key, false);
                    return 1;
                })))
            .then(Commands.literal("variant")
                .then(Commands.argument("player", EntityArgument.player())
                    .then(Commands.argument("variant", IntegerArgumentType.integer(0, VehicleTypes.TYPES.length - 1)).executes(c -> {
                        ServerPlayer p = EntityArgument.getPlayer(c, "player");
                        if (!(p.getVehicle() instanceof CarEntity car)) return 0;
                        car.setVariant(IntegerArgumentType.getInteger(c, "variant"));
                        return 1;
                    })))));
    }

    private static CarEntity find(CommandSourceStack src, String id) {
        UUID u;
        try {
            u = UUID.fromString(id);
        } catch (IllegalArgumentException ex) {
            return null;
        }
        for (ServerLevel l : src.getServer().getAllLevels()) {
            Entity e = l.getEntity(u);
            if (e instanceof CarEntity car) return car;
        }
        return null;
    }
}
