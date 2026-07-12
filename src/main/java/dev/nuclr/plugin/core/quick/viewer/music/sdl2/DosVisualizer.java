package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

import sdl2.AudioRingBuffer;

/**
 * "MS-DOS / Norton Commander" — a text-mode tribute visualizer, and a wink at
 * this very application's ancestry.
 * <p>
 * The panel becomes an 80x25 VGA text screen (16-colour palette, 8x16 cells,
 * box-drawing and shade blocks rendered exactly like a text-mode card would):
 * two blue Norton Commander panels, the left one a fake {@code C:\MUSIC}
 * directory listing — the current track shows up 8.3-mangled ({@code
 * AXELFO~1.MOD}) and tagged yellow, and the selection bar arrow-keys its way
 * down a row on every beat — the right one a spectrum analyzer built from
 * {@code ░▒▓█} characters with peak caps, over the classic function-key bar.
 * <p>
 * While the music plays, the
 * command line periodically types era-appropriate jokes and pops their
 * results as NC dialogs — {@code C:\>WIN} → {@code Bad command or file name},
 * the apocryphal 640K quote, the 32-bit/16-bit/8-bit/4-bit/2-bit/1-bit
 * classic, {@code UNINSTALL WINDOWS} → {@code Have a nice DOS}. Silence is
 * handled the way the era demands: a Blue Screen of Death, and eventually
 * {@code It's now safe to turn off your computer}.
 * <p>
 * Self-contained like its siblings; the glyph atlas, framebuffer and FFT
 * scratch are pre-allocated and the ring buffer snapshot is read on the EDT.
 */
final class DosVisualizer {

	// ---- Text mode (shared engine) ----
	private static final int COLS = TextModeScreen.COLS, ROWS = TextModeScreen.ROWS;
	private static final int SCREEN_W = TextModeScreen.WIDTH;
	private static final int SCREEN_H = TextModeScreen.HEIGHT;

	private static final int BLACK = 0, BLUE = 1, CYAN = 3, RED = 4, LGRAY = 7;
	private static final int LGREEN = 10, LCYAN = 11, LRED = 12, YELLOW = 14, WHITE = 15;

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

	private float bass, energy;
	private float bassAvg;
	private float autoGain = 8f;
	private int   framesSinceBeat = 999;

	// ---- Directory panel ----
	private static final String[][] FILES = {
		{ "..",       "<DIR>" },
		{ "WINDOWS",  "<DIR>" },
		{ "DOOM",     "<DIR>" },
		{ "MODS",     "<DIR>" },
		{ "",         ""      },   // slot 4: the current track, 8.3-mangled
		{ "AUTOEXEC", "BAT"   },
		{ "CONFIG",   "SYS"   },
		{ "HIMEM",    "SYS"   },
		{ "SMARTDRV", "EXE"   },
		{ "KEYGEN",   "NFO"   },
		{ "LLAMA",    "MOD"   },
		{ "NUKE_BG",  "BAT"   },
	};
	private static final int TRACK_SLOT = 4;
	private int selRow = TRACK_SLOT;

	// ---- Command-line jokes ----
	private static final String[][] JOKES = {
		{ "WIN", "Bad command or file name", "E" },
		{ "DEL C:\\WINDOWS /S /Q",
		  "637 files deleted. 640K of RAM freed.|System performance improved by 400%.", "I" },
		{ "MEM /FULL",
		  "Not enough memory to display memory.|\"640K ought to be enough for anybody\"|      -- B. Gates, 1981 (allegedly)", "E" },
		{ "TYPE BILL.TXT",
		  "Windows: a 32-bit shell for a 16-bit|extension to an 8-bit operating system|coded for a 4-bit CPU by a 2-bit|company that can't stand 1 bit of|competition.", "I" },
		{ "FORMAT A: /Q", "Insert MOD disk 2 in drive A:|and press any key when ready...", "I" },
		{ "UNINSTALL WINDOWS", "Uninstalling Windows... done.|Have a nice DOS.", "I" },
	};
	private static final int JOKE_NONE = 0, JOKE_TYPING = 1, JOKE_DIALOG = 2;
	private int jokePhase    = JOKE_NONE;
	private int jokeIdx      = 0;
	private int jokeTick     = 0;
	private int jokeCooldown = 700;

	// ---- State ----
	private String trackName83  = "INTRO~1.MOD";
	private String trackTitle   = "";
	private int    idleFrames   = 0;
	private int    frame        = 0;

	private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

	DosVisualizer() {
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

	/** New tune: mangle to 8.3 and tag it in the listing — straight to the panels. */
	void setTrackTitle(String title) {
		trackTitle = title == null ? "" : title;
		trackName83 = dosName(trackTitle);
		jokePhase = JOKE_NONE;
		jokeCooldown = 700;
		selRow = TRACK_SLOT;
	}

	private static String dosName(String title) {
		return TextModeScreen.dosName(title);
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
		advanceState(hasAudio);

		if (idleFrames > 1500)      drawSafeToTurnOff();
		else if (idleFrames > 300)  drawBsod();
		else                        drawNorton();

		scr.rasterize();

		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_OFF);
		g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

		// Fullscreen DOS: 8:5 text page centred on black.
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
		bass   += (nb - bass) * (nb > bass ? 0.5f : 0.12f);
		energy += (sum / NUM_BARS - energy) * 0.15f;

		bassAvg += (bass - bassAvg) * 0.02f;
		framesSinceBeat++;
		if (framesSinceBeat > 14 && bass > 0.14f && bass > bassAvg * 1.45f + 0.03f) {
			framesSinceBeat = 0;
			selRow = (selRow + 1) % FILES.length; // arrow-keying to the rhythm
		}
	}

	private void advanceState(boolean hasAudio) {
		for (int b = 0; b < NUM_BARS; b++) barPeak[b] = Math.max(bars[b], barPeak[b] - 0.005f);

		switch (jokePhase) {
			case JOKE_NONE -> {
				if (hasAudio && --jokeCooldown <= 0) {
					jokePhase = JOKE_TYPING;
					jokeTick = 0;
				}
			}
			case JOKE_TYPING -> {
				if (++jokeTick / 2 >= JOKES[jokeIdx][0].length()) {
					jokePhase = JOKE_DIALOG;
					jokeTick = 0;
				}
			}
			case JOKE_DIALOG -> {
				if (++jokeTick > 260) {
					jokePhase = JOKE_NONE;
					jokeCooldown = 900 + (jokeIdx * 137) % 500;
					jokeIdx = (jokeIdx + 1) % JOKES.length;
				}
			}
			default -> { }
		}
	}

	// ---- Screens ----

	private void drawNorton() {
		clear(' ', LGRAY, BLACK);

		panel(0, 39, " C:\\MUSIC ", true);
		panel(40, 79, " Spectrum ", false);

		// Left: directory listing. The track is tagged yellow, NC-style.
		print(2, 1, "Name", YELLOW, BLUE);
		for (int i = 0; i < FILES.length; i++) {
			int row = 2 + i;
			boolean isTrack = i == TRACK_SLOT;
			String name = isTrack ? fmt83(trackName83) : pad(FILES[i][0], 12) + FILES[i][1];
			int fg = isTrack ? YELLOW : LCYAN;
			int bg = BLUE;
			if (i == selRow) { bg = CYAN; fg = isTrack ? YELLOW : BLACK; }
			fillRow(row, 1, 38, ' ', fg, bg);
			print(2, row, pad(name, 36), fg, bg);
		}
		fillRow(19, 1, 38, '─', LCYAN, BLUE);
		String status = trackTitle.isBlank() ? "No tune loaded" : trackTitle;
		if (status.length() > 36) status = status.substring(0, 33) + "...";
		print(2, 20, status, LCYAN, BLUE);

		// Right: the ░▒▓█ spectrum analyzer.
		drawTextBars();
		fillRow(19, 41, 78, '─', LCYAN, BLUE);
		print(43, 20, "44,100 Hz  16-bit  Paula who?", LCYAN, BLUE);

		// Clock on the right panel's top border, like NC's.
		print(71, 0, " " + LocalTime.now().format(CLOCK) + " ", BLACK, CYAN);

		// Command line + function keys.
		String cmd = "";
		if (jokePhase == JOKE_TYPING) cmd = JOKES[jokeIdx][0].substring(0, Math.min(jokeTick / 2, JOKES[jokeIdx][0].length()));
		if (jokePhase == JOKE_DIALOG) cmd = JOKES[jokeIdx][0];
		print(0, 22, "C:\\MUSIC>" + cmd, LGRAY, BLACK);
		if (blink()) print(9 + cmd.length(), 22, "▌", LGRAY, BLACK);
		drawFKeys();

		if (jokePhase == JOKE_DIALOG) {
			drawDialog(JOKES[jokeIdx][1].split("\\|"), "E".equals(JOKES[jokeIdx][2]));
		}
	}

	private String fmt83(String n83) {
		int dot = n83.lastIndexOf('.');
		if (dot < 0) return pad(n83, 12);
		return pad(n83.substring(0, dot), 12) + n83.substring(dot + 1);
	}

	private void drawTextBars() {
		int bottom = 18, top = 2;
		int span = bottom - top + 1;             // 17 rows of headroom
		for (int b = 0; b < NUM_BARS; b++) {
			int col = 42 + b * 3;
			float cells = bars[b] * span;
			int full = (int) cells;
			for (int i = 0; i < span; i++) {
				int row = bottom - i;
				float f = (float) i / span;
				int fg = f < 0.55f ? LGREEN : f < 0.8f ? YELLOW : LRED;
				char ch;
				if (i < full)                          ch = '█';
				else if (i == full && cells - full > 0.4f) ch = '▄';
				else                                   continue;
				put(col, row, ch, fg, BLUE);
				put(col + 1, row, ch, fg, BLUE);
			}
			int peakRow = bottom - clampI(Math.round(barPeak[b] * span), 0, span - 1);
			put(col, peakRow, '─', WHITE, BLUE);
			put(col + 1, peakRow, '─', WHITE, BLUE);
		}
	}

	private void drawFKeys() {
		String[] labels = { "Help", "Menu", "View", "Edit", "Copy", "RenMov", "Mkdir", "Delete", "PullDn", "Quit" };
		int col = 0;
		for (int i = 0; i < labels.length; i++) {
			String num = Integer.toString(i + 1);
			print(col, 24, num, LGRAY, BLACK);
			col += num.length();
			print(col, 24, pad(labels[i], 6), BLACK, CYAN);
			col += 6;
			col += (i == labels.length - 1) ? 0 : 1;
		}
	}

	private void drawDialog(String[] lines, boolean error) {
		int wMax = 12;
		for (String l : lines) wMax = Math.max(wMax, l.length());
		int dw = Math.min(COLS - 6, wMax + 6);
		int dh = lines.length + 4;
		int c0 = (COLS - dw) / 2, r0 = (ROWS - dh) / 2 - 1;
		int bg = error ? RED : CYAN;
		int fg = error ? WHITE : BLACK;

		// Drop shadow, then the box.
		for (int r = r0 + 1; r < r0 + dh + 1; r++) fillRow(r, c0 + 2, c0 + dw + 1, ' ', LGRAY, BLACK);
		for (int r = r0; r < r0 + dh; r++)         fillRow(r, c0, c0 + dw - 1, ' ', fg, bg);
		box(c0, r0, c0 + dw - 1, r0 + dh - 1, fg, bg);
		for (int i = 0; i < lines.length; i++) {
			print(c0 + (dw - lines[i].length()) / 2, r0 + 1 + i, lines[i], fg, bg);
		}
		String ok = blink() ? "[ Ok ]" : "  Ok  ";
		print(c0 + (dw - 6) / 2, r0 + dh - 2, ok, bg, fg); // inverse video button
	}

	private void drawBsod() {
		clear(' ', WHITE, BLUE);
		print((COLS - 9) / 2, 6, " Windows ", BLUE, LGRAY);
		String[] lines = {
			"An exception 0E has occurred at 0028:C0FFEE42 in VxD SILENCE(01) +",
			"00000BEF. The current track has ended and all audio has been",
			"unloaded from memory.",
			"",
			"*  Press any key to terminate the silence.",
			"*  Press CTRL+ALT+DEL to restart your computer. You will",
			"   lose any unsaved vibes.",
		};
		for (int i = 0; i < lines.length; i++) {
			print(6, 9 + i, lines[i], WHITE, BLUE);
		}
		String prompt = "Press any key to continue";
		int pc = (COLS - prompt.length()) / 2;
		print(pc, 18, prompt, WHITE, BLUE);
		if (blink()) print(pc + prompt.length() + 1, 18, "_", WHITE, BLUE);
	}

	private void drawSafeToTurnOff() {
		clear(' ', YELLOW, BLACK);
		String msg = "It's now safe to turn off your computer.";
		print((COLS - msg.length()) / 2, 12, msg, YELLOW, BLACK);
	}

	// ---- Grid helpers (delegating to the shared text-mode engine) ----

	private boolean blink() {
		return (frame / 16) % 2 == 0;
	}

	private void clear(char ch, int fg, int bg) {
		scr.clear(ch, fg, bg);
	}

	private void put(int col, int row, char ch, int fg, int bg) {
		scr.put(col, row, ch, fg, bg);
	}

	private void print(int col, int row, String s, int fg, int bg) {
		scr.print(col, row, s, fg, bg);
	}

	private void fillRow(int row, int c0, int c1, char ch, int fg, int bg) {
		scr.fillRow(row, c0, c1, ch, fg, bg);
	}

	private void box(int c0, int r0, int c1, int r1, int fg, int bg) {
		scr.box(c0, r0, c1, r1, fg, bg);
	}

	/** One NC panel: blue field, double border, centred header on the top edge. */
	private void panel(int c0, int c1, String header, boolean active) {
		for (int r = 0; r <= 21; r++) fillRow(r, c0, c1, ' ', LCYAN, BLUE);
		box(c0, 0, c1, 21, LCYAN, BLUE);
		int hc = c0 + (c1 - c0 + 1 - header.length()) / 2;
		print(hc, 0, header, active ? BLACK : LCYAN, active ? CYAN : BLUE);
	}

	private static String pad(String s, int len) {
		return TextModeScreen.pad(s, len);
	}

	// ---- Helpers ----

	private static int clampI(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static float clampF(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
