package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * "Game Boy" — a pea-soup-green handheld tribute, complete with the whole
 * console drawn around the screen.
 * <p>
 * The panel becomes the machine itself: grey shell, dark bezel with a
 * battery LED that glows with the music's energy, d-pad, burgundy A/B
 * buttons and START/SELECT pills — and they play along: A presses on every
 * beat, B on treble hits, the d-pad leans towards whichever end of the
 * spectrum is winning.
 * <p>
 * On the 160x144 four-shade screen, a falling-blocks game plays itself: a
 * greedy little AI places tetrominoes into a well, the drop speed rides the
 * energy, every beat slams the active piece down a step, full rows flash
 * and clear, and topping out triggers the classic fill-and-wipe game-over
 * curtain before a fresh game begins. The side panel keeps score, level,
 * lines, the NEXT box, and a four-band EQ. Every frame is blended with the
 * previous one — genuine DMG LCD ghosting. A new track boots with the logo
 * dropping down the screen; silence blinks {@code PAUSE}, and a long
 * silence simply switches the LCD off.
 */
final class GameBoyVisualizer {

	// ---- LCD ----
	private static final int SCREEN_W = 160;
	private static final int SCREEN_H = 144;
	/** DMG shades, darkest to lightest. */
	private static final int G0 = 0x0F380F, G1 = 0x306230, G2 = 0x8BAC0F, G3 = 0x9BBC0F;

	private final BufferedImage screen = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB);
	private final int[] px = ((DataBufferInt) screen.getRaster().getDataBuffer()).getData();
	private final int[] prevPx = new int[SCREEN_W * SCREEN_H];

	// ---- FFT / analysis ----
	private static final int FFT_SIZE    = 2048;
	private static final int SAMPLE_RATE = 44100;
	private static final int NUM_BANDS   = 4;

	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   bandBin  = new int[NUM_BANDS + 1];
	private final float[] bands    = new float[NUM_BANDS];

	private float bass, treble, energy;
	private float bassAvg, trebleAvg;
	private float autoGain = 8f;
	private int   framesSinceBeat = 999;
	private float beatPulse = 0f;
	private int   bPress = 0;

	// ---- Falling blocks ----
	private static final int GRID_W = 10, GRID_H = 16;
	private static final int FIELD_X = 8, FIELD_Y = 8;   // pixels; cells are 8x8
	private final byte[][] grid = new byte[GRID_H][GRID_W];

	/** [piece][rotation][cell] -> packed x<<4|y. Built once from base shapes. */
	private static final int[][][] PIECES = buildPieces();
	private static final byte[] PIECE_SHADE = { 2, 1, 2, 1, 2, 1, 2 }; // I O T S Z J L

	private int  pType, pRot, pCol;
	private float pRow;
	private int  targetCol, targetRot;
	private int  nextType;
	private int  moveTick = 0;
	private float fallAcc = 0f;

	private int score = 0, lines = 0;
	private final java.util.List<Integer> flashRows = new java.util.ArrayList<>();
	private int flashTimer = 0;
	private int gameOverTimer = 0;

	// ---- State ----
	private int bootTimer = 0;
	private int idleFrames = 0;
	private int frame = 0;
	private final Random rnd = new Random(0xB0B0);

	GameBoyVisualizer() {
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
		nextType = rnd.nextInt(7);
		spawnPiece();
	}

	private static int[][][] buildPieces() {
		float[][][] base = {
			{ { 0, 1 }, { 1, 1 }, { 2, 1 }, { 3, 1 } },   // I
			{ { 1, 0 }, { 2, 0 }, { 1, 1 }, { 2, 1 } },   // O
			{ { 0, 1 }, { 1, 1 }, { 2, 1 }, { 1, 0 } },   // T
			{ { 1, 0 }, { 2, 0 }, { 0, 1 }, { 1, 1 } },   // S
			{ { 0, 0 }, { 1, 0 }, { 1, 1 }, { 2, 1 } },   // Z
			{ { 0, 0 }, { 0, 1 }, { 1, 1 }, { 2, 1 } },   // J
			{ { 2, 0 }, { 0, 1 }, { 1, 1 }, { 2, 1 } },   // L
		};
		float[][] pivot = {
			{ 1.5f, 1.5f }, { 1.5f, 0.5f }, { 1, 1 }, { 1, 1 }, { 1, 1 }, { 1, 1 }, { 1, 1 },
		};
		int[][][] out = new int[7][4][4];
		for (int p = 0; p < 7; p++) {
			float[][] cells = base[p];
			for (int r = 0; r < 4; r++) {
				for (int c = 0; c < 4; c++) {
					int x = Math.round(cells[c][0]);
					int y = Math.round(cells[c][1]);
					out[p][r][c] = (x + 2) << 4 | (y + 2);   // +2 bias keeps values positive
				}
				// Rotate the working copy clockwise around the pivot for the next slot.
				float[][] rotated = new float[4][2];
				for (int c = 0; c < 4; c++) {
					float dx = cells[c][0] - pivot[p][0];
					float dy = cells[c][1] - pivot[p][1];
					rotated[c][0] = pivot[p][0] - dy;
					rotated[c][1] = pivot[p][1] + dx;
				}
				cells = rotated;
			}
		}
		return out;
	}

	private static int cellX(int packed) { return (packed >> 4) - 2; }
	private static int cellY(int packed) { return (packed & 0xF) - 2; }

	/** New tune: power-cycle the handheld, logo drop and all. */
	void setTrackTitle(String title) {
		bootTimer = 110;
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
			treble += (0f - treble) * 0.06f;
			energy += (0f - energy) * 0.06f;
			idleFrames++;
		}
		beatPulse *= 0.9f;
		if (bPress > 0) bPress--;
		if (bootTimer > 0) bootTimer--;

		if (idleFrames > 900) {
			java.util.Arrays.fill(px, G3);   // LCD off
		} else if (bootTimer > 0) {
			drawBootLogo();
		} else {
			if (idleFrames <= 240) stepGame();
			drawGame();
			if (idleFrames > 240) drawPauseOverlay();
		}
		applyGhosting();

		drawShell(g2, w, h);
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
		bass   += (bands[0] - bass)              * (bands[0] > bass   ? 0.5f : 0.12f);
		treble += (bands[NUM_BANDS - 1] - treble) * (bands[NUM_BANDS - 1] > treble ? 0.5f : 0.12f);
		energy += (sum / NUM_BANDS - energy) * 0.15f;

		bassAvg += (bass - bassAvg) * 0.02f;
		framesSinceBeat++;
		if (framesSinceBeat > 14 && bass > 0.14f && bass > bassAvg * 1.45f + 0.03f) {
			framesSinceBeat = 0;
			beatPulse = 1f;
			fallAcc += 1f;   // the beat slams the piece down a step
			score += 10;
		}
		trebleAvg += (treble - trebleAvg) * 0.02f;
		if (bPress <= 0 && treble > 0.22f && treble > trebleAvg * 1.8f + 0.05f) {
			bPress = 8;
		}
	}

	// ---- Game simulation ----

	private void stepGame() {
		if (gameOverTimer > 0) {
			stepGameOverCurtain();
			return;
		}
		if (flashTimer > 0) {
			if (--flashTimer == 0) collapseFlashedRows();
			return;
		}

		// Steer towards the planned placement, one nudge at a time.
		if (++moveTick % 4 == 0) {
			if (pRot != targetRot && fits(pType, (pRot + 1) % 4, pCol, (int) pRow)) {
				pRot = (pRot + 1) % 4;
			} else if (pCol < targetCol && fits(pType, pRot, pCol + 1, (int) pRow)) {
				pCol++;
			} else if (pCol > targetCol && fits(pType, pRot, pCol - 1, (int) pRow)) {
				pCol--;
			}
		}

		fallAcc += 0.04f + energy * 0.16f;
		while (fallAcc >= 1f) {
			fallAcc -= 1f;
			if (fits(pType, pRot, pCol, (int) pRow + 1)) {
				pRow++;
			} else {
				lockPiece();
				return;
			}
		}
	}

	private boolean fits(int type, int rot, int col, int row) {
		for (int packed : PIECES[type][rot]) {
			int x = col + cellX(packed);
			int y = row + cellY(packed);
			if (x < 0 || x >= GRID_W || y >= GRID_H) return false;
			if (y >= 0 && grid[y][x] != 0) return false;
		}
		return true;
	}

	private void lockPiece() {
		for (int packed : PIECES[pType][pRot]) {
			int x = pCol + cellX(packed);
			int y = (int) pRow + cellY(packed);
			if (y < 0) { startGameOver(); return; }
			grid[y][x] = PIECE_SHADE[pType];
		}
		score += 15;
		flashRows.clear();
		for (int y = 0; y < GRID_H; y++) {
			boolean full = true;
			for (int x = 0; x < GRID_W; x++) {
				if (grid[y][x] == 0) { full = false; break; }
			}
			if (full) flashRows.add(y);
		}
		if (!flashRows.isEmpty()) {
			flashTimer = 20;
		}
		spawnPiece();
	}

	private void collapseFlashedRows() {
		for (int row : flashRows) {
			for (int y = row; y > 0; y--) {
				System.arraycopy(grid[y - 1], 0, grid[y], 0, GRID_W);
			}
			java.util.Arrays.fill(grid[0], (byte) 0);
		}
		lines += flashRows.size();
		score += flashRows.size() * flashRows.size() * 100;
		flashRows.clear();
	}

	private void spawnPiece() {
		pType = nextType;
		nextType = rnd.nextInt(7);
		pRot = 0;
		pCol = 3;
		pRow = -2;
		fallAcc = 0f;
		planPlacement();
		if (!fits(pType, pRot, pCol, 0)) {
			startGameOver();
		}
	}

	/** Greedy AI: prefer the deepest landing that digs the fewest holes. */
	private void planPlacement() {
		int bestScore = Integer.MIN_VALUE;
		targetCol = pCol;
		targetRot = 0;
		for (int rot = 0; rot < 4; rot++) {
			for (int col = -2; col < GRID_W; col++) {
				if (!fits(pType, rot, col, 0)) continue;
				int row = 0;
				while (fits(pType, rot, col, row + 1)) row++;
				int depth = 0, holes = 0;
				for (int packed : PIECES[pType][rot]) {
					int x = col + cellX(packed);
					int y = row + cellY(packed);
					depth += y;
					int below = y + 1;
					if (below < GRID_H && (y < 0 || grid[below][x] == 0) && !occupiedBySelf(rot, col, row, x, below)) {
						holes++;
					}
				}
				int s = depth * 3 - holes * 11 + rnd.nextInt(2);
				if (s > bestScore) {
					bestScore = s;
					targetCol = col;
					targetRot = rot;
				}
			}
		}
	}

	private boolean occupiedBySelf(int rot, int col, int row, int qx, int qy) {
		for (int packed : PIECES[pType][rot]) {
			if (col + cellX(packed) == qx && row + cellY(packed) == qy) return true;
		}
		return false;
	}

	private void startGameOver() {
		gameOverTimer = GRID_H * 4;
	}

	/** The classic top-out curtain: fill upward with bricks, then wipe clean. */
	private void stepGameOverCurtain() {
		gameOverTimer--;
		int step = GRID_H * 4 - gameOverTimer;
		if (step <= GRID_H * 2) {
			int row = GRID_H - 1 - (step / 2);
			if (row >= 0 && (step & 1) == 0) {
				for (int x = 0; x < GRID_W; x++) grid[row][x] = 1;
			}
		} else {
			int row = (step - GRID_H * 2) / 2;
			if (row < GRID_H && (step & 1) == 0) {
				java.util.Arrays.fill(grid[row], (byte) 0);
			}
		}
		if (gameOverTimer == 0) {
			for (byte[] r : grid) java.util.Arrays.fill(r, (byte) 0);
			score = 0;
			lines = 0;
			spawnPiece();
		}
	}

	// ---- LCD drawing ----

	private void drawGame() {
		java.util.Arrays.fill(px, G3);

		// Well walls with the classic tile pattern.
		for (int y = 0; y < SCREEN_H; y += 8) {
			drawWallTile(0, y);
			drawWallTile(FIELD_X + GRID_W * 8, y);
		}
		fill(FIELD_X, 0, GRID_W * 8, SCREEN_H, G3);

		// Settled blocks (flashing rows blink light/dark).
		for (int y = 0; y < GRID_H; y++) {
			boolean flashing = flashTimer > 0 && flashRows.contains(y);
			for (int x = 0; x < GRID_W; x++) {
				if (grid[y][x] == 0) continue;
				if (flashing && (flashTimer / 4) % 2 == 0) {
					fill(FIELD_X + x * 8, FIELD_Y + y * 8, 8, 8, G3);
				} else {
					drawBlock(FIELD_X + x * 8, FIELD_Y + y * 8, grid[y][x]);
				}
			}
		}

		// Active piece.
		if (gameOverTimer == 0) {
			for (int packed : PIECES[pType][pRot]) {
				int x = pCol + cellX(packed);
				int y = (int) pRow + cellY(packed);
				if (y >= 0) drawBlock(FIELD_X + x * 8, FIELD_Y + y * 8, PIECE_SHADE[pType]);
			}
		}

		drawSidePanel();
	}

	private void drawWallTile(int x, int y) {
		fill(x, y, 8, 8, G1);
		fill(x + 1, y + 1, 2, 2, G3);
		fill(x + 4, y + 4, 2, 2, G3);
		fill(x, y + 7, 8, 1, G0);
	}

	private void drawBlock(int x, int y, int shade) {
		int body = shade >= 2 ? G2 : G1;
		fill(x, y, 8, 8, G0);
		fill(x + 1, y + 1, 6, 6, body);
		fill(x + 2, y + 2, 2, 2, G3);
	}

	private void drawSidePanel() {
		int x0 = FIELD_X + GRID_W * 8 + 10;
		Graphics2D g = screen.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 9));
			g.setColor(new Color(G0));
			g.drawString("SCORE", x0, 14);
			g.drawString(String.format("%06d", score % 1000000), x0, 24);
			g.drawString("LEVEL " + (lines / 10), x0, 40);
			g.drawString("LINES " + lines, x0, 52);
			g.drawString("NEXT", x0, 68);
		} finally {
			g.dispose();
		}

		// NEXT box.
		fill(x0, 72, 36, 36, G2);
		fill(x0 + 1, 73, 34, 34, G3);
		for (int packed : PIECES[nextType][0]) {
			drawBlock(x0 + 2 + cellX(packed) * 8, 74 + cellY(packed) * 8, PIECE_SHADE[nextType]);
		}

		// Four-band EQ at the bottom of the panel.
		for (int b = 0; b < NUM_BANDS; b++) {
			int bh = Math.round(bands[b] * 24f);
			fill(x0 + b * 10, 140 - bh, 7, bh, G1);
			fill(x0 + b * 10, 140 - bh, 7, 2, G0);
		}
	}

	private void drawBootLogo() {
		java.util.Arrays.fill(px, G3);
		Graphics2D g = screen.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 16));
			g.setColor(new Color(G0));
			int logoY = bootTimer > 50 ? 70 - (bootTimer - 50) * 2 : 70;
			g.drawString("NUCLRBOY", 44, logoY);
		} finally {
			g.dispose();
		}
	}

	private void drawPauseOverlay() {
		if ((frame / 20) % 2 == 0) {
			fill(FIELD_X + 8, 60, 64, 16, G3);
			Graphics2D g = screen.createGraphics();
			try {
				g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
				g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
				g.setColor(new Color(G0));
				g.drawString("PAUSE", FIELD_X + 22, 72);
			} finally {
				g.dispose();
			}
		}
	}

	/** DMG LCD ghosting: each frame smears into the next. */
	private void applyGhosting() {
		for (int i = 0; i < px.length; i++) {
			int n = px[i], p = prevPx[i];
			int r = (((n >> 16 & 0xFF) * 3 + (p >> 16 & 0xFF) * 2) / 5) << 16;
			int gch = (((n >> 8 & 0xFF) * 3 + (p >> 8 & 0xFF) * 2) / 5) << 8;
			int b = ((n & 0xFF) * 3 + (p & 0xFF) * 2) / 5;
			int c = r | gch | b;
			px[i] = c;
			prevPx[i] = c;
		}
	}

	// ---- The console shell ----

	private void drawShell(Graphics2D g2, int w, int h) {
		g2.setColor(new Color(0x14, 0x14, 0x18));
		g2.fillRect(0, 0, w, h);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		float sh = Math.min(h * 0.98f, w * 0.98f / 0.62f);
		float sw = sh * 0.62f;
		float sx = (w - sw) / 2f;
		float sy = (h - sh) / 2f;
		int arc = Math.round(sw * 0.14f);

		// Body.
		g2.setColor(new Color(0xC8, 0xC8, 0xD0));
		g2.fillRoundRect(Math.round(sx), Math.round(sy), Math.round(sw), Math.round(sh), arc, arc);
		g2.setColor(new Color(0x90, 0x90, 0x98));
		g2.setStroke(new BasicStroke(2f));
		g2.drawRoundRect(Math.round(sx), Math.round(sy), Math.round(sw), Math.round(sh), arc, arc);

		// Bezel.
		float bx = sx + sw * 0.08f, by = sy + sh * 0.055f, bw = sw * 0.84f, bh = sh * 0.40f;
		g2.setColor(new Color(0x3C, 0x3C, 0x4A));
		g2.fillRoundRect(Math.round(bx), Math.round(by), Math.round(bw), Math.round(bh), 14, 14);

		// Battery LED, glowing with the music.
		int ledA = clampI(Math.round(70 + energy * 185), 0, 255);
		g2.setColor(new Color(255, 40, 40, ledA));
		g2.fillOval(Math.round(bx + bw * 0.045f), Math.round(by + bh * 0.44f), 8, 8);

		// The LCD itself.
		float scrW = bw * 0.68f;
		float scrH = scrW * SCREEN_H / SCREEN_W;
		if (scrH > bh * 0.82f) { scrH = bh * 0.82f; scrW = scrH * SCREEN_W / SCREEN_H; }
		float scrX = bx + (bw - scrW) * 0.62f;
		float scrY = by + (bh - scrH) / 2f;
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.drawImage(screen, Math.round(scrX), Math.round(scrY), Math.round(scrW), Math.round(scrH), null);

		// Branding.
		if (sw > 170) {
			g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD | Font.ITALIC, Math.max(10, Math.round(sw * 0.075f))));
			g2.setColor(new Color(0x2A, 0x2A, 0x55));
			g2.drawString("NUCLRBOY", Math.round(sx + sw * 0.09f), Math.round(sy + sh * 0.52f));
		}

		// D-pad; the pressed arm follows the dominant end of the spectrum.
		float dcx = sx + sw * 0.22f, dcy = sy + sh * 0.66f;
		float arm = sw * 0.085f, thick = sw * 0.055f;
		g2.setColor(new Color(0x28, 0x28, 0x30));
		g2.fillRoundRect(Math.round(dcx - arm), Math.round(dcy - thick / 2), Math.round(arm * 2), Math.round(thick), 5, 5);
		g2.fillRoundRect(Math.round(dcx - thick / 2), Math.round(dcy - arm), Math.round(thick), Math.round(arm * 2), 5, 5);
		if (energy > 0.15f) {
			g2.setColor(new Color(0x55, 0x55, 0x66));
			if (bass > treble * 1.2f) {
				g2.fillRoundRect(Math.round(dcx - arm), Math.round(dcy - thick / 2) + 2, Math.round(arm * 0.8f), Math.round(thick) - 4, 4, 4);
			} else if (treble > bass * 1.2f) {
				g2.fillRoundRect(Math.round(dcx + arm * 0.2f), Math.round(dcy - thick / 2) + 2, Math.round(arm * 0.8f), Math.round(thick) - 4, 4, 4);
			}
		}

		// A / B buttons: A fires on beats, B on treble hits.
		drawShellButton(g2, sx + sw * 0.84f, sy + sh * 0.615f, sw * 0.058f, "A", beatPulse > 0.45f, sw);
		drawShellButton(g2, sx + sw * 0.70f, sy + sh * 0.655f, sw * 0.058f, "B", bPress > 0, sw);

		// START / SELECT.
		g2.setColor(new Color(0x6A, 0x6A, 0x72));
		g2.fillRoundRect(Math.round(sx + sw * 0.36f), Math.round(sy + sh * 0.78f), Math.round(sw * 0.10f), Math.round(sh * 0.017f) + 4, 8, 8);
		g2.fillRoundRect(Math.round(sx + sw * 0.52f), Math.round(sy + sh * 0.78f), Math.round(sw * 0.10f), Math.round(sh * 0.017f) + 4, 8, 8);

		// Speaker grille.
		g2.setColor(new Color(0xA0, 0xA0, 0xA8));
		g2.setStroke(new BasicStroke(Math.max(2f, sw * 0.014f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		for (int i = 0; i < 6; i++) {
			int lx = Math.round(sx + sw * (0.66f + i * 0.045f));
			int ly = Math.round(sy + sh * 0.88f);
			g2.drawLine(lx, ly + Math.round(sh * 0.045f), lx + Math.round(sw * 0.04f), ly);
		}
	}

	private void drawShellButton(Graphics2D g2, float cx, float cy, float r, String label, boolean pressed, float sw) {
		int d = Math.round(r * 2);
		g2.setColor(pressed ? new Color(0x6E, 0x1F, 0x38) : new Color(0x96, 0x2B, 0x4E));
		g2.fillOval(Math.round(cx - r), Math.round(cy - r) + (pressed ? 2 : 0), d, d);
		if (!pressed) {
			g2.setColor(new Color(0xC0, 0x5A, 0x7E));
			g2.fillOval(Math.round(cx - r * 0.5f), Math.round(cy - r * 0.7f), Math.round(r * 0.6f), Math.round(r * 0.5f));
		}
		if (sw > 170) {
			g2.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(9, Math.round(sw * 0.045f))));
			g2.setColor(new Color(0x2A, 0x2A, 0x55));
			g2.drawString(label, Math.round(cx - r * 0.3f), Math.round(cy + r * 2.2f));
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
