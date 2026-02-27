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
 * Oscilloscope-style waveform visualizer panel.
 * <p>
 * Draws a triggered PCM trace around the center line so the waveform looks
 * like a classic oscillogram rather than a scrolling envelope ribbon.
 */
public class WaveformPanel extends JPanel {

	// Background palette
	private static final Color BG_TOP = new Color(0x11, 0x14, 0x1F);
	private static final Color BG_BOTTOM = new Color(0x08, 0x0B, 0x12);
	private static final Color GRID = new Color(0xB4, 0xC0, 0xD4, 18);
	private static final Color CENTER_LINE = new Color(0xD8, 0xE1, 0xF0, 36);
	private static final Color VIGNETTE = new Color(0, 0, 0, 70);

	// Oscilloscope trace colors
	private static final Color TRACE_MAIN = new Color(0x9C, 0xFF, 0xE7, 220);
	private static final Color TRACE_GLOW_A = new Color(0x6D, 0xE7, 0xFF, 95);
	private static final Color TRACE_GLOW_B = new Color(0xB1, 0x8C, 0xFF, 65);
	private static final Color TRACE_HIGH = new Color(0xFF, 0xA8, 0x66, 235);
	private static final Color TRACE_LOW = new Color(0x7E, 0xA8, 0xFF, 235);
	private static final float SEGMENT_THRESHOLD = 0.52f;
	private static final Color[] TRAIL_COLORS = {
		new Color(0x89, 0x78, 0xFF),
		new Color(0x67, 0xA1, 0xFF),
		new Color(0x63, 0xD1, 0xFF),
		new Color(0x78, 0xF6, 0xE7)
	};
	private static final int TRAIL_FRAMES = 8;
	private static final int TRAIL_MAX_ALPHA = 115;

	private static final BasicStroke GRID_STROKE = new BasicStroke(1f);
	private static final BasicStroke CENTER_STROKE = new BasicStroke(1f);
	private static final BasicStroke TRACE_GLOW_1 = new BasicStroke(5.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke TRACE_GLOW_2 = new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke TRACE_MAIN_STROKE = new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

	// Oscilloscope processing constants
	private static final int OSC_POINTS = 512;
	private static final int SNAPSHOT_SAMPLES = 8192;
	private static final int OSC_WINDOW_SAMPLES = 2048;
	private static final float TRACE_ATTACK = 0.48f;
	private static final float TRACE_DECAY = 0.22f;
	private static final float MIN_TRIGGER_SLOPE = 0.003f;
	private static final float TRIGGER_LEVEL = 0.015f;

	// Audio data source
	private AudioRingBuffer ringBuffer;

	// Pre-allocated processing buffers
	private final float[] snapshotBuf = new float[SNAPSHOT_SAMPLES];
	private final float[] oscTarget = new float[OSC_POINTS];
	private final float[] oscDisplay = new float[OSC_POINTS];
	private final float[] traceX = new float[OSC_POINTS];
	private final float[] traceY = new float[OSC_POINTS];
	private final float[][] trail = new float[TRAIL_FRAMES][OSC_POINTS];

	// Reusable path
	private final Path2D.Float tracePath = new Path2D.Float();

	// Cached paints
	private LinearGradientPaint bgPaint;
	private int cachedWidth = -1;
	private int cachedHeight = -1;

	// Dynamic state
	private int trailWriteIndex;
	private int trailCount;
	private float autoGain = 1.0f;

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
				int samples = ringBuffer.snapshot(snapshotBuf, SNAPSHOT_SAMPLES);
				hasAudio = samples >= OSC_POINTS;
				if (hasAudio) {
					extractOscilloscopeFrame(samples);
					smoothTrace();
					pushTrailFrame();
				}
			}

			updateBackgroundPaint(w, h);
			g2.setPaint(bgPaint);
			g2.fillRect(0, 0, w, h);
			drawGrid(g2, w, h);
			drawCenterLine(g2, w, h);
			if (!hasAudio) return;

			drawTrails(g2, w, h);
			buildTracePath(w, h, oscDisplay);

			g2.setPaint(new GradientPaint(0, 0, TRACE_GLOW_A, w, 0, TRACE_GLOW_B));
			g2.setStroke(TRACE_GLOW_1);
			g2.draw(tracePath);
			g2.setStroke(TRACE_GLOW_2);
			g2.draw(tracePath);

			drawSegmentedMainTrace(g2);

			g2.setColor(VIGNETTE);
			g2.fillRect(0, 0, w, 2);
			g2.fillRect(0, h - 2, w, 2);
		} finally {
			g2.dispose();
		}
	}

	private void extractOscilloscopeFrame(int samples) {
		int trigger = findTriggerIndex(snapshotBuf, samples);
		int window = Math.min(OSC_WINDOW_SAMPLES, samples - trigger - 1);
		if (window < OSC_POINTS) {
			window = Math.min(samples - 1, OSC_POINTS);
			trigger = Math.max(0, samples - window - 1);
		}

		float step = (window - 1f) / (OSC_POINTS - 1f);
		float peak = 0f;
		for (int i = 0; i < OSC_POINTS; i++) {
			float pos = trigger + i * step;
			int i0 = (int) pos;
			int i1 = Math.min(i0 + 1, samples - 1);
			float t = pos - i0;
			float v = snapshotBuf[i0] + (snapshotBuf[i1] - snapshotBuf[i0]) * t;
			oscTarget[i] = v;
			float av = Math.abs(v);
			if (av > peak) peak = av;
		}

		float targetGain = 0.85f / Math.max(0.08f, peak);
		targetGain = clamp(targetGain, 0.8f, 2.8f);
		autoGain += (targetGain - autoGain) * 0.10f;
	}

	private static int findTriggerIndex(float[] src, int len) {
		int start = Math.max(1, len / 4);
		int end = Math.max(start + 2, len - OSC_WINDOW_SAMPLES - 2);
		int best = start;
		float bestScore = Float.MAX_VALUE;

		for (int i = start; i < end; i++) {
			float a = src[i - 1];
			float b = src[i];
			float slope = b - a;
			if (a <= 0f && b > 0f && slope > MIN_TRIGGER_SLOPE) {
				float score = Math.abs(b);
				if (score < bestScore && Math.abs(b) < TRIGGER_LEVEL) {
					bestScore = score;
					best = i;
				}
			}
		}
		return best;
	}

	private void smoothTrace() {
		for (int i = 0; i < OSC_POINTS; i++) {
			float target = oscTarget[i];
			float current = oscDisplay[i];
			float alpha = Math.abs(target) > Math.abs(current) ? TRACE_ATTACK : TRACE_DECAY;
			oscDisplay[i] += (target - current) * alpha;
		}
	}

	private void pushTrailFrame() {
		float[] dst = trail[trailWriteIndex];
		System.arraycopy(oscDisplay, 0, dst, 0, OSC_POINTS);
		trailWriteIndex = (trailWriteIndex + 1) % TRAIL_FRAMES;
		if (trailCount < TRAIL_FRAMES) {
			trailCount++;
		}
	}

	private void drawTrails(Graphics2D g2, int w, int h) {
		for (int age = trailCount - 1; age >= 0; age--) {
			int idx = trailWriteIndex - 1 - age;
			if (idx < 0) idx += TRAIL_FRAMES;
			float[] frame = trail[idx];
			float norm = (age + 1f) / (trailCount + 1f);
			int alpha = (int) (TRAIL_MAX_ALPHA * norm * norm);
			Color c = TRAIL_COLORS[age % TRAIL_COLORS.length];
			g2.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));
			g2.setStroke(new BasicStroke(1.1f + norm, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			buildTracePath(w, h, frame);
			g2.draw(tracePath);
		}
	}

	private void drawSegmentedMainTrace(Graphics2D g2) {
		g2.setStroke(TRACE_MAIN_STROKE);
		for (int i = 0; i < OSC_POINTS - 1; i++) {
			float a = oscDisplay[i];
			float b = oscDisplay[i + 1];
			float mid = (a + b) * 0.5f;
			if (mid >= SEGMENT_THRESHOLD) {
				g2.setColor(TRACE_HIGH);
			} else if (mid <= -SEGMENT_THRESHOLD) {
				g2.setColor(TRACE_LOW);
			} else {
				g2.setColor(TRACE_MAIN);
			}
			g2.drawLine(Math.round(traceX[i]), Math.round(traceY[i]), Math.round(traceX[i + 1]), Math.round(traceY[i + 1]));
		}
	}

	private void buildTracePath(int w, int h, float[] data) {
		tracePath.reset();
		float cy = h * 0.5f;
		float dx = w / (OSC_POINTS - 1f);
		float amp = h * 0.36f * autoGain;

		traceX[0] = 0f;
		traceY[0] = cy - data[0] * amp;
		tracePath.moveTo(traceX[0], traceY[0]);
		for (int i = 1; i < OSC_POINTS; i++) {
			traceX[i] = i * dx;
			traceY[i] = cy - data[i] * amp;
			tracePath.lineTo(traceX[i], traceY[i]);
		}
	}

	private void updateBackgroundPaint(int w, int h) {
		if (w == cachedWidth && h == cachedHeight) {
			return;
		}
		cachedWidth = w;
		cachedHeight = h;
		bgPaint = new LinearGradientPaint(0, 0, 0, h, new float[]{0f, 1f}, new Color[]{BG_TOP, BG_BOTTOM});
	}

	private static void drawGrid(Graphics2D g2, int w, int h) {
		g2.setStroke(GRID_STROKE);
		g2.setColor(GRID);
		for (int i = 1; i < 8; i++) {
			int x = i * w / 8;
			g2.drawLine(x, 0, x, h);
		}
		for (int i = 1; i < 6; i++) {
			int y = i * h / 6;
			g2.drawLine(0, y, w, y);
		}
	}

	private static void drawCenterLine(Graphics2D g2, int w, int h) {
		g2.setStroke(CENTER_STROKE);
		g2.setColor(CENTER_LINE);
		g2.drawLine(0, h / 2, w, h / 2);
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

}
