package ro.solarstudios.flowcity.bridge.help;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.md_5.bungee.api.ChatColor;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ro.solarstudios.flowcity.bridge.core.Commands;
import ro.solarstudios.flowcity.bridge.core.ModConfig;
import ro.solarstudios.flowcity.bridge.core.SmallCaps;
import ro.solarstudios.flowcity.bridge.module.JsonModule;

/**
 * /help (si /ajutor, /?): lista comenzilor FlowCity, pe categorii cu pagini, doar cele la care ai acces. Totul e definit in
 * help.yml (categorii, comenzi, permisiuni, culori gradient, small caps); daca fisierul lipseste sau e gol, ramane o lista
 * minima din cod. Click pe o categorie o deschide, click pe o comanda o scrie in chat, mouse-ul deasupra arata detalii.
 * /help <categorie> [pagina], /help <comanda>, /help reload (flowcity.help.admin).
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

        /** Primul cuvant al comenzii, fara "/" sau ".": "/taxi call" -> "taxi". */
        String base() {
            String b = usage.split(" ")[0];
            while (b.startsWith("/") || b.startsWith(".")) b = b.substring(1);
            return b.toLowerCase(Locale.ROOT);
        }

        /** Prefixul de scris in chat ("/taxi " sau ".trip "). */
        String suggest() {
            return usage.split(" ")[0] + " ";
        }
    }

    private static final class Cat {
        final String key;
        final String title;
        final String icon;
        final int from;
        final int to;
        final List<Cmd> cmds = new ArrayList<>();

        Cat(String key, String title, String icon, int from, int to) {
            this.key = key;
            this.title = title;
            this.icon = icon;
            this.from = from;
            this.to = to;
        }
    }

    private final List<Cat> cats = new ArrayList<>();
    private YamlConfiguration cfg;
    private int pageSize = 8;

    @Override
    public String id() {
        return "help";
    }

    @Override
    protected void onEnable() {
        load();
        Commands.register(plugin, "help", null, (s, c, l, a) -> {
            run(s, a);
            return true;
        }, (s, c, l, a) -> {
            List<String> keys = new ArrayList<>();
            if (a.length == 1) {
                for (Cat cat : cats) if (!visible(s, cat).isEmpty()) keys.add(cat.key);
                for (Cat cat : cats) for (Cmd cmd : visible(s, cat)) if (!keys.contains(cmd.base())) keys.add(cmd.base());
                if (canReload(s)) keys.add("reload");
                return Commands.filter(keys, a[0]);
            }
            return new ArrayList<String>();
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

    // ------------------------------------------------------------------ incarcare

    private void load() {
        cfg = ModConfig.load(plugin, "help");
        SmallCaps.enabled = cfg.getBoolean("smallcaps", true);
        pageSize = Math.max(3, Math.min(20, cfg.getInt("page-size", 8)));
        cats.clear();
        for (java.util.Map<?, ?> m : cfg.getMapList("categories")) {
            String key = str(m.get("key"), "").toLowerCase(Locale.ROOT);
            if (key.isEmpty()) continue;
            int from = color(str(m.get("color"), "#4DA3FF"), 0x4DA3FF);
            int to = color(str(m.get("to"), ""), from);
            Cat cat = new Cat(key, str(m.get("title"), key), str(m.get("icon"), "»"), from, to);
            Object list = m.get("commands");
            if (list instanceof List) {
                for (Object o : (List<?>) list) {
                    if (!(o instanceof java.util.Map)) continue;
                    java.util.Map<?, ?> cm = (java.util.Map<?, ?>) o;
                    String usage = str(cm.get("usage"), "");
                    if (usage.isEmpty()) continue;
                    cat.cmds.add(new Cmd(usage, str(cm.get("desc"), ""), str(cm.get("perm"), "")));
                }
            }
            if (!cat.cmds.isEmpty()) cats.add(cat);
        }
        if (cats.isEmpty()) {
            // minim din cod, daca help.yml lipseste sau e gol
            Cat cat = new Cat("ajutor", "Ajutor", "?", 0x4DA3FF, 0x9B6BFF);
            cat.cmds.add(new Cmd("/help", "Lista comenzilor (help.yml lipsește sau e gol)", ""));
            cat.cmds.add(new Cmd("/telefon", "Folosește telefonul", "flowcity.phone"));
            cat.cmds.add(new Cmd("/politie", "Ecranul Poliției", ""));
            cat.cmds.add(new Cmd("/banca", "Banca ta", ""));
            cats.add(cat);
        }
    }

    private static String str(Object o, String def) {
        return o == null ? def : String.valueOf(o);
    }

    private static int color(String hex, int def) {
        if (hex == null) return def;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() != 6) return def;
        try {
            return Integer.parseInt(h, 16);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    // ------------------------------------------------------------------ acces

    private boolean allowed(CommandSender s, Cmd c) {
        if (c.perm == null || c.perm.isEmpty()) return true;
        return s.hasPermission(c.perm) || (s instanceof Player && s.isOp());
    }

    private List<Cmd> visible(CommandSender s, Cat cat) {
        List<Cmd> out = new ArrayList<>();
        for (Cmd c : cat.cmds) if (allowed(s, c)) out.add(c);
        return out;
    }

    private boolean canReload(CommandSender s) {
        return s.hasPermission("flowcity.help.admin") || (s instanceof Player && s.isOp());
    }

    private String t(String s) {
        return SmallCaps.title(s);
    }

    // ------------------------------------------------------------------ comanda

    private void run(CommandSender s, String[] a) {
        if (a.length == 0) {
            index(s);
            return;
        }
        String q = a[0].toLowerCase(Locale.ROOT);
        if (q.equals("reload")) {
            if (!canReload(s)) {
                s.sendMessage("§cNu ai permisiunea.");
                return;
            }
            load();
            s.sendMessage("§aHelp reîncărcat: §f" + cats.size() + " §acategorii.");
            return;
        }
        for (Cat cat : cats) {
            if (cat.key.equals(q)) {
                int page = 1;
                if (a.length >= 2) {
                    try {
                        page = Integer.parseInt(a[1]);
                    } catch (NumberFormatException ignored) {
                        page = 1;
                    }
                }
                category(s, cat, page);
                return;
            }
        }
        detail(s, q.startsWith("/") || q.startsWith(".") ? q.substring(1) : q);
    }

    // ------------------------------------------------------------------ afisare

    private void header(CommandSender s, String text, int from, int to) {
        s.sendMessage("");
        if (s instanceof Player) {
            TextComponent tc = new TextComponent("§8§m      §r ");
            tc.addExtra(gradient(t(text), from, to));
            TextComponent end = new TextComponent(" §8§m      ");
            tc.addExtra(end);
            ((Player) s).spigot().sendMessage(tc);
        } else {
            s.sendMessage("== " + t(text) + " ==");
        }
    }

    private static TextComponent gradient(String text, int from, int to) {
        TextComponent root = new TextComponent("");
        int n = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            float f = (float) i / n;
            int r = (int) (((from >> 16) & 255) + ((((to >> 16) & 255) - ((from >> 16) & 255)) * f));
            int g = (int) (((from >> 8) & 255) + ((((to >> 8) & 255) - ((from >> 8) & 255)) * f));
            int b = (int) ((from & 255) + (((to & 255) - (from & 255)) * f));
            TextComponent ch = new TextComponent(String.valueOf(text.charAt(i)));
            ch.setColor(ChatColor.of(String.format("#%02X%02X%02X", r & 255, g & 255, b & 255)));
            ch.setBold(true);
            root.addExtra(ch);
        }
        return root;
    }

    private void index(CommandSender s) {
        header(s, cfg.getString("title", "FlowCity ajutor"), 0x4DA3FF, 0xFFFFFF);
        s.sendMessage("§7" + cfg.getString("subtitle", "Comenzile pe care le poți folosi."));
        s.sendMessage("");
        boolean any = false;
        for (Cat cat : cats) {
            int n = visible(s, cat).size();
            if (n == 0) continue;
            any = true;
            if (s instanceof Player) {
                TextComponent tc = new TextComponent(" ");
                TextComponent icon = new TextComponent(cat.icon + " ");
                icon.setColor(ChatColor.of(String.format("#%06X", cat.from)));
                tc.addExtra(icon);
                tc.addExtra(gradient(t(cat.title), cat.from, cat.to));
                TextComponent cnt = new TextComponent(" §8(" + n + ")");
                tc.addExtra(cnt);
                tc.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/help " + cat.key));
                tc.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                    new ComponentBuilder("§f" + t(cat.title) + "\n§7" + n + " comenzi\n§8Click ca s-o deschizi").create()));
                ((Player) s).spigot().sendMessage(tc);
            } else {
                s.sendMessage("  " + cat.key + " - " + t(cat.title) + " (" + n + ")");
            }
        }
        if (!any) s.sendMessage("§cNu ai acces la nicio comandă.");
        s.sendMessage("");
        s.sendMessage("§8" + cfg.getString("footer-hint", "/help <categorie>  ·  /help <comandă>"));
    }

    private void category(CommandSender s, Cat cat, int page) {
        List<Cmd> shown = visible(s, cat);
        if (shown.isEmpty()) {
            s.sendMessage("§cNu ai acces la nicio comandă din §f" + cat.key + "§c. §7Încearcă: " + keysFor(s));
            return;
        }
        int pages = Math.max(1, (shown.size() + pageSize - 1) / pageSize);
        int p = Math.max(1, Math.min(pages, page));
        header(s, cat.icon + " " + cat.title, cat.from, cat.to);
        for (int i = (p - 1) * pageSize; i < Math.min(shown.size(), p * pageSize); i++) line(s, shown.get(i));
        s.sendMessage("");
        nav(s, cat, p, pages);
    }

    private void nav(CommandSender s, Cat cat, int page, int pages) {
        if (!(s instanceof Player)) {
            s.sendMessage("pagina " + page + "/" + pages);
            return;
        }
        TextComponent tc = new TextComponent(" ");
        TextComponent back = new TextComponent("« " + t("Categorii") + "  ");
        back.setColor(ChatColor.GRAY);
        back.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/help"));
        tc.addExtra(back);
        if (pages > 1) {
            TextComponent prev = new TextComponent(page > 1 ? "[« " + t("Înapoi") + "] " : "§8[« " + t("Înapoi") + "] ");
            if (page > 1) {
                prev.setColor(ChatColor.AQUA);
                prev.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/help " + cat.key + " " + (page - 1)));
            }
            tc.addExtra(prev);
            TextComponent pg = new TextComponent("§7" + t("Pagina") + " §f" + page + "§7/§f" + pages + " ");
            tc.addExtra(pg);
            TextComponent next = new TextComponent(page < pages ? "[" + t("Înainte") + " »]" : "§8[" + t("Înainte") + " »]");
            if (page < pages) {
                next.setColor(ChatColor.AQUA);
                next.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/help " + cat.key + " " + (page + 1)));
            }
            tc.addExtra(next);
        }
        ((Player) s).spigot().sendMessage(tc);
    }

    /** /help <comanda>: toate formele comenzii (din orice categorie) la care ai acces. */
    private void detail(CommandSender s, String base) {
        List<Cmd> found = new ArrayList<>();
        for (Cat cat : cats) {
            for (Cmd c : cat.cmds) if (c.base().equals(base) && allowed(s, c)) found.add(c);
        }
        if (found.isEmpty()) {
            s.sendMessage("§cNu există categoria sau comanda §f" + base + "§c. §7Încearcă: " + keysFor(s));
            return;
        }
        header(s, "/" + base, 0x4DA3FF, 0xFFFFFF);
        for (Cmd c : found) line(s, c);
        s.sendMessage("");
    }

    private String keysFor(CommandSender s) {
        List<String> out = new ArrayList<>();
        for (Cat cat : cats) if (!visible(s, cat).isEmpty()) out.add("§f" + cat.key);
        return String.join("§7, ", out);
    }

    private void line(CommandSender s, Cmd c) {
        if (!(s instanceof Player)) {
            s.sendMessage("  " + c.usage + " - " + c.desc);
            return;
        }
        TextComponent tc = new TextComponent(" ");
        TextComponent usage = new TextComponent(c.usage);
        usage.setColor(ChatColor.WHITE);
        usage.setClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, c.suggest()));
        usage.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
            new ComponentBuilder("§f" + c.usage + "\n§7" + c.desc + "\n§8Click ca s-o scrii").create()));
        tc.addExtra(usage);
        TextComponent d = new TextComponent("  §8» §7" + c.desc);
        tc.addExtra(d);
        ((Player) s).spigot().sendMessage(new BaseComponent[] {tc});
    }
}
