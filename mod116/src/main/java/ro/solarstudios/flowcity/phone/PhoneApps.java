package ro.solarstudios.flowcity.phone;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Catalogul aplicatiilor ePhone (client): nume, culoare, categorie, descriere, marime. Ce e instalat, ce costa si ce e
 * activat vine de la server (vezi AppStore in Bridge); ID-urile trebuie sa fie aceleasi.
 */
public final class PhoneApps {
    private PhoneApps() {}

    public static final class App {
        public final String id;
        public final String name;
        public final int color;
        public final int color2;
        public final String category;
        public final String desc;
        public final int sizeMb;
        public final boolean core;
        public final boolean needsSim;

        App(String id, String name, int color, int color2, String category, int sizeMb, boolean core, boolean needsSim, String desc) {
            this.id = id;
            this.name = name;
            this.color = color;
            this.color2 = color2;
            this.category = category;
            this.sizeMb = sizeMb;
            this.core = core;
            this.needsSim = needsSim;
            this.desc = desc;
        }
    }

    public static final List<String> CATEGORIES = Arrays.asList("Toate", "Social", "Info", "Finante", "Utilitare", "Navigare", "Joburi", "Distractie");

    private static final Map<String, App> ALL = new LinkedHashMap<>();

    private static void add(App a) {
        ALL.put(a.id, a);
    }

    static {
        add(new App("phone", "Telefon", 0xFF35C46A, 0xFF1E8A48, "Social", 8, true, true, "Apeluri, tastatura si istoricul apelurilor. Necesita o cartela SIM activa."));
        add(new App("messages", "Mesaje", 0xFF3BA7FF, 0xFF1F6FD0, "Social", 9, true, true, "Conversatii SMS cu ceilalti jucatori. Necesita o cartela SIM activa."));
        add(new App("contacts", "Contacte", 0xFF8A94A8, 0xFF566078, "Social", 4, true, false, "Agenda ta: adauga jucatori dupa nume sau ID si suna-i direct."));
        add(new App("store", "App Store", 0xFF2E9BFF, 0xFF1653C9, "Utilitare", 21, true, false, "Descopera, instaleaza si gestioneaza aplicatiile telefonului."));
        add(new App("settings", "Setari", 0xFF7D8696, 0xFF4A5262, "Utilitare", 6, true, false, "Fundal, mod luminos, sunete, HUD."));
        add(new App("sim", "SIM", 0xFF2FC2A3, 0xFF1A8C74, "Utilitare", 3, true, false, "Introdu, scoate sau cumpara o cartela SIM. Vezi numarul si operatorul."));
        add(new App("cnn", "CNN", 0xFFE0453C, 0xFFA12722, "Info", 34, false, false, "Stirile orasului: breaking news, articole si anunturi oficiale."));
        add(new App("ads", "Anunturi", 0xFFF2A33A, 0xFFC0701C, "Social", 18, false, false, "Marketplace: vinde, cumpara, inchiriaza sau ofera servicii. Contacteaza vanzatorii prin SMS sau apel."));
        add(new App("bank", "Banca", 0xFF2D6FE0, 0xFF183F9E, "Finante", 27, false, false, "Soldul contului, transferuri si istoricul tranzactiilor."));
        add(new App("notify", "Notificari", 0xFFF5C044, 0xFFC98A12, "Utilitare", 5, false, false, "Centrul de notificari: SMS-uri, apeluri pierdute, stiri, banca, joburi."));
        add(new App("gps", "Harta", 0xFF27B3C9, 0xFF137A92, "Navigare", 46, false, false, "Harta orasului cu locatii, trasee si navigare pana la destinatie."));
        add(new App("jobs", "Joburi", 0xFF6C8BB8, 0xFF3E5A86, "Joburi", 15, false, false, "Centrul de joburi: alege un loc de munca, vezi progresul si sarcinile."));
        add(new App("wheel", "Roata", 0xFFA470E6, 0xFF6B3DB0, "Distractie", 12, false, false, "Roata norocului: invarte si castiga premii."));
        add(new App("calc", "Calculator", 0xFF4A4F5A, 0xFF24272E, "Utilitare", 2, false, false, "Calculator rapid: adunare, scadere, inmultire, impartire, procente."));
        add(new App("notes", "Note", 0xFFF6D365, 0xFFE0A52C, "Utilitare", 3, false, false, "Notite personale salvate pe server: numere, adrese, liste."));
        add(new App("clock", "Ceas", 0xFF262A33, 0xFF0B0D12, "Utilitare", 4, false, false, "Ora reala si ora din joc, cronometru si temporizator cu alarma."));
        add(new App("weather", "Vremea", 0xFF4FA8F0, 0xFF2A6FC4, "Info", 7, false, false, "Vremea din lumea jocului unde te afli: ploaie, furtuna, temperatura biomului."));
    }

    public static App get(String id) {
        return ALL.get(id);
    }

    public static List<App> all() {
        return new ArrayList<>(ALL.values());
    }

    /** Toate aplicatiile (folosit cand serverul nu a trimis inca lista). */
    public static List<String> fallbackOrder() {
        List<String> l = new ArrayList<>();
        for (App a : ALL.values()) l.add(a.id);
        return l;
    }
}
