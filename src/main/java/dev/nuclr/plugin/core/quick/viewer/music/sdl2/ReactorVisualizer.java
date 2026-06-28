package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

import javax.imageio.ImageIO;

import sdl2.AudioRingBuffer;

/**
 * "Reactor Core" — a nuclear-themed audio visualizer.
 * <p>
 * A radioactive reactor core pulses at the centre of the panel, ringed by a
 * symmetric FFT-driven corona (a spiky energy halo). The classic radiation
 * trefoil (&#9762;) rotates slowly behind it, three electrons orbit the nucleus
 * on tilted elliptical paths with glowing trails, and every detected beat fires
 * a detonation shockwave ring plus a burst of fission sparks. Palette: neon
 * radioactive green, Cherenkov blue, and hazard amber going white-hot on peaks.
 * <p>
 * Self-contained like the other visualizers: it owns its FFT working buffers,
 * smoothing state, and fixed-size shockwave/particle pools, all pre-allocated.
 * Only the Swing EDT touches it (it reads the {@link AudioRingBuffer} snapshot).
 */
final class ReactorVisualizer {

	// ---- FFT / analysis ----
	private static final int   FFT_SIZE    = 2048;
	private static final int   NUM_BARS    = 64;
	private static final int   NUM_SPOKES  = 128; // corona rays around the circle (mirrored)
	private static final int   SAMPLE_RATE = 44100;
	private static final float MIN_FREQ    = 35f;
	private static final float MAX_FREQ    = 16000f;

	// ---- Dynamics ----
	private static final float BAR_ATTACK = 0.55f;
	private static final float BAR_DECAY  = 0.13f;

	// ---- Pools ----
	private static final int MAX_WAVES     = 7;
	private static final int MAX_PARTICLES = 200;
	private static final int ORBITS        = 3;

	// ---- Palette ----
	private static final Color RAD_GREEN = new Color(80, 255, 70);
	private static final Color HAZ_AMBER = new Color(255, 200, 70);
	private static final Color HOT_WHITE = new Color(255, 255, 235);

	// ---- Backdrop image (shared, loaded once) ----
	private static final String BG_RESOURCE = "/effects/reactor-bg.png";
	private static volatile BufferedImage bgSource;
	private static volatile boolean bgLoaded;

	// ---- FFT buffers ----
	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   barBin   = new int[NUM_BARS + 1];

	// ---- Band state ----
	private final float[] bandTarget  = new float[NUM_BARS];
	private final float[] bandDisplay = new float[NUM_BARS];
	private float autoGain = 1f;
	private float energy   = 0f; // smoothed overall level
	private float bass     = 0f; // smoothed low-band level

	// ---- Beat detection ----
	private float energyAvg   = 0f;
	private int   beatCooldown = 0;
	private float coreFlash   = 0f;

	// ---- Animation phases ----
	private float trefoilAngle = 0f;
	private final float[] orbitSpin  = new float[ORBITS];
	private final float[] electron   = new float[ORBITS];
	private static final float[] ORBIT_TILT  = { 0f, 1.05f, 2.10f };           // ~0/60/120 degrees
	private static final float[] ORBIT_SPIN  = { 0.006f, -0.009f, 0.012f };
	private static final float[] ELEC_SPEED  = { 0.075f, 0.060f, 0.090f };

	// ---- Shockwave rings ----
	private final float[] swRadius = new float[MAX_WAVES];
	private final float[] swLife   = new float[MAX_WAVES];
	private int swNext = 0;

	// ---- Fission particles ----
	private final float[] pX    = new float[MAX_PARTICLES];
	private final float[] pY    = new float[MAX_PARTICLES];
	private final float[] pVX   = new float[MAX_PARTICLES];
	private final float[] pVY   = new float[MAX_PARTICLES];
	private final float[] pLife = new float[MAX_PARTICLES];
	private final float[] pAmber = new float[MAX_PARTICLES]; // 0=green .. 1=amber
	private int pNext = 0;

	private final Area trefoilShape = buildTrefoil();
	private final Random rnd = new Random(92235L); // U-235

	// Backdrop pre-scaled to the current panel size (re-rendered only on resize).
	private BufferedImage bgScaled;
	private int bgScaledW = -1;
	private int bgScaledH = -1;

	ReactorVisualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		buildBarBins();
	}

	private void buildBarBins() {
		float logMin = (float) Math.log(MIN_FREQ);
		float logMax = (float) Math.log(MAX_FREQ);
		int   nyquist = FFT_SIZE / 2;
		for (int b = 0; b <= NUM_BARS; b++) {
			float t    = (float) b / NUM_BARS;
			float freq = (float) Math.exp(logMin + (logMax - logMin) * t);
			barBin[b] = Math.max(1, Math.min(nyquist, Math.round(freq * FFT_SIZE / SAMPLE_RATE)));
		}
		for (int b = 1; b <= NUM_BARS; b++) {
			if (barBin[b] <= barBin[b - 1]) {
				barBin[b] = Math.min(nyquist, barBin[b - 1] + 1);
			}
		}
	}

	// ---- Render entry ----

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {
		int samples = ring != null ? ring.snapshot(snapshot, FFT_SIZE) : 0;
		boolean hasAudio = samples >= NUM_BARS * 2;

		if (hasAudio) {
			analyze(samples);
		} else {
			for (int b = 0; b < NUM_BARS; b++) bandTarget[b] = 0f;
			energy += (0f - energy) * 0.06f;
			bass   += (0f - bass) * 0.06f;
		}

		advance(hasAudio, frameCount);
		drawScene(g2, w, h, frameCount, hasAudio);
	}

	// ---- Analysis ----

	private void analyze(int samples) {
		int pad = FFT_SIZE - samples;
		for (int i = 0; i < FFT_SIZE; i++) {
			float s = i >= pad ? snapshot[i - pad] : 0f;
			re[i] = s * hann[i];
			im[i] = 0f;
		}
		Fft.transform(re, im, FFT_SIZE);

		float maxMag = 1e-4f;
		for (int b = 0; b < NUM_BARS; b++) {
			int   lo = barBin[b];
			int   hi = barBin[b + 1];
			float power = 0f;
			for (int k = lo; k < hi; k++) {
				float p = re[k] * re[k] + im[k] * im[k];
				if (p > power) power = p;
			}
			float mag  = (float) Math.sqrt(power) / FFT_SIZE;
			float tilt = 1f + 1.5f * ((float) b / NUM_BARS);
			mag *= tilt;
			bandTarget[b] = mag;
			if (mag > maxMag) maxMag = mag;
		}

		float targetGain = clamp(0.85f / maxMag, 0.5f, 60f);
		autoGain += (targetGain - autoGain) * (targetGain < autoGain ? 0.20f : 0.05f);

		float sum = 0f, bassSum = 0f;
		for (int b = 0; b < NUM_BARS; b++) {
			float level = clamp((float) Math.pow(bandTarget[b] * autoGain, 0.72), 0f, 1.3f);
			bandTarget[b] = level;
			sum += level;
			if (b < 6) bassSum += level;
		}
		energy += (sum / NUM_BARS - energy) * 0.18f;
		bass   += (bassSum / 6f - bass) * 0.25f;
	}

	private void advance(boolean hasAudio, int frameCount) {
		for (int b = 0; b < NUM_BARS; b++) {
			float t = bandTarget[b];
			float d = bandDisplay[b];
			d += (t - d) * (t > d ? BAR_ATTACK : BAR_DECAY);
			bandDisplay[b] = d;
		}

		// Spin the atom; electrons accelerate with the energy.
		trefoilAngle += 0.004f + 0.02f * energy;
		for (int k = 0; k < ORBITS; k++) {
			orbitSpin[k] += ORBIT_SPIN[k];
			electron[k]  += ELEC_SPEED[k] * (1f + 1.6f * energy);
		}

		// Beat detection -> detonation + sparks + core flash.
		energyAvg += (energy - energyAvg) * 0.05f;
		if (beatCooldown > 0) beatCooldown--;
		if (hasAudio && energy > energyAvg * 1.35f + 0.05f && beatCooldown == 0) {
			spawnShockwave();
			coreFlash = 1f;
			beatCooldown = 8;
		}
		coreFlash *= 0.90f;
	}

	// ---- Scene ----

	private void drawScene(Graphics2D g2, int w, int h, int frameCount, boolean hasAudio) {
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		float cx    = w * 0.5f;
		float cy    = h * 0.5f;
		float scale = Math.min(w, h);
		float idle  = 0.10f + 0.06f * (float) Math.sin(frameCount * 0.05);
		float pulse = Math.max(clamp(energy, 0f, 1f), idle);

		float coreBase   = scale * 0.075f;
		float coreR      = coreBase * (0.75f + 0.85f * pulse) + coreFlash * coreBase * 0.7f;
		float coronaMax  = scale * 0.30f;

		drawBackdrop(g2, w, h);
		drawAmbient(g2, cx, cy, scale, pulse);
		drawTrefoil(g2, cx, cy, scale * 0.34f, trefoilAngle, (int) (22 + 46 * pulse));
		drawShockwaves(g2, cx, cy, scale);
		drawCore(g2, cx, cy, coreR, pulse);
		drawCorona(g2, cx, cy, coreR, coronaMax);
		drawAtom(g2, cx, cy, scale, pulse);
		updateAndDrawParticles(g2);

		if (hasAudio) emitSparks(cx, cy, coreR, scale);
	}

	/** Draws the baked nuclear backdrop, scaled to cover the panel (cached per size). */
	private void drawBackdrop(Graphics2D g2, int w, int h) {
		BufferedImage src = backgroundSource();
		if (src == null) return;
		if (bgScaled == null || bgScaledW != w || bgScaledH != h) {
			bgScaled = coverScale(src, w, h);
			bgScaledW = w;
			bgScaledH = h;
		}
		g2.drawImage(bgScaled, 0, 0, null);
	}

	/** Lazily loads the shipped backdrop PNG; falls back to procedural generation if absent. */
	private static BufferedImage backgroundSource() {
		if (!bgLoaded) {
			synchronized (ReactorVisualizer.class) {
				if (!bgLoaded) {
					bgLoaded = true;
					try (InputStream in = ReactorVisualizer.class.getResourceAsStream(BG_RESOURCE)) {
						if (in != null) bgSource = ImageIO.read(in);
					} catch (IOException ignored) {
						// fall through to procedural generation
					}
					if (bgSource == null) {
						bgSource = ReactorBackground.render(1440, 900);
					}
				}
			}
		}
		return bgSource;
	}

	/** Scale {@code src} to fully cover {@code w}x{@code h}, centred (excess cropped). */
	private static BufferedImage coverScale(BufferedImage src, int w, int h) {
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = out.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
			int dw = Math.round(src.getWidth() * scale);
			int dh = Math.round(src.getHeight() * scale);
			g.drawImage(src, (w - dw) / 2, (h - dh) / 2, dw, dh, null);
		} finally {
			g.dispose();
		}
		return out;
	}

	/** Soft radioactive haze filling the panel, breathing with the music. */
	private void drawAmbient(Graphics2D g2, float cx, float cy, float scale, float pulse) {
		float r = scale * 0.75f;
		if (r < 1f) return;
		g2.setPaint(new RadialGradientPaint(cx, cy, r,
				new float[]{0f, 0.55f, 1f},
				new Color[]{
					new Color(40, 120, 40, (int) (28 + 40 * pulse)),
					new Color(20, 70, 30, 16),
					new Color(0, 0, 0, 0)
				}));
		g2.fillRect(0, 0, Math.round(cx * 2), Math.round(cy * 2));
	}

	private void drawTrefoil(Graphics2D g2, float cx, float cy, float radius, float angle, int alpha) {
		Graphics2D tg = (Graphics2D) g2.create();
		try {
			tg.translate(cx, cy);
			tg.rotate(angle);
			tg.scale(radius, radius);
			tg.setColor(new Color(HAZ_AMBER.getRed(), HAZ_AMBER.getGreen(), HAZ_AMBER.getBlue(), clamp255(alpha)));
			tg.fill(trefoilShape);
		} finally {
			tg.dispose();
		}
	}

	private void drawShockwaves(Graphics2D g2, float cx, float cy, float scale) {
		for (int i = 0; i < MAX_WAVES; i++) {
			float life = swLife[i];
			if (life <= 0f) continue;
			swRadius[i] += scale * 0.016f;
			swLife[i]    = life - 0.02f;

			float r = swRadius[i];
			int   a = clamp255((int) (life * 170));
			g2.setStroke(new BasicStroke(1.5f + 4f * life, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.setColor(new Color(HAZ_AMBER.getRed(), HAZ_AMBER.getGreen(), HAZ_AMBER.getBlue(), a));
			g2.drawOval(Math.round(cx - r), Math.round(cy - r), Math.round(r * 2), Math.round(r * 2));
		}
	}

	private void drawCore(Graphics2D g2, float cx, float cy, float coreR, float pulse) {
		if (coreR < 1f) return;
		float glowR = coreR * 2.6f;
		Color mid = lerpColor(RAD_GREEN, HAZ_AMBER, clamp(pulse, 0f, 1f));

		g2.setPaint(new RadialGradientPaint(cx, cy, glowR,
				new float[]{0f, 0.22f, 0.55f, 1f},
				new Color[]{
					new Color(HOT_WHITE.getRed(), HOT_WHITE.getGreen(), HOT_WHITE.getBlue(), 235),
					new Color(mid.getRed(), mid.getGreen(), mid.getBlue(), 205),
					new Color(RAD_GREEN.getRed(), RAD_GREEN.getGreen(), RAD_GREEN.getBlue(), 110),
					new Color(10, 40, 10, 0)
				}));
		g2.fillOval(Math.round(cx - glowR), Math.round(cy - glowR), Math.round(glowR * 2), Math.round(glowR * 2));

		// Containment ring + white-hot centre.
		g2.setStroke(new BasicStroke(1.5f + 1.5f * pulse));
		g2.setColor(new Color(HAZ_AMBER.getRed(), HAZ_AMBER.getGreen(), HAZ_AMBER.getBlue(), 150));
		g2.drawOval(Math.round(cx - coreR), Math.round(cy - coreR), Math.round(coreR * 2), Math.round(coreR * 2));

		float hot = coreR * 0.5f;
		g2.setColor(new Color(255, 255, 255, (int) (180 + 60 * pulse)));
		g2.fillOval(Math.round(cx - hot), Math.round(cy - hot), Math.round(hot * 2), Math.round(hot * 2));
	}

	/** Symmetric FFT corona: rays shooting outward from the core, mirrored across the vertical axis. */
	private void drawCorona(Graphics2D g2, float cx, float cy, float coreR, float coronaMax) {
		int half = NUM_SPOKES / 2;
		for (int i = 0; i < NUM_SPOKES; i++) {
			int   band = i < half ? i : NUM_SPOKES - 1 - i; // left/right mirror
			float len  = bandDisplay[band] * coronaMax;
			if (len < 1.5f) continue;

			double a   = -Math.PI / 2 + i * (2 * Math.PI / NUM_SPOKES);
			float  cos = (float) Math.cos(a);
			float  sin = (float) Math.sin(a);
			float  x0  = cx + cos * coreR;
			float  y0  = cy + sin * coreR;
			float  x1  = cx + cos * (coreR + len);
			float  y1  = cy + sin * (coreR + len);

			float t = clamp(len / coronaMax, 0f, 1f);
			// glow pass
			g2.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.setColor(nuclearColor(t, 60));
			g2.drawLine(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1));
			// core ray
			g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.setColor(nuclearColor(t, 235));
			g2.drawLine(Math.round(x0), Math.round(y0), Math.round(x1), Math.round(y1));
		}
	}

	/** Three electrons orbiting on tilted elliptical paths, each with a glowing trail. */
	private void drawAtom(Graphics2D g2, float cx, float cy, float scale, float pulse) {
		float a = scale * 0.20f * (1f + 0.10f * pulse);
		float b = scale * 0.075f * (1f + 0.10f * pulse);

		for (int k = 0; k < ORBITS; k++) {
			Graphics2D og = (Graphics2D) g2.create();
			try {
				og.translate(cx, cy);
				og.rotate(ORBIT_TILT[k] + orbitSpin[k]);

				// Orbit ring (Cherenkov blue).
				og.setStroke(new BasicStroke(1.2f));
				og.setColor(new Color(90, 200, 255, (int) (45 + 40 * pulse)));
				og.draw(new Ellipse2D.Float(-a, -b, a * 2, b * 2));

				// Trailing ghosts then the bright electron head.
				for (int tjump = 8; tjump >= 0; tjump--) {
					float phase = electron[k] - tjump * 0.10f;
					float ex = a * (float) Math.cos(phase);
					float ey = b * (float) Math.sin(phase);
					float f  = 1f - tjump / 9f;
					float sz = 2f + f * 5f;
					int   al = clamp255((int) (f * f * 230));
					og.setColor(tjump == 0
							? new Color(220, 245, 255, 255)
							: new Color(120, 210, 255, al));
					og.fillOval(Math.round(ex - sz / 2), Math.round(ey - sz / 2), Math.round(sz), Math.round(sz));
				}
			} finally {
				og.dispose();
			}
		}
	}

	// ---- Shockwaves / particles ----

	private void spawnShockwave() {
		int i = swNext;
		swNext = (swNext + 1) % MAX_WAVES;
		swRadius[i] = 0f;
		swLife[i]   = 1f;
	}

	private void emitSparks(float cx, float cy, float coreR, float scale) {
		int count = (int) (2 + 14 * clamp(coreFlash, 0f, 1f));
		for (int n = 0; n < count; n++) {
			float ang = rnd.nextFloat() * (float) (Math.PI * 2);
			float spd = scale * (0.004f + rnd.nextFloat() * 0.016f);
			int i = pNext;
			pNext = (pNext + 1) % MAX_PARTICLES;
			pX[i]    = cx + (float) Math.cos(ang) * coreR;
			pY[i]    = cy + (float) Math.sin(ang) * coreR;
			pVX[i]   = (float) Math.cos(ang) * spd;
			pVY[i]   = (float) Math.sin(ang) * spd;
			pLife[i] = 1f;
			pAmber[i] = rnd.nextFloat();
		}
	}

	private void updateAndDrawParticles(Graphics2D g2) {
		for (int i = 0; i < MAX_PARTICLES; i++) {
			float life = pLife[i];
			if (life <= 0f) continue;
			pX[i]  += pVX[i];
			pY[i]  += pVY[i];
			pVX[i] *= 0.965f;
			pVY[i] *= 0.965f;
			life   -= 0.018f;
			pLife[i] = life;
			if (life <= 0f) continue;

			float sz = 1.5f + life * 3f;
			Color c  = lerpColor(RAD_GREEN, HAZ_AMBER, pAmber[i]);
			g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp255((int) (life * 230))));
			g2.fillOval(Math.round(pX[i] - sz / 2), Math.round(pY[i] - sz / 2), Math.round(sz), Math.round(sz));
		}
	}

	// ---- Helpers ----

	/** Green -> amber -> white-hot colour ramp driven by ray length {@code t} (0..1). */
	private static Color nuclearColor(float t, int alpha) {
		float hue = 0.33f - 0.20f * Math.min(t, 1f);      // green -> amber
		float sat = t < 0.6f ? 1f : 1f - (t - 0.6f) / 0.4f * 0.85f; // desaturate to white at the tip
		Color c = Color.getHSBColor(hue, clamp(sat, 0f, 1f), 1f);
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp255(alpha));
	}

	private static Color lerpColor(Color a, Color b, float t) {
		t = clamp(t, 0f, 1f);
		return new Color(
				Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
				Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
				Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
	}

	/** Unit-radius radiation trefoil centred at the origin (central disc + three 60&deg; blades). */
	static Area buildTrefoil() {
		float inner = 0.36f;
		float outer = 1.0f;
		float dot   = 0.18f;
		Area area = new Area(new Ellipse2D.Float(-dot, -dot, dot * 2, dot * 2));
		for (int k = 0; k < 3; k++) {
			float start = 90f + k * 120f - 30f; // 60-degree blade every 120 degrees
			Area blade = new Area(new Arc2D.Float(-outer, -outer, outer * 2, outer * 2, start, 60f, Arc2D.PIE));
			blade.subtract(new Area(new Ellipse2D.Float(-inner, -inner, inner * 2, inner * 2)));
			area.add(blade);
		}
		return area;
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int clamp255(int a) {
		return Math.max(0, Math.min(255, a));
	}
}
