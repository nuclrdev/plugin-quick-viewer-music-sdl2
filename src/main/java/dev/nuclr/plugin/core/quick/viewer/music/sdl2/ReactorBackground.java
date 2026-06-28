package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import javax.imageio.ImageIO;

/**
 * Procedural generator for the "Reactor Core" backdrop image.
 * <p>
 * Renders a dark nuclear-schematic background — radioactive radial wash, a faint
 * hex-lattice (graphite "containment" grid), concentric containment rings with a
 * reticle, a centred radiation-trefoil watermark (the very same geometry the live
 * effect spins), scattered radioactive specks, and a heavy vignette — using the
 * exact palette of {@link ReactorVisualizer}.
 * <p>
 * The baked PNG ({@code src/main/resources/effects/reactor-bg.png}) is what the
 * plugin ships and loads at runtime; {@link #render(int, int)} doubles as a
 * runtime fallback if that resource is ever missing. The {@link #main(String[])}
 * entry point regenerates the asset and is not part of the plugin runtime.
 */
final class ReactorBackground {

	private static final Color RAD_GREEN = new Color(80, 255, 70);
	private static final Color HAZ_AMBER = new Color(255, 200, 70);

	private ReactorBackground() {}

	static BufferedImage render(int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

			float cx = w * 0.5f;
			float cy = h * 0.5f;
			float s  = Math.min(w, h);
			Random rnd = new Random(92235L); // U-235

			drawBase(g, w, h, cx, cy, s);
			drawHexLattice(g, w, h, s);
			drawContainmentRings(g, cx, cy, s);
			drawReticle(g, cx, cy, s);
			drawTrefoilWatermark(g, cx, cy, s);
			drawSpecks(g, w, h, rnd);
			drawVignette(g, w, h, cx, cy);
		} finally {
			g.dispose();
		}
		return img;
	}

	private static void drawBase(Graphics2D g, int w, int h, float cx, float cy, float s) {
		g.setPaint(new RadialGradientPaint(cx, cy, s * 0.9f,
				new float[]{0f, 0.5f, 1f},
				new Color[]{new Color(12, 26, 18), new Color(6, 13, 13), new Color(2, 3, 8)}));
		g.fillRect(0, 0, w, h);

		// Faint radioactive core wash so the centre glows even when idle.
		g.setPaint(new RadialGradientPaint(cx, cy, s * 0.42f,
				new float[]{0f, 1f},
				new Color[]{new Color(24, 90, 46, 70), new Color(0, 0, 0, 0)}));
		g.fillRect(0, 0, w, h);
	}

	private static void drawHexLattice(Graphics2D g, int w, int h, float s) {
		float hs = s * 0.05f;
		float dx = 1.5f * hs;
		float dy = (float) Math.sqrt(3) * hs;
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(70, 200, 120, 12));
		int col = 0;
		for (float x = -hs; x < w + hs; x += dx, col++) {
			float yOff = (col & 1) == 0 ? 0f : dy / 2f;
			for (float y = -hs + yOff; y < h + hs; y += dy) {
				g.draw(hexagon(x, y, hs));
			}
		}
	}

	private static void drawContainmentRings(Graphics2D g, float cx, float cy, float s) {
		float[] dash = {6f, 10f};
		for (int i = 0; i < 6; i++) {
			float r = s * (0.13f + i * 0.060f);
			int   a = Math.max(8, 26 - i * 2);
			g.setColor(new Color(HAZ_AMBER.getRed(), HAZ_AMBER.getGreen(), HAZ_AMBER.getBlue(), a));
			g.setStroke((i & 1) == 0
					? new BasicStroke(1.4f)
					: new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, dash, 0f));
			g.draw(circle(cx, cy, r));
		}
	}

	private static void drawReticle(Graphics2D g, float cx, float cy, float s) {
		float rOut = s * 0.43f;
		g.setStroke(new BasicStroke(1.2f));
		g.setColor(new Color(120, 220, 150, 32));
		for (int d = 0; d < 360; d += 10) {
			double a   = Math.toRadians(d);
			float  rIn = (d % 30 == 0) ? s * 0.385f : s * 0.405f;
			float  c   = (float) Math.cos(a);
			float  sn  = (float) Math.sin(a);
			g.drawLine(Math.round(cx + c * rIn), Math.round(cy + sn * rIn),
					Math.round(cx + c * rOut), Math.round(cy + sn * rOut));
		}
	}

	private static void drawTrefoilWatermark(Graphics2D g, float cx, float cy, float s) {
		// Warning circle around the symbol.
		g.setStroke(new BasicStroke(s * 0.012f));
		g.setColor(new Color(HAZ_AMBER.getRed(), HAZ_AMBER.getGreen(), HAZ_AMBER.getBlue(), 26));
		g.draw(circle(cx, cy, s * 0.30f));

		// The trefoil itself — same shape the live effect rotates.
		AffineTransform at = new AffineTransform();
		at.translate(cx, cy);
		at.scale(s * 0.22f, s * 0.22f);
		Shape trefoil = at.createTransformedShape(ReactorVisualizer.buildTrefoil());
		g.setColor(new Color(HAZ_AMBER.getRed(), HAZ_AMBER.getGreen(), HAZ_AMBER.getBlue(), 30));
		g.fill(trefoil);
	}

	private static void drawSpecks(Graphics2D g, int w, int h, Random rnd) {
		int n = (w * h) / 3200;
		for (int i = 0; i < n; i++) {
			float x  = rnd.nextFloat() * w;
			float y  = rnd.nextFloat() * h;
			float sz = 1f + rnd.nextFloat() * 2.2f;
			Color c = rnd.nextFloat() < 0.20f
					? new Color(HAZ_AMBER.getRed(), HAZ_AMBER.getGreen(), HAZ_AMBER.getBlue(), 8 + rnd.nextInt(20))
					: new Color(RAD_GREEN.getRed(), RAD_GREEN.getGreen(), RAD_GREEN.getBlue(), 8 + rnd.nextInt(22));
			g.setColor(c);
			g.fillOval(Math.round(x), Math.round(y), Math.round(sz), Math.round(sz));
		}
	}

	private static void drawVignette(Graphics2D g, int w, int h, float cx, float cy) {
		g.setPaint(new RadialGradientPaint(cx, cy, Math.max(w, h) * 0.72f,
				new float[]{0f, 0.55f, 1f},
				new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 45), new Color(0, 0, 0, 210)}));
		g.fillRect(0, 0, w, h);
	}

	private static Shape hexagon(float cx, float cy, float r) {
		Path2D.Float p = new Path2D.Float();
		for (int i = 0; i < 6; i++) {
			double a = Math.toRadians(60 * i);
			float  x = cx + r * (float) Math.cos(a);
			float  y = cy + r * (float) Math.sin(a);
			if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
		}
		p.closePath();
		return p;
	}

	private static Ellipse2D.Float circle(float cx, float cy, float r) {
		return new Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2);
	}

	/** Regenerates the baked backdrop. Usage: {@code ReactorBackground [outPath] [width] [height]}. */
	public static void main(String[] args) throws Exception {
		int  w   = args.length > 1 ? Integer.parseInt(args[1]) : 1440;
		int  h   = args.length > 2 ? Integer.parseInt(args[2]) : 900;
		Path out = Path.of(args.length > 0 ? args[0] : "src/main/resources/effects/reactor-bg.png");
		if (out.getParent() != null) Files.createDirectories(out.getParent());
		BufferedImage img = render(w, h);
		File file = out.toFile();
		if (!ImageIO.write(img, "png", file)) {
			throw new IllegalStateException("No PNG writer available");
		}
		System.out.println("Wrote " + out.toAbsolutePath() + " (" + w + "x" + h + ")");
	}
}
