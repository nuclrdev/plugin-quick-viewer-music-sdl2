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
 * "id Inferno" — an early-id-Software (Doom / Quake) tribute visualizer.
 * <p>
 * The scene is the classic indexed-palette fire effect rendered into a low-res
 * framebuffer and scaled up with nearest-neighbour for chunky 320x200-era
 * pixels. The fire is alive: bass energy stokes the bottom-row heat, kick
 * drums flash the whole hearth, and silence lets it die down to embers.
 * <p>
 * Along the bottom sits a Doom-style status bar: AMMO (each detected beat
 * fires a shell; an empty clip picks up a fresh box), HEALTH (overall signal
 * energy) and ARMOR (treble content) drawn with a hand-made red pixel digit
 * font — plus a procedural pixel-art marine face that wanders its gaze,
 * blinks, grimaces on beats and goes progressively blood-soaked berserk
 * during sustained loud passages. Treble transients call down a Quake
 * lightning-gun bolt that also scorches the fire where it lands, pickup-style
 * messages appear in the top-left corner, and every new track slides in with
 * the famous screen-melt transition.
 * <p>
 * Self-contained like its siblings: owns its FFT scratch buffers, fire
 * framebuffer and particle state; everything heavy is pre-allocated and the
 * SDL audio thread is never touched (the ring buffer snapshot is read on the
 * Swing EDT).
 */
final class InfernoVisualizer {

	// ---- Fire framebuffer ----
	private static final int FIRE_W = 168;
	private static final int FIRE_H = 96;

	/** The classic 37-step black → red → orange → yellow → white fire ramp. */
	private static final int[] FIRE_PALETTE = {
		0x070707, 0x1F0707, 0x2F0F07, 0x470F07, 0x571707, 0x671F07, 0x771F07, 0x8F2707,
		0x9F2F07, 0xAF3F07, 0xBF4707, 0xC74707, 0xDF4F07, 0xDF5707, 0xDF5707, 0xD75F07,
		0xD75F07, 0xD7670F, 0xCF6F0F, 0xCF770F, 0xCF7F0F, 0xCF8717, 0xC78717, 0xC78F17,
		0xC7971F, 0xBF9F1F, 0xBF9F1F, 0xBFA727, 0xBFA727, 0xBFAF2F, 0xB7AF2F, 0xB7B72F,
		0xB7B737, 0xCFCF6F, 0xDFDF9F, 0xEFEFC7, 0xFFFFFF
	};
	private static final int MAX_HEAT = FIRE_PALETTE.length - 1;

	private final byte[]        heat      = new byte[FIRE_W * FIRE_H];
	private final int[]         fireRgb   = new int[FIRE_W * FIRE_H];
	private final BufferedImage fireImage = new BufferedImage(FIRE_W, FIRE_H, BufferedImage.TYPE_INT_RGB);

	// ---- FFT / analysis ----
	private static final int FFT_SIZE    = 2048;
	private static final int SAMPLE_RATE = 44100;

	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];

	private float bass, mids, treble, energy; // smoothed 0..1 band levels
	private float bassAvg, trebleAvg;         // slow-moving averages for onset detection
	private float autoGain = 8f;

	// ---- Gameplay state ----
	private static final int CLIP_SIZE = 50;

	private int   ammo            = CLIP_SIZE;
	private int   framesSinceBeat = 999;
	private int   beatFlash       = 0;    // frames of full-heat hearth after a kick
	private float grin            = 0f;   // decaying grimace timer 0..1
	private float shock           = 0f;   // open-mouth "ouch" timer 0..1
	private float berserk         = 0f;   // sustained-energy blood meter 0..1
	private boolean ripAndTearSent = false;

	private int gazeDir    = 0;  // -1, 0, 1
	private int gazeTimer  = 0;
	private int blinkTimer = 0;

	// ---- Messages (Doom pickup line, top-left) ----
	private static final int MESSAGE_TTL = 200;
	private static final String[] PICKUPS = {
		"YOU GOT THE CHAINGUN!",
		"PICKED UP A MEGASPHERE!",
		"YOU GOT THE ROCKET LAUNCHER!",
		"FOUND A SECRET AREA...",
		"IDKFA",
		"QUAD DAMAGE!",
		"YOU GOT THE PLASMA GUN!",
	};
	private String message      = null;
	private int    messageTtl   = 0;
	private int    pickupCooldown = 0;

	// ---- Screen melt ----
	private static final int MELT_COLS = 80;
	private final int[] meltDelay = new int[MELT_COLS];
	private int meltTimer = -1; // -1 = inactive

	// ---- Quake lightning bolt ----
	private static final int BOLT_MAX_POINTS = 24;
	private final float[] boltX = new float[BOLT_MAX_POINTS];
	private final float[] boltY = new float[BOLT_MAX_POINTS];
	private int boltPoints = 0;
	private int boltLife   = 0;

	private final Random rnd = new Random(0x1D50F7);

	// ---- Pixel-art marine face (21 x 24) ----
	// Legend: . transparent, H hair, s skin, S skin shadow, b brow, e eye white,
	//         n nose, M mouth dark, t teeth, c collar, C collar shadow
	private static final int FACE_W = 21;
	private static final String[] FACE_BASE = {
		"......HHHHHHHHH......",
		"....HHHHHHHHHHHHH....",
		"...HHHHHHHHHHHHHHH...",
		"..HHHHHHHHHHHHHHHHH..",
		"..HHHsssssssssssHHH..",
		".HHsssssssssssssssHH.",
		".HsssssssssssssssssH.",
		".HssbbbbsssssbbbbssH.",
		".HsseeeessssseeeessH.",
		".HSseeeessssseeeesSH.",
		".HSsssssssnsssssssSH.",
		".HSssssssnnnssssssSH.",
		"..SssssssnSnssssssS..",
		"..SsssssssssssssssS..",
		"..SsssssssssssssssS..",
		"...SsssMMMMMMMsssS...",
		"...SsssssssssssssS...",
		"....SsssssssssssS....",
		"....SSsssssssssSS....",
		".....SSSsssssSSS.....",
		"....ccCCsssssCCcc....",
		"..ccccCCsssssCCcccc..",
		".ccccccCsssssCcccccc.",
		"cccccccCCsssCCccccccc",
	};
	/** Grin / shout overlays, 11 x 5, drawn over face rows 14..18, cols 5..15. */
	private static final String[] MOUTH_GRIN = {
		".MMMMMMMMM.",
		"MtttttttttM",
		"MtttttttttM",
		".MMMMMMMMM.",
		"...........",
	};
	private static final String[] MOUTH_SHOUT = {
		"...MMMMM...",
		"..MMMMMMM..",
		"..MMMMMMM..",
		"...MMMMM...",
		"...........",
	};

	// ---- HUD colors ----
	private static final Color HUD_BG        = new Color(0x3A, 0x34, 0x2C);
	private static final Color HUD_BEVEL_HI  = new Color(0x5F, 0x57, 0x49);
	private static final Color HUD_BEVEL_LO  = new Color(0x16, 0x12, 0x0C);
	private static final Color HUD_SLOT      = new Color(0x24, 0x1F, 0x18);
	private static final Color LABEL_GOLD    = new Color(0xC8, 0xA2, 0x4B);
	private static final Color DIGIT_BRIGHT  = new Color(0xFF, 0x46, 0x38);
	private static final Color DIGIT_DARK    = new Color(0x9E, 0x1C, 0x10);
	private static final Color DIGIT_OUTLINE = new Color(0x1C, 0x06, 0x04);
	private static final Color MESSAGE_RED   = new Color(0xFF, 0x3B, 0x2F);

	// ---- Pixel digit font, 5 x 7 ('%' included for HEALTH/ARMOR) ----
	private static final String[][] DIGITS = {
		{"01110","10001","10011","10101","11001","10001","01110"}, // 0
		{"00100","01100","00100","00100","00100","00100","01110"}, // 1
		{"01110","10001","00001","00010","00100","01000","11111"}, // 2
		{"11111","00010","00100","00010","00001","10001","01110"}, // 3
		{"00010","00110","01010","10010","11111","00010","00010"}, // 4
		{"11111","10000","11110","00001","00001","10001","01110"}, // 5
		{"00110","01000","10000","11110","10001","10001","01110"}, // 6
		{"11111","00001","00010","00100","01000","01000","01000"}, // 7
		{"01110","10001","10001","01110","10001","10001","01110"}, // 8
		{"01110","10001","10001","01111","00001","00010","01100"}, // 9
		{"11001","11010","00010","00100","01000","01011","10011"}, // %
	};
	private static final int PERCENT = 10;

	InfernoVisualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		// Light the pilot flame so the panel never opens cold.
		int bottom = (FIRE_H - 1) * FIRE_W;
		for (int x = 0; x < FIRE_W; x++) {
			heat[bottom + x] = (byte) (MAX_HEAT / 3);
		}
	}

	/** New tune: announce it Quake-intermission style and run the screen melt. */
	void setTrackTitle(String title) {
		if (title != null && !title.isBlank()) {
			postMessage("NOW ENTERING: " + title.toUpperCase());
		}
		startMelt();
	}

	// ---- Render entry ----

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {
		int samples = ring != null ? ring.snapshot(snapshot, FFT_SIZE) : 0;
		boolean hasAudio = samples >= 512;

		if (hasAudio) {
			analyze(samples);
		} else {
			bass   += (0f - bass)   * 0.06f;
			mids   += (0f - mids)   * 0.06f;
			treble += (0f - treble) * 0.06f;
			energy += (0f - energy) * 0.06f;
		}
		advanceGameState(hasAudio);

		int hudH = clamp((int) (h * 0.20f), 42, 100);
		int hudY = h - hudH;

		// Chunky pixels: no antialiasing, nearest-neighbour scaling.
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,  RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

		stepFire(hasAudio);
		blitFire(g2, w, hudY);
		drawBolt(g2, w, hudY);
		drawHud(g2, w, h, hudY, hudH, frameCount);
		drawMessage(g2, w, h);
		drawMelt(g2, w, h);
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

		float rawBass   = bandLevel(35, 160);
		float rawMids   = bandLevel(160, 2000);
		float rawTreble = bandLevel(2000, 12000);

		// Auto-gain against the loudest band so quiet sources still burn.
		float rawMax = Math.max(rawBass, Math.max(rawMids, rawTreble));
		if (rawMax > 1e-6f) {
			float targetGain = clamp(0.8f / rawMax, 1f, 400f);
			float rate = targetGain < autoGain ? 0.15f : 0.02f;
			autoGain += (targetGain - autoGain) * rate;
		}

		float nb = clamp(rawBass   * autoGain, 0f, 1f);
		float nm = clamp(rawMids   * autoGain, 0f, 1f);
		float nt = clamp(rawTreble * autoGain, 0f, 1f);

		bass   += (nb - bass)   * (nb > bass   ? 0.5f : 0.12f);
		mids   += (nm - mids)   * (nm > mids   ? 0.5f : 0.12f);
		treble += (nt - treble) * (nt > treble ? 0.5f : 0.12f);
		float e = 0.5f * bass + 0.3f * mids + 0.2f * treble;
		energy += (e - energy) * 0.15f;

		// Beat: bass onset well above its own slow average.
		bassAvg += (bass - bassAvg) * 0.02f;
		if (framesSinceBeat > 14 && bass > 0.14f && bass > bassAvg * 1.45f + 0.03f) {
			onBeat();
		}

		// Treble transient: summon the lightning gun.
		trebleAvg += (treble - trebleAvg) * 0.02f;
		if (boltLife <= 0 && treble > 0.22f && treble > trebleAvg * 1.8f + 0.05f && rnd.nextFloat() < 0.35f) {
			spawnBolt();
		}
	}

	/** RMS-ish level of an FFT band in Hz. */
	private float bandLevel(float loHz, float hiHz) {
		int lo = Math.max(1, Math.round(loHz * FFT_SIZE / SAMPLE_RATE));
		int hi = Math.min(FFT_SIZE / 2, Math.round(hiHz * FFT_SIZE / SAMPLE_RATE));
		if (hi <= lo) hi = lo + 1;
		float sum = 0f;
		for (int k = lo; k < hi; k++) {
			sum += re[k] * re[k] + im[k] * im[k];
		}
		return (float) Math.sqrt(sum / (hi - lo)) / FFT_SIZE * 24f;
	}

	// ---- Game state ----

	private void onBeat() {
		framesSinceBeat = 0;
		beatFlash = 5;
		grin = Math.min(1f, grin + 0.8f);

		// Every beat fires a shell; an empty clip means it's time to reload.
		if (--ammo <= 0) {
			ammo = CLIP_SIZE;
			postMessage("PICKED UP A BOX OF SHELLS.");
		} else if (pickupCooldown <= 0 && rnd.nextFloat() < 0.05f) {
			postMessage(PICKUPS[rnd.nextInt(PICKUPS.length)]);
			pickupCooldown = 400;
		}
	}

	private void advanceGameState(boolean hasAudio) {
		framesSinceBeat++;
		if (beatFlash > 0) beatFlash--;
		if (pickupCooldown > 0) pickupCooldown--;
		if (messageTtl > 0) messageTtl--;

		grin  *= 0.94f;
		shock *= 0.90f;

		// Berserk builds during sustained loud passages, bleeds off in quiet ones.
		if (hasAudio && energy > 0.55f) {
			berserk = Math.min(1f, berserk + 0.005f);
		} else {
			berserk = Math.max(0f, berserk - 0.004f);
		}
		if (berserk > 0.85f && !ripAndTearSent) {
			postMessage("RIP AND TEAR!");
			ripAndTearSent = true;
		} else if (berserk < 0.5f) {
			ripAndTearSent = false;
		}

		// Wandering gaze and the occasional blink, just like the status bar face.
		if (--gazeTimer <= 0) {
			gazeDir = rnd.nextInt(3) - 1;
			gazeTimer = 40 + rnd.nextInt(120);
		}
		if (blinkTimer > 0) {
			blinkTimer--;
		} else if (rnd.nextFloat() < 0.006f) {
			blinkTimer = 5;
		}
	}

	private void postMessage(String text) {
		message = text;
		messageTtl = MESSAGE_TTL;
	}

	// ---- Fire ----

	private void stepFire(boolean hasAudio) {
		// Seed the hearth: bass drives the heat, beats flash it to full burn,
		// silence leaves guttering embers.
		float drive = hasAudio ? clamp(0.16f + bass * 1.05f, 0f, 1f) : 0.10f;
		if (beatFlash > 0) drive = 1f;
		int bottom = (FIRE_H - 1) * FIRE_W;
		for (int x = 0; x < FIRE_W; x++) {
			int target = Math.round(MAX_HEAT * drive) + rnd.nextInt(7) - 3;
			heat[bottom + x] = (byte) clamp(target, 0, MAX_HEAT);
		}

		// Classic propagation: each cell cools and drifts as it rises.
		for (int y = 1; y < FIRE_H; y++) {
			int row = y * FIRE_W;
			for (int x = 0; x < FIRE_W; x++) {
				int src = heat[row + x] & 0xFF;
				if (src == 0) {
					heat[row - FIRE_W + x] = 0;
				} else {
					int r   = rnd.nextInt(4);
					int dst = x - r + 1;
					if (dst < 0) dst = 0;
					else if (dst >= FIRE_W) dst = FIRE_W - 1;
					heat[row - FIRE_W + dst] = (byte) Math.max(0, src - (r & 1));
				}
			}
		}
	}

	private void blitFire(Graphics2D g2, int w, int fireAreaH) {
		for (int i = 0; i < heat.length; i++) {
			fireRgb[i] = FIRE_PALETTE[heat[i] & 0xFF];
		}
		fireImage.setRGB(0, 0, FIRE_W, FIRE_H, fireRgb, 0, FIRE_W);
		g2.drawImage(fireImage, 0, 0, w, Math.max(1, fireAreaH), null);
	}

	// ---- Lightning ----

	private void spawnBolt() {
		float x = 0.1f + rnd.nextFloat() * 0.8f; // fractions, scaled at draw time
		boltPoints = 0;
		float y = 0f;
		while (y < 0.75f && boltPoints < BOLT_MAX_POINTS) {
			boltX[boltPoints] = x;
			boltY[boltPoints] = y;
			boltPoints++;
			y += 0.06f + rnd.nextFloat() * 0.05f;
			x += (rnd.nextFloat() - 0.5f) * 0.07f;
		}
		boltLife = 8;
		shock = 1f;

		// Scorch the hearth where the bolt lands.
		int col = clamp(Math.round(x * FIRE_W), 2, FIRE_W - 3);
		int bottom = (FIRE_H - 1) * FIRE_W;
		for (int dx = -2; dx <= 2; dx++) {
			heat[bottom + col + dx] = (byte) MAX_HEAT;
		}
	}

	private void drawBolt(Graphics2D g2, int w, int fireAreaH) {
		if (boltLife <= 0 || boltPoints < 2) return;
		boltLife--;

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		float a = boltLife / 8f;
		int[] xs = new int[boltPoints];
		int[] ys = new int[boltPoints];
		for (int i = 0; i < boltPoints; i++) {
			xs[i] = Math.round(boltX[i] * w);
			ys[i] = Math.round(boltY[i] * fireAreaH);
		}
		g2.setColor(new Color(140, 190, 255, clamp((int) (150 * a), 0, 255)));
		g2.setStroke(new BasicStroke(4.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g2.drawPolyline(xs, ys, boltPoints);
		g2.setColor(new Color(255, 255, 255, clamp((int) (230 * a), 0, 255)));
		g2.setStroke(new BasicStroke(1.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g2.drawPolyline(xs, ys, boltPoints);
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
	}

	// ---- HUD ----

	private void drawHud(Graphics2D g2, int w, int h, int hudY, int hudH, int frameCount) {
		// Gunmetal slab with a bevelled edge and corner rivets.
		g2.setColor(HUD_BG);
		g2.fillRect(0, hudY, w, hudH);
		g2.setColor(HUD_BEVEL_HI);
		g2.fillRect(0, hudY, w, 2);
		g2.setColor(HUD_BEVEL_LO);
		g2.fillRect(0, h - 2, w, 2);
		drawRivet(g2, 5, hudY + 5);
		drawRivet(g2, w - 8, hudY + 5);
		drawRivet(g2, 5, h - 8);
		drawRivet(g2, w - 8, h - 8);

		// Section layout: AMMO | HEALTH | FACE | ARMOR
		int faceW  = Math.min(hudH, w / 5);
		int faceX  = (w - faceW) / 2;
		int cellW  = faceX / 2;

		drawSlotDivider(g2, cellW, hudY, hudH);
		drawSlotDivider(g2, faceX, hudY, hudH);
		drawSlotDivider(g2, faceX + faceW, hudY, hudH);
		drawSlotDivider(g2, faceX + faceW + cellW, hudY, hudH);

		int health = Math.round(clamp(energy, 0f, 1f) * 100);
		int armor  = Math.round(clamp(treble, 0f, 1f) * 100);

		drawCounter(g2, 0,                  cellW, hudY, hudH, "AMMO",   ammo,   false);
		drawCounter(g2, cellW,              cellW, hudY, hudH, "HEALTH", health, true);
		drawCounter(g2, faceX + faceW,      cellW, hudY, hudH, "ARMOR",  armor,  true);
		drawCounter(g2, faceX + faceW + cellW, Math.max(1, w - (faceX + faceW + cellW)),
				hudY, hudH, "FRAGS", Math.min(999, (frameCount / 3600)), false);

		drawFace(g2, faceX, faceW, hudY, hudH);
	}

	private void drawRivet(Graphics2D g2, int x, int y) {
		g2.setColor(HUD_BEVEL_LO);
		g2.fillRect(x, y, 3, 3);
		g2.setColor(HUD_BEVEL_HI);
		g2.fillRect(x, y, 2, 2);
	}

	private void drawSlotDivider(Graphics2D g2, int x, int hudY, int hudH) {
		g2.setColor(HUD_SLOT);
		g2.fillRect(x - 1, hudY + 4, 2, hudH - 8);
		g2.setColor(HUD_BEVEL_HI);
		g2.fillRect(x + 1, hudY + 4, 1, hudH - 8);
	}

	private void drawCounter(Graphics2D g2, int x, int cw, int hudY, int hudH,
	                         String label, int value, boolean percent) {
		int labelH = Math.max(10, hudH / 5);
		Font labelFont = new Font(Font.MONOSPACED, Font.BOLD, labelH);
		g2.setFont(labelFont);
		g2.setColor(LABEL_GOLD);
		int lw = g2.getFontMetrics().stringWidth(label);
		g2.drawString(label, x + (cw - lw) / 2, hudY + labelH + 4);

		String digits = Integer.toString(clamp(value, 0, 999));
		int glyphs = digits.length() + (percent ? 1 : 0);
		int scale  = Math.max(2, (hudH - labelH - 14) / 7);
		int gw     = 6 * scale; // 5px glyph + 1px gap
		int totalW = glyphs * gw - scale;
		int dx     = x + (cw - totalW) / 2;
		int dy     = hudY + labelH + 8;

		for (int i = 0; i < digits.length(); i++) {
			drawGlyph(g2, digits.charAt(i) - '0', dx + i * gw, dy, scale);
		}
		if (percent) {
			drawGlyph(g2, PERCENT, dx + digits.length() * gw, dy, scale);
		}
	}

	private void drawGlyph(Graphics2D g2, int glyph, int x, int y, int scale) {
		String[] rows = DIGITS[glyph];
		// Drop-shadow outline first, then the red gradient body.
		g2.setColor(DIGIT_OUTLINE);
		for (int r = 0; r < 7; r++) {
			for (int c = 0; c < 5; c++) {
				if (rows[r].charAt(c) == '1') {
					g2.fillRect(x + c * scale + 1, y + r * scale + 1, scale, scale);
				}
			}
		}
		for (int r = 0; r < 7; r++) {
			g2.setColor(lerp(DIGIT_BRIGHT, DIGIT_DARK, r / 6f));
			for (int c = 0; c < 5; c++) {
				if (rows[r].charAt(c) == '1') {
					g2.fillRect(x + c * scale, y + r * scale, scale, scale);
				}
			}
		}
	}

	// ---- Face ----

	private void drawFace(Graphics2D g2, int faceX, int faceW, int hudY, int hudH) {
		int rows  = FACE_BASE.length;
		int scale = Math.max(1, Math.min((faceW - 4) / FACE_W, (hudH - 6) / rows));
		int ox    = faceX + (faceW - FACE_W * scale) / 2;
		int oy    = hudY + (hudH - rows * scale) / 2;
		boolean grinning = grin > 0.25f || berserk > 0.8f;
		boolean shouting = !grinning && shock > 0.3f;
		boolean blinking = blinkTimer > 0;

		for (int r = 0; r < rows; r++) {
			String row = FACE_BASE[r];
			for (int c = 0; c < FACE_W && c < row.length(); c++) {
				char ch = row.charAt(c);
				if (ch == '.') continue;
				// Blink: skin over the eye whites for a few frames.
				if (blinking && ch == 'e') ch = 'S';
				Color col = facePixel(ch);
				if (col == null) continue;
				g2.setColor(col);
				g2.fillRect(ox + c * scale, oy + r * scale, scale, scale);
			}
		}

		// Pupils track the wandering gaze (rows 8-9; eye whites at cols 4..7 / 13..16).
		if (!blinking) {
			g2.setColor(new Color(0x1B, 0x20, 0x26));
			g2.fillRect(ox + (5 + gazeDir) * scale, oy + 8 * scale, scale, 2 * scale);
			g2.fillRect(ox + (14 + gazeDir) * scale, oy + 8 * scale, scale, 2 * scale);
		}

		// Mouth overlay: evil grin on beats / berserk, open shout after a bolt.
		if (grinning || shouting) {
			String[] mouth = grinning ? MOUTH_GRIN : MOUTH_SHOUT;
			for (int r = 0; r < mouth.length; r++) {
				String row = mouth[r];
				for (int c = 0; c < row.length(); c++) {
					char ch = row.charAt(c);
					if (ch == '.') continue;
					Color col = facePixel(ch);
					if (col == null) continue;
					g2.setColor(col);
					g2.fillRect(ox + (5 + c) * scale, oy + (14 + r) * scale, scale, scale);
				}
			}
		}
	}

	/** Face palette; skin tones drift toward berserk-pack blood red as the meter fills. */
	private Color facePixel(char ch) {
		return switch (ch) {
			case 'H' -> new Color(0x5A, 0x3A, 0x20);
			case 's' -> bloodTint(new Color(0xD6, 0x9A, 0x62));
			case 'S' -> bloodTint(new Color(0xA9, 0x6F, 0x3F));
			case 'b' -> new Color(0x3B, 0x2A, 0x16);
			case 'e' -> new Color(0xEF, 0xEA, 0xDB);
			case 'n' -> bloodTint(new Color(0xB9, 0x7F, 0x4B));
			case 'M' -> new Color(0x3A, 0x1E, 0x12);
			case 't' -> new Color(0xE8, 0xE0, 0xC8);
			case 'c' -> new Color(0x4A, 0x6B, 0x33);
			case 'C' -> new Color(0x33, 0x49, 0x1F);
			default  -> null;
		};
	}

	private Color bloodTint(Color base) {
		if (berserk <= 0.02f) return base;
		return lerp(base, new Color(0xB3, 0x20, 0x0F), berserk * 0.8f);
	}

	// ---- Message line ----

	private void drawMessage(Graphics2D g2, int w, int h) {
		if (message == null || messageTtl <= 0) return;
		int size = clamp(h / 22, 11, 20);
		g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, size));
		int alpha = messageTtl > 40 ? 255 : Math.round(255 * messageTtl / 40f);

		String text = message;
		int maxW = w - 16;
		while (text.length() > 4 && g2.getFontMetrics().stringWidth(text) > maxW) {
			text = text.substring(0, text.length() - 4) + "...";
		}
		g2.setColor(new Color(0, 0, 0, alpha));
		g2.drawString(text, 9, 7 + size);
		g2.setColor(new Color(MESSAGE_RED.getRed(), MESSAGE_RED.getGreen(), MESSAGE_RED.getBlue(), alpha));
		g2.drawString(text, 8, 6 + size);
	}

	// ---- Screen melt ----

	private void startMelt() {
		// Classic Doom wipe: neighbouring columns start at randomly-walked offsets.
		meltDelay[0] = rnd.nextInt(16);
		for (int i = 1; i < MELT_COLS; i++) {
			meltDelay[i] = clamp(meltDelay[i - 1] + rnd.nextInt(3) - 1, 0, 15);
		}
		meltTimer = 0;
	}

	private void drawMelt(Graphics2D g2, int w, int h) {
		if (meltTimer < 0) return;
		int speed = Math.max(5, h / 26);
		int colW  = Math.max(1, (w + MELT_COLS - 1) / MELT_COLS);
		boolean anyCovering = false;

		g2.setColor(Color.BLACK);
		for (int i = 0; i < MELT_COLS; i++) {
			int off = Math.max(0, meltTimer - meltDelay[i]) * speed;
			if (off < h) {
				g2.fillRect(i * colW, off, colW, h - off);
				anyCovering = true;
			}
		}
		meltTimer++;
		if (!anyCovering) meltTimer = -1;
	}

	// ---- Helpers ----

	private static Color lerp(Color a, Color b, float t) {
		t = clamp(t, 0f, 1f);
		return new Color(
			Math.round(a.getRed()   + (b.getRed()   - a.getRed())   * t),
			Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
			Math.round(a.getBlue()  + (b.getBlue()  - a.getBlue())  * t));
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
