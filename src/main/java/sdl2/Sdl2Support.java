package sdl2;

import java.awt.Component;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JEditorPane;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.event.HyperlinkEvent;

import com.sun.jna.NativeLibrary;

import lombok.extern.slf4j.Slf4j;

/**
 * Tells whether the SDL2 native libraries this plugin needs are present, and explains how to
 * install them when they are not.
 *
 * <p>Windows builds ship the DLLs inside the plugin, but on Linux and macOS SDL2 and SDL2_mixer
 * are system packages the user has to install. Without them the plugin used to die on a raw
 * {@code native library (linux-x86-64/libSDL2.so) not found} — and it could not even report that
 * itself, because the failure comes out of a JNA interface's static initializer as an
 * {@link ExceptionInInitializerError} rather than the {@link UnsatisfiedLinkError} the caller
 * was catching. So the check happens here, up front, before any of those classes is touched.
 */
@Slf4j
public final class Sdl2Support {
	private static final String SDL_INSTALL_URL = "https://wiki.libsdl.org/SDL2/Installation";
	private static final String HOMEBREW_URL = "https://brew.sh/";
	private static final String HOMEBREW_MIXER_URL = "https://formulae.brew.sh/formula/sdl2_mixer";
	private static final String UBUNTU_MIXER_URL = "https://packages.ubuntu.com/search?keywords=libsdl2-mixer-2.0-0";
	private static final String FEDORA_MIXER_URL = "https://packages.fedoraproject.org/pkgs/SDL2_mixer/SDL2_mixer/";
	private static final String ARCH_MIXER_URL = "https://archlinux.org/packages/extra/x86_64/sdl2_mixer/";
	private static final String OPENSUSE_MIXER_URL = "https://software.opensuse.org/package/SDL2_mixer";
	private static final String PLUGIN_HELP_URL =
			"https://nuclr.dev/plugins/core/music-sdl2-quick-viewer.html";

	private static Boolean available;

	private Sdl2Support() {
	}

	/**
	 * Whether SDL2 and SDL2_mixer can be loaded. Probed once and remembered: an install done
	 * while Nuclr is running would not take effect anyway, since the process has already fixed
	 * its native library search path.
	 */
	public static synchronized boolean isAvailable() {

		if (available == null) {
			NativeLibExtractor.ensureExtracted();
			available = probe();
		}

		return available;
	}

	private static boolean probe() {
		try {
			NativeLibrary.getInstance("SDL2");
			NativeLibrary.getInstance("SDL2_mixer");
			return true;
		} catch (Throwable t) {
			// UnsatisfiedLinkError when a library is missing, but a broken or half-installed
			// SDL2 can throw other LinkageErrors too — none of them should reach the panel.
			log.warn("SDL2 native libraries are not available: {}", t.toString());
			return false;
		}
	}

	/** One-line summary for the quick-view panel. */
	public static String shortHint() {
		return "Install SDL2/SDL2_mixer or disable this plugin.";
	}

	/**
	 * Tell the user which packages to install, with official links and the command for the platform
	 * they are on. Shown on the EDT; safe to call from the quick-view loader thread.
	 *
	 * @param parent        component used to position the dialog
	 * @param disablePlugin disables this plugin in the commander; may be {@code null}
	 */
	public static void showMissingLibraryDialog(Component parent, Runnable disablePlugin) {

		final InstallationHelp help = installationHelp();

		SwingUtilities.invokeLater(() -> {
			JEditorPane message = createMessage(help);
			Object[] options = help.command() == null
					? new Object[] { "Disable plugin", "Not now" }
					: new Object[] { "Copy install command", "Disable plugin", "Not now" };
			int choice = JOptionPane.showOptionDialog(
					parent != null && parent.isShowing() ? parent : null,
					message,
					"SDL2 audio support is unavailable",
					JOptionPane.DEFAULT_OPTION,
					JOptionPane.WARNING_MESSAGE,
					null,
					options,
					options[options.length - 1]);

			if (help.command() != null && choice == 0) {
				copyToClipboard(help.command());
				return;
			}
			int disableChoice = help.command() == null ? 0 : 1;
			if (choice == disableChoice && disablePlugin != null) {
				disablePlugin.run();
			}
		});
	}

	private static InstallationHelp installationHelp() {
		if (isMac()) {
			return new InstallationHelp(
					"macOS",
					"The macOS plugin does not bundle native SDL libraries. Install SDL2_mixer "
							+ "with Homebrew; its formula also installs the SDL2 compatibility runtime and codecs.",
					"brew install sdl2_mixer",
					links(
							link(HOMEBREW_URL, "Install Homebrew"),
							link(HOMEBREW_MIXER_URL, "SDL2_mixer formula"),
							link(SDL_INSTALL_URL, "SDL installation guide")));
		}

		if (isWindows()) {
			return new InstallationHelp(
					"Windows",
					"The required DLLs are included with this plugin, but they could not be loaded. "
							+ "Reinstall the plugin and check whether security software quarantined one of its DLLs.",
					null,
					links(link(PLUGIN_HELP_URL, "Plugin help")));
		}

		if (hasCommand("apt") || hasCommand("apt-get")) {
			String apt = hasCommand("apt") ? "apt" : "apt-get";
			return linuxHelp(
					"sudo " + apt + " install libsdl2-2.0-0 libsdl2-mixer-2.0-0",
					link(UBUNTU_MIXER_URL, "Ubuntu package information"));
		}
		if (hasCommand("dnf")) {
			return linuxHelp(
					"sudo dnf install SDL2 SDL2_mixer",
					link(FEDORA_MIXER_URL, "Fedora package information"));
		}
		if (hasCommand("pacman")) {
			return linuxHelp(
					"sudo pacman -S sdl2-compat sdl2_mixer",
					link(ARCH_MIXER_URL, "Arch package information"));
		}
		if (hasCommand("zypper")) {
			return linuxHelp(
					"sudo zypper install libSDL2-2_0-0 libSDL2_mixer-2_0-0",
					link(OPENSUSE_MIXER_URL, "openSUSE package information"));
		}

		return new InstallationHelp(
				"Linux",
				"This plugin does not bundle native libraries on Linux. Install the SDL2 runtime and "
						+ "SDL2_mixer packages supplied by your distribution, then restart Nuclr.",
				null,
				links(link(SDL_INSTALL_URL, "SDL installation guide")));
	}

	private static InstallationHelp linuxHelp(String command, String packageLink) {
		return new InstallationHelp(
				"Linux",
				"This plugin does not bundle native libraries on Linux. Install the SDL2 runtime and "
						+ "SDL2_mixer packages with your distribution's package manager.",
				command,
				links(packageLink, link(SDL_INSTALL_URL, "SDL installation guide")));
	}

	private static JEditorPane createMessage(InstallationHelp help) {
		String command = help.command() == null ? "" : "<p>Run in a terminal:</p>"
				+ "<p style='margin-left:12px;font-family:monospace;'><b>"
				+ html(help.command()) + "</b></p>";
		String customPath = "Windows".equals(help.platform()) ? "" : "<p>If the libraries are "
				+ "already installed in a custom location, start Nuclr with "
				+ "<code>-Djna.library.path=/path/to/libs</code>.</p>";
		String content = "<html><body style='font-family:sans-serif;width:520px;'>"
				+ "<h2>" + html(help.platform()) + " setup required</h2>"
				+ "<p>Nuclr could not load <b>SDL2</b> and <b>SDL2_mixer</b>.</p>"
				+ "<p>" + html(help.explanation()) + "</p>"
				+ command
				+ "<p>" + help.linksHtml() + "</p>"
				+ customPath
				+ "<p>Restart Nuclr after installing the libraries. If you do not need this music "
				+ "viewer, you can disable it now and enable it later from Plugins.</p>"
				+ "</body></html>";

		JEditorPane message = new JEditorPane("text/html", content);
		message.setEditable(false);
		message.setOpaque(false);
		message.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
		message.addHyperlinkListener(event -> {
			if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && event.getURL() != null) {
				openLink(event.getURL().toExternalForm());
			}
		});
		return message;
	}

	private static String link(String url, String label) {
		return "<a href='" + url + "'>" + html(label) + "</a>";
	}

	private static String links(String... links) {
		return String.join(" &nbsp;|&nbsp; ", links);
	}

	private static String html(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static boolean hasCommand(String name) {
		String path = System.getenv("PATH");
		if (path == null) {
			return false;
		}
		for (String dir : path.split(java.io.File.pathSeparator)) {
			if (dir.isBlank()) {
				continue;
			}
			try {
				if (Files.isExecutable(Path.of(dir, name))) {
					return true;
				}
			} catch (RuntimeException e) {
				// An unparseable PATH entry is not worth failing the whole message over.
				log.debug("Skipping PATH entry [{}]: {}", dir, e.getMessage());
			}
		}
		return false;
	}

	private static void copyToClipboard(String text) {
		try {
			Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
		} catch (Exception e) {
			log.warn("Could not copy the install command to the clipboard: {}", e.getMessage());
		}
	}

	private static void openLink(String url) {
		try {
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
				Desktop.getDesktop().browse(java.net.URI.create(url));
			} else {
				copyToClipboard(url);
			}
		} catch (Exception e) {
			log.warn("Could not open help link [{}]: {}", url, e.getMessage());
			copyToClipboard(url);
		}
	}

	private static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private record InstallationHelp(String platform, String explanation, String command, String linksHtml) {
	}
}
