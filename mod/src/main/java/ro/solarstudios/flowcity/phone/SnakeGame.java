package ro.solarstudios.flowcity.phone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Random;
import net.minecraftforge.fml.loading.FMLPaths;

/**
 * Jocul Snake din telefon (doar client): logica pe grila, fara legatura cu serverul. Recordul se salveaza local in
 * config/flowcity-snake.txt. Desenarea e in PhoneScreen.
 */
final class SnakeGame {
    static final int COLS = 15;
    static final int ROWS = 15;

    enum State { READY, RUNNING, OVER }

    State state = State.READY;
    int score;
    static int best = loadBest();
    final Deque<int[]> body = new ArrayDeque<>();
    int dx = 1;
    int dy = 0;
    private int pendX = 1;
    private int pendY = 0;
    int foodX;
    int foodY;
    long lastStep;
    long diedAt;
    private final Random rnd = new Random();
    boolean newBest;

    SnakeGame() {
        reset();
    }

    void reset() {
        body.clear();
        int cy = ROWS / 2;
        for (int i = 0; i < 3; i++) body.addFirst(new int[] {3 + i, cy});
        dx = 1;
        dy = 0;
        pendX = 1;
        pendY = 0;
        score = 0;
        newBest = false;
        placeFood();
        state = State.READY;
    }

    void start(long now) {
        if (state == State.OVER) reset();
        state = State.RUNNING;
        lastStep = now;
    }

    /** Schimba directia (fara intoarcere pe loc). */
    void turn(int nx, int ny) {
        if (state == State.OVER) return;
        if (nx == -dx && ny == -dy) return;
        if (nx == -pendX && ny == -pendY) return;
        pendX = nx;
        pendY = ny;
    }

    /** Intervalul dintre pasi: mai rapid cu scorul. */
    int stepMs() {
        return Math.max(65, 150 - score * 4);
    }

    void update(long now) {
        if (state != State.RUNNING) return;
        int guard = 0;
        while (now - lastStep >= stepMs() && guard++ < 4) {
            lastStep += stepMs();
            step(now);
            if (state != State.RUNNING) return;
        }
    }

    private void step(long now) {
        dx = pendX;
        dy = pendY;
        int[] head = body.peekFirst();
        int nx = head[0] + dx;
        int ny = head[1] + dy;
        boolean eat = nx == foodX && ny == foodY;
        if (nx < 0 || ny < 0 || nx >= COLS || ny >= ROWS || hits(nx, ny, eat)) {
            die(now);
            return;
        }
        body.addFirst(new int[] {nx, ny});
        if (eat) {
            score++;
            if (body.size() >= COLS * ROWS) {
                die(now);
                return;
            }
            placeFood();
        } else {
            body.removeLast();
        }
    }

    private boolean hits(int x, int y, boolean growing) {
        int i = 0;
        int n = body.size();
        for (int[] s : body) {
            i++;
            if (!growing && i == n) break; // coada se muta
            if (s[0] == x && s[1] == y) return true;
        }
        return false;
    }

    private void die(long now) {
        state = State.OVER;
        diedAt = now;
        if (score > best) {
            best = score;
            newBest = true;
            saveBest();
        }
    }

    private void placeFood() {
        for (int tries = 0; tries < 400; tries++) {
            int x = rnd.nextInt(COLS);
            int y = rnd.nextInt(ROWS);
            boolean on = false;
            for (int[] s : body) {
                if (s[0] == x && s[1] == y) {
                    on = true;
                    break;
                }
            }
            if (!on) {
                foodX = x;
                foodY = y;
                return;
            }
        }
        foodX = 0;
        foodY = 0;
    }

    private static Path file() {
        return FMLPaths.GAMEDIR.get().resolve("config").resolve("flowcity-snake.txt");
    }

    private static int loadBest() {
        try {
            Path f = file();
            if (Files.exists(f)) return Math.max(0, Integer.parseInt(new String(Files.readAllBytes(f), StandardCharsets.UTF_8).trim()));
        } catch (IOException | RuntimeException ignored) {
            // recordul ramane 0
        }
        return 0;
    }

    private static void saveBest() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            Files.write(f, String.valueOf(best).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // nu e critic
        }
    }
}
