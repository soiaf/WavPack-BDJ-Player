package wvplayer;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

import bdclip.ClipWriter;

/**
 * Build-time tool: writes the silent clip the player runs between songs (playlist 00999,
 * clip 99999: black 1080i video, silent 48 kHz 24-bit stereo), so the video never stops.
 *   java wvplayer.IdleClip <BDMV dir> <seconds> <black1080i.m2v>
 */
public class IdleClip {
    public static void main(String[] a) throws Exception {
        File bdmv = new File(a[0]);
        double secs = Double.parseDouble(a[1]);
        File g = new File(a[2]);
        byte[] es = new byte[(int) g.length()];
        FileInputStream in = new FileInputStream(g);
        int o = 0;
        while (o < es.length) o += in.read(es, o, es.length - o);
        in.close();
        String[] dirs = { "STREAM", "CLIPINF", "PLAYLIST", "BACKUP/CLIPINF", "BACKUP/PLAYLIST" };
        for (int i = 0; i < dirs.length; i++) new File(bdmv, dirs[i]).mkdirs();
        FileOutputStream ts = new FileOutputStream(new File(bdmv, "STREAM/99999.m2ts"));
        // 24-bit like the music, so switching from it doesn't change the audio format
        ClipWriter w = new ClipWriter(new BufferedOutputStream(ts, 65536), 48000, 2, 24,
                                      ClipWriter.splitMpeg2(es), 4, 4);   // 1080i 29.97, as the player
        byte[] silence = new byte[6 * 4800];
        for (int i = 0; i < (int) (secs * 10); i++) w.writePcm(silence, 0, silence.length);
        w.finish();
        ts.close();
        byte[] ci = w.clipInfo(), pl = w.playlist("99999", null);
        write(new File(bdmv, "CLIPINF/99999.clpi"), ci);
        write(new File(bdmv, "BACKUP/CLIPINF/99999.clpi"), ci);
        write(new File(bdmv, "PLAYLIST/00999.mpls"), pl);   // playlist ids only go to 01999
        write(new File(bdmv, "BACKUP/PLAYLIST/00999.mpls"), pl);
        System.out.println("silent clip (playlist 00999, clip 99999): " + (w.getEnd45() - w.getStart45()) / 45000.0 + " s, "
                           + new File(bdmv, "STREAM/99999.m2ts").length() / 1024 + " KB");
    }

    static void write(File f, byte[] b) throws Exception {
        FileOutputStream o = new FileOutputStream(f);
        o.write(b);
        o.close();
    }
}
