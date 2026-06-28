package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

/**
 * In-place iterative radix-2 Cooley-Tukey FFT, shared by the frequency-domain
 * visualizers (spectrum bars, reactor corona). Allocation-free: the caller owns
 * the {@code re}/{@code im} arrays.
 */
final class Fft {

	private Fft() {}

	/**
	 * Transform {@code re}/{@code im} in place. {@code n} must be a power of two
	 * and equal to both array lengths used by the caller.
	 */
	static void transform(float[] re, float[] im, int n) {
		// Bit-reversal permutation.
		for (int i = 1, j = 0; i < n; i++) {
			int bit = n >> 1;
			for (; (j & bit) != 0; bit >>= 1) j ^= bit;
			j ^= bit;
			if (i < j) {
				float t = re[i]; re[i] = re[j]; re[j] = t;
				t = im[i]; im[i] = im[j]; im[j] = t;
			}
		}
		for (int len = 2; len <= n; len <<= 1) {
			double ang   = -2 * Math.PI / len;
			float  wLenR = (float) Math.cos(ang);
			float  wLenI = (float) Math.sin(ang);
			int    half  = len >> 1;
			for (int i = 0; i < n; i += len) {
				float wR = 1f, wI = 0f;
				for (int k = 0; k < half; k++) {
					int   a   = i + k;
					int   bIx = a + half;
					float bR  = re[bIx] * wR - im[bIx] * wI;
					float bI  = re[bIx] * wI + im[bIx] * wR;
					re[bIx] = re[a] - bR;
					im[bIx] = im[a] - bI;
					re[a]  += bR;
					im[a]  += bI;
					float nR = wR * wLenR - wI * wLenI;
					wI = wR * wLenI + wI * wLenR;
					wR = nR;
				}
			}
		}
	}
}
