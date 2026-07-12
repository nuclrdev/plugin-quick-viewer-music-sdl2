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
 * "Dendy" — a tribute to the grey elephant-branded NES clone that raised a
 * whole hemisphere, and to the game everyone played on it: TANK 1990.
 * <p>
 * A virtual 256x240 screen in strict NES-flavoured colours. The spectrum
 * analyzer is a Battle City brick wall: fourteen destructible brick towers
 * that grow and crumble with their frequency bands, shedding debris as they
 * go. A little gold tank patrols the bottom and fires a shell at the wall
 * above it on every beat; each impact knocks an enemy icon off the classic
 * grey sidebar HUD, and clearing the roster advances the stage flag. The
 * eagle base sits at the bottom centre, flashing with the pulse of the mix.
 * When the music runs hot the sprites start to flicker — the genuine
 * too-many-sprites-per-scanline experience.
 * <p>
 * When the music stops, the cartridge unseats: the frozen
 * frame corrupts tile by tile, dissolves into TV static, and the screen
 * offers the only advice that ever worked: {@code ПОДУЙ В КАРТРИДЖ}.
 * <p>
 * Self-contained like its siblings; framebuffer, FFT scratch and particle
 * pool are pre-allocated, and the ring buffer snapshot is read on the EDT.
 */
final class DendyVisualizer {

	// ---- Virtual screen ----
	private static final int SCREEN_W = 256;
	private static final int SCREEN_H = 240;
	private static final int FIELD_W  = 224;  // playfield; the rest is the grey HUD sidebar

	// ---- NES-flavoured palette ----
	private static final int BLACK     = 0x000000;
	private static final int WHITE     = 0xFCFCFC;
	private static final int GRAY_L    = 0xBCBCBC;
	private static final int GRAY      = 0x7C7C7C;
	private static final int GRAY_D    = 0x404040;
	private static final int BRICK     = 0xB53120;
	private static final int BRICK_HI  = 0xE7845C;
	private static final int TANK_BODY = 0xFCBC3C;
	private static final int TANK_DARK = 0xAC7C00;

	private final BufferedImage screen = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB);
	private final int[] px = ((DataBufferInt) screen.getRaster().getDataBuffer()).getData();

	// ---- FFT / analysis ----
	private static final int FFT_SIZE    = 2048;
	private static final int SAMPLE_RATE = 44100;
	private static final int NUM_BARS    = 14;   // one brick tower per 16px playfield column

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

	// ---- Battle scene ----
	private static final int   BASE_Y   = 176;  // brick towers stand on this line
	private static final int   MAX_WALL = 136;  // tallest tower in pixels
	private static final float TANK_Y   = 196f;

	private float tankX  = 40f;
	private float tankVx = 0.8f;

	private static final int MAX_SHELLS = 3;
	private final float[] shellX = new float[MAX_SHELLS];
	private final float[] shellY = new float[MAX_SHELLS];
	private final boolean[] shellLive = new boolean[MAX_SHELLS];

	private static final int MAX_DEBRIS = 48;
	private final float[] dX = new float[MAX_DEBRIS];
	private final float[] dY = new float[MAX_DEBRIS];
	private final float[] dVX = new float[MAX_DEBRIS];
	private final float[] dVY = new float[MAX_DEBRIS];
	private final float[] dLife = new float[MAX_DEBRIS];
	private final int[]   dCol = new int[MAX_DEBRIS];
	private int dNext = 0;

	private int enemyIcons  = 20;
	private int stage       = 1;
	private int stageFlash  = 0;
	private int konamiTimer = 0;
	private int konamiCooldown = 0;

	// ---- Eagle base (16x16; W white, Y beak, G wing grey, D dark/pedestal) ----
	private static final String[] EAGLE = {
		"................",
		".......WW.......",
		"......WWWW......",
		"......WYYW......",
		"..G..GWWWWG..G..",
		".GG.GGWWWWGG.GG.",
		"GGGGGGWWWWGGGGGG",
		"GGGGGGDWWDGGGGGG",
		".GGGGDDWWDDGGGG.",
		"..GGDDDWWDDDGG..",
		"....DDDWWDDD....",
		"......DWWD......",
		".....DDWWDD.....",
		"....DDDDDDDD....",
		"...DDDDDDDDDD...",
		"..DDDDDDDDDDDD..",
	};

	// ---- State ----
	private int    idleFrames = 0;
	private int    frame      = 0;
	private final Random rnd = new Random(0xDE4D1);

	DendyVisualizer() {
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
	}

	/** New tune: fresh stage, straight into battle. */
	void setTrackTitle(String title) {
		idleFrames = 0;
		enemyIcons = 20;
		stage = 1;
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
		advanceState();

		if (idleFrames > 600) {
			drawStatic();
		} else if (idleFrames > 240) {
			corruptFrame(); // cartridge unseating: freeze and glitch the last frame
		} else {
			drawBattlefield();
		}

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.setColor(Color.BLACK);
		g2.fillRect(0, 0, w, h);
		int sw = Math.min(w, h * SCREEN_W / SCREEN_H);
		sw = Math.max(64, sw);
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
		float nb = clampF((bars[0] + bars[1] + bars[2]) * 0.45f, 0f, 1f);
		bass   += (nb - bass) * (nb > bass ? 0.5f : 0.12f);
		energy += (sum / NUM_BARS - energy) * 0.15f;

		bassAvg += (bass - bassAvg) * 0.02f;
		framesSinceBeat++;
		if (framesSinceBeat > 14 && bass > 0.14f && bass > bassAvg * 1.45f + 0.03f) {
			onBeat();
		}
	}

	private void onBeat() {
		framesSinceBeat = 0;
		beatPulse = 1f;
		fireShell();
		if (konamiCooldown <= 0 && energy > 0.72f) {
			konamiTimer = 90;
			konamiCooldown = 1800;
		}
	}

	private void fireShell() {
		for (int i = 0; i < MAX_SHELLS; i++) {
			if (!shellLive[i]) {
				shellLive[i] = true;
				shellX[i] = tankX + 8f;
				shellY[i] = TANK_Y - 2f;
				return;
			}
		}
	}

	private void advanceState() {
		beatPulse *= 0.92f;
		if (stageFlash > 0) stageFlash--;
		if (konamiTimer > 0) konamiTimer--;
		if (konamiCooldown > 0) konamiCooldown--;

		// Tank patrol.
		tankX += tankVx * (1f + energy * 1.6f);
		if (tankX < 4)              { tankX = 4;              tankVx = Math.abs(tankVx); }
		if (tankX > FIELD_W - 20)   { tankX = FIELD_W - 20;   tankVx = -Math.abs(tankVx); }

		// Shells fly up and blast the wall.
		for (int i = 0; i < MAX_SHELLS; i++) {
			if (!shellLive[i]) continue;
			shellY[i] -= 4f;
			int col = clampI((int) (shellX[i] / 16f), 0, NUM_BARS - 1);
			float towerTop = BASE_Y - bars[col] * MAX_WALL;
			if (shellY[i] <= towerTop || shellY[i] < 6f) {
				shellLive[i] = false;
				impact(shellX[i], Math.max(6f, towerTop));
			}
		}

		// Debris physics.
		for (int i = 0; i < MAX_DEBRIS; i++) {
			if (dLife[i] <= 0f) continue;
			dX[i] += dVX[i];
			dY[i] += dVY[i];
			dVY[i] += 0.15f;
			dLife[i] -= 0.03f;
		}
	}

	private void impact(float x, float y) {
		for (int n = 0; n < 8; n++) {
			int i = dNext;
			dNext = (dNext + 1) % MAX_DEBRIS;
			dX[i] = x;
			dY[i] = y;
			dVX[i] = (rnd.nextFloat() - 0.5f) * 3f;
			dVY[i] = -rnd.nextFloat() * 2.5f;
			dLife[i] = 1f;
			dCol[i] = switch (n % 3) { case 0 -> BRICK; case 1 -> BRICK_HI; default -> GRAY; };
		}
		if (--enemyIcons <= 0) {
			enemyIcons = 20;
			stage++;
			stageFlash = 90;
		}
	}

	// ---- Battlefield ----

	private void drawBattlefield() {
		fill(0, 0, FIELD_W, SCREEN_H, BLACK);
		drawWall();
		drawDebris();

		// The genuine 8-sprites-per-scanline experience: flicker under load.
		boolean flickerOut = energy > 0.65f && (frame & 1) == 1;
		if (!flickerOut) {
			drawTank(Math.round(tankX), (int) TANK_Y - 8);
			drawEagle(104, 216);
		}
		for (int i = 0; i < MAX_SHELLS; i++) {
			if (shellLive[i]) fill(Math.round(shellX[i]) - 1, Math.round(shellY[i]) - 2, 2, 4, WHITE);
		}

		drawHud();

		Graphics2D g = screen.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
			if (stageFlash > 0 && (stageFlash / 8) % 2 == 0) {
				String s = "STAGE " + stage;
				g.setColor(new Color(WHITE));
				g.drawString(s, (FIELD_W - s.length() * 6) / 2, 100);
			}
			if (konamiTimer > 0) {
				g.setColor(new Color(GRAY_L));
				g.drawString("^^vv<><>BA", 8, 12);
			}
		} finally {
			g.dispose();
		}
	}

	/** The spectrum as destructible Battle City brickwork. */
	private void drawWall() {
		for (int b = 0; b < NUM_BARS; b++) {
			int colX = b * 16;
			int hPx = Math.round(bars[b] * MAX_WALL);
			int bricks = hPx / 8;
			for (int i = 0; i <= bricks; i++) {
				int by = BASE_Y - (i + 1) * 8;
				int cut = i == bricks ? 8 - (hPx - bricks * 8) : 0; // partial top brick
				if (cut >= 8) continue;
				drawBrickTile(colX, by, cut, i);
			}
		}
	}

	/** One 16x8 brick tile, offset every other course; {@code cut} trims the top rows. */
	private void drawBrickTile(int x, int y, int cut, int course) {
		fill(x, y + cut, 16, 8 - cut, BRICK);
		// Highlight along the surviving top edge of the tile.
		fill(x, y + cut, 16, 1, BRICK_HI);
		// Mortar: bottom line plus staggered vertical joints.
		fill(x, y + 7, 16, 1, BLACK);
		int off = (course & 1) == 0 ? 7 : 3;
		for (int vx = off; vx < 16; vx += 8) {
			fill(x + vx, y + cut, 1, 8 - cut, BLACK);
		}
	}

	private void drawDebris() {
		for (int i = 0; i < MAX_DEBRIS; i++) {
			if (dLife[i] <= 0f) continue;
			int x = Math.round(dX[i]);
			int y = Math.round(dY[i]);
			if (x >= 0 && x < FIELD_W - 1 && y >= 0 && y < SCREEN_H - 1) {
				fill(x, y, 2, 2, dCol[i]);
			}
		}
	}

	/** The gold patrol tank (16x16, barrel up), treads animated while rolling. */
	private void drawTank(int tx, int ty) {
		int phase = (frame / 3) & 1;
		for (int r = 4; r < 16; r++) {
			int tread = ((r + phase) & 1) == 0 ? GRAY_L : GRAY;
			fill(tx, ty + r, 4, 1, tread);
			fill(tx + 12, ty + r, 4, 1, tread);
		}
		fill(tx + 4, ty + 5, 8, 10, TANK_BODY);
		fill(tx + 4, ty + 5, 8, 1, TANK_DARK);
		fill(tx + 4, ty + 14, 8, 1, TANK_DARK);
		fill(tx + 4, ty + 5, 1, 10, TANK_DARK);
		fill(tx + 11, ty + 5, 1, 10, TANK_DARK);
		fill(tx + 6, ty + 7, 4, 5, TANK_DARK);   // turret ring
		fill(tx + 7, ty + 8, 2, 3, TANK_BODY);   // turret cap
		fill(tx + 7, ty, 2, 7, GRAY_L);          // barrel
	}

	private void drawEagle(int ex, int ey) {
		boolean bright = beatPulse > 0.45f;
		for (int r = 0; r < EAGLE.length && r < 16; r++) {
			String row = EAGLE[r];
			for (int c = 0; c < 16 && c < row.length(); c++) {
				int col;
				switch (row.charAt(c)) {
					case 'W' -> col = WHITE;
					case 'Y' -> col = TANK_BODY;
					case 'G' -> col = bright ? GRAY_L : GRAY;
					case 'D' -> col = bright ? GRAY : GRAY_D;
					default  -> { continue; }
				}
				pset(ex + c, ey + r, col);
			}
		}
	}

	/** The grey Battle City sidebar: enemy roster, stage flag, lives. */
	private void drawHud() {
		fill(FIELD_W, 0, SCREEN_W - FIELD_W, SCREEN_H, GRAY_L);

		// Enemy icons, two columns of little tanks, depleted by shell impacts.
		for (int i = 0; i < enemyIcons; i++) {
			int cx = FIELD_W + 8 + (i % 2) * 10;
			int cy = 16 + (i / 2) * 10;
			fill(cx, cy + 2, 7, 4, BLACK);
			fill(cx + 2, cy, 3, 2, BLACK);
		}

		// Stage flag on its pole.
		int fy = 176;
		fill(FIELD_W + 12, fy, 1, 16, BLACK);
		fill(FIELD_W + 13, fy, 8, 6, BRICK);
		fill(FIELD_W + 13, fy + 1, 6, 4, BRICK_HI);

		Graphics2D g = screen.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 10));
			g.setColor(new Color(BLACK));
			g.drawString(String.valueOf(stage), FIELD_W + 14, fy + 28);
			g.drawString("1P", FIELD_W + 8, 220);
			g.drawString("x3", FIELD_W + 8, 231);
		} finally {
			g.dispose();
		}
	}

	// ---- Cartridge trouble ----

	/** Freeze the last frame and shuffle 8x8 tiles — the cart is coming loose. */
	private void corruptFrame() {
		for (int n = 0; n < 4; n++) {
			int sx = rnd.nextInt(SCREEN_W / 8) * 8;
			int sy = rnd.nextInt(SCREEN_H / 8) * 8;
			int tx = rnd.nextInt(SCREEN_W / 8) * 8;
			int ty = rnd.nextInt(SCREEN_H / 8) * 8;
			for (int y = 0; y < 8; y++) {
				System.arraycopy(px, (sy + y) * SCREEN_W + sx, px, (ty + y) * SCREEN_W + tx, 8);
			}
		}
	}

	private void drawStatic() {
		for (int i = 0; i < px.length; i++) {
			int v = rnd.nextInt(256);
			px[i] = v << 16 | v << 8 | v;
		}
		Graphics2D g = screen.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
			if ((frame / 24) % 2 == 0) {
				String hint = "ПОДУЙ В КАРТРИДЖ";
				g.setColor(new Color(BLACK));
				g.drawString(hint, 71, 121);
				g.setColor(new Color(WHITE));
				g.drawString(hint, 70, 120);
			}
		} finally {
			g.dispose();
		}
	}

	// ---- Pixel helpers ----

	private void pset(int x, int y, int color) {
		if (x < 0 || x >= SCREEN_W || y < 0 || y >= SCREEN_H) return;
		px[y * SCREEN_W + x] = color;
	}

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

	// ---- Helpers ----

	private static int clampI(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clampF(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
