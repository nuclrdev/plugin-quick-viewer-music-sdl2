package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * "C64 PETSCII" — a Commodore 64 tribute visualizer, and the plugin's most
 * radical renderer: the whole scene is drawn in full colour, then forced
 * through a genuine PETSCII pipe — every 8x8 cell of the 320x200 screen may
 * hold exactly one character from a graphic charset and one foreground colour
 * over the global background, so an audio-driven plasma field, spectrum
 * towers and bouncing sprite balls all come out as living character art.
 * <p>
 * The quantizer works like the VIC-II would have wanted: per cell it finds
 * the dominant foreground colour, thresholds the cell into a bitmask, and
 * picks the charset glyph (solid/half/quarter blocks, checkers, diagonals,
 * balls, lines) with the smallest Hamming distance. Everything breathes with
 * the music: plasma amplitude rides the energy, ten spectrum towers stand in
 * the field, and three bouncing balls get kicked by every beat.
 * <p>
 * Racing the beam: metallic raster bars drift over the top and bottom of the
 * character screen, and a scene scroller runs along the bottom. A new track
 * boots properly — {@code **** COMMODORE 64 BASIC V2 ****}, {@code
 * LOAD"TRACK",8,1 ... SEARCHING ... LOADING} — with the border strobing
 * turbo-loader colours while it loads. Silence drops back to a lonely
 * {@code READY.} prompt; stay silent and you earn {@code ?DEVICE NOT PRESENT
 * ERROR}.
 */
final class C64Visualizer {

	// ---- Screen ----
	private static final int SCREEN_W = 320;
	private static final int SCREEN_H = 200;
	private static final int CELL     = 8;
	private static final int COLS     = SCREEN_W / CELL;
	private static final int ROWS     = SCREEN_H / CELL;

	/** The 16 VIC-II colours (Pepto). */
	private static final int[] PAL = {
		0x000000, 0xFFFFFF, 0x68372B, 0x70A4B2, 0x6F3D86, 0x588D43, 0x352879, 0xB8C76F,
		0x6F4F25, 0x433900, 0x9A6759, 0x444444, 0x6C6C6C, 0x9AD284, 0x6C5EB5, 0x959595
	};
	private static final int BG      = 6;   // screen blue
	private static final int BORDER  = 14;  // light blue
	private static final int INK     = 14;  // light blue text
	/** Plasma colour ramp, quiet -> loud (starts at BG so silence melts away). */
	private static final int[] RAMP = { 6, 6, 4, 14, 3, 13, 7, 1 };

	private final BufferedImage screen = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB);
	private final int[] px = ((DataBufferInt) screen.getRaster().getDataBuffer()).getData();

	// ---- PETSCII-style graphic charset (8 bytes per glyph, LSB = leftmost) ----
	private static final int[][] GLYPHS = buildGlyphs();

	private static int[][] buildGlyphs() {
		java.util.List<int[]> g = new java.util.ArrayList<>();
		g.add(rows(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)); // blank
		g.add(rows(0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)); // solid
		g.add(rows(0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F, 0x0F)); // left half
		g.add(rows(0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0, 0xF0)); // right half
		g.add(rows(0xFF, 0xFF, 0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00)); // top half
		g.add(rows(0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0xFF)); // bottom half
		g.add(rows(0x0F, 0x0F, 0x0F, 0x0F, 0x00, 0x00, 0x00, 0x00)); // quarter TL
		g.add(rows(0xF0, 0xF0, 0xF0, 0xF0, 0x00, 0x00, 0x00, 0x00)); // quarter TR
		g.add(rows(0x00, 0x00, 0x00, 0x00, 0x0F, 0x0F, 0x0F, 0x0F)); // quarter BL
		g.add(rows(0x00, 0x00, 0x00, 0x00, 0xF0, 0xF0, 0xF0, 0xF0)); // quarter BR
		g.add(rows(0x55, 0xAA, 0x55, 0xAA, 0x55, 0xAA, 0x55, 0xAA)); // checker
		g.add(rows(0xAA, 0x55, 0xAA, 0x55, 0xAA, 0x55, 0xAA, 0x55)); // checker inv
		g.add(rows(0x00, 0x00, 0x00, 0xFF, 0xFF, 0x00, 0x00, 0x00)); // horiz bar
		g.add(rows(0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18)); // vert bar
		g.add(rows(0x03, 0x07, 0x0E, 0x1C, 0x38, 0x70, 0xE0, 0xC0)); // diagonal \
		g.add(rows(0xC0, 0xE0, 0x70, 0x38, 0x1C, 0x0E, 0x07, 0x03)); // diagonal /
		g.add(rows(0x3C, 0x7E, 0xFF, 0xFF, 0xFF, 0xFF, 0x7E, 0x3C)); // ball
		g.add(rows(0x3C, 0x42, 0x81, 0x81, 0x81, 0x81, 0x42, 0x3C)); // ring
		g.add(rows(0x00, 0x00, 0x18, 0x3C, 0x3C, 0x18, 0x00, 0x00)); // dot
		g.add(rows(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0xFF, 0xFF)); // low ledge
		g.add(rows(0xFF, 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)); // high ledge
		g.add(rows(0x18, 0x18, 0x18, 0xFF, 0xFF, 0x18, 0x18, 0x18)); // cross
		return g.toArray(new int[0][]);
	}

	private static int[] rows(int... r) {
		return r;
	}

	// ---- FFT / analysis ----
	private static final int FFT_SIZE    = 2048;
	private static final int SAMPLE_RATE = 44100;
	private static final int NUM_BARS    = 10;

	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   barBin   = new int[NUM_BARS + 1];
	private final float[] bars     = new float[NUM_BARS];

	private float bass, energy;
	private float bassAvg;
	private float autoGain = 8f;
	private int   framesSinceBeat = 999;
	private float beatPulse = 0f;

	// ---- Sine table for the plasma ----
	private final float[] sinT = new float[1024];

	// ---- Bouncing sprite balls ----
	private static final int BALLS = 3;
	private final float[] ballX  = { 60f, 160f, 250f };
	private final float[] ballY  = { 60f, 100f, 50f };
	private final float[] ballVx = { 1.3f, -1.7f, 1.1f };
	private final float[] ballVy = { 0.9f, 1.2f, -1.4f };
	private static final int[] BALL_COL = { 1, 10, 7 };

	// ---- Boot sequence ----
	private String bootLoadName = "TRACK";
	private int bootChars = Integer.MAX_VALUE;
	private int bootTotal = 1;
	private boolean bootLoading = false;

	// ---- Scroller ----
	private String scrollText =
		"NUCLR 64 PRESENTS THE PETSCII ENGINE ...   GREETINGS TO HVSC + FAIRLIGHT + CENSOR DESIGN + TRIAD ...   ";
	private float scrollX = Float.NaN;

	// ---- State ----
	private int idleFrames = 0;
	private int frame = 0;
	private final Random rnd = new Random(0xC64);

	C64Visualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		float logMin = (float) Math.log(45), logMax = (float) Math.log(12000);
		for (int b = 0; b <= NUM_BARS; b++) {
			float freq = (float) Math.exp(logMin + (logMax - logMin) * b / NUM_BARS);
			barBin[b] = clampI(Math.round(freq * FFT_SIZE / SAMPLE_RATE), 1, FFT_SIZE / 2);
		}
		for (int b = 1; b <= NUM_BARS; b++) {
			if (barBin[b] <= barBin[b - 1]) barBin[b] = barBin[b - 1] + 1;
		}
		for (int i = 0; i < sinT.length; i++) {
			sinT[i] = (float) Math.sin(i * 2 * Math.PI / sinT.length);
		}
	}

	private float sin(float turns) {
		return sinT[((int) (turns * sinT.length) % sinT.length + sinT.length) % sinT.length];
	}

	/** New tune: LOAD it from device 8 with the border strobing. */
	void setTrackTitle(String title) {
		String name = title == null ? "" : title.toUpperCase().replaceAll("[^A-Z0-9 .]", "");
		if (name.length() > 14) name = name.substring(0, 14);
		if (name.isBlank()) name = "TRACK";
		bootLoadName = name;
		bootChars = 0;
		bootTotal = bootScript().length() + 1;
		scrollText = "NOW PLAYING: " + name + " ...   " + scrollText;
		if (scrollText.length() > 240) scrollText = scrollText.substring(0, 240);
		scrollX = Float.NaN;
		idleFrames = 0;
	}

	private String bootScript() {
		return "READY.\nLOAD\"" + bootLoadName + "\",8,1\n\nSEARCHING FOR " + bootLoadName + "\nLOADING\nREADY.\nRUN\n";
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
			for (int b = 0; b < NUM_BARS; b++) bars[b] *= 0.94f;
			bass   += (0f - bass)   * 0.06f;
			energy += (0f - energy) * 0.06f;
			idleFrames++;
		}
		beatPulse *= 0.92f;
		bootLoading = false;

		if (idleFrames > 240) {
			drawBasicScreen(idleFrames > 900);
		} else if (bootChars < bootTotal) {
			bootChars = Math.min(bootTotal, bootChars + 3);
			drawBoot();
		} else {
			drawScene();
		}

		// Border: the whole panel is the border, screen centred inside.
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		int borderCol = bootLoading ? PAL[rnd.nextInt(16)] : PAL[BORDER];
		g2.setColor(new Color(borderCol));
		g2.fillRect(0, 0, w, h);
		int minB = 12;
		int sw = Math.max(64, Math.min(w - 2 * minB, (h - 2 * minB) * SCREEN_W / SCREEN_H));
		int sh = sw * SCREEN_H / SCREEN_W;
		g2.drawImage(screen, (w - sw) / 2, (h - sh) / 2, sw, sh, null);
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
		float[] raw = new float[NUM_BARS];
		for (int b = 0; b < NUM_BARS; b++) {
			float sum = 0f;
			for (int k = barBin[b]; k < barBin[b + 1]; k++) {
				sum += re[k] * re[k] + im[k] * im[k];
			}
			raw[b] = (float) Math.sqrt(sum / (barBin[b + 1] - barBin[b])) / FFT_SIZE * 24f;
			if (raw[b] > rawMax) rawMax = raw[b];
		}
		float targetGain = clampF(0.8f / rawMax, 1f, 400f);
		autoGain += (targetGain - autoGain) * (targetGain < autoGain ? 0.15f : 0.02f);

		float sum = 0f;
		for (int b = 0; b < NUM_BARS; b++) {
			float n = clampF(raw[b] * autoGain, 0f, 1f);
			bars[b] += (n - bars[b]) * (n > bars[b] ? 0.55f : 0.10f);
			sum += bars[b];
		}
		float nb = clampF((bars[0] + bars[1]) * 0.6f, 0f, 1f);
		bass   += (nb - bass) * (nb > bass ? 0.5f : 0.12f);
		energy += (sum / NUM_BARS - energy) * 0.15f;

		bassAvg += (bass - bassAvg) * 0.02f;
		framesSinceBeat++;
		if (framesSinceBeat > 14 && bass > 0.14f && bass > bassAvg * 1.45f + 0.03f) {
			framesSinceBeat = 0;
			beatPulse = 1f;
			for (int i = 0; i < BALLS; i++) {
				ballVy[i] -= 1.2f + rnd.nextFloat();
			}
		}
	}

	// ---- The PETSCII scene ----

	private void drawScene() {
		drawPlasma();
		drawTowers();
		moveAndDrawBalls();
		quantizeToPetscii();
		drawRasterBars();
		drawScroller();
	}

	/** Audio-modulated plasma, painted straight in palette colours. */
	private void drawPlasma() {
		float t = frame * 0.004f;
		float amp = 0.30f + energy * 0.95f + beatPulse * 0.25f;
		for (int y = 0; y < SCREEN_H; y++) {
			float sy1 = sin(y * 0.011f - t * 1.3f);
			float sy2 = sin(y * 0.004f + t * 0.6f);
			int base = y * SCREEN_W;
			for (int x = 0; x < SCREEN_W; x++) {
				float v = sin(x * 0.008f + t)
						+ sy1
						+ sin((x + y) * 0.006f + t * 0.7f)
						+ sy2 * sin(x * 0.005f - t);
				float level = clampF((v + 4f) / 8f * amp, 0f, 0.999f);
				px[base + x] = PAL[RAMP[(int) (level * RAMP.length)]];
			}
		}
	}

	/** Ten spectrum towers standing in the plasma field. */
	private void drawTowers() {
		for (int b = 0; b < NUM_BARS; b++) {
			int bh = Math.round(bars[b] * 150f);
			if (bh < 3) continue;
			int x = 16 + b * 29;
			fill(x, 180 - bh, 20, bh, PAL[13]);
			fill(x, 180 - bh, 20, 3, PAL[1]);
			fill(x + 2, 180 - bh + 4, 3, Math.max(0, bh - 6), PAL[7]);
		}
	}

	private void moveAndDrawBalls() {
		for (int i = 0; i < BALLS; i++) {
			ballX[i] += ballVx[i] * (1f + energy);
			ballY[i] += ballVy[i];
			ballVy[i] += 0.06f;
			if (ballX[i] < 10)             { ballX[i] = 10;             ballVx[i] = Math.abs(ballVx[i]); }
			if (ballX[i] > SCREEN_W - 10)  { ballX[i] = SCREEN_W - 10;  ballVx[i] = -Math.abs(ballVx[i]); }
			if (ballY[i] < 10)             { ballY[i] = 10;             ballVy[i] = Math.abs(ballVy[i]); }
			if (ballY[i] > 172)            { ballY[i] = 172;            ballVy[i] = -Math.abs(ballVy[i]) * 0.96f; }
			fillCircle(Math.round(ballX[i]), Math.round(ballY[i]), 10, PAL[BALL_COL[i]]);
		}
	}

	private void fillCircle(int cx, int cy, int r, int color) {
		for (int y = -r; y <= r; y++) {
			int half = (int) Math.sqrt(r * r - y * y);
			int yy = cy + y;
			if (yy < 0 || yy >= SCREEN_H) continue;
			int x0 = Math.max(0, cx - half), x1 = Math.min(SCREEN_W - 1, cx + half);
			int base = yy * SCREEN_W;
			for (int x = x0; x <= x1; x++) px[base + x] = color;
		}
	}

	/**
	 * The VIC-II law: per 8x8 cell, one glyph, one foreground colour, shared
	 * background. Dominant colour wins the cell; nearest-bitmask glyph renders it.
	 */
	private void quantizeToPetscii() {
		int bgRgb = PAL[BG];
		int[] count = new int[16];
		for (int cy = 0; cy < ROWS; cy++) {
			for (int cx = 0; cx < COLS; cx++) {
				int base = cy * CELL * SCREEN_W + cx * CELL;

				int mask0 = 0, mask1 = 0, mask2 = 0, mask3 = 0; // 4 rows per int (8 bits each)
				java.util.Arrays.fill(count, 0);
				int fgPixels = 0;
				for (int yy = 0; yy < CELL; yy++) {
					int rowBits = 0;
					int rowBase = base + yy * SCREEN_W;
					for (int xx = 0; xx < CELL; xx++) {
						int c = px[rowBase + xx];
						if (c != bgRgb) {
							rowBits |= 1 << xx;
							count[palIndex(c)]++;
							fgPixels++;
						}
					}
					switch (yy >> 1) {
						case 0 -> mask0 |= rowBits << ((yy & 1) * 8);
						case 1 -> mask1 |= rowBits << ((yy & 1) * 8);
						case 2 -> mask2 |= rowBits << ((yy & 1) * 8);
						default -> mask3 |= rowBits << ((yy & 1) * 8);
					}
				}

				int fg = BG;
				if (fgPixels > 0) {
					int best = 0;
					for (int i = 1; i < 16; i++) {
						if (count[i] > count[best]) best = i;
					}
					fg = best;
				}

				// Nearest glyph by Hamming distance on the 64-bit pattern.
				int bestGlyph = 0, bestDist = Integer.MAX_VALUE;
				for (int gi = 0; gi < GLYPHS.length; gi++) {
					int[] g = GLYPHS[gi];
					int d = Integer.bitCount(mask0 ^ (g[0] | g[1] << 8))
							+ Integer.bitCount(mask1 ^ (g[2] | g[3] << 8))
							+ Integer.bitCount(mask2 ^ (g[4] | g[5] << 8))
							+ Integer.bitCount(mask3 ^ (g[6] | g[7] << 8));
					if (d < bestDist) {
						bestDist = d;
						bestGlyph = gi;
					}
				}

				int fgRgb = PAL[fg];
				int[] g = GLYPHS[bestGlyph];
				for (int yy = 0; yy < CELL; yy++) {
					int bits = g[yy];
					int rowBase = base + yy * SCREEN_W;
					for (int xx = 0; xx < CELL; xx++) {
						px[rowBase + xx] = (bits >> xx & 1) != 0 ? fgRgb : bgRgb;
					}
				}
			}
		}
	}

	private static int palIndex(int rgb) {
		for (int i = 0; i < 16; i++) {
			if (PAL[i] == rgb) return i;
		}
		return 1;
	}

	/** Metallic raster bars racing the beam over the character screen. */
	private void drawRasterBars() {
		int[] metal = { 11, 12, 15, 1, 15, 12, 11 };
		int y1 = 4 + Math.round(sin(frame * 0.0021f) * 3f);
		int y2 = 186 + Math.round(sin(frame * 0.0017f + 0.4f) * 3f);
		for (int i = 0; i < metal.length; i++) {
			fill(0, y1 + i, SCREEN_W, 1, PAL[metal[i]]);
			fill(0, y2 + i, SCREEN_W, 1, PAL[metal[i]]);
		}
	}

	private void drawScroller() {
		Graphics2D g = screen.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
			if (Float.isNaN(scrollX)) scrollX = SCREEN_W;
			scrollX -= 1.6f + energy * 1.4f;
			int cw = 7;
			if (scrollX < -scrollText.length() * cw) scrollX = SCREEN_W;
			g.setColor(new Color(PAL[0]));
			g.drawString(scrollText, Math.round(scrollX) + 1, 178);
			g.setColor(new Color(PAL[INK]));
			g.drawString(scrollText, Math.round(scrollX), 177);
		} finally {
			g.dispose();
		}
	}

	// ---- Boot & idle: the blue place we all came from ----

	private void drawBoot() {
		java.util.Arrays.fill(px, PAL[BG]);
		Graphics2D g = screen.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
			g.setColor(new Color(PAL[INK]));
			g.drawString("**** COMMODORE 64 BASIC V2 ****", 32, 20);
			g.drawString("64K RAM SYSTEM  38911 BASIC BYTES FREE", 8, 34);

			String script = bootScript();
			int budget = bootChars;
			int row = 0;
			for (String line : script.split("\n", -1)) {
				if (budget <= 0) break;
				int take = Math.min(line.length(), budget);
				String shown = line.substring(0, take);
				g.drawString(shown, 8, 56 + row * 12);
				if (shown.equals("LOADING")) bootLoading = true;
				budget -= line.length() + 2;
				row++;
			}
			if ((frame / 16) % 2 == 0) {
				g.fillRect(8, 48 + row * 12, 8, 10);
			}
		} finally {
			g.dispose();
		}
	}

	private void drawBasicScreen(boolean deviceError) {
		java.util.Arrays.fill(px, PAL[BG]);
		Graphics2D g = screen.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 11));
			g.setColor(new Color(PAL[INK]));
			g.drawString("READY.", 8, 20);
			if (deviceError) {
				g.drawString("?DEVICE NOT PRESENT  ERROR", 8, 34);
				g.drawString("READY.", 8, 48);
			}
			if ((frame / 16) % 2 == 0) {
				g.fillRect(8, deviceError ? 52 : 24, 8, 10);
			}
		} finally {
			g.dispose();
		}
	}

	// ---- Pixel helpers ----

	private void fill(int x, int y, int w, int h, int color) {
		int x1 = Math.min(SCREEN_W, x + w);
		int y1 = Math.min(SCREEN_H, y + h);
		for (int yy = Math.max(0, y); yy < y1; yy++) {
			int base = yy * SCREEN_W;
			for (int xx = Math.max(0, x); xx < x1; xx++) {
				px[base + xx] = color;
			}
		}
	}

	private static int clampI(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clampF(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
