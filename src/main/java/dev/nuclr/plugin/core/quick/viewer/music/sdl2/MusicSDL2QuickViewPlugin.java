package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

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
