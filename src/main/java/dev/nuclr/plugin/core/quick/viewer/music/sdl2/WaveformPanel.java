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
 * Aurora-mirror waveform visualizer.
 * <p>
 * Renders a symmetric (mirrored) oscilloscope trace filled with a slowly-shifting
 * prismatic gradient — violet → blue → cyan — with a multi-pass neon glow on
 * the wave edges and motion-trail ghost frames.
 */
public class WaveformPanel extends JPanel {

	// ---- Background ----
	private static final Color BG_TOP    = new Color(0x08, 0x09, 0x14);
	private static final Color BG_BOTTOM = new Color(0x04, 0x05, 0x09);

	// ---- Strokes (pre-allocated) ----
	private static final BasicStroke GLOW1 = new BasicStroke(8f,   BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke GLOW2 = new BasicStroke(3.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke MAIN  = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke IDLE  = new BasicStroke(1f);

	// ---- Oscilloscope config ----
	private static final int   OSC_POINTS         = 256;
	private static final int   SNAPSHOT_SAMPLES   = 8192;
	private static final int   OSC_WINDOW_SAMPLES = 2048;
	private static final float TRACE_ATTACK       = 0.45f;
	private static final float TRACE_DECAY        = 0.18f;
	private static final float MIN_TRIGGER_SLOPE  = 0.003f;
	private static final float TRIGGER_LEVEL      = 0.015f;

	// ---- Trail ----
	private static final int TRAIL_FRAMES    = 5;
	private static final int TRAIL_MAX_ALPHA = 68;

	// ---- Color animation ----
	// Full hue drift cycle over ~10 minutes at 60 fps — very subtle living shift
	private static final float HUE_CYCLE = 36000f;

	// ---- Audio ----
	private AudioRingBuffer ringBuffer;

	// ---- Processing buffers ----
	private final float[]   snapshotBuf = new float[SNAPSHOT_SAMPLES];
	private final float[]   oscTarget   = new float[OSC_POINTS];
	private final float[]   oscDisplay  = new float[OSC_POINTS];
	private final float[][] trail       = new float[TRAIL_FRAMES][OSC_POINTS];

	// ---- Paths ----
	// Main paths for the current frame
	private final Path2D.Float topPath  = new Path2D.Float();
	private final Path2D.Float botPath  = new Path2D.Float();
	private final Path2D.Float fillPath = new Path2D.Float();
	// Auxiliary paths reused for trail rendering (avoids overwriting main paths)
	private final Path2D.Float auxTop   = new Path2D.Float();
	private final Path2D.Float auxBot   = new Path2D.Float();

	// ---- Cached background gradient ----
	private LinearGradientPaint bgPaint;
	private int cachedW = -1, cachedH = -1;

	// ---- State ----
	private int   trailWrite = 0;
	private int   trailCount = 0;
	private float autoGain   = 1.0f;
	private int   frameCount = 0;

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

	// ---- Color helpers ----

	/**
	 * Returns [left, mid, right] aurora colors for a 3-stop horizontal gradient.
	 * The hue slowly drifts from violet through blue to teal over one full cycle,
	 * giving the visualization a living, iridescent quality.
	 */
	private Color[] auroraColors(float alpha01) {
		float phase = (frameCount % (int) HUE_CYCLE) / HUE_CYCLE;
		// violet (0.76) drifts to teal (0.48) over the full cycle
		float base = 0.76f - phase * 0.28f;
		int   a    = clampAlpha(alpha01);
		return new Color[]{
			hsba(base,         0.88f, 1.00f, a),  // violet / indigo
			hsba(base - 0.13f, 0.80f, 1.00f, a),  // electric blue
			hsba(base - 0.26f, 0.88f, 1.00f, a),  // cyan / teal
		};
	}

	private static Color hsba(float h, float s, float b, int a) {
		h = ((h % 1f) + 1f) % 1f;
		Color c = Color.getHSBColor(h, s, b);
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}

	/** Horizontal 3-stop aurora gradient spanning the full panel width. */
	private LinearGradientPaint hGrad(int w, float alpha01) {
		Color[] c = auroraColors(alpha01);
		return new LinearGradientPaint(0, 0, w, 0, new float[]{0f, 0.5f, 1f}, c);
	}

	// ---- Paint ----

	@Override
	protected void paintComponent(Graphics g) {
		int w = getWidth();
		int h = getHeight();
		if (w < 10 || h < 10) return;
		frameCount++;

		Graphics2D g2 = (Graphics2D) g.create();
		try {
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,   RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

			// 1 — Background
			updateBg(w, h);
			g2.setPaint(bgPaint);
			g2.fillRect(0, 0, w, h);

			// 2 — Audio snapshot
			boolean hasAudio = false;
			if (ringBuffer != null) {
				int samples = ringBuffer.snapshot(snapshotBuf, SNAPSHOT_SAMPLES);
				hasAudio = samples >= OSC_POINTS;
				if (hasAudio) {
					extractOscFrame(samples);
					smoothTrace();
					pushTrail();
				}
			}

			if (!hasAudio) {
				drawIdle(g2, w, h);
				return;
			}

			// 3 — Trail ghost frames (drawn first, behind current frame)
			drawTrails(g2, w, h);

			// 4 — Build current-frame paths
			buildLinePaths(topPath, botPath, w, h, oscDisplay);
			buildFillPath(fillPath, w, h, oscDisplay);

			// 5 — Filled wave body (semi-transparent aurora wash)
			g2.setPaint(hGrad(w, 0.28f));
			g2.fill(fillPath);

			// 6 — Glow pass 1: wide, very soft outer halo
			g2.setPaint(hGrad(w, 0.16f));
			g2.setStroke(GLOW1);
			g2.draw(topPath);
			g2.draw(botPath);

			// 7 — Glow pass 2: tighter inner glow
			g2.setPaint(hGrad(w, 0.40f));
			g2.setStroke(GLOW2);
			g2.draw(topPath);
			g2.draw(botPath);

			// 8 — Main bright edge lines
			g2.setPaint(hGrad(w, 0.88f));
			g2.setStroke(MAIN);
			g2.draw(topPath);
			g2.draw(botPath);

			// 9 — Left / right vignette
			drawVignette(g2, w, h);

		} finally {
			g2.dispose();
		}
	}

	// ---- Idle state ----

	private void drawIdle(Graphics2D g2, int w, int h) {
		float pulse = 0.5f + 0.5f * (float) Math.sin(frameCount * 0.04);
		Color[] cols = auroraColors(0.35f * pulse);
		g2.setColor(cols[1]);
		g2.setStroke(IDLE);
		g2.drawLine(0, h / 2, w, h / 2);
	}

	// ---- Path builders ----

	private void buildLinePaths(Path2D.Float top, Path2D.Float bot,
	                            int w, int h, float[] data) {
		float cy  = h * 0.5f;
		float dx  = w / (OSC_POINTS - 1f);
		float amp = h * 0.42f * autoGain;
		top.reset();
		bot.reset();
		top.moveTo(0, cy - data[0] * amp);
		bot.moveTo(0, cy + data[0] * amp);
		for (int i = 1; i < OSC_POINTS; i++) {
			float x = i * dx;
			top.lineTo(x, cy - data[i] * amp);
			bot.lineTo(x, cy + data[i] * amp);
		}
	}

	/** Closed polygon covering the area between the top and mirrored bottom traces. */
	private void buildFillPath(Path2D.Float fill, int w, int h, float[] data) {
		float cy  = h * 0.5f;
		float dx  = w / (OSC_POINTS - 1f);
		float amp = h * 0.42f * autoGain;
		fill.reset();
		fill.moveTo(0, cy - data[0] * amp);
		for (int i = 1; i < OSC_POINTS; i++) {
			fill.lineTo(i * dx, cy - data[i] * amp);
		}
		// Return along the bottom (mirrored) edge right-to-left to close the shape
		for (int i = OSC_POINTS - 1; i >= 0; i--) {
			fill.lineTo(i * dx, cy + data[i] * amp);
		}
		fill.closePath();
	}

	// ---- Trail rendering ----

	private void drawTrails(Graphics2D g2, int w, int h) {
		for (int age = trailCount - 1; age >= 1; age--) {
			int idx = trailWrite - age;
			if (idx < 0) idx += TRAIL_FRAMES;
			float norm  = 1f - (float) age / trailCount;
			int   alpha = (int)(TRAIL_MAX_ALPHA * norm * norm);
			if (alpha < 4) continue;

			buildLinePaths(auxTop, auxBot, w, h, trail[idx]);
			Color[] c  = auroraColors(alpha / 255f);
			LinearGradientPaint tp = new LinearGradientPaint(
				0, 0, w, 0, new float[]{0f, 0.5f, 1f}, c);
			g2.setPaint(tp);
			float sw = 0.6f + norm;
			g2.setStroke(new BasicStroke(sw, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g2.draw(auxTop);
			g2.draw(auxBot);
		}
	}

	// ---- Vignette ----

	private static void drawVignette(Graphics2D g2, int w, int h) {
		int vw = Math.min(32, w / 6);
		g2.setPaint(new GradientPaint(0, 0, new Color(0, 0, 0, 100), vw, 0, new Color(0, 0, 0, 0)));
		g2.fillRect(0, 0, vw, h);
		g2.setPaint(new GradientPaint(w - vw, 0, new Color(0, 0, 0, 0), w, 0, new Color(0, 0, 0, 100)));
		g2.fillRect(w - vw, 0, vw, h);
	}

	// ---- Audio processing ----

	private void extractOscFrame(int samples) {
		int trigger = findTrigger(snapshotBuf, samples);
		int window  = Math.min(OSC_WINDOW_SAMPLES, samples - trigger - 1);
		if (window < OSC_POINTS) {
			window  = Math.min(samples - 1, OSC_POINTS);
			trigger = Math.max(0, samples - window - 1);
		}
		float step = (window - 1f) / (OSC_POINTS - 1f);
		float peak = 0f;
		for (int i = 0; i < OSC_POINTS; i++) {
			float pos = trigger + i * step;
			int   i0  = (int) pos;
			int   i1  = Math.min(i0 + 1, samples - 1);
			float v   = snapshotBuf[i0] + (snapshotBuf[i1] - snapshotBuf[i0]) * (pos - i0);
			oscTarget[i] = v;
			float av = Math.abs(v);
			if (av > peak) peak = av;
		}
		float tg = 0.85f / Math.max(0.08f, peak);
		autoGain += (clamp(tg, 0.8f, 2.8f) - autoGain) * 0.10f;
	}

	private static int findTrigger(float[] src, int len) {
		int   start     = Math.max(1, len / 4);
		int   end       = Math.max(start + 2, len - OSC_WINDOW_SAMPLES - 2);
		int   best      = start;
		float bestScore = Float.MAX_VALUE;
		for (int i = start; i < end; i++) {
			float a = src[i - 1], b = src[i];
			if (a <= 0f && b > 0f && (b - a) > MIN_TRIGGER_SLOPE) {
				float score = Math.abs(b);
				if (score < bestScore && score < TRIGGER_LEVEL) {
					bestScore = score;
					best      = i;
				}
			}
		}
		return best;
	}

	private void smoothTrace() {
		for (int i = 0; i < OSC_POINTS; i++) {
			float t = oscTarget[i], c = oscDisplay[i];
			float a = Math.abs(t) > Math.abs(c) ? TRACE_ATTACK : TRACE_DECAY;
			oscDisplay[i] = c + (t - c) * a;
		}
	}

	private void pushTrail() {
		System.arraycopy(oscDisplay, 0, trail[trailWrite], 0, OSC_POINTS);
		trailWrite = (trailWrite + 1) % TRAIL_FRAMES;
		if (trailCount < TRAIL_FRAMES) trailCount++;
	}

	private void updateBg(int w, int h) {
		if (w == cachedW && h == cachedH) return;
		cachedW = w;
		cachedH = h;
		bgPaint = new LinearGradientPaint(0, 0, 0, h,
			new float[]{0f, 1f}, new Color[]{BG_TOP, BG_BOTTOM});
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}

	private static int clampAlpha(float a01) {
		return Math.max(0, Math.min(255, (int)(a01 * 255)));
	}

}
