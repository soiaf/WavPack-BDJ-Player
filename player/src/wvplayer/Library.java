/*
 * Library - the tracks on the disc, how they are grouped into Blu-ray clips,
 * and what has been prepared in local storage (BUDA).
 *
 * Java 1.3-level code.
 */
package wvplayer;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Vector;

import bdclip.ClipWriter;

import com.beatofthedrum.wvdecoder.WavPackUtils;
import com.beatofthedrum.wvdecoder.WavpackContext;

class Library {

    static final String VERSION = "wvplayer-2";
    static final int MAX_GROUPS = 8;                   // clips per album (format changes); more -> one common format
    static final int CLIP_SLOTS = 150;                 // clips 00001..00150 in the pre-signed manifest

    /** One WavPack file. */
    static final class Track {
        String name;
        String path;
        long fileSize;
        int inRate, bytesPerSample, bits, channels;
        long samples;                                   // per channel
        int group;                                      // clip index (0-based)
        long mark45;                                    // start in its clip's 45 kHz timeline
        double offsetSec;                               // start in the whole playlist
        double durationSec;
        String error;
        String title, artist, album, trackNo;          // from the APEv2 tag, may be null
        long coverOffset = -1;                          // embedded front cover, if any
        int coverLength = 0;

        /** Title from the tag, else the file name without ".wv". */
        String display() {
            if (title != null && title.length() > 0) return title;
            String n = name.toLowerCase().endsWith(".wv") ? name.substring(0, name.length() - 3) : name;
            // "03 - Song" / "03. Song" / "03_Song" -> "Song" (the list already shows the number)
            int i = 0;
            while (i < n.length() && i < 3 && Character.isDigit(n.charAt(i))) i++;
            if (i > 0) {
                int j = i;
                while (j < n.length() && " .-_".indexOf(n.charAt(j)) >= 0) j++;
                if (j > i && j < n.length()) return n.substring(j);
            }
            return n;
        }
    }

    /** One output clip: consecutive tracks sharing input rate and output format. */
    static final class Group {
        int inRate, outRate, outBits;
        int firstTrack, lastTrack;
        int index;
        int clipId;                                     // allocated when preparing
        String clip() { return five(clipId); }
    }

    static String five(int n) {
        String s = Integer.toString(n);
        while (s.length() < 5) s = "0" + s;
        return s;
    }

    final String musicDir;
    final Track[] tracks;
    final int playlistId;

    String playlistName() { return five(playlistId); }
    String stateName() { return "wvp" + five(playlistId) + ".txt"; }
    Group[] groups = new Group[0];
    ClipWriter.PlayItem[] items = new ClipWriter.PlayItem[0];

    Library(String musicDir, String[] names, int playlistId) {
        this.musicDir = musicDir;
        this.playlistId = playlistId;
        tracks = new Track[names.length];
        for (int i = 0; i < names.length; i++) {
            Track t = new Track();
            t.name = names[i];
            t.path = musicDir + "/" + names[i];
            t.fileSize = new File(t.path).length();
            tracks[i] = t;
        }
    }

    String album, artist;                              // most common values across tracks

    /** Reads every file's APEv2 tag (only the end of each file is read). */
    void readTags() {
        java.util.Hashtable albums = new java.util.Hashtable(), artists = new java.util.Hashtable();
        for (int i = 0; i < tracks.length; i++) {
            Track t = tracks[i];
            Tags g = Tags.read(t.path, false);
            t.title = g.get("title");
            t.artist = g.get("artist");
            t.album = g.get("album");
            t.trackNo = g.get("track");
            t.coverOffset = g.coverOffset;
            t.coverLength = g.coverLength;
            String aa = g.get("album artist");
            count(albums, t.album);
            count(artists, aa != null ? aa : t.artist);
        }
        album = most(albums);
        artist = most(artists);
    }

    private static void count(java.util.Hashtable h, String v) {
        if (v == null || v.length() == 0) return;
        Integer n = (Integer) h.get(v);
        h.put(v, new Integer(n == null ? 1 : n.intValue() + 1));
    }

    private static String most(java.util.Hashtable h) {
        String best = null;
        int n = 0;
        for (java.util.Enumeration e = h.keys(); e.hasMoreElements();) {
            String k = (String) e.nextElement();
            int c = ((Integer) h.get(k)).intValue();
            if (c > n) { n = c; best = k; }
        }
        return best;
    }

    double totalSeconds() {
        double s = 0;
        for (int i = 0; i < tracks.length; i++) if (tracks[i].group >= 0) s += tracks[i].durationSec;
        return s;
    }

    /** Identifies this exact set of files; stored with the prepared clips. */
    String fingerprint() {
        StringBuffer b = new StringBuffer(VERSION);
        for (int i = 0; i < tracks.length; i++) {
            b.append('|').append(tracks[i].name).append(':').append(tracks[i].fileSize);
        }
        return b.toString();
    }

    /** Reads every file's header and works out the clip groups. */
    void scanHeaders() {
        for (int i = 0; i < tracks.length; i++) {
            Track t = tracks[i];
            DataInputStream in = null;
            try {
                in = new DataInputStream(new BufferedInputStream(new FileInputStream(t.path), 16384));
                WavpackContext w = WavPackUtils.WavpackOpenFileInput(in);
                if (w.error) {
                    t.error = w.error_message;
                    continue;
                }
                t.inRate = (int) WavPackUtils.WavpackGetSampleRate(w);
                t.bytesPerSample = WavPackUtils.WavpackGetBytesPerSample(w);
                t.bits = WavPackUtils.WavpackGetBitsPerSample(w);
                t.channels = WavPackUtils.WavpackGetReducedChannels(w);
                t.samples = WavPackUtils.WavpackGetNumSamples(w);
                t.durationSec = t.samples / (double) t.inRate;
            } catch (Throwable e) {
                t.error = e.toString();
            } finally {
                try { if (in != null) in.close(); } catch (Throwable e) { }
            }
        }
        plan(false);
        if (groups.length > MAX_GROUPS) {
            plan(true);                                 // too many format changes: one common format
        }
    }

    static int outRateFor(int in) {
        if (in == 48000 || in == 96000 || in == 192000) return in;
        if (in == 88200) return 96000;
        if (in == 176400) return 192000;
        return 48000;                                   // 44.1 kHz and anything else
    }

    private void plan(boolean forceCommon) {
        Vector v = new Vector();
        Group g = null;
        for (int i = 0; i < tracks.length; i++) {
            Track t = tracks[i];
            if (t.error != null) {
                t.group = -1;
                continue;
            }
            int outRate = forceCommon ? 48000 : outRateFor(t.inRate);
            boolean resample = (outRate != t.inRate);
            int outBits = (resample || forceCommon || t.bytesPerSample > 2) ? 24 : 16;
            if (g == null || g.inRate != t.inRate || g.outRate != outRate || g.outBits != outBits) {
                g = new Group();
                g.index = v.size();
                g.inRate = t.inRate;
                g.outRate = outRate;
                g.outBits = outBits;
                g.firstTrack = i;
                v.addElement(g);
            }
            g.lastTrack = i;
            t.group = g.index;
        }
        groups = new Group[v.size()];
        v.copyInto(groups);
    }

    /** Output bytes estimate (audio + video + transport overhead). */
    long estimatedBytes() {
        double total = 0;
        for (int i = 0; i < tracks.length; i++) {
            Track t = tracks[i];
            if (t.group < 0) continue;
            Group g = groups[t.group];
            total += t.durationSec * (g.outRate * 2 * (g.outBits / 8) * 1.08 + 60000);
        }
        return (long) total + 1000000L;
    }

    /** After preparing: work out each track's offset in the playlist timeline. */
    void computeOffsets() {
        double base = 0;
        for (int gi = 0; gi < groups.length; gi++) {
            ClipWriter.PlayItem it = items[gi];
            for (int i = groups[gi].firstTrack; i <= groups[gi].lastTrack; i++) {
                Track t = tracks[i];
                if (t.group != gi) continue;
                t.offsetSec = base + (t.mark45 - it.in45) / 45000.0;
            }
            base += (it.out45 - it.in45) / 45000.0;
        }
    }

    int playableCount() {
        int n = 0;
        for (int i = 0; i < tracks.length; i++) if (tracks[i].group >= 0) n++;
        return n;
    }

    /* ---------------------------------------------------------------- state file */

    /** Saves what the playlist contains, so later inserts can skip preparing. */
    void save(File dir) throws Exception {
        Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, stateName())), "UTF-8");
        try {
            w.write(fingerprint() + "\n");
            w.write(items.length + "\n");
            for (int i = 0; i < items.length; i++) w.write(items[i].toLine() + "\n");
            for (int i = 0; i < tracks.length; i++) {
                Track t = tracks[i];
                w.write(t.group + " " + t.mark45 + " " + t.offsetSec + " " + t.durationSec + "\n");
            }
        } finally {
            w.close();
        }
    }

    /** Loads the saved state if it matches the files on this disc. */
    boolean load(File dir) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(new File(dir, stateName())), "UTF-8"));
            if (!fingerprint().equals(r.readLine())) return false;
            int n = Integer.parseInt(r.readLine().trim());
            ClipWriter.PlayItem[] its = new ClipWriter.PlayItem[n];
            for (int i = 0; i < n; i++) its[i] = ClipWriter.PlayItem.fromLine(r.readLine());
            for (int i = 0; i < tracks.length; i++) {
                String[] f = split(r.readLine());
                tracks[i].group = Integer.parseInt(f[0]);
                tracks[i].mark45 = Long.parseLong(f[1]);
                tracks[i].offsetSec = Double.valueOf(f[2]).doubleValue();
                tracks[i].durationSec = Double.valueOf(f[3]).doubleValue();
            }
            items = its;
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable t) { }
        }
    }

    /** Whether the prepared state on disk matches this disc (without loading it). */
    boolean stateMatches(File dir) {
        BufferedReader r = null;
        try {
            r = new BufferedReader(new InputStreamReader(new FileInputStream(new File(dir, stateName())), "UTF-8"));
            return fingerprint().equals(r.readLine());
        } catch (Throwable t) {
            return false;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable t) { }
        }
    }

    static String[] split(String l) {
        Vector v = new Vector();
        java.util.StringTokenizer t = new java.util.StringTokenizer(l, " ");
        while (t.hasMoreTokens()) v.addElement(t.nextToken());
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }
}
