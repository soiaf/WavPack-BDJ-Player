package bdclip;

/** CRC-32/MPEG-2 (poly 0x04C11DB7, init 0xFFFFFFFF, no reflection, no final xor). */
final class Crc32 {
    private static final int[] T = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int k = 0; k < 8; k++) {
                c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : c << 1;
            }
            T[i] = c;
        }
    }

    private Crc32() {}

    static int mpeg(byte[] b, int off, int len) {
        int crc = 0xFFFFFFFF;
        for (int i = off; i < off + len; i++) {
            crc = (crc << 8) ^ T[((crc >>> 24) ^ b[i]) & 0xFF];
        }
        return crc;
    }
}
