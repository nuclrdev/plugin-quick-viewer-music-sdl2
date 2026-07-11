package sdl2;

import java.awt.Component;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

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
		return "SDL2 and SDL2_mixer are not installed — see the instructions.";
	}

	/**
	 * Tell the user which packages to install, with the command for the platform they are on.
	 * Shown on the EDT; safe to call from the quick-view loader thread.
	 */
	public static void showMissingLibraryDialog(Component parent) {

		final String command = installCommand();
		final String message = ""
				+ "This player needs the SDL2 audio libraries, which are not installed.\n\n"
				+ "Install them with:\n\n"
				+ "    " + command + "\n\n"
				+ alternatives()
				+ "\nThen restart Nuclr.";

		SwingUtilities.invokeLater(() -> {
			Object[] options = { "Copy command", "Close" };
			int choice = JOptionPane.showOptionDialog(
					parent != null && parent.isShowing() ? parent : null,
					message,
					"SDL2 audio libraries missing",
					JOptionPane.DEFAULT_OPTION,
					JOptionPane.WARNING_MESSAGE,
					null,
					options,
					options[1]);

			if (choice == 0) {
				copyToClipboard(command);
			}
		});
	}

	/** The install command for this platform, picked from the package manager actually present. */
	private static String installCommand() {

		if (isMac()) {
			return "brew install sdl2 sdl2_mixer";
		}

		if (isWindows()) {
			// The DLLs ship with the plugin, so reaching this means the install is damaged.
			return "reinstall the SDL2 music plugin";
		}

		if (hasCommand("apt")) {
			return "sudo apt install libsdl2-2.0-0 libsdl2-mixer-2.0-0";
		}
		if (hasCommand("dnf")) {
			return "sudo dnf install SDL2 SDL2_mixer";
		}
		if (hasCommand("pacman")) {
			return "sudo pacman -S sdl2 sdl2_mixer";
		}
		if (hasCommand("zypper")) {
			return "sudo zypper install libSDL2-2_0-0 libSDL2_mixer-2_0-0";
		}

		return "install the SDL2 and SDL2_mixer packages for your distribution";
	}

	/** The commands for the platforms we did not detect, so the message still helps if the guess is off. */
	private static String alternatives() {

		if (isWindows()) {
			return "";
		}

		var sb = new StringBuilder("On other systems:\n");

		if (!isMac()) {
			sb.append("    macOS:            brew install sdl2 sdl2_mixer\n");
		}
		if (!hasCommand("apt")) {
			sb.append("    Debian/Ubuntu:    sudo apt install libsdl2-2.0-0 libsdl2-mixer-2.0-0\n");
		}
		if (!hasCommand("dnf")) {
			sb.append("    Fedora:           sudo dnf install SDL2 SDL2_mixer\n");
		}
		if (!hasCommand("pacman")) {
			sb.append("    Arch:             sudo pacman -S sdl2 sdl2_mixer\n");
		}

		return sb.toString();
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

	private static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}
}
