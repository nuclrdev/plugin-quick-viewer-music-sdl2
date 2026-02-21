package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

import javax.swing.JPanel;
import javax.swing.Timer;

import sdl2.AudioRingBuffer;

/**
 * Neon mirrored waveform visualizer panel.
 * <p>
 * Renders a smooth, Catmull-Rom interpolated amplitude envelope mirrored
 * around a horizontal center line, filled with a cyan→blue→purple→pink
 * gradient and surrounded by a multi-pass glow.
 * <p>
 * All buffers and paths are pre-allocated; no per-frame object creation
 * except the Graphics2D copy (required by Swing contract).
 */
public class WaveformPanel extends JPanel {

	// --- Background ---
	private static final Color BG = new Color(0x1A, 0x1A, 0x1E);

	// --- Gradient: cyan → blue → purple → pink ---
	private static final float[] GRAD_FRACTIONS = {0f, 0.33f, 0.66f, 1f};
	private static final Color[] GRAD_COLORS = {
			new Color(0x00, 0xE5, 0xFF),  // cyan
			new Color(0x29, 0x79, 0xFF),  // blue
			new Color(0xAA, 0x00, 0xFF),  // purple
			new Color(0xFF, 0x14, 0x93),  // pink
	};

	// --- Glow layers (outer → inner) ---
	private static final BasicStroke[] GLOW_STROKES = {
			new BasicStroke(14f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND),
			new BasicStroke(8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND),
			new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND),
			new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND),
	};
	private static final AlphaComposite[] GLOW_ALPHAS = {
			AlphaComposite.SrcOver.derive(0.06f),
			AlphaComposite.SrcOver.derive(0.12f),
			AlphaComposite.SrcOver.derive(0.22f),
			AlphaComposite.SrcOver.derive(0.35f),
	};
	private static final AlphaComposite FILL_ALPHA = AlphaComposite.SrcOver.derive(0.85f);

	// --- Signal processing constants ---
	private static final int NUM_POINTS = 200;
	private static final int SNAPSHOT_SAMPLES = 22050; // ~500ms at 44.1kHz
	private static final int SMOOTH_PASSES = 3;
	private static final float SMOOTH_ALPHA = 0.25f;
	private static final float COMPRESSION_EXP = 0.55f;
	private static final float ATTACK = 0.45f;
	private static final float DECAY = 0.12f;

	// --- Audio data source ---
	private AudioRingBuffer ringBuffer;

	// --- Pre-allocated processing buffers ---
	private final float[] snapshotBuf = new float[SNAPSHOT_SAMPLES];
	private final float[] rawAmps = new float[NUM_POINTS];
	private final float[] smoothBuf = new float[NUM_POINTS];
	private final float[] displayAmps = new float[NUM_POINTS];

	// --- Pre-allocated path-building arrays ---
	private final float[] topX = new float[NUM_POINTS];
	private final float[] topY = new float[NUM_POINTS];
	private final float[] botY = new float[NUM_POINTS];
	private final float[] revX = new float[NUM_POINTS];
	private final float[] revY = new float[NUM_POINTS];

	// --- Reusable Path2D objects ---
	private final Path2D.Float topPath = new Path2D.Float();
	private final Path2D.Float botPath = new Path2D.Float();
	private final Path2D.Float fillPath = new Path2D.Float();

	// --- Cached gradient (recreated when panel width changes) ---
	private LinearGradientPaint gradientPaint;
	private int cachedWidth = -1;

	// --- Animation timer ---
	private final Timer animTimer;

	public WaveformPanel() {
		setOpaque(true);
		setBackground(BG);
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

	// =========================================================================
	// Rendering
	// =========================================================================

	@Override
	protected void paintComponent(Graphics g) {
		int w = getWidth();
		int h = getHeight();

		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

			// Dark background
			g2.setColor(BG);
			g2.fillRect(0, 0, w, h);

			if (ringBuffer == null || w < 10 || h < 10) return;

			// Rebuild gradient when width changes
			if (w != cachedWidth) {
				cachedWidth = w;
				gradientPaint = new LinearGradientPaint(0, 0, w, 0, GRAD_FRACTIONS, GRAD_COLORS);
			}

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
			float halfH = h * 0.42f;
			buildPaths(w, centerY, halfH);

			// 7. Draw glow layers on contour edges
			for (int pass = 0; pass < GLOW_STROKES.length; pass++) {
				g2.setStroke(GLOW_STROKES[pass]);
				g2.setComposite(GLOW_ALPHAS[pass]);
				g2.setPaint(gradientPaint);
				g2.draw(topPath);
				g2.draw(botPath);
			}

			// 8. Fill the mirrored waveform body
			g2.setComposite(FILL_ALPHA);
			g2.setPaint(gradientPaint);
			g2.fill(fillPath);

		} finally {
			g2.dispose();
		}
	}

	// =========================================================================
	// Signal processing (all operate on pre-allocated arrays, zero allocation)
	// =========================================================================

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

	// =========================================================================
	// Path building: Catmull-Rom spline interpolation
	// =========================================================================

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

		// Top contour path (open, left → right) — used for glow
		topPath.reset();
		catmullRomPath(topPath, topX, topY, NUM_POINTS, true);

		// Bottom contour path (open, left → right) — used for glow
		botPath.reset();
		catmullRomPath(botPath, topX, botY, NUM_POINTS, true);

		// Filled shape: top L→R, then bottom R→L (reversed)
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

			// Catmull-Rom → cubic Bezier control points
			float cp1x = x0 + (x1 - xPrev) / 6f;
			float cp1y = y0 + (y1 - yPrev) / 6f;
			float cp2x = x1 - (xNext - x0) / 6f;
			float cp2y = y1 - (yNext - y0) / 6f;

			path.curveTo(cp1x, cp1y, cp2x, cp2y, x1, y1);
		}
	}
}
