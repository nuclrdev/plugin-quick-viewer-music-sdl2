package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * Assembly-style demoscene visualizer — the full oldskool stack, beat-driven.
 * <p>
 * Layered back to front: a chunky palette-cycled <b>plasma</b>, sine-driven <b>copper bars</b>,
 * a perspective <b>starfield</b> that warps on the kick, a rotating wireframe <b>vector cube</b>,
 * a bank of <b>VU blocks</b>, and a <b>sine scroller</b> greeting the scene. A snare/kick
 * detector on the bass bins drives the flash, the star warp and the cube's spin.
 * <p>
 * The plasma is rendered into a small {@code int[]} raster and blown up with nearest-neighbour
 * sampling: that is both how it was done in 1993 and the reason it costs nothing at 60 fps.
 * Everything else is pre-allocated — sine tables, a 256-entry palette, the star and particle
 * arrays — so a frame allocates nothing and the EDT stays smooth.
 *
 * @see WaveformPanel.VisualizerMode#DEMOSCENE
 */
final class DemosceneVisualizer {

	// ---- Analysis ----
	private static final int   FFT_SIZE    = 1024;
	private static final int   SAMPLE_RATE = 44100;
	private static final int   BARS        = 32;
	private static final int   BASS_BINS   = 5;     // ~0-215 Hz: the kick lives here
	private static final float BEAT_FACTOR = 1.32f; // bass must exceed the running mean by this much
	private static final int   BEAT_HOLD   = 7;     // frames of cooldown, so one kick is one beat

	// ---- Plasma ----
	private static final int PIXEL      = 5;    // chunky-pixel size; the whole point
	private static final int SIN_BITS   = 10;
	private static final int SIN_SIZE   = 1 << SIN_BITS;
	private static final int SIN_MASK   = SIN_SIZE - 1;
	private static final int PALETTE    = 256;

	// ---- Starfield ----
	private static final int   STARS     = 110;
	private static final float STAR_BASE = 0.010f;
	private static final float WARP_DECAY = 0.90f;

	// ---- Copper ----
	private static final int COPPER_BARS = 5;

	// ---- Scroller ----
	private static final String GREETINGS =
			"NUCLR COMMANDER PRESENTS  ···  100% PURE JAVA2D, NO GPU HARMED  ···  "
			+ "GREETINGS TO EVERY SCENER STILL COUNTING SCANLINES  ···  "
			+ "RESPECT TO FUTURE CREW, TRITON, PURPLE, FARBRAUSCH & THE WHOLE ASSEMBLY CROWD  ···  "
			+ "REMEMBER: A DEMO IS NEVER FINISHED, IT IS ONLY RELEASED  ···  ";

	// ---- Tables (built once) ----
	private final float[] sin        = new float[SIN_SIZE];
	private final int[]   paletteRgb = new int[PALETTE];  // dim: the plasma backdrop
	private final Color[] palette    = new Color[PALETTE];  // same, as Color
	private final Color[] paletteHot = new Color[PALETTE];  // full brightness: copper, cube, logo
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   barBin   = new int[BARS + 1];

	// ---- FFT buffers ----
	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];

	// ---- Bar state ----
	private final float[] barRaw   = new float[BARS];  // this frame's magnitudes, pre-smoothing
	private final float[] barLevel = new float[BARS];  // smoothed, what actually gets drawn
	private final float[] barPeak  = new float[BARS];
	private float autoGain = 1f;
	private float energy;
	private float bass;
	private float bassMean = 0.02f;

	// ---- Beat ----
	private int   beatCooldown;
	private float flash;      // white-out, decays each frame
	private float warp;       // starfield speed kick, decays each frame
	private int   beatCount;

	// ---- Starfield ----
	private final float[] starX = new float[STARS];
	private final float[] starY = new float[STARS];
	private final float[] starZ = new float[STARS];

	// ---- Vector cube ----
	private static final float[][] CUBE_VERTS = {
			{-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
			{-1, -1,  1}, {1, -1,  1}, {1, 1,  1}, {-1, 1,  1}
	};
	private static final int[][] CUBE_EDGES = {
			{0, 1}, {1, 2}, {2, 3}, {3, 0},
			{4, 5}, {5, 6}, {6, 7}, {7, 4},
			{0, 4}, {1, 5}, {2, 6}, {3, 7}
	};
	private final float[] projX = new float[CUBE_VERTS.length];
	private final float[] projY = new float[CUBE_VERTS.length];
	private float cubeAngle;
	private float cubeSpin = 0.010f;

	// ---- Plasma raster ----
	private BufferedImage plasmaImg;
	private int[] plasmaPix;
	private int plasmaW, plasmaH;

	// ---- Scroller ----
	private String scrollText = GREETINGS;
	private float  scrollX = Float.NaN; // NaN = start off the right edge on first paint
	private Font   scrollFont;
	private Font   logoFont;
	private int    scrollFontSize = -1;

	private final Random rnd = new Random(0x5CE7EL);

	DemosceneVisualizer() {
		for (int i = 0; i < SIN_SIZE; i++) {
			sin[i] = (float) Math.sin(i * 2 * Math.PI / SIN_SIZE);
		}
		buildPalette();
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		buildBarBins();
		resetStars();
	}

	/** Name the tune in the scroller, the way a demo would announce the module it is playing. */
	void setTrackTitle(String title) {
		String tune = title == null || title.isBlank() ? "UNTITLED" : title.toUpperCase();
		scrollText = "NOW PLAYING: " + tune + "  ···  " + GREETINGS;
		scrollX = Float.NaN;
	}

	// =========================================================================
	// Frame
	// =========================================================================

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {

		analyze(ring);

		// Nearest-neighbour keeps the blown-up plasma pixels hard-edged instead of smeared.
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		drawPlasma(g2, w, h, frameCount);
		drawCopperBars(g2, w, h, frameCount);
		drawStarfield(g2, w, h);
		drawCube(g2, w, h);
		drawVuBlocks(g2, w, h);
		drawScroller(g2, w, h, frameCount);
		drawLogo(g2, w, h, frameCount);
		drawScanlines(g2, w, h);
		drawFlash(g2, w, h);

		flash *= 0.82f;
		warp  *= WARP_DECAY;
		if (beatCooldown > 0) beatCooldown--;
	}

	// =========================================================================
	// Analysis
	// =========================================================================

	private void analyze(AudioRingBuffer ring) {

		int samples = ring != null ? ring.snapshot(snapshot, FFT_SIZE) : 0;

		if (samples < BARS * 2) {
			// Silence: let everything sag back down, but keep the demo idling rather than freezing.
			for (int b = 0; b < BARS; b++) {
				barLevel[b] *= 0.90f;
				barPeak[b]   = Math.max(barPeak[b] - 0.010f, barLevel[b]);
			}
			energy += (0f - energy) * 0.08f;
			bass   += (0f - bass) * 0.08f;
			return;
		}

		int pad = FFT_SIZE - samples;
		for (int i = 0; i < FFT_SIZE; i++) {
			float s = i >= pad ? snapshot[i - pad] : 0f;
			re[i] = s * hann[i];
			im[i] = 0f;
		}
		Fft.transform(re, im, FFT_SIZE);

		float maxMag = 1e-4f;
		float sum    = 0f;
		for (int b = 0; b < BARS; b++) {
			float power = 0f;
			for (int k = barBin[b]; k < barBin[b + 1]; k++) {
				float p = re[k] * re[k] + im[k] * im[k];
				if (p > power) power = p;   // peak-pick the band: punchier than averaging it
			}
			// Tilt the top end up so hats and leads stay visible next to the bass.
			barRaw[b] = (float) Math.sqrt(power) / FFT_SIZE * (1f + 1.4f * b / BARS);
			if (barRaw[b] > maxMag) maxMag = barRaw[b];
		}

		float targetGain = clamp(0.85f / maxMag, 0.5f, 60f);
		autoGain += (targetGain - autoGain) * (targetGain < autoGain ? 0.20f : 0.05f);

		for (int b = 0; b < BARS; b++) {
			float level = clamp((float) Math.pow(barRaw[b] * autoGain, 0.70), 0f, 1.3f);
			// Snap up on the transient, glide back down — a bar that fell as fast as it rose
			// would just flicker.
			float shown = barLevel[b];
			barLevel[b] = level > shown ? level : shown + (level - shown) * 0.22f;
			barPeak[b]  = Math.max(barPeak[b] - 0.012f, barLevel[b]);
			sum += barLevel[b];
		}
		energy += (sum / BARS - energy) * 0.20f;

		detectBeat();
	}

	/** Kick detector: bass well above its own running mean, rate-limited so one thump is one beat. */
	private void detectBeat() {

		float b = 0f;
		for (int k = 1; k <= BASS_BINS; k++) {
			b += (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) / FFT_SIZE;
		}
		// Clamped: auto-gain runs up to 60x on quiet passages, and an unbounded bass would
		// blow the cube's radius (and the flash) far past the panel.
		b = clamp(b * autoGain, 0f, 1f);
		bass += (b - bass) * 0.45f;

		if (bass > bassMean * BEAT_FACTOR && beatCooldown == 0 && bass > 0.05f) {
			beatCooldown = BEAT_HOLD;
			beatCount++;
			flash    = Math.min(1f, 0.35f + bass * 0.5f);
			warp     = Math.min(1f, 0.55f + bass);
			cubeSpin = 0.055f;                       // kick the cube into a spin...
		} else {
			cubeSpin += (0.010f - cubeSpin) * 0.06f; // ...which eases back to a lazy drift
		}

		bassMean += (bass - bassMean) * 0.05f;
	}

	// =========================================================================
	// Plasma
	// =========================================================================

	/**
	 * Four interfering sine fields sampled per chunky pixel, coloured through a cycling palette.
	 * The palette offset advances with the music, so the whole screen pulses in time.
	 */
	private void drawPlasma(Graphics2D g2, int w, int h, int frameCount) {

		int lw = Math.max(1, w / PIXEL);
		int lh = Math.max(1, h / PIXEL);
		ensurePlasmaRaster(lw, lh);

		float t     = frameCount * 0.021f;
		int   cycle = (int) (frameCount * 1.7f + energy * 260f);
		float amp   = 26f + energy * 34f;          // louder music = deeper colour swings
		int   cx    = lw / 2;
		int   cy    = lh / 2;

		for (int y = 0; y < lh; y++) {
			int rowBase = y * lw;
			for (int x = 0; x < lw; x++) {
				float v = lut(x * 14f + t * 90f)
						+ lut(y * 17f + t * 62f)
						+ lut((x + y) * 9f + t * 120f)
						+ lut(dist(x - cx, y - cy) * 20f - t * 105f);
				int idx = (int) (v * amp + cycle) & (PALETTE - 1);
				plasmaPix[rowBase + x] = paletteRgb[idx];
			}
		}

		g2.drawImage(plasmaImg, 0, 0, w, h, null);
	}

	private void ensurePlasmaRaster(int lw, int lh) {
		if (plasmaImg == null || plasmaW != lw || plasmaH != lh) {
			plasmaImg = new BufferedImage(lw, lh, BufferedImage.TYPE_INT_RGB);
			plasmaPix = ((DataBufferInt) plasmaImg.getRaster().getDataBuffer()).getData();
			plasmaW = lw;
			plasmaH = lh;
		}
	}

	// =========================================================================
	// Copper bars
	// =========================================================================

	/** Amiga copper: horizontal light bars sliding on out-of-phase sines, brightest at their core. */
	private void drawCopperBars(Graphics2D g2, int w, int h, int frameCount) {

		Composite old = g2.getComposite();
		g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));

		int barH = Math.max(6, Math.round(h * 0.10f));

		for (int i = 0; i < COPPER_BARS; i++) {
			float phase = frameCount * (0.013f + i * 0.0024f) + i * 1.7f;
			float yc    = h * 0.5f + (float) Math.sin(phase) * h * 0.36f;
			int   idx   = (int) (frameCount * 2.1f + i * 40 + energy * 200f) & (PALETTE - 1);
			Color core  = paletteHot[idx];

			// Bright core with soft shoulders: three passes, no gradient object per frame.
			for (int pass = 3; pass >= 1; pass--) {
				int    hh = barH * pass / 2;
				int    a  = pass == 1 ? 190 : pass == 2 ? 90 : 45;
				g2.setColor(new Color(core.getRed(), core.getGreen(), core.getBlue(), a));
				g2.fillRect(0, Math.round(yc - hh / 2f), w, Math.max(1, hh));
			}
		}

		g2.setComposite(old);
	}

	// =========================================================================
	// Starfield
	// =========================================================================

	private void resetStars() {
		for (int i = 0; i < STARS; i++) {
			respawnStar(i, rnd.nextFloat());
		}
	}

	private void respawnStar(int i, float z) {
		starX[i] = rnd.nextFloat() * 2f - 1f;
		starY[i] = rnd.nextFloat() * 2f - 1f;
		starZ[i] = Math.max(0.05f, z);
	}

	/** Perspective starfield flying at the viewer; the kick throws it into warp. */
	private void drawStarfield(Graphics2D g2, int w, int h) {

		float cx    = w * 0.5f;
		float cy    = h * 0.5f;
		float scale = Math.min(w, h) * 0.9f;
		float speed = STAR_BASE + energy * 0.020f + warp * 0.055f;

		for (int i = 0; i < STARS; i++) {
			starZ[i] -= speed;
			if (starZ[i] <= 0.03f) {
				respawnStar(i, 1f);
				continue;
			}

			float sx = cx + starX[i] / starZ[i] * scale * 0.30f;
			float sy = cy + starY[i] / starZ[i] * scale * 0.30f;
			if (sx < -8 || sx > w + 8 || sy < -8 || sy > h + 8) {
				continue;
			}

			float near = 1f - starZ[i];                       // 0 far … 1 right in your face
			float size = 1f + near * 3.2f;
			int   a    = clamp255((int) (60 + near * 195));

			// In warp the near stars stretch into streaks, the classic hyperspace tell.
			if (warp > 0.25f && near > 0.45f) {
				float px = cx + starX[i] / (starZ[i] + speed * 3f) * scale * 0.30f;
				float py = cy + starY[i] / (starZ[i] + speed * 3f) * scale * 0.30f;
				g2.setStroke(new BasicStroke(Math.max(1f, size * 0.8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				g2.setColor(new Color(200, 235, 255, a));
				g2.drawLine(Math.round(px), Math.round(py), Math.round(sx), Math.round(sy));
				continue;
			}

			g2.setColor(new Color(255, 255, 255, a));
			g2.fillOval(Math.round(sx - size / 2f), Math.round(sy - size / 2f),
					Math.round(size), Math.round(size));
		}
	}

	// =========================================================================
	// Vector cube
	// =========================================================================

	/** The obligatory rotating wireframe cube: glowing edges, scale thumping on the bass. */
	private void drawCube(Graphics2D g2, int w, int h) {

		cubeAngle += cubeSpin;

		float radius = Math.min(w, h) * 0.16f * (1f + clamp(bass, 0f, 1f) * 0.35f);
		if (radius < 8f) {
			return;
		}

		float cx  = w * 0.5f;
		float cy  = h * 0.44f;
		float ax  = cubeAngle;
		float ay  = cubeAngle * 0.73f;
		float sinX = (float) Math.sin(ax), cosX = (float) Math.cos(ax);
		float sinY = (float) Math.sin(ay), cosY = (float) Math.cos(ay);

		for (int i = 0; i < CUBE_VERTS.length; i++) {
			float x = CUBE_VERTS[i][0];
			float y = CUBE_VERTS[i][1];
			float z = CUBE_VERTS[i][2];

			float y1 = y * cosX - z * sinX;      // pitch
			float z1 = y * sinX + z * cosX;
			float x2 = x * cosY + z1 * sinY;     // yaw
			float z2 = -x * sinY + z1 * cosY;

			float persp = 3.2f / (3.2f + z2);    // weak perspective, strong enough to read as 3D
			projX[i] = cx + x2 * radius * persp;
			projY[i] = cy + y1 * radius * persp;
		}

		Color edge = paletteHot[(int) (cubeAngle * 40f) & (PALETTE - 1)];

		g2.setColor(new Color(edge.getRed(), edge.getGreen(), edge.getBlue(), 70));
		g2.setStroke(new BasicStroke(5.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		strokeCube(g2);

		g2.setColor(new Color(255, 255, 255, 225));
		g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		strokeCube(g2);
	}

	private void strokeCube(Graphics2D g2) {
		for (int[] e : CUBE_EDGES) {
			g2.drawLine(Math.round(projX[e[0]]), Math.round(projY[e[0]]),
					Math.round(projX[e[1]]), Math.round(projY[e[1]]));
		}
	}

	// =========================================================================
	// VU blocks
	// =========================================================================

	/** Spectrum drawn as stacked LED blocks with a peak brick on top — a tracker's VU meter. */
	private void drawVuBlocks(Graphics2D g2, int w, int h) {

		int   rows  = Math.max(4, Math.round(h * 0.30f / 6f));
		float slotW = (float) w / BARS;
		float blockW = Math.max(2f, slotW - 2f);
		float blockH = Math.max(2f, h * 0.30f / rows - 1f);
		float baseY  = h - 3f;

		for (int b = 0; b < BARS; b++) {
			int lit = Math.round(clamp(barLevel[b], 0f, 1f) * rows);
			float x = b * slotW + (slotW - blockW) * 0.5f;

			for (int r = 0; r < rows; r++) {
				float y = baseY - (r + 1) * (blockH + 1f);
				boolean on = r < lit;
				// Green at the bottom through amber to red at the top, like every hardware VU ever.
				float hue = 0.33f * (1f - (float) r / rows);
				Color c = on
						? hsba(hue, 0.95f, 1f, 235)
						: hsba(hue, 0.75f, 0.30f, 70);
				g2.setColor(c);
				g2.fillRect(Math.round(x), Math.round(y), Math.round(blockW), Math.round(blockH));
			}

			int peakRow = Math.round(clamp(barPeak[b], 0f, 1f) * rows) - 1;
			if (peakRow >= 0) {
				float y = baseY - (peakRow + 1) * (blockH + 1f);
				g2.setColor(Color.WHITE);
				g2.fillRect(Math.round(x), Math.round(y), Math.round(blockW), Math.max(1, Math.round(blockH * 0.45f)));
			}
		}
	}

	// =========================================================================
	// Sine scroller
	// =========================================================================

	/** Rainbow sine scroller: each glyph rides its own point on the wave, as tradition demands. */
	private void drawScroller(Graphics2D g2, int w, int h, int frameCount) {

		int size = Math.round(clamp(h * 0.16f, 11f, 30f));
		if (scrollFont == null || scrollFontSize != size) {
			scrollFontSize = size;
			scrollFont = new Font(Font.MONOSPACED, Font.BOLD, size);
			logoFont   = new Font(Font.MONOSPACED, Font.BOLD, Math.round(size * 1.15f));
		}

		g2.setFont(scrollFont);
		FontMetrics fm = g2.getFontMetrics();

		if (Float.isNaN(scrollX)) {
			scrollX = w;
		}
		scrollX -= 2.2f + energy * 2.6f;   // the louder it gets, the harder it scrolls

		int textW = fm.stringWidth(scrollText);
		if (scrollX < -textW) {
			scrollX = w;                   // wrap round for another lap
		}

		float baseY = h * 0.70f;
		float x     = scrollX;

		for (int i = 0; i < scrollText.length(); i++) {
			char ch = scrollText.charAt(i);
			int  cw = fm.charWidth(ch);

			if (x > -cw && x < w) {        // only glyphs actually on screen cost anything
				float wave = (float) Math.sin(frameCount * 0.06 + i * 0.42) * h * 0.10f;
				float y    = baseY + wave;
				float hue  = ((frameCount * 0.004f) + i * 0.02f) % 1f;

				g2.setColor(new Color(0, 0, 0, 170));
				g2.drawString(String.valueOf(ch), x + 2f, y + 2f);
				g2.setColor(hsba(hue, 0.80f, 1f, 255));
				g2.drawString(String.valueOf(ch), x, y);
			}

			x += cw;
			if (x > w) {
				break;                     // everything after this is off the right edge
			}
		}
	}

	// =========================================================================
	// Logo / overlays
	// =========================================================================

	/** Bobbing, beat-punched logo with a hard drop shadow — top-left, where a logo belongs. */
	private void drawLogo(Graphics2D g2, int w, int h, int frameCount) {

		if (logoFont == null) {
			return;
		}

		float pop = 1f + flash * 0.22f;
		float bob = (float) Math.sin(frameCount * 0.045) * h * 0.02f;

		g2.setFont(logoFont.deriveFont(logoFont.getSize2D() * pop));
		FontMetrics fm = g2.getFontMetrics();

		String logo = "▲ NUCLR";
		float  x    = 8f;
		float  y    = fm.getAscent() + 4f + bob;

		g2.setColor(new Color(0, 0, 0, 190));
		g2.drawString(logo, x + 2f, y + 2f);
		g2.setColor(paletteHot[(int) (frameCount * 2.4f) & (PALETTE - 1)]);
		g2.drawString(logo, x, y);
	}

	/** CRT scanlines: the cheapest way to make anything look like it came off a 15 kHz monitor. */
	private void drawScanlines(Graphics2D g2, int w, int h) {
		g2.setColor(new Color(0, 0, 0, 46));
		for (int y = 0; y < h; y += 3) {
			g2.fillRect(0, y, w, 1);
		}
	}

	private void drawFlash(Graphics2D g2, int w, int h) {
		if (flash <= 0.02f) {
			return;
		}
		g2.setColor(new Color(255, 255, 255, clamp255((int) (flash * 90f))));
		g2.fillRect(0, 0, w, h);
	}

	// =========================================================================
	// Tables & helpers
	// =========================================================================

	/**
	 * A 256-entry cycling palette in four movements — magenta, cyan, amber, back to indigo —
	 * so palette rotation reads as a colour sweep rather than a rainbow blur.
	 */
	private void buildPalette() {
		for (int i = 0; i < PALETTE; i++) {
			float t   = (float) i / PALETTE;
			float hue = (0.72f + t * 0.85f) % 1f;                             // violet → cyan → amber
			float sat = 0.72f + 0.28f * (float) Math.sin(t * Math.PI * 2);
			// Deliberately dim: this palette paints the backdrop, and a backdrop that is as bright
			// as the scroller, the cube and the bars just swallows all three.
			float bri = 0.16f + 0.42f * (float) Math.abs(Math.sin(t * Math.PI * 2 + 0.6));
			Color c   = Color.getHSBColor(hue, clamp(sat, 0f, 1f), clamp(bri, 0f, 1f));
			palette[i]    = c;
			paletteRgb[i] = c.getRGB();
			// Same hue sweep at full brightness, for the elements that sit on top of the plasma.
			paletteHot[i] = Color.getHSBColor(hue, clamp(sat * 0.9f, 0f, 1f), 1f);
		}
	}

	private void buildBarBins() {
		float logMin  = (float) Math.log(40f);
		float logMax  = (float) Math.log(16000f);
		int   nyquist = FFT_SIZE / 2;
		for (int b = 0; b <= BARS; b++) {
			float freq = (float) Math.exp(logMin + (logMax - logMin) * b / BARS);
			barBin[b] = Math.max(1, Math.min(nyquist, Math.round(freq * FFT_SIZE / SAMPLE_RATE)));
		}
		for (int b = 1; b <= BARS; b++) {
			if (barBin[b] <= barBin[b - 1]) {
				barBin[b] = Math.min(nyquist, barBin[b - 1] + 1);
			}
		}
	}

	/** Table lookup standing in for {@code Math.sin}, indexed in table units rather than radians. */
	private float lut(float index) {
		return sin[((int) index) & SIN_MASK];
	}

	private static float dist(int dx, int dy) {
		return (float) Math.sqrt(dx * dx + dy * dy);
	}

	private static Color hsba(float h, float s, float b, int a) {
		h = ((h % 1f) + 1f) % 1f;
		Color c = Color.getHSBColor(h, clamp(s, 0f, 1f), clamp(b, 0f, 1f));
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), clamp255(a));
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int clamp255(int a) {
		return Math.max(0, Math.min(255, a));
	}
}
