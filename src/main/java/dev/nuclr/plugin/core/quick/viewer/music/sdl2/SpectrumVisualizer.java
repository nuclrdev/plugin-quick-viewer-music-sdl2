package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * Neon spectrum-bars visualizer.
 * <p>
 * An FFT frequency analyzer rendered as a symmetric (centre-mirrored) bank of
 * rainbow "capsule" bars with a multi-pass bloom glow, slowly falling peak-hold
 * caps, a beat-reactive central spine and floating spark particles that pop off
 * the tips of loud bars.
 * <p>
 * Self-contained: it owns its iterative radix-2 FFT, smoothing state and a
 * fixed-size particle pool. Heavy buffers are pre-allocated to keep per-frame
 * garbage low; the SDL audio thread never touches this class (it reads the
 * {@link AudioRingBuffer} snapshot on the Swing EDT, like the aurora effect).
 */
final class SpectrumVisualizer {

	// ---- FFT / analysis ----
	private static final int   FFT_SIZE    = 2048;
	private static final int   NUM_BARS    = 64;
	private static final int   SAMPLE_RATE = 44100;
	private static final float MIN_FREQ    = 35f;
	private static final float MAX_FREQ    = 17000f;

	// ---- Bar dynamics ----
	private static final float BAR_ATTACK   = 0.52f;  // fast rise
	private static final float BAR_DECAY    = 0.14f;  // slow fall
	private static final float PEAK_GRAVITY = 0.0017f;
	private static final float SPAWN_LEVEL  = 0.60f;  // bar height that starts shedding sparks

	// ---- Particles ----
	private static final int MAX_PARTICLES = 140;

	// ---- Layout ----
	private static final float MAX_HALF_FRAC = 0.46f; // half-height occupied by a full bar
	private static final float BAR_FILL_FRAC = 0.62f; // bar width as fraction of its slot

	// ---- FFT working buffers (pre-allocated) ----
	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   barBin   = new int[NUM_BARS + 1];

	// ---- Bar state ----
	private final float[] barTarget  = new float[NUM_BARS];
	private final float[] barDisplay = new float[NUM_BARS];
	private final float[] barPeak    = new float[NUM_BARS];
	private final float[] barPeakVel = new float[NUM_BARS];
	private float autoGain = 1f;
	private float energy   = 0f; // smoothed overall level, drives beat reactions

	// ---- Particle pool ----
	private final float[] pX    = new float[MAX_PARTICLES];
	private final float[] pY    = new float[MAX_PARTICLES];
	private final float[] pVX   = new float[MAX_PARTICLES];
	private final float[] pVY   = new float[MAX_PARTICLES];
	private final float[] pLife = new float[MAX_PARTICLES];
	private final float[] pHue  = new float[MAX_PARTICLES];
	private int pNext = 0;

	private final Random rnd = new Random(0xC0FFEEL);

	SpectrumVisualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		buildBarBins();
	}

	/** Map the {@value NUM_BARS} bars onto logarithmically-spaced FFT bins (matches pitch perception). */
	private void buildBarBins() {
		float logMin = (float) Math.log(MIN_FREQ);
		float logMax = (float) Math.log(MAX_FREQ);
		int   nyquist = FFT_SIZE / 2;
		for (int b = 0; b <= NUM_BARS; b++) {
			float t    = (float) b / NUM_BARS;
			float freq = (float) Math.exp(logMin + (logMax - logMin) * t);
			int   bin  = Math.round(freq * FFT_SIZE / SAMPLE_RATE);
			barBin[b] = Math.max(1, Math.min(nyquist, bin));
		}
		// Guarantee every bar covers at least one bin.
		for (int b = 1; b <= NUM_BARS; b++) {
			if (barBin[b] <= barBin[b - 1]) {
				barBin[b] = Math.min(nyquist, barBin[b - 1] + 1);
			}
		}
	}

	// ---- Public render entry ----

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {
		int samples = ring != null ? ring.snapshot(snapshot, FFT_SIZE) : 0;
		boolean hasAudio = samples >= NUM_BARS * 2;

		if (hasAudio) {
			analyze(samples);
		} else {
			for (int b = 0; b < NUM_BARS; b++) barTarget[b] = 0f;
			energy += (0f - energy) * 0.08f;
		}

		advanceBars();
		drawScene(g2, w, h, frameCount, hasAudio);
	}

	// ---- Analysis ----

	private void analyze(int samples) {
		// Most-recent samples sit at snapshot[0..samples-1]; zero-pad the front if short.
		int pad = FFT_SIZE - samples;
		for (int i = 0; i < FFT_SIZE; i++) {
			float s = i >= pad ? snapshot[i - pad] : 0f;
			re[i] = s * hann[i];
			im[i] = 0f;
		}
		fft(re, im, FFT_SIZE);

		float maxMag = 1e-4f;
		for (int b = 0; b < NUM_BARS; b++) {
			int   lo = barBin[b];
			int   hi = barBin[b + 1];
			float power = 0f;
			for (int k = lo; k < hi; k++) {
				float p = re[k] * re[k] + im[k] * im[k];
				if (p > power) power = p; // peak-pick per band: punchier than averaging
			}
			// Linear magnitude with a gentle high-frequency tilt so quiet treble stays visible.
			float mag  = (float) Math.sqrt(power) / FFT_SIZE;
			float tilt = 1f + 1.5f * ((float) b / NUM_BARS);
			mag *= tilt;
			barTarget[b] = mag;
			if (mag > maxMag) maxMag = mag;
		}

		// Auto-gain so the loudest current bar roughly fills the panel; release slowly to avoid pumping.
		float targetGain = clamp(0.85f / maxMag, 0.5f, 60f);
		float rate = targetGain < autoGain ? 0.20f : 0.05f;
		autoGain += (targetGain - autoGain) * rate;

		float frameEnergy = 0f;
		for (int b = 0; b < NUM_BARS; b++) {
			// pow < 1 compresses the dynamic range into a fuller, livelier spectrum.
			float level = (float) Math.pow(barTarget[b] * autoGain, 0.72);
			barTarget[b] = clamp(level, 0f, 1.25f);
			frameEnergy += barTarget[b];
		}
		energy += (frameEnergy / NUM_BARS - energy) * 0.18f;
	}

	private void advanceBars() {
		for (int b = 0; b < NUM_BARS; b++) {
			float t = barTarget[b];
			float d = barDisplay[b];
			d += (t - d) * (t > d ? BAR_ATTACK : BAR_DECAY);
			barDisplay[b] = d;

			if (d >= barPeak[b]) {
				barPeak[b] = d;
				barPeakVel[b] = 0f;
			} else {
				barPeakVel[b] += PEAK_GRAVITY;
				barPeak[b] -= barPeakVel[b];
				if (barPeak[b] < d) {
					barPeak[b] = d;
					barPeakVel[b] = 0f;
				}
			}
		}
	}

	// ---- Rendering ----

	private void drawScene(Graphics2D g2, int w, int h, int frameCount, boolean hasAudio) {
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		float cy      = h * 0.5f;
		float maxHalf = h * MAX_HALF_FRAC;
		float slot    = (float) w / NUM_BARS;
		float barW    = Math.max(2f, slot * BAR_FILL_FRAC);
		float baseHue = (frameCount * 0.0009f) % 1f;

		drawSpine(g2, w, cy, baseHue, frameCount);

		for (int b = 0; b < NUM_BARS; b++) {
			float level = barDisplay[b];
			float hue   = (baseHue + (float) b / NUM_BARS * 0.92f) % 1f;
			float x     = b * slot + (slot - barW) * 0.5f;
			float half  = level * maxHalf;

			if (half > 1.2f) {
				float y0 = cy - half;
				int   ix = Math.round(x);
				int   iw = Math.round(barW);
				int   iy = Math.round(y0);
				int   ih = Math.round(half * 2f);

				// Bloom: two soft, widening halo passes behind the bar.
				g2.setColor(hsba(hue, 0.85f, 1f, (int) (28 * clamp(level, 0f, 1f))));
				g2.fillRoundRect(ix - 7, iy - 7, iw + 14, ih + 14, iw + 14, iw + 14);
				g2.setColor(hsba(hue, 0.80f, 1f, (int) (70 * clamp(level, 0f, 1f))));
				g2.fillRoundRect(ix - 3, iy - 3, iw + 6, ih + 6, iw + 6, iw + 6);

				// Capsule: hot/white tips fading to a saturated colour at the centre axis.
				Color tip = hsba(hue, 0.42f, 1f, 255);
				Color mid = hsba(hue, 0.95f, 0.72f, 240);
				g2.setPaint(new LinearGradientPaint(
						x, y0, x, cy + half,
						new float[]{0f, 0.5f, 1f},
						new Color[]{tip, mid, tip}));
				g2.fillRoundRect(ix, iy, iw, ih, iw, iw);
			}

			// Peak-hold caps mirrored above and below the axis.
			float ph = barPeak[b] * maxHalf;
			if (ph > 2.5f) {
				g2.setColor(hsba(hue, 0.22f, 1f, 230));
				int ix = Math.round(x);
				int iw = Math.round(barW);
				g2.fillRoundRect(ix, Math.round(cy - ph - 3f), iw, 3, 3, 3);
				g2.fillRoundRect(ix, Math.round(cy + ph),       iw, 3, 3, 3);
			}

			// Sparks fly off the tips of tall bars on the beat.
			if (hasAudio && level > SPAWN_LEVEL && rnd.nextFloat() < 0.12f * level) {
				spawnParticle(x + barW * 0.5f, cy - half, hue);
				spawnParticle(x + barW * 0.5f, cy + half, hue);
			}
		}

		updateAndDrawParticles(g2);
		drawVignette(g2, w, h);
	}

	/** Glowing central axis line whose width and brightness pulse with the music. */
	private void drawSpine(Graphics2D g2, int w, float cy, float baseHue, int frameCount) {
		float idle = 0.10f + 0.06f * (float) Math.sin(frameCount * 0.05);
		float e    = Math.max(clamp(energy, 0f, 1f), idle);
		int   y    = Math.round(cy);
		float hue  = (baseHue + 0.5f) % 1f;

		g2.setStroke(new BasicStroke(2f + 7f * e, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g2.setColor(hsba(hue, 0.55f, 1f, (int) (50 + 90 * e)));
		g2.drawLine(0, y, w, y);

		g2.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g2.setColor(hsba(hue, 0.18f, 1f, (int) (110 + 120 * e)));
		g2.drawLine(0, y, w, y);
	}

	// ---- Particles ----

	private void spawnParticle(float x, float y, float hue) {
		int i = pNext;
		pNext = (pNext + 1) % MAX_PARTICLES;
		float ang = rnd.nextFloat() * (float) (Math.PI * 2);
		float spd = 0.4f + rnd.nextFloat() * 1.7f;
		pX[i]    = x;
		pY[i]    = y;
		pVX[i]   = (float) Math.cos(ang) * spd;
		pVY[i]   = (float) Math.sin(ang) * spd - 0.3f; // slight upward bias
		pLife[i] = 1f;
		pHue[i]  = hue;
	}

	private void updateAndDrawParticles(Graphics2D g2) {
		for (int i = 0; i < MAX_PARTICLES; i++) {
			float life = pLife[i];
			if (life <= 0f) continue;

			pX[i]  += pVX[i];
			pY[i]  += pVY[i];
			pVY[i] += 0.02f;  // gentle gravity
			pVX[i] *= 0.99f;
			life   -= 0.02f;
			pLife[i] = life;
			if (life <= 0f) continue;

			float sz = 1.5f + life * 2.5f;
			g2.setColor(hsba(pHue[i], 0.35f, 1f, clamp255((int) (life * 220))));
			g2.fillOval(Math.round(pX[i] - sz / 2f), Math.round(pY[i] - sz / 2f),
					Math.round(sz), Math.round(sz));
		}
	}

	// ---- Helpers ----

	private static void drawVignette(Graphics2D g2, int w, int h) {
		int vw = Math.min(32, w / 6);
		if (vw <= 0) return;
		g2.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 90), vw, 0, new Color(0, 0, 0, 0)));
		g2.fillRect(0, 0, vw, h);
		g2.setPaint(new GradientPaint(w - vw, 0, new Color(0, 0, 0, 0), w, 0, new Color(0, 0, 0, 90)));
		g2.fillRect(w - vw, 0, vw, h);
	}

	private static Color hsba(float h, float s, float b, int a) {
		h = ((h % 1f) + 1f) % 1f;
		Color c = Color.getHSBColor(h, clamp(s, 0f, 1f), clamp(b, 0f, 1f));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp255(a));
	}

	/** In-place iterative radix-2 Cooley-Tukey FFT ({@code n} must be a power of two). */
	private static void fft(float[] re, float[] im, int n) {
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

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int clamp255(int a) {
		return Math.max(0, Math.min(255, a));
	}
}
