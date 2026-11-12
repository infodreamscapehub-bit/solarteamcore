package ro.solarstudios.flowcity.module;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** GZIP pentru payload-uri JSON mari. */
public final class Gz {
    private Gz() {}

    public static byte[] pack(byte[] data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length / 2 + 16);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(data);
        }
        return bos.toByteArray();
    }

    public static byte[] unpack(byte[] data, int max) throws IOException {
        try (GZIPInputStream gz = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = gz.read(buf)) > 0) {
                bos.write(buf, 0, n);
                if (bos.size() > max) throw new IOException("payload prea mare");
            }
            return bos.toByteArray();
        }
    }
}
