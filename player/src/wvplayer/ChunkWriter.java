/*
 * ChunkWriter - decodes WavPack into one group's chunk clips, starting at any
 * chunk, and hands each finished chunk to the host to enable.
 *
 * Starting mid-album uses the layout's saved writer state for that chunk, a
 * WavPack block seek, and a resampler started just early enough, so every file
 * is identical to the one an uninterrupted run would write. Writing stops at the
 * end of the group, at the first chunk that is already enabled, or on request.
 *
 * Java 1.3-level code.
 */
package wvplayer;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import bdclip.ClipWriter;
import bdclip.Resampler;

import com.beatofthedrum.wvdecoder.WavPackUtils;
import com.beatofthedrum.wvdecoder.WavpackContext;

final class ChunkWriter {

    interface Host {
        boolean isEnabled(int clip);
        /** Chunk file written and closed; enable it. */
        void chunkWritten(int clip) throws IOException;
        boolean stopRequested();
        /** Progress: samples decoded in this run (input rate). */
        void decoded(long samples);
        /** About to write a chunk of roughly this size: make room, or throw. */
        void needSpace(long bytes) throws IOException;
    }

    static final int RESAMPLER_TAPS = 64;
    /** Desktop tests only (set by the test harness): imitate a player decoding at this many x real time. */
    static int throttle = 0;

    private final Layout lay;
    private final String musicDir;
    private final File dir;
    private final byte[][] gop;
    private final Host host;
    private boolean stopped;
    /** What the writer was doing, for error messages. */
    volatile String stage = "starting";

    ChunkWriter(Layout lay, String musicDir, File dir, byte[][] gop, Host host) {
        this.lay = lay;
        this.musicDir = musicDir;
        this.dir = dir;
        this.gop = gop;
        this.host = host;
    }

    static final int BLOCK = 4096;

    /* timing of this run, in ms (for the speed line on screen) */
    private long tDecode, tConvert, tMux, tWrite, tWait, tEnable, tStart, outFrames;
    private int outRate;

    /** "decoding at 3.1x real time (decode 9.6x, resample 8.2x, ...)" for the run so far, or null. */
    String speedReport() {
        long wall = System.currentTimeMillis() - tStart;
        if (outRate == 0 || outFrames < outRate || wall < 200) return null;
        double sec = outFrames / (double) outRate;
        long other = wall - tDecode - tConvert - tMux - tWrite - tWait - tEnable;
        return "decoding " + x(sec, wall) + " real time  (wait " + tWait / 1000 + " s, enable "
               + tEnable / 1000 + " s, other " + Math.max(0, other) / 1000 + " s of " + wall / 1000 + " s)\n"
               + "decode " + x(sec, tDecode) + " · resample " + x(sec, tConvert) + " · stream " + x(sec, tMux)
               + " · storage " + x(sec, tWrite);
    }

    private static String x(double sec, long ms) {
        if (ms <= 0) return "fast";
        double v = sec * 1000.0 / ms;
        long t = Math.round(v * 10);
        return (t / 10) + "." + (t % 10) + "x";
    }

    /** Counts the time spent in the storage writes. */
    private final class TimedOut extends OutputStream {
        final OutputStream o;
        TimedOut(OutputStream o) { this.o = o; }
        public void write(int b) throws IOException {
            long t0 = System.currentTimeMillis();
            o.write(b);
            tWrite += System.currentTimeMillis() - t0;
        }
        public void write(byte[] b, int off, int len) throws IOException {
            long t0 = System.currentTimeMillis();
            o.write(b, off, len);
            tWrite += System.currentTimeMillis() - t0;
        }
        public void flush() throws IOException {
            long t0 = System.currentTimeMillis();
            o.flush();
            tWrite += System.currentTimeMillis() - t0;
        }
        public void close() throws IOException {
            long t0 = System.currentTimeMillis();
            o.close();
            tWrite += System.currentTimeMillis() - t0;
        }
    }

    /** Writes group g from chunk c0 (which must not be enabled). Returns the chunks written. */
    int write(int g, int c0) throws Exception {
        tDecode = tConvert = tMux = tWrite = tWait = tEnable = outFrames = 0;
        tStart = System.currentTimeMillis();
        final Layout.Group G = lay.groups[g];
        outRate = G.outRate;
        final int[] written = { 0 };
        final FileOutputStream[] fos = new FileOutputStream[1];
        stopped = false;
        ClipWriter.ChunkSink sink = new ClipWriter.ChunkSink() {
            public void chunkDone(int n, OutputStream os, byte[] info) throws IOException {
                if (fos[0] == null) return;                    // a discarded chunk
                stage = "finishing chunk " + G.clip(n);
                // no fd.sync(): forcing each chunk to the medium cost up to 20x the write
                // time on the Oppo (benchmark v2), and the player reads it through the same cache
                long t0 = System.currentTimeMillis();
                os.flush();
                os.close();
                tWrite += System.currentTimeMillis() - t0;
                fos[0] = null;
                stage = "enabling chunk " + G.clip(n);
                t0 = System.currentTimeMillis();
                host.chunkWritten(G.clip(n));
                tEnable += System.currentTimeMillis() - t0;
                stage = "writing chunk " + (G.clip(n) + 1);
                written[0]++;
            }
            public OutputStream nextChunk(int n) throws IOException {
                if (stopped || host.stopRequested() || host.isEnabled(G.clip(n))) {
                    stopped = true;
                    return new Discard();
                }
                return open(G, n, fos);
            }
        };
        ClipWriter w = new ClipWriter(open(G, c0, fos), G.outRate, 2, G.outBits, gop,
                                      Preparer.VIDEO_FORMAT, Preparer.VIDEO_RATE);
        w.setChunking(G.cuts, sink);
        w.setTotalSamples(G.outSamples);
        long outStart = 0;
        if (c0 > 0) {
            w.resume(G.states[c0 - 1]);
            outStart = w.getResumeAudioFrame() * w.samplesPerFrame();
        }
        Resampler rs = (G.inRate != G.outRate) ? new Resampler(G.inRate, G.outRate, 2, RESAMPLER_TAPS) : null;
        long inStart = outStart;
        if (rs != null) {
            inStart = Math.max(0, rs.firstInputFor(outStart) - 16);
            rs.startAt(outStart, inStart);
        }
        int outBytes = G.outBits / 8;
        // Decode BLOCK samples per call (the decoder's own default is 256, i.e. 128 stereo
        // frames: the per-call costs of decoder, resampler, stream writer and timing then
        // added up to a quarter of the Oppo's time). Mono gets BLOCK frames, stereo BLOCK/2.
        int[] samples = new int[BLOCK];
        float[] fin = new float[BLOCK * 2];
        float[] fout = new float[(int) ((long) BLOCK * G.outRate / G.inRate + 64) * 2];
        byte[] pcm = new byte[fout.length * 3];
        long done = 0;

        for (int ti = G.firstTrack; ti <= G.lastTrack && !stopped; ti++) {
            Layout.Track t = lay.tracks[ti];
            if (t.group != g) continue;
            if (inStart >= t.inStart + t.samples) continue;     // before the start point
            long offset = Math.max(0, inStart - t.inStart);     // first sample wanted in this track
            String path = musicDir + "/" + t.name;
            stage = "opening " + t.name + " at sample " + offset;
            DataInputStream in = WvSeek.open(path, offset);
            try {
                WavpackContext wpc = WavPackUtils.WavpackOpenFileInput(in);
                if (wpc.error) throw new Exception(t.name + ": " + wpc.error_message);
                int ch = WavPackUtils.WavpackGetReducedChannels(wpc);
                int bps = WavPackUtils.WavpackGetBytesPerSample(wpc);
                long pos = WavPackUtils.WavpackGetSampleIndex(wpc);
                if (pos > offset) throw new Exception(t.name + ": seek went past " + offset);
                long left = t.samples - pos;
                int perCall = BLOCK / ch;
                final String decodingStage = "decoding " + t.name;   // built once, not per block
                final String writingStage = "writing chunk (" + t.name + ")";
                while (left > 0 && !stopped) {
                    if (host.stopRequested()) {
                        stopped = true;
                        break;
                    }
                    stage = decodingStage;
                    long t0 = System.currentTimeMillis();
                    int got = (int) WavPackUtils.WavpackUnpackSamples(wpc, samples, perCall);
                    long t1 = System.currentTimeMillis();
                    tDecode += t1 - t0;
                    if (got <= 0) break;
                    if (got > left) got = (int) left;
                    left -= got;
                    int skip = 0;
                    if (pos < offset) {                          // decoded from the block start: drop the extra
                        skip = (int) Math.min(got, offset - pos);
                    }
                    pos += got;
                    int frames = got - skip;
                    if (frames <= 0) continue;
                    if (skip > 0) System.arraycopy(samples, skip * ch, samples, 0, frames * ch);
                    int nb;
                    if (rs == null) {
                        nb = Preparer.toPcmDirect(samples, frames, ch, bps, outBytes, pcm);
                    } else {
                        Preparer.toFloat24(samples, frames, ch, bps, fin);
                        int of = rs.process(fin, frames, fout);
                        nb = Preparer.floatToPcm24(fout, of, pcm);
                    }
                    long t2 = System.currentTimeMillis();
                    tConvert += t2 - t1;
                    stage = writingStage;
                    long w0 = tWrite + tWait + tEnable;
                    w.writePcm(pcm, 0, nb);
                    tMux += System.currentTimeMillis() - t2 - (tWrite + tWait + tEnable - w0);
                    outFrames += nb / (2 * outBytes);
                    done += frames;
                    host.decoded(done);
                    if (throttle > 0) {                         // desktop tests: imitate a slow player
                        try { Thread.sleep((long) (frames * 1000L / G.inRate / throttle)); } catch (InterruptedException e) { }
                    }
                }
                if (pos < t.samples && !stopped) {
                    // short file: pad with silence so the timeline stays as laid out
                    long pad = t.samples - pos;
                    java.util.Arrays.fill(samples, 0);
                    while (pad > 0 && !stopped) {
                        int k = (int) Math.min(perCall, pad);
                        int nb;
                        if (rs == null) {
                            nb = Preparer.toPcmDirect(samples, k, ch, bps, outBytes, pcm);
                        } else {
                            Preparer.toFloat24(samples, k, ch, bps, fin);
                            nb = Preparer.floatToPcm24(fout, rs.process(fin, k, fout), pcm);
                        }
                        w.writePcm(pcm, 0, nb);
                        pad -= k;
                    }
                }
            } finally {
                in.close();
            }
        }
        if (!stopped) {
            if (rs != null) {
                int of = rs.flush(fout);
                w.writePcm(pcm, 0, Preparer.floatToPcm24(fout, of, pcm));
            }
            w.finish();
        } else if (fos[0] != null) {
            try { fos[0].close(); } catch (Throwable t) { }     // partial chunk: stays disabled, rewritten later
            fos[0] = null;
        }
        return written[0];
    }

    /** Rough size of chunk c: LPCM + black video + transport overhead. */
    static long chunkBytes(Layout.Group G, int c) {
        double sec = (G.out45[c] - G.in45[c]) / 45000.0;
        return (long) (sec * (G.outRate * 2L * G.outBits / 8 + 70000) * 1.06) + (1 << 20);
    }

    private OutputStream open(Layout.Group G, int c, FileOutputStream[] fos) throws IOException {
        int clip = G.clip(c);
        stage = "making room for chunk " + clip;
        long t0 = System.currentTimeMillis();
        host.needSpace(chunkBytes(G, c));
        tWait += System.currentTimeMillis() - t0;
        stage = "creating chunk file " + clip;
        t0 = System.currentTimeMillis();
        fos[0] = new FileOutputStream(new File(dir, Layout.five(clip) + ".m2t"));
        tWrite += System.currentTimeMillis() - t0;
        stage = "writing chunk " + clip;
        return new BufferedOutputStream(new TimedOut(fos[0]), 256 * 1024);
    }

    static final class Discard extends OutputStream {
        public void write(int b) { }
        public void write(byte[] b, int o, int l) { }
    }
}
