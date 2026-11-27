import bdclip.Resampler;
import java.io.*;
/** java ResampleTest <freqHz> <out.f32> : 2 s stereo sine at 44.1k -> 48k, writes float32 LE output */
public class ResampleTest {
    public static void main(String[] a) throws Exception {
        double f = Double.parseDouble(a[0]);
        Resampler r = new Resampler(44100, 48000, 2, 64);
        int n = 44100 * 2;
        float[] in = new float[n * 2];
        for (int i = 0; i < n; i++) { float v = (float) (0.5 * Math.sin(2 * Math.PI * f * i / 44100)); in[2*i] = v; in[2*i+1] = -v; }
        float[] out = new float[(n * 160 / 147 + 200) * 2];
        long t0 = System.nanoTime();
        int m = 0;
        for (int off = 0; off < n; off += 4410) {            // feed in chunks, like the player
            float[] chunk = new float[4410 * 2];
            System.arraycopy(in, off * 2, chunk, 0, 4410 * 2);
            float[] o = new float[(4410 * 160 / 147 + 4) * 2];
            int k = r.process(chunk, 4410, o);
            System.arraycopy(o, 0, out, m * 2, k * 2); m += k;
        }
        float[] o = new float[200 * 2]; int k = r.flush(o); System.arraycopy(o, 0, out, m * 2, k * 2); m += k;
        long ns = System.nanoTime() - t0;
        DataOutputStream d = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(a[1])));
        for (int i = 0; i < m * 2; i++) d.writeInt(Integer.reverseBytes(Float.floatToIntBits(out[i])));
        d.close();
        System.out.println("in " + n + " frames -> out " + m + " frames (expected ~" + (n * 160 / 147) + "), " + (2.0e9 / ns) + "x realtime on this machine");
    }
}
