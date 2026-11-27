/*
 * Catalog - the albums on the disc.
 *
 * Every folder under BDMV/JAR/00000/music that contains .wv files is an album.
 * With the usual layout  music/Artist/Album/tracks  the artist and album names
 * come from the folder names (tags refine them when an album is opened).
 * Loose .wv files directly in music/ form one album too.
 *
 * Java 1.3-level code.
 */
package wvplayer;

import java.io.File;
import java.util.Vector;

class Catalog {

    static final int MAX_ALBUMS = 900;          // playlists 00001..00900

    static final class Album {
        int index;                              // 0-based; playlist number = index + 1
        String rel;                             // path below music/, "" for loose files
        String dir;
        String artist = "", title = "";
        String[] files;                         // .wv names, sorted (from the index: just the first)
        int pl;                                 // playlist number from the library index (0: index + 1)
        int firstClip, clips;                   // chunk clips of this album

        int playlistId() { return pl > 0 ? pl : index + 1; }
        String label() { return artist.length() > 0 ? artist + "  —  " + title : title; }
    }

    final String root;
    Album[] albums = new Album[0];
    boolean truncated = false;

    /** Albums from the library index written by the PC tool (lines: pl, first clip, clips, first file, rel). */
    Catalog(String root, String[] lines) {
        this.root = root;
        Album[] a = new Album[lines.length];
        for (int i = 0; i < lines.length; i++) {
            String[] f = split(lines[i], '\t');
            Album al = new Album();
            al.index = i;
            al.pl = Integer.parseInt(f[0]);
            al.firstClip = Integer.parseInt(f[1]);
            al.clips = Integer.parseInt(f[2]);
            al.files = new String[] { f[3] };
            al.rel = f.length > 4 ? f[4] : "";
            al.dir = al.rel.length() > 0 ? root + "/" + al.rel : root;
            name(al);
            a[i] = al;
        }
        albums = a;
    }

    static String[] split(String s, char c) {
        Vector v = new Vector();
        int p = 0;
        while (true) {
            int q = s.indexOf(c, p);
            if (q < 0) {
                v.addElement(s.substring(p));
                break;
            }
            v.addElement(s.substring(p, q));
            p = q + 1;
        }
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }

    private static void name(Album al) {
        String[] parts = split(al.rel);
        if (parts.length == 0) {
            al.title = "Tracks";
        } else if (parts.length == 1) {
            al.title = parts[0];
        } else {
            al.artist = parts[0];
            StringBuffer t = new StringBuffer(parts[1]);
            for (int i = 2; i < parts.length; i++) t.append(" / ").append(parts[i]);   // e.g. Album / CD1
            al.title = t.toString();
        }
    }

    Catalog(String root) {
        this.root = root;
        Vector v = new Vector();
        walk(root, "", 0, v);
        Album[] a = new Album[v.size()];
        v.copyInto(a);
        // order by artist, then album (case-insensitive)
        for (int i = 1; i < a.length; i++) {
            Album k = a[i];
            int j = i - 1;
            while (j >= 0 && sortKey(a[j]).compareTo(sortKey(k)) > 0) { a[j + 1] = a[j]; j--; }
            a[j + 1] = k;
        }
        if (a.length > MAX_ALBUMS) {
            truncated = true;
            Album[] b = new Album[MAX_ALBUMS];
            System.arraycopy(a, 0, b, 0, MAX_ALBUMS);
            a = b;
        }
        for (int i = 0; i < a.length; i++) a[i].index = i;
        albums = a;
    }

    private static String sortKey(Album a) {
        return (a.artist + "\u0001" + a.title).toLowerCase();
    }

    private void walk(String dir, String rel, int depth, Vector out) {
        String[] names = new File(dir).list();
        if (names == null) return;
        sort(names);
        Vector wv = new Vector();
        for (int i = 0; i < names.length; i++) {
            if (names[i].toLowerCase().endsWith(".wv")) wv.addElement(names[i]);
        }
        if (wv.size() > 0) {
            Album al = new Album();
            al.rel = rel;
            al.dir = dir;
            al.files = new String[wv.size()];
            wv.copyInto(al.files);
            name(al);
            out.addElement(al);
        }
        if (depth >= 4) return;
        for (int i = 0; i < names.length; i++) {
            if (names[i].startsWith(".")) continue;
            File f = new File(dir, names[i]);
            if (f.isDirectory()) {
                walk(dir + "/" + names[i], rel.length() == 0 ? names[i] : rel + "/" + names[i], depth + 1, out);
            }
        }
    }

    /** Identifies the set of albums on this disc (not the audio data). */
    String fingerprint() {
        StringBuffer b = new StringBuffer(Library.VERSION);
        for (int i = 0; i < albums.length; i++) {
            Album a = albums[i];
            b.append('|').append(a.rel).append(':').append(a.files.length).append(':').append(a.files[0]);
        }
        return b.toString();
    }

    static String[] split(String rel) {
        Vector v = new Vector();
        java.util.StringTokenizer t = new java.util.StringTokenizer(rel, "/");
        while (t.hasMoreTokens()) v.addElement(t.nextToken());
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }

    static void sort(String[] a) {
        for (int i = 1; i < a.length; i++) {
            String k = a[i];
            int j = i - 1;
            while (j >= 0 && a[j].compareTo(k) > 0) { a[j + 1] = a[j]; j--; }
            a[j + 1] = k;
        }
    }
}
