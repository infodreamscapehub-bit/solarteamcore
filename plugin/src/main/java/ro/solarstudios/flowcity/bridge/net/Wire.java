package ro.solarstudios.flowcity.bridge.net;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Codificare compatibila cu PacketBuffer din Minecraft 1.16.5
 * (VarInt, UTF cu prefix VarInt, int/long big-endian).
 */
public final class Wire {
    private Wire() {}

    public static final class Out {
        private final ByteArrayOutputStream bos = new ByteArrayOutputStream(128);

        public Out u8(int v) { bos.write(v & 0xFF); return this; }

        public Out bool(boolean v) { return u8(v ? 1 : 0); }

        public Out varInt(int v) {
            while ((v & -128) != 0) {
                bos.write((v & 127) | 128);
                v >>>= 7;
            }
            bos.write(v);
            return this;
        }

        public Out i32(int v) {
            bos.write(v >>> 24);
            bos.write(v >>> 16);
            bos.write(v >>> 8);
            bos.write(v);
            return this;
        }

        public Out i64(long v) {
            i32((int) (v >>> 32));
            i32((int) v);
            return this;
        }

        public Out utf(String s) {
            if (s == null) s = "";
            byte[] b = s.getBytes(StandardCharsets.UTF_8);
            if (b.length > 32767) {
                s = s.substring(0, Math.min(s.length(), 8000));
                b = s.getBytes(StandardCharsets.UTF_8);
            }
            varInt(b.length);
            bos.write(b, 0, b.length);
            return this;
        }

        /** Matrice de bytes cu prefix VarInt (compatibil PacketBuffer.writeByteArray). */
        public Out byteArray(byte[] data) {
            varInt(data.length);
            bos.write(data, 0, data.length);
            return this;
        }

        public byte[] bytes() { return bos.toByteArray(); }
    }

    public static final class In {
        private final byte[] b;
        private int p;

        public In(byte[] b) { this.b = b; }

        public int u8() { return b[p++] & 0xFF; }

        public boolean bool() { return u8() != 0; }

        public int varInt() {
            int out = 0;
            int shift = 0;
            int cur;
            do {
                cur = u8();
                out |= (cur & 127) << shift;
                shift += 7;
                if (shift > 35) throw new IllegalStateException("VarInt prea mare");
            } while ((cur & 128) == 128);
            return out;
        }

        public int i32() {
            return (u8() << 24) | (u8() << 16) | (u8() << 8) | u8();
        }

        public long i64() {
            long hi = i32() & 0xFFFFFFFFL;
            long lo = i32() & 0xFFFFFFFFL;
            return (hi << 32) | lo;
        }

        public String utf() {
            int len = varInt();
            if (len < 0 || len > 32767 * 4 || p + len > b.length) throw new IllegalStateException("UTF invalid");
            String s = new String(b, p, len, StandardCharsets.UTF_8);
            p += len;
            return s;
        }

        /** Matrice de bytes cu prefix VarInt (compatibil PacketBuffer.readByteArray). */
        public byte[] byteArray(int max) {
            int len = varInt();
            if (len < 0 || len > max || p + len > b.length) throw new IllegalStateException("byteArray invalid");
            byte[] out = new byte[len];
            System.arraycopy(b, p, out, 0, len);
            p += len;
            return out;
        }

        public int remaining() { return b.length - p; }
    }
}
