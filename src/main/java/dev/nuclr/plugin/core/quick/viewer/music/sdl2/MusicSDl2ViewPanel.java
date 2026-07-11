package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.FileSystemException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import javax.swing.UIManager;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import sdl2.AudioRingBuffer;
import sdl2.SDLMixerAudio;
import sdl2.Sdl2Support;

@Data
@Slf4j
public class MusicSDl2ViewPanel extends JPanel {

	private Color bgColor;
	private Color accentColor;
	private Color textPrimary;
	private Color textSecondary;
	private Color trackBg;
	private Color buttonBg;
	private Color buttonHover;
	
	private BufferedImage image;

	public static final Set<String> allowedExtensions = Set.of(
			"wav", "flac", "aac", "voc", "aiff", "mid",
			"ogg", "mp3", "xm", "mod", "s3m", "it", "669");
	private static final Set<String> moduleExtensions = Set.of("xm", "mod", "s3m", "it", "669");

	public static SDLMixerAudio TrackerMusic;
	private static AudioRingBuffer audioRingBuffer;

	private NuclrResource currentFile;
	private Path stagedFile;
	private Timer updateTimer;
	private WaveformPanel waveformPanel;

	// UI components
	private JLabel trackNameLabel;
	private JLabel trackInfoLabel;
	private JLabel currentTimeLabel;
	private JLabel totalTimeLabel;
	private ProgressBar progressBar;
	private JButton playPauseButton;
	private JButton stopButton;
	private JButton rewindButton;
	private JButton forwardButton;
	private JSlider volumeSlider;
	private JLabel volumeLabel;
	private JCheckBox loopCheckBox;

	/** Whether playback loops indefinitely. Defaults to on. */
	private boolean loopEnabled = true;

	public MusicSDl2ViewPanel() {
		bgColor       = uiColor("Panel.background",          new Color(0x14, 0x17, 0x1F));
		accentColor   = uiColor("Component.accentColor",     new Color(0x4E, 0x9A, 0xE1));
		textPrimary   = uiColor("Label.foreground",          new Color(0xD2, 0xDA, 0xE8));
		textSecondary = uiColor("Label.disabledForeground",  new Color(0x8B, 0x96, 0xA8));
		trackBg       = uiColor("Component.borderColor",     new Color(0x2B, 0x31, 0x3D));
		buttonBg      = uiColor("Button.background",          new Color(0x1F, 0x25, 0x31));
		buttonHover   = uiColor("Button.hoverBackground",     new Color(0x2B, 0x33, 0x42));
		setLayout(new BorderLayout());
		setBackground(bgColor);
		buildUI();
		startUpdateTimer();
	}

	private static Color uiColor(String key, Color fallback) {
		Color c = UIManager.getColor(key);
		return c != null ? c : fallback;
	}

	private void buildUI() {
		// ---- Top: Waveform visualizer + track info ----
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(bgColor);
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

		waveformPanel = new WaveformPanel();
		waveformPanel.setPreferredSize(new Dimension(100, 120));

		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(bgColor);
		infoPanel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		trackNameLabel = new JLabel("No track loaded");
		trackNameLabel.setFont(new Font("JetBrains Mono", Font.BOLD, 14));
		trackNameLabel.setForeground(textPrimary);
		trackNameLabel.setAlignmentX(CENTER_ALIGNMENT);
		trackNameLabel.setHorizontalAlignment(SwingConstants.CENTER);

		trackInfoLabel = new JLabel(" ");
		trackInfoLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
		trackInfoLabel.setForeground(textSecondary);
		trackInfoLabel.setAlignmentX(CENTER_ALIGNMENT);
		trackInfoLabel.setHorizontalAlignment(SwingConstants.CENTER);

		infoPanel.add(trackNameLabel);
		infoPanel.add(Box.createVerticalStrut(2));
		infoPanel.add(trackInfoLabel);

		topPanel.add(waveformPanel, BorderLayout.CENTER);
		topPanel.add(infoPanel, BorderLayout.SOUTH);

		// ---- Bottom: Controls ----
		JPanel controlsPanel = new JPanel();
		controlsPanel.setLayout(new BoxLayout(controlsPanel, BoxLayout.Y_AXIS));
		controlsPanel.setBackground(bgColor);
		controlsPanel.setBorder(BorderFactory.createEmptyBorder(0, 20, 16, 20));

		// Progress bar
		progressBar = new ProgressBar();
		progressBar.setAlignmentX(CENTER_ALIGNMENT);
		progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
		progressBar.setPreferredSize(new Dimension(100, 14));

		// Time labels
		JPanel timePanel = new JPanel(new BorderLayout());
		timePanel.setBackground(bgColor);
		timePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

		currentTimeLabel = new JLabel("0:00");
		currentTimeLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
		currentTimeLabel.setForeground(textSecondary);

		totalTimeLabel = new JLabel("0:00");
		totalTimeLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
		totalTimeLabel.setForeground(textSecondary);

		timePanel.add(currentTimeLabel, BorderLayout.WEST);
		timePanel.add(totalTimeLabel, BorderLayout.EAST);

		// Buttons
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
		buttonPanel.setBackground(bgColor);
		buttonPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));

		rewindButton = createControlButton("\u23EE", "Rewind 10s");
		playPauseButton = createControlButton("\u25B6", "Play");
		stopButton = createControlButton("\u25A0", "Stop");
		forwardButton = createControlButton("\u23ED", "Forward 10s");

		playPauseButton.setPreferredSize(new Dimension(44, 36));
		playPauseButton.setFont(playPauseButton.getFont().deriveFont(16f));

		rewindButton.addActionListener(e -> onRewind());
		playPauseButton.addActionListener(e -> onPlayPause());
		stopButton.addActionListener(e -> onStop());
		forwardButton.addActionListener(e -> onForward());

		buttonPanel.add(rewindButton);
		buttonPanel.add(playPauseButton);
		buttonPanel.add(stopButton);
		buttonPanel.add(forwardButton);

		// Volume
		JPanel volumePanel = new JPanel(new GridBagLayout());
		volumePanel.setBackground(bgColor);
		volumePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(0, 0, 0, 6);
		gbc.gridy = 0;

		JLabel volIcon = new JLabel("\u266A");
		volIcon.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 13));
		volIcon.setForeground(textSecondary);
		gbc.gridx = 0;
		gbc.weightx = 0;
		volumePanel.add(volIcon, gbc);

		volumeSlider = new JSlider(0, 100, 70);
		volumeSlider.setBackground(bgColor);
		volumeSlider.setForeground(accentColor);
		volumeSlider.setPreferredSize(new Dimension(140, 20));
		volumeSlider.setFocusable(false);
		volumeSlider.addChangeListener(e -> {
			float vol = volumeSlider.getValue() / 100f;
			if (TrackerMusic != null) {
				TrackerMusic.setVolume(vol);
			}
			volumeLabel.setText(volumeSlider.getValue() + "%");
		});

		gbc.gridx = 1;
		gbc.weightx = 1;
		gbc.fill = GridBagConstraints.HORIZONTAL;
		volumePanel.add(volumeSlider, gbc);

		volumeLabel = new JLabel("70%");
		volumeLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
		volumeLabel.setForeground(textSecondary);
		volumeLabel.setPreferredSize(new Dimension(38, 16));
		gbc.gridx = 2;
		gbc.weightx = 0;
		gbc.fill = GridBagConstraints.NONE;
		volumePanel.add(volumeLabel, gbc);

		loopCheckBox = new JCheckBox("Loop", loopEnabled);
		loopCheckBox.setToolTipText("Repeat the track when it finishes");
		loopCheckBox.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
		loopCheckBox.setForeground(textSecondary);
		loopCheckBox.setBackground(bgColor);
		loopCheckBox.setFocusable(false);
		loopCheckBox.addActionListener(e -> loopEnabled = loopCheckBox.isSelected());
		gbc.gridx = 3;
		gbc.insets = new Insets(0, 14, 0, 0);
		volumePanel.add(loopCheckBox, gbc);

		// Assemble controls
		controlsPanel.add(progressBar);
		controlsPanel.add(Box.createVerticalStrut(4));
		controlsPanel.add(timePanel);
		controlsPanel.add(Box.createVerticalStrut(10));
		controlsPanel.add(buttonPanel);
		controlsPanel.add(Box.createVerticalStrut(8));
		controlsPanel.add(volumePanel);

		add(topPanel, BorderLayout.CENTER);
		add(controlsPanel, BorderLayout.SOUTH);
	}

	private JButton createControlButton(String text, String tooltip) {
		JButton btn = new JButton(text);
		btn.setToolTipText(tooltip);
		btn.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 14));
		btn.setForeground(textPrimary);
		btn.setBackground(buttonBg);
		btn.setFocusPainted(false);
		btn.setBorderPainted(false);
		btn.setContentAreaFilled(true);
		btn.setPreferredSize(new Dimension(38, 36));
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				btn.setBackground(buttonHover);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				btn.setBackground(buttonBg);
			}
		});
		return btn;
	}

	// ---- Playback actions ----

	/** Loop count to pass to SDL_mixer: -1 for infinite, 0 to play once. */
	private int loops() {
		return loopEnabled ? -1 : 0;
	}

	private void onPlayPause() {
		if (TrackerMusic == null) return;

		if (TrackerMusic.isPaused()) {
			TrackerMusic.resumeMusic();
		} else if (TrackerMusic.isPlaying()) {
			TrackerMusic.pauseMusic();
		} else if (currentFile != null) {
			try {
				var file = ensureLoadableFile(currentFile, null);
				TrackerMusic.loadMusic(file.toFile());
				TrackerMusic.playMusic(loops());
			} catch (Exception e) {
				log.error("Failed to restart music: {}", e.getMessage(), e);
			}
		}
		updatePlayPauseIcon();
	}

	private void onStop() {
		if (TrackerMusic != null && (TrackerMusic.isPlaying() || TrackerMusic.isPaused())) {
			TrackerMusic.stopMusic();
		}
		updatePlayPauseIcon();
	}

	private void onRewind() {
		if (TrackerMusic == null) return;
		double pos = TrackerMusic.getMusicPosition();
		if (pos > 0) {
			TrackerMusic.setPosition(Math.max(0, pos - 10));
		}
	}

	private void onForward() {
		if (TrackerMusic == null) return;
		double pos = TrackerMusic.getMusicPosition();
		double dur = TrackerMusic.getMusicDuration();
		if (pos >= 0 && dur > 0) {
			TrackerMusic.setPosition(Math.min(dur - 0.5, pos + 10));
		}
	}

	private void updatePlayPauseIcon() {
		if (TrackerMusic != null && TrackerMusic.isPlaying() && !TrackerMusic.isPaused()) {
			playPauseButton.setText("\u23F8");
			playPauseButton.setToolTipText("Pause");
		} else {
			playPauseButton.setText("\u25B6");
			playPauseButton.setToolTipText("Play");
		}
	}

	// ---- Progress timer ----

	private void startUpdateTimer() {
		updateTimer = new Timer(250, e -> updateProgress());
		updateTimer.start();
	}

	private void updateProgress() {
		if (TrackerMusic == null) return;

		double pos = TrackerMusic.getMusicPosition();
		double dur = TrackerMusic.getMusicDuration();
		waveformPanel.setPlaybackPositionSeconds(pos >= 0 ? pos : 0);

		if (pos >= 0 && dur > 0) {
			progressBar.setProgress(pos / dur);
			currentTimeLabel.setText(formatTime(pos));
			totalTimeLabel.setText(formatTime(dur));
		} else {
			progressBar.setProgress(0);
		}

		updatePlayPauseIcon();
	}

	private static String formatTime(double seconds) {
		if (seconds < 0) return "0:00";
		int totalSec = (int) Math.round(seconds);
		int min = totalSec / 60;
		int sec = totalSec % 60;
		return String.format("%d:%02d", min, sec);
	}

	// ---- Public API ----
	public boolean load(NuclrResource item, AtomicBoolean cancelled) {
		if (cancelled.get()) return false;

		this.currentFile = item;
		Path file = null;

		// SDL2 ships with the plugin on Windows but is a system package on Linux and macOS.
		// Check before touching SDLMixerAudio: loading it without the native libraries fails
		// inside a static initializer, which no catch below could report usefully.
		if (!Sdl2Support.isAvailable()) {
			Sdl2Support.showMissingLibraryDialog(this);
			trackNameLabel.setText("SDL2 audio libraries missing");
			trackInfoLabel.setText(Sdl2Support.shortHint());
			waveformPanel.clearTrackerBackdrop();
			return false;
		}

		try {

			if (TrackerMusic != null) {
				TrackerMusic.unloadMusic();
			} else {
				TrackerMusic = new SDLMixerAudio();
				audioRingBuffer = new AudioRingBuffer(44100); // ~1 second at 44.1kHz
				TrackerMusic.enableVisualizer(audioRingBuffer);
				waveformPanel.setRingBuffer(audioRingBuffer);
			}

			if (cancelled.get()) return false;

			file = ensureLoadableFile(currentFile, cancelled);
			audioRingBuffer.clear();
			TrackerMusic.loadMusic(file.toFile());
			if (cancelled.get()) {
				TrackerMusic.stopMusic();
				return false;
			}
			TrackerMusic.playMusic(loops());

			// Show the original resource's name, not the staged temp file's name
			// (which is "nuclr-music-preview-…").
			String name = displayName(item, file);
			String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : "";
			String extUpper = ext.toUpperCase();
			trackNameLabel.setText(name);
			trackInfoLabel.setText(extUpper + " audio");
			progressBar.setProgress(0);
			currentTimeLabel.setText("0:00");
			totalTimeLabel.setText("0:00");
			waveformPanel.setPlaybackPositionSeconds(0);
			updateTrackerBackdrop(name, ext);

			float vol = TrackerMusic.getVolume();
			volumeSlider.setValue(Math.round(vol * 100));

			updatePlayPauseIcon();

		} catch (LinkageError e) {
			// SDL2 is present but unusable (wrong ABI, half-installed, missing codec dependency):
			// it surfaces as an ExceptionInInitializerError/NoClassDefFoundError from the JNA
			// interfaces, which is an Error and would otherwise sail past the catch below.
			log.error("SDL2 native libraries could not be initialised", e);
			Sdl2Support.showMissingLibraryDialog(this);
			trackNameLabel.setText("SDL2 audio libraries missing");
			trackInfoLabel.setText(Sdl2Support.shortHint());
			waveformPanel.clearTrackerBackdrop();
			return false;
		} catch (Exception e) {
			log.error("Failed to read music file: {}", file != null ? file.toAbsolutePath() : currentFile, e);
			trackNameLabel.setText("Error loading file");
			trackInfoLabel.setText(e.getMessage());
			waveformPanel.clearTrackerBackdrop();
			return false;
		}
		
		return true;
	}

	/**
	 * The name to display for a track: the original resource's name, falling back to
	 * the staged file only if the resource has none.
	 */
	private static String displayName(NuclrResource item, Path stagedFallback) {
		if (item != null && item.getName() != null && !item.getName().isBlank()) {
			return item.getName();
		}
		if (stagedFallback != null) {
			return stagedFallback.getFileName() != null
					? stagedFallback.getFileName().toString()
					: stagedFallback.toString();
		}
		return "";
	}

	public void stopMusic() {
		if (TrackerMusic != null) {
			TrackerMusic.stopMusic();
		}
		if (audioRingBuffer != null) {
			audioRingBuffer.clear();
		}
		updatePlayPauseIcon();
	}

	// ---- Custom progress bar ----

	private class ProgressBar extends JPanel {

		private double progress = 0;
		private boolean hovering = false;
		private boolean dragging = false;

		ProgressBar() {
			setOpaque(false);
			setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			MouseAdapter mouse = new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (TrackerMusic == null) return;
					dragging = true;
					seekToMouse(e.getX());
				}

				@Override
				public void mouseReleased(MouseEvent e) {
					dragging = false;
				}

				@Override
				public void mouseDragged(MouseEvent e) {
					if (dragging) {
						seekToMouse(e.getX());
					}
				}

				@Override
				public void mouseEntered(MouseEvent e) {
					hovering = true;
					repaint();
				}

				@Override
				public void mouseExited(MouseEvent e) {
					hovering = false;
					repaint();
				}
			};
			addMouseListener(mouse);
			addMouseMotionListener(mouse);
		}

		private void seekToMouse(int mouseX) {
			if (TrackerMusic == null) return;
			double dur = TrackerMusic.getMusicDuration();
			if (dur <= 0) return;

			double ratio = Math.max(0, Math.min(1, (double) mouseX / getWidth()));
			TrackerMusic.setPosition(ratio * dur);
			progress = ratio;
			repaint();
		}

		void setProgress(double p) {
			if (!dragging) {
				this.progress = Math.max(0, Math.min(1, p));
				repaint();
			}
		}

		@Override
		protected void paintComponent(Graphics g) {
			Graphics2D g2 = (Graphics2D) g.create();
			try {
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

				int w = getWidth();
				int h = getHeight();
				int barH = hovering || dragging ? 8 : 4;
				int y = (h - barH) / 2;
				int arc = barH;

				// Background track
				g2.setColor(trackBg);
				g2.fillRoundRect(0, y, w, barH, arc, arc);

				// Filled portion
				int fillW = (int) (w * progress);
				if (fillW > 0) {
					g2.setColor(accentColor);
					g2.fillRoundRect(0, y, fillW, barH, arc, arc);
				}

				// Thumb knob on hover/drag
				if (hovering || dragging) {
					int knobR = 6;
					int cx = Math.max(knobR, Math.min(w - knobR, fillW));
					int cy = h / 2;
					g2.setColor(Color.WHITE);
					g2.fillOval(cx - knobR, cy - knobR, knobR * 2, knobR * 2);
				}
			} finally {
				g2.dispose();
			}
		}
	}

	public void clear() {
		releaseLoadedMusic();
		deleteStagedFile();
		currentFile = null;
		waveformPanel.clearTrackerBackdrop();
		trackNameLabel.setText("No track loaded");
		trackInfoLabel.setText(" ");
		progressBar.setProgress(0);
		currentTimeLabel.setText("0:00");
		totalTimeLabel.setText("0:00");		
	}

	private void releaseLoadedMusic() {
		if (TrackerMusic != null) {
			TrackerMusic.dispose();
		}
		if (audioRingBuffer != null) {
			audioRingBuffer.clear();
		}
		updatePlayPauseIcon();
	}

	private Path ensureLoadableFile(NuclrResource item, AtomicBoolean cancelled) throws Exception {
		// The native SDL_mixer loader requires a real file on the default filesystem,
		// so the resource is always staged to a temp file. Read it through the resource
		// abstraction (openInputStream) rather than its Path, so resources backed by
		// non-file sources (zip entries, remote/in-memory content) work identically.
		deleteStagedFile();
		String suffix = extensionSuffix(item);
		Path tempFile = Files.createTempFile("nuclr-music-preview-", suffix);
		// Safety net: the file is normally deleted eagerly (on the next load and on
		// closeResource), but register it for JVM-shutdown deletion too so it is not
		// left behind if the app exits before cleanup or an eager delete fails.
		tempFile.toFile().deleteOnExit();
		try {
			try (InputStream input = item.openInputStream(); OutputStream output = Files.newOutputStream(tempFile)) {
				copyWithCancellation(input, output, cancelled);
			}
			stagedFile = tempFile;
			return tempFile;
		} catch (Exception e) {
			Files.deleteIfExists(tempFile);
			throw e;
		}
	}

	private void copyWithCancellation(InputStream input, OutputStream output, AtomicBoolean cancelled) throws Exception {
		byte[] buffer = new byte[8192];
		int read;
		while ((read = input.read(buffer)) >= 0) {
			if (cancelled != null && cancelled.get()) {
				throw new InterruptedException("Music load cancelled");
			}
			if (read > 0) {
				output.write(buffer, 0, read);
			}
		}
	}

	private String extensionSuffix(NuclrResource item) {
		String name = item != null ? item.getName() : null;
		String extension = "";
		if (name != null) {
			int dot = name.lastIndexOf('.');
			if (dot >= 0 && dot < name.length() - 1) {
				extension = name.substring(dot + 1);
			}
		}
		if (extension.isBlank()) {
			return ".tmp";
		}
		return extension.startsWith(".") ? extension : "." + extension;
	}

	private void deleteStagedFile() {
		if (stagedFile == null) {
			return;
		}
		Path fileToDelete = stagedFile;
		try {
			deleteWithRetry(fileToDelete);
		} catch (IOException e) {
			log.debug("Failed to delete staged music file {}", fileToDelete, e);
		} finally {
			stagedFile = null;
		}
	}

	private void deleteWithRetry(Path fileToDelete) throws IOException {
		IOException lastFailure = null;
		for (int attempt = 0; attempt < 5; attempt++) {
			try {
				Files.deleteIfExists(fileToDelete);
				return;
			} catch (FileSystemException e) {
				lastFailure = e;
				if (attempt == 4) {
					break;
				}
				try {
					Thread.sleep(25L * (attempt + 1));
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					e.addSuppressed(interrupted);
					throw e;
				}
			}
		}
		throw lastFailure != null ? lastFailure : new IOException("Failed to delete staged music file " + fileToDelete);
	}

	private void updateTrackerBackdrop(String name, String extension) {
		// The demoscene scroller announces every tune, module or not.
		waveformPanel.setTrackTitle(name);
		if (extension != null && moduleExtensions.contains(extension.toLowerCase())) {
			waveformPanel.setTrackerBackdrop(name + ":" + extension.toLowerCase());
		} else {
			waveformPanel.clearTrackerBackdrop();
		}
	}


}
