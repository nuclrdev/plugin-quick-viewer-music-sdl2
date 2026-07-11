package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

import sdl2.AudioRingBuffer;

/**
 * "Amiga Cracktro" — a Commodore Amiga tribute visualizer.
 * <p>
 * All the beloved OCS-era iconography in one scene: the red/white checkered
 * Boing Ball (procedurally ray-shaded, tilted axis, squash-and-stretch)
 * bouncing over the purple demo grid, a bank of Copper rasterbars — each one
 * wired to its own frequency band so the whole sky pumps with the mix — and a
 * rainbow sine-wave cracktro scroller complete with scene greetings. Beats
 * kick the ball higher and flash the grid; sustained energy speeds the spin.
 * <p>
 * Leave it in silence long enough and the machine crashes the only way an
 * Amiga knew how: a blinking red-bordered Guru Meditation.
 * <p>
 * Self-contained like its siblings: owns its FFT scratch buffers, the ball
 * framebuffer and all animation state; everything heavy is pre-allocated or
 * only re-allocated on resize, and the ring buffer snapshot is read on the
 * Swing EDT.
 */
final class AmigaVisualizer {

	// ---- FFT / analysis ----
	private static final int FFT_SIZE    = 2048;
	private static final int SAMPLE_RATE = 44100;
	private static final int NUM_BANDS   = 6;

	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   bandBin  = new int[NUM_BANDS + 1];
	private final float[] bands    = new float[NUM_BANDS];

	private float bass, energy;   // smoothed 0..1
	private float bassAvg;        // slow average for beat onset
	private float autoGain = 8f;
	private int   framesSinceBeat = 999;
	private float beatPulse = 0f;

	// ---- Boing ball ----
	private static final Color BALL_RED   = new Color(0xE0, 0x24, 0x24);
	private static final Color BALL_WHITE = new Color(0xF2, 0xF2, 0xF2);

	private BufferedImage ballImage;
	private int[]         ballPx;
	private int           ballR = -1;

	private float ballX = -1f, ballY, ballVx, ballVy;
	private float spin, spinV = 0.035f;
	private float squash = 0f;

	// ---- Copper bars ----
	private static final float[] BAR_HUES   = { 0.00f, 0.11f, 0.33f, 0.50f, 0.62f, 0.85f };
	private static final float[] BAR_SPEED  = { 0.017f, 0.023f, 0.013f, 0.027f, 0.019f, 0.031f };
	private static final float[] BAR_PHASE  = { 0.0f, 1.3f, 2.7f, 4.1f, 5.2f, 0.7f };
	private static final int     BAR_STEPS  = 9; // quantized shades per bar, Copper-list style

	// ---- Grid ----
	private static final Color GRID_PURPLE = new Color(150, 80, 220);

	// ---- Scroller ----
	private static final String GREETINGS =
		"   *** NUCLR COMMANDER PRESENTS: THE AMIGA CRACKTRO ***   " +
		"GREETINGS FLY OUT TO FAIRLIGHT + RAZOR 1911 + SKID ROW + QUARTEX + PARADOX + KEFRENS ...   " +
		"BOING! BOING! BOING!   INSERT DISK 2 IN ANY DRIVE ...      ";
	private String scrollText = GREETINGS;
	private float  scrollX    = Float.NaN;

	// ---- Guru meditation (idle) ----
	private static final int IDLE_BEFORE_GURU = 240;
	private int idleFrames = 0;

	AmigaVisualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		// Log-spaced band edges, 40 Hz .. 11 kHz — one Copper bar per band.
		float logMin = (float) Math.log(40), logMax = (float) Math.log(11000);
		for (int b = 0; b <= NUM_BANDS; b++) {
			float freq = (float) Math.exp(logMin + (logMax - logMin) * b / NUM_BANDS);
			bandBin[b] = clamp(Math.round(freq * FFT_SIZE / SAMPLE_RATE), 1, FFT_SIZE / 2);
		}
		for (int b = 1; b <= NUM_BANDS; b++) {
			if (bandBin[b] <= bandBin[b - 1]) bandBin[b] = bandBin[b - 1] + 1;
		}
	}

	/** New tune: put it on the marquee and restart the scroller. */
	void setTrackTitle(String title) {
		if (title != null && !title.isBlank()) {
			scrollText = "   *** NOW PLAYING: " + title.toUpperCase() + " ***   " + GREETINGS;
		} else {
			scrollText = GREETINGS;
		}
		scrollX = Float.NaN; // restart from the right edge
	}

	// ---- Render entry ----

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {
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
		beatPulse *= 0.92f;

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,      RenderingHints.VALUE_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,     RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

		if (idleFrames > IDLE_BEFORE_GURU) {
			drawGuru(g2, w, h, frameCount);
			return;
		}

		drawSky(g2, w, h);
		drawCopperBars(g2, w, h, frameCount);
		drawGrid(g2, w, h);
		updateBall(w, h);
		drawBall(g2, w, h);
		drawScroller(g2, w, h, frameCount);
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

		float targetGain = clamp(0.8f / rawMax, 1f, 400f);
		autoGain += (targetGain - autoGain) * (targetGain < autoGain ? 0.15f : 0.02f);

		float sum = 0f;
		for (int b = 0; b < NUM_BANDS; b++) {
			float n = clamp(raw[b] * autoGain, 0f, 1f);
			bands[b] += (n - bands[b]) * (n > bands[b] ? 0.5f : 0.10f);
			sum += bands[b];
		}
		float nb = clamp((bands[0] + bands[1]) * 0.6f, 0f, 1f);
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
		beatPulse = 1f;
		// Kick the ball: extra lift on its way down, extra spin always.
		if (ballVy > 0) ballVy -= 0.004f * lastH;
		spinV = Math.min(0.14f, spinV + 0.02f);
	}

	// ---- Sky ----

	private void drawSky(Graphics2D g2, int w, int h) {
		// Copper-list sky: a quantized midnight gradient in chunky horizontal bands.
		int bandsN = 14;
		int bandH = h / bandsN + 1;
		for (int i = 0; i < bandsN; i++) {
			float t = (float) i / (bandsN - 1);
			g2.setColor(new Color(
				(int) (10 + 14 * t),
				(int) (8 + 6 * t),
				(int) (26 + 22 * t)));
			g2.fillRect(0, i * bandH, w, bandH);
		}
	}

	// ---- Copper bars ----

	private void drawCopperBars(Graphics2D g2, int w, int h, int frameCount) {
		for (int b = 0; b < NUM_BANDS; b++) {
			float level = bands[b];
			float cy = h * 0.32f
					+ (float) Math.sin(frameCount * BAR_SPEED[b] + BAR_PHASE[b]) * h * 0.24f;
			int thick = Math.round(h * 0.030f + level * h * 0.055f);
			if (thick < BAR_STEPS) thick = BAR_STEPS;
			int top = Math.round(cy - thick / 2f);

			float bright = 0.30f + 0.70f * level + 0.15f * beatPulse;
			int stepH = Math.max(1, thick / BAR_STEPS);
			for (int s = 0; s < BAR_STEPS; s++) {
				// Mirrored ramp: dark -> bright -> dark, like a real Copper gradient.
				float m = 1f - Math.abs(s - (BAR_STEPS - 1) / 2f) / ((BAR_STEPS - 1) / 2f);
				Color c = Color.getHSBColor(BAR_HUES[b], 0.88f - 0.45f * m, clamp(bright * (0.35f + 0.75f * m), 0f, 1f));
				g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 235));
				g2.fillRect(0, top + s * stepH, w, stepH);
			}
		}
	}

	// ---- Grid ----

	private void drawGrid(Graphics2D g2, int w, int h) {
		int wallTop   = Math.round(h * 0.16f);
		int horizonY  = Math.round(h * 0.68f);
		int floorBot  = Math.round(h * 0.88f);
		int alpha     = clamp(105 + Math.round(90 * beatPulse), 0, 255);
		g2.setColor(new Color(GRID_PURPLE.getRed(), GRID_PURPLE.getGreen(), GRID_PURPLE.getBlue(), alpha));
		g2.setStroke(new BasicStroke(1f));

		int cols = 14;
		float colW = (float) w / cols;

		// Back wall.
		for (int i = 0; i <= cols; i++) {
			int x = Math.round(i * colW);
			g2.drawLine(x, wallTop, x, horizonY);
		}
		int wallRows = 7;
		for (int i = 0; i <= wallRows; i++) {
			int y = wallTop + Math.round((horizonY - wallTop) * (float) i / wallRows);
			g2.drawLine(0, y, w, y);
		}

		// Floor, fanning out from the horizon.
		float vpx = w * 0.5f;
		for (int i = 0; i <= cols; i++) {
			float xTop = i * colW;
			float xBot = vpx + (xTop - vpx) * 1.9f;
			g2.drawLine(Math.round(xTop), horizonY, Math.round(xBot), floorBot);
		}
		int floorRows = 5;
		for (int i = 1; i <= floorRows; i++) {
			float t = (float) i / floorRows;
			int y = horizonY + Math.round((floorBot - horizonY) * t * t);
			g2.drawLine(0, y, w, y);
		}
	}

	// ---- Boing ball ----

	private int lastH = 400; // remembered for beat impulses between frames

	private void updateBall(int w, int h) {
		lastH = h;
		float r = ballRadius(w, h);
		float floorY = h * 0.84f;

		if (ballX < 0f) { // first frame
			ballX = w * 0.3f;
			ballY = h * 0.3f;
			ballVx = w * 0.0045f;
			ballVy = 0f;
		}

		float gravity = 0.00055f * h;
		ballVy += gravity;
		ballX  += ballVx;
		ballY  += ballVy;

		if (ballX - r < 0)  { ballX = r;     ballVx = Math.abs(ballVx); }
		if (ballX + r > w)  { ballX = w - r; ballVx = -Math.abs(ballVx); }
		if (ballY + r > floorY) {
			ballY = floorY - r;
			ballVy = -0.021f * h * (0.9f + 0.35f * energy);
			squash = 1f;
		}
		if (ballY - r < 0) { ballY = r; ballVy = Math.abs(ballVy); }

		squash *= 0.82f;
		spinV  += (0.035f + energy * 0.05f - spinV) * 0.04f;
		spin   += spinV * Math.signum(ballVx);
	}

	private float ballRadius(int w, int h) {
		return clamp(Math.min(w, h) * 0.17f, 12f, 80f);
	}

	private void drawBall(Graphics2D g2, int w, int h) {
		int r = Math.round(ballRadius(w, h));
		renderBallImage(r);

		float sx = 1f + 0.18f * squash;
		float sy = 1f - 0.24f * squash;
		int dw = Math.round(2 * r * sx);
		int dh = Math.round(2 * r * sy);
		int dx = Math.round(ballX - dw / 2f);
		int dy = Math.round(ballY + r - dh); // keep the squashed ball planted on its floor contact

		// Offset drop shadow on the floor, just like the original demo.
		float floorY = h * 0.84f;
		float lift   = clamp((floorY - r - (ballY - r)) / Math.max(1f, h * 0.5f), 0f, 1f);
		int shAlpha  = Math.round(90 * (1f - 0.6f * lift));
		int shRx     = Math.round(r * (0.95f - 0.3f * lift) * sx);
		int shRy     = Math.round(r * 0.20f);
		g2.setColor(new Color(0, 0, 0, shAlpha));
		g2.fillOval(Math.round(ballX + r * 0.35f) - shRx, Math.round(floorY) - shRy, shRx * 2, shRy * 2);

		g2.drawImage(ballImage, dx, dy, dw, dh, null);
	}

	/** Ray-shade the checkered sphere into the ball framebuffer (tilted axis, quantized light). */
	private void renderBallImage(int r) {
		int d = 2 * r;
		if (ballR != r) {
			ballR = r;
			ballImage = new BufferedImage(d, d, BufferedImage.TYPE_INT_ARGB);
			ballPx = new int[d * d];
		}

		float tilt = 0.34f; // the Boing ball spins around a jauntily tilted axis
		float ct = (float) Math.cos(tilt), st = (float) Math.sin(tilt);
		float cs = (float) Math.cos(spin), ss = (float) Math.sin(spin);

		for (int py = 0; py < d; py++) {
			float ny = (py - r + 0.5f) / r;
			for (int px = 0; px < d; px++) {
				float nx = (px - r + 0.5f) / r;
				float d2 = nx * nx + ny * ny;
				int idx = py * d + px;
				if (d2 > 1f) {
					ballPx[idx] = 0;
					continue;
				}
				float nz = (float) Math.sqrt(1f - d2);

				// Tilt about the view axis, then spin about the ball's own axis.
				float x1 = nx * ct - ny * st;
				float y1 = nx * st + ny * ct;
				float x2 = x1 * cs + nz * ss;
				float z2 = -x1 * ss + nz * cs;

				int lon = (int) Math.floor((Math.atan2(x2, z2) / Math.PI + 1.0) * 4.0);  // 8 segments
				int lat = (int) Math.floor((Math.asin(clamp(y1, -1f, 1f)) / Math.PI + 0.5) * 6.0); // 6 rings
				boolean red = ((lon + lat) & 1) == 0;

				// View-fixed light, quantized to 4 chunky shades.
				float dot = nx * -0.42f + ny * -0.48f + nz * 0.76f;
				float shade = 0.45f + 0.55f * Math.max(0f, dot);
				shade = (float) (Math.ceil(shade * 4f) / 4f);

				Color base = red ? BALL_RED : BALL_WHITE;
				int cr = Math.min(255, Math.round(base.getRed()   * shade));
				int cg = Math.min(255, Math.round(base.getGreen() * shade));
				int cb = Math.min(255, Math.round(base.getBlue()  * shade));
				ballPx[idx] = 0xFF000000 | (cr << 16) | (cg << 8) | cb;
			}
		}
		ballImage.setRGB(0, 0, d, d, ballPx, 0, d);
	}

	// ---- Scroller ----

	private void drawScroller(Graphics2D g2, int w, int h, int frameCount) {
		int fs = clamp(Math.round(h / 9f), 14, 40);
		g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, fs));
		FontMetrics fm = g2.getFontMetrics();
		int cw = fm.charWidth('W');
		float baseY = h * 0.93f;

		if (Float.isNaN(scrollX)) scrollX = w;
		scrollX -= 2.0f + 1.6f * energy;
		float totalW = scrollText.length() * cw;
		if (scrollX < -totalW) scrollX = w;

		float amp = fs * (0.28f + 0.30f * energy);
		for (int i = 0; i < scrollText.length(); i++) {
			float cx = scrollX + i * cw;
			if (cx < -cw || cx > w) continue;
			char ch = scrollText.charAt(i);
			if (ch == ' ') continue;
			int y = Math.round(baseY + (float) Math.sin(cx * 0.018f + frameCount * 0.09f) * amp);

			// Copper-rainbow fill with a hard shadow for readability.
			Color c = Color.getHSBColor((cx * 0.0022f + frameCount * 0.004f) % 1f, 0.85f, 1f);
			g2.setColor(Color.BLACK);
			g2.drawString(String.valueOf(ch), Math.round(cx) + 2, y + 2);
			g2.setColor(c);
			g2.drawString(String.valueOf(ch), Math.round(cx), y);
		}
	}

	// ---- Guru meditation ----

	private void drawGuru(Graphics2D g2, int w, int h, int frameCount) {
		g2.setColor(Color.BLACK);
		g2.fillRect(0, 0, w, h);

		int boxH = clamp(Math.round(h * 0.26f), 44, 120);
		int inset = 6;

		// The border blinks; the text never does.
		if ((frameCount / 45) % 2 == 0) {
			g2.setColor(new Color(0xFF, 0x20, 0x10));
			g2.setStroke(new BasicStroke(3f));
			g2.drawRect(inset, inset, w - 2 * inset, boxH);
		}

		int fs = clamp(Math.round(h / 22f), 10, 18);
		g2.setFont(new Font(Font.MONOSPACED, Font.BOLD, fs));
		FontMetrics fm = g2.getFontMetrics();
		g2.setColor(new Color(0xFF, 0x20, 0x10));

		String l1 = "Software Failure.  Press left mouse button to continue.";
		String l2 = String.format("Guru Meditation #%08X.%08X", scrollText.hashCode(), 0x0000AAC0);
		int y1 = inset + boxH / 2 - fs / 3;
		int y2 = y1 + fm.getHeight();
		g2.drawString(l1, Math.max(inset + 8, (w - fm.stringWidth(l1)) / 2), y1);
		g2.drawString(l2, Math.max(inset + 8, (w - fm.stringWidth(l2)) / 2), y2);
	}

	// ---- Helpers ----

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
