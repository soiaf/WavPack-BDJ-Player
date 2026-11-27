/*
 * Preparer - sample format helpers shared by the chunk writer: decoder output
 * to big-endian LPCM (direct, or via float for the resampler).
 *
 * Java 1.3-level code.
 */
package wvplayer;

final class Preparer {

    static final int VIDEO_FORMAT = 4;      // 1080i
    static final int VIDEO_RATE = 4;        // 29.97 Hz - matches the PS3's normal output, no 24 Hz switch

    /* ---------------------------------------------------------------- sample conversion */

    /** Decoder ints (right-justified, bps bytes) -> big-endian stereo PCM, 16 or 24 bit, no resampling. */
    static int toPcmDirect(int[] s, int frames, int ch, int bps, int outBytes, byte[] out) {
        int shift = outBytes * 8 - bps * 8;              // >0 scale up, <0 scale down
        int o = 0;
        for (int f = 0; f < frames; f++) {
            for (int c = 0; c < 2; c++) {
                int v = s[f * ch + (ch == 1 ? 0 : c)];
                v = (shift >= 0) ? (v << shift) : (v >> -shift);
                if (outBytes == 3) {
                    out[o++] = (byte) (v >> 16);
                }
                out[o++] = (byte) (v >> 8);
                out[o++] = (byte) v;
            }
        }
        return o;
    }

    /** Decoder ints -> interleaved stereo floats on a 24-bit integer scale. */
    static void toFloat24(int[] s, int frames, int ch, int bps, float[] out) {
        int shift = 24 - bps * 8;
        int o = 0;
        for (int f = 0; f < frames; f++) {
            int l = s[f * ch];
            int r = (ch == 1) ? l : s[f * ch + 1];
            if (shift >= 0) {
                l <<= shift;
                r <<= shift;
            } else {
                l >>= -shift;
                r >>= -shift;
            }
            out[o++] = l;
            out[o++] = r;
        }
    }

    /** Interleaved stereo floats (24-bit scale) -> big-endian 24-bit PCM, rounded and clipped. */
    static int floatToPcm24(float[] in, int frames, byte[] out) {
        int o = 0;
        int n = frames * 2;
        for (int i = 0; i < n; i++) {
            float x = in[i];
            int v = (int) (x >= 0 ? x + 0.5f : x - 0.5f);
            if (v > 8388607) v = 8388607;
            else if (v < -8388608) v = -8388608;
            out[o++] = (byte) (v >> 16);
            out[o++] = (byte) (v >> 8);
            out[o++] = (byte) v;
        }
        return o;
    }

    private Preparer() { }
}
