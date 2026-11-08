package ro.solarstudios.flowcity.bridge.maps;

/** Geometrie pura pentru zone: poligon in plan (x,z) dat ca {x0,z0,x1,z1,...}. */
public final class ZoneGeo {
    private ZoneGeo() {}

    /** Punct in poligon (ray casting); marginea apartine poligonului pentru dreptunghiuri aliniate. */
    public static boolean contains(int[] p, double x, double z) {
        int n = p.length / 2;
        if (n < 3) return false;
        boolean in = false;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = p[2 * i];
            double zi = p[2 * i + 1];
            double xj = p[2 * j];
            double zj = p[2 * j + 1];
            if (onSegment(xi, zi, xj, zj, x, z)) return true;
            if ((zi > z) != (zj > z) && x < (xj - xi) * (z - zi) / (zj - zi) + xi) in = !in;
        }
        return in;
    }

    private static boolean onSegment(double ax, double az, double bx, double bz, double x, double z) {
        double cross = (x - ax) * (bz - az) - (z - az) * (bx - ax);
        if (Math.abs(cross) > 1e-9) return false;
        return x >= Math.min(ax, bx) - 1e-9 && x <= Math.max(ax, bx) + 1e-9
            && z >= Math.min(az, bz) - 1e-9 && z <= Math.max(az, bz) + 1e-9;
    }

    /** Aria (valoare absoluta, formula lui Gauss). */
    public static double area(int[] p) {
        int n = p.length / 2;
        double s = 0;
        for (int i = 0, j = n - 1; i < n; j = i++) {
            s += (double) p[2 * j] * p[2 * i + 1] - (double) p[2 * i] * p[2 * j + 1];
        }
        return Math.abs(s) / 2.0;
    }

    /** {minX, minZ, maxX, maxZ}. */
    public static int[] bounds(int[] p) {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (int i = 0; i + 1 < p.length; i += 2) {
            minX = Math.min(minX, p[i]);
            maxX = Math.max(maxX, p[i]);
            minZ = Math.min(minZ, p[i + 1]);
            maxZ = Math.max(maxZ, p[i + 1]);
        }
        return new int[] {minX, minZ, maxX, maxZ};
    }

    /** Valid: 3..maxPoints puncte, coordonate rezonabile, arie &gt; 0, fara auto-intersectii. Intoarce null daca ok, altfel motivul. */
    public static String validate(int[] p, int maxPoints, int maxCoord) {
        if (p == null || p.length % 2 != 0) return "date invalide";
        int n = p.length / 2;
        if (n < 3) return "minim 3 puncte";
        if (n > maxPoints) return "maxim " + maxPoints + " puncte";
        for (int v : p) if (Math.abs((long) v) > maxCoord) return "coordonate in afara limitei";
        if (area(p) < 1.0) return "zona are arie zero";
        for (int i = 0; i < n; i++) {
            int i2 = (i + 1) % n;
            for (int j = i + 1; j < n; j++) {
                int j2 = (j + 1) % n;
                if (i == j || i2 == j || j2 == i) continue; // segmente adiacente
                if (intersects(p[2 * i], p[2 * i + 1], p[2 * i2], p[2 * i2 + 1], p[2 * j], p[2 * j + 1], p[2 * j2], p[2 * j2 + 1])) {
                    return "laturile se intersecteaza";
                }
            }
        }
        return null;
    }

    private static long orient(long ax, long az, long bx, long bz, long cx, long cz) {
        return (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
    }

    private static boolean intersects(long ax, long az, long bx, long bz, long cx, long cz, long dx, long dz) {
        long o1 = Long.signum(orient(ax, az, bx, bz, cx, cz));
        long o2 = Long.signum(orient(ax, az, bx, bz, dx, dz));
        long o3 = Long.signum(orient(cx, cz, dx, dz, ax, az));
        long o4 = Long.signum(orient(cx, cz, dx, dz, bx, bz));
        if (o1 != o2 && o3 != o4) return true;
        if (o1 == 0 && onSeg(ax, az, bx, bz, cx, cz)) return true;
        if (o2 == 0 && onSeg(ax, az, bx, bz, dx, dz)) return true;
        if (o3 == 0 && onSeg(cx, cz, dx, dz, ax, az)) return true;
        return o4 == 0 && onSeg(cx, cz, dx, dz, bx, bz);
    }

    private static boolean onSeg(long ax, long az, long bx, long bz, long x, long z) {
        return x >= Math.min(ax, bx) && x <= Math.max(ax, bx) && z >= Math.min(az, bz) && z <= Math.max(az, bz);
    }

    public static String encode(int[] p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i + 1 < p.length; i += 2) {
            if (i > 0) sb.append(';');
            sb.append(p[i]).append(',').append(p[i + 1]);
        }
        return sb.toString();
    }

    /** Invers pentru {@link #encode}; arunca IllegalArgumentException daca e corupt. */
    public static int[] decode(String s) {
        if (s == null || s.isEmpty()) return new int[0];
        String[] parts = s.split(";");
        int[] out = new int[parts.length * 2];
        try {
            for (int i = 0; i < parts.length; i++) {
                String[] xy = parts[i].split(",");
                out[2 * i] = Integer.parseInt(xy[0].trim());
                out[2 * i + 1] = Integer.parseInt(xy[1].trim());
            }
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("puncte corupte: " + s);
        }
        return out;
    }
}
