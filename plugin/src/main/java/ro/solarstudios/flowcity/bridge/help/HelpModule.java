package ro.solarstudios.flowcity.bridge.help;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * /help (si /ajutor, /?): lista frumoasa a comenzilor FlowCity, pe categorii, doar cele la care ai acces. Click pe o comanda
 * o scrie in chat, iar mouse-ul deasupra arata detalii. /help <categorie> arata doar o categorie.
 */
public final class HelpModule extends JsonModule {
    private static final class Cmd {
        final String usage;
        final String desc;
        final String perm;

        Cmd(String usage, String desc, String perm) {
            this.usage = usage;
            this.desc = desc;
            this.perm = perm;
        }
    }

    private static final class Cat {
        final String key;
        final String title;
        final String color;
        final List<Cmd> cmds = new ArrayList<>();

        Cat(String key, String title, String color) {
            this.key = key;
            this.title = title;
            this.color = color;
        }

        Cat add(String usage, String desc, String perm) {
            cmds.add(new Cmd(usage, desc, perm));
            return this;
        }
    }

    private final List<Cat> cats = new ArrayList<>();

    @Override
    public String id() {
        return "help";
    }

    @Override
    protected void onEnable() {
        cats.add(new Cat("bani", "BANI ȘI PLĂȚI", "§6")
            .add("/atm deposit <sumă>", "Depui cash-ul în banca ta", "flowcity.hype.atm")
            .add("/atm withdraw <sumă>", "Retragi bani ca bancnote în inventar", "flowcity.hype.atm")
            .add("/atm transfer <jucător> <sumă>", "Trimiți bani altui jucător", "flowcity.hype.atm")
            .add("/amenzi", "Îți vezi și plătești amenzile (cash sau card)", null));
        cats.add(new Cat("joburi", "JOBURI", "§a")
            .add("/job", "Deschide meniul de joburi", "flowcity.hype.job")
            .add("/job select <id>", "Alegi un job", "flowcity.hype.job")
            .add("/job leave", "Părăsești jobul curent", "flowcity.hype.job"));
        cats.add(new Cat("personaj", "PERSONAJ ȘI TELEFON", "§b")
            .add("/id <jucător|număr>", "Vezi ID-ul unui jucător", null)
            .add("/sete", "Cât de sete îți e (0-100)", null)
            .add("/hud [fundal|craciun]", "Pornește/oprește HUD-ul sau schimbă aspectul", null)
            .add("/telefon", "Folosește telefonul", "flowcity.phone")
            .add("/notificari [sterge]", "Istoricul notificărilor tale", null)
            .add("/roata", "Roata norocului", "flowcity.hype.wheel"));
        cats.add(new Cat("politie", "POLIȚIE", "§9")
            .add("/mdt", "Deschide tableta MDT a poliției", "flowcity.mdt.use"));
        cats.add(new Cat("taxi", "TAXI", "§e")
            .add("/taxi", "Ecranul de taxi (cheamă un taxi, panoul șoferului)", null)
            .add("/taxi call [mesaj]", "Cheamă un taxi la locația ta", null)
            .add("/taxi cancel", "Anulează cererea de taxi", null)
            .add("/taxi duty [on|off]", "Intri/ieși de la datorie ca taximetrist", "flowcity.taxi.driver")
            .add("/taxi accept <jucător>", "Accepți o cerere de taxi", "flowcity.taxi.driver")
            .add("/taxi start | stop", "Pornești / oprești cursa (taximetrul)", "flowcity.taxi.driver")
            .add("/taxi top", "Clasamentul taximetriștilor", null));
        cats.add(new Cat("staff", "STAFF", "§c")
            .add("/dealer open <jucător>", "Deschide dealerul auto pentru un jucător", "flowcity.dealer.admin")
            .add("/dealer stock [<vehicul> <număr>]", "Vezi sau setezi stocul dealerului", "flowcity.dealer.admin")
            .add("/tuning open <jucător>", "Deschide tuning-ul (mecanic) pentru un jucător", "flowcity.dealer.admin")
            .add("/shop open <jucător> <magazin>", "Deschide un e-shop (arme, magazine, backpackuri)", "flowcity.shop.admin")
            .add("/shop reload", "Reîncarcă shop.yml", "flowcity.shop.admin")
            .add("/amenzi open <jucător>", "Deschide amenzile unui jucător (NPC/ghișeu)", "flowcity.fines.admin")
            .add("/notify <jucător|*> <tip> <titlu> [| text]", "Trimite o notificare", "flowcity.notify.admin")
            .add("/cnn <categorie> <titlu> [| text]", "Postează o știre", "flowcity.hype.cnn")
            .add("/setid <jucător> <număr> [swap]", "Setează ID-ul unui jucător", "flowcity.id.admin")
            .add("/delid <jucător|#număr>", "Șterge un ID", "flowcity.id.admin")
            .add("/listid", "Lista ID-urilor", "flowcity.id.list")
            .add("/hype <atm|jobloc|delivery|reload>", "Administrare ATM, joburi, livrări", "flowcity.hype.admin")
            .add("/roata reset <jucător>", "Resetează roata unui jucător", "flowcity.hype.admin")
            .add("/telefon da [jucător]", "Dai un telefon", "flowcity.phone.give")
            .add("/eadmin <info|warn|mute|kick|ban|...> <jucător>", "Moderare", "flowcity.eadmin")
            .add("/flowcity <update|check|download>", "Verifică și instalează actualizări", "flowcity.update"));
        Commands.register(plugin, "help", null, (s, c, l, a) -> {
            show(s, a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT));
            return true;
        }, (s, c, l, a) -> {
            List<String> keys = new ArrayList<>();
            for (Cat cat : cats) keys.add(cat.key);
            return a.length == 1 ? Commands.filter(keys, a[0]) : new ArrayList<String>();
        }, "ajutor", "?");
        Commands.takeOver(plugin, "help");
        Commands.takeOver(plugin, "?");
        Commands.takeOver(plugin, "ajutor");
        // alte pluginuri se pot incarca dupa noi: reluam peste cateva secunde
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Commands.takeOver(plugin, "help");
            Commands.takeOver(plugin, "?");
        }, 40L);
    }

    private boolean allowed(CommandSender s, Cmd c) {
        if (c.perm == null || c.perm.isEmpty()) return true;
        return s.hasPermission(c.perm) || (s instanceof Player && s.isOp());
    }

    private void show(CommandSender s, String only) {
        final String line = "§9§m                      ";
        s.sendMessage("");
        s.sendMessage(line + "§r §b§lFLOWCITY §f§lAJUTOR " + line);
        s.sendMessage("§7Comenzile pe care le poți folosi. §8Click pe una ca s-o scrii în chat.");
        boolean any = false;
        for (Cat cat : cats) {
            if (!only.isEmpty() && !cat.key.equals(only)) continue;
            List<Cmd> shown = new ArrayList<>();
            for (Cmd c : cat.cmds) if (allowed(s, c)) shown.add(c);
            if (shown.isEmpty()) continue;
            any = true;
            s.sendMessage("");
            s.sendMessage(cat.color + "§l» " + cat.title);
            for (Cmd c : shown) line(s, cat, c);
        }
        if (!any) {
            s.sendMessage("");
            s.sendMessage("§cNu există categoria §f" + only + "§c. §7Încearcă: " + keysFor(s));
        }
        s.sendMessage("");
        s.sendMessage("§8Categorii: " + keysFor(s) + "  §7(ex. §f/help bani§7)");
        s.sendMessage(line + "§9§m                         ");
    }

    private String keysFor(CommandSender s) {
        List<String> out = new ArrayList<>();
        for (Cat cat : cats) {
            for (Cmd c : cat.cmds) {
                if (allowed(s, c)) {
                    out.add("§f" + cat.key);
                    break;
                }
            }
        }
        return String.join("§7, ", out);
    }

    private void line(CommandSender s, Cat cat, Cmd c) {
        if (!(s instanceof Player)) {
            s.sendMessage("  " + c.usage + " - " + c.desc);
            return;
        }
        String base = c.usage.split(" ")[0];
        TextComponent tc = new TextComponent(" ");
        TextComponent usage = new TextComponent(c.usage);
        usage.setColor(net.md_5.bungee.api.ChatColor.WHITE);
        usage.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, base + " "));
        usage.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new ComponentBuilder("§f" + c.usage + "\n§7" + c.desc + "\n§8Click ca s-o scrii").create()));
        tc.addExtra(usage);
        TextComponent d = new TextComponent("  §8» §7" + c.desc);
        tc.addExtra(d);
        ((Player) s).spigot().sendMessage(tc);
    }
}
