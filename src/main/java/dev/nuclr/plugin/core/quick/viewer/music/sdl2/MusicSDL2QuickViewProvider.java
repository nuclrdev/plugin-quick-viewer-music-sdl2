package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.ApplicationPluginContext;
import dev.nuclr.plugin.MenuResource;
import dev.nuclr.plugin.PluginManifest;
import dev.nuclr.plugin.PluginPathResource;
import dev.nuclr.plugin.QuickViewProviderPlugin;
import sdl2.NativeLibExtractor;

public class MusicSDL2QuickViewProvider implements QuickViewProviderPlugin {

	private ApplicationPluginContext context;
	private MusicSDl2ViewPanel panel;
	private volatile AtomicBoolean currentCancelled;

	@Override
	public PluginManifest getPluginInfo() {
		ObjectMapper objectMapper = context != null ? context.getObjectMapper() : new ObjectMapper();
		try (InputStream is = getClass().getResourceAsStream("/plugin.json")) {
			if (is != null) {
				return objectMapper.readValue(is, PluginManifest.class);
			}
		} catch (Exception e) {
			return null;
		}
		return null;
	}

	@Override
	public JComponent getPanel() {
		if (this.panel == null) {
			this.panel = new MusicSDl2ViewPanel();
		}
		return panel;
	}

	@Override
	public List<MenuResource> getMenuItems(PluginPathResource source) {
		return List.of();
	}

	@Override
	public void load(ApplicationPluginContext context) {
		this.context = context;
	}

	@Override
	public boolean supports(PluginPathResource resource) {
		if (resource == null || resource.getExtension() == null) {
			return false;
		}
		return MusicSDl2ViewPanel.allowedExtensions.contains(resource.getExtension().toLowerCase(Locale.ROOT));
	}

	@Override
	public int getPriority() {
		return 1;
	}

	@Override
	public boolean openItem(PluginPathResource resource, AtomicBoolean cancelled) {
		if (currentCancelled != null) {
			currentCancelled.set(true);
		}
		this.currentCancelled = cancelled;
		NativeLibExtractor.ensureExtracted();
		getPanel();
		return this.panel.load(resource, cancelled);
	}

	@Override
	public void closeItem() {
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
		closeItem();
		this.panel = null;
		this.context = null;
	}

	@Override
	public void onFocusGained() {
		// Quick view providers do not need focus-specific behavior.
	}

	@Override
	public void onFocusLost() {
		// Quick view providers do not need focus-specific behavior.
	}
}
