package ro.solarstudios.flowcity.news;

import java.util.ArrayList;
import java.util.List;
import ro.solarstudios.flowcity.client.ui.Theme;

/** Starea CNN pe client: lista de stiri, drepturi, stil pe categorii. Doar thread-ul clientului. */
public final class NewsState {
    private NewsState() {}

    public static final class Item {
        public long id;
        public String cat = "";
        public String title = "";
        public String body = "";
        public String author = "";
        public long ts;
    }

    public static final List<Item> items = new ArrayList<>();
    public static final List<String> cats = new ArrayList<>();
    public static boolean canPublish;
    public static boolean canDelete;

    public static void reset() {
        items.clear();
        cats.clear();
        canPublish = false;
        canDelete = false;
    }

    public static int color(String cat) {
        switch (cat) {
            case "breaking": return Theme.BAD;
            case "politie": return 0xFF5BA8FF;
            case "eveniment": return Theme.WARN;
            case "guvern": return Theme.OK;
            default: return Theme.ACCENT;
        }
    }

    public static String label(String cat) {
        return cat.toUpperCase(java.util.Locale.ROOT);
    }
}
