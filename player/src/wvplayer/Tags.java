/*
 * Tags - reads the APEv2 tag that WavPack files carry at their end
 * (Title, Artist, Album, ... and "Cover Art (Front)" pictures).
 *
 * Java 1.3-level code.
 */
package wvplayer;

import java.io.RandomAccessFile;
import java.util.Hashtable;

class Tags {

    /** Text items, keys lowercased ("title", "artist", "album", ...). */
    final Hashtable text = new Hashtable();
    /** Front cover picture bytes (JPEG/PNG), or null. */
    byte[] cover;
    /** Where the front cover sits in the file (so it can be re-read later without keeping it in memory). */
    long coverOffset = -1;
    int coverLength = 0;

    String get(String key) {
        return (String) text.get(key);
    }

    /**
     * @param keepCover  true to load the picture bytes now; false only records where it is
     */
    static Tags read(String path, boolean keepCover) {
        Tags t = new Tags();
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(path, "r");
            long len = f.length();
            long end = len;
            // skip an ID3v1 tag if present
            if (len >= 128) {
                byte[] id3 = new byte[3];
                f.seek(len - 128);
                f.readFully(id3);
                if (id3[0] == 'T' && id3[1] == 'A' && id3[2] == 'G') end = len - 128;
            }
            if (end < 32) return t;
            byte[] foot = new byte[32];
            f.seek(end - 32);
            f.readFully(foot);
            if (!new String(foot, 0, 8, "ISO-8859-1").equals("APETAGEX")) return t;
            int size = le32(foot, 12);          // items + footer
            int count = le32(foot, 16);
            if (size < 32 || size > end || size > 64 * 1024 * 1024) return t;
            long itemsStart = end - size;
            int itemsLen = size - 32;
            // read everything except big binary values in one go
            f.seek(itemsStart);
            long pos = itemsStart;
            for (int i = 0; i < count && pos < end - 32; i++) {
                byte[] h = new byte[8];
                f.seek(pos);
                f.readFully(h);
                int vlen = le32(h, 0);
                int flags = le32(h, 4);
                // key: ASCII up to NUL
                StringBuffer key = new StringBuffer();
                int c;
                int keyBytes = 0;
                while ((c = f.read()) > 0 && keyBytes < 255) {
                    key.append((char) c);
                    keyBytes++;
                }
                long valuePos = pos + 8 + keyBytes + 1;
                if (vlen < 0 || valuePos + vlen > end) break;
                String k = key.toString().toLowerCase();
                int type = (flags >> 1) & 3;   // 0 text, 1 binary, 2 external
                if (type == 0 && vlen <= 4096) {
                    byte[] v = new byte[vlen];
                    f.seek(valuePos);
                    f.readFully(v);
                    String s = new String(v, "UTF-8");
                    int nul = s.indexOf('\0');  // multiple values: keep the first
                    if (nul >= 0) s = s.substring(0, nul);
                    t.text.put(k, s.trim());
                } else if (type == 1 && k.equals("cover art (front)") && vlen > 16) {
                    // value = file name, NUL, picture data
                    byte[] head = new byte[Math.min(vlen, 512)];
                    f.seek(valuePos);
                    f.readFully(head);
                    int nul = 0;
                    while (nul < head.length && head[nul] != 0) nul++;
                    if (nul < head.length) {
                        t.coverOffset = valuePos + nul + 1;
                        t.coverLength = vlen - nul - 1;
                        if (keepCover) {
                            t.cover = new byte[t.coverLength];
                            f.seek(t.coverOffset);
                            f.readFully(t.cover);
                        }
                    }
                }
                pos = valuePos + vlen;
            }
            if (itemsLen < 0) return t;
        } catch (Throwable e) {
            // no or broken tag: just no metadata
        } finally {
            try { if (f != null) f.close(); } catch (Throwable e) { }
        }
        return t;
    }

    /** Loads picture bytes recorded by read(path, false). */
    static byte[] readCover(String path, long offset, int length) {
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(path, "r");
            byte[] b = new byte[length];
            f.seek(offset);
            f.readFully(b);
            return b;
        } catch (Throwable e) {
            return null;
        } finally {
            try { if (f != null) f.close(); } catch (Throwable e) { }
        }
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }
}
