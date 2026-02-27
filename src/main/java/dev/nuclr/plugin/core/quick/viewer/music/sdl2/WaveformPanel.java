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
 * Renders a minimalist layered-ribbon waveform with soft depth bands
 * and a clean center trace.
 */
public class WaveformPanel extends JPanel {

	// Background palette
	private static final Color BG_TOP = new Color(0x11, 0x14, 0x1F);
	private static final Color BG_BOTTOM = new Color(0x08, 0x0B, 0x12);

	// Ribbon colors
	private static final Color RIBBON_START = new Color(0xF6, 0x8D, 0x77, 160);
	private static final Color RIBBON_MID = new Color(0xE8, 0x6A, 0x9A, 150);
	private static final Color RIBBON_END = new Color(0x9B, 0x77, 0xE8, 160);
	private static final Color LAYER_2 = new Color(0xE7, 0x73, 0x9F, 110);
	private static final Color LAYER_3 = new Color(0xB8, 0x86, 0xEA, 80);
	private static final Color EDGE = new Color(0xFF, 0xE2, 0xD8, 185);
	private static final Color CENTER_LINE = new Color(0xD8, 0xE1, 0xF0, 34);
	private static final Color VIGNETTE = new Color(0, 0, 0, 70);

	private static final float[] RIBBON_FRACTIONS = {0f, 0.5f, 1f};
	private static final Color[] RIBBON_COLORS = {RIBBON_START, RIBBON_MID, RIBBON_END};

	private static final BasicStroke EDGE_GLOW = new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke EDGE_LINE = new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke CENTER_STROKE = new BasicStroke(1f);

	// Signal processing constants
	private static final int NUM_POINTS = 200;
	private static final int SNAPSHOT_SAMPLES = 22050; // ~500ms at 44.1kHz
	private static final int SMOOTH_PASSES = 3;
	private static final float SMOOTH_ALPHA = 0.25f;
	private static final float COMPRESSION_EXP = 0.55f;
	private static final float ATTACK = 0.45f;
	private static final float DECAY = 0.12f;
	private static final float BEAT_AVG_ALPHA = 0.08f;
	private static final float BEAT_PULSE_DECAY = 0.90f;
	private static final float BEAT_FLASH_DECAY = 0.84f;
	private static final int BEAT_COOLDOWN_FRAMES = 8;
	private static final Color BEAT_TOP = new Color(0x2D, 0x1F, 0x3F);
	private static final Color BEAT_BOTTOM = new Color(0x1C, 0x15, 0x2A);
	private static final Color BEAT_FLASH_A = new Color(0xD5, 0x4B, 0x87);
	private static final Color BEAT_FLASH_B = new Color(0x6E, 0x45, 0xE4);

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
	private final Path2D.Float fillPath2 = new Path2D.Float();
	private final Path2D.Float fillPath3 = new Path2D.Float();

	// Cached paints
	private LinearGradientPaint bgPaint;
	private LinearGradientPaint ribbonPaint;
	private GradientPaint edgeGlowPaint;
	private int cachedWidth = -1;
	private int cachedHeight = -1;
	private int cachedBeatBucket = -1;
	private float beatPulse;
	private float beatFlash;
	private float beatEnergyAvg;
	private int beatCooldown;

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
			if (w < 10 || h < 10) return;

			boolean hasAudio = false;
			if (ringBuffer != null) {
				// 1. Snapshot audio data from ring buffer
				int samples = ringBuffer.snapshot(snapshotBuf, SNAPSHOT_SAMPLES);
				hasAudio = samples >= NUM_POINTS;

				if (hasAudio) {
					// 2. Downsample to RMS amplitudes
					downsampleRMS(snapshotBuf, samples);

					// 3. Spatial smoothing (bidirectional EMA, multiple passes)
					spatialSmooth();

					// 4. Compression (lift quiet parts)
					for (int i = 0; i < NUM_POINTS; i++) {
						smoothBuf[i] = (float) Math.pow(smoothBuf[i], COMPRESSION_EXP);
					}
					updateBeatPulse();

					// 5. Temporal smoothing (fast attack, slow decay)
					for (int i = 0; i < NUM_POINTS; i++) {
						float target = smoothBuf[i];
						if (target > displayAmps[i]) {
							displayAmps[i] += (target - displayAmps[i]) * ATTACK;
						} else {
							displayAmps[i] += (target - displayAmps[i]) * DECAY;
						}
					}
				}
			} else {
				beatPulse *= BEAT_PULSE_DECAY;
			}

			// Background reacts to beat intensity.
			updateCachedPaints(w, h, beatPulse);
			g2.setPaint(bgPaint);
			g2.fillRect(0, 0, w, h);
			drawBeatOverlay(g2, w, h);
			drawCenterLine(g2, w, h);
			if (!hasAudio) return;

			// 6. Build all three paths (top contour, bottom contour, filled shape)
			float centerY = h / 2f;
			buildPaths(w, centerY, h * 0.30f, fillPath2, 0.84f);
			buildPaths(w, centerY, h * 0.22f, fillPath3, 0.70f);
			buildPaths(w, centerY, h * 0.40f, fillPath, 1f);

			// 7. Fill layered ribbons for depth
			g2.setColor(LAYER_3);
			g2.fill(fillPath3);
			g2.setColor(LAYER_2);
			g2.fill(fillPath2);
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

	private void updateCachedPaints(int w, int h, float beatLevel) {
		int beatBucket = (int) (Math.max(0f, Math.min(1f, beatLevel)) * 24f);
		if (w == cachedWidth && h == cachedHeight && beatBucket == cachedBeatBucket) {
			return;
		}
		cachedWidth = w;
		cachedHeight = h;
		cachedBeatBucket = beatBucket;
		float t = beatBucket / 24f;
		Color top = blend(BG_TOP, BEAT_TOP, t);
		Color bottom = blend(BG_BOTTOM, BEAT_BOTTOM, t);
		bgPaint = new LinearGradientPaint(0, 0, 0, h,
			new float[]{0f, 1f},
			new Color[]{top, bottom});
		ribbonPaint = new LinearGradientPaint(0, 0, w, 0, RIBBON_FRACTIONS, RIBBON_COLORS);
		edgeGlowPaint = new GradientPaint(0, 0, new Color(0xA9, 0xF0, 0xE8, 70), w, 0, new Color(0xB8, 0xA8, 0xEE, 65));
	}

	private void updateBeatPulse() {
		float instEnergy = 0f;
		for (int i = 0; i < NUM_POINTS; i++) {
			instEnergy += smoothBuf[i];
		}
		instEnergy /= NUM_POINTS;

		beatEnergyAvg += (instEnergy - beatEnergyAvg) * BEAT_AVG_ALPHA;
		float flux = instEnergy - beatEnergyAvg;
		float threshold = 0.006f + beatEnergyAvg * 0.22f;

		boolean beat = beatCooldown == 0 && flux > threshold;
		if (beat) {
			beatPulse = 1f;
			beatFlash = 1f;
			beatCooldown = BEAT_COOLDOWN_FRAMES;
		} else {
			beatPulse *= BEAT_PULSE_DECAY;
			beatPulse = Math.max(beatPulse, Math.min(1f, instEnergy * 4.5f));
			beatFlash *= BEAT_FLASH_DECAY;
			if (beatCooldown > 0) {
				beatCooldown--;
			}
		}
	}

	private void drawBeatOverlay(Graphics2D g2, int w, int h) {
		float intensity = Math.max(beatFlash, beatPulse * 0.55f);
		if (intensity <= 0.02f) return;
		int alpha = Math.min(170, (int) (intensity * 170f));
		int r = (BEAT_FLASH_A.getRed() + BEAT_FLASH_B.getRed()) / 2;
		int g = (BEAT_FLASH_A.getGreen() + BEAT_FLASH_B.getGreen()) / 2;
		int b = (BEAT_FLASH_A.getBlue() + BEAT_FLASH_B.getBlue()) / 2;
		GradientPaint pulse = new GradientPaint(
			0, 0, new Color(BEAT_FLASH_A.getRed(), BEAT_FLASH_A.getGreen(), BEAT_FLASH_A.getBlue(), alpha),
			w, h, new Color(r, g, b, alpha / 2)
		);
		g2.setPaint(pulse);
		g2.fillRect(0, 0, w, h);
	}

	private static Color blend(Color a, Color b, float t) {
		float clamped = Math.max(0f, Math.min(1f, t));
		int r = (int) (a.getRed() + (b.getRed() - a.getRed()) * clamped);
		int g = (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * clamped);
		int bl = (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * clamped);
		return new Color(r, g, bl);
	}

	private static void drawCenterLine(Graphics2D g2, int w, int h) {
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

	private void buildPaths(float w, float cy, float hh, Path2D.Float targetFill, float amplitudeScale) {
		float dx = w / (NUM_POINTS - 1);

		// Compute top and bottom contour Y-coordinates
		for (int i = 0; i < NUM_POINTS; i++) {
			float x = i * dx;
			float a = displayAmps[i] * amplitudeScale;
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
		targetFill.reset();
		catmullRomPath(targetFill, topX, topY, NUM_POINTS, true);

		// Reversed bottom for right-to-left traversal
		for (int i = 0; i < NUM_POINTS; i++) {
			revX[i] = (NUM_POINTS - 1 - i) * dx;
			revY[i] = cy + displayAmps[NUM_POINTS - 1 - i] * amplitudeScale * hh;
		}
		catmullRomPath(targetFill, revX, revY, NUM_POINTS, false);
		targetFill.closePath();
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
