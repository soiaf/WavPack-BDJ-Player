/*
 * ClipWriter - writes a Blu-ray (BDMV) clip: an .m2ts transport stream with
 * LPCM audio (and optionally a repeated black MPEG-2 video GOP), plus the
 * matching clip info (.clpi) and playlist (.mpls).
 *
 * Java 1.3-level code: runs on BD-J players and on desktop JVMs.
 *
 * Layouts mirror a BD-J disc known to play on PS3/PS5 hardware and are checked
 * against libbluray's parsers:
 *   PAT (program 0 -> SIT 0x1F, program 1 -> PMT 0x100), SIT, PMT with HDMV
 *   registration + copy-control descriptors, PCR on its own PID 0x1001,
 *   PSI/PCR every ~100 ms, 192-byte source packets with 27 MHz arrival
 *   timestamps, file padded to whole aligned units (32 packets).
 *   LPCM: PID 0x1100, stream id 0xBD, 5 ms (1/200 s) frames, 4-byte BD LPCM header.
 *   Video: PID 0x1011, MPEG-2, DTS = PTS - 1 frame (no B-frames).
 */
package bdclip;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class ClipWriter {

    static final int PID_PAT = 0x0000, PID_SIT = 0x001F, PID_PMT = 0x0100;
    static final int PID_PCR = 0x1001, PID_VIDEO = 0x1011, PID_AUDIO = 0x1100;
    static final long CLOCK = 27000000L;           // 27 MHz system / arrival clock
    static final long PTS0 = 90000L;               // first presentation time: 1 s (90 kHz)

    // ---- configuration
    private OutputStream out;
    private final int sampleRate, channels, bytesPerSample;
    private final byte[][] gop;                     // video access units of one GOP, or null
    private final int videoFormat, videoRate;       // BD codes, e.g. 6 = 1080p, 2 = 24 Hz
    private final long frameTicks90;                // video frame duration in 90 kHz
    private final long rateBytes;                   // TS recording rate, bytes/s
    private final long pktTicks;                    // 27 MHz ticks per source packet at that rate

    // ---- state
    private long now;                               // current arrival time, 27 MHz
    private long packets = 0;                       // all packets written
    private long filePackets = 0;                   // packets in the current file

    // ---- chunked mode: one continuous stream cut into several clip files
    private int chunkFrames = 0;                    // video frames per chunk (fixed-length mode)
    private long[] cuts;                            // or: video frame indices where new files start
    private int nextCut = 0;
    private long noCutAt = -1;                      // frame where a resumed writer already is cut
    private long[][] cutStates = new long[16][];
    private ChunkSink sink;
    private int chunk = 0;
    private long fileStart90 = PTS0;
    private long[] chunkIn90 = new long[64];
    private int chunksDone = 0;
    private long endPts90 = -1;                     // known end of the audio, if set
    private final int[] cc = new int[0x2000];
    private long lastPcr = -CLOCK, lastPsi = -CLOCK, lastSit = -CLOCK;
    private final byte[] frameBuf;                  // one 5 ms LPCM frame
    private int frameFill = 0;
    private long audioFrames = 0;
    private long videoFrames = 0;
    private int late = 0;
    private long[] epPts = new long[256];
    private long[] epSpn = new long[256];
    private int epCount = 0;
    private final byte[] pkt = new byte[192];

    /**
     * @param out          the .m2ts output (use a BufferedOutputStream)
     * @param sampleRate   48000, 96000 or 192000
     * @param channels     1 or 2 (mono is written as stereo by the caller's choice; 2 recommended)
     * @param bits         16 or 24
     * @param gop          access units of one closed GOP (first is the I-frame with sequence header), or null for audio only
     * @param videoFormat  BD video_format code (6 = 1080p, 4 = 1080i, 5 = 720p)
     * @param videoRate    BD frame_rate code (1 = 23.976, 2 = 24, 3 = 25, 4 = 29.97)
     */
    public ClipWriter(OutputStream out, int sampleRate, int channels, int bits,
                      byte[][] gop, int videoFormat, int videoRate) throws IOException {
        this.out = out;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bytesPerSample = bits / 8;
        this.gop = gop;
        this.videoFormat = videoFormat;
        this.videoRate = videoRate;
        // 90 kHz ticks per frame: 1 = 23.976, 2 = 24, 3 = 25, 4 = 29.97
        this.frameTicks90 = (videoRate == 1) ? 3754 : (videoRate == 2) ? 3750 : (videoRate == 3) ? 3600 : 3003;
        this.rateBytes = (gop == null) ? 1000000L : 2500000L;
        this.pktTicks = 192L * CLOCK / rateBytes;
        this.frameBuf = new byte[(sampleRate / 200) * channels * bytesPerSample];
        this.now = (PTS0 - 27000) * 300;            // start sending 0.3 s before the first PTS
    }

    /**
     * Receives the files of a chunked clip. The stream passed to the constructor
     * is chunk 0; each later chunk's stream comes from nextChunk().
     */
    public interface ChunkSink {
        /** Chunk n is complete: its stream has been flushed (the sink closes it). */
        void chunkDone(int n, OutputStream os, byte[] clipInfo) throws IOException;
        /** Output stream for chunk n (n >= 1). */
        OutputStream nextChunk(int n) throws IOException;
    }

    /**
     * Cut the stream into separate clip files every framesPerChunk video frames
     * (a multiple of the GOP length). The files form one continuous transport
     * stream (continuous arrival and system clocks), to be played as play items
     * with connection_condition 6. Call before writing any PCM. Needs video.
     */
    public void setChunking(int framesPerChunk, ChunkSink s) {
        if (gop == null || framesPerChunk <= 0 || framesPerChunk % gop.length != 0) {
            throw new IllegalArgumentException("chunk length must be a multiple of the GOP");
        }
        chunkFrames = framesPerChunk;
        sink = s;
    }

    /**
     * Total audio length, if known in advance: video is then not sent beyond the
     * end of the audio (needed in chunked mode, so no empty chunk is started).
     */
    public void setTotalSamples(long samples) {
        long spf = sampleRate / 200;
        endPts90 = audioPts((samples + spf - 1) / spf);
    }

    /**
     * Cut the stream into separate files at the given video frame indices
     * (ascending, each a multiple of the GOP length, all before the end of the
     * audio). Call before writing any PCM, or before resume().
     */
    public void setChunking(long[] cutFrames, ChunkSink s) {
        if (gop == null) throw new IllegalArgumentException("chunking needs video");
        for (int i = 0; i < cutFrames.length; i++) {
            if (cutFrames[i] <= 0 || cutFrames[i] % gop.length != 0 || (i > 0 && cutFrames[i] <= cutFrames[i - 1])) {
                throw new IllegalArgumentException("bad cut " + cutFrames[i]);
            }
        }
        cuts = cutFrames;
        sink = s;
    }

    private boolean isCutFrame(long v) {
        if (v == noCutAt) return false;
        if (chunkFrames > 0) return v > 0 && v % chunkFrames == 0;
        if (cuts != null && nextCut < cuts.length && cuts[nextCut] == v) {
            nextCut++;
            return true;
        }
        return false;
    }

    /**
     * Writer state at the start of chunk n (n >= 1), recorded at each cut:
     * enough to resume() writing from chunk n without writing chunks 0..n-1.
     */
    public long[] getCutState(int n) {
        return (n >= 1 && n - 1 < cutStates.length) ? cutStates[n - 1] : null;
    }

    private long[] snapshot() {
        return new long[] { now, packets, audioFrames, videoFrames, chunk, fileStart90, late,
                            cc[PID_PAT], cc[PID_SIT], cc[PID_PMT], cc[PID_PCR], cc[PID_VIDEO], cc[PID_AUDIO],
                            cc[0x1FFF] };
    }

    /**
     * Continue a chunked stream at the start of the chunk whose state is given
     * (from getCutState of a dry run with the same settings and cuts). The output
     * stream given to the constructor becomes that chunk's file. Feed PCM from
     * audio frame getResumeAudioFrame() on; the files written are identical to
     * those of an uninterrupted run.
     */
    public void resume(long[] st) {
        if (sink == null) throw new IllegalStateException("setChunking first");
        now = st[0];
        packets = st[1];
        audioFrames = st[2];
        videoFrames = st[3];
        chunk = (int) st[4];
        fileStart90 = st[5];
        late = (int) st[6];
        cc[PID_PAT] = (int) st[7];
        cc[PID_SIT] = (int) st[8];
        cc[PID_PMT] = (int) st[9];
        cc[PID_PCR] = (int) st[10];
        cc[PID_VIDEO] = (int) st[11];
        cc[PID_AUDIO] = (int) st[12];
        cc[0x1FFF] = (int) st[13];
        lastPsi = lastPcr = lastSit = now - CLOCK;
        filePackets = 0;
        epCount = 0;
        frameFill = 0;
        noCutAt = videoFrames;
        nextCut = 0;
        while (cuts != null && nextCut < cuts.length && cuts[nextCut] <= videoFrames) nextCut++;
    }

    /** Audio frame (5 ms) a resumed writer expects next; times samplesPerFrame gives the sample index. */
    public long getResumeAudioFrame() { return audioFrames; }

    /** Samples per channel in one 5 ms LPCM frame. */
    public int samplesPerFrame() { return sampleRate / 200; }

    /** Number of 5 ms audio frames in one chunk (chunk length must be a whole number of them). */
    public long audioFramesPerChunk() {
        long t = chunkFrames * frameTicks90;
        return (t % 450 == 0) ? t / 450 : -1;
    }

    // ================================================================ public API

    /** Add big-endian interleaved PCM samples. */
    public void writePcm(byte[] pcm, int off, int len) throws IOException {
        while (len > 0) {
            int n = Math.min(len, frameBuf.length - frameFill);
            System.arraycopy(pcm, off, frameBuf, frameFill, n);
            frameFill += n;
            off += n;
            len -= n;
            if (frameFill == frameBuf.length) {
                emitAudioFrame();
                frameFill = 0;
            }
        }
    }

    /** Pads the last audio frame with silence, writes trailing video and aligned-unit padding. */
    public void finish() throws IOException {
        if (frameFill > 0) {
            for (int i = frameFill; i < frameBuf.length; i++) frameBuf[i] = 0;
            emitAudioFrame();
            frameFill = 0;
        }
        if (gop != null) {
            while (videoPts(videoFrames) < audioEndPts()) {
                emitVideoFrame();
            }
        }
        while (filePackets % 32 != 0) {
            writeNull();
        }
        out.flush();
        if (sink != null) {
            recordChunk(fileStart90);
            sink.chunkDone(chunk, out, clipInfo());
        }
    }

    /** Ends the current file at an aligned-unit boundary and starts the next one (chunked mode). */
    private void cut() throws IOException {
        while (filePackets % 32 != 0) {
            writeNull();
        }
        out.flush();
        long end90 = videoPts(videoFrames);
        recordChunk(fileStart90);
        sink.chunkDone(chunk, out, clipInfo(fileStart90 / 2, end90 / 2));
        chunk++;
        out = sink.nextChunk(chunk);
        filePackets = 0;
        epCount = 0;
        fileStart90 = end90;
        lastPsi = lastPcr = lastSit = now - CLOCK;   // each file starts with PAT, PMT, SIT, PCR
        if (chunk - 1 >= cutStates.length) {
            long[][] a = new long[Math.max(cutStates.length * 2, chunk + 16)][];
            System.arraycopy(cutStates, 0, a, 0, cutStates.length);
            cutStates = a;
        }
        cutStates[chunk - 1] = snapshot();
    }

    private void recordChunk(long in90) {
        if (chunksDone == chunkIn90.length) {
            long[] a = new long[chunksDone * 2];
            System.arraycopy(chunkIn90, 0, a, 0, chunksDone);
            chunkIn90 = a;
        }
        chunkIn90[chunksDone++] = in90;
    }

    /**
     * Play items of a finished chunked clip, one per file, with the given clip names.
     * Each item ends where the next starts; the last ends with the audio.
     */
    public PlayItem[] chunkItems(String[] names) {
        PlayItem[] r = new PlayItem[chunksDone];
        for (int i = 0; i < chunksDone; i++) {
            long out90 = (i + 1 < chunksDone) ? chunkIn90[i + 1] : audioEndPts();
            r[i] = new PlayItem(names[i], chunkIn90[i] / 2, out90 / 2, true, videoFormat, videoRate,
                                audioFormat(), audioRateCode());
        }
        return r;
    }

    public int getChunkCount() { return chunksDone; }

    public long getPacketCount() { return packets; }
    public int getLateCount() { return late; }

    /** Presentation start/end in 45 kHz units (as used in clpi/mpls). */
    public long getStart45() { return PTS0 / 2; }
    public long getEnd45() {
        long end = audioEndPts();
        if (gop != null) end = Math.max(end, videoPts(videoFrames));
        return end / 2;
    }

    // ================================================================ scheduling

    private long audioPts(long n) { return PTS0 + n * 90000L / 200; }
    private long audioEndPts() { return audioPts(audioFrames); }
    private long videoPts(long n) { return PTS0 + n * frameTicks90; }

    private void emitAudioFrame() throws IOException {
        long pts = audioPts(audioFrames);
        // interleave: send every video frame whose send time comes first
        if (gop != null) {
            while ((endPts90 < 0 || videoPts(videoFrames) < endPts90)
                    && (videoPts(videoFrames) - frameTicks90 - 22500) <= (pts - 4500)) {
                emitVideoFrame();
            }
        }
        boolean ep = (gop == null) && (audioFrames % 200 == 0);   // audio-only: entry point every 1 s
        byte[] pes = lpcmPes(pts);
        sendPes(PID_AUDIO, pes, pts - 4500, pts, ep, pts, false); // send ~50 ms ahead
        audioFrames++;
    }

    private void emitVideoFrame() throws IOException {
        int idx = (int) (videoFrames % gop.length);
        long pts = videoPts(videoFrames);
        long dts = pts - frameTicks90;
        byte[] pes = videoPes(gop[idx], pts, dts);
        boolean cutHere = sink != null && isCutFrame(videoFrames);
        sendPes(PID_VIDEO, pes, dts - 22500, dts, idx == 0, pts, cutHere);  // send ~250 ms ahead
        videoFrames++;
    }

    private void sendPes(int pid, byte[] pes, long sendAt90, long deadline90, boolean entryPoint, long epPtsValue,
                         boolean cutBefore) throws IOException {
        long t = sendAt90 * 300;
        // keep PCR/PSI going through any gap before this PES (arrival timestamps may jump)
        while (now < t) {
            long due = Math.min(lastPcr + CLOCK / 25, lastPsi + CLOCK / 10);
            if (due >= t) {
                now = t;
                break;
            }
            if (due > now) now = due;
            service();
        }
        if (cutBefore) cut();
        service();
        if (entryPoint) addEp(epPtsValue, filePackets);
        int pos = 0;
        boolean first = true;
        while (pos < pes.length) {
            int n = Math.min(184, pes.length - pos);
            writeTs(pid, first, pes, pos, n);
            pos += n;
            first = false;
            service();
        }
        if (now > deadline90 * 300) late++;
    }

    /** Emit PAT/PMT/SIT and PCR when due. */
    private void service() throws IOException {
        if (now - lastPsi >= CLOCK / 10) {
            lastPsi = now;
            writeSection(PID_PAT, pat());
            writeSection(PID_PMT, pmt());
            if (now - lastSit >= CLOCK) {
                lastSit = now;
                writeSection(PID_SIT, sit());
            }
        }
        if (now - lastPcr >= CLOCK / 25) {
            lastPcr = now;
            writePcr();
        }
    }

    // ================================================================ PES

    private byte[] lpcmPes(long pts) {
        int payload = frameBuf.length;
        // BD LPCM header: payload size, channel assignment, sampling freq, bits per sample
        int chan = (channels == 1) ? 1 : 3;
        int freq = (sampleRate == 96000) ? 4 : (sampleRate == 192000) ? 5 : 1;
        int bits = (bytesPerSample == 3) ? 3 : 1;
        byte[] p = new byte[14 + 4 + payload];
        pesHeader(p, 0xBD, p.length - 6, pts, -1);
        p[14] = (byte) (payload >> 8);
        p[15] = (byte) payload;
        p[16] = (byte) ((chan << 4) | freq);
        p[17] = (byte) (bits << 6);
        System.arraycopy(frameBuf, 0, p, 18, payload);
        return p;
    }

    private byte[] videoPes(byte[] au, long pts, long dts) {
        byte[] p = new byte[19 + au.length];
        int len = p.length - 6;
        pesHeader(p, 0xE0, len > 65535 ? 0 : len, pts, dts);
        System.arraycopy(au, 0, p, 19, au.length);
        return p;
    }

    /** PES header with PTS (and DTS if dts >= 0). Header is 14 bytes (PTS) or 19 bytes (PTS+DTS). */
    private static void pesHeader(byte[] p, int streamId, int pesLen, long pts, long dts) {
        p[0] = 0; p[1] = 0; p[2] = 1; p[3] = (byte) streamId;
        p[4] = (byte) (pesLen >> 8); p[5] = (byte) pesLen;
        p[6] = (byte) 0x81;                                   // '10', original
        if (dts >= 0) {
            p[7] = (byte) 0xC0; p[8] = 10;
            timestamp(p, 9, 3, pts);
            timestamp(p, 14, 1, dts);
        } else {
            p[7] = (byte) 0x80; p[8] = 5;
            timestamp(p, 9, 2, pts);
        }
    }

    private static void timestamp(byte[] p, int o, int prefix, long t) {
        p[o] = (byte) ((prefix << 4) | (int) (((t >> 30) & 7) << 1) | 1);
        p[o + 1] = (byte) (t >> 22);
        p[o + 2] = (byte) ((((t >> 15) & 0x7F) << 1) | 1);
        p[o + 3] = (byte) (t >> 7);
        p[o + 4] = (byte) (((t & 0x7F) << 1) | 1);
    }

    // ================================================================ TS packets

    private void startPacket() {
        long ats = now & 0x3FFFFFFFL;
        pkt[0] = (byte) (ats >> 24);
        pkt[1] = (byte) (ats >> 16);
        pkt[2] = (byte) (ats >> 8);
        pkt[3] = (byte) ats;
        pkt[4] = 0x47;
    }

    private void finishPacket() throws IOException {
        out.write(pkt, 0, 192);
        packets++;
        filePackets++;
        now += pktTicks;
    }

    private void writeTs(int pid, boolean start, byte[] data, int off, int n) throws IOException {
        startPacket();
        pkt[5] = (byte) ((start ? 0x40 : 0) | (pid >> 8));
        pkt[6] = (byte) pid;
        int c = cc[pid];
        cc[pid] = (c + 1) & 15;
        if (n == 184) {
            pkt[7] = (byte) (0x10 | c);
            System.arraycopy(data, off, pkt, 8, 184);
        } else {
            pkt[7] = (byte) (0x30 | c);                      // adaptation field + payload
            int afLen = 183 - n;
            pkt[8] = (byte) afLen;
            if (afLen > 0) {
                pkt[9] = 0;                                   // no flags
                for (int i = 10; i < 9 + afLen; i++) pkt[i] = (byte) 0xFF;
            }
            System.arraycopy(data, off, pkt, 9 + afLen, n);
        }
        finishPacket();
    }

    private void writePcr() throws IOException {
        startPacket();
        pkt[5] = (byte) (PID_PCR >> 8);
        pkt[6] = (byte) PID_PCR;
        pkt[7] = (byte) (0x20 | cc[PID_PCR]);                // adaptation only, CC not incremented
        pkt[8] = (byte) 183;
        pkt[9] = 0x10;                                        // PCR flag
        long base = now / 300, ext = now % 300;
        pkt[10] = (byte) (base >> 25);
        pkt[11] = (byte) (base >> 17);
        pkt[12] = (byte) (base >> 9);
        pkt[13] = (byte) (base >> 1);
        pkt[14] = (byte) (((base & 1) << 7) | 0x7E | ((ext >> 8) & 1));
        pkt[15] = (byte) ext;
        for (int i = 16; i < 192; i++) pkt[i] = (byte) 0xFF;
        finishPacket();
    }

    private void writeNull() throws IOException {
        startPacket();
        pkt[5] = 0x1F; pkt[6] = (byte) 0xFF; pkt[7] = 0x10;
        for (int i = 8; i < 192; i++) pkt[i] = (byte) 0xFF;
        finishPacket();
    }

    private void writeSection(int pid, byte[] section) throws IOException {
        byte[] d = new byte[184];
        d[0] = 0;                                             // pointer field
        System.arraycopy(section, 0, d, 1, section.length);
        for (int i = 1 + section.length; i < 184; i++) d[i] = (byte) 0xFF;
        writeTs(pid, true, d, 0, 184);
    }

    // ================================================================ PSI

    private static byte[] section(int tableId, int idExt, byte[] body) {
        int len = 5 + body.length + 4;                        // after section_length field
        byte[] s = new byte[3 + len];
        s[0] = (byte) tableId;
        s[1] = (byte) (0xB0 | (len >> 8));
        s[2] = (byte) len;
        s[3] = (byte) (idExt >> 8);
        s[4] = (byte) idExt;
        s[5] = (byte) 0xC1;                                   // version 0, current
        s[6] = 0;
        s[7] = 0;
        System.arraycopy(body, 0, s, 8, body.length);
        int crc = Crc32.mpeg(s, 0, s.length - 4);
        s[s.length - 4] = (byte) (crc >> 24);
        s[s.length - 3] = (byte) (crc >> 16);
        s[s.length - 2] = (byte) (crc >> 8);
        s[s.length - 1] = (byte) crc;
        return s;
    }

    private static byte[] pat() {
        return section(0x00, 0, new byte[] {
            0, 0, (byte) 0xE0, (byte) PID_SIT,                // program 0 -> network (SIT) PID
            0, 1, (byte) (0xE0 | (PID_PMT >> 8)), (byte) PID_PMT });
    }

    private byte[] pmt() {
        Bytes b = new Bytes();
        b.u16(0xE000 | PID_PCR);
        byte[] progInfo = { 0x05, 0x04, 'H', 'D', 'M', 'V', (byte) 0x88, 0x04, 0x0F, (byte) 0xFF, (byte) 0xFC, (byte) 0xFC };
        b.u16(0xF000 | progInfo.length);
        b.raw(progInfo);
        if (gop != null) {
            b.u8(0x02);
            b.u16(0xE000 | PID_VIDEO);
            b.u16(0xF000 | 10);
            b.raw(new byte[] { 0x05, 0x08, 'H', 'D', 'M', 'V', (byte) 0xFF, 0x02,
                               (byte) ((videoFormat << 4) | videoRate), 0x3F });
        }
        b.u8(0x80);
        b.u16(0xE000 | PID_AUDIO);
        b.u16(0xF000 | 10);
        b.raw(new byte[] { 0x05, 0x08, 'H', 'D', 'M', 'V', (byte) 0xFF, (byte) 0x80,
                           (byte) ((audioFormat() << 4) | audioRateCode()), 0x3F });
        return section(0x02, 1, b.toByteArray());
    }

    private static byte[] sit() {
        // Selection information table as in the reference disc (partial TS descriptor).
        Bytes b = new Bytes();
        b.u16(0xF000 | 10);
        b.raw(new byte[] { 0x63, 0x08, (byte) 0xC1, 0x5A, (byte) 0xAE, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF });
        b.u16(1);                                             // service id
        b.u16(0x8000);                                        // running status / loop length 0
        return section(0x7F, 0xFFFF, b.toByteArray());
    }

    int audioFormat() { return channels == 1 ? 1 : 3; }
    int audioRateCode() { return sampleRate == 96000 ? 4 : sampleRate == 192000 ? 5 : 1; }

    // ================================================================ EP map

    private void addEp(long pts90, long spn) {
        if (epCount == epPts.length) {
            long[] a = new long[epCount * 2];
            long[] b = new long[epCount * 2];
            System.arraycopy(epPts, 0, a, 0, epCount);
            System.arraycopy(epSpn, 0, b, 0, epCount);
            epPts = a;
            epSpn = b;
        }
        epPts[epCount] = pts90;
        epSpn[epCount] = spn;
        epCount++;
    }

    // ================================================================ .clpi

    public byte[] clipInfo() {
        if (sink != null) return clipInfo(fileStart90 / 2, getEnd45());
        return clipInfo(getStart45(), getEnd45());
    }

    private byte[] clipInfo(long start45, long end45) {
        Bytes b = new Bytes();
        b.ascii("HDMV0200");
        b.u32(0); b.u32(0); b.u32(0); b.u32(0); b.u32(0);    // patched below
        b.zero(12);

        // ClipInfo (176 bytes)
        b.u32(176);
        b.u16(0);
        b.u8(1);                                              // clip_stream_type: AV stream
        b.u8(1);                                              // application_type: main TS for movie
        b.u32(0);                                             // is_ATC_delta = 0
        b.u32(rateBytes);
        b.u32(filePackets);
        b.zero(128);
        b.u16(0x1E);                                          // TS_type_info_block
        b.u8(0x80);
        b.ascii("HDMV");
        b.zero(25);

        // SequenceInfo
        int seqStart = b.size();
        b.u32(22);
        b.u8(0);
        b.u8(1);                                              // one ATC sequence
        b.u32(0);                                             // SPN_ATC_start
        b.u8(1);                                              // one STC sequence
        b.u8(0);                                              // offset_STC_id
        b.u16(PID_PCR);
        b.u32(0);                                             // SPN_STC_start
        b.u32(start45);
        b.u32(end45);

        // ProgramInfo
        int progStart = b.size();
        Bytes p = new Bytes();
        p.u8(0);
        p.u8(1);                                              // one program sequence
        p.u32(0);
        p.u16(PID_PMT);
        p.u8(gop != null ? 2 : 1);
        p.u8(0);
        if (gop != null) {
            p.u16(PID_VIDEO);
            p.u8(21);
            p.u8(0x02);
            p.u8((videoFormat << 4) | videoRate);
            p.u8(0x30);                                       // 16:9, oc_flag 0
            p.zero(18);
        }
        p.u16(PID_AUDIO);
        p.u8(21);
        p.u8(0x80);
        p.u8((audioFormat() << 4) | audioRateCode());
        p.ascii("und");
        p.zero(16);
        b.u32(p.size());
        b.raw(p.toByteArray());

        // CPI / EP_map
        int cpiStart = b.size();
        Bytes ep = epMap();
        b.u32(2 + ep.size());
        b.u16(1);                                             // CPI_type 1 = EP_map
        b.raw(ep.toByteArray());

        // ClipMark (empty) + ExtensionData (empty)
        int markStart = b.size();
        b.u32(0);
        int extStart = b.size();
        b.u32(0);

        byte[] r = b.toByteArray();
        put32(r, 8, seqStart);
        put32(r, 12, progStart);
        put32(r, 16, cpiStart);
        put32(r, 20, markStart);
        put32(r, 24, extStart);
        return r;
    }

    private Bytes epMap() {
        // coarse entry whenever PTS bits 19+ or SPN bits 17+ change
        Bytes coarse = new Bytes();
        Bytes fine = new Bytes();
        int nCoarse = 0;
        long prevC = -1, prevS = -1;
        for (int i = 0; i < epCount; i++) {
            long pts = epPts[i], spn = epSpn[i];
            long cPts = (pts >> 19) & 0x3FFF;
            if (i == 0 || cPts != prevC || (spn >> 17) != prevS) {
                coarse.u32(((long) i << 14) | cPts);
                coarse.u32(spn);
                nCoarse++;
                prevC = cPts;
                prevS = spn >> 17;
            }
            long fPts = (pts >> 9) & 0x7FF;
            fine.u32((1L << 28) | (fPts << 17) | (spn & 0x1FFFF));  // i_end_position_offset = 1
        }
        Bytes m = new Bytes();
        m.u8(0);
        m.u8(1);                                              // one stream PID
        m.u16(gop != null ? PID_VIDEO : PID_AUDIO);
        int type = (gop != null) ? 1 : 3;                     // EP_stream_type: 1 video, 3 audio
        // reserved 10 | EP_stream_type 4 | num_EP_coarse 16 | num_EP_fine 18
        long v = ((long) type << 34) | ((long) nCoarse << 18) | epCount;
        m.u16((int) (v >> 32));
        m.u32(v & 0xFFFFFFFFL);
        m.u32(2 + 12);                                        // map start, relative to EP_map start
        m.u32(4 + coarse.size());                             // EP_fine_table_start_address
        m.raw(coarse.toByteArray());
        m.raw(fine.toByteArray());
        return m;
    }

    // ================================================================ .mpls

    /**
     * @param clipName  5-digit clip name, e.g. "00003"
     * @param marks45   chapter mark times in 45 kHz (absolute, like getStart45()), or null for one mark at the start
     */
    public byte[] playlist(String clipName, long[] marks45) {
        Bytes b = new Bytes();
        b.ascii("MPLS0200");
        b.u32(0); b.u32(0); b.u32(0);                         // patched below
        b.zero(20);
        // AppInfoPlayList
        b.u32(14);
        b.u8(0);
        b.u8(1);                                              // sequential playback
        b.u16(0);
        b.zero(8);                                            // UO mask
        b.u16(0x4000);                                        // audio_mix_flag, as in reference
        // PlayList
        int listStart = b.size();
        Bytes pi = new Bytes();
        pi.ascii(clipName);
        pi.ascii("M2TS");
        pi.u16(1);                                            // connection_condition 1
        pi.u8(0);                                             // STC id
        pi.u32(getStart45());
        pi.u32(getEnd45());
        pi.zero(8);                                           // UO mask
        pi.u8(0);                                             // random access flag
        pi.u8(0);                                             // still mode
        pi.u16(0);
        Bytes stn = new Bytes();
        stn.u16(0);
        stn.u8(gop != null ? 1 : 0);                          // video
        stn.u8(1);                                            // audio
        stn.zero(5);                                          // pg, ig, 2nd audio, 2nd video, pip pg
        stn.zero(5);
        if (gop != null) {
            stn.u8(9); stn.u8(1); stn.u16(PID_VIDEO); stn.zero(6);
            stn.u8(5); stn.u8(0x02); stn.u8((videoFormat << 4) | videoRate); stn.zero(3);
        }
        stn.u8(9); stn.u8(1); stn.u16(PID_AUDIO); stn.zero(6);
        stn.u8(5); stn.u8(0x80); stn.u8((audioFormat() << 4) | audioRateCode()); stn.ascii("und");
        pi.u16(stn.size());
        pi.raw(stn.toByteArray());

        Bytes list = new Bytes();
        list.u16(0);
        list.u16(1);                                          // one play item
        list.u16(0);                                          // no sub paths
        list.u16(pi.size());
        list.raw(pi.toByteArray());
        b.u32(list.size());
        b.raw(list.toByteArray());

        // PlayListMark
        int markStart = b.size();
        if (marks45 == null) marks45 = new long[] { getStart45() };
        b.u32(2 + 14 * marks45.length);
        b.u16(marks45.length);
        for (int i = 0; i < marks45.length; i++) {
            b.u8(0);
            b.u8(1);                                          // entry mark (chapter)
            b.u16(0);
            b.u32(marks45[i]);
            b.u16(0xFFFF);
            b.u32(0);
        }
        byte[] r = b.toByteArray();
        put32(r, 8, listStart);
        put32(r, 12, markStart);
        put32(r, 16, 0);
        return r;
    }

    // ================================================================ multi-item playlists

    /** What a playlist needs to know about one finished clip. */
    public static final class PlayItem {
        public final String clip;
        public final long in45, out45;
        final boolean video;
        final int vfmt, vrate, afmt, arate;

        PlayItem(String clip, long in45, long out45, boolean video, int vfmt, int vrate, int afmt, int arate) {
            this.clip = clip; this.in45 = in45; this.out45 = out45;
            this.video = video; this.vfmt = vfmt; this.vrate = vrate; this.afmt = afmt; this.arate = arate;
        }

        /** Serialised as one text line, so it can be saved in local storage and reloaded. */
        public String toLine() {
            return clip + " " + in45 + " " + out45 + " " + (video ? 1 : 0) + " " + vfmt + " " + vrate + " " + afmt + " " + arate;
        }

        public static PlayItem fromLine(String l) {
            String[] f = split(l);
            return new PlayItem(f[0], Long.parseLong(f[1]), Long.parseLong(f[2]), f[3].equals("1"),
                                Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6]), Integer.parseInt(f[7]));
        }

        private static String[] split(String l) {
            java.util.Vector v = new java.util.Vector();
            java.util.StringTokenizer t = new java.util.StringTokenizer(l, " ");
            while (t.hasMoreTokens()) v.addElement(t.nextToken());
            String[] r = new String[v.size()];
            v.copyInto(r);
            return r;
        }
    }

    /**
     * Describes this (finished) clip as a play item. The item ends where the audio
     * ends (the video track runs a fraction of a second longer), so consecutive
     * items follow each other without an added silence.
     */
    public PlayItem toPlayItem(String clipName) {
        return new PlayItem(clipName, getStart45(), audioEndPts() / 2, gop != null, videoFormat, videoRate,
                            audioFormat(), audioRateCode());
    }

    /**
     * A playlist that plays several clips in order, with chapter (entry) marks.
     *
     * @param items     play items, in order
     * @param markItem  for each mark, the index of its play item
     * @param mark45    for each mark, the time in that clip's 45 kHz timeline
     */
    public static byte[] playlist(PlayItem[] items, int[] markItem, long[] mark45) {
        return playlist(items, null, markItem, mark45);
    }

    /**
     * As above, with a connection_condition per play item (null = all 1).
     * Use 6 for the items after the first of a chunked clip (seamless).
     */
    public static byte[] playlist(PlayItem[] items, int[] connection, int[] markItem, long[] mark45) {
        Bytes b = new Bytes();
        b.ascii("MPLS0200");
        b.u32(0); b.u32(0); b.u32(0);
        b.zero(20);
        b.u32(14);
        b.u8(0);
        b.u8(1);
        b.u16(0);
        b.zero(8);
        b.u16(0x4000);
        int listStart = b.size();
        Bytes list = new Bytes();
        list.u16(0);
        list.u16(items.length);
        list.u16(0);
        for (int i = 0; i < items.length; i++) {
            PlayItem it = items[i];
            Bytes pi = new Bytes();
            pi.ascii(it.clip);
            pi.ascii("M2TS");
            pi.u16(connection != null ? connection[i] : 1);
            pi.u8(0);
            pi.u32(it.in45);
            pi.u32(it.out45);
            pi.zero(8);
            pi.u8(0);
            pi.u8(0);
            pi.u16(0);
            Bytes stn = new Bytes();
            stn.u16(0);
            stn.u8(it.video ? 1 : 0);
            stn.u8(1);
            stn.zero(5);
            stn.zero(5);
            if (it.video) {
                stn.u8(9); stn.u8(1); stn.u16(PID_VIDEO); stn.zero(6);
                stn.u8(5); stn.u8(0x02); stn.u8((it.vfmt << 4) | it.vrate); stn.zero(3);
            }
            stn.u8(9); stn.u8(1); stn.u16(PID_AUDIO); stn.zero(6);
            stn.u8(5); stn.u8(0x80); stn.u8((it.afmt << 4) | it.arate); stn.ascii("und");
            pi.u16(stn.size());
            pi.raw(stn.toByteArray());
            list.u16(pi.size());
            list.raw(pi.toByteArray());
        }
        b.u32(list.size());
        b.raw(list.toByteArray());
        int markStart = b.size();
        b.u32(2 + 14 * mark45.length);
        b.u16(mark45.length);
        for (int i = 0; i < mark45.length; i++) {
            b.u8(0);
            b.u8(1);
            b.u16(markItem[i]);
            b.u32(mark45[i]);
            b.u16(0xFFFF);
            b.u32(0);
        }
        byte[] r = b.toByteArray();
        put32(r, 8, listStart);
        put32(r, 12, markStart);
        put32(r, 16, 0);
        return r;
    }

    // ================================================================ helpers

    private static void put32(byte[] b, int o, long v) {
        b[o] = (byte) (v >> 24); b[o + 1] = (byte) (v >> 16); b[o + 2] = (byte) (v >> 8); b[o + 3] = (byte) v;
    }

    static final class Bytes extends ByteArrayOutputStream {
        void u8(int v) { write(v & 0xFF); }
        void u16(int v) { u8(v >> 8); u8(v); }
        void u32(long v) { u8((int) (v >> 24)); u8((int) (v >> 16)); u8((int) (v >> 8)); u8((int) v); }
        void zero(int n) { for (int i = 0; i < n; i++) write(0); }
        void raw(byte[] b) { write(b, 0, b.length); }
        void ascii(String s) { for (int i = 0; i < s.length(); i++) write(s.charAt(i)); }
    }

    /** Splits an MPEG-2 elementary stream (one GOP) into access units (one per picture). */
    public static byte[][] splitMpeg2(byte[] es) {
        int[] starts = new int[64];
        int n = 0;
        int firstPic = -1;
        for (int i = 0; i + 3 < es.length; i++) {
            if (es[i] == 0 && es[i + 1] == 0 && es[i + 2] == 1 && es[i + 3] == 0) {
                if (firstPic < 0) {
                    firstPic = i;
                    starts[n++] = 0;                          // include sequence + GOP header in the first AU
                } else {
                    if (n == starts.length) {
                        int[] s = new int[n * 2];
                        System.arraycopy(starts, 0, s, 0, n);
                        starts = s;
                    }
                    starts[n++] = i;
                }
            }
        }
        byte[][] aus = new byte[n][];
        for (int i = 0; i < n; i++) {
            int end = (i + 1 < n) ? starts[i + 1] : es.length;
            aus[i] = new byte[end - starts[i]];
            System.arraycopy(es, starts[i], aus[i], 0, aus[i].length);
        }
        return aus;
    }
}
