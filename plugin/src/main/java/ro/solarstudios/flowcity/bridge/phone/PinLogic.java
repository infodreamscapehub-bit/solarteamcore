package ro.solarstudios.flowcity.bridge.phone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/** PIN-ul telefonului: 4-6 cifre, stocat ca SHA-256(salt + pin). */
public final class PinLogic {
    private static final SecureRandom RND = new SecureRandom();

    private PinLogic() {}

    public static boolean validPin(String pin) {
        if (pin == null || pin.length() < 4 || pin.length() > 6) return false;
        for (int i = 0; i < pin.length(); i++) if (pin.charAt(i) < '0' || pin.charAt(i) > '9') return false;
        return true;
    }

    public static String newSalt() {
        byte[] b = new byte[8];
        RND.nextBytes(b);
        return hex(b);
    }

    public static String hash(String salt, String pin) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest((salt + ":" + pin).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean check(String salt, String hash, String pin) {
        if (hash == null || hash.isEmpty() || pin == null) return false;
        String h = hash(salt, pin);
        // comparatie in timp constant
        int diff = h.length() ^ hash.length();
        for (int i = 0; i < Math.min(h.length(), hash.length()); i++) diff |= h.charAt(i) ^ hash.charAt(i);
        return diff == 0;
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
