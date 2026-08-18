package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * "Second Horizon" visualizer — an original piece in the house style of the 1993 PC demo, built
 * from the era's stock effects and none of anyone else's pixels.
 * <p>
 * A demo of that period was never one effect but a <b>sequence of parts</b>, and the good ones cut
 * those parts to the music at a time when almost nothing else did. This visualizer works the same
 * way: six scenes run one after another, and a scene only hands over to the next <em>on a kick
 * drum</em>, once it has had its minimum time on screen — so the whole show breathes with the tune.
 * <ol>
 *   <li><b>Starfield</b> — stars flying at the viewer, with the group logo zooming in. Demos of
 *       the day took a command-line argument to jump straight to a part; so does the HUD here.</li>
 *   <li><b>Glenz vectors</b> — a translucent rotating solid over copper bars, the 1993 way of
 *       proving your 486 could do real-time 3D.</li>
 *   <li><b>Moire</b> — three interfering ring fields, XOR-combined through a cycling palette.</li>
 *   <li><b>Dot morph</b> — a dot object melting between sphere, torus and tunnel, over its own
 *       phosphor trails.</li>
 *   <li><b>Warp &amp; scale</b> — a perspective checkerboard plane under a bitmap logo warped and
 *       scaled column by column, the way a still image was made to ripple back then.</li>
 *   <li><b>Kefrens finale</b> — the scanline bar cascade, a sine scroller, and a live oscilloscope
 *       trace of what SDL is actually playing.</li>
 * </ol>
 * Everything is drawn into one small {@code int[]} raster (a stand-in for VGA mode 13h) and blown
 * up with nearest-neighbour sampling — which is both how it was done and why a 386 could keep up.
 * Vectors, text and the scope go on top with Java2D. Tables, palettes, star and dot arrays are all
 * pre-allocated, so a frame allocates next to nothing and the EDT stays at 60 fps.
 *
 * @see WaveformPanel.VisualizerMode#SECOND_HORIZON
 */
final class SecondHorizonVisualizer {

	// ---- Analysis ----
	private static final int   FFT_SIZE    = 1024;
	private static final int   SAMPLE_RATE = 44100;
	private static final int   BANDS       = 24;
	private static final int   BASS_BINS   = 5;     // ~0-215 Hz: the kick lives here
	private static final float BEAT_FACTOR = 1.30f; // bass must exceed its running mean by this much
	private static final int   BEAT_HOLD   = 7;     // frames of cooldown, so one thump is one beat

	// ---- Raster ----
	private static final int PIXEL  = 3;    // chunky-pixel size; the whole point
	private static final int MAX_LW = 460;  // caps the per-pixel work on a maximised panel
	private static final int MAX_LH = 300;

	private static final int PALETTE  = 256;
	private static final int PAL_MASK = PALETTE - 1;

	// ---- Scenes ----
	private static final int SCENE_STARS   = 0;
	private static final int SCENE_GLENZ   = 1;
	private static final int SCENE_MOIRE   = 2;
	private static final int SCENE_DOTS    = 3;
	private static final int SCENE_WARP    = 4;
	private static final int SCENE_COUNT   = 6;

	private static final int SCENE_MIN_FRAMES  = 13 * 60; // earliest a part may hand over...
	private static final int SCENE_MAX_FRAMES  = 19 * 60; // ...and where it stops waiting for a kick
	private static final int TRANSITION_FRAMES = 26;

	private static final String[] SCENE_NAMES = {
			"STARFIELD", "GLENZ VECTORS", "MOIRE", "DOT MORPH", "WARP & SCALE", "KEFRENS"
	};
	/** Period demos took a digit to start from a given part, and a letter for the bonus one. */
	private static final String[] SCENE_ARGS = { "u", "2", "3", "4", "5", "" };

	// ---- Starfield ----
	private static final int   STARS      = 340;
	private static final float STAR_SPEED = 0.0085f;
	private static final float WARP_DECAY = 0.91f;

	// ---- Dot object ----
	private static final int DOTS   = 820;
	private static final int SHAPES = 3;

	// ---- Scroller ----
	private static final String GREETINGS =
			"NUCLR CREW - SURREAL ][ - THE 2ND HORIZON - MODE 13H, 1993   ...   "
			+ "SIX PARTS, EVERY ONE OF THEM CUT TO THE MUSIC, THE WAY IT WAS DONE   ...   "
			+ "CODE, GRAPHICS AND QUESTIONABLE IDEAS: THE USUAL SUSPECTS   ...   "
			+ "TESTED ON A 486 DX2/66 WITH A 16-BIT WAVETABLE CARD, YOUR MILEAGE MAY VARY   ...   "
			+ "NO GPU WAS HARMED IN THE MAKING OF THIS SCROLLER, EVERY PIXEL IS PLOTTED BY HAND   ...   "
			+ "GREETINGS TO EVERY SCENER STILL COUNTING SCANLINES   ...   ";

	// ---- Strokes (pre-allocated) ----
	private static final BasicStroke WIRE_STROKE = new BasicStroke(1.4f);
	private static final BasicStroke EDGE_STROKE = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke SCOPE_GLOW  = new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke SCOPE_LINE  = new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

	// ---- Tables ----
	private final float[] hann      = new float[FFT_SIZE];
	private final int[]   bandBin   = new int[BANDS + 1];
	private final int[]   palPlasma = new int[PALETTE];   // violet -> cyan -> amber, cyclic
	private final int[]   palIce    = new int[PALETTE];   // deep blue -> cyan -> white, cyclic
	private final int[]   palFire   = new int[PALETTE];   // black -> red -> yellow -> white, cyclic
	private final Color[] hot       = new Color[PALETTE]; // the same sweep at full brightness, for Java2D

	// ---- FFT buffers ----
	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private int scopeSamples;

	// ---- Band state ----
	private final float[] bandRaw   = new float[BANDS];
	private final float[] bandLevel = new float[BANDS];
	private float autoGain = 1f;
	private float energy;
	private float bassRaw;              // smoothed bass magnitude, in the FFT's own units
	private float bassMean = 1e-3f;     // its slow running mean — the beat threshold rides on this
	private float bass;                 // bassRaw normalised against the mean: ~0.5 idle, ~1.4 on a kick
	private float scopePeak = 0.3f;     // smoothed waveform peak, so the scope self-normalises

	// ---- Beat ----
	private boolean beatNow;
	private int     beatCooldown;
	private int     beatCount;
	private float   flash;   // white-out, decays every frame
	private float   warp;    // starfield kick, decays every frame

	// ---- Raster ----
	private BufferedImage img;
	private int[] pix;
	private int   lw, lh;

	// ---- Scene state ----
	private int scene = SCENE_STARS;
	private int sceneFrame;
	private int transition;

	// ---- Starfield ----
	private final float[] starX = new float[STARS];
	private final float[] starY = new float[STARS];
	private final float[] starZ = new float[STARS];

	// ---- Glenz solid: an octahedron, the classic transparent gem, inside a wireframe cube ----
	private static final float[][] OCTA_V = {
			{ 1, 0, 0}, {-1, 0, 0}, {0,  1, 0}, {0, -1, 0}, {0, 0,  1}, {0, 0, -1}
	};
	private static final int[][] OCTA_F = {
			{0, 2, 4}, {2, 1, 4}, {1, 3, 4}, {3, 0, 4},
			{2, 0, 5}, {1, 2, 5}, {3, 1, 5}, {0, 3, 5}
	};
	private static final float[][] CUBE_V = {
			{-1, -1, -1}, {1, -1, -1}, {1, 1, -1}, {-1, 1, -1},
			{-1, -1,  1}, {1, -1,  1}, {1, 1,  1}, {-1, 1,  1}
	};
	private static final int[][] CUBE_E = {
			{0, 1}, {1, 2}, {2, 3}, {3, 0}, {4, 5}, {5, 6}, {6, 7}, {7, 4},
			{0, 4}, {1, 5}, {2, 6}, {3, 7}
	};
	private final float[] octaSx    = new float[OCTA_V.length];
	private final float[] octaSy    = new float[OCTA_V.length];
	private final float[] octaZ     = new float[OCTA_V.length];
	private final float[] cubeSx    = new float[CUBE_V.length];
	private final float[] cubeSy    = new float[CUBE_V.length];
	private final int[]   faceOrder = new int[OCTA_F.length];
	private final float[] faceDepth = new float[OCTA_F.length];
	private final int[]   triX      = new int[3];
	private final int[]   triY      = new int[3];
	private float angX, angY, angZ;
	private float spin = 0.011f;

	// ---- Dot object ----
	private final float[][] shapeX = new float[SHAPES][DOTS];
	private final float[][] shapeY = new float[SHAPES][DOTS];
	private final float[][] shapeZ = new float[SHAPES][DOTS];
	private int   shapeFrom, shapeTo = 1;
	private float morph;

	// ---- Warped bitmap logo (rendered once into a small ARGB raster) ----
	private BufferedImage logoImg;
	private int[] logoPix;
	private int   logoW, logoH;
	private int   logoBuiltFor = -1;

	// ---- Scroller ----
	private char[] scrollChars = GREETINGS.toCharArray();
	private float  scrollX = Float.NaN;   // NaN = start off the right edge
	private int    scrollWidth;
	private Font   scrollWidthFont;

	// ---- Oscilloscope ----
	private static final int SCOPE_POINTS = 192;
	private final int[] scopeX = new int[SCOPE_POINTS];
	private final int[] scopeY = new int[SCOPE_POINTS];

	// ---- Fonts (rebuilt only when the panel is resized) ----
	private Font bigFont, midFont, smallFont, scrollFont;
	private int  fontsFor = -1;

	private final Random rnd = new Random(0x2DEC1993L);

	SecondHorizonVisualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		buildBandBins();
		buildPalettes();
		buildShapes();
		resetStars();
	}

	/** Name the tune in the finale's scroller, the way a demo announces the module it is playing. */
	void setTrackTitle(String title) {
		String tune = title == null || title.isBlank() ? null : title.toUpperCase();
		scrollChars = (tune == null ? GREETINGS : "NOW PLAYING: " + tune + "   ...   " + GREETINGS)
				.toCharArray();
		scrollX         = Float.NaN;
		scrollWidthFont = null;   // force a re-measure against the new text
	}

	// =========================================================================
	// Frame
	// =========================================================================

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {

		analyze(ring);
		ensureRaster(w, h);
		ensureFonts(h);
		advanceScene();

		// Nearest-neighbour: the blown-up raster has to stay hard-edged, not smeared.
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		switch (scene) {
			case SCENE_STARS -> {
				rasterStarfield();
				blit(g2, w, h);
				drawIntroTitles(g2, w, h);
			}
			case SCENE_GLENZ -> {
				rasterCopperSky(frameCount);
				blit(g2, w, h);
				drawGlenz(g2, w, h);
			}
			case SCENE_MOIRE -> {
				rasterMoire(frameCount);
				blit(g2, w, h);
			}
			case SCENE_DOTS -> {
				rasterDots(frameCount);
				blit(g2, w, h);
			}
			case SCENE_WARP -> {
				rasterLandscape(frameCount);
				blit(g2, w, h);
			}
			default -> {
				rasterKefrens(frameCount);
				blit(g2, w, h);
				drawScope(g2, w, h);
				drawScroller(g2, w, h, frameCount);
				drawFinaleLogo(g2, w, h);
			}
		}

		drawTransition(g2, w, h);
		drawScanlines(g2, w, h);
		drawHud(g2, w, h, frameCount);
		drawFlash(g2, w, h);

		flash *= 0.84f;
		warp  *= WARP_DECAY;
		if (beatCooldown > 0) beatCooldown--;
		if (transition > 0) transition--;
		sceneFrame++;
	}

	/** A part hands over on a kick once it has had its time — that is the whole trick of the form. */
	private void advanceScene() {
		boolean onCue = sceneFrame > SCENE_MIN_FRAMES && beatNow;
		if (!onCue && sceneFrame <= SCENE_MAX_FRAMES) {
			return;
		}
		scene      = (scene + 1) % SCENE_COUNT;
		sceneFrame = 0;
		transition = TRANSITION_FRAMES;
		flash      = Math.max(flash, 0.85f);
		scrollX    = Float.NaN;
		Arrays.fill(pix, 0);
		if (scene == SCENE_STARS) {
			resetStars();
		}
	}

	// =========================================================================
	// Analysis
	// =========================================================================

	private void analyze(AudioRingBuffer ring) {

		beatNow = false;
		int samples = ring != null ? ring.snapshot(snapshot, FFT_SIZE) : 0;
		scopeSamples = samples;

		if (samples < BANDS * 2) {
			// Silence: let it all sag, but keep the demo idling rather than freezing.
			for (int b = 0; b < BANDS; b++) bandLevel[b] *= 0.90f;
			energy += (0f - energy) * 0.08f;
			bass   += (0f - bass) * 0.08f;
			return;
		}

		float peak = 0f;
		for (int i = 0; i < samples; i++) {
			float v = Math.abs(snapshot[i]);
			if (v > peak) peak = v;
		}
		scopePeak += (peak - scopePeak) * (peak > scopePeak ? 0.50f : 0.03f);

		int pad = FFT_SIZE - samples;
		for (int i = 0; i < FFT_SIZE; i++) {
			float s = i >= pad ? snapshot[i - pad] : 0f;
			re[i] = s * hann[i];
			im[i] = 0f;
		}
		Fft.transform(re, im, FFT_SIZE);

		float maxMag = 1e-4f;
		float sum    = 0f;
		for (int b = 0; b < BANDS; b++) {
			float power = 0f;
			for (int k = bandBin[b]; k < bandBin[b + 1]; k++) {
				float p = re[k] * re[k] + im[k] * im[k];
				if (p > power) power = p;   // peak-pick the band: punchier than averaging it
			}
			// Tilt the top end up so hats and leads stay visible next to the bass.
			bandRaw[b] = (float) Math.sqrt(power) / FFT_SIZE * (1f + 1.3f * b / BANDS);
			if (bandRaw[b] > maxMag) maxMag = bandRaw[b];
		}

		float targetGain = clamp(0.85f / maxMag, 0.5f, 60f);
		autoGain += (targetGain - autoGain) * (targetGain < autoGain ? 0.20f : 0.05f);

		for (int b = 0; b < BANDS; b++) {
			float level = clamp((float) Math.pow(bandRaw[b] * autoGain, 0.70), 0f, 1.3f);
			float shown = bandLevel[b];
			bandLevel[b] = level > shown ? level : shown + (level - shown) * 0.22f;
			sum += bandLevel[b];
		}
		energy += (sum / BANDS - energy) * 0.20f;

		detectBeat();
	}

	/**
	 * Kick detector: bass well above its own running mean, rate-limited so one thump is one beat.
	 * <p>
	 * The comparison deliberately runs on the <em>raw</em> magnitude rather than on anything
	 * clamped or auto-gained. A clamped level pins at its ceiling on loud music, the running mean
	 * climbs to meet it, and the detector goes silent for the rest of the tune. What the visuals
	 * want instead is a ratio, so the level handed to them is the raw bass divided by its own
	 * mean — which is bounded, scale-free, and holds up from a quiet MOD to a loud MP3 alike.
	 */
	private void detectBeat() {

		float b = 0f;
		for (int k = 1; k <= BASS_BINS; k++) {
			b += (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) / FFT_SIZE;
		}
		bassRaw += (b - bassRaw) * 0.45f;
		bass = clamp(bassRaw / (bassMean * 2f + 1e-6f), 0f, 1.4f);

		if (bassRaw > bassMean * BEAT_FACTOR && beatCooldown == 0 && bassRaw > 2e-4f) {
			beatNow      = true;
			beatCooldown = BEAT_HOLD;
			beatCount++;
			flash = Math.min(1f, 0.28f + bass * 0.45f);
			warp  = Math.min(1f, 0.55f + bass);
			spin  = 0.052f;                        // kick the solid into a spin...
			if ((beatCount & 7) == 0) nextShape(); // ...and morph the dot object every eighth beat
		} else {
			spin += (0.011f - spin) * 0.06f;       // ...which eases back to a lazy drift
		}

		bassMean += (bassRaw - bassMean) * 0.02f;   // ~0.8 s baseline: slow enough to survive a kick
	}

	// =========================================================================
	// Scene 1 — starfield ("HORIZON.EXE u")
	// =========================================================================

	/** Stars flying at the viewer, streaked when the kick pushes the warp up. */
	private void rasterStarfield() {

		Arrays.fill(pix, 0x00030A);

		float speed = STAR_SPEED + warp * 0.030f + energy * 0.008f;
		float cx    = lw * 0.5f;
		float cy    = lh * 0.5f;
		float fov   = lw * 0.62f;

		for (int i = 0; i < STARS; i++) {
			starZ[i] -= speed;
			if (starZ[i] <= 0.04f) respawnStar(i, false);

			float z  = starZ[i];
			float px = cx + starX[i] / z * fov;
			float py = cy + starY[i] / z * fov;
			if (px < 0 || py < 0 || px >= lw || py >= lh) {
				// Off the edge and still far away: it will never come back, so recycle it.
				if (z > 0.85f) respawnStar(i, true);
				continue;
			}

			float b   = clamp(1.05f - z, 0f, 1f);
			int   col = scaleRgb(mixRgb(0xFFFFFF, 0x88C8FF, (i & 7) / 7f), 0.25f + 0.75f * b);

			// The streak is just the same star re-plotted a few frames back along its own radius.
			int steps = 1 + (int) (warp * 7f);
			for (int s = 0; s < steps; s++) {
				float zs = z + speed * s * 2.2f;
				int   sx = Math.round(cx + starX[i] / zs * fov);
				int   sy = Math.round(cy + starY[i] / zs * fov);
				if (sx < 0 || sy < 0 || sx >= lw || sy >= lh) break;
				plot(sx, sy, s == 0 ? col : scaleRgb(col, 1f - s / (float) (steps + 1)));
			}
			if (b > 0.62f) {   // the near ones are fat pixels
				plot((int) px + 1, (int) py, scaleRgb(col, 0.7f));
				plot((int) px, (int) py + 1, scaleRgb(col, 0.7f));
			}
		}
	}

	private void resetStars() {
		for (int i = 0; i < STARS; i++) {
			respawnStar(i, false);
			starZ[i] = 0.05f + rnd.nextFloat() * 0.95f;   // spread them down the tunnel straight away
		}
	}

	private void respawnStar(int i, boolean nearCentre) {
		float spread = nearCentre ? 0.35f : 1f;
		starX[i] = (rnd.nextFloat() * 2f - 1f) * spread;
		starY[i] = (rnd.nextFloat() * 2f - 1f) * spread;
		starZ[i] = 1f;
	}

	/** The opening credit roll: group, "presents", then the title the demo actually shipped under. */
	private void drawIntroTitles(Graphics2D g2, int w, int h) {

		float a1 = window(sceneFrame,   0, 250, 30);
		float a2 = window(sceneFrame, 255, 430, 30);
		float a3 = window(sceneFrame, 440, Integer.MAX_VALUE - 1, 40);

		if (a1 > 0f) {
			drawGlowText(g2, "NUCLR CREW", bigFont, w * 0.5f, h * 0.48f, a1, 1f + flash * 0.10f);
		}
		if (a2 > 0f) {
			drawGlowText(g2, "p r e s e n t s", midFont, w * 0.5f, h * 0.50f, a2, 1f);
		}
		if (a3 > 0f) {
			drawGlowText(g2, "SURREAL ][", midFont, w * 0.5f, h * 0.40f, a3, 1f);
			drawGlowText(g2, "THE 2ND HORIZON", bigFont, w * 0.5f, h * 0.58f, a3, 1f + flash * 0.14f);
		}
	}

	// =========================================================================
	// Scene 2 — glenz vectors over copper bars
	// =========================================================================

	private void rasterCopperSky(int frameCount) {

		for (int y = 0; y < lh; y++) {
			int rgb = mixRgb(0x0A0520, 0x02030C, y / (float) lh);
			Arrays.fill(pix, y * lw, y * lw + lw, rgb);
		}

		// A scatter of fixed stars behind everything, so the sky is not dead.
		for (int i = 0; i < STARS / 3; i++) {
			int x = (i * 7919) % lw;
			int y = (i * 6151) % lh;
			plot(x, y, scaleRgb(0x9FC8FF, 0.25f + 0.35f * ((i * 13 % 7) / 7f)));
		}

		int bars = 5;
		int barH = Math.max(2, lh / 13);
		for (int i = 0; i < bars; i++) {
			float phase = frameCount * (0.017f + i * 0.0031f) + i * 1.9f;
			int   yc    = Math.round(lh * 0.5f + (float) Math.sin(phase) * lh * 0.40f);
			int   col   = palPlasma[(int) (frameCount * 2.3f + i * 46 + energy * 200f) & PAL_MASK];
			for (int dy = -barH; dy <= barH; dy++) {
				int y = yc + dy;
				if (y < 0 || y >= lh) continue;
				float k   = 1f - Math.abs(dy) / (float) barH;
				int   c   = scaleRgb(col, 0.20f + 0.80f * k * k);   // bright core, soft shoulders
				int   row = y * lw;
				for (int x = 0; x < lw; x++) pix[row + x] = addRgb(pix[row + x], c);
			}
		}
	}

	/**
	 * A glenz solid: every face drawn back to front and translucent, so the far faces show through
	 * the near ones. In 1993 this needed a hand-written span filler; here Java2D does the spans and
	 * the depth sort is eight faces long.
	 */
	private void drawGlenz(Graphics2D g2, int w, int h) {

		angX += spin * 0.7f;
		angY += spin;
		angZ += spin * 0.4f;

		float cx = w * 0.5f;
		float cy = h * 0.5f;
		float r  = Math.min(w, h) * (0.30f + bass * 0.09f + flash * 0.05f);

		rotate(OCTA_V, angX, angY, angZ, r, cx, cy, octaSx, octaSy, octaZ);
		rotate(CUBE_V, -angY * 0.8f, angX * 0.6f, -angZ, r * 0.78f, cx, cy, cubeSx, cubeSy, null);
		sortFaces();

		Composite old      = g2.getComposite();
		Stroke    oldStr   = g2.getStroke();
		Composite fillComp = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp(0.34f + flash * 0.10f, 0f, 1f));
		Composite edgeComp = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.75f);

		// Wireframe cube around the gem — a second object, because one was never enough.
		g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
		g2.setStroke(WIRE_STROKE);
		g2.setColor(hot[(int) (angY * 260f) & PAL_MASK]);
		for (int[] e : CUBE_E) {
			g2.drawLine(Math.round(cubeSx[e[0]]), Math.round(cubeSy[e[0]]),
					Math.round(cubeSx[e[1]]), Math.round(cubeSy[e[1]]));
		}

		g2.setStroke(EDGE_STROKE);
		for (int i = 0; i < faceOrder.length; i++) {
			int[] tri = OCTA_F[faceOrder[i]];
			for (int v = 0; v < 3; v++) {
				triX[v] = Math.round(octaSx[tri[v]]);
				triY[v] = Math.round(octaSy[tri[v]]);
			}
			Color face = hot[(int) (faceOrder[i] * 30 + angY * 180f + energy * 150f) & PAL_MASK];
			g2.setComposite(fillComp);
			g2.setColor(face);
			g2.fillPolygon(triX, triY, 3);
			g2.setComposite(edgeComp);
			g2.setColor(brighter(face));
			g2.drawPolygon(triX, triY, 3);
		}

		g2.setComposite(old);
		g2.setStroke(oldStr);
	}

	/** Insertion sort, far faces first: eight entries, and near-sorted from one frame to the next. */
	private void sortFaces() {
		for (int f = 0; f < OCTA_F.length; f++) {
			int[] tri = OCTA_F[f];
			faceDepth[f] = (octaZ[tri[0]] + octaZ[tri[1]] + octaZ[tri[2]]) / 3f;
			faceOrder[f] = f;
		}
		for (int i = 1; i < faceOrder.length; i++) {
			int   key = faceOrder[i];
			float d   = faceDepth[key];
			int   j   = i - 1;
			while (j >= 0 && faceDepth[faceOrder[j]] < d) {
				faceOrder[j + 1] = faceOrder[j];
				j--;
			}
			faceOrder[j + 1] = key;
		}
	}

	/** Rotate a unit model on three axes and project it; {@code depth} may be null when unused. */
	private void rotate(float[][] verts, float ax, float ay, float az, float r,
			float cx, float cy, float[] outX, float[] outY, float[] depth) {

		float sx = (float) Math.sin(ax), cxr = (float) Math.cos(ax);
		float sy = (float) Math.sin(ay), cyr = (float) Math.cos(ay);
		float sz = (float) Math.sin(az), czr = (float) Math.cos(az);

		for (int i = 0; i < verts.length; i++) {
			float x = verts[i][0], y = verts[i][1], z = verts[i][2];
			float y1 =  y * cxr - z * sx;
			float z1 =  y * sx  + z * cxr;
			float x2 =  x * cyr + z1 * sy;
			float z2 = -x * sy  + z1 * cyr;
			float x3 =  x2 * czr - y1 * sz;
			float y3 =  x2 * sz  + y1 * czr;

			float persp = 2.7f / (4.0f + z2);
			outX[i] = cx + x3 * r * persp;
			outY[i] = cy + y3 * r * persp;
			if (depth != null) depth[i] = z2;
		}
	}

	// =========================================================================
	// Scene 3 — moire
	// =========================================================================

	/**
	 * Three concentric ring fields on Lissajous paths. Two of them are XOR-ed — which is what makes
	 * the fringes hard-edged instead of a soft blur — and the third is added on top to break the
	 * symmetry. The palette index moves with the music, so the whole field pulses in time.
	 */
	private void rasterMoire(int frameCount) {

		float t   = frameCount * 0.012f;
		float amp = 0.30f + bass * 0.12f;

		float c1x = lw * 0.5f + (float) Math.sin(t * 1.10) * lw * amp;
		float c1y = lh * 0.5f + (float) Math.cos(t * 0.83) * lh * amp;
		float c2x = lw * 0.5f - (float) Math.sin(t * 0.77) * lw * amp;
		float c2y = lh * 0.5f - (float) Math.cos(t * 1.23) * lh * amp;
		float c3x = lw * 0.5f + (float) Math.sin(t * 0.51 + 1.7) * lw * amp * 0.6f;
		float c3y = lh * 0.5f + (float) Math.sin(t * 0.94 + 0.4) * lh * amp * 0.6f;

		float f     = 1.15f + energy * 1.2f;                   // ring spacing
		int   cycle = (int) (frameCount * 2.2f + beatCount * 26);
		int[] pal   = switch ((beatCount / 24) % 3) {
			case 0  -> palIce;
			case 1  -> palPlasma;
			default -> palFire;
		};

		for (int y = 0; y < lh; y++) {
			float dy1 = y - c1y, dy2 = y - c2y, dy3 = y - c3y;
			float q1 = dy1 * dy1, q2 = dy2 * dy2, q3 = dy3 * dy3;
			int   row = y * lw;
			for (int x = 0; x < lw; x++) {
				float dx1 = x - c1x, dx2 = x - c2x, dx3 = x - c3x;
				int r1 = (int) (Math.sqrt(dx1 * dx1 + q1) * f);
				int r2 = (int) (Math.sqrt(dx2 * dx2 + q2) * f);
				int r3 = (int) (Math.sqrt(dx3 * dx3 + q3) * f * 0.5f);
				pix[row + x] = pal[((r1 ^ r2) + r3 + cycle) & PAL_MASK];
			}
		}
	}

	// =========================================================================
	// Scene 4 — morphing dot object
	// =========================================================================

	/** Dots melting between sphere, torus and tunnel, over their own phosphor trails. */
	private void rasterDots(int frameCount) {

		// Halving every channel each frame leaves a decaying trail behind every dot.
		for (int i = 0; i < pix.length; i++) pix[i] = (pix[i] >>> 1) & 0x7F7F7F;

		morph = Math.min(1f, morph + 0.018f);
		float m = smoothStep(morph);

		angX += 0.009f + bass * 0.010f;
		angY += 0.013f + energy * 0.012f;

		float sxr = (float) Math.sin(angX), cxr = (float) Math.cos(angX);
		float syr = (float) Math.sin(angY), cyr = (float) Math.cos(angY);

		float cx = lw * 0.5f;
		float cy = lh * 0.5f;
		float r  = Math.min(lw, lh) * (0.36f + bass * 0.08f);

		float[] ax = shapeX[shapeFrom], ay = shapeY[shapeFrom], az = shapeZ[shapeFrom];
		float[] bx = shapeX[shapeTo],   by = shapeY[shapeTo],   bz = shapeZ[shapeTo];

		for (int i = 0; i < DOTS; i++) {
			float x = ax[i] + (bx[i] - ax[i]) * m;
			float y = ay[i] + (by[i] - ay[i]) * m;
			float z = az[i] + (bz[i] - az[i]) * m;

			float y1 =  y * cxr - z * sxr;
			float z1 =  y * sxr + z * cxr;
			float x2 =  x * cyr + z1 * syr;
			float z2 = -x * syr + z1 * cyr;

			float persp = 2.6f / (3.6f + z2);
			int   px = Math.round(cx + x2 * r * persp);
			int   py = Math.round(cy + y1 * r * persp);
			if (px < 0 || py < 0 || px >= lw || py >= lh) continue;

			float near = clamp((1.6f - z2) * 0.4f, 0f, 1f);
			int   col  = palFire[(int) (near * 110f + 40f + frameCount * 0.8f) & PAL_MASK];
			pix[py * lw + px] = col;
			if (near > 0.62f) {   // near dots are fat, so the object reads as solid
				plot(px + 1, py, scaleRgb(col, 0.8f));
				plot(px, py + 1, scaleRgb(col, 0.8f));
				plot(px + 1, py + 1, scaleRgb(col, 0.6f));
			}
		}
	}

	private void nextShape() {
		shapeFrom = shapeTo;
		shapeTo   = (shapeTo + 1) % SHAPES;
		morph     = 0f;
	}

	/** Sphere, torus and tunnel, all with the same point count so any pair can be interpolated. */
	private void buildShapes() {
		float golden = (float) (Math.PI * (3.0 - Math.sqrt(5.0)));
		for (int i = 0; i < DOTS; i++) {
			// 0: Fibonacci sphere — an even scatter, with no clumping at the poles.
			float sy = 1f - (i / (float) (DOTS - 1)) * 2f;
			float sr = (float) Math.sqrt(Math.max(0f, 1f - sy * sy));
			float th = golden * i;
			shapeX[0][i] = (float) Math.cos(th) * sr;
			shapeY[0][i] = sy;
			shapeZ[0][i] = (float) Math.sin(th) * sr;

			// 1: torus
			float u  = (float) (i * 2 * Math.PI / 41.0);
			float v  = (float) (i * 2 * Math.PI / DOTS * 7.0);
			float tr = 0.68f + 0.30f * (float) Math.cos(v);
			shapeX[1][i] = tr * (float) Math.cos(u);
			shapeY[1][i] = 0.30f * (float) Math.sin(v);
			shapeZ[1][i] = tr * (float) Math.sin(u);

			// 2: tunnel — a cylinder down the z axis, the dots flying past the camera
			float a = (float) (i * 2 * Math.PI / 23.0);
			shapeX[2][i] = (float) Math.cos(a) * 0.85f;
			shapeY[2][i] = (float) Math.sin(a) * 0.85f;
			shapeZ[2][i] = (i / (float) DOTS) * 3.4f - 1.7f;
		}
	}

	// =========================================================================
	// Scene 5 — perspective plane with a warped, scaled bitmap logo
	// =========================================================================

	private void rasterLandscape(int frameCount) {

		int horizon = Math.round(lh * 0.44f + (float) Math.sin(frameCount * 0.010) * lh * 0.02f);
		horizon = Math.max(4, Math.min(lh - 4, horizon));

		for (int y = 0; y < horizon; y++) {
			int rgb = mixRgb(0x08061C, 0x3A1152, y / (float) horizon);
			Arrays.fill(pix, y * lw, y * lw + lw, rgb);
		}
		for (int i = 0; i < 70; i++) {
			plot((i * 5077) % lw, (i * 3413) % horizon, scaleRgb(0xCFE4FF, 0.25f + 0.5f * ((i % 5) / 5f)));
		}

		drawSun(horizon, frameCount);
		drawRidges(horizon, frameCount);

		// Floor: one divide per row gives the depth, then the checker is integer maths per pixel.
		float scroll = frameCount * 0.055f + beatCount * 0.10f;
		float cx     = lw * 0.5f;
		for (int y = horizon; y < lh; y++) {
			float depth = 26f / (y - horizon + 1f);
			float fog   = clamp(1f - depth * 0.035f, 0.05f, 1f);
			int   wz    = (int) Math.floor(depth + scroll);
			int   row   = y * lw;
			int   lit   = mixRgb(0x120A28, 0x25E8F0, fog);
			int   dark  = mixRgb(0x0A0620, 0x7A1FA8, fog * 0.8f);
			for (int x = 0; x < lw; x++) {
				int wx = (int) Math.floor((x - cx) * depth * 0.045f);
				pix[row + x] = (((wx + wz) & 1) == 0) ? lit : dark;
			}
		}

		blitWarpedLogo(frameCount);
	}

	/** A striped disc on the horizon — the cheapest sun anyone ever drew. */
	private void drawSun(int horizon, int frameCount) {
		int r  = Math.max(6, Math.round(lh * 0.17f));
		int cx = lw / 2;
		int cy = horizon - Math.round(r * 0.35f);
		for (int dy = -r; dy <= r; dy++) {
			int y = cy + dy;
			if (y < 0 || y >= horizon) continue;
			if (dy > -r / 3 && Math.floorMod(dy + frameCount / 6, 7) < 2) continue;   // scan gaps
			int span = (int) Math.sqrt(Math.max(0, r * r - dy * dy));
			int col  = mixRgb(0xFFE45C, 0xFF2E8B, (dy + r) / (float) (2 * r));
			int row  = y * lw;
			for (int x = Math.max(0, cx - span); x <= Math.min(lw - 1, cx + span); x++) {
				pix[row + x] = col;
			}
		}
	}

	/** Two sine-sum ridges: parallax mountains for the price of a few sines per column. */
	private void drawRidges(int horizon, int frameCount) {
		for (int layer = 0; layer < 2; layer++) {
			float speed = frameCount * (0.20f + layer * 0.30f);
			int   body  = layer == 0 ? 0x1B0F3A : 0x2E1856;
			int   ridge = layer == 0 ? 0x6A3CB0 : 0xA163F0;
			float scale = layer == 0 ? 0.045f : 0.031f;
			float mag   = lh * (layer == 0 ? 0.22f : 0.15f);
			for (int x = 0; x < lw; x++) {
				float u = (x + speed) * scale;
				float k = (float) (Math.sin(u) * 0.5 + Math.sin(u * 2.3 + 1.7) * 0.3
						+ Math.sin(u * 0.7 + 0.4) * 0.2);
				int top = Math.max(0, horizon - Math.round((0.45f + 0.55f * k) * mag));
				for (int y = top; y < horizon; y++) pix[y * lw + x] = body;
				plot(x, top, ridge);
			}
		}
	}

	/**
	 * The bitmap warp: the logo raster is copied column by column, each column offset by one sine
	 * and stretched by another. One source column, one destination column, integer stepping — the
	 * same trick the demo used to make a still image ripple and breathe.
	 */
	private void blitWarpedLogo(int frameCount) {

		ensureLogo(Math.round(lw * 0.84f));
		if (logoImg == null) return;

		int   x0    = (lw - logoW) / 2;
		float baseY = lh * 0.27f + (float) Math.sin(frameCount * 0.023) * lh * 0.05f;
		float amp   = lh * 0.035f * (0.55f + bass * 0.9f);

		for (int sx = 0; sx < logoW; sx++) {
			int dx = x0 + sx;
			if (dx < 0 || dx >= lw) continue;
			float ph = frameCount * 0.080f + sx * 0.030f;
			float dy = (float) Math.sin(ph) * amp;
			float vs = 1f + (float) Math.sin(ph * 0.61f + 1.1f) * 0.18f;   // per-column scaling
			int   dh = Math.max(1, Math.round(logoH * vs));
			int   top = Math.round(baseY + dy - dh * 0.5f);
			for (int k = 0; k < dh; k++) {
				int y = top + k;
				if (y < 0 || y >= lh) continue;
				int argb = logoPix[(k * logoH / dh) * logoW + sx];
				if ((argb >>> 24) < 96) continue;
				pix[y * lw + dx] = argb & 0xFFFFFF;
			}
		}
	}

	/** Render "2ND HORIZON" once into a small ARGB raster; the warp then only copies pixels. */
	private void ensureLogo(int targetW) {
		if (logoImg != null && logoBuiltFor == targetW) return;
		logoBuiltFor = targetW;

		String text = "2ND HORIZON";
		Font   base = new Font(Font.MONOSPACED, Font.BOLD, 64);

		BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		Graphics2D pg = probe.createGraphics();
		pg.setFont(base);
		int measured = Math.max(1, pg.getFontMetrics().stringWidth(text));
		pg.dispose();

		Font font = base.deriveFont((float) Math.max(8, Math.round(64f * targetW / measured)));
		probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
		pg = probe.createGraphics();
		pg.setFont(font);
		FontMetrics fm = pg.getFontMetrics();
		int tw = Math.max(1, fm.stringWidth(text)) + 6;
		int th = Math.max(1, fm.getHeight()) + 6;
		int ascent = fm.getAscent();
		pg.dispose();

		logoImg = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
		logoPix = ((DataBufferInt) logoImg.getRaster().getDataBuffer()).getData();
		logoW   = tw;
		logoH   = th;

		Graphics2D lg = logoImg.createGraphics();
		// Antialiasing off: a warp that samples soft edges smears them into mud.
		lg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
		lg.setFont(font);
		lg.setColor(new Color(0x0B, 0x04, 0x18));
		for (int d = 1; d <= 2; d++) lg.drawString(text, 3 + d, 3 + ascent + d);
		lg.setColor(new Color(0x35, 0xF0, 0xFF));
		lg.drawString(text, 3, 3 + ascent);
		lg.dispose();

		// Fade the glyphs to magenta down their own height by hand: sharper than a gradient paint,
		// and it leaves the hard drop shadow untouched.
		for (int y = 3; y < th; y++) {
			float t = (y - 3f) / Math.max(1f, ascent);
			for (int x = 0; x < tw; x++) {
				int i = y * tw + x;
				if ((logoPix[i] >>> 24) < 96) continue;
				int c = logoPix[i] & 0xFFFFFF;
				if (c == 0x0B0418) continue;
				logoPix[i] = 0xFF000000 | mixRgb(c, 0xFF2FC8, t);
			}
		}
	}

	// =========================================================================
	// Scene 6 — kefrens bars, scroller and scope
	// =========================================================================

	/**
	 * Kefrens bars: only the top scanline is ever drawn, and the whole screen scrolls down one row
	 * per frame. The snaking column of bars is nothing but the history of a single moving dot —
	 * the effect is essentially free, which is why every 1993 demo had one.
	 */
	private void rasterKefrens(int frameCount) {

		System.arraycopy(pix, 0, pix, lw, lw * (lh - 1));
		Arrays.fill(pix, 0, lw, 0x05030E);

		float phase = frameCount * 0.085f;
		float amp   = lw * 0.30f * (0.55f + bass * 0.45f);
		int   barW  = Math.max(3, lw / 11);
		int   cx    = Math.round(clamp(lw * 0.5f
				+ (float) Math.sin(phase) * amp
				+ (float) Math.sin(phase * 0.61f + 1.3f) * amp * 0.35f,
				barW, lw - 1f - barW));
		int   base  = (int) (frameCount * 2.6f + beatCount * 18);

		for (int i = -barW; i <= barW; i++) {
			int x = cx + i;
			if (x < 0 || x >= lw) continue;
			float k = 1f - Math.abs(i) / (float) barW;
			pix[x] = scaleRgb(palPlasma[(base + (int) (k * 36)) & PAL_MASK], 0.25f + 0.75f * k * k);
		}
	}

	/** The actual PCM SDL is playing, drawn as a bright trace across the middle. */
	private void drawScope(Graphics2D g2, int w, int h) {
		if (scopeSamples < SCOPE_POINTS) return;

		int   step = scopeSamples / SCOPE_POINTS;
		float mid  = h * 0.5f;
		float amp  = h * 0.17f;
		float inv  = 1f / Math.max(0.12f, scopePeak);
		for (int i = 0; i < SCOPE_POINTS; i++) {
			scopeX[i] = Math.round(i * (w - 1) / (float) (SCOPE_POINTS - 1));
			scopeY[i] = Math.round(mid - clamp(snapshot[i * step] * inv, -1.3f, 1.3f) * amp);
		}

		Stroke old = g2.getStroke();
		g2.setStroke(SCOPE_GLOW);
		g2.setColor(new Color(0x2A, 0xF5, 0xFF, 60));
		g2.drawPolyline(scopeX, scopeY, SCOPE_POINTS);
		g2.setStroke(SCOPE_LINE);
		g2.setColor(new Color(0xE8, 0xFF, 0xFF, 220));
		g2.drawPolyline(scopeX, scopeY, SCOPE_POINTS);
		g2.setStroke(old);
	}

	/** Sine scroller: every character sits on its own point of a travelling wave. */
	private void drawScroller(Graphics2D g2, int w, int h, int frameCount) {

		g2.setFont(scrollFont);
		FontMetrics fm = g2.getFontMetrics();
		if (scrollWidthFont != scrollFont) {
			scrollWidthFont = scrollFont;
			int total = 0;
			for (char c : scrollChars) total += fm.charWidth(c);
			scrollWidth = total;
		}

		if (Float.isNaN(scrollX) || scrollX < -scrollWidth) scrollX = w;
		scrollX -= 2.0f + energy * 3.4f;

		float baseY = h * 0.80f;
		float amp   = h * 0.07f;
		float x     = scrollX;

		for (int i = 0; i < scrollChars.length && x < w; i++) {
			int cw = fm.charWidth(scrollChars[i]);
			if (x > -cw) {
				float y = baseY + (float) Math.sin(x * 0.017 + frameCount * 0.045) * amp;
				g2.setColor(Color.BLACK);
				g2.drawChars(scrollChars, i, 1, Math.round(x) + 2, Math.round(y) + 2);
				g2.setColor(hot[(int) (i * 6 + frameCount * 2.4f) & PAL_MASK]);
				g2.drawChars(scrollChars, i, 1, Math.round(x), Math.round(y));
			}
			x += cw;
		}
	}

	private void drawFinaleLogo(Graphics2D g2, int w, int h) {
		drawGlowText(g2, "THE 2ND HORIZON", midFont, w * 0.5f, h * 0.17f, 1f, 1f + flash * 0.16f);
		drawGlowText(g2, "NUCLR CREW  1993", smallFont, w * 0.5f, h * 0.29f, 0.75f, 1f);
	}

	// =========================================================================
	// Overlays
	// =========================================================================

	/**
	 * The part change: a few frames of torn slices, as if the CRT lost sync while the next part was
	 * loading. Redrawing the raster in offset bands is enough to sell it.
	 */
	private void drawTransition(Graphics2D g2, int w, int h) {
		if (transition <= 0 || img == null) return;

		float t      = transition / (float) TRANSITION_FRAMES;
		int   slices = 14;
		for (int i = 0; i < slices; i++) {
			int dy0 = i * h / slices;
			int dy1 = (i + 1) * h / slices;
			int off = Math.round((rnd.nextFloat() - 0.5f) * w * 0.35f * t);
			g2.drawImage(img, off, dy0, off + w, dy1, 0, i * lh / slices, lw, (i + 1) * lh / slices, null);
		}
	}

	/** CRT scanlines: the cheapest way to make anything look like it came off a 15 kHz monitor. */
	private void drawScanlines(Graphics2D g2, int w, int h) {
		g2.setColor(new Color(0, 0, 0, 52));
		for (int y = 0; y < h; y += 3) g2.fillRect(0, y, w, 1);
	}

	private void drawFlash(Graphics2D g2, int w, int h) {
		if (flash <= 0.02f) return;
		g2.setColor(new Color(255, 255, 255, clamp255((int) (flash * 64f))));
		g2.fillRect(0, 0, w, h);
	}

	/**
	 * Part counter, beat-sync tell-tale, and — for the first couple of seconds of every part — the
	 * DOS command line that would have started it, back when a demo let you skip straight to the
	 * part you wanted.
	 */
	private void drawHud(Graphics2D g2, int w, int h, int frameCount) {

		g2.setFont(smallFont);
		FontMetrics fm = g2.getFontMetrics();

		// Top right: which part is running.
		String part = "PART " + (scene + 1) + "/" + SCENE_COUNT + "  " + SCENE_NAMES[scene];
		int    pw   = fm.stringWidth(part);
		int    px   = w - pw - 14;
		if (px > 4) {
			g2.setColor(new Color(5, 7, 12, 170));
			g2.fillRect(px - 6, 10, pw + 12, fm.getHeight() + 6);
			g2.setColor(new Color(0x9F, 0xE8, 0xFF, 220));
			g2.drawString(part, px, 13 + fm.getAscent());
		}

		// Bottom right: the sync lamp, lit by the same kick that cuts between the parts.
		boolean lit  = beatCooldown > 3;
		String  sync = lit ? "SYNC [*]" : "SYNC [ ]";
		int     sw   = fm.stringWidth(sync);
		if (w - sw - 14 > 4) {
			g2.setColor(lit ? new Color(0xFF, 0xD6, 0x5C, 235) : new Color(0x54, 0x6A, 0x86, 190));
			g2.drawString(sync, w - sw - 14, h - 10);
		}

		// Bottom left: the command line, fading out once the part is under way.
		float a = window(sceneFrame, 0, 150, 30);
		if (a > 0.01f) {
			String arg  = SCENE_ARGS[scene];
			String line = "C:\\DEMOS\\2ND>HORIZON.EXE" + (arg.isEmpty() ? "" : " " + arg)
					+ ((frameCount / 16) % 2 == 0 ? "_" : " ");
			Composite old = g2.getComposite();
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
			g2.setColor(new Color(0xC8, 0xC8, 0xC8, 235));
			g2.drawString(line, 12, h - 10);
			g2.setComposite(old);
		}
	}

	/** Chunky centred text with a hard shadow and a colour-fringed halo — 1993 had no font smoothing. */
	private void drawGlowText(Graphics2D g2, String text, Font font, float cx, float cy,
			float alpha, float scale) {

		g2.setFont(scale == 1f ? font : font.deriveFont(font.getSize2D() * scale));
		FontMetrics fm = g2.getFontMetrics();
		int x = Math.round(cx - fm.stringWidth(text) * 0.5f);
		int y = Math.round(cy + fm.getAscent() * 0.5f);

		Composite old = g2.getComposite();
		g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, clamp(alpha, 0f, 1f)));

		g2.setColor(new Color(0, 0, 0, 200));
		g2.drawString(text, x + 3, y + 3);
		g2.setColor(new Color(0x2A, 0xC8, 0xFF, 90));
		g2.drawString(text, x - 2, y);
		g2.setColor(new Color(0xFF, 0x3C, 0xC0, 90));
		g2.drawString(text, x + 2, y);
		g2.setColor(new Color(0xF2, 0xFA, 0xFF, 245));
		g2.drawString(text, x, y);

		g2.setComposite(old);
	}

	// =========================================================================
	// Raster plumbing
	// =========================================================================

	/**
	 * The raster is the panel divided by a whole number, so the pixels stay square — circles in the
	 * moire field would otherwise come out as ellipses — and the per-pixel work stays bounded
	 * however large the panel gets.
	 */
	private void ensureRaster(int w, int h) {
		int scale = PIXEL;
		while (w / scale > MAX_LW || h / scale > MAX_LH) scale++;
		int nw = Math.max(16, w / scale);
		int nh = Math.max(16, h / scale);
		if (img == null || nw != lw || nh != lh) {
			img = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
			pix = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
			lw  = nw;
			lh  = nh;
		}
	}

	private void blit(Graphics2D g2, int w, int h) {
		g2.drawImage(img, 0, 0, w, h, null);
	}

	private void plot(int x, int y, int rgb) {
		if (x < 0 || y < 0 || x >= lw || y >= lh) return;
		pix[y * lw + x] = rgb;
	}

	private void ensureFonts(int h) {
		if (fontsFor == h) return;
		fontsFor   = h;
		bigFont    = new Font(Font.MONOSPACED, Font.BOLD, Math.max(16, Math.round(h * 0.115f)));
		midFont    = new Font(Font.MONOSPACED, Font.BOLD, Math.max(12, Math.round(h * 0.070f)));
		smallFont  = new Font(Font.MONOSPACED, Font.BOLD, Math.max(9,  Math.round(h * 0.034f)));
		scrollFont = new Font(Font.MONOSPACED, Font.BOLD, Math.max(12, Math.round(h * 0.090f)));
	}

	// =========================================================================
	// Tables & helpers
	// =========================================================================

	private void buildBandBins() {
		float logMin  = (float) Math.log(40f);
		float logMax  = (float) Math.log(16000f);
		int   nyquist = FFT_SIZE / 2;
		for (int b = 0; b <= BANDS; b++) {
			float freq = (float) Math.exp(logMin + (logMax - logMin) * b / BANDS);
			bandBin[b] = Math.max(1, Math.min(nyquist, Math.round(freq * FFT_SIZE / SAMPLE_RATE)));
		}
		for (int b = 1; b <= BANDS; b++) {
			if (bandBin[b] <= bandBin[b - 1]) bandBin[b] = Math.min(nyquist, bandBin[b - 1] + 1);
		}
	}

	/**
	 * Three 256-entry palettes, each seamless end to end so that rotating the index reads as a
	 * colour sweep rather than a jump. VGA had one palette and demos animated it; here the palette
	 * is baked and the index does the moving, which comes to the same thing.
	 */
	private void buildPalettes() {
		for (int i = 0; i < PALETTE; i++) {
			float t    = i / (float) PALETTE;
			float wave = 0.5f - 0.5f * (float) Math.cos(t * 2 * Math.PI);   // 0 at both ends: seamless

			palPlasma[i] = hsbRgb(0.72f + t, 0.62f + 0.30f * wave, 0.30f + 0.62f * wave);
			palIce[i]    = hsbRgb(0.46f + 0.22f * wave, 0.95f - 0.45f * wave, 0.10f + 0.90f * wave);
			palFire[i]   = fireRgb(wave);
			hot[i]       = new Color(hsbRgb(0.72f + t, 0.85f, 1f));
		}
	}

	/** Black to red to yellow to white and back — the heat ramp every fire routine ever used. */
	private static int fireRgb(float v) {
		int r = clamp255(Math.round(v * 3.2f * 255f));
		int g = clamp255(Math.round((v - 0.32f) * 2.6f * 255f));
		int b = clamp255(Math.round((v - 0.72f) * 3.6f * 255f));
		return (r << 16) | (g << 8) | b;
	}

	private static int hsbRgb(float hue, float sat, float bri) {
		return Color.HSBtoRGB(((hue % 1f) + 1f) % 1f, clamp(sat, 0f, 1f), clamp(bri, 0f, 1f)) & 0xFFFFFF;
	}

	private static int scaleRgb(int rgb, float k) {
		int r = clamp255(Math.round(((rgb >> 16) & 0xFF) * k));
		int g = clamp255(Math.round(((rgb >> 8) & 0xFF) * k));
		int b = clamp255(Math.round((rgb & 0xFF) * k));
		return (r << 16) | (g << 8) | b;
	}

	private static int addRgb(int a, int b) {
		int r = Math.min(255, ((a >> 16) & 0xFF) + ((b >> 16) & 0xFF));
		int g = Math.min(255, ((a >> 8) & 0xFF) + ((b >> 8) & 0xFF));
		int c = Math.min(255, (a & 0xFF) + (b & 0xFF));
		return (r << 16) | (g << 8) | c;
	}

	private static int mixRgb(int a, int b, float t) {
		t = clamp(t, 0f, 1f);
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return (clamp255(Math.round(ar + (br - ar) * t)) << 16)
				| (clamp255(Math.round(ag + (bg - ag) * t)) << 8)
				| clamp255(Math.round(ab + (bb - ab) * t));
	}

	private static Color brighter(Color c) {
		return new Color(Math.min(255, c.getRed() + 70), Math.min(255, c.getGreen() + 70),
				Math.min(255, c.getBlue() + 70));
	}

	/** 1 inside [start, end] with {@code ramp}-frame fades at both ends, 0 outside. */
	private static float window(int frame, int start, int end, int ramp) {
		if (frame < start || frame > end) return 0f;
		float in  = (frame - start) / (float) ramp;
		float out = (end - frame) / (float) ramp;
		return clamp(Math.min(in, out), 0f, 1f);
	}

	private static float smoothStep(float t) {
		t = clamp(t, 0f, 1f);
		return t * t * (3f - 2f * t);
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int clamp255(int v) {
		return Math.max(0, Math.min(255, v));
	}
}
