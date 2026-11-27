import java.io.*;
import java.util.*;
import bdclip.ClipWriter;

/** Full chunked run vs. resumed run from every chunk: files must be identical. */
public class ResumeTest {
    static byte[] tone(long start, int n, int bytes) {
        byte[] b = new byte[n * 2 * bytes];
        int o = 0;
        for (int i = 0; i < n; i++) {
            long s = start + i;
            int l = (int) (3000000 * Math.sin(s * 0.0571)), r = (int) (2000000 * Math.sin(s * 0.0313));
            for (int c = 0; c < 2; c++) {
                int v = c == 0 ? l : r;
                if (bytes == 2) v >>= 8;
                if (bytes == 3) b[o++] = (byte) (v >> 16);
                b[o++] = (byte) (v >> 8); b[o++] = (byte) v;
            }
        }
        return b;
    }
    static Map<Integer, byte[]> run(byte[][] gop, int rate, int bits, long total, long[] cuts, long[] state) throws Exception {
        final Map<Integer, ByteArrayOutputStream> files = new TreeMap<>();
        final Map<Integer, byte[]> infos = new TreeMap<>();
        ClipWriter.ChunkSink sink = new ClipWriter.ChunkSink() {
            public void chunkDone(int n, OutputStream os, byte[] ci) { infos.put(n, ci); }
            public OutputStream nextChunk(int n) { ByteArrayOutputStream b = new ByteArrayOutputStream(); files.put(n, b); return b; }
        };
        int first = state == null ? 0 : (int) state[4];
        ClipWriter w = new ClipWriter(sink.nextChunk(first), rate, 2, bits, gop, 4, 4);
        w.setChunking(cuts, sink);
        w.setTotalSamples(total);
        long s = 0;
        if (state != null) { w.resume(state); s = w.getResumeAudioFrame() * w.samplesPerFrame(); }
        while (s < total) {
            int n = (int) Math.min(3001, total - s);    // odd block size on purpose
            byte[] b = tone(s, n, bits / 8);
            w.writePcm(b, 0, b.length);
            s += n;
        }
        w.finish();
        Map<Integer, byte[]> r = new TreeMap<>();
        for (Integer k : files.keySet()) r.put(k, files.get(k).toByteArray());
        for (Integer k : infos.keySet()) r.put(1000 + k, infos.get(k));
        if (state == null) { stateOut = new long[cuts.length + 1][]; for (int i = 1; i <= cuts.length; i++) stateOut[i] = w.getCutState(i); }
        return r;
    }
    static long[][] stateOut;
    public static void main(String[] a) throws Exception {
        byte[] m2v = java.nio.file.Files.readAllBytes(new File(a[0]).toPath());
        byte[][] gop = ClipWriter.splitMpeg2(m2v);
        int[][] fmts = { { 48000, 24 }, { 48000, 16 }, { 96000, 24 } };
        for (int[] f : fmts) {
            long total = (long) (f[0] * 47.3);
            long[] cuts = { 150, 450, 600, 1200 };          // 5, 15, 20, 40 s
            Map<Integer, byte[]> full = run(gop, f[0], f[1], total, cuts, null);
            int bad = 0;
            for (int c = 1; c <= cuts.length; c++) {
                Map<Integer, byte[]> part = run(gop, f[0], f[1], total, cuts, stateOut[c]);
                for (Integer k : part.keySet()) if (!Arrays.equals(part.get(k), full.get(k))) { bad++; System.out.println("  differs: resume " + c + " file " + k); }
            }
            System.out.println(f[0] + " Hz " + f[1] + "-bit: " + (full.size() / 2) + " chunks, resume mismatches: " + bad);
        }
        // long stream, resumed deep (the cut-state table must grow past its first doubling)
        long total = 48000L * 400;
        long[] cuts = new long[79];
        for (int i = 0; i < cuts.length; i++) cuts[i] = (i + 1) * 150L;
        Map<Integer, byte[]> full = run(gop, 48000, 24, total, cuts, null);
        int bad = 0;
        for (int c : new int[] { 17, 33, 47, 64, 75 }) {
            Map<Integer, byte[]> part = run(gop, 48000, 24, total, cuts, stateOut[c]);
            for (Integer k : part.keySet()) if (!Arrays.equals(part.get(k), full.get(k))) bad++;
        }
        System.out.println("80-chunk stream resumed at 17/33/47/64/75: mismatches " + bad);
    }
}
