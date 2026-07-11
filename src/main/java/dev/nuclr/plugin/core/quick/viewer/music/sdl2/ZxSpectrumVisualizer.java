package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

import sdl2.AudioRingBuffer;

/**
 * "ZX Spectrum" — a Sinclair ZX Spectrum tribute visualizer.
 * <p>
 * The whole panel becomes a Speccy: a fat border whose tape-loading stripes
 * are driven by the music (sound literally was the border on a real Spectrum —
 * red/cyan pilot bands in silence, racing blue/yellow data bands while
 * playing, a white flash on every kick) around a virtual 256x192 screen that
 * is post-processed through an authentic 8x8 <em>attribute-cell</em>
 * quantizer: every cell may hold only two colours of the 15-colour palette,
 * so sprites and bars smear into each other with genuine attribute clash.
 * <p>
 * On screen, a Manic Miner-flavoured scene: a brick platform with a little
 * hard-hatted miner who patrols it and jumps on every beat, sixteen
 * brick-tower VU bars with falling peak caps, and — as on the Manic Miner
 * title screen — a piano keyboard along the bottom whose keys light up with
 * the dominant pitches of the music. A new track "LOAD""s in exactly like a
 * SCREEN$: pixel thirds arrive in the Spectrum's interleaved scanline order
 * as black-on-white ghosts, then the attribute colours pop in afterwards.
 * Prolonged silence drops to the boot screen — and eventually to the
 * heartbreak of {@code R Tape loading error, 0:1}.
 * <p>
 * Self-contained like its siblings; the framebuffer, FFT scratch and all
 * tables are pre-allocated, and the ring buffer snapshot is read on the EDT.
 */
final class ZxSpectrumVisualizer {

	// ---- Virtual screen ----
	private static final int SCREEN_W = 256;
	private static final int SCREEN_H = 192;
	private static final int CELL     = 8;
	private static final int COLS     = SCREEN_W / CELL;
	private static final int ROWS     = SCREEN_H / CELL;

	/** 0..7 normal, 8..15 BRIGHT (bright black kept for a regular 16-entry table). */
	private static final int[] PAL = {
		0x000000, 0x0000D7, 0xD70000, 0xD700D7, 0x00D700, 0x00D7D7, 0xD7D700, 0xD7D7D7,
		0x000000, 0x0000FF, 0xFF0000, 0xFF00FF, 0x00FF00, 0x00FFFF, 0xFFFF00, 0xFFFFFF
	};
	private static final int BLACK = PAL[0],  BLUE_B = PAL[9],  RED = PAL[2],  GREEN = PAL[4];
	private static final int CYAN = PAL[5],   YELLOW = PAL[6],  WHITE = PAL[7];
	private static final int RED_B = PAL[10], MAGENTA_B = PAL[11], CYAN_B = PAL[13];
	private static final int YELLOW_B = PAL[14], WHITE_B = PAL[15];

	private final BufferedImage screen = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB);
	private final int[] px = ((DataBufferInt) screen.getRaster().getDataBuffer()).getData();
	private final int[][] palDist = new int[16][16];
	private final int[] cellCount = new int[16];

	// ---- FFT / analysis ----
	private static final int FFT_SIZE    = 2048;
	private static final int SAMPLE_RATE = 44100;
	private static final int NUM_BARS    = 16;

	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   barBin   = new int[NUM_BARS + 1];
	private final float[] bars     = new float[NUM_BARS];
	private final float[] barPeak  = new float[NUM_BARS];

	private float bass, treble, energy;
	private float bassAvg;
	private float autoGain = 8f;
	private int   framesSinceBeat = 999;
	private int   borderFlash = 0;

	// ---- Piano (2 octaves, C4..B5, Manic Miner title style) ----
	private static final int   WHITE_KEYS = 14;
	private static final int[] WHITE_OF   = { 0, -1, 1, -1, 2, 3, -1, 4, -1, 5, -1, 6 };
	private static final int[] BLACK_OF   = { -1, 0, -1, 1, -1, -1, 2, -1, 3, -1, 4, -1 };
	private static final int[] BLACK_POS  = { 0, 1, 3, 4, 5 }; // white-key boundary each black slot sits on

	private final float[] whiteLit = new float[WHITE_KEYS];
	private final float[] blackLit = new float[10];

	// ---- Miner sprite (12 x 16; . transparent, W white, Y bright-yellow hard hat) ----
	private static final int SPRITE_W = 12;
	private static final String[][] SPRITE_FRAMES = {
		{ // walk A — legs striding
			"...YYYYYY...", "..YYYYYYYY..", "..YYYYYYYY..", "...WWWWWW...",
			"...W.WW.W...", "...WWWWWW...", "....WWWW....", "..WWWWWWWW..",
			".WW.WWWW.WW.", ".WW.WWWW.WW.", ".W..WWWW..W.", "....WWWW....",
			"....W..W....", "...WW..WW...", "...W....W...", "..WW....WW..",
		},
		{ // walk B — legs together
			"...YYYYYY...", "..YYYYYYYY..", "..YYYYYYYY..", "...WWWWWW...",
			"...W.WW.W...", "...WWWWWW...", "....WWWW....", "..WWWWWWWW..",
			".WW.WWWW.WW.", ".WW.WWWW.WW.", ".W..WWWW..W.", "....WWWW....",
			"....W.W.....", "....W.W.....", "....W.W.....", "...WW.WW....",
		},
		{ // jump — legs tucked
			"...YYYYYY...", "..YYYYYYYY..", "..YYYYYYYY..", "...WWWWWW...",
			"...W.WW.W...", "...WWWWWW...", "....WWWW....", "..WWWWWWWW..",
			".WW.WWWW.WW.", ".WW.WWWW.WW.", ".W..WWWW..W.", "....WWWW....",
			"...WW..WW...", "...WW..WW...", "............", "............",
		},
	};

	private static final float PLATFORM_Y = 148f; // the miner's feet rest here
	private float minerX  = 40f;
	private float minerY  = PLATFORM_Y;
	private float minerVx = 0.7f;
	private float minerVy = 0f;

	// ---- SCREEN$ loading sequence ----
	private final int[] revealRank = new int[SCREEN_H];
	private int pixelProgress = SCREEN_H;   // scanlines arrived (interleaved order)
	private int attrProgress  = COLS * ROWS; // attribute cells coloured in

	// ---- Border stripes ----
	private float stripePos = 0f;

	// ---- State ----
	private String trackTitle = "";
	private int    idleFrames = 0;

	ZxSpectrumVisualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		// Log-spaced VU bands, 45 Hz .. 12 kHz.
		float logMin = (float) Math.log(45), logMax = (float) Math.log(12000);
		for (int b = 0; b <= NUM_BARS; b++) {
			float freq = (float) Math.exp(logMin + (logMax - logMin) * b / NUM_BARS);
			barBin[b] = clamp(Math.round(freq * FFT_SIZE / SAMPLE_RATE), 1, FFT_SIZE / 2);
		}
		for (int b = 1; b <= NUM_BARS; b++) {
			if (barBin[b] <= barBin[b - 1]) barBin[b] = barBin[b - 1] + 1;
		}
		// The Spectrum's famous interleaved display file order: three 64-line
		// thirds; within a third all first char-lines arrive, then all second...
		for (int i = 0; i < SCREEN_H; i++) {
			int third = i / 64, s = i % 64;
			revealRank[third * 64 + (s % 8) * 8 + s / 8] = i;
		}
		for (int a = 0; a < 16; a++) {
			for (int b = 0; b < 16; b++) {
				int dr = ((PAL[a] >> 16) & 0xFF) - ((PAL[b] >> 16) & 0xFF);
				int dg = ((PAL[a] >> 8) & 0xFF) - ((PAL[b] >> 8) & 0xFF);
				int db = (PAL[a] & 0xFF) - (PAL[b] & 0xFF);
				palDist[a][b] = dr * dr + dg * dg + db * db;
			}
		}
	}

	/** New tune: LOAD "" it — header line, interleaved pixel reveal, attributes last. */
	void setTrackTitle(String title) {
		trackTitle = title == null ? "" : title;
		pixelProgress = 0;
		attrProgress  = 0;
	}

	// ---- Render entry ----

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {
		int samples = ring != null ? ring.snapshot(snapshot, FFT_SIZE) : 0;
		boolean hasAudio = samples >= 512;

		if (hasAudio) {
			analyze(samples);
			idleFrames = 0;
		} else {
			for (int b = 0; b < NUM_BARS; b++) bars[b] *= 0.94f;
			bass   += (0f - bass)   * 0.06f;
			treble += (0f - treble) * 0.06f;
			energy += (0f - energy) * 0.06f;
			idleFrames++;
		}
		advanceState();

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,     RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

		boolean idle = idleFrames > 240;
		if (idle) {
			drawIdleScreen(frameCount);
		} else {
			drawScene(frameCount);
			quantizeAttributes();
			applyLoadingFilter();
		}

		drawBorder(g2, w, h, idle);

		// Screen area: 4:3, centred inside the border.
		int minB = 12;
		int sw = Math.min(w - 2 * minB, (h - 2 * minB) * 4 / 3);
		sw = Math.max(32, sw);
		int sh = sw * 3 / 4;
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
		float targetGain = clamp(0.8f / rawMax, 1f, 400f);
		autoGain += (targetGain - autoGain) * (targetGain < autoGain ? 0.15f : 0.02f);

		float sum = 0f;
		for (int b = 0; b < NUM_BARS; b++) {
			float n = clamp(raw[b] * autoGain, 0f, 1f);
			bars[b] += (n - bars[b]) * (n > bars[b] ? 0.55f : 0.10f);
			sum += bars[b];
			if (bars[b] > barPeak[b]) barPeak[b] = bars[b];
		}
		float nb = clamp((bars[0] + bars[1] + bars[2]) * 0.45f, 0f, 1f);
		float nt = clamp((bars[NUM_BARS - 3] + bars[NUM_BARS - 2] + bars[NUM_BARS - 1]) * 0.45f, 0f, 1f);
		bass   += (nb - bass)   * (nb > bass   ? 0.5f : 0.12f);
		treble += (nt - treble) * (nt > treble ? 0.5f : 0.12f);
		energy += (sum / NUM_BARS - energy) * 0.15f;

		bassAvg += (bass - bassAvg) * 0.02f;
		framesSinceBeat++;
		if (framesSinceBeat > 14 && bass > 0.14f && bass > bassAvg * 1.45f + 0.03f) {
			onBeat();
		}

		detectPianoNotes();
	}

	/** Light the piano keys under the strongest spectral peaks (melody range). */
	private void detectPianoNotes() {
		int lo = Math.max(2, Math.round(80f * FFT_SIZE / SAMPLE_RATE));
		int hi = Math.min(FFT_SIZE / 2 - 1, Math.round(2200f * FFT_SIZE / SAMPLE_RATE));
		float globalMax = 1e-6f;
		for (int k = lo; k <= hi; k++) {
			float m = re[k] * re[k] + im[k] * im[k];
			if (m > globalMax) globalMax = m;
		}
		int found = 0;
		for (int k = lo + 1; k < hi && found < 3; k++) {
			float m  = re[k] * re[k] + im[k] * im[k];
			float mp = re[k - 1] * re[k - 1] + im[k - 1] * im[k - 1];
			float mn = re[k + 1] * re[k + 1] + im[k + 1] * im[k + 1];
			if (m > mp && m >= mn && m > globalMax * 0.30f) {
				float freq = (float) k * SAMPLE_RATE / FFT_SIZE;
				int midi = Math.round(69f + 12f * (float) (Math.log(freq / 440.0) / Math.log(2)));
				while (midi < 60) midi += 12;
				while (midi > 83) midi -= 12;
				int pc = midi % 12, octave = (midi - 60) / 12;
				if (WHITE_OF[pc] >= 0) whiteLit[octave * 7 + WHITE_OF[pc]] = 1f;
				else                   blackLit[octave * 5 + BLACK_OF[pc]] = 1f;
				found++;
			}
		}
	}

	private void onBeat() {
		framesSinceBeat = 0;
		borderFlash = 2;
		// Miner Willy jumps for joy (only if he's on the platform).
		if (minerY >= PLATFORM_Y) {
			minerVy = -3.4f;
		}
	}

	private void advanceState() {
		if (borderFlash > 0) borderFlash--;
		for (int i = 0; i < WHITE_KEYS; i++) whiteLit[i] *= 0.85f;
		for (int i = 0; i < blackLit.length; i++) blackLit[i] *= 0.85f;
		for (int b = 0; b < NUM_BARS; b++) barPeak[b] = Math.max(bars[b], barPeak[b] - 0.006f);

		// Miner physics in virtual-screen coordinates.
		minerX += minerVx * (1f + energy * 1.5f);
		if (minerX < 8)               { minerX = 8;               minerVx = Math.abs(minerVx); }
		if (minerX > SCREEN_W - 20)   { minerX = SCREEN_W - 20;   minerVx = -Math.abs(minerVx); }
		if (minerY < PLATFORM_Y || minerVy < 0) {
			minerVy += 0.22f;
			minerY  += minerVy;
			if (minerY >= PLATFORM_Y) { minerY = PLATFORM_Y; minerVy = 0f; }
		}

		// SCREEN$ load: pixels stream in first, attributes afterwards.
		if (pixelProgress < SCREEN_H) {
			pixelProgress = Math.min(SCREEN_H, pixelProgress + 3);
		} else if (attrProgress < COLS * ROWS) {
			attrProgress = Math.min(COLS * ROWS, attrProgress + 24);
		}
	}

	// ---- Scene (drawn in full colour, then attribute-quantized) ----

	private void drawScene(int frameCount) {
		Graphics2D g = screen.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setColor(new Color(BLACK));
			g.fillRect(0, 0, SCREEN_W, SCREEN_H);

			drawBars(g);
			drawPlatform(g);
			drawPiano(g);
			drawMiner(frameCount);
			drawHeader(g, frameCount);
		} finally {
			g.dispose();
		}
	}

	private void drawBars(Graphics2D g) {
		for (int b = 0; b < NUM_BARS; b++) {
			int x = b * 16 + 1;
			int hMax = 120;
			int bh = Math.round(bars[b] * hMax);
			// Brick courses, VU-coloured bottom to top: green -> yellow -> red.
			for (int segY = 0; segY < bh; segY += 6) {
				float f = segY / (float) hMax;
				int col = f < 0.5f ? GREEN : f < 0.78f ? YELLOW : RED_B;
				int y = Math.round(PLATFORM_Y) - segY - 5;
				g.setColor(new Color(col));
				g.fillRect(x, y, 14, 5);
			}
			// Falling peak cap in bright white.
			int py = Math.round(PLATFORM_Y) - Math.round(barPeak[b] * hMax) - 2;
			if (barPeak[b] > 0.02f) {
				g.setColor(new Color(WHITE_B));
				g.fillRect(x, py, 14, 2);
			}
		}
	}

	private void drawPlatform(Graphics2D g) {
		int y = Math.round(PLATFORM_Y);
		g.setColor(new Color(RED));
		g.fillRect(0, y, SCREEN_W, 8);
		g.setColor(new Color(BLACK));
		g.drawLine(0, y + 3, SCREEN_W, y + 3);
		for (int x = 0; x < SCREEN_W; x += 16) {
			g.drawLine(x, y, x, y + 3);
			g.drawLine(x + 8, y + 4, x + 8, y + 7);
		}
	}

	private void drawPiano(Graphics2D g) {
		int y0 = 156, kh = SCREEN_H - y0;
		for (int i = 0; i < WHITE_KEYS; i++) {
			int x0 = Math.round(i * (float) SCREEN_W / WHITE_KEYS);
			int x1 = Math.round((i + 1) * (float) SCREEN_W / WHITE_KEYS);
			g.setColor(new Color(whiteLit[i] > 0.3f ? CYAN_B : WHITE));
			g.fillRect(x0, y0, x1 - x0, kh);
			g.setColor(new Color(BLACK));
			g.drawRect(x0, y0, x1 - x0, kh);
		}
		for (int octave = 0; octave < 2; octave++) {
			for (int s = 0; s < 5; s++) {
				int boundary = octave * 7 + BLACK_POS[s] + 1;
				int x = Math.round(boundary * (float) SCREEN_W / WHITE_KEYS);
				g.setColor(new Color(blackLit[octave * 5 + s] > 0.3f ? MAGENTA_B : BLACK));
				g.fillRect(x - 5, y0, 10, kh * 3 / 5);
			}
		}
	}

	private void drawMiner(int frameCount) {
		boolean airborne = minerY < PLATFORM_Y;
		int frame = airborne ? 2 : (frameCount / 8) % 2;
		String[] art = SPRITE_FRAMES[frame];
		boolean flip = minerVx < 0;
		int ox = Math.round(minerX);
		int oy = Math.round(minerY) - 16;

		for (int r = 0; r < art.length; r++) {
			String row = art[r];
			int y = oy + r;
			if (y < 0 || y >= SCREEN_H) continue;
			for (int c = 0; c < SPRITE_W && c < row.length(); c++) {
				char ch = row.charAt(flip ? SPRITE_W - 1 - c : c);
				if (ch == '.') continue;
				int x = ox + c;
				if (x < 0 || x >= SCREEN_W) continue;
				px[y * SCREEN_W + x] = ch == 'Y' ? YELLOW_B : WHITE_B;
			}
		}
	}

	private void drawHeader(Graphics2D g, int frameCount) {
		g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
		g.setColor(new Color(WHITE));
		String title = trackTitle.isBlank() ? "tape 1" : trackTitle;
		if (title.length() > 22) title = title.substring(0, 22);
		g.drawString("Program: " + title, 4, 11);
		// FLASH-attribute style cursor block while "loading".
		if (attrProgress < COLS * ROWS && (frameCount / 16) % 2 == 0) {
			g.fillRect(4 + (9 + title.length()) * 6 + 4, 3, 8, 10);
		}
	}

	// ---- Idle: boot screen, then the heartbreak ----

	private void drawIdleScreen(int frameCount) {
		Graphics2D g = screen.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setColor(new Color(WHITE));
			g.fillRect(0, 0, SCREEN_W, SCREEN_H);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
			g.setColor(new Color(BLACK));
			if (idleFrames > 840) {
				g.drawString("R Tape loading error, 0:1", 4, SCREEN_H - 6);
			} else {
				g.drawString("© 1982 Sinclair Research Ltd", 4, SCREEN_H - 6);
				if ((frameCount / 16) % 2 == 0) {
					// Flashing [K] cursor, ink-on-paper inverted.
					g.fillRect(4, SCREEN_H - 28, 8, 10);
					g.setColor(new Color(WHITE));
					g.drawString("K", 5, SCREEN_H - 19);
				}
			}
		} finally {
			g.dispose();
		}
	}

	// ---- Attribute quantizer: two palette colours per 8x8 cell, like the real ULA ----

	private void quantizeAttributes() {
		for (int cy = 0; cy < ROWS; cy++) {
			for (int cx = 0; cx < COLS; cx++) {
				int base = cy * CELL * SCREEN_W + cx * CELL;
				java.util.Arrays.fill(cellCount, 0);
				for (int yy = 0; yy < CELL; yy++) {
					int rowBase = base + yy * SCREEN_W;
					for (int xx = 0; xx < CELL; xx++) {
						cellCount[palIndex(px[rowBase + xx])]++;
					}
				}
				int top1 = 0, top2 = -1;
				for (int i = 1; i < 16; i++) {
					if (cellCount[i] > cellCount[top1]) top1 = i;
				}
				for (int i = 0; i < 16; i++) {
					if (i != top1 && cellCount[i] > 0 && (top2 < 0 || cellCount[i] > cellCount[top2])) top2 = i;
				}
				if (top2 < 0) continue; // solid cell — nothing to clash

				for (int yy = 0; yy < CELL; yy++) {
					int rowBase = base + yy * SCREEN_W;
					for (int xx = 0; xx < CELL; xx++) {
						int idx = palIndex(px[rowBase + xx]);
						if (idx != top1 && idx != top2) {
							px[rowBase + xx] = PAL[palDist[idx][top1] <= palDist[idx][top2] ? top1 : top2];
						}
					}
				}
			}
		}
	}

	/** Everything is drawn in palette colours; unknown colours fall to nearest. */
	private int palIndex(int rgb) {
		rgb &= 0xFFFFFF;
		for (int i = 0; i < 16; i++) {
			if (PAL[i] == rgb) return i;
		}
		int best = 0, bestD = Integer.MAX_VALUE;
		int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
		for (int i = 0; i < 16; i++) {
			int dr = r - ((PAL[i] >> 16) & 0xFF), dg = g - ((PAL[i] >> 8) & 0xFF), db = b - (PAL[i] & 0xFF);
			int d = dr * dr + dg * dg + db * db;
			if (d < bestD) { bestD = d; best = i; }
		}
		return best;
	}

	// ---- SCREEN$ loading filter: interleaved mono ghosts, then attributes ----

	private void applyLoadingFilter() {
		if (pixelProgress >= SCREEN_H && attrProgress >= COLS * ROWS) return;

		for (int y = 0; y < SCREEN_H; y++) {
			int rowBase = y * SCREEN_W;
			if (revealRank[y] >= pixelProgress) {
				// This scanline hasn't arrived from tape yet: blank paper.
				java.util.Arrays.fill(px, rowBase, rowBase + SCREEN_W, WHITE);
			} else if (attrProgress < (y / CELL) * COLS + COLS) {
				// Pixels are in but this row's attributes may not be: mono ghost
				// (ink on paper) for every cell still waiting for its colour byte.
				int cellRowStart = (y / CELL) * COLS;
				for (int x = 0; x < SCREEN_W; x++) {
					if (cellRowStart + x / CELL >= attrProgress) {
						px[rowBase + x] = (px[rowBase + x] & 0xFFFFFF) == BLACK ? WHITE : BLACK;
					}
				}
			}
		}
	}

	// ---- Border ----

	private void drawBorder(Graphics2D g2, int w, int h, boolean idle) {
		if (borderFlash > 0) {
			g2.setColor(new Color(WHITE_B));
			g2.fillRect(0, 0, w, h);
			return;
		}
		boolean loading = pixelProgress < SCREEN_H || attrProgress < COLS * ROWS;
		stripePos += idle ? 0.5f : 1.2f + energy * 4f + treble * 5f + (loading ? 3f : 0f);

		for (int y = 0; y < h; y += 2) {
			int c;
			if (idle) {
				// Pilot tone: broad red/cyan bands.
				int n = (int) Math.floor((y * 0.55f + stripePos) * 0.055f);
				c = (n & 1) == 0 ? RED : CYAN;
			} else {
				// Data blocks: frantic blue/yellow bands with pseudo-random widths.
				int n = (int) Math.floor((y * 0.55f + stripePos) * 0.33f);
				c = ((n * 0x9E3779B9) >>> 16 & 1) == 0 ? BLUE_B : YELLOW_B;
			}
			g2.setColor(new Color(c));
			g2.fillRect(0, y, w, 2);
		}
	}

	// ---- Helpers ----

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
