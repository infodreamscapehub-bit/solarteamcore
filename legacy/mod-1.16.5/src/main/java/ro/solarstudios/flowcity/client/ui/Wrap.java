package ro.solarstudios.flowcity.client.ui;

import java.util.ArrayList;
import java.util.List;

/** Rupere de text pe randuri dupa latimea in pixeli (respecta \n, taie cuvintele prea lungi). */
public final class Wrap {
    private Wrap() {}

    public static List<String> lines(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                if (word.isEmpty()) continue;
                // cuvant mai lat decat linia: il spargem pe caractere
                while (Ui.width(word) > maxWidth) {
                    int cut = 1;
                    while (cut < word.length() && Ui.width(word.substring(0, cut + 1)) <= maxWidth) cut++;
                    if (line.length() > 0) {
                        out.add(line.toString());
                        line.setLength(0);
                    }
                    out.add(word.substring(0, cut));
                    word = word.substring(cut);
                }
                String next = line.length() == 0 ? word : line + " " + word;
                if (Ui.width(next) > maxWidth) {
                    out.add(line.toString());
                    line.setLength(0);
                    line.append(word);
                } else {
                    line.setLength(0);
                    line.append(next);
                }
            }
            out.add(line.toString());
        }
        return out;
    }
}
