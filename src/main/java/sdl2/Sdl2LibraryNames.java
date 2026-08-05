package sdl2;

/**
 * Native library names used by JNA.
 *
 * <p>Linux runtime packages expose the SDL 2 ABI through SONAMEs such as
 * {@code libSDL2-2.0.so.0}. They do not normally install the unversioned
 * {@code libSDL2.so} linker name; that symlink belongs to the development
 * package. Asking JNA for {@code SDL2-2.0} lets it find the runtime SONAME
 * without requiring users to install development files.
 */
final class Sdl2LibraryNames {

	static final String SDL2 = isLinux() ? "SDL2-2.0" : "SDL2";
	static final String SDL2_MIXER = isLinux() ? "SDL2_mixer-2.0" : "SDL2_mixer";

	private Sdl2LibraryNames() {
	}

	private static boolean isLinux() {
		return System.getProperty("os.name", "").toLowerCase().contains("linux");
	}
}
