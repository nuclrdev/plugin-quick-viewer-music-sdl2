package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.platform.plugin.QuickViewNuclrPlugin;
import sdl2.NativeLibExtractor;

public class MusicSDL2QuickViewPlugin implements QuickViewNuclrPlugin {
	private static final String PLUGIN_DISABLE_EVENT = "plugin.disable";
	/** Cover art lives in the tags at the head of a file; a remote track is copied only this far. */
	private static final long MAX_THUMBNAIL_STAGE_BYTES = 32L * 1024 * 1024;
	private static final String PLUGIN_ID_KEY = "pluginId";

	private NuclrPluginContext context;
	private MusicSDl2ViewPanel panel;
	private volatile AtomicBoolean currentCancelled;
	private NuclrResource currentResource;

	@Override
	public JComponent panel() {
		if (this.panel == null) {
			this.panel = new MusicSDl2ViewPanel(this::disablePlugin);
		}
		return panel;
	}

	private void disablePlugin() {
		NuclrPluginContext currentContext = context;
		if (currentContext == null || currentContext.getEventBus() == null) {
			return;
		}
		currentContext.getEventBus().emit(this, PLUGIN_DISABLE_EVENT, Map.of(PLUGIN_ID_KEY, id));
	}

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
	}

	@Override
	public void init() {
	}

	@Override
	public NuclrPluginContext getContext() {
		return this.context;
	}

	@Override
	public boolean supports(NuclrResource resource) {
		String extension = extension(resource);
		if (extension == null) {
			return false;
		}
		return MusicSDl2ViewPanel.allowedExtensions.contains(extension.toLowerCase(Locale.ROOT));
	}

	private static String extension(NuclrResource resource) {
		if (resource == null || resource.getName() == null) {
			return null;
		}
		String name = resource.getName();
		int dot = name.lastIndexOf('.');
		if (dot < 0 || dot == name.length() - 1) {
			return null;
		}
		return name.substring(dot + 1);
	}


	@Override
	public boolean openResource(NuclrResource resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		this.currentResource = resource;
		this.currentCancelled = cancelled;
		NativeLibExtractor.ensureExtracted();
		panel();
		return this.panel.load(resource, cancelled);
	}

	@Override
	public boolean supportsThumbnails() {
		return true;
	}

	/**
	 * The track's embedded front cover. Never touches SDL, so it works whether or
	 * not audio is available; a track without artwork has no thumbnail.
	 */
	@Override
	public BufferedImage thumbnail(NuclrResource resource, int maxWidth, int maxHeight, AtomicBoolean cancelled) {
		if (maxWidth <= 0 || maxHeight <= 0 || !supports(resource)) {
			return null;
		}
		Path staged = null;
		try {
			Path file = resource.getPath();
			if (file == null || !Files.isReadable(file)) {
				staged = stageHead(resource, cancelled);
				file = staged;
			}
			if (file == null || (cancelled != null && cancelled.get())) {
				return null;
			}
			return ThumbnailScaler.fit(CoverArtExtractor.extract(file, extension(resource)), maxWidth, maxHeight);
		} catch (Exception e) {
			return null; // no readable artwork is just a track without a thumbnail
		} finally {
			if (staged != null) {
				try {
					Files.deleteIfExists(staged);
				} catch (Exception e) {
					staged.toFile().deleteOnExit();
				}
			}
		}
	}

	/** Copies the head of a resource with no local file, or returns {@code null} if cancelled. */
	private static Path stageHead(NuclrResource resource, AtomicBoolean cancelled) throws Exception {
		Path temp = Files.createTempFile("nuclr-cover-art-", ".tmp");
		boolean staged = false;
		try {
			try (InputStream in = resource.openInputStream(); OutputStream out = Files.newOutputStream(temp)) {
				byte[] buffer = new byte[64 * 1024];
				long total = 0;
				int read;
				while (total < MAX_THUMBNAIL_STAGE_BYTES
						&& (read = in.read(buffer, 0, (int) Math.min(buffer.length, MAX_THUMBNAIL_STAGE_BYTES - total))) >= 0) {
					if (cancelled != null && cancelled.get()) {
						return null;
					}
					out.write(buffer, 0, read);
					total += read;
				}
			}
			staged = true;
			return temp;
		} finally {
			// After the streams close: Windows will not delete a file that is still open.
			if (!staged) {
				Files.deleteIfExists(temp);
			}
		}
	}

	@Override
	public void closeResource() {
		if (currentCancelled != null) {
			currentCancelled.set(true);
			currentCancelled = null;
		}
		if (this.panel != null) {
			this.panel.clear();
		}
	}

	@Override
	public void unload() {
		closeResource();
		this.panel = null;
		this.context = null;
	}

	@Override
	public boolean onFocusGained() {
		return false;
	}

	@Override
	public void onFocusLost() {
	}

	@Override
	public boolean isFocused() {
		return false;
	}

	private String id = "dev.nuclr.plugin.core.quickviewer.music.sdl2";


	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
	}

	@Override
	public NuclrResource getCurrentResource() {
		return this.currentResource;
	}

	@Override
	public String uuid() {
		return id;
	}

}
