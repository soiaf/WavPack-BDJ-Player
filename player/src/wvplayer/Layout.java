/*
 * Layout - how one album is laid out as Blu-ray clips. Worked out on the PC by
 * the library tool (from the WavPack headers) and read by the player.
 *
 * An album is one or more groups (consecutive tracks with the same format).
 * Each group is one continuous LPCM + black video stream, cut into chunk clips
 * at video frames on a 5.005 s grid: a short chunk where each track starts,
 * then longer ones. Every chunk is a progressive asset: the player writes it
 * (decoding the WavPack files) when it is first needed and then enables it.
 *
 * Java 1.3-level code.
 */
package wvplayer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Writer;
import java.util.StringTokenizer;

final class Layout {

    static final String MAGIC = "wvlayout-1";
    static final int GRID = 150;                    // video frames per grid unit: 5.005 s, = 1001 LPCM frames
    /** Chunk lengths (grid units) after each track start; the last repeats. */
    static final int[] LADDER = { 2, 2, 3, 4, 5, 6, 8, 10, 12 };   // grows gently: slow players keep up

    static final class Group {
        int inRate, outRate, outBits;
        int firstTrack, lastTrack;
        long inSamples, outSamples;                 // per channel, whole group
        int firstClip;                              // clip number of chunk 0
        long[] cuts;                                // video frames where chunks 1.. start
        long[][] states;                            // writer state at the start of chunks 1..
        long[] in45, out45;                         // per chunk, in the group's 45 kHz timeline
        int firstItem;                              // play item of chunk 0 in the album playlist

        int chunks() { return cuts.length + 1; }
        int clip(int c) { return firstClip + c; }

        /** Chunk that plays at t45 (in this group's timeline). */
        int chunkAt(long t45) {
            int c = 0;
            while (c + 1 < in45.length && in45[c + 1] <= t45) c++;
            return c;
        }
    }

    static final class Track {
        String name;
        int group = -1;                             // -1: unreadable, skipped
        long inStart;                               // first input sample within its group
        long samples;
        int inRate;
        double durationSec;
        long mark45;                                // chapter position, group timeline
        double offsetSec;                           // position in the album playlist
    }

    int playlistId;
    String rel;                                     // folder below music/
    Group[] groups;
    Track[] tracks;

    String playlistName() { return five(playlistId); }

    int firstClipOf() { return groups.length > 0 ? groups[0].firstClip : 0; }

    int clipCount() {
        int n = 0;
        for (int i = 0; i < groups.length; i++) n += groups[i].chunks();
        return n;
    }

    static String five(int n) {
        String s = Integer.toString(n);
        while (s.length() < 5) s = "0" + s;
        return s;
    }

    /** Copies the layout's timing onto the tag/display model used by the screens. */
    void apply(Library lib) {
        for (int i = 0; i < lib.tracks.length && i < tracks.length; i++) {
            Library.Track t = lib.tracks[i];
            Track T = tracks[i];
            t.group = T.group;
            t.offsetSec = T.offsetSec;
            t.durationSec = T.durationSec;
            t.inRate = T.inRate;
            t.samples = T.samples;
            t.error = (T.group < 0) ? "unreadable" : null;
        }
    }

    String[] names() {
        String[] r = new String[tracks.length];
        for (int i = 0; i < r.length; i++) r[i] = tracks[i].name;
        return r;
    }

    /* ---------------------------------------------------------------- text form */

    void write(Writer w) throws IOException {
        w.write(MAGIC + "\n");
        w.write("album " + playlistId + " " + groups.length + " " + tracks.length + " " + rel + "\n");
        for (int g = 0; g < groups.length; g++) {
            Group G = groups[g];
            w.write("group " + G.inRate + " " + G.outRate + " " + G.outBits + " " + G.firstTrack + " " + G.lastTrack
                    + " " + G.inSamples + " " + G.outSamples + " " + G.firstClip + " " + G.firstItem + " " + G.chunks() + "\n");
            for (int c = 0; c < G.chunks(); c++) {
                StringBuffer b = new StringBuffer("c ");
                b.append(c == 0 ? 0 : G.cuts[c - 1]).append(' ').append(G.in45[c]).append(' ').append(G.out45[c]);
                if (c > 0) {
                    long[] st = G.states[c - 1];
                    for (int k = 0; k < st.length; k++) b.append(' ').append(st[k]);
                }
                w.write(b.toString() + "\n");
            }
        }
        for (int i = 0; i < tracks.length; i++) {
            Track t = tracks[i];
            w.write("t " + t.group + " " + t.inStart + " " + t.samples + " " + t.inRate + " " + t.durationSec + " "
                    + t.mark45 + " " + t.offsetSec + " " + t.name + "\n");
        }
    }

    static Layout read(InputStream in) throws IOException {
        BufferedReader r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        if (!MAGIC.equals(r.readLine())) throw new IOException("not a layout file");
        Layout L = new Layout();
        String[] f = fields(r.readLine(), 5);
        L.playlistId = Integer.parseInt(f[1]);
        L.groups = new Group[Integer.parseInt(f[2])];
        L.tracks = new Track[Integer.parseInt(f[3])];
        L.rel = f.length > 4 ? f[4] : "";
        for (int g = 0; g < L.groups.length; g++) {
            f = fields(r.readLine(), 0);
            Group G = new Group();
            G.inRate = Integer.parseInt(f[1]);
            G.outRate = Integer.parseInt(f[2]);
            G.outBits = Integer.parseInt(f[3]);
            G.firstTrack = Integer.parseInt(f[4]);
            G.lastTrack = Integer.parseInt(f[5]);
            G.inSamples = Long.parseLong(f[6]);
            G.outSamples = Long.parseLong(f[7]);
            G.firstClip = Integer.parseInt(f[8]);
            G.firstItem = Integer.parseInt(f[9]);
            int n = Integer.parseInt(f[10]);
            G.cuts = new long[n - 1];
            G.states = new long[n - 1][];
            G.in45 = new long[n];
            G.out45 = new long[n];
            for (int c = 0; c < n; c++) {
                f = fields(r.readLine(), 0);
                if (c > 0) G.cuts[c - 1] = Long.parseLong(f[1]);
                G.in45[c] = Long.parseLong(f[2]);
                G.out45[c] = Long.parseLong(f[3]);
                if (c > 0) {
                    long[] st = new long[f.length - 4];
                    for (int k = 0; k < st.length; k++) st[k] = Long.parseLong(f[4 + k]);
                    G.states[c - 1] = st;
                }
            }
            L.groups[g] = G;
        }
        for (int i = 0; i < L.tracks.length; i++) {
            f = fields(r.readLine(), 9);
            Track t = new Track();
            t.group = Integer.parseInt(f[1]);
            t.inStart = Long.parseLong(f[2]);
            t.samples = Long.parseLong(f[3]);
            t.inRate = Integer.parseInt(f[4]);
            t.durationSec = Double.valueOf(f[5]).doubleValue();
            t.mark45 = Long.parseLong(f[6]);
            t.offsetSec = Double.valueOf(f[7]).doubleValue();
            t.name = f[8];
            L.tracks[i] = t;
        }
        return L;
    }

    /** Space-separated fields; with last > 0 the last field takes the rest of the line (names may hold spaces). */
    static String[] fields(String line, int last) throws IOException {
        if (line == null) throw new IOException("layout file ends early");
        java.util.Vector v = new java.util.Vector();
        int p = 0;
        while (p < line.length()) {
            if (last > 0 && v.size() == last - 1) {
                v.addElement(line.substring(p));
                break;
            }
            int q = line.indexOf(' ', p);
            if (q < 0) q = line.length();
            if (q > p) v.addElement(line.substring(p, q));
            p = q + 1;
        }
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }

    static String[] words(String line) {
        java.util.Vector v = new java.util.Vector();
        StringTokenizer t = new StringTokenizer(line, " ");
        while (t.hasMoreTokens()) v.addElement(t.nextToken());
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }
}
