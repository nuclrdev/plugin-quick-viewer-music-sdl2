package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResourcePath;
import sdl2.NativeLibExtractor;

public class MusicSDL2QuickViewProvider implements NuclrPlugin {

	private NuclrPluginContext context;
	private MusicSDl2ViewPanel panel;
	private volatile AtomicBoolean currentCancelled;

	@Override
	public JComponent panel() {
		if (this.panel == null) {
			this.panel = new MusicSDl2ViewPanel();
		}
		return panel;
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResourcePath source) {
		return List.of();
	}

	@Override
	public void load(NuclrPluginContext context) {
		this.context = context;
	}

	@Override
	public boolean supports(NuclrResourcePath resource) {
		if (resource == null || resource.getExtension() == null) {
			return false;
		}
		return MusicSDl2ViewPanel.allowedExtensions.contains(resource.getExtension().toLowerCase(Locale.ROOT));
	}

	@Override
	public int priority() {
		return 1;
	}

	@Override
	public boolean openResource(NuclrResourcePath resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		this.currentCancelled = cancelled;
		NativeLibExtractor.ensureExtracted();
		panel();
		return this.panel.load(resource, cancelled);
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

	private String name = "Music Quick Viewer (SDL2)";
	private String id = "dev.nuclr.plugin.core.quickviewer.music.sdl2";
	private String version = "1.0.1";
	private String description = "A quick viewer for music/sound files.";
	private String author = "Nuclr Development Team";
	private String license = "Apache-2.0";
	private String website = "https://nuclr.dev";
	private String pageUrl = "https://nuclr.dev/plugins/core/music-sdl2-quick-viewer.html";
	private String docUrl = "https://nuclr.dev/plugins/core/music-sdl2-quick-viewer.html";

	@Override
	public String id() {
		return id;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String version() {
		return version;
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public String author() {
		return author;
	}

	@Override
	public String license() {
		return license;
	}

	@Override
	public String website() {
		return website;
	}

	@Override
	public String pageUrl() {
		return pageUrl;
	}

	@Override
	public String docUrl() {
		return docUrl;
	}

	@Override
	public Developer type() {
		return Developer.Official;
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
	}
}
