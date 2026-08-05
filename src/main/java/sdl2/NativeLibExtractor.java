package sdl2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.NativeLibrary;

import lombok.extern.slf4j.Slf4j;

/**
 * Extracts bundled native libraries from the plugin JAR to a persistent
 * user-local directory and registers that directory with JNA.
 *
 * <p>Must be called before any {@code Native.load()} invocation (i.e., before
 * the first {@link SDLMixerAudio} is constructed). Safe to call multiple times;
 * extraction is skipped for files whose on-disk size already matches the
 * bundled version.
 */
@Slf4j
public class NativeLibExtractor {

	public static final Path EXTRACT_DIR =
			Path.of(System.getProperty("user.home"), ".nuclr", "plugins", "native", "sdl2");

	private static final String[] WIN_LIBS = {
			"SDL2.dll", "SDL2_mixer.dll",
			"libgme.dll", "libogg-0.dll", "libopus-0.dll",
			"libopusfile-0.dll", "libwavpack-1.dll", "libxmp.dll"
	};

	/**
	 * Pre-load order for codec DLLs: dependencies before dependents.
	 * SDL2_mixer.dll is excluded — JNA loads it; by then SDL2.dll is already in
	 * the module list so its import is satisfied.
	 * These are loaded with absolute paths so that when SDL2_mixer later calls
	 * Windows {@code LoadLibrary("libxmp.dll")} (etc.) the module is already
	 * present in the process module list and Windows returns the cached handle
	 * instead of searching the system DLL path.
	 */
	private static final String[] WIN_LIBS_PRELOAD = {
			"SDL2.dll",           // SDL2_mixer.dll import dependency
			"libogg-0.dll",       // libopusfile-0.dll import dependency
			"libopus-0.dll",      // libopusfile-0.dll import dependency
			"libopusfile-0.dll",
			"libwavpack-1.dll",
			"libgme.dll",
			"libxmp.dll",
	};

	private static final String[] MAC_LIBRARY_SEARCH_DIRS = {
			"/opt/homebrew/lib",
			"/usr/local/lib",
			"/opt/local/lib"
	};

	private static final String[] LINUX_LIBRARY_SEARCH_DIRS = {
			"/usr/local/lib",
			"/usr/lib",
			"/usr/lib64",
			"/usr/lib/x86_64-linux-gnu",
			"/usr/lib/aarch64-linux-gnu"
	};

	private static final String[] MAC_FRAMEWORK_PATHS = {
			"/Library/Frameworks/SDL2.framework/SDL2",
			"/Library/Frameworks/SDL2_mixer.framework/SDL2_mixer",
			"/System/Library/Frameworks/SDL2.framework/SDL2",
			"/System/Library/Frameworks/SDL2_mixer.framework/SDL2_mixer",
			System.getProperty("user.home", "") + "/Library/Frameworks/SDL2.framework/SDL2",
			System.getProperty("user.home", "") + "/Library/Frameworks/SDL2_mixer.framework/SDL2_mixer"
	};

	private static boolean done = false;

	/**
	 * Ensure all bundled native libraries for the current platform are present
	 * on disk and reachable by JNA. Idempotent: subsequent calls return immediately.
	 *
	 * <p>On failure, a warning is logged and execution continues — JNA will fall
	 * back to its normal library search path (system PATH, etc.).
	 */
	public static synchronized void ensureExtracted() {
		if (done) return;
		try {
			doExtract();
			done = true;
		} catch (Exception e) {
			log.warn("Native lib extraction failed — falling back to system path: {}", e.getMessage());
		}
	}

	private static void doExtract() throws IOException {
		if (isWindows()) {
			extractBundledWindowsLibs();
			prependJnaLibraryPath(EXTRACT_DIR.toString());
			preloadNativeLibs();
			log.info("SDL2 native libs ready at {}", EXTRACT_DIR);
			return;
		}
		configureSystemLibraryPaths();
	}

	/**
	 * Pre-load codec DLLs by absolute path so they are already in the Windows
	 * process module list before JNA loads SDL2_mixer.dll. This prevents
	 * ERROR_MOD_NOT_FOUND when SDL2_mixer calls {@code LoadLibrary("libxmp.dll")}
	 * (etc.) at codec-init time, since those names are not on the system PATH.
	 */
	private static void preloadNativeLibs() {
		if (!isWindows()) return;
		for (String lib : WIN_LIBS_PRELOAD) {
			Path path = EXTRACT_DIR.resolve(lib);
			try {
				System.load(path.toAbsolutePath().toString());
				log.debug("Pre-loaded: {}", lib);
			} catch (UnsatisfiedLinkError e) {
				log.warn("Could not pre-load {}: {}", lib, e.getMessage());
			}
		}
	}

	private static void extractBundledWindowsLibs() throws IOException {
		Files.createDirectories(EXTRACT_DIR);

		ClassLoader loader = NativeLibExtractor.class.getClassLoader();
		for (String lib : WIN_LIBS) {
			String resource = "native/win/" + lib;
			Path dest = EXTRACT_DIR.resolve(lib);
			extractIfNeeded(loader, resource, dest);
		}
	}

	private static void extractIfNeeded(ClassLoader loader, String resource, Path dest)
			throws IOException {
		try (InputStream in = loader.getResourceAsStream(resource)) {
			if (in == null) {
				log.warn("Bundled native lib not found in JAR: {}", resource);
				return;
			}
			byte[] data = in.readAllBytes();
			if (Files.exists(dest) && Files.size(dest) == data.length) {
				log.debug("Native lib up-to-date: {}", dest.getFileName());
				return;
			}
			Files.write(dest, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			log.debug("Extracted native lib: {}", dest.getFileName());
		}
	}

	private static void configureSystemLibraryPaths() {
		List<String> configuredDirs = new ArrayList<>();
		if (isMac()) {
			collectExistingDirs(configuredDirs, MAC_LIBRARY_SEARCH_DIRS);
			preloadExistingLibraries(MAC_FRAMEWORK_PATHS);
		} else if (isLinux()) {
			collectExistingDirs(configuredDirs, LINUX_LIBRARY_SEARCH_DIRS);
		}

		for (String dir : configuredDirs) {
			prependJnaLibraryPath(dir);
			addJnaSearchPath(dir);
		}

		if (!configuredDirs.isEmpty()) {
			log.info("Configured SDL2 system library search paths: {}", configuredDirs);
		} else {
			log.info("No additional SDL2 system library paths configured for {}", System.getProperty("os.name"));
		}
	}

	private static void collectExistingDirs(List<String> configuredDirs, String[] candidates) {
		for (String candidate : candidates) {
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			Path path = Path.of(candidate);
			if (Files.isDirectory(path) && !configuredDirs.contains(candidate)) {
				configuredDirs.add(candidate);
			}
		}
	}

	private static void addJnaSearchPath(String dir) {
		NativeLibrary.addSearchPath(Sdl2LibraryNames.SDL2, dir);
		NativeLibrary.addSearchPath(Sdl2LibraryNames.SDL2_MIXER, dir);
	}

	private static void preloadExistingLibraries(String[] libraryPaths) {
		for (String libraryPath : libraryPaths) {
			if (libraryPath == null || libraryPath.isBlank()) {
				continue;
			}
			Path path = Path.of(libraryPath);
			if (!Files.isRegularFile(path)) {
				continue;
			}
			try {
				System.load(path.toAbsolutePath().toString());
				log.debug("Pre-loaded native library: {}", path);
			} catch (UnsatisfiedLinkError e) {
				log.warn("Could not pre-load {}: {}", path, e.getMessage());
			}
		}
	}

	private static boolean isWindows() {
		return System.getProperty("os.name", "").toLowerCase().contains("win");
	}

	private static boolean isMac() {
		return System.getProperty("os.name", "").toLowerCase().contains("mac");
	}

	private static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase().contains("linux");
	}

	private static void prependJnaLibraryPath(String dir) {
		String existing = System.getProperty("jna.library.path", "");
		System.setProperty("jna.library.path",
				existing.isBlank() ? dir : dir + java.io.File.pathSeparator + existing);
	}
}
