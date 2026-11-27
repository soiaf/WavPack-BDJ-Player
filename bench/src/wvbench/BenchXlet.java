/*
 * Benchmark disc (v2): measures, on the player itself, how fast its Java runs the
 * work the WavPack player does, so optimisation targets the player's real costs.
 * Starts automatically; results stay on screen in two columns. Java 1.3-level code.
 *
 * Sections: Java basics, disc reads, WavPack decoding, resampling, sample conversion,
 * stream writing, storage writes, and decoding while a clip plays (thread priorities
 * and the whole pipeline).
 */
package wvbench;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.Vector;

import javax.media.Manager;
import javax.media.MediaLocator;
import javax.media.Player;
import javax.tv.xlet.Xlet;
import javax.tv.xlet.XletContext;
import javax.tv.xlet.XletStateChangeException;

import org.bluray.net.BDLocator;
import org.havi.ui.HScene;
import org.havi.ui.HSceneFactory;

import bdclip.ClipWriter;
import bdclip.Resampler;

public class BenchXlet implements Xlet {

    private HScene scene;
    private Screen screen;
    private final Vector lines = new Vector();
    private volatile String status = "starting";
    private String root, dir;
    private long startMs;

    public void initXlet(XletContext c) throws XletStateChangeException {
        root = System.getProperty("bluray.vfs.root");
        dir = root + "/BDMV/JAR/00000/bench";
        try {
            scene = HSceneFactory.getInstance().getDefaultHScene();
            scene.setLayout(null);
            screen = new Screen();
            screen.setBounds(0, 0, scene.getWidth() > 0 ? scene.getWidth() : 1920,
                             scene.getHeight() > 0 ? scene.getHeight() : 1080);
            scene.add(screen);
        } catch (Throwable t) { }
    }

    public void startXlet() {
        if (scene != null) scene.setVisible(true);
        startMs = System.currentTimeMillis();
        new Thread(new Runnable() {
            public void run() {
                try {
                    runAll();
                    status = "done after " + (System.currentTimeMillis() - startMs) / 1000
                             + " s - take a photo of this screen";
                } catch (Throwable t) {
                    add("FAILED: " + t);
                    status = "stopped with an error";
                }
                stopPlayback();
                repaint();
            }
        }, "bench").start();
    }

    public void pauseXlet() { }

    public void destroyXlet(boolean u) {
        stopPlayback();
        if (scene != null) {
            scene.setVisible(false);
            HSceneFactory.getInstance().dispose(scene);
        }
    }

    /* ================================================================ plan */

    static volatile int sink;          // keeps the JIT (if any) from removing loops
    static volatile long lsink;
    static volatile float fsink;
    int field;

    private String[] files;            // wv files to decode
    private String big;                // largest wv file (disc read + priority tests)

    private void runAll() throws Exception {
        findFiles();

        add("-- Java (" + prop("java.vm.name") + " " + prop("java.vm.version") + ")");
        Runtime rt = Runtime.getRuntime();
        add("memory: " + rt.totalMemory() / 1024 / 1024 + " MB heap, " + rt.freeMemory() / 1024 / 1024
            + " MB free; clock step " + clockStep() + " ms");
        add("-- basic operations, million per second");
        final int N = 2000000;
        String a = "int add/mul " + micro(N, 0) + " | long add/mul " + micro(N, 1);
        add(a);
        add("long /128 " + micro(N, 8) + " | int >>>7 " + micro(N, 9) + " | float " + micro(N, 2));
        add("long 32x32 mul " + micro(N, 10) + " | split int mul " + micro(N, 11));
        add("array r+w " + micro(N, 3) + " | call " + micro(N, 4) + " | field " + micro(N, 5));
        add("arraycopy " + copySpeed() + " MB/s | " + gcTest());

        add("-- disc read, MB/s (" + name(big) + ")");
        add(discRead());

        add("-- WavPack decoding, x real time");
        for (int i = 0; i < files.length; i++) decodeAll(files[i]);

        add("-- resample 44.1->48 kHz, x real time");
        add("64 taps " + fmt(resample(64, 5)) + " | 48 taps " + fmt(resample(48, 5))
            + " | 32 taps " + fmt(resample(32, 5)));
        add("convert to/from float " + fmt(convert(10)) + "x | stream " + fmt(mux(5)) + "x");

        add("-- storage writes, MB/s (16 MB each)");
        storage();

        add("-- decoding while a clip plays (" + name(big) + ")");
        playbackTests();
    }

    private void findFiles() {
        Vector v = new Vector();
        String[] fixed = { "t16.wv", "t32.wv" };
        for (int i = 0; i < fixed.length; i++)
            if (new File(dir + "/" + fixed[i]).exists()) v.addElement(dir + "/" + fixed[i]);
        String[] all = new File(dir).list();
        if (all != null) {
            java.util.Arrays.sort(all);
            for (int i = 0; i < all.length; i++)
                if (all[i].startsWith("user") && all[i].endsWith(".wv")) v.addElement(dir + "/" + all[i]);
        }
        files = new String[v.size()];
        v.copyInto(files);
        big = files[0];
        for (int i = 0; i < files.length; i++)
            if (new File(files[i]).length() > new File(big).length()) big = files[i];
    }

    /* ================================================================ basics */

    private String micro(int n, int kind) {
        status = "basic operations (" + kind + ")";
        repaint();
        long t0 = System.currentTimeMillis();
        switch (kind) {
            case 0: { int x = 1, y = 3; for (int i = 0; i < n; i++) { x = x * 7 + y; y += i; } sink = x + y; break; }
            case 1: { long x = 1, y = 3; for (int i = 0; i < n; i++) { x = x * 7 + y; y += i; } lsink = x + y; break; }
            case 2: { float x = 1, y = 0.5f; for (int i = 0; i < n; i++) { x = x * 0.999f + y; } fsink = x; break; }
            case 3: { int[] x = new int[1024]; for (int i = 0; i < n; i++) { x[i & 1023] = x[(i + 1) & 1023] + i; } sink = x[5]; break; }
            case 4: { int x = 0; for (int i = 0; i < n; i++) x = f(x, i); sink = x; break; }
            case 5: { for (int i = 0; i < n; i++) field = field + i; sink = field; break; }
            case 8: { long m = 1000; for (int i = 0; i < n; i++) { m += ((m + 128) / 128) * 5 - ((m + 126) / 128) * 2 + (i & 3); } lsink = m; break; }
            case 9: { int m = 1000; for (int i = 0; i < n; i++) { m += ((m + 128) >>> 7) * 5 - (((m + 126) >>> 7) << 1) + (i & 3); } sink = m; break; }
            case 10: { int w = 700, s = 123456789; int acc = 0; for (int i = 0; i < n; i++) { acc += (int) ((w * (long) (s + i) + 512) >> 10); } sink = acc; break; }
            case 11: { int w = 700, s = 123456789; int acc = 0; for (int i = 0; i < n; i++) { int x = s + i; acc += (x >> 10) * w + (((x & 1023) * w + 512) >> 10); } sink = acc; break; }
            default: break;
        }
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        return fmt(n / 1000.0 / ms);
    }

    private static int f(int a, int i) { return a + i; }

    private static long clockStep() {
        long best = Long.MAX_VALUE;
        long last = System.currentTimeMillis();
        int changes = 0;
        long guard = last;
        while (changes < 10 && System.currentTimeMillis() - guard < 2000) {
            long t = System.currentTimeMillis();
            if (t != last) {
                if (t - last < best) best = t - last;
                last = t;
                changes++;
            }
        }
        return best == Long.MAX_VALUE ? -1 : best;
    }

    private String copySpeed() {
        status = "array copy";
        repaint();
        byte[] a = new byte[1 << 20], b = new byte[1 << 20];
        long t0 = System.currentTimeMillis();
        int n = 0;
        while (System.currentTimeMillis() - t0 < 500) { System.arraycopy(a, 0, b, 0, a.length); n++; }
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        return fmt(n * 1000.0 / ms);
    }

    private String gcTest() {
        status = "memory allocation";
        repaint();
        long t0 = System.currentTimeMillis(), worst = 0;
        Object keep = null;
        for (int i = 0; i < 1500; i++) {
            long s = System.currentTimeMillis();
            byte[] x = new byte[65536];
            x[i & 65535] = 1;
            if ((i & 15) == 0) keep = x;
            long d = System.currentTimeMillis() - s;
            if (d > worst) worst = d;
        }
        sink = keep != null ? 1 : 0;
        long total = System.currentTimeMillis() - t0;
        long g0 = System.currentTimeMillis();
        System.gc();
        long gc = System.currentTimeMillis() - g0;
        return "alloc 94 MB " + total + " ms, worst " + worst + " ms, gc " + gc + " ms";
    }

    /* ================================================================ disc */

    private String discRead() {
        int[] sizes = { 8192, 65536, 524288 };
        StringBuffer b = new StringBuffer();
        long len = new File(big).length();
        long part = Math.min(8L << 20, len / 3);
        for (int i = 0; i < sizes.length; i++) {
            status = "reading the disc, " + sizes[i] / 1024 + " KB blocks";
            repaint();
            try {
                FileInputStream in = new FileInputStream(big);
                long skip = part * i;
                while (skip > 0) { long s = in.skip(skip); if (s <= 0) break; skip -= s; }
                byte[] buf = new byte[sizes[i]];
                long got = 0;
                long t0 = System.currentTimeMillis();
                while (got < part) {
                    int n = in.read(buf, 0, (int) Math.min(buf.length, part - got));
                    if (n <= 0) break;
                    got += n;
                }
                long ms = Math.max(1, System.currentTimeMillis() - t0);
                in.close();
                b.append(i == 0 ? "" : " | ").append(sizes[i] / 1024).append(" KB ").append(fmt(got / 1024.0 / 1024.0 * 1000 / ms));
            } catch (Throwable t) {
                b.append(" | ").append(sizes[i] / 1024).append(" KB ").append(t.getClass().getName());
            }
        }
        return b.toString();
    }

    /* ================================================================ decoders */

    /** One decoder variant: open a file, decode into a buffer. */
    private static abstract class Dec {
        int ch;
        long rate;
        int bytes;
        DataInputStream in;
        abstract void open(DataInputStream in) throws Exception;
        abstract long unpack(int[] buf, int frames);
        abstract int bufSize();
        void close() { try { in.close(); } catch (Throwable t) { } }
    }

    private static Dec dec(int v) {
        return new Dec() {
            com.beatofthedrum.wvdecoder.WavpackContext w;
            void open(DataInputStream i) throws Exception {
                in = i; w = com.beatofthedrum.wvdecoder.WavPackUtils.WavpackOpenFileInput(i);
                if (w.error) throw new IOException(w.error_message);
                ch = com.beatofthedrum.wvdecoder.WavPackUtils.WavpackGetReducedChannels(w);
                rate = com.beatofthedrum.wvdecoder.WavPackUtils.WavpackGetSampleRate(w);
                bytes = com.beatofthedrum.wvdecoder.WavPackUtils.WavpackGetBytesPerSample(w);
            }
            long unpack(int[] b, int n) { return com.beatofthedrum.wvdecoder.WavPackUtils.WavpackUnpackSamples(w, b, n); }
            int bufSize() { return 4096; }
        };
    }

    private long lastSum;

    /** Decodes the first seconds of a file; returns x real time (lastSum = checksum). */
    private double decode(String path, int variant, double seconds) throws Exception {
        Dec d = dec(variant);
        d.open(new DataInputStream(new BufferedInputStream(new FileInputStream(path), 65536)));
        int[] buf = new int[d.bufSize()];
        long total = 0, want = (long) (d.rate * seconds), ms = 0, sum = 0;
        long wall0 = System.currentTimeMillis();
        while (total < want && System.currentTimeMillis() - wall0 < 60000) {
            long t0 = System.currentTimeMillis();
            long g = d.unpack(buf, buf.length / d.ch);
            ms += System.currentTimeMillis() - t0;
            if (g <= 0) break;
            int n = (int) g * d.ch;
            for (int k = 0; k < n; k++) sum = sum * 31 + buf[k];
            total += g;
        }
        d.close();
        lastSum = sum ^ total;
        return total * 1000.0 / d.rate / Math.max(1, ms);
    }

    private void decodeAll(String path) {
        status = "decoding " + name(path);
        repaint();
        String info = "";
        try {
            Dec d = dec(0);
            d.open(new DataInputStream(new BufferedInputStream(new FileInputStream(path), 4096)));
            info = "  (" + d.bytes * 8 + "-bit " + d.rate / 100 / 10.0 + " kHz)";
            d.close();
            add(name(path) + ": " + fmt(decode(path, 0, 6)) + "x" + info);
        } catch (Throwable t) {
            add(name(path) + ": " + t);
        }
    }

    /* ================================================================ resample etc */

    private double resample(int taps, int seconds) {
        status = "resampling, " + taps + " taps";
        repaint();
        int block = 4096;
        float[] in = new float[block * 2];
        for (int i = 0; i < in.length; i++) in[i] = (float) (Math.sin(i * 0.01) * 4000000);
        float[] out = new float[block * 2 * 2 + 64];
        Resampler r = new Resampler(44100, 48000, 2, taps);
        long done = 0, want = 44100L * seconds;
        long t0 = System.currentTimeMillis();
        while (done < want && System.currentTimeMillis() - t0 < 60000) {
            r.process(in, block, out);
            done += block;
        }
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        return done * 1000.0 / 44100 / ms;
    }

    private double convert(int seconds) {
        status = "sample conversion";
        repaint();
        int frames = 4096;
        int[] s = new int[frames * 2];
        for (int i = 0; i < s.length; i++) s[i] = (i * 7919) << 8;
        float[] f = new float[frames * 2];
        byte[] pcm = new byte[frames * 6];
        long done = 0, want = 44100L * seconds;
        long t0 = System.currentTimeMillis();
        while (done < want) {
            toFloat24(s, frames, 2, 4, f);
            floatToPcm24(f, frames, pcm);
            done += frames;
        }
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        return done * 1000.0 / 44100 / ms;
    }

    static void toFloat24(int[] s, int frames, int ch, int bps, float[] out) {
        int shift = 24 - bps * 8;
        int o = 0;
        for (int f = 0; f < frames; f++) {
            int l = s[f * ch];
            int r = (ch == 1) ? l : s[f * ch + 1];
            if (shift >= 0) { l <<= shift; r <<= shift; } else { l >>= -shift; r >>= -shift; }
            out[o++] = l;
            out[o++] = r;
        }
    }

    static int floatToPcm24(float[] in, int frames, byte[] out) {
        int o = 0;
        int n = frames * 2;
        for (int i = 0; i < n; i++) {
            float x = in[i];
            int v = (int) (x >= 0 ? x + 0.5f : x - 0.5f);
            if (v > 8388607) v = 8388607; else if (v < -8388608) v = -8388608;
            out[o++] = (byte) (v >> 16);
            out[o++] = (byte) (v >> 8);
            out[o++] = (byte) v;
        }
        return o;
    }

    private byte[][] gop;

    private double mux(int seconds) throws Exception {
        status = "writing stream";
        repaint();
        if (gop == null) gop = ClipWriter.splitMpeg2(readAll(dir + "/black1080i.m2v"));
        ClipWriter w = new ClipWriter(new OutputStream() {
            public void write(int b) { }
            public void write(byte[] b, int o, int l) { }
        }, 48000, 2, 24, gop, 4, 4);
        byte[] pcm = new byte[4800 * 6];
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < seconds * 10; i++) w.writePcm(pcm, 0, pcm.length);
        w.finish();
        long ms = Math.max(1, System.currentTimeMillis() - t0);
        return seconds * 1000.0 / ms;
    }

    /* ================================================================ storage */

    private File storageDir;

    private File storage() {
        try {
            String bu = System.getProperty("bluray.bindingunit.root");
            if (bu == null) { add("no local storage on this player"); return null; }
            File d = new File(bu + "/" + orgHex() + "/" + discHex());
            d.mkdirs();
            storageDir = d;
        } catch (Throwable t) {
            add("storage: " + t);
            return null;
        }
        final int MB16 = 16 << 20;
        String[] names = { "64K buffer", "256K buffer", "1M buffer", "256K + sync/2MB", "RandomAccessFile" };
        int[] bufs = { 65536, 262144, 1048576, 262144, 262144 };
        StringBuffer line = new StringBuffer();
        for (int i = 0; i < names.length; i++) {
            status = "storage writes: " + names[i];
            repaint();
            File f = new File(storageDir, "bench" + i + ".tmp");
            String r;
            try {
                byte[] chunk = new byte[192 * 32];        // written in 6 KB pieces, like the muxer
                for (int k = 0; k < chunk.length; k++) chunk[k] = (byte) (k * 13);
                long t0 = System.currentTimeMillis();
                if (i == 4) {
                    RandomAccessFile raf = new RandomAccessFile(f, "rw");
                    byte[] big = new byte[bufs[i]];
                    for (long w = 0; w < MB16; w += big.length) raf.write(big, 0, big.length);
                    raf.close();
                } else {
                    FileOutputStream fos = new FileOutputStream(f);
                    OutputStream os = new BufferedOutputStream(fos, bufs[i]);
                    long w = 0, synced = 0;
                    while (w < MB16) {
                        os.write(chunk, 0, chunk.length);
                        w += chunk.length;
                        if (i == 3 && w - synced >= (2 << 20)) {
                            os.flush();
                            fos.getFD().sync();
                            synced = w;
                        }
                    }
                    os.flush();
                    if (i == 3) fos.getFD().sync();
                    os.close();
                }
                long ms = Math.max(1, System.currentTimeMillis() - t0);
                r = fmt(16.0 * 1000 / ms);
            } catch (Throwable t) {
                r = t.getClass().getName().substring(t.getClass().getName().lastIndexOf('.') + 1);
                if (t.getMessage() != null) r += " " + t.getMessage();
            }
            long d0 = System.currentTimeMillis();
            boolean del = f.delete();
            long dms = System.currentTimeMillis() - d0;
            add(names[i] + ": " + r + (i == 0 ? "  (delete " + dms + " ms" + (del ? ")" : ", failed)") : ""));
        }
        return storageDir;
    }

    private String orgHex() throws Exception {
        byte[] b = readAll(root + "/CERTIFICATE/id.bdmv");
        long org = ((b[40] & 0xFFL) << 24) | ((b[41] & 0xFF) << 16) | ((b[42] & 0xFF) << 8) | (b[43] & 0xFF);
        return Long.toHexString(org);
    }

    private String discHex() throws Exception {
        byte[] b = readAll(root + "/CERTIFICATE/id.bdmv");
        StringBuffer s = new StringBuffer();
        for (int i = 44; i < 60; i++) {
            String h = Integer.toHexString(b[i] & 0xFF);
            if (h.length() < 2) h = "0" + h;
            s.append(h);
        }
        String d = s.toString();
        while (d.length() > 1 && d.charAt(0) == '0') d = d.substring(1);
        return d;
    }

    /* ================================================================ playback */

    private Player player;

    private void stopPlayback() {
        Player p = player;
        player = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable t) { }
            try { p.close(); } catch (Throwable t) { }
        }
    }

    private void playbackTests() throws Exception {
        status = "decoding without playback (low priority)";
        repaint();
        String base = fmt(threadDecode(Thread.MIN_PRIORITY, 8));
        status = "starting the clip";
        repaint();
        String started;
        try {
            player = Manager.createPlayer(new MediaLocator(new BDLocator("bd://PLAYLIST:00001").toExternalForm()));
            player.start();
            Thread.sleep(3000);
            double t1 = player.getMediaTime().getSeconds();
            Thread.sleep(1000);
            double t2 = player.getMediaTime().getSeconds();
            started = "clip time " + fmt(t1) + " -> " + fmt(t2) + " s";
        } catch (Throwable t) {
            started = "clip did not start: " + t;
        }
        add("no clip: low " + base + "x | " + started);
        String lo = fmt(threadDecode(Thread.MIN_PRIORITY, 8));
        String no = fmt(threadDecode(Thread.NORM_PRIORITY, 8));
        String hi = fmt(threadDecode(Thread.MAX_PRIORITY, 8));
        add("with clip: low " + lo + "x | normal " + no + "x | high " + hi + "x");
        if (storageDir != null) {
            String a = pipeline(Thread.MIN_PRIORITY, 15);
            String b = pipeline(Thread.NORM_PRIORITY, 15);
            add("whole pipeline: low " + a + " | normal " + b);
        } else {
            add("whole pipeline: no storage");
        }
        stopPlayback();
    }

    private volatile double threadResult;
    private volatile String threadText;

    private double threadDecode(int priority, final double seconds) throws Exception {
        threadResult = -1;
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    long t0 = System.currentTimeMillis();
                    Dec d = dec(0);
                    d.open(new DataInputStream(new BufferedInputStream(new FileInputStream(big), 65536)));
                    int[] buf = new int[d.bufSize()];
                    long total = 0, want = (long) (d.rate * seconds);
                    while (total < want) {
                        long g = d.unpack(buf, buf.length / d.ch);
                        if (g <= 0) break;
                        total += g;
                    }
                    d.close();
                    threadResult = total * 1000.0 / d.rate / Math.max(1, System.currentTimeMillis() - t0);
                } catch (Throwable e) {
                    threadResult = -2;
                }
            }
        }, "decode");
        t.setPriority(priority);
        t.start();
        t.join(120000);
        return threadResult;
    }

    /** Decode + resample + convert + stream + storage writes, as the player does; "2.3x". */
    private String pipeline(int priority, final double seconds) throws Exception {
        status = "whole pipeline, " + (priority == Thread.MIN_PRIORITY ? "low" : "normal") + " priority";
        repaint();
        threadText = "timeout";
        if (gop == null) gop = ClipWriter.splitMpeg2(readAll(dir + "/black1080i.m2v"));
        Thread t = new Thread(new Runnable() {
            public void run() {
                File f = new File(storageDir, "benchpipe.tmp");
                try {
                    long t0 = System.currentTimeMillis();
                    Dec d = dec(0);
                    d.open(new DataInputStream(new BufferedInputStream(new FileInputStream(big), 65536)));
                    final FileOutputStream fos = new FileOutputStream(f);
                    OutputStream os = new BufferedOutputStream(fos, 262144);
                    ClipWriter w = new ClipWriter(os, 48000, 2, 24, gop, 4, 4);
                    int[] buf = new int[d.bufSize()];
                    int framesPer = buf.length / d.ch;
                    float[] fin = new float[framesPer * 2];
                    float[] fout = new float[framesPer * 2 * 5 + 64];
                    byte[] pcm = new byte[fout.length * 3];
                    Resampler rs = (d.rate == 48000) ? null : new Resampler((int) d.rate, 48000, 2, 64);
                    int bps = d.bytes;
                    long total = 0, want = (long) (d.rate * seconds), sinceSync = 0;
                    while (total < want) {
                        int g = (int) d.unpack(buf, framesPer);
                        if (g <= 0) break;
                        toFloat24(buf, g, d.ch, bps, fin);
                        int nb;
                        if (rs != null) {
                            int of = rs.process(fin, g, fout);
                            nb = floatToPcm24(fout, of, pcm);
                        } else {
                            nb = floatToPcm24(fin, g, pcm);
                        }
                        w.writePcm(pcm, 0, nb);
                        total += g;
                        sinceSync += g;
                        if (sinceSync >= d.rate * 5) {          // like a 5 s chunk being finished
                            os.flush();
                            fos.getFD().sync();
                            sinceSync = 0;
                        }
                    }
                    w.finish();
                    os.flush();
                    fos.getFD().sync();
                    os.close();
                    d.close();
                    long ms = Math.max(1, System.currentTimeMillis() - t0);
                    threadText = fmt(total * 1000.0 / d.rate / ms) + "x";
                } catch (Throwable e) {
                    threadText = e.getClass().getName().substring(e.getClass().getName().lastIndexOf('.') + 1);
                }
                f.delete();
            }
        }, "pipeline");
        t.setPriority(priority);
        t.start();
        t.join(180000);
        return threadText;
    }

    /* ================================================================ helpers */

    private static byte[] readAll(String p) throws Exception {
        File f = new File(p);
        byte[] b = new byte[(int) f.length()];
        DataInputStream in = new DataInputStream(new FileInputStream(f));
        in.readFully(b);
        in.close();
        return b;
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static String prop(String k) {
        try {
            String v = System.getProperty(k);
            return v != null ? v : "?";
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String fmt(double v) {
        if (v < 0) return "ERR";
        if (v >= 100) return String.valueOf(Math.round(v));
        long t = Math.round(v * 10);
        return (t / 10) + "." + (t % 10);
    }

    /* ================================================================ ui */

    private void add(String s) {
        System.out.println("[Bench] " + s);
        lines.addElement(s);
        repaint();
    }

    private void repaint() { if (screen != null) screen.repaint(); }

    private static final int PER_COLUMN = 27;

    private class Screen extends Component {
        public void paint(Graphics g) {
            int w = getWidth() > 0 ? getWidth() : 1920;
            double k = w / 1920.0;
            g.setColor(new Color(20, 24, 40));
            g.fillRect(0, 0, w, getHeight() > 0 ? getHeight() : 1080);
            g.setColor(Color.white);
            g.setFont(new Font("SansSerif", Font.PLAIN, (int) (34 * k)));
            g.drawString("WavPack player benchmark", (int) (90 * k), (int) (80 * k));
            g.setFont(new Font("SansSerif", Font.PLAIN, (int) (24 * k)));
            g.setColor(new Color(255, 210, 90));
            g.drawString("Now: " + status, (int) (90 * k), (int) (122 * k));
            g.setFont(new Font("SansSerif", Font.PLAIN, (int) (21 * k)));
            for (int i = 0; i < lines.size(); i++) {
                String s = (String) lines.elementAt(i);
                int col = i / PER_COLUMN, row = i % PER_COLUMN;
                if (col > 1) break;
                if (s.startsWith("--")) g.setColor(new Color(140, 190, 255));
                else g.setColor(new Color(225, 225, 235));
                g.drawString(s, (int) ((90 + col * 880) * k), (int) ((172 + row * 32) * k));
            }
        }
    }
}
