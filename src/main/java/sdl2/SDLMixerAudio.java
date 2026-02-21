package sdl2;

import java.io.File;

import javax.sound.sampled.AudioFormat;

import com.sun.jna.Callback;
import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import lombok.extern.slf4j.Slf4j;

/**
 * SDL_Mixer integration for LibGDX to play XM, MOD, S3M, IT tracker files
 */
@Slf4j
public class SDLMixerAudio {

	private int audioDevice = 0;
	private AudioFormat format;
	    
    // SDL2 library interface (separate from SDL2_mixer)
    public interface SDL2 extends Library {
        SDL2 INSTANCE = Native.load("SDL2", SDL2.class);
        
        // SDL structures
        @Structure.FieldOrder({"freq", "format", "channels", "silence", 
                             "samples", "size", "callback", "userdata"})
        class SDL_AudioSpec extends Structure {
            public int freq;
            public short format;
            public byte channels;
            public byte silence;
            public short samples;
            public int size;
            public SDL_AudioCallback callback;
            public Pointer userdata;
            
            public static class ByReference extends SDL_AudioSpec implements Structure.ByReference {}
        }
        
        interface SDL_AudioCallback extends Callback {
            void callback(Pointer userdata, Pointer stream, int len);
        }
            
        int SDL_Init(int flags);
        void SDL_Quit();
        String SDL_GetError();
        
        int SDL_OpenAudioDevice(String device, int iscapture, 
                SDL_AudioSpec.ByReference desired,
                SDL_AudioSpec.ByReference obtained, 
                int allowed_changes);
		void SDL_CloseAudioDevice(int dev);
		void SDL_PauseAudioDevice(int dev, int pause_on);
		int SDL_QueueAudio(int dev, Pointer data, int len);
		int SDL_GetQueuedAudioSize(int dev);
		void SDL_ClearQueuedAudio(int dev);
		void SDL_Delay(int ms);        
    }
    
    // SDL_Mixer library interface
    public interface SDLMixer extends Library {
        SDLMixer INSTANCE = Native.load("SDL2_mixer", SDLMixer.class);
        
        // SDL_Mixer functions
        int Mix_Init(int flags);
        void Mix_Quit();
        int Mix_OpenAudio(int frequency, short format, int channels, int chunksize);
        void Mix_CloseAudio();
        Pointer Mix_LoadMUS(String file);
        Pointer Mix_LoadMUS_RW(Pointer src, int freesrc);
        void Mix_FreeMusic(Pointer music);
        int Mix_PlayMusic(Pointer music, int loops);
        int Mix_HaltMusic();
        int Mix_PauseMusic();
        void Mix_ResumeMusic();
        int Mix_PlayingMusic();
        int Mix_PausedMusic();
        int Mix_SetMusicPosition(double position);
        int Mix_VolumeMusic(int volume);
        int Mix_FadeInMusic(Pointer music, int loops, int ms);
        int Mix_FadeOutMusic(int ms);
        double Mix_GetMusicPosition(Pointer music);
        double Mix_MusicDuration(Pointer music);
        int Mix_GetMusicType(Pointer music);
        void Mix_SetPostMix(MixCallback mix_func, Pointer arg);

        interface MixCallback extends Callback {
            void invoke(Pointer udata, Pointer stream, int len);
        }
    }
    
    // Constants
    private static final int SDL_INIT_AUDIO = 0x00000010;
    private static final int MIX_DEFAULT_FREQUENCY = 44100;
    private static final short MIX_DEFAULT_FORMAT = (short) 0x8010; // AUDIO_S16SYS
    private static final int MIX_DEFAULT_CHANNELS = 2;
    private static final int MIX_MAX_VOLUME = 128;
    private static final int SDL_AUDIO_ALLOW_ANY_CHANGE = 0x0F;
    
    private static boolean sdlInitialized = false;
    private Pointer currentMusic = null;
    private String currentFile = null;

    // Visualizer support: postmix callback feeds PCM into a ring buffer
    private SDLMixer.MixCallback postMixCallback; // strong ref prevents GC
    private AudioRingBuffer visualizerBuffer;
    private byte[] rawPcmBuffer = new byte[16384];
    private float[] monoConvBuffer = new float[4096];
    
    public SDLMixerAudio() {
        initializeSDL();
    }
    
    private synchronized void initializeSDL() {
        if (!sdlInitialized) {
            try {
                // Initialize SDL audio subsystem
                if (SDL2.INSTANCE.SDL_Init(SDL_INIT_AUDIO) < 0) {
                    throw new RuntimeException("Failed to initialize SDL: " + 
                        SDL2.INSTANCE.SDL_GetError());
                }
            } catch (UnsatisfiedLinkError e) {
                throw new RuntimeException(
                    "SDL2 library not found. Please install SDL2:\n" +
                    "  Linux: sudo apt-get install libsdl2-dev\n" +
                    "  macOS: brew install sdl2\n" +
                    "  Windows: Place SDL2.dll in project directory\n" +
                    "Original error: " + e.getMessage(), e);
            }
            
            // Initialize SDL_Mixer
            SDLMixer.INSTANCE.Mix_Init(0);
            
            // Open audio device
            if (SDLMixer.INSTANCE.Mix_OpenAudio(
                    MIX_DEFAULT_FREQUENCY,
                    MIX_DEFAULT_FORMAT,
                    MIX_DEFAULT_CHANNELS,
                    2048) < 0) {
                throw new RuntimeException("Failed to open audio: " + 
                    SDL2.INSTANCE.SDL_GetError());
            }
            
            sdlInitialized = true;
            
            // Set initial volume to 70%
            setVolume(0.7f);
        }
    }
    
    /**
     * Load a tracker file (XM, MOD, S3M, IT)
     * @param file LibGDX FileHandle to the tracker file
     */
    public void loadMusic(File file) {
        if (currentMusic != null) {
            stopMusic();
            SDLMixer.INSTANCE.Mix_FreeMusic(currentMusic);
            currentMusic = null;
        }
        
        currentFile = file.getAbsolutePath();
        currentMusic = SDLMixer.INSTANCE.Mix_LoadMUS(currentFile);
        
        if (currentMusic == null) {
            throw new RuntimeException("Failed to load music: " + 
                SDL2.INSTANCE.SDL_GetError());
        }
    }
    
    /**
     * Load a tracker file from a path
     * @param filePath Path to the tracker file
     */
    public void loadMusic(String filePath) {
        loadMusic(new File(filePath));
    }
    
    /**
     * Play the loaded music
     * @param loops Number of times to play (-1 for infinite)
     */
    public void playMusic(int loops) {
        if (currentMusic == null) {
            throw new IllegalStateException("No music loaded");
        }
        
        if (SDLMixer.INSTANCE.Mix_PlayMusic(currentMusic, loops) < 0) {
            throw new RuntimeException("Failed to play music: " + 
                SDL2.INSTANCE.SDL_GetError());
        }
    }
    
    /**
     * Play the loaded music once
     */
    public void playMusic() {
        playMusic(0);
    }
    
    /**
     * Play the loaded music on loop
     */
    public void loopMusic() {
        playMusic(-1);
    }
    
    /**
     * Stop the music
     */
    public void stopMusic() {
        SDLMixer.INSTANCE.Mix_HaltMusic();
    }
    
    /**
     * Pause the music
     */
    public void pauseMusic() {
        SDLMixer.INSTANCE.Mix_PauseMusic();
    }
    
    /**
     * Resume the music
     */
    public void resumeMusic() {
        SDLMixer.INSTANCE.Mix_ResumeMusic();
    }
    
    /**
     * Check if music is playing
     */
    public boolean isPlaying() {
        return SDLMixer.INSTANCE.Mix_PlayingMusic() != 0;
    }
    
    /**
     * Check if music is paused
     */
    public boolean isPaused() {
        return SDLMixer.INSTANCE.Mix_PausedMusic() != 0;
    }
    
    /**
     * Set music volume
     * @param volume Volume from 0.0 to 1.0
     */
    public void setVolume(float volume) {
        int sdlVolume = (int)(Math.max(0, Math.min(1, volume)) * MIX_MAX_VOLUME);
        SDLMixer.INSTANCE.Mix_VolumeMusic(sdlVolume);
    }
    
    /**
     * Fade in music
     * @param loops Number of times to play (-1 for infinite)
     * @param fadeMs Milliseconds to fade in
     */
    public void fadeIn(int loops, int fadeMs) {
        if (currentMusic == null) {
            throw new IllegalStateException("No music loaded");
        }
        
        if (SDLMixer.INSTANCE.Mix_FadeInMusic(currentMusic, loops, fadeMs) < 0) {
            throw new RuntimeException("Failed to fade in music: " + 
                SDL2.INSTANCE.SDL_GetError());
        }
    }
    
    /**
     * Fade out music
     * @param fadeMs Milliseconds to fade out
     */
    public void fadeOut(int fadeMs) {
        SDLMixer.INSTANCE.Mix_FadeOutMusic(fadeMs);
    }
    
    /**
     * Set music position (for MOD/XM files)
     * @param position Position in seconds
     */
    public void setPosition(double position) {
        if (SDLMixer.INSTANCE.Mix_SetMusicPosition(position) < 0) {
            log.error("SDLMixer", "Failed to set position: " +
                SDL2.INSTANCE.SDL_GetError());
        }
    }

    /**
     * Get the current playback position in seconds.
     * @return position in seconds, or -1.0 if not supported
     */
    public double getMusicPosition() {
        if (currentMusic == null) return -1.0;
        return SDLMixer.INSTANCE.Mix_GetMusicPosition(currentMusic);
    }

    /**
     * Get the total duration of the loaded music in seconds.
     * @return duration in seconds, or -1.0 if not supported
     */
    public double getMusicDuration() {
        if (currentMusic == null) return -1.0;
        return SDLMixer.INSTANCE.Mix_MusicDuration(currentMusic);
    }

    /**
     * Get the current volume level.
     * @return volume from 0.0 to 1.0
     */
    public float getVolume() {
        int vol = SDLMixer.INSTANCE.Mix_VolumeMusic(-1);
        return vol / (float) MIX_MAX_VOLUME;
    }
    
    /**
     * Register a Mix_SetPostMix callback that converts S16 stereo PCM
     * to mono float and writes into the given ring buffer.
     * The callback runs on the SDL audio thread; the ring buffer is
     * designed for lock-free single-producer / single-consumer access.
     */
    public void enableVisualizer(AudioRingBuffer buffer) {
        this.visualizerBuffer = buffer;

        this.postMixCallback = (udata, stream, len) -> {
            if (len > rawPcmBuffer.length) {
                rawPcmBuffer = new byte[len];
            }
            int frames = len / 4; // S16 stereo = 4 bytes per frame
            if (frames > monoConvBuffer.length) {
                monoConvBuffer = new float[frames];
            }

            // Bulk-read raw PCM bytes (one JNA call instead of per-sample)
            stream.read(0, rawPcmBuffer, 0, len);

            // Convert interleaved S16LE stereo to mono float [-1..1]
            for (int i = 0; i < frames; i++) {
                int off = i * 4;
                short left  = (short) ((rawPcmBuffer[off]     & 0xFF) | (rawPcmBuffer[off + 1] << 8));
                short right = (short) ((rawPcmBuffer[off + 2] & 0xFF) | (rawPcmBuffer[off + 3] << 8));
                monoConvBuffer[i] = (left + right) / 65536.0f;
            }

            buffer.write(monoConvBuffer, 0, frames);
        };

        SDLMixer.INSTANCE.Mix_SetPostMix(postMixCallback, null);
    }

    public void disableVisualizer() {
        SDLMixer.INSTANCE.Mix_SetPostMix(null, null);
        this.postMixCallback = null;
        this.visualizerBuffer = null;
    }

    public void dispose() {
        if (currentMusic != null) {
            stopMusic();
            SDLMixer.INSTANCE.Mix_FreeMusic(currentMusic);
            currentMusic = null;
        }
        
        // Note: We don't close SDL_Mixer here as other instances might be using it
        // In a real application, you might want to reference count or use a singleton
    }
    
    /**
     * Shutdown SDL_Mixer completely (call on application exit)
     */
    public static void shutdown() {
        if (sdlInitialized) {
            SDLMixer.INSTANCE.Mix_CloseAudio();
            SDLMixer.INSTANCE.Mix_Quit();
            SDL2.INSTANCE.SDL_Quit();
            sdlInitialized = false;
        }
    }
    
    /**
     * Open audio device with the specified format
     */
    public void openAudio(AudioFormat audioFormat) {
        this.format = audioFormat;
        
        SDL2.SDL_AudioSpec.ByReference desired = new SDL2.SDL_AudioSpec.ByReference();
        SDL2.SDL_AudioSpec.ByReference obtained = new SDL2.SDL_AudioSpec.ByReference();
        
        // Set desired audio format
        desired.freq = (int) audioFormat.getSampleRate();
        desired.format = MIX_DEFAULT_FORMAT; // 16-bit signed samples
        desired.channels = (byte) audioFormat.getChannels();
        desired.samples = 4096; // Buffer size
        desired.callback = null; // Using queue audio instead of callback
        
        // Open audio device
        audioDevice = SDL2.INSTANCE.SDL_OpenAudioDevice(
            null, // Default device
            0,    // Not capture
            desired,
            obtained,
            SDL_AUDIO_ALLOW_ANY_CHANGE
        );
        
        if (audioDevice == 0) {
            throw new RuntimeException("Failed to open audio device: " + 
                SDL2.INSTANCE.SDL_GetError());
        }
        
        // Start audio playback
        SDL2.INSTANCE.SDL_PauseAudioDevice(audioDevice, 0);
    }
    
    /**
     * Clear any queued audio
     */
    public void clearAudio() {
        if (audioDevice != 0) {
            SDL2.INSTANCE.SDL_ClearQueuedAudio(audioDevice);
        }
    }
    
    /**
     * Close audio device
     */
    public void closeAudio() {
        if (audioDevice != 0) {
            SDL2.INSTANCE.SDL_CloseAudioDevice(audioDevice);
            audioDevice = 0;
        }
    }
}