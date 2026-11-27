/*
 * WvSeek - open a WavPack file at the block that contains a given sample.
 *
 * Every WavPack block starts with a 32-byte header ("wvpk", size, ..., block
 * index, block samples, flags) and can be decoded on its own, so the decoder
 * can be started at any block: only the headers are read to get there.
 *
 * Java 1.3-level code.
 */
package wvplayer;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

final class WvSeek {

    static final int INITIAL_BLOCK = 0x800;

    /** Byte offset of the last initial block starting at or before sample, or 0. */
    static long blockOffset(String path, long sample) {
        if (sample <= 0) return 0;
        RandomAccessFile f = null;
        try {
            f = new RandomAccessFile(path, "r");
            return blockOffset(f, sample);
        } catch (IOException e) {
            return 0;
        } finally {
            try { if (f != null) f.close(); } catch (IOException e) { }
        }
    }

    /*
     * Reading every header from the start meant one disc seek per block: on the Oppo
     * that took over 20 s to reach the end of a long 32-bit track. So: binary search
     * (each step reads one window and finds the next header in it), then a short
     * header-by-header walk over the last stretch, exactly as before.
     */
    private static final int WINDOW = 256 * 1024;
    private static final long WALK = 256 * 1024;      // walk the last 256 KB header by header

    static long blockOffset(RandomAccessFile f, long sample) throws IOException {
        long len = f.length();
        byte[] h = new byte[32];
        long lo = 0;                                     // a header with index <= sample
        long hi = len;                                   // no initial header <= sample starts at or after hi
        byte[] win = new byte[WINDOW];
        long[] found = new long[2];
        while (hi - lo > WALK) {
            long mid = lo + (hi - lo) / 2;
            if (!nextInitial(f, mid, hi, win, found)) {
                hi = mid;                                // nothing usable between mid and hi
                continue;
            }
            if (found[1] <= sample) lo = found[0];
            else hi = mid;
        }
        // the walk from lo, as the original code did from 0
        long pos = lo, best = 0;
        while (pos + 32 <= len) {
            f.seek(pos);
            f.readFully(h);
            if (h[0] != 'w' || h[1] != 'v' || h[2] != 'p' || h[3] != 'k') break;
            long size = le32(h, 4) + 8;
            long index = le32(h, 16);
            long count = le32(h, 20);
            long flags = le32(h, 24);
            if (count > 0 && (flags & INITIAL_BLOCK) != 0) {
                if (index > sample) break;
                best = pos;
            }
            if (size < 32) break;
            pos += size;
        }
        return best;
    }

    /**
     * Finds the first initial block header at or after from (and before limit), checked
     * by its fields and by the header that follows it. found = { offset, block index }.
     */
    private static boolean nextInitial(RandomAccessFile f, long from, long limit, byte[] win, long[] found)
            throws IOException {
        long len = f.length();
        long base = from;
        while (base < limit && base + 32 <= len) {
            int n = (int) Math.min(win.length, len - base);
            f.seek(base);
            f.readFully(win, 0, n);
            for (int i = 0; i + 32 <= n; i++) {
                if (win[i] != 'w' || win[i + 1] != 'v' || win[i + 2] != 'p' || win[i + 3] != 'k') continue;
                long pos = base + i;
                if (pos >= limit) return false;
                long size = le32(win, i + 4) + 8;
                int version = (win[i + 8] & 0xff) | ((win[i + 9] & 0xff) << 8);
                long count = le32(win, i + 20);
                long flags = le32(win, i + 24);
                if (size < 32 || size > (16L << 20) || version < 0x402 || version > 0x410) continue;
                if (!headerAt(f, pos + size, len)) continue;   // the next block must follow
                if (count > 0 && (flags & INITIAL_BLOCK) != 0) {
                    found[0] = pos;
                    found[1] = le32(win, i + 16);
                    return true;
                }
            }
            base += n - 31;                              // overlap so a header across the edge is seen
        }
        return false;
    }

    private static boolean headerAt(RandomAccessFile f, long pos, long len) throws IOException {
        if (pos == len) return true;                     // last block of the file
        if (pos + 4 > len) return false;
        byte[] b = new byte[4];
        f.seek(pos);
        f.readFully(b);
        return b[0] == 'w' && b[1] == 'v' && b[2] == 'p' && b[3] == 'k';
    }

    /** Stream positioned at the block holding sample (decoding starts at that block's first sample). */
    static DataInputStream open(String path, long sample) throws IOException {
        InputStream in = new FileInputStream(path);
        long off = blockOffset(path, sample);
        while (off > 0) {
            long n = in.skip(off);
            if (n <= 0) throw new IOException("skip failed");
            off -= n;
        }
        return new DataInputStream(new BufferedInputStream(in, 65536));
    }

    private static long le32(byte[] b, int o) {
        return (b[o] & 0xFFL) | ((b[o + 1] & 0xFFL) << 8) | ((b[o + 2] & 0xFFL) << 16) | ((b[o + 3] & 0xFFL) << 24);
    }

    private WvSeek() { }
}
