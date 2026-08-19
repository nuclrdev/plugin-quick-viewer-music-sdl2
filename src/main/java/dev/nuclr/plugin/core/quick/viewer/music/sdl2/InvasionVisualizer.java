package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.Arrays;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * "Invasion '95" — a descending-swarm shooter whose formation <b>is</b> the spectrum analyser.
 * <p>
 * Every column of the swarm owns a slice of the FFT. That column's aliens ride up and down on their
 * band, brighten with it, and flash white when it spikes, so a bass hit visibly punches the left of
 * the formation while a hi-hat rattles the right. The swarm <b>steps sideways on the kick drum</b>
 * rather than on a timer — the original marched in discrete steps, so pinning those steps to the
 * beat is both the obvious sync and the authentic one — and the two-frame sprite flip lands on the
 * same step. Whichever column is loudest up top is the one that drops the next bomb, so the music
 * chooses who shoots at you.
 * <p>
 * The presentation is the 16-bit arcade era rather than the monochrome original: a VGA sky with
 * ordered dithering between its two stops, two-tone shaded sprites, destructible bunkers that erode
 * a pixel at a time, a bloom pass over the chunky raster, scanlines, and chrome-gradient arcade
 * lettering. The playfield is drawn into a small {@code int[]} and blown up nearest-neighbour, so
 * the pixels stay hard the way they were on a 15 kHz monitor.
 *
 * @see WaveformPanel.VisualizerMode#INVASION
 */
final class InvasionVisualizer {

	// ---- Analysis ----
	private static final int   FFT_SIZE    = 1024;
	private static final int   SAMPLE_RATE = 44100;
	private static final int   BANDS       = 16;
	private static final int   BASS_BINS   = 5;
	private static final float BEAT_FACTOR = 1.28f;
	private static final int   BEAT_HOLD   = 6;

	// ---- Playfield ----
	private static final int MAX_COLS   = 11;
	private static final int ROWS       = 5;
	private static final int MAX_BOMBS  = 14;
	private static final int MAX_SHOTS  = 3;
	private static final int MAX_PARTS  = 300;
	private static final int BUNKERS    = 4;
	private static final int BUNKER_W   = 22;
	private static final int BUNKER_H   = 14;

	private static final int[] KILL_SCORE  = { 30, 20, 10 };
	private static final int STEP_FALLBACK = 26;   // frames between steps when there is no beat
	private static final int CARD_FRAMES   = 130;

	// ---- Sprite art (authored here, two frames each) ----
	private static final String[] SQUID_A = {
			"...XX...", "..XXXX..", ".XXXXXX.", "XX.XX.XX",
			"XXXXXXXX", "..X..X..", ".X.XX.X.", "X.X..X.X"
	};
	private static final String[] SQUID_B = {
			"...XX...", "..XXXX..", ".XXXXXX.", "XX.XX.XX",
			"XXXXXXXX", ".X.XX.X.", "X.X..X.X", "..X..X.."
	};
	private static final String[] CRAB_A = {
			"..X.....X..", "...X...X...", "..XXXXXXX..", ".XX.XXX.XX.",
			"XXXXXXXXXXX", "X.XXXXXXX.X", "X.X.....X.X", "...XX.XX..."
	};
	private static final String[] CRAB_B = {
			"..X.....X..", "X..X...X..X", "X.XXXXXXX.X", "XXX.XXX.XXX",
			"XXXXXXXXXXX", ".XXXXXXXXX.", "..X.....X..", ".X.......X."
	};
	private static final String[] OCTO_A = {
			"....XXXX....", ".XXXXXXXXXX.", "XXXXXXXXXXXX", "XXX..XX..XXX",
			"XXXXXXXXXXXX", "...XX..XX...", "..XX.XX.XX..", "XX........XX"
	};
	private static final String[] OCTO_B = {
			"....XXXX....", ".XXXXXXXXXX.", "XXXXXXXXXXXX", "XXX..XX..XXX",
			"XXXXXXXXXXXX", "..XXX..XXX..", ".XX..XX..XX.", "..XX....XX.."
	};
	private static final String[] CANNON = {
			"......X......", ".....XXX.....", ".....XXX.....", ".XXXXXXXXXXX.",
			"XXXXXXXXXXXXX", "XXXXXXXXXXXXX", "XXXXXXXXXXXXX", "XX.XXXXXXX.XX"
	};
	private static final String[] SAUCER = {
			"....XXXXXX....", "..XXXXXXXXXX..", ".XXXXXXXXXXXX.",
			"XX.XX.XX.XX.XX", "XXXXXXXXXXXXXX", "..XXX....XXX..", "...X......X..."
	};

	// ---- Colours (16-bit palette: body, highlight) ----
	private static final int[][] ALIEN_RGB = {
			{ 0xFF3E7A, 0xFF9EC4 },   // top row  — hot pink
			{ 0x35D8FF, 0xB4F2FF },   // middle   — cyan
			{ 0x8CFF3E, 0xDBFFA8 }    // bottom   — acid green
	};
	private static final int CANNON_RGB   = 0x46FF8C;
	private static final int CANNON_HI    = 0xC8FFDD;
	private static final int SHOT_RGB     = 0xFFF4C0;
	private static final int BOMB_RGB     = 0xFF7A3E;
	private static final int BUNKER_RGB   = 0x37C05A;
	private static final int SAUCER_RGB   = 0xFF4FE0;
	private static final int SKY_TOP      = 0x0A0620;
	private static final int SKY_BOTTOM   = 0x2B1050;

	// ---- Tables ----
	private final float[] hann    = new float[FFT_SIZE];
	private final int[]   bandBin = new int[BANDS + 1];

	// ---- FFT buffers ----
	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];

	// ---- Levels ----
	private final float[] bandRaw   = new float[BANDS];
	private final float[] bandLevel = new float[BANDS];
	private float autoGain = 1f;
	private float energy;
	private float bassRaw;
	private float bassMean = 1e-3f;
	private float bass;
	private float treble;

	// ---- Beat ----
	private boolean beatNow;
	private int     beatCooldown;

	// ---- Per-column reaction ----
	private final float[] colLevel = new float[MAX_COLS];
	private final float[] colFlash = new float[MAX_COLS];

	// ---- Swarm ----
	private final boolean[] alive = new boolean[MAX_COLS * ROWS];
	private int   cols = MAX_COLS;
	private float formX, formY;
	private int   dir = 1;
	private int   frame;              // sprite flip
	private int   stepTimer;
	private int   wave = 1;
	private int   remaining;

	// ---- Player ----
	private float playerX;
	private int   playerCool;
	private int   lives = 3;
	private int   playerDead;         // frames of respawn pause

	// ---- Shots / bombs ----
	private final float[] shotX = new float[MAX_SHOTS];
	private final float[] shotY = new float[MAX_SHOTS];
	private final boolean[] shotLive = new boolean[MAX_SHOTS];
	private final float[] bombX = new float[MAX_BOMBS];
	private final float[] bombY = new float[MAX_BOMBS];
	private final int[]   bombKind = new int[MAX_BOMBS];
	private final boolean[] bombLive = new boolean[MAX_BOMBS];

	// ---- Saucer ----
	private boolean saucerLive;
	private float   saucerX;
	private int     saucerDir = 1;
	private int     saucerTimer = 500;

	// ---- Bunkers ----
	private final boolean[][] bunker = new boolean[BUNKERS][BUNKER_W * BUNKER_H];

	// ---- Particles ----
	private final float[] parX = new float[MAX_PARTS];
	private final float[] parY = new float[MAX_PARTS];
	private final float[] parVx = new float[MAX_PARTS];
	private final float[] parVy = new float[MAX_PARTS];
	private final float[] parLife = new float[MAX_PARTS];
	private final int[]   parRgb = new int[MAX_PARTS];
	private int parNext;

	// ---- Score ----
	private int   score, hiScore;
	private float scoreShown;
	private int   card;               // title-card countdown
	private String cardText = "";
	private String cardSub  = "";
	private int   gameOver;
	private float shake;

	// ---- Starfield ----
	private static final int STARS = 70;
	private final float[] starX = new float[STARS];
	private final float[] starY = new float[STARS];
	private final float[] starZ = new float[STARS];

	// ---- Raster ----
	private BufferedImage img;
	private int[] pix;
	private int   rw, rh;
	private BufferedImage glow;

	// ---- Geometry, recomputed on resize ----
	private int cellW, cellH, playerY, bunkerY, formTop;
	/** Size of one sprite pixel in raster pixels — the art is 16-bit chunky, never resampled. */
	private int sscale = 1;

	// ---- Fonts ----
	private Font hudFont, cardFont, subFont;
	private int  fontsFor = -1;

	private String trackTitle;
	private final Random rnd = new Random(0x19953L);

	InvasionVisualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		buildBandBins();
		for (int i = 0; i < STARS; i++) {
			starX[i] = rnd.nextFloat();
			starY[i] = rnd.nextFloat();
			starZ[i] = 0.25f + rnd.nextFloat() * 0.75f;
		}
	}

	void setTrackTitle(String title) {
		trackTitle = title == null || title.isBlank() ? null : title.toUpperCase();
	}

	// =========================================================================
	// Frame
	// =========================================================================

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {

		analyze(ring);
		ensureFonts(h);
		boolean fresh = ensureRaster(w, h);
		if (fresh) newGame();

		updateColumns();
		updateSwarm();
		updatePlayer();
		updateShots();
		updateBombs();
		updateSaucer();
		updateParticles();

		drawSky(frameCount);
		drawSpectrumGhost();
		drawBunkers();
		drawSwarm();
		drawSaucer();
		drawPlayer(frameCount);
		drawShots();
		drawParticles();

		blit(g2, w, h);
		drawScanlines(g2, w, h);
		drawHud(g2, w, h);
		drawCard(g2, w, h);

		shake *= 0.85f;
		if (beatCooldown > 0) beatCooldown--;
		if (card > 0) card--;
		if (gameOver > 0 && --gameOver == 0) newGame();
		scoreShown += (score - scoreShown) * 0.16f;
	}

	// =========================================================================
	// Analysis
	// =========================================================================

	private void analyze(AudioRingBuffer ring) {

		beatNow = false;
		int samples = ring != null ? ring.snapshot(snapshot, FFT_SIZE) : 0;

		if (samples < BANDS * 2) {
			for (int b = 0; b < BANDS; b++) bandLevel[b] *= 0.90f;
			energy += (0f - energy) * 0.08f;
			bass   += (0f - bass) * 0.08f;
			treble += (0f - treble) * 0.08f;
			return;
		}

		int pad = FFT_SIZE - samples;
		for (int i = 0; i < FFT_SIZE; i++) {
			float s = i >= pad ? snapshot[i - pad] : 0f;
			re[i] = s * hann[i];
			im[i] = 0f;
		}
		Fft.transform(re, im, FFT_SIZE);

		float maxMag = 1e-4f, sum = 0f, top = 0f;
		for (int b = 0; b < BANDS; b++) {
			float power = 0f;
			for (int k = bandBin[b]; k < bandBin[b + 1]; k++) {
				float p = re[k] * re[k] + im[k] * im[k];
				if (p > power) power = p;
			}
			bandRaw[b] = (float) Math.sqrt(power) / FFT_SIZE * (1f + 1.3f * b / BANDS);
			if (bandRaw[b] > maxMag) maxMag = bandRaw[b];
		}

		float targetGain = clamp(0.85f / maxMag, 0.5f, 60f);
		autoGain += (targetGain - autoGain) * (targetGain < autoGain ? 0.20f : 0.05f);

		for (int b = 0; b < BANDS; b++) {
			float lvl   = clamp((float) Math.pow(bandRaw[b] * autoGain, 0.70), 0f, 1.3f);
			float shown = bandLevel[b];
			bandLevel[b] = lvl > shown ? lvl : shown + (lvl - shown) * 0.22f;
			sum += bandLevel[b];
			if (b >= BANDS * 2 / 3) top += bandLevel[b];
		}
		energy += (sum / BANDS - energy) * 0.20f;
		treble += (top / (BANDS / 3f) - treble) * 0.25f;

		float b = 0f;
		for (int k = 1; k <= BASS_BINS; k++) {
			b += (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) / FFT_SIZE;
		}
		bassRaw += (b - bassRaw) * 0.45f;
		bass = clamp(bassRaw / (bassMean * 2f + 1e-6f), 0f, 1.4f);
		if (bassRaw > bassMean * BEAT_FACTOR && beatCooldown == 0 && bassRaw > 2e-4f) {
			beatNow = true;
			beatCooldown = BEAT_HOLD;
		}
		bassMean += (bassRaw - bassMean) * 0.02f;
	}

	/** Each column owns a slice of the spectrum: this is the whole idea of the effect. */
	private void updateColumns() {
		for (int c = 0; c < cols; c++) {
			int b = Math.min(BANDS - 1, c * BANDS / Math.max(1, cols));
			float target = clamp(bandLevel[b], 0f, 1.2f);
			float prev = colLevel[c];
			colLevel[c] += (target - prev) * (target > prev ? 0.55f : 0.16f);
			// A band jumping hard makes its column flare white for a few frames.
			if (target > prev + 0.22f) colFlash[c] = 1f;
			colFlash[c] *= 0.84f;
		}
	}

	// =========================================================================
	// Swarm
	// =========================================================================

	private void updateSwarm() {
		if (gameOver > 0) return;

		// The swarm marches on the kick. With no beat to be had it falls back to a timer, so a
		// quiet passage still advances rather than freezing mid-screen.
		stepTimer++;
		boolean step = (beatNow && stepTimer >= 5)
				|| stepTimer > STEP_FALLBACK + (int) (remaining * 0.6f);
		if (!step) return;
		stepTimer = 0;
		frame ^= 1;

		float stepX = cellW * (0.22f + 0.5f * (1f - remaining / (float) (cols * ROWS)));
		formX += dir * stepX;

		float leftMost = Float.MAX_VALUE, rightMost = -Float.MAX_VALUE;
		for (int c = 0; c < cols; c++) {
			for (int r = 0; r < ROWS; r++) {
				if (!alive[r * MAX_COLS + c]) continue;
				leftMost  = Math.min(leftMost, formX + c * cellW);
				rightMost = Math.max(rightMost, formX + c * cellW + cellW);
			}
		}
		if (leftMost < 2 || rightMost > rw - 2) {
			dir = -dir;
			formX += dir * stepX;
			formY += cellH * 0.42f;
			shake = Math.max(shake, 1.6f);
		}

		// Reaching the bunkers is the end of the run.
		int lowest = -1;
		for (int r = ROWS - 1; r >= 0 && lowest < 0; r--) {
			for (int c = 0; c < cols; c++) {
				if (alive[r * MAX_COLS + c]) { lowest = r; break; }
			}
		}
		if (lowest >= 0 && formY + (lowest + 1) * cellH > bunkerY) {
			loseLife(true);
		}
	}

	private void drawSwarm() {
		for (int r = 0; r < ROWS; r++) {
			int type = r == 0 ? 0 : (r <= 2 ? 1 : 2);
			String[] art = spriteFor(type, frame);
			int[] rgb = ALIEN_RGB[type];
			for (int c = 0; c < cols; c++) {
				if (!alive[r * MAX_COLS + c]) continue;
				float lift = colLevel[c] * cellH * 0.55f;           // riding its own band
				int x = Math.round(formX + c * cellW);
				int y = Math.round(formY + r * cellH - lift);
				int body = rgb[0], hi = rgb[1];
				if (colFlash[c] > 0.25f) {
					body = mix(body, 0xFFFFFF, colFlash[c]);
					hi   = mix(hi, 0xFFFFFF, colFlash[c]);
				} else {
					float k = 0.62f + 0.38f * clamp(colLevel[c], 0f, 1f);
					body = scale(body, k);
				}
				sprite(art, x, y, body, hi);
			}
		}
	}

	private static String[] spriteFor(int type, int f) {
		return switch (type) {
			case 0 -> f == 0 ? SQUID_A : SQUID_B;
			case 1 -> f == 0 ? CRAB_A : CRAB_B;
			default -> f == 0 ? OCTO_A : OCTO_B;
		};
	}

	/**
	 * A dim shaft of light behind each column, brightening with that column's band. It states the
	 * mapping outright — this column is this slice of the spectrum — without putting a bar chart
	 * on top of the game.
	 */
	private void drawSpectrumGhost() {
		int top = Math.round(formY) - cellH / 2;
		int bottom = Math.min(rh, bunkerY - 2);
		int shaftW = Math.max(2, cellW / 4);
		for (int c = 0; c < cols; c++) {
			float lvl = clamp(colLevel[c], 0f, 1f);
			if (lvl < 0.05f) continue;
			int x = Math.round(formX + c * cellW + (cellW - shaftW) * 0.5f);
			int col = mix(0x2A1A55, 0x7C5AE0, lvl);
			for (int y = Math.max(0, top); y < bottom; y++) {
				// Fades out towards the bottom, so it reads as a beam rather than a solid block.
				float k = 1f - (y - top) / (float) Math.max(1, bottom - top);
				for (int dx = 0; dx < shaftW; dx++) blend(x + dx, y, col, 0.30f * lvl * k);
			}
		}
	}

	// =========================================================================
	// Player, shots, bombs
	// =========================================================================

	private void updatePlayer() {
		if (gameOver > 0) return;
		if (playerDead > 0) {
			playerDead--;
			return;
		}
		if (playerCool > 0) playerCool--;

		// Aim at the lowest alien of the nearest occupied column, and slide out from under bombs.
		float want = playerX;
		int bestCol = -1;
		float bestDist = Float.MAX_VALUE;
		for (int c = 0; c < cols; c++) {
			boolean any = false;
			for (int r = 0; r < ROWS; r++) if (alive[r * MAX_COLS + c]) { any = true; break; }
			if (!any) continue;
			float cx = formX + c * cellW + cellW * 0.5f;
			float d = Math.abs(cx - playerX);
			if (d < bestDist) { bestDist = d; bestCol = c; }
		}
		if (bestCol >= 0) want = formX + bestCol * cellW + cellW * 0.5f;
		if (saucerLive) want = saucerX + 7 * sscale;

		float dodge = 0f;
		for (int i = 0; i < MAX_BOMBS; i++) {
			if (!bombLive[i]) continue;
			float dx = bombX[i] - playerX;
			if (Math.abs(dx) < cellW * 0.9f && bombY[i] > playerY - rh * 0.35f) {
				dodge -= Math.signum(dx == 0 ? 1 : dx) * (cellW - Math.abs(dx));
			}
		}
		if (dodge != 0f) want = playerX + dodge;

		playerX += clamp(want - playerX, -1.7f, 1.7f);
		playerX = clamp(playerX, 7f * sscale, rw - 8f * sscale);

		boolean aligned = bestCol >= 0 && Math.abs(want - playerX) < 3f;
		if ((aligned || saucerLive) && playerCool == 0) {
			for (int i = 0; i < MAX_SHOTS; i++) {
				if (shotLive[i]) continue;
				shotLive[i] = true;
				shotX[i] = playerX;
				shotY[i] = playerY - 4;
				playerCool = 16;
				break;
			}
		}
	}

	private void updateShots() {
		for (int i = 0; i < MAX_SHOTS; i++) {
			if (!shotLive[i]) continue;
			shotY[i] -= 3.1f;
			if (shotY[i] < 0) { shotLive[i] = false; continue; }
			if (hitBunker(shotX[i], shotY[i])) { shotLive[i] = false; continue; }

			if (saucerLive && shotY[i] < rh * 0.07f + 7 * sscale
					&& Math.abs(shotX[i] - (saucerX + 7 * sscale)) < 8 * sscale) {
				saucerLive = false;
				saucerTimer = 420 + rnd.nextInt(400);
				addScore(300);
				burst(saucerX + 7 * sscale, rh * 0.09f, 26, SAUCER_RGB);
				shotLive[i] = false;
				shake = Math.max(shake, 3.4f);
				continue;
			}

			for (int r = ROWS - 1; r >= 0; r--) {
				int type = r == 0 ? 0 : (r <= 2 ? 1 : 2);
				String[] art = spriteFor(type, frame);
				int sw = art[0].length() * sscale, sh = art.length * sscale;
				for (int c = 0; c < cols; c++) {
					if (!alive[r * MAX_COLS + c]) continue;
					float lift = colLevel[c] * cellH * 0.55f;
					float ax = formX + c * cellW, ay = formY + r * cellH - lift;
					if (shotX[i] < ax || shotX[i] > ax + sw || shotY[i] < ay || shotY[i] > ay + sh) continue;
					alive[r * MAX_COLS + c] = false;
					remaining--;
					shotLive[i] = false;
					addScore(KILL_SCORE[type]);
					burst(ax + sw * 0.5f, ay + sh * 0.5f, 14, ALIEN_RGB[type][0]);
					shake = Math.max(shake, 1.8f);
					colFlash[c] = 1f;
					if (remaining <= 0) nextWave();
					r = -1;
					break;
				}
			}
		}
	}

	private void updateBombs() {
		// The loudest column up top is the one that fires: the music picks the shooter.
		if (gameOver == 0 && remaining > 0 && rnd.nextFloat() < 0.02f + treble * 0.16f) {
			int best = -1;
			float bestLevel = 0.05f;
			for (int c = 0; c < cols; c++) {
				if (colLevel[c] <= bestLevel) continue;
				for (int r = ROWS - 1; r >= 0; r--) {
					if (!alive[r * MAX_COLS + c]) continue;
					bestLevel = colLevel[c];
					best = c;
					break;
				}
			}
			if (best >= 0) dropBomb(best);
		}

		for (int i = 0; i < MAX_BOMBS; i++) {
			if (!bombLive[i]) continue;
			bombY[i] += 1.15f + energy * 1.1f;
			if (bombY[i] > rh) { bombLive[i] = false; continue; }
			if (hitBunker(bombX[i], bombY[i])) { bombLive[i] = false; continue; }
				if (playerDead == 0 && bombY[i] > playerY - 3 && Math.abs(bombX[i] - playerX) < 7 * sscale) {
				bombLive[i] = false;
				loseLife(false);
			}
		}
	}

	private void dropBomb(int c) {
		int row = -1;
		for (int r = ROWS - 1; r >= 0; r--) if (alive[r * MAX_COLS + c]) { row = r; break; }
		if (row < 0) return;
		for (int i = 0; i < MAX_BOMBS; i++) {
			if (bombLive[i]) continue;
			bombLive[i] = true;
			bombX[i] = formX + c * cellW + cellW * 0.4f;
			bombY[i] = formY + row * cellH + 8;
			bombKind[i] = rnd.nextInt(2);
			return;
		}
	}

	private void updateSaucer() {
		if (!saucerLive) {
			if (--saucerTimer <= 0) {
				saucerLive = true;
				saucerDir  = rnd.nextBoolean() ? 1 : -1;
				saucerX    = saucerDir > 0 ? -16 : rw + 4;
			}
			return;
		}
		saucerX += saucerDir * 0.75f;
		if (saucerX < -20 || saucerX > rw + 20) {
			saucerLive = false;
			saucerTimer = 420 + rnd.nextInt(400);
		}
	}

	private void loseLife(boolean overrun) {
		burst(playerX, playerY, 34, CANNON_RGB);
		shake = 6f;
		playerDead = 70;
		if (overrun || --lives <= 0) {
			lives = Math.max(0, lives);
			gameOver = 190;
			card = CARD_FRAMES;
			cardText = "GAME OVER";
			cardSub  = "INSERT COIN";
			hiScore = Math.max(hiScore, score);
		}
	}

	private void nextWave() {
		wave++;
		card = CARD_FRAMES;
		cardText = "WAVE " + wave;
		cardSub  = trackTitle == null ? "GET READY" : trackTitle;
		spawnWave();
	}

	// =========================================================================
	// Bunkers
	// =========================================================================

	private boolean hitBunker(float x, float y) {
		int gap = rw / (BUNKERS + 1);
		int bw = BUNKER_W * sscale, bh = BUNKER_H * sscale;
		for (int b = 0; b < BUNKERS; b++) {
			int bx = gap * (b + 1) - bw / 2;
			if (x < bx || x >= bx + bw || y < bunkerY || y >= bunkerY + bh) continue;
			int lx = (int) (x - bx) / sscale, ly = (int) (y - bunkerY) / sscale;
			if (!bunker[b][ly * BUNKER_W + lx]) continue;
			// Carve a small crater rather than removing one pixel: erosion has to be visible.
			for (int dy = -2; dy <= 2; dy++) {
				for (int dx = -2; dx <= 2; dx++) {
					if (dx * dx + dy * dy > 5) continue;
					int nx = lx + dx, ny = ly + dy;
					if (nx < 0 || ny < 0 || nx >= BUNKER_W || ny >= BUNKER_H) continue;
					bunker[b][ny * BUNKER_W + nx] = false;
				}
			}
			burst(x, y, 5, BUNKER_RGB);
			return true;
		}
		return false;
	}

	private void buildBunkers() {
		for (int b = 0; b < BUNKERS; b++) {
			for (int y = 0; y < BUNKER_H; y++) {
				for (int x = 0; x < BUNKER_W; x++) {
					boolean solid = true;
					if (y < 4 && (x < 4 - y || x >= BUNKER_W - (4 - y))) solid = false;   // sloped top
					int archW = BUNKER_W / 3;
					if (y > BUNKER_H - 6 && x > (BUNKER_W - archW) / 2 && x < (BUNKER_W + archW) / 2) {
						solid = false;                                                    // doorway
					}
					bunker[b][y * BUNKER_W + x] = solid;
				}
			}
		}
	}

	private void drawBunkers() {
		int gap = rw / (BUNKERS + 1);
		int bw = BUNKER_W * sscale;
		for (int b = 0; b < BUNKERS; b++) {
			int bx = gap * (b + 1) - bw / 2;
			for (int y = 0; y < BUNKER_H; y++) {
				for (int x = 0; x < BUNKER_W; x++) {
					if (!bunker[b][y * BUNKER_W + x]) continue;
					int rgb = y < 2 ? mix(BUNKER_RGB, 0xFFFFFF, 0.35f) : BUNKER_RGB;
					for (int dy = 0; dy < sscale; dy++) {
						for (int dx = 0; dx < sscale; dx++) {
							px(bx + x * sscale + dx, bunkerY + y * sscale + dy, rgb);
						}
					}
				}
			}
		}
	}

	// =========================================================================
	// Particles
	// =========================================================================

	private void burst(float x, float y, int n, int rgb) {
		for (int i = 0; i < n; i++) {
			float a = rnd.nextFloat() * (float) Math.PI * 2f;
			float s = 0.3f + rnd.nextFloat() * 1.5f;
			parX[parNext] = x;
			parY[parNext] = y;
			parVx[parNext] = (float) Math.cos(a) * s;
			parVy[parNext] = (float) Math.sin(a) * s;
			parLife[parNext] = 14f + rnd.nextFloat() * 16f;
			parRgb[parNext] = rgb;
			parNext = (parNext + 1) % MAX_PARTS;
		}
	}

	private void updateParticles() {
		for (int i = 0; i < MAX_PARTS; i++) {
			if (parLife[i] <= 0f) continue;
			parX[i] += parVx[i];
			parY[i] += parVy[i];
			parVy[i] += 0.045f;
			parLife[i] -= 1f;
		}
	}

	private void drawParticles() {
		for (int i = 0; i < MAX_PARTS; i++) {
			if (parLife[i] <= 0f) continue;
			float k = clamp(parLife[i] / 26f, 0f, 1f);
			px(Math.round(parX[i]), Math.round(parY[i]), scale(parRgb[i], 0.35f + 0.65f * k));
		}
	}

	// =========================================================================
	// Drawing
	// =========================================================================

	/** VGA sky: two stops with ordered dithering between them, plus a parallax starfield. */
	private void drawSky(int frameCount) {
		// The sky lifts on the kick, so even the backdrop is on the beat.
		float punch = beatCooldown > 2 ? 0.22f : 0f;
		int top = mix(SKY_TOP, 0x5A2A8C, punch);
		int bot = mix(SKY_BOTTOM, 0x8A3ACC, punch);
		for (int y = 0; y < rh; y++) {
			float t = y / (float) rh;
			int base = mix(top, bot, t);
			int next = mix(top, bot, Math.min(1f, t + 0.07f));
			int row = y * rw;
			// A 2x2 Bayer pattern between neighbouring stops is what 256 colours actually looked
			// like, and it keeps the gradient from banding into stripes.
			for (int x = 0; x < rw; x++) {
				boolean dither = ((x & 1) ^ (y & 1)) == 0;
				pix[row + x] = dither ? base : next;
			}
		}
		// A glow sitting on the horizon behind the bunkers: pure 1990s attract-mode backdrop.
		int glowTop = Math.max(0, bunkerY - rh / 6);
		for (int y = glowTop; y < rh; y++) {
			float k = (y - glowTop) / (float) Math.max(1, rh - glowTop);
			int col = mix(0x3A1A6E, 0x8C3ACC, k * (0.45f + energy * 0.55f));
			int row = y * rw;
			for (int x = 0; x < rw; x++) pix[row + x] = mix(pix[row + x], col, 0.30f * k);
		}

		float drift = frameCount * 0.05f;
		for (int i = 0; i < STARS; i++) {
			int x = (int) (((starX[i] * rw + drift * starZ[i]) % rw + rw) % rw);
			int y = (int) (starY[i] * rh * 0.75f);
			px(x, y, scale(0xC8D8FF, 0.35f + 0.65f * starZ[i]));
		}
	}

	private void drawPlayer(int frameCount) {
		if (playerDead > 0) {
			if ((frameCount / 4) % 2 == 0) return;   // blink back in
		}
		if (gameOver > 0) return;
		sprite(CANNON, Math.round(playerX) - 6 * sscale, playerY, CANNON_RGB, CANNON_HI);
	}

	private void drawSaucer() {
		if (!saucerLive) return;
		sprite(SAUCER, Math.round(saucerX), Math.round(rh * 0.07f), SAUCER_RGB,
				mix(SAUCER_RGB, 0xFFFFFF, 0.45f));
	}

	private void drawShots() {
		for (int i = 0; i < MAX_SHOTS; i++) {
			if (!shotLive[i]) continue;
			int x = Math.round(shotX[i]), y = Math.round(shotY[i]);
			px(x, y, SHOT_RGB);
			px(x, y + 1, SHOT_RGB);
			px(x, y + 2, scale(SHOT_RGB, 0.55f));
		}
		for (int i = 0; i < MAX_BOMBS; i++) {
			if (!bombLive[i]) continue;
			int x = Math.round(bombX[i]), y = Math.round(bombY[i]);
			// Two bomb shapes, alternating with the frame flip, the way the originals wiggled.
			int wig = bombKind[i] == 0 ? ((y / 2) % 2 == 0 ? 1 : -1) : 0;
			px(x + wig, y, BOMB_RGB);
			px(x, y - 1, BOMB_RGB);
			px(x - wig, y - 2, scale(BOMB_RGB, 0.7f));
		}
	}

	/** Blit sprite art, one art pixel to an {@code sscale} square block. */
	private void sprite(String[] art, int x, int y, int body, int highlight) {
		for (int r = 0; r < art.length; r++) {
			String row = art[r];
			int py = y + r * sscale;
			for (int c = 0; c < row.length(); c++) {
				if (row.charAt(c) != 'X') continue;
				int rgb = r < 2 ? highlight : body;
				int pxx = x + c * sscale;
				for (int dy = 0; dy < sscale; dy++) {
					for (int dx = 0; dx < sscale; dx++) px(pxx + dx, py + dy, rgb);
				}
			}
		}
	}

	private void blit(Graphics2D g2, int w, int h) {
		int sx = 0, sy = 0;
		if (shake > 0.4f) {
			sx = Math.round((rnd.nextFloat() - 0.5f) * shake);
			sy = Math.round((rnd.nextFloat() - 0.5f) * shake);
		}
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.drawImage(img, sx, sy, w + sx, h + sy, 0, 0, rw, rh, null);

		// Bloom: the raster shrunk and drawn back over itself, which is what a CRT did for free.
		Graphics2D gg = glow.createGraphics();
		try {
			gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			gg.drawImage(img, 0, 0, glow.getWidth(), glow.getHeight(), null);
		} finally {
			gg.dispose();
		}
		Graphics2D bg = (Graphics2D) g2.create();
		try {
			bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			bg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.42f));
			bg.drawImage(glow, sx - w / 40, sy - h / 40, w + w / 20, h + h / 20, null);
		} finally {
			bg.dispose();
		}
	}

	private void drawScanlines(Graphics2D g2, int w, int h) {
		g2.setColor(new Color(0, 0, 0, 46));
		for (int y = 0; y < h; y += 3) g2.fillRect(0, y, w, 1);

		// Corner falloff: a tube never lit its edges as brightly as its middle.
		int steps = 7;
		for (int i = 0; i < steps; i++) {
			int inset = i * Math.max(1, Math.min(w, h) / 46);
			g2.setColor(new Color(0, 0, 0, 16));
			g2.drawRect(inset, inset, w - 1 - inset * 2, h - 1 - inset * 2);
		}
	}

	// =========================================================================
	// HUD
	// =========================================================================

	private void drawHud(Graphics2D g2, int w, int h) {
		Graphics2D g = (Graphics2D) g2.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setFont(hudFont);
			FontMetrics fm = g.getFontMetrics();
			int base = 6 + fm.getAscent();

			chrome(g, "SCORE " + zeroPad(Math.round(scoreShown), 5), 10, base, 0x53E6FF);
			String hi = "HI " + zeroPad(Math.max(hiScore, score), 5);
			chrome(g, hi, w - fm.stringWidth(hi) - 10, base, 0xFF6AC0);

			String wv = "WAVE " + wave;
			chrome(g, wv, (w - fm.stringWidth(wv)) / 2, base, 0x8CFF3E);

			for (int i = 0; i < lives; i++) {
				int lx = 10 + i * 16;
				int ly = h - 14;
				g.setColor(new Color(CANNON_RGB));
				g.fillRect(lx, ly + 4, 12, 4);
				g.fillRect(lx + 5, ly, 2, 4);
			}
		} finally {
			g.dispose();
		}
	}

	/** Arcade lettering: hard drop shadow, bright top half, saturated bottom half. */
	private void chrome(Graphics2D g, String text, int x, int y, int rgb) {
		g.setColor(new Color(0, 0, 0, 190));
		g.drawString(text, x + 2, y + 2);
		g.setColor(new Color(scale(rgb, 0.55f)));
		g.drawString(text, x, y + 1);
		g.setColor(new Color(mix(rgb, 0xFFFFFF, 0.55f)));
		g.drawString(text, x, y);
	}

	private void drawCard(Graphics2D g2, int w, int h) {
		if (card <= 0) return;
		float a = clamp(card / 30f, 0f, 1f);
		Graphics2D g = (Graphics2D) g2.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
			g.setFont(cardFont);
			FontMetrics fm = g.getFontMetrics();
			if (fm.stringWidth(cardText) > w - 16) return;
			chrome(g, cardText, (w - fm.stringWidth(cardText)) / 2, h / 2, 0xFFD84A);
			g.setFont(subFont);
			FontMetrics sm = g.getFontMetrics();
			if (sm.stringWidth(cardSub) < w - 16) {
				chrome(g, cardSub, (w - sm.stringWidth(cardSub)) / 2,
						h / 2 + fm.getHeight() * 2 / 3 + sm.getAscent(), 0x9FD8FF);
			}
		} finally {
			g.dispose();
		}
	}

	// =========================================================================
	// Setup
	// =========================================================================

	private void newGame() {
		score = 0;
		scoreShown = 0f;
		wave = 1;
		lives = 3;
		gameOver = 0;
		playerX = rw * 0.5f;
		playerDead = 0;
		Arrays.fill(shotLive, false);
		Arrays.fill(bombLive, false);
		buildBunkers();
		spawnWave();
		card = CARD_FRAMES;
		cardText = "WAVE 1";
		cardSub  = trackTitle == null ? "GET READY" : trackTitle;
	}

	private void spawnWave() {
		Arrays.fill(alive, false);
		for (int r = 0; r < ROWS; r++) {
			for (int c = 0; c < cols; c++) alive[r * MAX_COLS + c] = true;
		}
		remaining = cols * ROWS;
		formX = cellW * 0.5f;
		formY = formTop + Math.min(wave - 1, 4) * cellH * 0.30f;
		dir = 1;
		stepTimer = 0;
		Arrays.fill(bombLive, false);
	}

	/**
	 * Lay the playfield out for the panel. Columns are dropped rather than sprites squashed when
	 * the panel is narrow — a 12-pixel alien has to stay 12 pixels or it stops being pixel art.
	 */
	private boolean ensureRaster(int w, int h) {
		int scale = Math.max(1, Math.min(w / 300, h / 170));
		int nw = Math.max(120, w / scale);
		int nh = Math.max(80, h / scale);
		if (img != null && nw == rw && nh == rh) return false;

		img = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
		pix = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
		glow = new BufferedImage(Math.max(1, nw / 3), Math.max(1, nh / 3), BufferedImage.TYPE_INT_RGB);
		rw = nw;
		rh = nh;

		// Everything is laid out from the sprite pixel size, so the art stays chunky and the
		// formation stays tight instead of drifting apart on a big panel.
		sscale  = clamp(Math.round(rh / 115f), 1, 3);
		playerY = rh - 10 * sscale;
		bunkerY = playerY - BUNKER_H * sscale - 5 * sscale;

		// The five rows have to fit between the HUD and the bunkers. On a short panel they do not
		// at the nominal spacing, and the swarm would start already overrun — an instant, endless
		// game over. Tighten the row pitch to whatever room there actually is.
		formTop = Math.round(rh * 0.13f);
		int room = bunkerY - formTop - 4 * sscale;
		cellH = clamp(room / ROWS, 5, 8 * sscale + 5);
		cellW = 12 * sscale + 5;
		cols  = clamp((rw - 16) / cellW, 5, MAX_COLS);
		return true;
	}

	private void ensureFonts(int h) {
		if (fontsFor == h) return;
		fontsFor = h;
		hudFont  = new Font(Font.MONOSPACED, Font.BOLD, Math.max(10, Math.round(h * 0.042f)));
		cardFont = new Font(Font.MONOSPACED, Font.BOLD, Math.max(16, Math.round(h * 0.105f)));
		subFont  = new Font(Font.MONOSPACED, Font.BOLD, Math.max(9, Math.round(h * 0.034f)));
	}

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

	private void addScore(int points) {
		score += points;
		if (score > hiScore) hiScore = score;
	}

	// =========================================================================
	// Raster helpers
	// =========================================================================

	private void px(int x, int y, int rgb) {
		if (x < 0 || y < 0 || x >= rw || y >= rh) return;
		pix[y * rw + x] = rgb;
	}

	private void blend(int x, int y, int rgb, float a) {
		if (x < 0 || y < 0 || x >= rw || y >= rh) return;
		pix[y * rw + x] = mix(pix[y * rw + x], rgb, a);
	}

	private static int mix(int a, int b, float t) {
		t = clamp(t, 0f, 1f);
		int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return (Math.round(ar + (br - ar) * t) << 16)
				| (Math.round(ag + (bg - ag) * t) << 8)
				| Math.round(ab + (bb - ab) * t);
	}

	private static int scale(int rgb, float k) {
		int r = clamp255(Math.round(((rgb >> 16) & 0xFF) * k));
		int g = clamp255(Math.round(((rgb >> 8) & 0xFF) * k));
		int b = clamp255(Math.round((rgb & 0xFF) * k));
		return (r << 16) | (g << 8) | b;
	}

	private static String zeroPad(int value, int digits) {
		String s = Integer.toString(Math.max(0, value));
		if (s.length() >= digits) return s;
		StringBuilder sb = new StringBuilder(digits);
		for (int i = s.length(); i < digits; i++) sb.append('0');
		return sb.append(s).toString();
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int clamp255(int v) {
		return Math.max(0, Math.min(255, v));
	}
}
