/*
 * Resampler - rational-ratio polyphase resampler (Kaiser-windowed sinc).
 *
 * Used to turn 44.1 kHz audio into 48 kHz (ratio 160/147), which Blu-ray LPCM
 * requires. The state is kept between calls, so consecutive tracks fed through
 * one Resampler join without a gap or click.
 *
 * Java 1.3-level code, float arithmetic.
 */
package bdclip;

public class Resampler {

    private final int up, down;            // output rate / input rate = up / down
    private final int taps;                // filter taps per phase (input samples per output)
    private final int channels;
    private final float[][] coef;          // [phase][tap]
    private final float[][] hist;          // per channel ring of the last 'taps' input samples
    private int histPos = 0;
    private long inCount = 0;              // input samples consumed (per channel)
    private long outCount = 0;             // output samples produced
    private final int delay;               // group delay in input samples

    /**
     * @param inRate    e.g. 44100
     * @param outRate   e.g. 48000
     * @param channels  interleaved channel count
     * @param taps      taps per phase (e.g. 64; more = sharper filter, more CPU)
     */
    public Resampler(int inRate, int outRate, int channels, int taps) {
        int g = gcd(inRate, outRate);
        this.up = outRate / g;
        this.down = inRate / g;
        this.taps = taps;
        this.channels = channels;
        this.delay = taps / 2;
        this.coef = new float[up][taps];
        this.hist = new float[channels][2 * taps];

        // Prototype low-pass at the upsampled rate (inRate * up), cutoff at the lower
        // Nyquist (22.05 kHz for 44.1 -> 48). With 64 taps/phase and beta 9 the
        // transition is ~4 kHz wide: flat to ~20 kHz, >= ~90 dB from ~24 kHz, so
        // images of the 44.1 kHz spectrum cannot fold back into the audible band.
        double nyq = 0.5 * Math.min(inRate, outRate);
        double fc = nyq / ((double) inRate * up);             // normalised to upsampled rate
        int len = taps * up;
        double beta = 9.0;                                    // ~90 dB stopband
        double i0b = bessel0(beta);
        double centre = (len - 1) / 2.0;
        for (int n = 0; n < len; n++) {
            double x = n - centre;
            double sinc = (x == 0) ? 2 * fc : Math.sin(2 * Math.PI * fc * x) / (Math.PI * x);
            double r = 2.0 * n / (len - 1) - 1.0;
            double w = bessel0(beta * Math.sqrt(Math.max(0, 1 - r * r))) / i0b;
            // gain 'up' compensates for zero-stuffing; phase p uses n = p + k*up
            coef[n % up][n / up] = (float) (sinc * w * up);
        }
        for (int p = 0; p < up; p++) {                        // exact unity gain on every phase
            double sum = 0;
            for (int k = 0; k < taps; k++) sum += coef[p][k];
            for (int k = 0; k < taps; k++) coef[p][k] = (float) (coef[p][k] / sum);
        }
    }

    /**
     * Feed interleaved samples (any scale) and receive interleaved output.
     *
     * @param in      interleaved input
     * @param frames  input frames in 'in'
     * @param out     output buffer; needs room for at least frames*up/down + 2 frames
     * @return output frames written
     */
    public int process(float[] in, int frames, float[] out) {
        int o = 0;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < channels; c++) {
                float v = in[f * channels + c];
                hist[c][histPos] = v;                         // stored twice so any window of
                hist[c][histPos + taps] = v;                  // 'taps' samples is contiguous
            }
            histPos = (histPos + 1) % taps;
            inCount++;
            o = emit(out, o);
        }
        return o / channels;
    }

    /** Push 'delay' zeros through so the tail of the last track comes out. */
    public int flush(float[] out) {
        int o = 0;
        for (int f = 0; f < delay + 1; f++) {
            for (int c = 0; c < channels; c++) {
                hist[c][histPos] = 0;
                hist[c][histPos + taps] = 0;
            }
            histPos = (histPos + 1) % taps;
            inCount++;
            o = emit(out, o);
        }
        return o / channels;
    }

    /** Produce every output sample whose input position is now available. */
    private int emit(float[] out, int o) {
        // output n sits at input position n*down/up; it needs input up to that + delay
        while (true) {
            long num = outCount * down;                       // position * up
            long base = num / up;                             // integer input index
            int phase = (int) (num - base * up);
            if (base + delay >= inCount) break;
            // newest sample (index inCount-1) is at histPos-1 (+taps); the one we need,
            // base+delay, is 'back' samples older. Coefficients run newest -> oldest,
            // so walk the contiguous window backwards.
            int back = (int) (inCount - 1 - (base + delay));
            int newestPos = histPos - 1 + taps - back;        // in [0, 2*taps)
            float[] h = coef[phase];
            if (channels == 2 && (taps & 3) == 0) {
                // both channels in one pass, unrolled by 4; same additions in the same
                // order as the plain loop below, so the output is bit-identical (just
                // far fewer bytecodes per sample on players without a JIT)
                float[] xl = hist[0], xr = hist[1];
                float al = 0, ar = 0;
                int j = newestPos;
                for (int k = 0; k < taps; k += 4) {
                    float h0 = h[k], h1 = h[k + 1], h2 = h[k + 2], h3 = h[k + 3];
                    al += h0 * xl[j];
                    ar += h0 * xr[j];
                    al += h1 * xl[j - 1];
                    ar += h1 * xr[j - 1];
                    al += h2 * xl[j - 2];
                    ar += h2 * xr[j - 2];
                    al += h3 * xl[j - 3];
                    ar += h3 * xr[j - 3];
                    j -= 4;
                }
                out[o++] = al;
                out[o++] = ar;
            } else {
                for (int c = 0; c < channels; c++) {
                    float[] x = hist[c];
                    float acc = 0;
                    int j = newestPos;
                    for (int k = 0; k < taps; k++) {
                        acc += h[k] * x[j--];
                    }
                    out[o++] = acc;
                }
            }
            outCount++;
        }
        return o;
    }

    public long getOutputFrames() { return outCount; }

    /**
     * Start in the middle of a stream: the next output sample is outIndex, and the
     * next input fed is inIndex. Output is identical to an uninterrupted run as
     * long as inIndex <= firstInputFor(outIndex).
     */
    public void startAt(long outIndex, long inIndex) {
        for (int c = 0; c < channels; c++) {
            for (int i = 0; i < hist[c].length; i++) hist[c][i] = 0;
        }
        histPos = 0;
        outCount = outIndex;
        inCount = inIndex;
    }

    /** Earliest input sample that output sample outIndex depends on. */
    public long firstInputFor(long outIndex) {
        return outIndex * down / up + delay - taps + 1;
    }

    /** Output samples produced for inFrames input samples, including flush(). */
    public static long outputLength(long inFrames, int inRate, int outRate) {
        int g = gcd(inRate, outRate);
        long up = outRate / g, down = inRate / g;
        return ((inFrames + 1) * up + down - 1) / down;
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int t = a % b; a = b; b = t; }
        return a;
    }

    private static double bessel0(double x) {
        double sum = 1, term = 1, y = x * x / 4;
        for (int k = 1; k < 50; k++) {
            term *= y / (k * k);
            sum += term;
            if (term < 1e-12 * sum) break;
        }
        return sum;
    }
}
