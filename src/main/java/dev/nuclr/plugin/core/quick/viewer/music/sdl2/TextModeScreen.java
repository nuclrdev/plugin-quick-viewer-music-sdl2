package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * A software 80x25 VGA text-mode card, shared by the text-era visualizers
 * (MS-DOS/Norton, BBS/ANSI).
 * <p>
 * Holds a cell grid (char | fg | bg packed per int), a pre-rendered glyph
 * atlas for ASCII 32..126 and synthesized CP437 box/shade glyphs, and
 * rasterizes the grid straight into an INT_RGB framebuffer through its
 * {@code DataBufferInt} — no per-frame allocations, no AWT text calls after
 * construction.
 */
final class TextModeScreen {

	static final int COLS = 80, ROWS = 25;
	static final int CW = 8, CH = 16;
	static final int WIDTH = COLS * CW, HEIGHT = ROWS * CH;

	/** The VGA 16-colour text palette. */
	static final int[] PAL = {
		0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xAA5500, 0xAAAAAA,
		0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
	};

	private final int[] grid = new int[COLS * ROWS];
	private final BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
	private final int[] px = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
	private final byte[][] atlas = new byte[95][CH]; // ASCII 32..126, one bit-row byte per scanline

	TextModeScreen() {
		buildAtlas();
	}

	/** Rasterize ASCII 32..126 once into per-glyph bitmasks. */
	private void buildAtlas() {
		BufferedImage tmp = new BufferedImage(95 * CW, CH, BufferedImage.TYPE_INT_RGB);
		Graphics2D g = tmp.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
			g.setColor(Color.BLACK);
			g.fillRect(0, 0, tmp.getWidth(), CH);
			g.setColor(Color.WHITE);
			g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
			FontMetrics fm = g.getFontMetrics();
			int base = (CH + fm.getAscent() - fm.getDescent()) / 2;
			for (int i = 0; i < 95; i++) {
				char ch = (char) (32 + i);
				int pad = Math.max(0, (CW - fm.charWidth(ch)) / 2);
				g.drawString(String.valueOf(ch), i * CW + pad, base);
			}
		} finally {
			g.dispose();
		}
		for (int i = 0; i < 95; i++) {
			for (int y = 0; y < CH; y++) {
				int bits = 0;
				for (int x = 0; x < CW; x++) {
					if ((tmp.getRGB(i * CW + x, y) & 0xFF) > 100) bits |= 1 << x;
				}
				atlas[i][y] = (byte) bits;
			}
		}
	}

	BufferedImage image() {
		return image;
	}

	// ---- Grid ops ----

	private static int enc(char ch, int fg, int bg) {
		return ch | fg << 16 | bg << 20;
	}

	void clear(char ch, int fg, int bg) {
		java.util.Arrays.fill(grid, enc(ch, fg, bg));
	}

	void put(int col, int row, char ch, int fg, int bg) {
		if (col < 0 || col >= COLS || row < 0 || row >= ROWS) return;
		grid[row * COLS + col] = enc(ch, fg, bg);
	}

	void print(int col, int row, String s, int fg, int bg) {
		for (int i = 0; i < s.length(); i++) {
			put(col + i, row, s.charAt(i), fg, bg);
		}
	}

	void fillRow(int row, int c0, int c1, char ch, int fg, int bg) {
		for (int c = c0; c <= c1; c++) put(c, row, ch, fg, bg);
	}

	/** Double-line box between the given corners (inclusive). */
	void box(int c0, int r0, int c1, int r1, int fg, int bg) {
		put(c0, r0, '╔', fg, bg);
		put(c1, r0, '╗', fg, bg);
		put(c0, r1, '╚', fg, bg);
		put(c1, r1, '╝', fg, bg);
		for (int c = c0 + 1; c < c1; c++) { put(c, r0, '═', fg, bg); put(c, r1, '═', fg, bg); }
		for (int r = r0 + 1; r < r1; r++) { put(c0, r, '║', fg, bg); put(c1, r, '║', fg, bg); }
	}

	/** Raw glyph scanline bits (LSB = leftmost) — lets callers build banner/big-text effects. */
	int glyphRow(char ch, int y) {
		if (ch >= 32 && ch < 127 && y >= 0 && y < CH) return atlas[ch - 32][y] & 0xFF;
		return 0;
	}

	// ---- Rasterizer ----

	void rasterize() {
		for (int row = 0; row < ROWS; row++) {
			for (int col = 0; col < COLS; col++) {
				int v = grid[row * COLS + col];
				renderCell(col * CW, row * CH, (char) (v & 0xFFFF), PAL[(v >> 16) & 0xF], PAL[(v >> 20) & 0xF]);
			}
		}
	}

	private void renderCell(int x0, int y0, char ch, int fg, int bg) {
		for (int y = 0; y < CH; y++) {
			int rowBase = (y0 + y) * WIDTH + x0;
			int bits = cellRowBits(ch, y);
			for (int x = 0; x < CW; x++) {
				px[rowBase + x] = (bits >> x & 1) != 0 ? fg : bg;
			}
		}
	}

	/** Bitmask (LSB = leftmost pixel) of one glyph scanline; blocks and box chars are synthesized. */
	private int cellRowBits(char ch, int y) {
		switch (ch) {
			case ' ': return 0;
			case '█': return 0xFF;
			case '▄': return y >= CH / 2 ? 0xFF : 0;
			case '▀': return y < CH / 2 ? 0xFF : 0;
			case '▌': return 0x0F;
			case '░': return (y % 2) == 0 ? 0b01000100 : 0b00010001;
			case '▒': return (y % 2) == 0 ? 0b10101010 : 0b01010101;
			case '▓': return (y % 2) == 0 ? 0b10111011 : 0b11101110;
			case '─': return y == CH / 2 ? 0xFF : 0;
			case '│': return 0b00010000;
			case '═': return (y == CH / 2 - 1 || y == CH / 2 + 1) ? 0xFF : 0;
			case '║': return 0b00101000;
			// Double-line corners: verticals at x=3/x=5 (matching ║), horizontals at
			// y=7/y=9 (matching ═); the outer line runs to the corner, the inner stops short.
			case '╔':
				if (y == CH / 2 - 1) return 0xF8;        // outer top: x=3..7
				if (y == CH / 2 + 1) return 0xE8;        // inner top x=5..7 + outer vertical x=3
				if (y > CH / 2 + 1)  return 0x28;        // both verticals
				if (y > CH / 2 - 1)  return 0x08;        // outer vertical only
				return 0;
			case '╗':
				if (y == CH / 2 - 1) return 0x3F;        // outer top: x=0..5
				if (y == CH / 2 + 1) return 0x2F;        // inner top x=0..3 + outer vertical x=5
				if (y > CH / 2 + 1)  return 0x28;
				if (y > CH / 2 - 1)  return 0x20;
				return 0;
			case '╚':
				if (y == CH / 2 + 1) return 0xF8;        // outer bottom: x=3..7
				if (y == CH / 2 - 1) return 0xE8;        // inner bottom x=5..7 + outer vertical x=3
				if (y < CH / 2 - 1)  return 0x28;
				if (y < CH / 2 + 1)  return 0x08;
				return 0;
			case '╝':
				if (y == CH / 2 + 1) return 0x3F;        // outer bottom: x=0..5
				if (y == CH / 2 - 1) return 0x2F;        // inner bottom x=0..3 + outer vertical x=5
				if (y < CH / 2 - 1)  return 0x28;
				if (y < CH / 2 + 1)  return 0x20;
				return 0;
			default:
				if (ch >= 32 && ch < 127) return atlas[ch - 32][y] & 0xFF;
				return 0;
		}
	}

	// ---- Text-era utilities ----

	static String pad(String s, int len) {
		if (s.length() >= len) return s.substring(0, len);
		StringBuilder sb = new StringBuilder(s);
		while (sb.length() < len) sb.append(' ');
		return sb.toString();
	}

	/** Mangle a modern file name into a DOS 8.3 name, tilde and all. */
	static String dosName(String title) {
		String name = title, ext = "";
		int dot = title.lastIndexOf('.');
		if (dot > 0) {
			name = title.substring(0, dot);
			ext = title.substring(dot + 1);
		}
		name = name.toUpperCase().replaceAll("[^A-Z0-9]", "");
		ext  = ext.toUpperCase().replaceAll("[^A-Z0-9]", "");
		if (name.isEmpty()) name = "TRACK";
		if (name.length() > 8) name = name.substring(0, 6) + "~1";
		if (ext.length() > 3) ext = ext.substring(0, 3);
		return ext.isEmpty() ? name : name + "." + ext;
	}
}
