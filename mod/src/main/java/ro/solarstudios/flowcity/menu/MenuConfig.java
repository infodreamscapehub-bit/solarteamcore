package ro.solarstudios.flowcity.menu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraftforge.fml.loading.FMLPaths;

/** config/flowcity-menu.json: adresa serverului, link Discord, titlu. Se creeaza singur la prima pornire. */
public final class MenuConfig {
    public String serverName = "FlowCity";
    public static final String DEFAULT_IP = "node1.capitalrp.ro:25579";
    /** Adrese vechi: cine le are salvate in config primeste automat adresa noua. */
    private static final String[] OLD_IPS = {"91.200.220.147:25579"};
    public String serverIp = DEFAULT_IP;  // gol = foloseste adresa implicita FlowCity
    public static final String DEFAULT_DISCORD = "https://dsc.gg/solarteam12";
    public String discord = DEFAULT_DISCORD; // gol = foloseste link-ul implicit
    public String tagline = "Roleplay  |  SolarStudios";
    /** Hash-ul regulamentului acceptat (0 = neacceptat). Se reseteaza singur cand se schimba regulamentul. */
    public int rulesAccepted = 0;

    private static MenuConfig cached;

    public static MenuConfig get() {
        if (cached == null) cached = load();
        return cached;
    }

    public void save() {
        try {
            Path file = FMLPaths.CONFIGDIR.get().resolve("flowcity-menu.json");
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                new GsonBuilder().setPrettyPrinting().create().toJson(this, w);
            }
        } catch (IOException | RuntimeException ignored) {
            // nu e critic
        }
    }

    private static MenuConfig load() {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Path file = FMLPaths.CONFIGDIR.get().resolve("flowcity-menu.json");
        try {
            if (Files.exists(file)) {
                try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    MenuConfig c = gson.fromJson(r, MenuConfig.class);
                    if (c != null) {
                        for (String old : OLD_IPS) {
                            if (old.equals(c.serverIp)) {
                                c.serverIp = DEFAULT_IP;
                                cached = c;
                                c.save();
                            }
                        }
                        return c;
                    }
                }
            }
            MenuConfig c = new MenuConfig();
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(c, w);
            }
            return c;
        } catch (IOException | RuntimeException e) {
            return new MenuConfig();
        }
    }
}
