import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import bdclip.ClipWriter;

/**
 * Desktop tool: writes a test tone clip using the same ClipWriter the xlet uses.
 *   java MakeClip <BDMV dir> <clip 5 digits> <seconds> <tone Hz> <gop.m2v or -> [raw-pcm-out]
 * Tone is stereo 48 kHz 16-bit; left = tone, right = tone at 1.5x frequency.
 */
public class MakeClip {
    public static void main(String[] a) throws Exception {
        File bdmv = new File(a[0]);
        String name = a[1];
        double secs = Double.parseDouble(a[2]);
        double hz = Double.parseDouble(a[3]);
        byte[][] gop = null;
        if (!a[4].equals("-")) {
            File f = new File(a[4]);
            byte[] es = new byte[(int) f.length()];
            FileInputStream in = new FileInputStream(f);
            int o = 0;
            while (o < es.length) o += in.read(es, o, es.length - o);
            in.close();
            gop = ClipWriter.splitMpeg2(es);
        }
        new File(bdmv, "STREAM").mkdirs();
        new File(bdmv, "CLIPINF").mkdirs();
        new File(bdmv, "PLAYLIST").mkdirs();
        FileOutputStream ts = new FileOutputStream(new File(bdmv, "STREAM/" + name + ".m2ts"));
        ClipWriter w = new ClipWriter(new BufferedOutputStream(ts, 65536), 48000, 2, 16, gop, 6, 2);
        FileOutputStream raw = a.length > 5 ? new FileOutputStream(a[5]) : null;

        int frames = (int) (secs * 48000);
        byte[] buf = new byte[4 * 1000];
        int n = 0;
        for (int i = 0; i < frames; i++) {
            double t = i / 48000.0;
            double env = Math.min(1.0, Math.min(t / 0.02, (secs - t) / 0.02));
            int l = (int) Math.round(0.4 * 32767 * env * Math.sin(2 * Math.PI * hz * t));
            int r = (int) Math.round(0.4 * 32767 * env * Math.sin(2 * Math.PI * hz * 1.5 * t));
            buf[n++] = (byte) (l >> 8); buf[n++] = (byte) l;
            buf[n++] = (byte) (r >> 8); buf[n++] = (byte) r;
            if (n == buf.length || i == frames - 1) {
                w.writePcm(buf, 0, n);
                if (raw != null) raw.write(buf, 0, n);
                n = 0;
            }
        }
        w.finish();
        ts.close();
        if (raw != null) raw.close();
        write(new File(bdmv, "CLIPINF/" + name + ".clpi"), w.clipInfo());
        write(new File(bdmv, "PLAYLIST/" + name + ".mpls"), w.playlist(name, null));
        System.out.println("clip " + name + ": " + w.getPacketCount() + " packets, "
                + (w.getEnd45() - w.getStart45()) / 45000.0 + " s, late PES: " + w.getLateCount()
                + (gop != null ? ", video GOP of " + gop.length + " frames" : ", audio only"));
    }

    static void write(File f, byte[] b) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(b);
        o.close();
    }
}
