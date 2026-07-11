package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Locale;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * "BBS / ANSI" — a dial-up bulletin board tribute visualizer.
 * <p>
 * Renders on the shared {@link TextModeScreen} as a 14400-baud terminal
 * session. Every new track redials: {@code ATZ}, {@code ATDT}, {@code CONNECT
 * 14400/ARQ/V32B}, log in as guest, ANSI art loads. The main screen is a
 * proper board: a big shaded block-letter banner (built by scaling the glyph
 * atlas up to cell-sized "pixels", TheDraw style), a ZMODEM download window
 * where the current tune transfers endlessly — CPS rate rides the music's
 * energy, and when a file completes its CRC the queue moves on to the next
 * scene MOD — a SysOp EQ panel of {@code █▄} bars, and an activity feed where
 * beats bring scene handles online one by one. Treble transients hit the
 * line itself: single-frame bursts of modem noise garbage scatter across the
 * screen.
 * <p>
 * And when the music stops, so does the carrier: {@code +++ ATH0 OK} ...
 * {@code NO CARRIER}.
 * <p>
 * Self-contained like its siblings; FFT scratch and the text screen are
 * pre-allocated, and the ring buffer snapshot is read on the EDT.
 */
final class BbsVisualizer {

	private static final int COLS = TextModeScreen.COLS, ROWS = TextModeScreen.ROWS;
	private static final int SCREEN_W = TextModeScreen.WIDTH;
	private static final int SCREEN_H = TextModeScreen.HEIGHT;

	private static final int BLACK = 0, BLUE = 1, LGRAY = 7, DGRAY = 8, LBLUE = 9;
	private static final int LGREEN = 10, LCYAN = 11, LRED = 12, LMAGENTA = 13, YELLOW = 14, WHITE = 15;

	private final TextModeScreen scr = new TextModeScreen();

	// ---- FFT / analysis ----
	private static final int FFT_SIZE    = 2048;
	private static final int SAMPLE_RATE = 44100;
	private static final int NUM_BARS    = 12;

	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];
	private final float[] hann     = new float[FFT_SIZE];
	private final int[]   barBin   = new int[NUM_BARS + 1];
	private final float[] bars     = new float[NUM_BARS];
	private final float[] barPeak  = new float[NUM_BARS];

	private float bass, treble, energy;
	private float bassAvg, trebleAvg;
	private float autoGain = 8f;
	private int   framesSinceBeat = 999;
	private int   noiseFrames = 0;

	// ---- Dial-up sequence ----
	private static final String[] DIAL_LINES = {
		"ATZ",
		"OK",
		"ATDT 1-555-682-5722",
		"",
		"CONNECT 14400/ARQ/V32B/LAPM/V42BIS",
		"",
		"Welcome to NUCLR BBS - 8 nodes of pure MOD energy",
		"Handle: guest",
		"Password: ********",
		"",
		"Loading ANSI art... done.",
	};
	private int dialChars = Integer.MAX_VALUE;
	private final int dialTotal;

	// ---- ZMODEM transfer ----
	private static final String[] QUEUE = {
		"GRAVITY.MOD", "2ND_RE~1.MOD", "UNREAL~1.S3M", "ENIGMA.MOD", "SPACED~1.XM", "CLIMAX.MOD"
	};
	private String downloadName = "INTRO~1.MOD";
	private float  downloadPct  = 0f;
	private int    completeHold = 0;
	private int    queueIdx     = 0;
	private int    fileFrames   = 0;

	// ---- Activity feed (advanced by beats) ----
	private static final String[] FEED = {
		"* AcidBurn dialed in from node 5",
		"<ZeroCool> this track SLAPS",
		"* Ph0enix downloaded 12 files today",
		"<CrashOverride> turn it UP",
		"* SysOp paged... no answer (jamming)",
		"<Cereal> mess with the best, dance like the rest",
		"* New oneliner: RIP AND TEAR",
		"<Nikon> rewind that part!!",
		"* Node 7 dropped: NO CARRIER (too much bass)",
		"<TheMentor> another one got caught grooving today",
	};
	private final String[] feedView = new String[3];
	private int feedIdx = 0;

	// ---- State ----
	private String trackTitle = "";
	private int    idleFrames = 0;
	private int    frame      = 0;
	private int    totalFrames = 0;

	private final Random rnd = new Random(0x2400BAD); // 2400 baud, bad line

	BbsVisualizer() {
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
		int total = 0;
		for (String l : DIAL_LINES) total += l.length() + 4;
		dialTotal = total;
		for (int i = 0; i < feedView.length; i++) {
			feedView[i] = FEED[i];
		}
		feedIdx = feedView.length;
	}

	/** New tune: hang up and redial; it becomes the file currently downloading. */
	void setTrackTitle(String title) {
		trackTitle = title == null ? "" : title;
		downloadName = TextModeScreen.dosName(trackTitle);
		downloadPct = 0f;
		completeHold = 0;
		fileFrames = 0;
		dialChars = 0;
	}

	// ---- Render entry ----

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {
		frame = frameCount;
		totalFrames++;
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
		advanceState(hasAudio);

		if (idleFrames > 300)            drawNoCarrier();
		else if (dialChars < dialTotal)  drawDial();
		else                             drawBoard();

		scr.rasterize();

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
		g2.setColor(Color.BLACK);
		g2.fillRect(0, 0, w, h);
		int sw = Math.min(w, h * SCREEN_W / SCREEN_H);
		sw = Math.max(80, sw);
		int sh = sw * SCREEN_H / SCREEN_W;
		g2.drawImage(scr.image(), (w - sw) / 2, (h - sh) / 2, sw, sh, null);
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
			if (bars[b] > barPeak[b]) barPeak[b] = bars[b];
		}
		float nb = clampF((bars[0] + bars[1] + bars[2]) * 0.45f, 0f, 1f);
		float nt = clampF((bars[NUM_BARS - 3] + bars[NUM_BARS - 2] + bars[NUM_BARS - 1]) * 0.45f, 0f, 1f);
		bass   += (nb - bass)   * (nb > bass   ? 0.5f : 0.12f);
		treble += (nt - treble) * (nt > treble ? 0.5f : 0.12f);
		energy += (sum / NUM_BARS - energy) * 0.15f;

		bassAvg += (bass - bassAvg) * 0.02f;
		framesSinceBeat++;
		if (framesSinceBeat > 14 && bass > 0.14f && bass > bassAvg * 1.45f + 0.03f) {
			framesSinceBeat = 0;
			pushFeed();
		}

		// Treble transient: the phone line takes a hit.
		trebleAvg += (treble - trebleAvg) * 0.02f;
		if (noiseFrames <= 0 && treble > 0.22f && treble > trebleAvg * 1.8f + 0.05f) {
			noiseFrames = 2;
		}
	}

	private void pushFeed() {
		System.arraycopy(feedView, 1, feedView, 0, feedView.length - 1);
		feedView[feedView.length - 1] = FEED[feedIdx % FEED.length];
		feedIdx++;
	}

	private void advanceState(boolean hasAudio) {
		for (int b = 0; b < NUM_BARS; b++) barPeak[b] = Math.max(bars[b], barPeak[b] - 0.005f);
		if (noiseFrames > 0) noiseFrames--;
		if (dialChars < dialTotal) {
			dialChars = Math.min(dialTotal, dialChars + 3);
			return;
		}
		if (!hasAudio) return;

		fileFrames++;
		if (completeHold > 0) {
			if (--completeHold == 0) {
				downloadName = QUEUE[queueIdx % QUEUE.length];
				queueIdx++;
				downloadPct = 0f;
				fileFrames = 0;
			}
		} else {
			downloadPct += 0.05f + energy * 0.06f;
			if (downloadPct >= 100f) {
				downloadPct = 100f;
				completeHold = 180;
			}
		}
	}

	// ---- Screens ----

	private void drawBoard() {
		scr.clear(' ', LGRAY, BLACK);

		drawBanner("NUCLR BBS", 4, 0);
		scr.fillRow(5, 0, COLS - 1, '▒', BLUE, BLACK);
		center(6, "- t h e   m o d   c a v e  -   node 2/8   -   est. 1993 -", LCYAN);

		drawZmodem();
		drawEq();

		// Activity feed: the board lives to the beat.
		for (int i = 0; i < feedView.length; i++) {
			String line = feedView[i];
			if (line == null) continue;
			if (line.length() > 78) line = line.substring(0, 78);
			scr.print(1, 20 + i, line, line.startsWith("*") ? LGREEN : LCYAN, BLACK);
		}

		// Status bar + prompt.
		scr.fillRow(23, 0, COLS - 1, ' ', WHITE, BLUE);
		String title = trackTitle.isBlank() ? "no carrier tune" : trackTitle;
		String status = " NUCLR BBS | Node 2 | 14400 baud | Time on " + mmss(totalFrames / 60) + " | " + title;
		if (status.length() > 79) status = status.substring(0, 79);
		scr.print(0, 23, status, WHITE, BLUE);
		scr.print(0, 24, "[Main] Command? ", LGRAY, BLACK);
		if (blink()) scr.put(16, 24, '▌', LGRAY, BLACK);

		drawLineNoise();
	}

	/** TheDraw-style banner: glyph atlas scanlines scaled up to whole text cells. */
	private void drawBanner(String text, int col, int row) {
		int[] sampleYs = { 3, 5, 7, 9, 11 };
		int[] rowColor = { LMAGENTA, LRED, YELLOW, WHITE, LCYAN };
		// Drop shadow first; the coloured face overwrites it where they overlap.
		for (int r = 0; r < sampleYs.length; r++) {
			for (int i = 0; i < text.length(); i++) {
				int bits = scr.glyphRow(text.charAt(i), sampleYs[r]);
				for (int x = 0; x < 8; x++) {
					if ((bits >> x & 1) != 0) {
						scr.put(col + i * 8 + x + 1, row + r + 1, '▓', DGRAY, BLACK);
					}
				}
			}
		}
		for (int r = 0; r < sampleYs.length; r++) {
			for (int i = 0; i < text.length(); i++) {
				int bits = scr.glyphRow(text.charAt(i), sampleYs[r]);
				for (int x = 0; x < 8; x++) {
					if ((bits >> x & 1) != 0) {
						scr.put(col + i * 8 + x, row + r, '█', rowColor[r], BLACK);
					}
				}
			}
		}
	}

	private void drawZmodem() {
		scr.box(1, 8, 39, 19, LBLUE, BLACK);
		scr.print(3, 8, " ZMODEM-90 ", LMAGENTA, BLACK);

		long size = 200_000L + (Math.abs(downloadName.hashCode()) % 3_000_000L);
		int  cps  = Math.round(1200 + energy * 3200 + 300 * (float) Math.sin(frame * 0.1));

		scr.print(3, 10, "Receiving: ", LGRAY, BLACK);
		scr.print(14, 10, downloadName, WHITE, BLACK);
		scr.print(3, 11, "Size     : " + String.format(Locale.US, "%,d", size) + " bytes", LGRAY, BLACK);
		scr.print(3, 12, "CPS      : " + cps + "  (14400 baud)", LGRAY, BLACK);
		scr.print(3, 13, "Elapsed  : " + mmss(fileFrames / 60), LGRAY, BLACK);

		// Progress bar with the percentage riding the thumb.
		int barW = 33;
		int fill = Math.round(downloadPct / 100f * barW);
		scr.put(2, 15, '[', LGRAY, BLACK);
		for (int i = 0; i < barW; i++) {
			scr.put(3 + i, 15, i < fill ? '█' : '░', i < fill ? LGREEN : DGRAY, BLACK);
		}
		scr.put(3 + barW, 15, ']', LGRAY, BLACK);
		scr.print(17, 16, Math.round(downloadPct) + "%", WHITE, BLACK);

		if (completeHold > 0) {
			if (blink()) center2(18, 2, 38, "CRC-32 OK - download complete!", YELLOW);
		} else {
			center2(18, 2, 38, "Streaming from node 2...", DGRAY);
		}
	}

	private void drawEq() {
		scr.box(41, 8, 78, 19, LMAGENTA, BLACK);
		scr.print(43, 8, " SysOp EQ ", LCYAN, BLACK);

		int bottom = 17, top = 9;
		int span = bottom - top + 1;
		for (int b = 0; b < NUM_BARS; b++) {
			int col = 43 + b * 3;
			float cells = bars[b] * span;
			int full = (int) cells;
			for (int i = 0; i < span; i++) {
				int row = bottom - i;
				float f = (float) i / span;
				int fg = f < 0.55f ? LGREEN : f < 0.8f ? YELLOW : LRED;
				char ch;
				if (i < full)                              ch = '█';
				else if (i == full && cells - full > 0.4f) ch = '▄';
				else                                       continue;
				scr.put(col, row, ch, fg, BLACK);
				scr.put(col + 1, row, ch, fg, BLACK);
			}
			int peakRow = bottom - clampI(Math.round(barPeak[b] * span), 0, span - 1);
			scr.put(col, peakRow, '─', WHITE, BLACK);
			scr.put(col + 1, peakRow, '─', WHITE, BLACK);
		}
		center2(18, 42, 77, "44100 Hz - 16-bit - hi-fi line", DGRAY);
	}

	/** Single-frame bursts of modem line garbage when the treble spikes. */
	private void drawLineNoise() {
		if (noiseFrames <= 0) return;
		String garbage = "#$%&@!~?*{}";
		for (int i = 0; i < 12; i++) {
			int col = rnd.nextInt(COLS);
			int row = 1 + rnd.nextInt(21);
			scr.put(col, row, garbage.charAt(rnd.nextInt(garbage.length())), 9 + rnd.nextInt(7), BLACK);
		}
	}

	private void drawDial() {
		scr.clear(' ', LGRAY, BLACK);
		int budget = dialChars;
		int row = 1;
		for (String line : DIAL_LINES) {
			if (budget <= 0) break;
			int take = Math.min(line.length(), budget);
			scr.print(1, row, line.substring(0, take), LGRAY, BLACK);
			budget -= line.length() + 4;
			row++;
		}
		if (blink()) scr.put(1, Math.min(ROWS - 1, row), '▌', LGRAY, BLACK);
	}

	private void drawNoCarrier() {
		scr.clear(' ', LGRAY, BLACK);
		scr.print(1, 8, "+++", LGRAY, BLACK);
		scr.print(1, 9, "ATH0", LGRAY, BLACK);
		scr.print(1, 10, "OK", LGRAY, BLACK);
		scr.print(1, 12, "NO CARRIER", WHITE, BLACK);
		if (idleFrames > 1500) {
			scr.print(1, 14, "(play another tune to redial)", DGRAY, BLACK);
		}
		if (blink()) scr.put(1, 16, '▌', LGRAY, BLACK);
	}

	// ---- Helpers ----

	private boolean blink() {
		return (frame / 16) % 2 == 0;
	}

	private void center(int row, String s, int fg) {
		scr.print((COLS - s.length()) / 2, row, s, fg, BLACK);
	}

	private void center2(int row, int c0, int c1, String s, int fg) {
		scr.print(c0 + (c1 - c0 + 1 - s.length()) / 2, row, s, fg, BLACK);
	}

	private static String mmss(int seconds) {
		return String.format("%02d:%02d", seconds / 60, seconds % 60);
	}

	private static int clampI(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clampF(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
