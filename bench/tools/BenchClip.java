import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import bdclip.ClipWriter;

/**
 * Desktop tool for the benchmark disc: writes clip 00001 (a quiet 440 Hz tone over
 * black 1080i video, 48 kHz 24-bit stereo, the same format the player writes).
 *   java BenchClip <BDMV dir> <seconds> <black1080i.m2v>
 */
public class BenchClip {
    public static void main(String[] a) throws Exception {
        File bdmv = new File(a[0]);
        double secs = Double.parseDouble(a[1]);
        File g = new File(a[2]);
        byte[] es = new byte[(int) g.length()];
        FileInputStream in = new FileInputStream(g);
        int o = 0;
        while (o < es.length) o += in.read(es, o, es.length - o);
        in.close();
        byte[][] gop = ClipWriter.splitMpeg2(es);
        String[] dirs = { "STREAM", "CLIPINF", "PLAYLIST", "BACKUP/CLIPINF", "BACKUP/PLAYLIST" };
        for (int i = 0; i < dirs.length; i++) new File(bdmv, dirs[i]).mkdirs();
        FileOutputStream ts = new FileOutputStream(new File(bdmv, "STREAM/00001.m2ts"));
        ClipWriter w = new ClipWriter(new BufferedOutputStream(ts, 65536), 48000, 2, 24, gop, 4, 4);
        int frames = (int) (secs * 48000);
        byte[] buf = new byte[6 * 1000];
        int n = 0;
        for (int i = 0; i < frames; i++) {
            double t = i / 48000.0;
            int v = (int) Math.round(0.03 * 8388607 * Math.sin(2 * Math.PI * 440 * t));
            for (int c = 0; c < 2; c++) { buf[n++] = (byte) (v >> 16); buf[n++] = (byte) (v >> 8); buf[n++] = (byte) v; }
            if (n == buf.length || i == frames - 1) { w.writePcm(buf, 0, n); n = 0; }
        }
        w.finish();
        ts.close();
        byte[] ci = w.clipInfo(), pl = w.playlist("00001", null);
        write(new File(bdmv, "CLIPINF/00001.clpi"), ci);
        write(new File(bdmv, "BACKUP/CLIPINF/00001.clpi"), ci);
        write(new File(bdmv, "PLAYLIST/00001.mpls"), pl);
        write(new File(bdmv, "BACKUP/PLAYLIST/00001.mpls"), pl);
        System.out.println("clip 00001: " + (w.getEnd45() - w.getStart45()) / 45000.0 + " s, late PES " + w.getLateCount());
    }

    static void write(File f, byte[] b) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(b);
        o.close();
    }
}
