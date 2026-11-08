package ro.solarstudios.flowcity.bridge.core;

import java.io.File;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import ro.solarstudios.flowcity.bridge.FlowCityBridge;

/** Config per modul: copiaza resursa "<nume>.yml" in folderul pluginului la prima rulare si o incarca (cu valori implicite din jar). */
public final class ModConfig {
    private ModConfig() {}

    public static YamlConfiguration load(FlowCityBridge plugin, String name) {
        File file = new File(plugin.getDataFolder(), name + ".yml");
        if (!file.exists()) {
            plugin.getDataFolder().mkdirs();
            if (plugin.getResource(name + ".yml") != null) plugin.saveResource(name + ".yml", false);
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        try (Reader r = new InputStreamReader(plugin.getResource(name + ".yml"), StandardCharsets.UTF_8)) {
            cfg.setDefaults(YamlConfiguration.loadConfiguration(r));
            cfg.options().copyDefaults(true);
        } catch (Exception ignored) {
            // fara valori implicite in jar
        }
        return cfg;
    }

    public static void save(YamlConfiguration cfg, FlowCityBridge plugin, String name) {
        try {
            cfg.save(new File(plugin.getDataFolder(), name + ".yml"));
        } catch (Exception e) {
            plugin.getLogger().warning("Nu pot salva " + name + ".yml: " + e);
        }
    }
}
