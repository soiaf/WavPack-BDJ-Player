/*
 * Art - finds, decodes and caches cover pictures.
 *
 * Album cover: cover / folder / front / album .jpg/.png in the album folder
 * (or the only picture there), else the first track's embedded APEv2
 * "Cover Art (Front)". A track with its own embedded cover shows that instead.
 * Pictures are checked from their header before decoding (players have little
 * memory), decoded in a background thread and scaled once into a cached image.
 *
 * Java 1.3-level code.
 */
package wvplayer;

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Image;
import java.awt.MediaTracker;
import java.awt.Toolkit;
import java.io.File;
import java.util.Hashtable;
import java.util.Vector;

class Art {

    static final int MAX_SIDE = 1600;           // larger pictures are skipped (memory)
    static final int CACHE = 6;                 // decoded pictures kept

    /** A decoded picture and the size to draw it at. */
    static final class Pic {
        final Image img;
        final int w, h;
        Pic(Image img, int w, int h) { this.img = img; this.w = w; this.h = h; }
    }

    private final Component owner;
    private final PlayerXlet log;
    private final Hashtable cache = new Hashtable();       // key@size -> Pic
    private final Vector order = new Vector();             // LRU order of cache keys
    private final Hashtable bad = new Hashtable();
    private final Hashtable loading = new Hashtable();
    private final Hashtable folderCovers = new Hashtable(); // dir -> path or ""

    Art(Component owner, PlayerXlet log) {
        this.owner = owner;
        this.log = log;
    }

    /** Source key for an album's cover. */
    static String albumKey(String dir, String firstTrack) {
        return "album:" + dir + "|" + firstTrack;
    }

    /** Source key for a track: its own embedded cover, else its album's cover. */
    static String trackKey(Library lib, int track, String dir) {
        Library.Track t = lib.tracks[track];
        if (t.coverLength > 0) return "emb:" + t.path + "|" + t.coverOffset + "|" + t.coverLength;
        return albumKey(dir, lib.tracks.length > 0 ? lib.tracks[0].path : "");
    }

    /** Cached picture or null; starts a background load (and repaints when done). */
    Pic peek(final String src, final int size) {
        if (src == null || bad.containsKey(src)) return null;
        final String key = src + "@" + size;
        Pic p = (Pic) cache.get(key);
        if (p != null || loading.containsKey(key)) return p;
        loading.put(key, key);
        new Thread(new Runnable() {
            public void run() {
                load(src, size);
                loading.remove(key);
                owner.repaint();
            }
        }, "wv-art").start();
        return null;
    }

    private synchronized void load(String src, int size) {
        String key = src + "@" + size;
        if (cache.containsKey(key)) return;
        try {
            byte[] data = bytes(src);
            if (data == null) {
                bad.put(src, src);                      // simply no picture
                return;
            }
            int[] wh = dimensions(data);
            if (wh == null) throw new Exception("not a JPEG or PNG picture");
            if (wh[0] > MAX_SIDE || wh[1] > MAX_SIDE) {
                throw new Exception(wh[0] + "x" + wh[1] + " is too large - run wvlibrary.jar again (it resizes covers)");
            }
            if (wh[2] == 1) {
                throw new Exception("progressive JPEG, which the PS3 can't show - run wvlibrary.jar again (it converts covers)");
            }
            Image raw = Toolkit.getDefaultToolkit().createImage(data);
            MediaTracker mt = new MediaTracker(owner);
            mt.addImage(raw, 0);
            mt.waitForID(0);
            if (mt.isErrorID(0) || raw.getWidth(null) <= 0) throw new Exception("the player could not decode it");
            int w = raw.getWidth(null), h = raw.getHeight(null);
            int tw = size, th = size;
            if (w >= h) th = Math.max(1, size * h / w); else tw = Math.max(1, size * w / h);
            Image scaled = offscreen(tw, th);
            Pic pic;
            if (scaled != null) {
                Graphics g = scaled.getGraphics();
                g.drawImage(raw, 0, 0, tw, th, null);
                g.dispose();
                raw.flush();
                pic = new Pic(scaled, tw, th);
            } else {
                pic = new Pic(raw, tw, th);
            }
            cache.put(key, pic);
            order.addElement(key);
            while (order.size() > CACHE) {
                String old = (String) order.elementAt(0);
                order.removeElementAt(0);
                Pic gone = (Pic) cache.remove(old);
                if (gone != null) gone.img.flush();
            }
        } catch (Throwable e) {
            bad.put(src, src);
            log.log("Cover not shown: " + e.getMessage());
        }
    }

    /** Picture bytes for a source key, or null if there is no picture. */
    private byte[] bytes(String src) throws Exception {
        if (src.startsWith("emb:")) {
            String[] f = split(src.substring(4));
            return Tags.readCover(f[0], Long.parseLong(f[1]), Integer.parseInt(f[2]));
        }
        if (src.startsWith("album:")) {
            String[] f = split(src.substring(6));
            String cover = folderCover(f[0]);
            if (cover != null) return PlayerXlet.readAll(cover);
            if (f.length > 1 && f[1].length() > 0) {
                Tags t = Tags.read(f[1], true);
                return t.cover;
            }
            return null;
        }
        return PlayerXlet.readAll(src);
    }

    private static String[] split(String s) {
        Vector v = new Vector();
        int p = 0;
        while (true) {
            int q = s.indexOf('|', p);
            if (q < 0) { v.addElement(s.substring(p)); break; }
            v.addElement(s.substring(p, q));
            p = q + 1;
        }
        String[] r = new String[v.size()];
        v.copyInto(r);
        return r;
    }

    /** A cover picture file in a folder, or null. */
    String folderCover(String dir) {
        String c = (String) folderCovers.get(dir);
        if (c != null) return c.length() > 0 ? c : null;
        String found = null;
        String[] names = new File(dir).list();
        if (names != null) {
            String[] preferred = { "cover", "folder", "front", "album" };
            for (int p = 0; p < preferred.length && found == null; p++) {
                for (int i = 0; i < names.length; i++) {
                    String n = names[i].toLowerCase();
                    if (n.equals(preferred[p] + ".jpg") || n.equals(preferred[p] + ".jpeg") || n.equals(preferred[p] + ".png")) {
                        found = dir + "/" + names[i];
                        break;
                    }
                }
            }
            if (found == null) {
                int pictures = 0;
                String single = null;
                for (int i = 0; i < names.length; i++) {
                    String n = names[i].toLowerCase();
                    if (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")) {
                        pictures++;
                        single = dir + "/" + names[i];
                    }
                }
                if (pictures == 1) found = single;
            }
        }
        folderCovers.put(dir, found != null ? found : "");
        return found;
    }

    /** An off-screen image: DVBBufferedImage on BD-J, BufferedImage on desktop JVMs (testing). */
    static Image offscreen(int w, int h) {
        try {
            Image i = new org.dvb.ui.DVBBufferedImage(w, h);
            if (i.getGraphics() != null) return i;
        } catch (Throwable t) { }
        try {
            Class c = Class.forName("java.awt.image.BufferedImage");
            Object i = c.getConstructor(new Class[] { Integer.TYPE, Integer.TYPE, Integer.TYPE })
                        .newInstance(new Object[] { new Integer(w), new Integer(h), new Integer(2) });
            return (Image) i;
        } catch (Throwable t) { }
        return null;
    }

    /** Width, height and progressive flag from a JPEG or PNG header, or null. */
    static int[] dimensions(byte[] d) {
        if (d.length > 24 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') {
            return new int[] { be32(d, 16), be32(d, 20), 0 };
        }
        if (d.length > 4 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8) {
            int p = 2;
            while (p + 9 < d.length) {
                if ((d[p] & 0xFF) != 0xFF) { p++; continue; }
                int m = d[p + 1] & 0xFF;
                if (m == 0xFF) { p++; continue; }
                if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { p += 2; continue; }
                int len = ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
                if (m >= 0xC0 && m <= 0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                    int h = ((d[p + 5] & 0xFF) << 8) | (d[p + 6] & 0xFF);
                    int w = ((d[p + 7] & 0xFF) << 8) | (d[p + 8] & 0xFF);
                    return new int[] { w, h, (m == 0xC2 || m == 0xC6 || m == 0xCA || m == 0xCE) ? 1 : 0 };
                }
                p += 2 + len;
            }
        }
        return null;
    }

    private static int be32(byte[] b, int o) {
        return ((b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }
}
