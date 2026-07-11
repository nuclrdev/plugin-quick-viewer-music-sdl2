package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * "Vectrex" — a vector-monitor tribute: no pixels, no raster, just glowing
 * phosphor strokes on black.
 * <p>
 * Rendering is done into a persistence buffer that fades a little every
 * frame, so every stroke leaves genuine phosphor trails. Each line is drawn
 * in three passes (wide soft halo, tight glow, thin hot core) with bright
 * endpoint dots — the tell-tale spots where a real vector beam lingered — and
 * every vertex carries a sub-pixel analog wobble so nothing is ever
 * digitally still.
 * <p>
 * The scene: a Tempest-style sixteen-lane web whose rim rides the frequency
 * bands — each lane bulges and brightens with its own slice of the spectrum —
 * around a slowly tumbling wireframe icosahedron that swells with the bass
 * and spins faster as the energy climbs. Beats fire the superzapper: the
 * whole web flashes white-hot and vector sparks scatter outward. A new track
 * zooms the web in from deep space under a {@code NEW WAVE} banner; silence
 * drops the machine into attract mode — {@code INSERT COIN}.
 */
final class VectrexVisualizer {

	// ---- Phosphor persistence buffer ----
	private BufferedImage trail;
	private int trailW = -1, trailH = -1;

	// ---- Colours ----
	private static final Color WEB_GLOW   = new Color(0x40, 0x80, 0xFF);
	private static final Color WEB_CORE   = new Color(0xC8, 0xE8, 0xFF);
	private static final Color ICO_GLOW   = new Color(0xFF, 0xA0, 0x30);
	private static final Color ICO_CORE   = new Color(0xFF, 0xE8, 0xC0);
	private static final Color TEXT_DIM   = new Color(0x8C, 0xB4, 0xE0);

	// ---- FFT / analysis ----
	private static final int FFT_SIZE    = 2048;
	private static final int SAMPLE_RATE = 44100;
	private static final int NUM_BANDS   = 16;

	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   bandBin  = new int[NUM_BANDS + 1];
	private final float[] bands    = new float[NUM_BANDS];

	private float bass, energy;
	private float bassAvg;
	private float autoGain = 8f;
	private int   framesSinceBeat = 999;
	private int   zapFrames = 0;
	private int   beats = 0;

	// ---- Icosahedron (12 vertices, 30 edges) ----
	private final float[][] icoV = buildIcoVertices();
	private final int[][]   icoE = buildIcoEdges(icoV);
	private final float[]   projX = new float[12];
	private final float[]   projY = new float[12];
	private float rotA = 0f, rotB = 0f;

	// ---- Sparks ----
	private static final int MAX_SPARKS = 40;
	private final float[] spX = new float[MAX_SPARKS];
	private final float[] spY = new float[MAX_SPARKS];
	private final float[] spVX = new float[MAX_SPARKS];
	private final float[] spVY = new float[MAX_SPARKS];
	private final float[] spLife = new float[MAX_SPARKS];
	private int spNext = 0;

	// ---- State ----
	private int   zoomT = 0;   // >0: the new-wave fly-in is running
	private int   idleFrames = 0;
	private int   frame = 0;
	private final Random rnd = new Random(0x0EC7);

	VectrexVisualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		float logMin = (float) Math.log(45), logMax = (float) Math.log(12000);
		for (int b = 0; b <= NUM_BANDS; b++) {
			float freq = (float) Math.exp(logMin + (logMax - logMin) * b / NUM_BANDS);
			bandBin[b] = clampI(Math.round(freq * FFT_SIZE / SAMPLE_RATE), 1, FFT_SIZE / 2);
		}
		for (int b = 1; b <= NUM_BANDS; b++) {
			if (bandBin[b] <= bandBin[b - 1]) bandBin[b] = bandBin[b - 1] + 1;
		}
	}

	private static float[][] buildIcoVertices() {
		float p = (float) ((1 + Math.sqrt(5)) / 2);
		float[][] v = {
			{ -1,  p, 0 }, { 1,  p, 0 }, { -1, -p, 0 }, { 1, -p, 0 },
			{ 0, -1,  p }, { 0, 1,  p }, { 0, -1, -p }, { 0, 1, -p },
			{  p, 0, -1 }, { p, 0, 1 }, { -p, 0, -1 }, { -p, 0, 1 },
		};
		for (float[] a : v) {
			float len = (float) Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
			a[0] /= len; a[1] /= len; a[2] /= len;
		}
		return v;
	}

	/** Edges = every vertex pair at the icosahedron's minimal distance. */
	private static int[][] buildIcoEdges(float[][] v) {
		java.util.List<int[]> e = new java.util.ArrayList<>();
		for (int i = 0; i < v.length; i++) {
			for (int j = i + 1; j < v.length; j++) {
				float dx = v[i][0] - v[j][0], dy = v[i][1] - v[j][1], dz = v[i][2] - v[j][2];
				if (dx * dx + dy * dy + dz * dz < 1.2f) {
					e.add(new int[]{ i, j });
				}
			}
		}
		return e.toArray(new int[0][]);
	}

	/** New tune: fly the web in from deep space. */
	void setTrackTitle(String title) {
		zoomT = 60;
		idleFrames = 0;
	}

	// ---- Render entry ----

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {
		frame = frameCount;
		int samples = ring != null ? ring.snapshot(snapshot, FFT_SIZE) : 0;
		boolean hasAudio = samples >= 512;

		if (hasAudio) {
			analyze(samples);
			idleFrames = 0;
		} else {
			for (int b = 0; b < NUM_BANDS; b++) bands[b] *= 0.94f;
			bass   += (0f - bass)   * 0.06f;
			energy += (0f - energy) * 0.06f;
			idleFrames++;
		}
		if (zapFrames > 0) zapFrames--;
		if (zoomT > 0) zoomT--;

		ensureTrail(w, h);
		Graphics2D g = trail.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

			// Phosphor decay.
			g.setColor(new Color(0, 0, 0, 60));
			g.fillRect(0, 0, w, h);

			float zoom = 1f + zoomT / 60f * 1.8f;
			drawWeb(g, w, h, zoom);
			drawIcosahedron(g, w, h);
			updateAndDrawSparks(g);
			drawHudText(g, w, h);
		} finally {
			g.dispose();
		}

		g2.setColor(Color.BLACK);
		g2.fillRect(0, 0, w, h);
		g2.drawImage(trail, 0, 0, null);
	}

	private void ensureTrail(int w, int h) {
		if (trail == null || trailW != w || trailH != h) {
			trail = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
			trailW = w;
			trailH = h;
		}
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

		float rawMax = 1e-6f;
		float[] raw = new float[NUM_BANDS];
		for (int b = 0; b < NUM_BANDS; b++) {
			float sum = 0f;
			for (int k = bandBin[b]; k < bandBin[b + 1]; k++) {
				sum += re[k] * re[k] + im[k] * im[k];
			}
			raw[b] = (float) Math.sqrt(sum / (bandBin[b + 1] - bandBin[b])) / FFT_SIZE * 24f;
			if (raw[b] > rawMax) rawMax = raw[b];
		}
		float targetGain = clampF(0.8f / rawMax, 1f, 400f);
		autoGain += (targetGain - autoGain) * (targetGain < autoGain ? 0.15f : 0.02f);

		float sum = 0f;
		for (int b = 0; b < NUM_BANDS; b++) {
			float n = clampF(raw[b] * autoGain, 0f, 1f);
			bands[b] += (n - bands[b]) * (n > bands[b] ? 0.55f : 0.10f);
			sum += bands[b];
		}
		float nb = clampF((bands[0] + bands[1] + bands[2]) * 0.45f, 0f, 1f);
		bass   += (nb - bass) * (nb > bass ? 0.5f : 0.12f);
		energy += (sum / NUM_BANDS - energy) * 0.15f;

		bassAvg += (bass - bassAvg) * 0.02f;
		framesSinceBeat++;
		if (framesSinceBeat > 14 && bass > 0.14f && bass > bassAvg * 1.45f + 0.03f) {
			onBeat();
		}
	}

	private void onBeat() {
		framesSinceBeat = 0;
		zapFrames = 3;
		beats++;
		// Superzapper sparks fly off the rim.
		for (int n = 0; n < 16; n++) {
			int i = spNext;
			spNext = (spNext + 1) % MAX_SPARKS;
			float ang = rnd.nextFloat() * (float) (Math.PI * 2);
			float spd = 2f + rnd.nextFloat() * 4f;
			spX[i] = 0.5f;   // stored as fractions of panel size
			spY[i] = 0.52f;
			spVX[i] = (float) Math.cos(ang) * spd * 0.004f;
			spVY[i] = (float) Math.sin(ang) * spd * 0.004f;
			spLife[i] = 1f;
		}
	}

	// ---- Vector drawing ----

	/** A line the way a vector monitor drew one: halo, glow, hot core, bright ends. */
	private void vLine(Graphics2D g, float x1, float y1, float x2, float y2,
	                   Color glow, Color core, float bright) {
		// Analog wobble: the beam never sat perfectly still.
		x1 += wobble(); y1 += wobble();
		x2 += wobble(); y2 += wobble();
		int a1 = clampI(Math.round(45 * bright), 0, 255);
		int a2 = clampI(Math.round(120 * bright), 0, 255);
		int a3 = clampI(Math.round(255 * bright), 0, 255);

		g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), a1));
		g.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2));

		g.setColor(new Color(glow.getRed(), glow.getGreen(), glow.getBlue(), a2));
		g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2));

		g.setColor(new Color(core.getRed(), core.getGreen(), core.getBlue(), a3));
		g.setStroke(new BasicStroke(1f));
		g.drawLine(Math.round(x1), Math.round(y1), Math.round(x2), Math.round(y2));

		// Beam-dwell endpoint dots.
		g.setColor(new Color(core.getRed(), core.getGreen(), core.getBlue(), clampI(Math.round(200 * bright), 0, 255)));
		g.fillOval(Math.round(x1) - 1, Math.round(y1) - 1, 3, 3);
		g.fillOval(Math.round(x2) - 1, Math.round(y2) - 1, 3, 3);
	}

	private float wobble() {
		return (rnd.nextFloat() - 0.5f) * 1.4f;
	}

	/** The sixteen-lane web; each rim lane rides its own frequency band. */
	private void drawWeb(Graphics2D g, int w, int h, float zoom) {
		float cx = w * 0.5f, cy = h * 0.52f;
		float base = Math.min(w, h) * 0.42f / zoom;
		boolean zap = zapFrames > 0;

		float[] vx = new float[NUM_BANDS];
		float[] vy = new float[NUM_BANDS];
		for (int i = 0; i < NUM_BANDS; i++) {
			float ang = (float) (i * 2 * Math.PI / NUM_BANDS) + frame * 0.0015f;
			float r = base * (1f + bands[i] * 0.28f);
			vx[i] = cx + (float) Math.cos(ang) * r;
			vy[i] = cy + (float) Math.sin(ang) * r * 0.92f;
		}
		for (int i = 0; i < NUM_BANDS; i++) {
			float bright = zap ? 1f : 0.30f + bands[i] * 0.7f;
			Color core = zap ? Color.WHITE : WEB_CORE;
			// Spoke into the vanishing centre, then the rim segment.
			vLine(g, cx, cy, vx[i], vy[i], WEB_GLOW, core, zap ? 1f : 0.22f + bands[i] * 0.5f);
			int j = (i + 1) % NUM_BANDS;
			vLine(g, vx[i], vy[i], vx[j], vy[j], WEB_GLOW, core, bright);
		}
	}

	private void drawIcosahedron(Graphics2D g, int w, int h) {
		rotA += 0.006f + energy * 0.028f;
		rotB += 0.0043f + energy * 0.017f;
		float cx = w * 0.5f, cy = h * 0.52f;
		float scale = Math.min(w, h) * (0.14f + bass * 0.07f);
		float ca = (float) Math.cos(rotA), sa = (float) Math.sin(rotA);
		float cb = (float) Math.cos(rotB), sb = (float) Math.sin(rotB);

		for (int i = 0; i < icoV.length; i++) {
			float x = icoV[i][0], y = icoV[i][1], z = icoV[i][2];
			float x1 = x * ca - z * sa;
			float z1 = x * sa + z * ca;
			float y1 = y * cb - z1 * sb;
			float z2 = y * sb + z1 * cb;
			float persp = 2.4f / (2.4f + z2);
			projX[i] = cx + x1 * scale * persp;
			projY[i] = cy + y1 * scale * persp;
		}
		float bright = 0.35f + energy * 0.65f;
		for (int[] e : icoE) {
			vLine(g, projX[e[0]], projY[e[0]], projX[e[1]], projY[e[1]], ICO_GLOW, ICO_CORE, bright);
		}
	}

	private void updateAndDrawSparks(Graphics2D g) {
		for (int i = 0; i < MAX_SPARKS; i++) {
			if (spLife[i] <= 0f) continue;
			spX[i] += spVX[i];
			spY[i] += spVY[i];
			spLife[i] -= 0.04f;
			if (spLife[i] <= 0f) continue;
			float x = spX[i] * trailW, y = spY[i] * trailH;
			float tx = x - spVX[i] * trailW * 2.5f, ty = y - spVY[i] * trailH * 2.5f;
			vLine(g, tx, ty, x, y, WEB_GLOW, Color.WHITE, spLife[i]);
		}
	}

	private void drawHudText(Graphics2D g, int w, int h) {
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(11, h / 30)));
		g.setColor(TEXT_DIM);
		g.drawString(String.format("SCORE %06d", beats * 150 % 1000000), 12, 20);
		String hi = "HI 65535";
		g.drawString(hi, w - g.getFontMetrics().stringWidth(hi) - 12, 20);

		if (zoomT > 20) {
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(16, h / 12)));
			g.setColor(Color.WHITE);
			String s = "NEW WAVE";
			g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, h / 3);
		}
		if (idleFrames > 240 && (frame / 24) % 2 == 0) {
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.max(14, h / 16)));
			g.setColor(Color.WHITE);
			String s = "INSERT COIN";
			g.drawString(s, (w - g.getFontMetrics().stringWidth(s)) / 2, h / 2);
			g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, Math.max(10, h / 34)));
			g.setColor(TEXT_DIM);
			String c = "(c) 1982 NUCLR VECTOR CORP";
			g.drawString(c, (w - g.getFontMetrics().stringWidth(c)) / 2, h - 16);
		}
	}

	// ---- Helpers ----

	private static int clampI(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clampF(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
