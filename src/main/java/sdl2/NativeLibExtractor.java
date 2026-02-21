package sdl2;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
		String platformDir = resolvePlatformDir();
		Files.createDirectories(EXTRACT_DIR);

		ClassLoader loader = NativeLibExtractor.class.getClassLoader();
		for (String lib : WIN_LIBS) {
			String resource = "native/" + platformDir + "/" + lib;
			Path dest = EXTRACT_DIR.resolve(lib);
			extractIfNeeded(loader, resource, dest);
		}

		prependJnaLibraryPath(EXTRACT_DIR.toString());
		preloadNativeLibs();
		log.info("SDL2 native libs ready at {}", EXTRACT_DIR);
	}

	/**
	 * Pre-load codec DLLs by absolute path so they are already in the Windows
	 * process module list before JNA loads SDL2_mixer.dll. This prevents
	 * ERROR_MOD_NOT_FOUND when SDL2_mixer calls {@code LoadLibrary("libxmp.dll")}
	 * (etc.) at codec-init time, since those names are not on the system PATH.
	 */
	private static void preloadNativeLibs() {
		if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
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

	private static String resolvePlatformDir() {
		String os = System.getProperty("os.name", "").toLowerCase();
		if (os.contains("win")) return "win";
		throw new UnsupportedOperationException(
				"No bundled native libs for platform: " + System.getProperty("os.name"));
	}

	private static void prependJnaLibraryPath(String dir) {
		String existing = System.getProperty("jna.library.path", "");
		System.setProperty("jna.library.path",
				existing.isBlank() ? dir : dir + java.io.File.pathSeparator + existing);
	}
}
