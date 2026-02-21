package sdl2;

import com.sun.jna.Native;
import com.sun.jna.Platform;
import java.io.File;
import java.util.Arrays;

/**
 * Diagnostic tool to help debug SDL2 library loading issues
 */
public class SDLDiagnostic {
    
    public static void main(String[] args) {
        System.out.println("=== SDL2 Library Diagnostic Tool ===\n");
        
        // System info
        System.out.println("System Information:");
        System.out.println("  OS: " + System.getProperty("os.name") + " " + System.getProperty("os.version"));
        System.out.println("  Arch: " + System.getProperty("os.arch"));
        System.out.println("  Java: " + System.getProperty("java.version") + " (" + 
                         System.getProperty("java.vendor") + ")");
        System.out.println("  JNA: " + Native.VERSION);
        System.out.println();
        
        // Library names for current platform
        String[] sdlLibNames = getSDLLibraryNames();
        System.out.println("Expected library names for your platform:");
        for (String lib : sdlLibNames) {
            System.out.println("  - " + lib);
        }
        System.out.println();
        
        // Check JNA library path
        String jnaPath = System.getProperty("jna.library.path");
        System.out.println("JNA library path: " + (jnaPath != null ? jnaPath : "(not set)"));
        System.out.println();
        
        // Check system library path
        System.out.println("System library paths:");
        String libPath = System.getProperty("java.library.path");
        if (libPath != null) {
            for (String path : libPath.split(File.pathSeparator)) {
                System.out.println("  " + path);
                checkLibrariesInPath(path, sdlLibNames);
            }
        }
        System.out.println();
        
        // Platform-specific checks
        platformSpecificChecks();
        
        // Try to load libraries
        System.out.println("Attempting to load libraries:");
        tryLoadLibrary("SDL2");
        tryLoadLibrary("SDL2_mixer");
        
        // Suggestions
        System.out.println("\n=== Suggestions ===");
        provideSuggestions();
    }
    
    private static String[] getSDLLibraryNames() {
        if (Platform.isWindows()) {
            return new String[]{"SDL2.dll", "SDL2_mixer.dll"};
        } else if (Platform.isMac()) {
            return new String[]{"libSDL2.dylib", "libSDL2_mixer.dylib", 
                              "libSDL2-2.0.0.dylib", "libSDL2_mixer-2.0.0.dylib"};
        } else if (Platform.isLinux()) {
            return new String[]{"libSDL2.so", "libSDL2_mixer.so",
                              "libSDL2-2.0.so.0", "libSDL2_mixer-2.0.so.0"};
        }
        return new String[]{"SDL2", "SDL2_mixer"};
    }
    
    private static void checkLibrariesInPath(String path, String[] libNames) {
        File dir = new File(path);
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles((d, name) -> {
                for (String libName : libNames) {
                    if (name.contains(libName.replace("lib", ""))) {
                        return true;
                    }
                }
                return false;
            });
            
            if (files != null && files.length > 0) {
                System.out.println("    Found SDL libraries:");
                for (File file : files) {
                    System.out.println("      ✓ " + file.getName());
                }
            }
        }
    }
    
    private static void platformSpecificChecks() {
        if (Platform.isWindows()) {
            System.out.println("Windows-specific checks:");
            System.out.println("  Current directory: " + System.getProperty("user.dir"));
            checkLibrariesInPath(".", getSDLLibraryNames());
            
            // Check if running 64-bit Java
            boolean is64bit = System.getProperty("os.arch").contains("64");
            System.out.println("  Java architecture: " + (is64bit ? "64-bit" : "32-bit"));
            System.out.println("  Make sure to use " + (is64bit ? "x64" : "x86") + " versions of SDL libraries!");
            
        } else if (Platform.isMac()) {
            System.out.println("macOS-specific checks:");
            
            // Check Homebrew locations
            String[] brewPaths = {"/opt/homebrew/lib", "/usr/local/lib"};
            for (String path : brewPaths) {
                System.out.println("  Checking " + path + ":");
                checkLibrariesInPath(path, getSDLLibraryNames());
            }
            
        } else if (Platform.isLinux()) {
            System.out.println("Linux-specific checks:");
            
            // Check common locations
            String[] linuxPaths = {"/usr/lib", "/usr/local/lib", "/usr/lib/x86_64-linux-gnu"};
            for (String path : linuxPaths) {
                File dir = new File(path);
                if (dir.exists()) {
                    System.out.println("  Checking " + path + ":");
                    checkLibrariesInPath(path, getSDLLibraryNames());
                }
            }
            
            // Check ldconfig
            System.out.println("\n  Running ldconfig check:");
            try {
                Process p = Runtime.getRuntime().exec("ldconfig -p");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream()));
                String line;
                boolean foundSDL = false;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("SDL2")) {
                        System.out.println("    ✓ " + line.trim());
                        foundSDL = true;
                    }
                }
                if (!foundSDL) {
                    System.out.println("    ✗ No SDL2 libraries found in ldconfig cache");
                }
            } catch (Exception e) {
                System.out.println("    Could not run ldconfig: " + e.getMessage());
            }
        }
    }
    
    private static void tryLoadLibrary(String libName) {
        try {
            System.out.print("  Loading " + libName + "... ");
            Native.load(libName, com.sun.jna.Library.class);
            System.out.println("✓ SUCCESS");
        } catch (UnsatisfiedLinkError e) {
            System.out.println("✗ FAILED");
            System.out.println("    Error: " + e.getMessage());
        }
    }
    
    private static void provideSuggestions() {
        if (Platform.isWindows()) {
            System.out.println("For Windows:");
            System.out.println("1. Download SDL2 and SDL2_mixer development libraries");
            System.out.println("2. Extract SDL2.dll and SDL2_mixer.dll to your project directory");
            System.out.println("3. Make sure to use the correct architecture (x64 vs x86)");
            System.out.println("4. Install Visual C++ Redistributables if missing");
            
        } else if (Platform.isMac()) {
            System.out.println("For macOS:");
            System.out.println("1. Install with Homebrew: brew install sdl2 sdl2_mixer");
            System.out.println("2. For Apple Silicon, add to JVM args: -Djna.library.path=/opt/homebrew/lib");
            System.out.println("3. For Intel Macs, add: -Djna.library.path=/usr/local/lib");
            System.out.println("4. If blocked by security, allow in System Preferences");
            
        } else if (Platform.isLinux()) {
            System.out.println("For Linux:");
            System.out.println("1. Install packages: sudo apt-get install libsdl2-2.0-0 libsdl2-mixer-2.0-0");
            System.out.println("2. For development: sudo apt-get install libsdl2-dev libsdl2-mixer-dev");
            System.out.println("3. Run: sudo ldconfig");
            System.out.println("4. If in custom location, add: -Djna.library.path=/path/to/libs");
        }
        
        System.out.println("\nTo run with custom library path:");
        System.out.println("  ./gradlew desktop:run -Plibpath=/path/to/libraries");
    }
}