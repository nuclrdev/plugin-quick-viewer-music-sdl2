package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Shrinks a decoded picture into the box a thumbnail caller asked for.
 */
final class ThumbnailScaler {

	private ThumbnailScaler() {
	}

	/**
	 * Scales {@code source} down to fit within {@code maxWidth} x {@code maxHeight},
	 * keeping its aspect ratio. Never enlarges. The result is always a fresh image,
	 * so the caller may keep it while {@code source} is reused or dropped.
	 *
	 * @return the thumbnail, or {@code null} when the source or the box is empty
	 */
	static BufferedImage fit(BufferedImage source, int maxWidth, int maxHeight) {
		if (source == null || maxWidth <= 0 || maxHeight <= 0) {
			return null;
		}
		int width = source.getWidth();
		int height = source.getHeight();
		if (width <= 0 || height <= 0) {
			return null;
		}

		double scale = Math.min(1.0, Math.min((double) maxWidth / width, (double) maxHeight / height));
		int targetWidth = Math.clamp(Math.round(width * scale), 1, maxWidth);
		int targetHeight = Math.clamp(Math.round(height * scale), 1, maxHeight);
		int type = source.getColorModel().hasAlpha() ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;

		// Halve first: a single bilinear pass to a much smaller size samples only a
		// few source pixels per output pixel and aliases badly.
		BufferedImage current = source;
		int currentWidth = width;
		int currentHeight = height;
		while (currentWidth / 2 >= targetWidth && currentHeight / 2 >= targetHeight) {
			currentWidth /= 2;
			currentHeight /= 2;
			current = draw(current, currentWidth, currentHeight, type);
		}
		return draw(current, targetWidth, targetHeight, type);
	}

	private static BufferedImage draw(BufferedImage source, int width, int height, int type) {
		BufferedImage target = new BufferedImage(width, height, type);
		Graphics2D g = target.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(source, 0, 0, width, height, null);
		} finally {
			g.dispose();
		}
		return target;
	}
}
