package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * "Macintosh" — a 1984 one-bit tribute where the star of the show is the
 * renderer itself: the whole 512x342 desktop is composed in greyscale and
 * then pushed through a genuine Atkinson error-diffusion dither every single
 * frame, so a full-motion audio visualizer comes out looking like an
 * animated MacPaint document.
 * <p>
 * The scene is a proper System-era desktop: dithered grey wallpaper, menu
 * bar with a live clock, and a striped-title-bar window named after the
 * current track. Inside it, a radial greyscale plasma breathes with the
 * music while a crisp oscilloscope trace rides on top; a second overlapping
 * window shows eight spectrum meters as shifting grey columns that the
 * dither turns into swimming dot patterns. A mouse pointer wanders the
 * desktop and clicks a menu on every beat, flashing it inverted, while the
 * beat also gives the front window a little bounce.
 * <p>
 * A new track boots the machine — happy little computer icon, then the
 * {@code Welcome to Macintosh} splash — and when the music stops the era's
 * dreaded bomb dialog appears: {@code Sorry, a system error occurred. The
 * music has stopped.} The CRT's rounded corners are masked over everything,
 * because that glass never was square.
 */
final class MacVisualizer {

	// ---- Screen ----
	private static final int SCREEN_W = 512;
	private static final int SCREEN_H = 342;

	private final BufferedImage scene  = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB);
	private final int[] sceneData = ((DataBufferInt) scene.getRaster().getDataBuffer()).getData();
	private final BufferedImage screen = new BufferedImage(SCREEN_W, SCREEN_H, BufferedImage.TYPE_INT_RGB);
	private final int[] px = ((DataBufferInt) screen.getRaster().getDataBuffer()).getData();

	// Atkinson error rows (index shifted by +1 so x-1 never underflows).
	private final int[] err0 = new int[SCREEN_W + 3];
	private final int[] err1 = new int[SCREEN_W + 3];
	private final int[] err2 = new int[SCREEN_W + 3];

	// ---- FFT / analysis ----
	private static final int FFT_SIZE    = 2048;
	private static final int SAMPLE_RATE = 44100;
	private static final int NUM_BANDS   = 8;

	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   bandBin  = new int[NUM_BANDS + 1];
	private final float[] bands    = new float[NUM_BANDS];

	private float bass, energy;
	private float bassAvg;
	private float autoGain = 8f;
	private int   framesSinceBeat = 999;
	private float beatPulse = 0f;

	// ---- Sine table for the plasma ----
	private final float[] sinT = new float[1024];

	// ---- Desktop state ----
	private static final String[] MENUS = { "*", "File", "Edit", "View", "Special" };
	private final int[] menuX = new int[MENUS.length];
	private int menuFlash = -1;
	private int menuFlashTimer = 0;

	private float ptrX = 250f, ptrY = 200f;
	private float ptrTX = 250f, ptrTY = 200f;
	private int   ptrRetarget = 0;

	private static final String[] POINTER = {
		"B..........",
		"BB.........",
		"BWB........",
		"BWWB.......",
		"BWWWB......",
		"BWWWWB.....",
		"BWWWWWB....",
		"BWWWWWWB...",
		"BWWWWWWWB..",
		"BWWWWWBBBB.",
		"BWWBWWB....",
		"BWB.BWWB...",
		"BB..BWWB...",
		"B....BWWB..",
		".....BWWB..",
		"......BB...",
	};

	// ---- State ----
	private String trackTitle = "Untitled";
	private int    bootTimer  = 0;
	private int    idleFrames = 0;
	private int    frame      = 0;
	private final Random rnd = new Random(0x1984);

	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm");

	MacVisualizer() {
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
		for (int i = 0; i < sinT.length; i++) {
			sinT[i] = (float) Math.sin(i * 2 * Math.PI / sinT.length);
		}
		int x = 12;
		for (int m = 0; m < MENUS.length; m++) {
			menuX[m] = x;
			x += MENUS[m].length() * 9 + 22;
		}
	}

	private float sin(float turns) {
		return sinT[((int) (turns * sinT.length) % sinT.length + sinT.length) % sinT.length];
	}

	/** New tune: reboot into a freshly-titled document. */
	void setTrackTitle(String title) {
		trackTitle = title == null || title.isBlank() ? "Untitled" : title;
		bootTimer = 150;
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
			energy += (0f - energy) * 0.06f;
			idleFrames++;
		}
		beatPulse *= 0.9f;
		if (bootTimer > 0) bootTimer--;
		if (menuFlashTimer > 0 && --menuFlashTimer == 0) menuFlash = -1;

		if (bootTimer > 0) {
			drawBootScene();
		} else {
			drawDesktopScene(samples);
		}
		atkinsonDither();
		maskCrtCorners();

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.setColor(new Color(0x1A, 0x1A, 0x1A));
		g2.fillRect(0, 0, w, h);
		int sw = Math.max(64, Math.min(w, h * SCREEN_W / SCREEN_H));
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
		float nb = clampF((bands[0] + bands[1]) * 0.6f, 0f, 1f);
		bass   += (nb - bass) * (nb > bass ? 0.5f : 0.12f);
		energy += (sum / NUM_BANDS - energy) * 0.15f;

		bassAvg += (bass - bassAvg) * 0.02f;
		framesSinceBeat++;
		if (framesSinceBeat > 14 && bass > 0.14f && bass > bassAvg * 1.45f + 0.03f) {
			framesSinceBeat = 0;
			beatPulse = 1f;
			// The pointer darts up and clicks a menu.
			menuFlash = rnd.nextInt(MENUS.length);
			menuFlashTimer = 10;
			ptrTX = menuX[menuFlash] + 10;
			ptrTY = 10;
			ptrRetarget = 50;
		}
	}

	// ---- Desktop ----

	private void drawDesktopScene(int samples) {
		Graphics2D g = scene.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);

			// Desktop grey — the dither turns it into the classic checker.
			g.setColor(new Color(128, 128, 128));
			g.fillRect(0, 0, SCREEN_W, SCREEN_H);

			drawMenuBar(g);

			int bounce = Math.round(beatPulse * 3f);
			drawMainWindow(g, 56, 44 - bounce, 336, 250, samples);
			drawLevelsWindow(g, 350, 190, 148, 130);

			if (idleFrames > 240) {
				drawBombDialog(g);
			}
			drawPointer(g);
		} finally {
			g.dispose();
		}
	}

	private void drawMenuBar(Graphics2D g) {
		g.setColor(Color.WHITE);
		g.fillRect(0, 0, SCREEN_W, 20);
		g.setColor(Color.BLACK);
		g.fillRect(0, 20, SCREEN_W, 1);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		for (int m = 0; m < MENUS.length; m++) {
			if (m == menuFlash) {
				g.setColor(Color.BLACK);
				g.fillRect(menuX[m] - 8, 0, MENUS[m].length() * 9 + 16, 20);
				g.setColor(Color.WHITE);
			} else {
				g.setColor(Color.BLACK);
			}
			g.drawString(MENUS[m], menuX[m], 15);
		}
		g.setColor(Color.BLACK);
		String clock = LocalTime.now().format(CLOCK);
		g.drawString(clock, SCREEN_W - 48, 15);
	}

	private void drawWindowChrome(Graphics2D g, int x, int y, int w, int h, String title) {
		// Drop shadow (mid grey dithers into a soft speckle).
		g.setColor(new Color(80, 80, 80));
		g.fillRect(x + 3, y + 3, w, h);
		g.setColor(Color.WHITE);
		g.fillRect(x, y, w, h);
		g.setColor(Color.BLACK);
		g.drawRect(x, y, w, h);

		// Striped title bar with a white gap for the centred title.
		g.drawRect(x, y, w, 18);
		for (int ly = y + 3; ly < y + 17; ly += 3) {
			g.drawLine(x + 2, ly, x + w - 2, ly);
		}
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		int tw = g.getFontMetrics().stringWidth(title);
		g.setColor(Color.WHITE);
		g.fillRect(x + (w - tw) / 2 - 8, y + 1, tw + 16, 17);
		g.setColor(Color.BLACK);
		g.drawString(title, x + (w - tw) / 2, y + 14);

		// Close box.
		g.setColor(Color.WHITE);
		g.fillRect(x + 8, y + 4, 11, 11);
		g.setColor(Color.BLACK);
		g.drawRect(x + 8, y + 4, 11, 11);
	}

	private void drawMainWindow(Graphics2D g, int x, int y, int w, int h, int samples) {
		String title = trackTitle.length() > 30 ? trackTitle.substring(0, 30) : trackTitle;
		drawWindowChrome(g, x, y, w, h, title);

		// Content: radial greyscale plasma, written straight into the buffer.
		int cx0 = x + 1, cy0 = y + 19;
		int cw = w - 2, ch = h - 20;
		float t = frame * 0.006f;
		float amp = 0.35f + energy * 0.6f;
		float mx = cx0 + cw / 2f, my = cy0 + ch / 2f;
		for (int yy = 0; yy < ch; yy++) {
			int base = (cy0 + yy) * SCREEN_W + cx0;
			float dy = (cy0 + yy - my);
			for (int xx = 0; xx < cw; xx++) {
				float dx = (cx0 + xx - mx);
				float dist = (float) Math.sqrt(dx * dx + dy * dy);
				float v = sin(dist * 0.0055f - t * 2.1f) + sin((dx + dy) * 0.0031f + t);
				int grey = clampI(Math.round(210 + v * 90f * amp), 0, 255);
				sceneData[base + xx] = grey << 16 | grey << 8 | grey;
			}
		}

		// Crisp black oscilloscope trace over the plasma. The freshest samples
		// sit at the start of the snapshot buffer.
		if (samples >= 4) {
			g.setColor(Color.BLACK);
			g.setStroke(new BasicStroke(2f));
			int points = Math.min(360, samples);
			int prevX = cx0, prevY = Math.round(my);
			for (int i = 0; i < points; i++) {
				int sx = cx0 + i * (cw - 1) / (points - 1);
				float sample = snapshot[i];
				int sy = Math.round(my + clampF(sample * 2.2f, -1f, 1f) * ch * 0.34f);
				if (i > 0) g.drawLine(prevX, prevY, sx, sy);
				prevX = sx;
				prevY = sy;
			}
		}
	}

	private void drawLevelsWindow(Graphics2D g, int x, int y, int w, int h) {
		drawWindowChrome(g, x, y, w, h, "Levels");
		int cx0 = x + 8, cy0 = y + 26;
		int ch = h - 36;
		int bw = (w - 16) / NUM_BANDS;
		for (int b = 0; b < NUM_BANDS; b++) {
			int bh = Math.round(bands[b] * ch);
			int grey = clampI(200 - Math.round(bands[b] * 170), 0, 255);
			g.setColor(new Color(grey, grey, grey));
			g.fillRect(cx0 + b * bw, cy0 + ch - bh, bw - 3, bh);
			g.setColor(Color.BLACK);
			g.drawRect(cx0 + b * bw, cy0 + ch - bh, bw - 3, bh);
		}
	}

	private void drawPointer(Graphics2D g) {
		if (ptrRetarget-- <= 0) {
			ptrTX = 40 + rnd.nextFloat() * (SCREEN_W - 80);
			ptrTY = 40 + rnd.nextFloat() * (SCREEN_H - 80);
			ptrRetarget = 120 + rnd.nextInt(140);
		}
		ptrX += (ptrTX - ptrX) * 0.07f;
		ptrY += (ptrTY - ptrY) * 0.07f;

		int ox = Math.round(ptrX), oy = Math.round(ptrY);
		for (int r = 0; r < POINTER.length; r++) {
			String row = POINTER[r];
			for (int c = 0; c < row.length(); c++) {
				char ch = row.charAt(c);
				if (ch == '.') continue;
				g.setColor(ch == 'B' ? Color.BLACK : Color.WHITE);
				g.fillRect(ox + c, oy + r, 1, 1);
			}
		}
	}

	// ---- Boot & bomb ----

	private void drawBootScene() {
		Graphics2D g = scene.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setColor(new Color(128, 128, 128));
			g.fillRect(0, 0, SCREEN_W, SCREEN_H);

			if (bootTimer > 90) {
				// The happy little computer.
				int cx = SCREEN_W / 2 - 24, cy = SCREEN_H / 2 - 30;
				g.setColor(Color.WHITE);
				g.fillRect(cx, cy, 48, 40);
				g.setColor(Color.BLACK);
				g.drawRect(cx, cy, 48, 40);
				g.drawRect(cx + 6, cy + 5, 35, 24);
				g.fillRect(cx + 16, cy + 12, 2, 6);   // eyes
				g.fillRect(cx + 29, cy + 12, 2, 6);
				g.fillRect(cx + 18, cy + 22, 2, 2);   // smile
				g.fillRect(cx + 20, cy + 24, 7, 2);
				g.fillRect(cx + 27, cy + 22, 2, 2);
				g.fillRect(cx + 14, cy + 44, 20, 4);  // pedestal
			} else {
				g.setColor(Color.WHITE);
				g.fillRoundRect(SCREEN_W / 2 - 120, SCREEN_H / 2 - 30, 240, 60, 12, 12);
				g.setColor(Color.BLACK);
				g.drawRoundRect(SCREEN_W / 2 - 120, SCREEN_H / 2 - 30, 240, 60, 12, 12);
				g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
				String s = "Welcome to Macintosh";
				g.drawString(s, SCREEN_W / 2 - g.getFontMetrics().stringWidth(s) / 2, SCREEN_H / 2 + 5);
			}
		} finally {
			g.dispose();
		}
	}

	private void drawBombDialog(Graphics2D g) {
		int dw = 330, dh = 110;
		int x = (SCREEN_W - dw) / 2, y = (SCREEN_H - dh) / 2;
		g.setColor(Color.WHITE);
		g.fillRect(x, y, dw, dh);
		g.setColor(Color.BLACK);
		g.drawRect(x, y, dw, dh);
		g.drawRect(x + 3, y + 3, dw - 6, dh - 6);

		// The bomb.
		g.fillOval(x + 20, y + 34, 30, 30);
		g.setStroke(new BasicStroke(2f));
		g.drawLine(x + 42, y + 36, x + 52, y + 24);
		g.drawLine(x + 52, y + 24, x + 56, y + 28);
		g.fillRect(x + 55, y + 22, 3, 3);

		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
		g.drawString("Sorry, a system error occurred.", x + 66, y + 34);
		g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
		g.drawString("The music has stopped.", x + 66, y + 54);

		g.drawRoundRect(x + dw - 92, y + dh - 36, 72, 22, 8, 8);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
		g.drawString("Restart", x + dw - 78, y + dh - 21);
	}

	// ---- Atkinson dither: 1984's finest error diffusion, every frame ----

	private void atkinsonDither() {
		java.util.Arrays.fill(err0, 0);
		java.util.Arrays.fill(err1, 0);
		java.util.Arrays.fill(err2, 0);

		for (int y = 0; y < SCREEN_H; y++) {
			int base = y * SCREEN_W;
			for (int x = 0; x < SCREEN_W; x++) {
				int idx = x + 1;
				int grey = (sceneData[base + x] & 0xFF) + err0[idx];
				int out = grey < 128 ? 0 : 255;
				int e = (grey - out) >> 3;   // Atkinson spreads 6/8 of the error
				err0[idx + 1] += e;
				err0[idx + 2] += e;
				err1[idx - 1] += e;
				err1[idx]     += e;
				err1[idx + 1] += e;
				err2[idx]     += e;
				px[base + x] = out == 0 ? 0x000000 : 0xFFFFFF;
			}
			System.arraycopy(err1, 0, err0, 0, err0.length);
			System.arraycopy(err2, 0, err1, 0, err1.length);
			java.util.Arrays.fill(err2, 0);
		}
	}

	/** That CRT glass never was square. */
	private void maskCrtCorners() {
		int r = 18;
		for (int y = 0; y < r; y++) {
			for (int x = 0; x < r; x++) {
				int dx = r - x, dy = r - y;
				if (dx * dx + dy * dy > r * r) {
					px[y * SCREEN_W + x] = 0;
					px[y * SCREEN_W + (SCREEN_W - 1 - x)] = 0;
					px[(SCREEN_H - 1 - y) * SCREEN_W + x] = 0;
					px[(SCREEN_H - 1 - y) * SCREEN_W + (SCREEN_W - 1 - x)] = 0;
				}
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
