package ro.solarstudios.flowcity.menu;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Regulamentul serverului. Implicit e cel de mai jos; il poti inlocui cu config/flowcity-rules.txt
 * (linie care incepe cu "# " = titlu de sectiune, restul = reguli). Cand se schimba textul, jucatorii trebuie sa accepte din nou.
 */
public final class Rules {
    private Rules() {}

    private static final String[] DEFAULT = {
        "# 1. Respect si comportament",
        "Respecta toti jucatorii si staff-ul. Insultele, rasismul, hartuirea si amenintarile sunt interzise.",
        "Ascultarea deciziilor staff-ului este obligatorie. Contestatiile se fac pe Discord, nu in joc.",
        "Publicitatea pentru alte servere sau comunitati este interzisa.",
        "# 2. Roleplay",
        "Joaca-ti personajul serios: ramai in rol (IC) si nu amesteca informatiile din afara jocului (OOC).",
        "RDM / VDM: interzis sa omori sau sa lovesti jucatori fara motiv de roleplay.",
        "Metagaming si powergaming: nu folosi informatii din afara jocului si nu forta actiuni asupra altora.",
        "FearRP: cand ai o arma in fata, te comporti ca in viata reala. NLR: dupa ce mori uiti ce s-a intamplat.",
        "# 3. Economie si legalitate",
        "Abuzul de buguri, duplicarea de iteme sau bani si orice cheat/hack sunt interzise si pedepsite cu ban.",
        "Daca gasesti un bug, il raportezi staff-ului. Folosirea lui aduce sanctiuni.",
        "Tranzactiile cu bani reali sau vanzarea de conturi si iteme sunt interzise.",
        "# 4. Reguli generale",
        "Un singur cont pe persoana. Contul tau este responsabilitatea ta.",
        "Mod-urile care dau avantaj (xray, auto-click, minimap cu entitati etc.) sunt interzise.",
        "Prin intrarea pe server confirmi ca ai citit regulamentul si accepti termenii si conditiile FlowCity.",
    };

    public static List<String> lines() {
        Path file = FMLPaths.CONFIGDIR.get().resolve("flowcity-rules.txt");
        try {
            if (Files.exists(file)) {
                List<String> l = new ArrayList<>();
                for (String s : Files.readAllLines(file, StandardCharsets.UTF_8)) if (!s.trim().isEmpty()) l.add(s.trim());
                if (!l.isEmpty()) return l;
            } else {
                Files.write(file, Arrays.asList(DEFAULT), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException ignored) {
            // folosim varianta implicita
        }
        return Arrays.asList(DEFAULT);
    }

    /** Titlurile sectiunilor (fara "# "). */
    public static List<String> titles() {
        List<String> t = new ArrayList<>();
        for (String s : lines()) if (s.startsWith("#")) t.add(s.replaceFirst("^#+\\s*", ""));
        return t;
    }

    public static int hash() {
        int h = 17;
        for (String s : lines()) h = h * 31 + s.hashCode();
        return h == 0 ? 1 : h;
    }

    public static boolean accepted() {
        return MenuConfig.get().rulesAccepted == hash();
    }

    public static void accept() {
        MenuConfig c = MenuConfig.get();
        c.rulesAccepted = hash();
        c.save();
    }
}
