package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import javax.swing.JComponent;

import dev.nuclr.plugin.QuickViewItem;
import dev.nuclr.plugin.QuickViewProvider;
import sdl2.NativeLibExtractor;

public class MusicSDL2QuickViewProvider implements QuickViewProvider {

	private MusicSDl2ViewPanel panel;

	@Override
	public String getPluginClass() {
		return getClass().getName();
	}

	@Override
	public boolean matches(QuickViewItem item) {
		return MusicSDl2ViewPanel.allowedExtensions.contains(item.extension().toLowerCase());
	}

	@Override
	public JComponent getPanel() {
		if (this.panel == null) {
			this.panel = new MusicSDl2ViewPanel();
		}
		return panel;
	}

	@Override
	public boolean open(QuickViewItem item) {
		NativeLibExtractor.ensureExtracted();
		getPanel(); // ensure panel exists
		return this.panel.load(item);
	}

	@Override
	public void close() {
		if (this.panel != null) {
			this.panel.clear();
		}
	}

	@Override
	public void unload() {
		close();
		this.panel = null;
	}

	@Override
	public int priority() {
		return 1;
	}

}
