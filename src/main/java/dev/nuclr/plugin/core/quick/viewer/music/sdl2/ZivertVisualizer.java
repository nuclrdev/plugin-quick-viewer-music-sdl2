package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.QuadCurve2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * "Zivert" — a neon retrowave tribute to the Russian synth-pop chanteuse:
 * an outrun sunset that never ends.
 * <p>
 * The scene is a full 80s/90s VHS fever dream: a scanline sun sinking into a
 * scrolling perspective grid, a chrome {@code ZIVERT} wordmark hanging in the
 * sky, an FFT-driven city-skyline equalizer along the horizon, palm
 * silhouettes swaying at the edges, and — because she flew before she sang —
 * a lone jet crossing the sky with blinking nav lights and a fading contrail.
 * A roadside neon billboard planted on the grid carries a hand-pixelled
 * portrait homage — platinum bob, wrap-around visor with the sunset mirrored
 * in the glass, gold hoops, iridescent puffer — over a caption strip that
 * cycles through the discography.
 * <p>
 * Everything breathes with the music: bass swells the sun and speeds the
 * grid, each frequency band raises its own skyline tower, and beats flash the
 * grid, split the wordmark into chromatic ghosts and occasionally knock the
 * VHS tracking loose. The frame is rendered into an offscreen buffer so
 * glitch bands can shear real pixels; the VCR's own OSD ({@code ► PLAY},
 * tape counter, track title) is drawn on top and never glitches — just like
 * the real thing.
 */
final class ZivertVisualizer {

	// ---- Layout ----
	private static final float HORIZON_FRAC = 0.60f;

	// ---- Palette ----
	private static final float[] SKY_STOPS = { 0f, 0.45f, 0.78f, 1f };
	private static final Color[] SKY_COLORS = {
		new Color(0x06, 0x04, 0x16),
		new Color(0x1E, 0x0E, 0x4E),
		new Color(0x71, 0x17, 0x6E),
		new Color(0xFF, 0x5E, 0x6E),
	};
	private static final float[] SUN_STOPS = { 0f, 0.35f, 0.70f, 1f };
	private static final Color[] SUN_COLORS = {
		new Color(0xFF, 0xF3, 0xA6),
		new Color(0xFF, 0xC8, 0x5C),
		new Color(0xFF, 0x5E, 0x7E),
		new Color(0xF0, 0x26, 0x9B),
	};
	private static final Color FLOOR_TOP = new Color(0x14, 0x04, 0x2A);
	private static final Color FLOOR_BOT = new Color(0x05, 0x01, 0x10);
	private static final Color GRID_H    = new Color(255, 45, 210);
	private static final Color GRID_V    = new Color(90, 220, 255);
	private static final Color PALM      = new Color(8, 3, 20);
	private static final Color STAR      = new Color(200, 215, 255);
	private static final Color OSD_INK   = new Color(235, 242, 255);
	private static final Color SUBTITLE  = new Color(140, 235, 255);
	private static final Color TOWER     = new Color(13, 6, 32, 242);

	// ---- Chrome wordmark gradient (sky reflection / horizon cut / neon base) ----
	private static final float[] CHROME_STOPS = { 0f, 0.42f, 0.50f, 0.58f, 1f };
	private static final Color[] CHROME_COLORS = {
		new Color(0xF4, 0xFB, 0xFF),
		new Color(0x8F, 0xD0, 0xFF),
		new Color(0x10, 0x21, 0x4E),
		new Color(0x7A, 0x2F, 0xB8),
		new Color(0xFF, 0x7B, 0xE0),
	};

	// ---- Billboard portrait (hand-pixelled homage: platinum bob, wrap-around
	// visor with the sunset reflected in the glass, gold hoops, puffer collar) ----
	private static final int SPW = 24, SPH = 30;
	private static final String[] PORTRAIT = {
		"........HHHHHHHH........",
		"......HHHHHHHHHHHH......",
		".....HHHHHHHHHHHHHH.....",
		"....HHHHHHHHHHHHHHHH....",
		"...HHhHHHHHHHHHHHHHH....",
		"...HhHHHHHHHHHHHHHHHH...",
		"..HHhHHHHHHHHHHHHHHHH...",
		"..HHhHHHHHHHHHHHHHHHHH..",
		"..HhHHHHHHHHHHHHHHHHHH..",
		"..HhHHSSSSSSSSSSSSHHHH..",
		".hVVVVVVVVVVVVVVVVVVVVh.",
		".hVVVVVVVVVVVVVVVVVVVVh.",
		".hVVVVVVVVVVVVVVVVVVVVh.",
		".hVVVVVVVVVVVVVVVVVVVVh.",
		"..HSsVVVVVVVVVVVVVVsSH..",
		"..HHSSSSSSSSSSSSSSSSHH..",
		"..HHSSSSSSSssSSSSSSSHH..",
		".EHHSSSSSSSssSSSSSSSHHE.",
		".EHHSSSSSSSSSSSSSSSSHHE.",
		".EHHSSSSLLLLLLLLSSSSHHE.",
		".EHHSSSSSllllllSSSSSHHE.",
		"..HHSSSSSSSSSSSSSSSSHH..",
		"..HHhSSSSSSSSSSSSSShHH..",
		"..HHh.sSSSSSSSSSSs.hHH..",
		"..HHh..sSSSSSSSSs..hHH..",
		"..HH...sSSSSSSSSs...HH..",
		"..jJJj..sSSSSSSs..jJJj..",
		".jJJJJj..SSSSSS..jJJJJj.",
		".JJJJJcJj.ssss.jJcJJJJJ.",
		"JJJJJJJJJJJccJJJJJJJJJJJ",
	};
	private static final int VISOR_TOP = 10, VISOR_BOT = 14;
	private static final int VISOR_X0 = 2, VISOR_X1 = 21;
	private static final Color PX_HAIR   = new Color(0xF2, 0xE8, 0xF5);
	private static final Color PX_HAIR_S = new Color(0xB9, 0x8B, 0xC9);
	private static final Color PX_SKIN   = new Color(0xFF, 0xD3, 0xB8);
	private static final Color PX_SKIN_S = new Color(0xE0, 0x9A, 0x78);
	private static final Color PX_LIP    = new Color(0xFF, 0x4D, 0x7A);
	private static final Color PX_LIP_D  = new Color(0xC2, 0x2B, 0x57);
	private static final Color PX_GOLD   = new Color(0xFF, 0xD7, 0x5A);
	private static final Color PX_JKT    = new Color(0x6E, 0x2B, 0xB0);
	private static final Color PX_JKT_H  = new Color(0x9C, 0x4F, 0xE0);
	private static final Color PX_SHEEN  = new Color(0x46, 0xD8, 0xFF);
	private static final Color VISOR_L   = new Color(0xFF, 0x2B, 0xD6);
	private static final Color VISOR_R   = new Color(0x46, 0xE8, 0xFF);
	private static final String[] HITS = { "LIFE", "CREDO", "ЯТЛ", "BEVERLY HILLS" };

	// ---- FFT / analysis ----
	private static final int FFT_SIZE    = 2048;
	private static final int SAMPLE_RATE = 44100;
	private static final int NUM_BANDS   = 36;

	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   bandBin  = new int[NUM_BANDS + 1];
	private final float[] rawBands = new float[NUM_BANDS];
	private final float[] bands    = new float[NUM_BANDS];

	private float bass, energy;
	private float bassAvg;
	private float autoGain = 8f;
	private int   framesSinceBeat = 999;
	private int   beats = 0;

	// ---- Stars ----
	private static final int STAR_COUNT = 130;
	private final float[] starX  = new float[STAR_COUNT];
	private final float[] starY  = new float[STAR_COUNT];
	private final float[] starPh = new float[STAR_COUNT];
	private final float[] starSp = new float[STAR_COUNT];
	private final int[]   starSz = new int[STAR_COUNT];

	// ---- Jet (she was a flight attendant, after all) ----
	private boolean planeActive = false;
	private float   planeX;      // fraction of width, moves left → right
	private float   planeY;      // fraction of height
	private float   planeSpeed;  // fraction of width per frame

	// ---- VHS / beat FX state ----
	private int    gridFlash    = 0;
	private int    aberrFrames  = 0;
	private int    glitchFrames = 0;
	private int    billboardDim = 0;
	private int    titleFrames  = 0;
	private String trackTitle   = "";
	private double gridScroll   = 0d;
	private int    idleFrames   = 0;
	private int    frame        = 0;

	// ---- Offscreen scene buffer (needed for real pixel-shear glitches) ----
	private BufferedImage scene;
	private int sceneW = -1, sceneH = -1;
	private LinearGradientPaint skyPaint;
	private LinearGradientPaint floorPaint;

	private final Random rnd = new Random(0x21EA7L);

	ZivertVisualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		float logMin = (float) Math.log(40), logMax = (float) Math.log(15000);
		for (int b = 0; b <= NUM_BANDS; b++) {
			float freq = (float) Math.exp(logMin + (logMax - logMin) * b / NUM_BANDS);
			bandBin[b] = clampI(Math.round(freq * FFT_SIZE / SAMPLE_RATE), 1, FFT_SIZE / 2);
		}
		for (int b = 1; b <= NUM_BANDS; b++) {
			if (bandBin[b] <= bandBin[b - 1]) bandBin[b] = bandBin[b - 1] + 1;
		}

		Random starRnd = new Random(0x51A25L);
		for (int i = 0; i < STAR_COUNT; i++) {
			starX[i]  = starRnd.nextFloat();
			starY[i]  = starRnd.nextFloat() * HORIZON_FRAC * 0.92f;
			starPh[i] = starRnd.nextFloat() * (float) (Math.PI * 2);
			starSp[i] = 0.02f + starRnd.nextFloat() * 0.05f;
			starSz[i] = starRnd.nextFloat() < 0.18f ? 2 : 1;
		}
	}

	/** New tune: flash the title on the VCR OSD and send a jet across the sky. */
	void setTrackTitle(String title) {
		trackTitle = title == null ? "" : title.toUpperCase();
		titleFrames = 480;
		idleFrames = 0;
		planeActive = true;
		planeX = -0.15f;
		planeY = 0.10f + rnd.nextFloat() * 0.16f;
		planeSpeed = 0.0009f + rnd.nextFloat() * 0.0006f;
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
			for (int b = 0; b < NUM_BANDS; b++) bands[b] *= 0.93f;
			bass   += (0f - bass)   * 0.06f;
			energy += (0f - energy) * 0.06f;
			idleFrames++;
		}
		if (gridFlash > 0) gridFlash--;
		if (aberrFrames > 0) aberrFrames--;
		if (titleFrames > 0) titleFrames--;
		if (glitchFrames > 0) glitchFrames--;
		if (rnd.nextFloat() < 0.004f) glitchFrames = Math.max(glitchFrames, 2);
		if (billboardDim > 0) billboardDim--;
		if (rnd.nextFloat() < 0.008f) billboardDim = 2;
		gridScroll += 0.0045d + energy * 0.020d + bass * 0.012d;
		updatePlane();

		ensureScene(w, h);
		Graphics2D g = scene.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			drawSky(g, w, h);
			drawStars(g, w, h);
			drawSun(g, w, h);
			drawPlane(g, w, h);
			drawWordmark(g, w, h);
			drawFloor(g, w, h);
			drawGrid(g, w, h);
			drawSkyline(g, w, h);
			drawHorizonLine(g, w, h);
			drawBillboard(g, w, h);
			drawPalms(g, w, h);
		} finally {
			g.dispose();
		}

		blitWithGlitch(g2, w, h);
		drawCrtOverlay(g2, w, h);
		drawOsd(g2, w, h, hasAudio);
	}

	private void ensureScene(int w, int h) {
		if (scene == null || sceneW != w || sceneH != h) {
			scene = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_RGB);
			sceneW = w;
			sceneH = h;
			skyPaint = new LinearGradientPaint(0, 0, 0, Math.max(1, horizonY(h)), SKY_STOPS, SKY_COLORS);
			floorPaint = new LinearGradientPaint(0, horizonY(h), 0, Math.max(horizonY(h) + 1, h),
				new float[]{ 0f, 1f }, new Color[]{ FLOOR_TOP, FLOOR_BOT });
		}
	}

	private static int horizonY(int h) {
		return Math.round(h * HORIZON_FRAC);
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
		for (int b = 0; b < NUM_BANDS; b++) {
			float sum = 0f;
			for (int k = bandBin[b]; k < bandBin[b + 1]; k++) {
				sum += re[k] * re[k] + im[k] * im[k];
			}
			rawBands[b] = (float) Math.sqrt(sum / (bandBin[b + 1] - bandBin[b])) / FFT_SIZE * 24f;
			if (rawBands[b] > rawMax) rawMax = rawBands[b];
		}
		float targetGain = clampF(0.8f / rawMax, 1f, 400f);
		autoGain += (targetGain - autoGain) * (targetGain < autoGain ? 0.15f : 0.02f);

		float sum = 0f;
		for (int b = 0; b < NUM_BANDS; b++) {
			float n = clampF(rawBands[b] * autoGain, 0f, 1f);
			bands[b] += (n - bands[b]) * (n > bands[b] ? 0.50f : 0.10f);
			sum += bands[b];
		}
		float nb = clampF((bands[0] + bands[1] + bands[2]) * 0.45f, 0f, 1f);
		bass   += (nb - bass) * (nb > bass ? 0.5f : 0.12f);
		energy += (sum / NUM_BANDS - energy) * 0.15f;

		bassAvg += (bass - bassAvg) * 0.02f;
		framesSinceBeat++;
		if (framesSinceBeat > 13 && bass > 0.15f && bass > bassAvg * 1.40f + 0.03f) {
			onBeat();
		}
	}

	private void onBeat() {
		framesSinceBeat = 0;
		beats++;
		gridFlash = 6;
		if (energy > 0.30f) aberrFrames = 4;
		if (beats % 5 == 0 && energy > 0.35f) glitchFrames = 3;
	}

	// ---- Sky / stars ----

	private void drawSky(Graphics2D g, int w, int h) {
		g.setPaint(skyPaint);
		g.fillRect(0, 0, w, horizonY(h));
	}

	private void drawStars(Graphics2D g, int w, int h) {
		int hy = horizonY(h);
		for (int i = 0; i < STAR_COUNT; i++) {
			int y = Math.round(starY[i] * h);
			if (y >= hy - 4) continue;
			float tw = 0.45f + 0.55f * (float) Math.sin(frame * starSp[i] + starPh[i]);
			int a = clamp255(Math.round((90 + 120 * tw) * (0.75f + energy * 0.35f)));
			g.setColor(new Color(STAR.getRed(), STAR.getGreen(), STAR.getBlue(), a));
			g.fillRect(Math.round(starX[i] * w), y, starSz[i], starSz[i]);
		}
	}

	// ---- The sun ----

	private void drawSun(Graphics2D g, int w, int h) {
		int   hy = horizonY(h);
		float cx = w * 0.5f;
		float r  = Math.min(h * 0.30f, w * 0.24f) * (1f + bass * 0.05f);
		float cy = hy - r * 0.42f;

		// Atmospheric halo behind the disc, breathing with the bass.
		int haloA = clamp255(60 + Math.round(70 * bass));
		g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), Math.max(1f, r * 2.1f),
			new float[]{ 0f, 1f },
			new Color[]{ new Color(255, 110, 180, haloA), new Color(255, 110, 180, 0) }));
		g.fillRect(Math.round(cx - r * 2.2f), Math.round(cy - r * 2.2f),
			Math.round(r * 4.4f), Math.round(r * 4.4f));

		Ellipse2D.Float disc = new Ellipse2D.Float(cx - r, cy - r, r * 2f, r * 2f);
		g.setPaint(new LinearGradientPaint(0, cy - r, 0, cy + r, SUN_STOPS, SUN_COLORS));
		g.fill(disc);

		// Scanline slats: gaps drift slowly downward, widening toward the base.
		float spacing = Math.max(4f, r * 0.16f);
		float phase   = (frame * 0.4f) % spacing;
		float bottom  = cy + r;
		g.setClip(disc);
		g.setPaint(skyPaint);
		for (int k = 0; k < 40; k++) {
			float y = bottom - k * spacing + phase;
			if (y < cy - r) break;
			float rel = (bottom - y) / (r * 2f);
			if (rel < 0f || rel > 0.62f) continue;
			float gap = (0.62f - rel) / 0.62f * (r * 0.11f);
			if (gap < 1f) continue;
			g.fillRect(Math.round(cx - r - 2), Math.round(y - gap), Math.round(r * 2f + 4), Math.round(gap));
		}
		g.setClip(null);
	}

	// ---- The jet ----

	private void updatePlane() {
		if (planeActive) {
			planeX += planeSpeed;
			if (planeX > 1.15f) planeActive = false;
		} else if (rnd.nextFloat() < 0.002f) {
			planeActive = true;
			planeX = -0.15f;
			planeY = 0.10f + rnd.nextFloat() * 0.16f;
			planeSpeed = 0.0009f + rnd.nextFloat() * 0.0006f;
		}
	}

	private void drawPlane(Graphics2D g, int w, int h) {
		if (!planeActive) return;
		float cx = planeX * w;
		float cy = (planeY + 0.012f * (float) Math.sin(frame * 0.02f)) * h;
		float s  = Math.max(6f, h * 0.028f);

		// Contrail fading out behind the tail.
		float tail = cx - s * 1.2f;
		g.setPaint(new GradientPaint(tail - w * 0.16f, cy, new Color(255, 235, 250, 0),
			tail, cy, new Color(255, 235, 250, 80)));
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(Math.round(tail - w * 0.16f), Math.round(cy + 1), Math.round(tail), Math.round(cy + 1));

		// Silhouette: fuselage, swept wing, tail fin.
		g.setColor(new Color(16, 10, 34));
		g.fillRoundRect(Math.round(cx - s * 1.1f), Math.round(cy - s * 0.22f),
			Math.round(s * 2.2f), Math.round(s * 0.44f), Math.round(s * 0.4f), Math.round(s * 0.4f));
		Path2D.Float wing = new Path2D.Float();
		wing.moveTo(cx + s * 0.10f, cy);
		wing.lineTo(cx - s * 0.55f, cy + s * 0.75f);
		wing.lineTo(cx - s * 0.15f, cy + s * 0.05f);
		wing.closePath();
		g.fill(wing);
		Path2D.Float fin = new Path2D.Float();
		fin.moveTo(cx - s * 0.80f, cy - s * 0.05f);
		fin.lineTo(cx - s * 1.35f, cy - s * 0.75f);
		fin.lineTo(cx - s * 1.05f, cy - s * 0.05f);
		fin.closePath();
		g.fill(fin);

		// Cabin windows glowing at dusk.
		g.setColor(new Color(180, 230, 255, 200));
		for (int i = 0; i < 3; i++) {
			g.fillRect(Math.round(cx - s * 0.45f + i * s * 0.45f), Math.round(cy - s * 0.08f), 1, 1);
		}
		// Red beacon on the fin, white strobe on the wingtip.
		if ((frame / 15) % 2 == 0) {
			g.setColor(new Color(255, 64, 64, 230));
			g.fillOval(Math.round(cx - s * 1.36f), Math.round(cy - s * 0.85f), 2, 2);
		}
		if (frame % 45 < 3) {
			g.setColor(new Color(255, 255, 255, 240));
			g.fillOval(Math.round(cx - s * 0.60f), Math.round(cy + s * 0.70f), 3, 3);
		}
	}

	// ---- Chrome wordmark ----

	private void drawWordmark(Graphics2D g, int w, int h) {
		float size = Math.min(w / 7.2f, h / 3.6f);
		if (size < 14f) return;
		Font f = new Font(Font.SANS_SERIF, Font.BOLD | Font.ITALIC, Math.round(size));
		g.setFont(f);
		FontMetrics fm = g.getFontMetrics();

		String text = "ZIVERT";
		float tracking = size * 0.10f;
		float total = -tracking;
		for (int i = 0; i < text.length(); i++) {
			total += fm.charWidth(text.charAt(i)) + tracking;
		}
		float x0 = (w - total) * 0.5f;
		float baseY = h * 0.30f;

		// Outer neon glow.
		g.setColor(new Color(255, 64, 200, 28));
		int[][] ring = { {3, 0}, {-3, 0}, {0, 3}, {0, -3}, {2, 2}, {-2, 2}, {2, -2}, {-2, -2} };
		for (int[] o : ring) {
			drawTracked(g, text, x0 + o[0], baseY + o[1], tracking, fm);
		}
		g.setColor(new Color(255, 96, 220, 60));
		int[][] inner = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };
		for (int[] o : inner) {
			drawTracked(g, text, x0 + o[0], baseY + o[1], tracking, fm);
		}

		// Beat-driven chromatic aberration ghosts.
		if (aberrFrames > 0) {
			int off = aberrFrames;
			g.setColor(new Color(255, 40, 70, 120));
			drawTracked(g, text, x0 - off, baseY, tracking, fm);
			g.setColor(new Color(40, 230, 255, 120));
			drawTracked(g, text, x0 + off, baseY, tracking, fm);
		}

		// Chrome fill: sky reflection above, hard horizon cut, neon base below.
		float yTop = baseY - fm.getAscent() * 0.95f;
		float yBot = baseY + Math.max(1f, fm.getDescent() * 0.35f);
		g.setPaint(new LinearGradientPaint(0, yTop, 0, yBot, CHROME_STOPS, CHROME_COLORS));
		drawTracked(g, text, x0, baseY, tracking, fm);

		// Spaced-caps subtitle, like the back of a cassette single.
		float subSize = Math.max(9f, size * 0.16f);
		g.setFont(new Font(Font.MONOSPACED, Font.BOLD, Math.round(subSize)));
		FontMetrics sfm = g.getFontMetrics();
		String sub = "N E O N  P O P  W A V E";
		int sw = sfm.stringWidth(sub);
		float sy = baseY + size * 0.30f;
		g.setColor(new Color(SUBTITLE.getRed(), SUBTITLE.getGreen(), SUBTITLE.getBlue(), 70));
		g.drawString(sub, (w - sw) * 0.5f + 1, sy + 1);
		g.setColor(new Color(SUBTITLE.getRed(), SUBTITLE.getGreen(), SUBTITLE.getBlue(), 210));
		g.drawString(sub, (w - sw) * 0.5f, sy);
	}

	private static void drawTracked(Graphics2D g, String text, float x, float baseY,
	                                float tracking, FontMetrics fm) {
		float cx = x;
		for (int i = 0; i < text.length(); i++) {
			String c = String.valueOf(text.charAt(i));
			g.drawString(c, cx, baseY);
			cx += fm.charWidth(text.charAt(i)) + tracking;
		}
	}

	// ---- Floor / grid ----

	private void drawFloor(Graphics2D g, int w, int h) {
		int hy = horizonY(h);
		g.setPaint(floorPaint);
		g.fillRect(0, hy, w, h - hy);

		// Sun reflection pooled on the grid floor.
		int a = clamp255(55 + Math.round(50 * bass));
		g.setClip(0, hy, w, h - hy);
		g.setPaint(new RadialGradientPaint(new Point2D.Float(w * 0.5f, hy), Math.max(1f, w * 0.33f),
			new float[]{ 0f, 1f },
			new Color[]{ new Color(255, 60, 190, a), new Color(255, 60, 190, 0) }));
		g.fillRect(0, hy, w, h - hy);
		g.setClip(null);
	}

	private void drawGrid(Graphics2D g, int w, int h) {
		int hy = horizonY(h);
		g.setClip(0, hy + 1, w, h - hy);
		float cx = w * 0.5f;
		float flash = 1f + gridFlash * 0.10f + energy * 0.15f;

		// Verticals fanning out of the vanishing point.
		for (int i = -12; i <= 12; i++) {
			float xe = cx + i * w * 0.16f;
			g.setColor(new Color(GRID_V.getRed(), GRID_V.getGreen(), GRID_V.getBlue(),
				clamp255(Math.round(38 * flash))));
			g.setStroke(new BasicStroke(2.6f));
			g.drawLine(Math.round(cx), hy, Math.round(xe), h);
			g.setColor(new Color(GRID_V.getRed(), GRID_V.getGreen(), GRID_V.getBlue(),
				clamp255(Math.round(95 * flash))));
			g.setStroke(new BasicStroke(1f));
			g.drawLine(Math.round(cx), hy, Math.round(xe), h);
		}

		// Horizontals racing toward the viewer.
		int rows = 13;
		for (int n = 0; n < rows; n++) {
			double p = (n / (double) rows + gridScroll) % 1d;
			float pf = (float) p;
			int y = hy + Math.round((h - hy) * (float) Math.pow(pf, 2.6d));
			int a = clamp255(Math.round((25 + 180 * pf) * Math.min(1.5f, flash)));
			if (pf > 0.5f) {
				g.setColor(new Color(GRID_H.getRed(), GRID_H.getGreen(), GRID_H.getBlue(),
					clamp255(Math.round(a * 0.35f))));
				g.setStroke(new BasicStroke(3f + 4f * pf));
				g.drawLine(0, y, w, y);
			}
			g.setColor(new Color(GRID_H.getRed(), GRID_H.getGreen(), GRID_H.getBlue(), a));
			g.setStroke(new BasicStroke(1f + 2.4f * pf));
			g.drawLine(0, y, w, y);
		}
		g.setClip(null);
	}

	// ---- Skyline equalizer ----

	private void drawSkyline(Graphics2D g, int w, int h) {
		int   hy   = horizonY(h);
		float slot = w / (float) NUM_BANDS;
		float barW = Math.max(2f, slot * 0.82f);
		float maxH = h * 0.14f;

		for (int b = 0; b < NUM_BANDS; b++) {
			float level = bands[b];
			int   hgt   = 3 + Math.round(level * maxH);
			int   x     = Math.round(b * slot + (slot - barW) * 0.5f);
			int   iw    = Math.round(barW);
			int   y     = hy - hgt;

			g.setColor(TOWER);
			g.fillRect(x, y, iw, hgt);

			// Neon roofline, pink on the flanks fading to cyan downtown.
			float hue = 0.92f - 0.40f * b / (NUM_BANDS - 1f);
			g.setColor(hsba(hue, 0.85f, 1f, 90 + Math.round(120 * level)));
			g.fillRect(x, y - 1, iw, 2);
			g.setColor(hsba(hue, 0.70f, 1f, Math.round(70 * level)));
			g.fillRect(x - 1, y - 4, iw + 2, 4);

			// A few windows still lit at dusk.
			if (hgt > 14) {
				g.setColor(new Color(255, 220, 150, 140));
				for (int wy = hy - 4; wy > y + 3; wy -= 5) {
					for (int wx = 0; wx < 2; wx++) {
						if (((b * 31 + wy * 7 + wx * 13) & 7) < 2) {
							g.fillRect(x + 2 + wx * Math.max(2, iw / 2), wy, 1, 1);
						}
					}
				}
			}
		}
	}

	private void drawHorizonLine(Graphics2D g, int w, int h) {
		int hy = horizonY(h);
		g.setColor(new Color(255, 60, 200, 50));
		g.setStroke(new BasicStroke(5f));
		g.drawLine(0, hy, w, hy);
		g.setColor(new Color(255, 210, 250, 230));
		g.setStroke(new BasicStroke(1.6f));
		g.drawLine(0, hy, w, hy);
	}

	// ---- Roadside billboard: the star herself, in pixels ----

	private void drawBillboard(Graphics2D g, int w, int h) {
		int hy   = horizonY(h);
		int s    = Math.max(1, Math.round(Math.min(w * 0.145f / SPW, h * 0.31f / SPH)));
		int padX = s * 2;
		int capH = Math.max(10, s * 4);
		int panelW = SPW * s + padX * 2;
		int panelH = s * 2 + SPH * s + s + capH + s;
		int footY  = hy + Math.round((h - hy) * 0.22f);
		int legH   = Math.max(8, Math.round(h * 0.045f));
		int px = Math.round(w * 0.815f - panelW / 2f);
		int py = Math.max(2, footY - legH - panelH);
		float bf = billboardDim > 0 ? 0.45f : 1f;

		// Legs and a cross brace planted on the grid.
		g.setColor(new Color(13, 5, 36));
		g.setStroke(new BasicStroke(Math.max(2f, s), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
		int lx1 = px + panelW / 5, lx2 = px + panelW - panelW / 5;
		g.drawLine(lx1, py + panelH, lx1, footY);
		g.drawLine(lx2, py + panelH, lx2, footY);
		g.setStroke(new BasicStroke(1.2f));
		g.drawLine(lx1, footY - legH / 3, lx2, py + panelH + legH / 3);

		// Panel body.
		g.setPaint(new GradientPaint(0, py, new Color(10, 6, 24), 0, py + panelH, new Color(26, 11, 51)));
		g.fillRect(px, py, panelW, panelH);

		// Neon frame with bloom, pulsing with the music, flickering like old tube.
		int ba = clamp255(Math.round((110 + 80 * energy + (gridFlash > 0 ? 40 : 0)) * bf));
		g.setColor(new Color(255, 77, 200, clamp255(Math.round(ba * 0.35f))));
		g.setStroke(new BasicStroke(4.5f));
		g.drawRect(px - 1, py - 1, panelW + 2, panelH + 2);
		g.setColor(new Color(255, 120, 220, ba));
		g.setStroke(new BasicStroke(1.6f));
		g.drawRect(px, py, panelW, panelH);

		// The pixel portrait.
		int ox = px + padX, oy = py + s * 2;
		for (int r = 0; r < SPH; r++) {
			String row = PORTRAIT[r];
			for (int c = 0; c < SPW; c++) {
				Color col = pixelColor(row.charAt(c), c, r);
				if (col == null) continue;
				g.setColor(bf < 1f ? scale(col, bf) : col);
				g.fillRect(ox + c * s, oy + r * s, s, s);
			}
		}
		// Hologram scanlines over the portrait.
		g.setColor(new Color(0, 0, 0, 30));
		for (int r = 1; r < SPH; r += 2) {
			g.fillRect(ox, oy + r * s, SPW * s, Math.max(1, s / 2));
		}

		// Caption strip cycling through the discography.
		int capY = oy + SPH * s + s;
		String hit = HITS[(frame / 900) % HITS.length];
		int fs = Math.max(8, capH - 4);
		Font cf = new Font(Font.MONOSPACED, Font.BOLD, fs);
		FontMetrics cfm = g.getFontMetrics(cf);
		while (fs > 7 && cfm.stringWidth(hit) > panelW - 8) {
			fs--;
			cf = new Font(Font.MONOSPACED, Font.BOLD, fs);
			cfm = g.getFontMetrics(cf);
		}
		g.setFont(cf);
		int tx = px + (panelW - cfm.stringWidth(hit)) / 2;
		int ty = capY + (capH + cfm.getAscent() - cfm.getDescent()) / 2;
		Color neon = (frame / 900) % 2 == 0 ? new Color(255, 120, 220) : new Color(110, 230, 255);
		g.setColor(scale(neon, 0.40f * bf));
		g.drawString(hit, tx + 1, ty + 1);
		g.drawString(hit, tx - 1, ty - 1);
		g.setColor(bf < 1f ? scale(neon, bf) : neon);
		g.drawString(hit, tx, ty);
	}

	private Color pixelColor(char ch, int c, int r) {
		return switch (ch) {
			case 'H' -> PX_HAIR;
			case 'h' -> PX_HAIR_S;
			case 'S' -> PX_SKIN;
			case 's' -> PX_SKIN_S;
			case 'L' -> PX_LIP;
			case 'l' -> PX_LIP_D;
			case 'E' -> PX_GOLD;
			case 'J' -> PX_JKT;
			case 'j' -> PX_JKT_H;
			case 'c' -> PX_SHEEN;
			case 'V' -> visorPixel(c, r);
			default  -> null;
		};
	}

	/** Wrap-around visor glass: magenta→cyan sweep, specular streak, and the sunset reflected in miniature. */
	private Color visorPixel(int c, int r) {
		float t = clampF((c - VISOR_X0) / (float) (VISOR_X1 - VISOR_X0), 0f, 1f);
		Color base = lerp(VISOR_L, VISOR_R, t);
		// Tiny reflected sun, slat gaps and all.
		float dx = c - 7.5f, dy = r - 12.3f;
		if (dx * dx + dy * dy * 1.4f < 6.5f) {
			boolean slatGap = dy >= 0.5f && (r & 1) == 0;
			if (!slatGap) {
				return dy < 0 ? new Color(0xFF, 0xE9, 0xA0) : new Color(0xFF, 0x7B, 0xAA);
			}
		}
		if (r == VISOR_TOP + 1 && c >= 10 && c <= 16) {
			return new Color(0xEF, 0xFA, 0xFF);
		}
		if (r == VISOR_TOP || r == VISOR_BOT) {
			return scale(base, 0.62f);
		}
		return base;
	}

	// ---- Palms ----

	private void drawPalms(Graphics2D g, int w, int h) {
		drawPalm(g, w * 0.07f, -1, w, h, 0f);
		drawPalm(g, w * 0.93f, 1, w, h, 2.1f);
	}

	private void drawPalm(Graphics2D g, float baseX, int dir, int w, int h, float phase) {
		int   hy     = horizonY(h);
		float sway   = (float) Math.sin(frame * 0.009f + phase) * (2f + energy * 4f);
		float crownX = baseX + dir * h * 0.02f + sway;
		float crownY = hy - h * 0.13f;
		float baseY  = h + 4f;

		g.setColor(PALM);
		g.setStroke(new BasicStroke(Math.max(3.5f, h * 0.012f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.draw(new QuadCurve2D.Float(baseX, baseY,
			(baseX + crownX) * 0.5f + dir * 6f, (baseY + crownY) * 0.5f + h * 0.03f,
			crownX, crownY));

		int fronds = 7;
		for (int k = 0; k < fronds; k++) {
			float ang = (float) Math.toRadians(-150 + k * (120f / (fronds - 1))) + sway * 0.02f;
			float len = h * 0.10f * (0.85f + 0.30f * (float) Math.sin(k * 2.1f + phase));
			float ex = crownX + (float) Math.cos(ang) * len;
			float ey = crownY + (float) Math.sin(ang) * len * 0.85f + len * 0.25f;
			float mx = crownX + (float) Math.cos(ang) * len * 0.5f;
			float my = crownY + (float) Math.sin(ang) * len * 0.5f - len * 0.35f;

			g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.draw(new QuadCurve2D.Float(crownX, crownY, mx, my, ex, ey));

			// Leaf barbs drooping off the spine give the silhouette its palm-ness.
			g.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			for (float t : new float[]{ 0.55f, 0.75f, 0.9f }) {
				float omt = 1f - t;
				float px = omt * omt * crownX + 2f * omt * t * mx + t * t * ex;
				float py = omt * omt * crownY + 2f * omt * t * my + t * t * ey;
				g.drawLine(Math.round(px), Math.round(py),
					Math.round(px + (float) Math.cos(ang + 0.9f) * len * 0.14f),
					Math.round(py + len * 0.16f));
			}
		}
	}

	// ---- VHS compositing ----

	private void blitWithGlitch(Graphics2D g2, int w, int h) {
		g2.drawImage(scene, 0, 0, null);
		if (glitchFrames <= 0) return;

		int bandCount = 2 + rnd.nextInt(2);
		for (int i = 0; i < bandCount; i++) {
			int by = rnd.nextInt(Math.max(1, h - 20));
			int bh = 3 + rnd.nextInt(12);
			int dx = rnd.nextInt(37) - 18;
			g2.drawImage(scene, dx, by, dx + w, by + bh, 0, by, w, by + bh, null);
			g2.setColor(new Color(255, 255, 255, 40));
			g2.fillRect(0, by + bh, w, 1);
		}
	}

	private void drawCrtOverlay(Graphics2D g2, int w, int h) {
		// Scanlines.
		g2.setColor(new Color(0, 0, 0, 20));
		for (int y = 0; y < h; y += 2) {
			g2.drawLine(0, y, w, y);
		}
		// Slow vertical roll bar, like a tired VCR head.
		int rollY = (int) ((frame * 0.6f) % (h + 40)) - 20;
		g2.setColor(new Color(255, 255, 255, 7));
		g2.fillRect(0, rollY, w, 26);
		// Tape noise specks.
		for (int i = 0; i < 18; i++) {
			g2.setColor(new Color(255, 255, 255, 10 + rnd.nextInt(12)));
			g2.fillRect(rnd.nextInt(Math.max(1, w)), rnd.nextInt(Math.max(1, h)), 1, 1);
		}
		// Side vignette, matching the house style.
		int vw = Math.min(32, w / 6);
		if (vw > 0) {
			g2.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 90), vw, 0, new Color(0, 0, 0, 0)));
			g2.fillRect(0, 0, vw, h);
			g2.setPaint(new GradientPaint(w - vw, 0, new Color(0, 0, 0, 0), w, 0, new Color(0, 0, 0, 90)));
			g2.fillRect(w - vw, 0, vw, h);
		}
	}

	// ---- VCR OSD (drawn over everything; a real VCR's OSD never glitches) ----

	private void drawOsd(Graphics2D g2, int w, int h, boolean hasAudio) {
		int osdSize = Math.max(11, h / 24);
		g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, osdSize));
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		FontMetrics fm = g2.getFontMetrics();
		int pad = 12;
		int topY = pad + fm.getAscent();

		if (hasAudio) {
			osdText(g2, "► PLAY", pad, topY);
		} else if ((frame / 30) % 2 == 0 || idleFrames < 30) {
			osdText(g2, "PAUSE", pad, topY);
		}

		int total = frame / 60;
		String counter = String.format("SP %d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
		osdText(g2, counter, w - fm.stringWidth(counter) - pad, topY);

		if (titleFrames > 0 && !trackTitle.isEmpty()) {
			float fade = Math.min(1f, titleFrames / 60f);
			String label = "♪ " + trackTitle;
			while (label.length() > 3 && fm.stringWidth(label) > w - pad * 2) {
				label = label.substring(0, label.length() - 1);
			}
			int y = h - pad - fm.getDescent();
			g2.setColor(new Color(0, 0, 0, Math.round(170 * fade)));
			g2.drawString(label, pad + 1, y + 1);
			g2.setColor(new Color(OSD_INK.getRed(), OSD_INK.getGreen(), OSD_INK.getBlue(),
				Math.round(235 * fade)));
			g2.drawString(label, pad, y);
		}
	}

	private static void osdText(Graphics2D g2, String text, int x, int y) {
		g2.setColor(new Color(0, 0, 0, 170));
		g2.drawString(text, x + 1, y + 1);
		g2.setColor(OSD_INK);
		g2.drawString(text, x, y);
	}

	// ---- Helpers ----

	private static Color lerp(Color a, Color b, float t) {
		return new Color(
			Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
			Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
	}

	private static Color scale(Color c, float f) {
		return new Color(clamp255(Math.round(c.getRed() * f)),
			clamp255(Math.round(c.getGreen() * f)),
			clamp255(Math.round(c.getBlue() * f)));
	}

	private static Color hsba(float h, float s, float b, int a) {
		h = ((h % 1f) + 1f) % 1f;
		Color c = Color.getHSBColor(h, clampF(s, 0f, 1f), clampF(b, 0f, 1f));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp255(a));
	}

	private static int clampI(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clampF(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int clamp255(int a) {
		return Math.max(0, Math.min(255, a));
	}
}
