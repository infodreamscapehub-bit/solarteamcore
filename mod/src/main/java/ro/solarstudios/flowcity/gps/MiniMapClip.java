package ro.solarstudios.flowcity.gps;

/**
 * Decupare geometrica pe un poligon CONVEX (patrat cu colturi taiate sau cerc aproximat cu 40 de laturi), fara stencil/scissor:
 * astfel minimap-ul circular are o masca corecta in 1.16.5 fara API-uri speciale. Poligonul de decupare trebuie dat cu varfurile
 * in ordinea unghiului crescator (pe ecran, in sens orar, y in jos). Functiile nu aloca memorie (lucreaza pe vectori primiti).
 */
public final class MiniMapClip {
    private MiniMapClip() {}

    /** Maximul de varfuri ale poligonului de decupare. */
    public static final int MAX = 64;

    /**
     * Sutherland-Hodgman: taie poligonul din in[] (n varfuri, fiecare cu "stride" valori: x, y, apoi atribute interpolate liniar
     * - ex. u, v) pe poligonul convex clip[] (clipN varfuri x,y). Rezultatul ajunge in out[] sau in tmp[] (intoarce indexul 0/1
     * al vectorului cu rezultat prin campul {@link #resultInTmp}) si numarul de varfuri. Vectorii out/tmp trebuie sa aiba loc
     * pentru (n + clipN) * stride valori.
     */
    public static boolean resultInTmp;

    public static int clip(float[] in, int n, int stride, float[] clip, int clipN, float[] out, float[] tmp) {
        float[] src = in;
        float[] dst = out;
        int count = n;
        boolean first = true;
        for (int e = 0; e < clipN && count > 0; e++) {
            int e2 = (e + 1) % clipN;
            float ax = clip[e * 2];
            float ay = clip[e * 2 + 1];
            float bx = clip[e2 * 2];
            float by = clip[e2 * 2 + 1];
            float ex = bx - ax;
            float ey = by - ay;
            int m = 0;
            for (int i = 0; i < count; i++) {
                int p = (i + count - 1) % count;
                int ci = i * stride;
                int pi = p * stride;
                float dc = ex * (src[ci + 1] - ay) - ey * (src[ci] - ax);
                float dp = ex * (src[pi + 1] - ay) - ey * (src[pi] - ax);
                if (dc >= 0f) {
                    if (dp < 0f) m = intersect(src, pi, ci, stride, dp, dc, dst, m);
                    for (int k = 0; k < stride; k++) dst[m * stride + k] = src[ci + k];
                    m++;
                } else if (dp >= 0f) {
                    m = intersect(src, pi, ci, stride, dp, dc, dst, m);
                }
            }
            count = m;
            // urmatoarea muchie lucreaza pe rezultatul curent (alternam vectorii, fara sa stricam in[])
            src = dst;
            dst = first ? tmp : (dst == out ? tmp : out);
            if (first) first = false;
        }
        resultInTmp = src == tmp;
        return count;
    }

    private static int intersect(float[] s, int pi, int ci, int stride, float dp, float dc, float[] d, int m) {
        float t = dp / (dp - dc);
        for (int k = 0; k < stride; k++) d[m * stride + k] = s[pi + k] + (s[ci + k] - s[pi + k]) * t;
        return m + 1;
    }

    /** Punctul e in interiorul poligonului convex (cu margine de toleranta m spre interior; m>0 = mai strans). */
    public static boolean inside(float[] clip, int clipN, float x, float y) {
        for (int e = 0; e < clipN; e++) {
            int e2 = (e + 1) % clipN;
            float ax = clip[e * 2];
            float ay = clip[e * 2 + 1];
            float ex = clip[e2 * 2] - ax;
            float ey = clip[e2 * 2 + 1] - ay;
            if (ex * (y - ay) - ey * (x - ax) < 0f) return false;
        }
        return true;
    }

    /** Rezultat al taierii unui segment (Cyrus-Beck): parametrii t0..t1 in [0,1]. */
    public static float t0;
    public static float t1;

    /** Taie segmentul (x0,y0)-(x1,y1) pe poligon; intoarce false daca e complet afara. Parametrii raman in t0/t1. */
    public static boolean clipSegment(float[] clip, int clipN, float x0, float y0, float x1, float y1) {
        float a = 0f;
        float b = 1f;
        float dx = x1 - x0;
        float dy = y1 - y0;
        for (int e = 0; e < clipN; e++) {
            int e2 = (e + 1) % clipN;
            float ax = clip[e * 2];
            float ay = clip[e * 2 + 1];
            float ex = clip[e2 * 2] - ax;
            float ey = clip[e2 * 2 + 1] - ay;
            // interior: ex*(y-ay) - ey*(x-ax) >= 0
            float num = ex * (y0 - ay) - ey * (x0 - ax);
            float den = ex * dy - ey * dx;
            if (Math.abs(den) < 1e-9f) {
                if (num < 0f) return false;
            } else {
                float t = -num / den;
                if (den > 0f) {
                    if (t > a) a = t;
                } else if (t < b) {
                    b = t;
                }
                if (a > b) return false;
            }
        }
        t0 = a;
        t1 = b;
        return true;
    }
}
