package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

import javax.swing.JPanel;
import javax.swing.Timer;

import sdl2.AudioRingBuffer;

/**
 * Smoothed mirrored waveform visualizer panel.
 * <p>
 * Renders a calm "glass ribbon" waveform with a soft background gradient,
 * subtle guide lines, and restrained edge highlights.
 */
public class WaveformPanel extends JPanel {

	// Background palette
	private static final Color BG_TOP = new Color(0x0D, 0x18, 0x24);
	private static final Color BG_BOTTOM = new Color(0x08, 0x10, 0x1B);

	// Ribbon colors
	private static final Color RIBBON_START = new Color(0x6E, 0xD7, 0xD0, 210);
	private static final Color RIBBON_MID = new Color(0x57, 0x8D, 0xE0, 190);
	private static final Color RIBBON_END = new Color(0x89, 0x6A, 0xD9, 210);
	private static final Color EDGE = new Color(0xC9, 0xF4, 0xF0, 170);
	private static final Color CENTER_LINE = new Color(0xCA, 0xDB, 0xEC, 50);
	private static final Color GRID = new Color(0xB0, 0xC5, 0xD8, 20);
	private static final Color VIGNETTE = new Color(0, 0, 0, 70);

	private static final float[] RIBBON_FRACTIONS = {0f, 0.5f, 1f};
	private static final Color[] RIBBON_COLORS = {RIBBON_START, RIBBON_MID, RIBBON_END};

	private static final BasicStroke EDGE_GLOW = new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke EDGE_LINE = new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke GRID_STROKE = new BasicStroke(1f);
	private static final BasicStroke CENTER_STROKE = new BasicStroke(1.1f);

	// Signal processing constants
	private static final int NUM_POINTS = 200;
	private static final int SNAPSHOT_SAMPLES = 22050; // ~500ms at 44.1kHz
	private static final int SMOOTH_PASSES = 3;
	private static final float SMOOTH_ALPHA = 0.25f;
	private static final float COMPRESSION_EXP = 0.55f;
	private static final float ATTACK = 0.45f;
	private static final float DECAY = 0.12f;

	// Audio data source
	private AudioRingBuffer ringBuffer;

	// Pre-allocated processing buffers
	private final float[] snapshotBuf = new float[SNAPSHOT_SAMPLES];
	private final float[] rawAmps = new float[NUM_POINTS];
	private final float[] smoothBuf = new float[NUM_POINTS];
	private final float[] displayAmps = new float[NUM_POINTS];

	// Pre-allocated path-building arrays
	private final float[] topX = new float[NUM_POINTS];
	private final float[] topY = new float[NUM_POINTS];
	private final float[] botY = new float[NUM_POINTS];
	private final float[] revX = new float[NUM_POINTS];
	private final float[] revY = new float[NUM_POINTS];

	// Reusable Path2D objects
	private final Path2D.Float topPath = new Path2D.Float();
	private final Path2D.Float botPath = new Path2D.Float();
	private final Path2D.Float fillPath = new Path2D.Float();

	// Cached paints
	private LinearGradientPaint bgPaint;
	private LinearGradientPaint ribbonPaint;
	private GradientPaint edgeGlowPaint;
	private int cachedWidth = -1;
	private int cachedHeight = -1;

	// Animation timer
	private final Timer animTimer;

	public WaveformPanel() {
		setOpaque(true);
		setBackground(BG_BOTTOM);
		animTimer = new Timer(16, e -> repaint()); // ~60 fps
		animTimer.start();
	}

	public void setRingBuffer(AudioRingBuffer buf) {
		this.ringBuffer = buf;
	}

	public void stop() {
		animTimer.stop();
	}

	public void start() {
		if (!animTimer.isRunning()) animTimer.start();
	}

	@Override
	protected void paintComponent(Graphics g) {
		int w = getWidth();
		int h = getHeight();

		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

			// Background
			updateCachedPaints(w, h);
			g2.setPaint(bgPaint);
			g2.fillRect(0, 0, w, h);
			drawGuides(g2, w, h);

			if (ringBuffer == null || w < 10 || h < 10) return;

			// 1. Snapshot audio data from ring buffer
			int samples = ringBuffer.snapshot(snapshotBuf, SNAPSHOT_SAMPLES);
			if (samples < NUM_POINTS) return;

			// 2. Downsample to RMS amplitudes
			downsampleRMS(snapshotBuf, samples);

			// 3. Spatial smoothing (bidirectional EMA, multiple passes)
			spatialSmooth();

			// 4. Compression (lift quiet parts)
			for (int i = 0; i < NUM_POINTS; i++) {
				smoothBuf[i] = (float) Math.pow(smoothBuf[i], COMPRESSION_EXP);
			}

			// 5. Temporal smoothing (fast attack, slow decay)
			for (int i = 0; i < NUM_POINTS; i++) {
				float target = smoothBuf[i];
				if (target > displayAmps[i]) {
					displayAmps[i] += (target - displayAmps[i]) * ATTACK;
				} else {
					displayAmps[i] += (target - displayAmps[i]) * DECAY;
				}
			}

			// 6. Build all three paths (top contour, bottom contour, filled shape)
			float centerY = h / 2f;
			float halfH = h * 0.40f;
			buildPaths(w, centerY, halfH);

			// 7. Fill the mirrored waveform ribbon
			g2.setPaint(ribbonPaint);
			g2.fill(fillPath);

			// 8. Edge highlight and soft glow
			g2.setPaint(edgeGlowPaint);
			g2.setStroke(EDGE_GLOW);
			g2.draw(topPath);
			g2.draw(botPath);

			g2.setPaint(EDGE);
			g2.setStroke(EDGE_LINE);
			g2.draw(topPath);
			g2.draw(botPath);

			// 9. Soft vignette
			g2.setColor(VIGNETTE);
			g2.fillRect(0, 0, w, 2);
			g2.fillRect(0, h - 2, w, 2);

		} finally {
			g2.dispose();
		}
	}

	private void updateCachedPaints(int w, int h) {
		if (w == cachedWidth && h == cachedHeight) {
			return;
		}
		cachedWidth = w;
		cachedHeight = h;
		bgPaint = new LinearGradientPaint(0, 0, 0, h,
			new float[]{0f, 1f},
			new Color[]{BG_TOP, BG_BOTTOM});
		ribbonPaint = new LinearGradientPaint(0, 0, w, 0, RIBBON_FRACTIONS, RIBBON_COLORS);
		edgeGlowPaint = new GradientPaint(0, 0, new Color(0xA9, 0xF0, 0xE8, 70), w, 0, new Color(0xB8, 0xA8, 0xEE, 65));
	}

	private static void drawGuides(Graphics2D g2, int w, int h) {
		g2.setStroke(GRID_STROKE);
		g2.setColor(GRID);
		int lines = 6;
		for (int i = 1; i < lines; i++) {
			int y = i * h / lines;
			g2.drawLine(0, y, w, y);
		}
		g2.setStroke(CENTER_STROKE);
		g2.setColor(CENTER_LINE);
		g2.drawLine(0, h / 2, w, h / 2);
	}

	private void downsampleRMS(float[] src, int srcLen) {
		float binSize = (float) srcLen / NUM_POINTS;
		for (int i = 0; i < NUM_POINTS; i++) {
			int start = (int) (i * binSize);
			int end = Math.min((int) ((i + 1) * binSize), srcLen);
			float sum = 0;
			for (int j = start; j < end; j++) {
				sum += src[j] * src[j];
			}
			int count = end - start;
			rawAmps[i] = count > 0 ? (float) Math.sqrt(sum / count) : 0;
		}
	}

	private void spatialSmooth() {
		System.arraycopy(rawAmps, 0, smoothBuf, 0, NUM_POINTS);
		for (int pass = 0; pass < SMOOTH_PASSES; pass++) {
			// Forward
			for (int i = 1; i < NUM_POINTS; i++) {
				smoothBuf[i] = SMOOTH_ALPHA * smoothBuf[i] + (1 - SMOOTH_ALPHA) * smoothBuf[i - 1];
			}
			// Backward
			for (int i = NUM_POINTS - 2; i >= 0; i--) {
				smoothBuf[i] = SMOOTH_ALPHA * smoothBuf[i] + (1 - SMOOTH_ALPHA) * smoothBuf[i + 1];
			}
		}
	}

	private void buildPaths(float w, float cy, float hh) {
		float dx = w / (NUM_POINTS - 1);

		// Compute top and bottom contour Y-coordinates
		for (int i = 0; i < NUM_POINTS; i++) {
			float x = i * dx;
			float a = displayAmps[i];
			topX[i] = x;
			topY[i] = cy - a * hh;
			botY[i] = cy + a * hh;
		}

		// Top contour path (open, left to right)
		topPath.reset();
		catmullRomPath(topPath, topX, topY, NUM_POINTS, true);

		// Bottom contour path (open, left to right)
		botPath.reset();
		catmullRomPath(botPath, topX, botY, NUM_POINTS, true);

		// Filled shape: top left-to-right, then bottom right-to-left
		fillPath.reset();
		catmullRomPath(fillPath, topX, topY, NUM_POINTS, true);

		// Reversed bottom for right-to-left traversal
		for (int i = 0; i < NUM_POINTS; i++) {
			revX[i] = (NUM_POINTS - 1 - i) * dx;
			revY[i] = cy + displayAmps[NUM_POINTS - 1 - i] * hh;
		}
		catmullRomPath(fillPath, revX, revY, NUM_POINTS, false);
		fillPath.closePath();
	}

	/**
	 * Append a Catmull-Rom spline (converted to cubic Bezier segments)
	 * through the given points onto the path.
	 *
	 * @param moveTo true to moveTo the first point; false to lineTo it
	 *               (for appending to an existing path)
	 */
	private static void catmullRomPath(Path2D.Float path, float[] px, float[] py,
	                                   int count, boolean moveTo) {
		if (count < 2) return;

		if (moveTo) {
			path.moveTo(px[0], py[0]);
		} else {
			path.lineTo(px[0], py[0]);
		}

		for (int i = 0; i < count - 1; i++) {
			float x0 = px[i], y0 = py[i];
			float x1 = px[i + 1], y1 = py[i + 1];

			// Neighbor points for tangent estimation (clamp at edges)
			float xPrev = (i > 0) ? px[i - 1] : x0 - (x1 - x0);
			float yPrev = (i > 0) ? py[i - 1] : y0;
			float xNext = (i + 2 < count) ? px[i + 2] : x1 + (x1 - x0);
			float yNext = (i + 2 < count) ? py[i + 2] : y1;

			// Catmull-Rom to cubic Bezier control points
			float cp1x = x0 + (x1 - xPrev) / 6f;
			float cp1y = y0 + (y1 - yPrev) / 6f;
			float cp2x = x1 - (xNext - x0) / 6f;
			float cp2y = y1 - (yNext - y0) / 6f;

			path.curveTo(cp1x, cp1y, cp2x, cp2y, x1, y1);
		}
	}
}
