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
import java.io.File;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingConstants;
import javax.swing.Timer;

import dev.nuclr.plugin.QuickViewItem;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import sdl2.AudioRingBuffer;
import sdl2.SDLMixerAudio;

@Data
@Slf4j
public class MusicSDl2ViewPanel extends JPanel {

	private static final Color BG_COLOR = new Color(0x2B, 0x2B, 0x2B);
	private static final Color ACCENT_COLOR = new Color(0x4E, 0x9A, 0xE1);
	private static final Color TEXT_PRIMARY = new Color(0xBB, 0xBB, 0xBB);
	private static final Color TEXT_SECONDARY = new Color(0x78, 0x78, 0x78);
	private static final Color TRACK_BG = new Color(0x3C, 0x3F, 0x41);
	private static final Color BUTTON_BG = new Color(0x3C, 0x3F, 0x41);
	private static final Color BUTTON_HOVER = new Color(0x4C, 0x50, 0x52);
	
	private BufferedImage image;

	public static final Set<String> allowedExtensions = Set.of(
			"wav", "flac", "aac", "voc", "aiff", "mid",
			"ogg", "mp3", "xm", "mod", "s3m", "it", "669");

	public static SDLMixerAudio TrackerMusic;
	private static AudioRingBuffer audioRingBuffer;

	private QuickViewItem currentFile;
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

	public MusicSDl2ViewPanel() {
		setLayout(new BorderLayout());
		setBackground(BG_COLOR);
		buildUI();
		startUpdateTimer();
	}

	private void buildUI() {
		// ---- Top: Waveform visualizer + track info ----
		JPanel topPanel = new JPanel(new BorderLayout());
		topPanel.setBackground(BG_COLOR);
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

		waveformPanel = new WaveformPanel();
		waveformPanel.setPreferredSize(new Dimension(100, 120));

		JPanel infoPanel = new JPanel();
		infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
		infoPanel.setBackground(BG_COLOR);
		infoPanel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		trackNameLabel = new JLabel("No track loaded");
		trackNameLabel.setFont(new Font("JetBrains Mono", Font.BOLD, 14));
		trackNameLabel.setForeground(TEXT_PRIMARY);
		trackNameLabel.setAlignmentX(CENTER_ALIGNMENT);
		trackNameLabel.setHorizontalAlignment(SwingConstants.CENTER);

		trackInfoLabel = new JLabel(" ");
		trackInfoLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
		trackInfoLabel.setForeground(TEXT_SECONDARY);
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
		controlsPanel.setBackground(BG_COLOR);
		controlsPanel.setBorder(BorderFactory.createEmptyBorder(0, 20, 16, 20));

		// Progress bar
		progressBar = new ProgressBar();
		progressBar.setAlignmentX(CENTER_ALIGNMENT);
		progressBar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 14));
		progressBar.setPreferredSize(new Dimension(100, 14));

		// Time labels
		JPanel timePanel = new JPanel(new BorderLayout());
		timePanel.setBackground(BG_COLOR);
		timePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 20));

		currentTimeLabel = new JLabel("0:00");
		currentTimeLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
		currentTimeLabel.setForeground(TEXT_SECONDARY);

		totalTimeLabel = new JLabel("0:00");
		totalTimeLabel.setFont(new Font("JetBrains Mono", Font.PLAIN, 11));
		totalTimeLabel.setForeground(TEXT_SECONDARY);

		timePanel.add(currentTimeLabel, BorderLayout.WEST);
		timePanel.add(totalTimeLabel, BorderLayout.EAST);

		// Buttons
		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 0));
		buttonPanel.setBackground(BG_COLOR);
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
		volumePanel.setBackground(BG_COLOR);
		volumePanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.insets = new Insets(0, 0, 0, 6);
		gbc.gridy = 0;

		JLabel volIcon = new JLabel("\u266A");
		volIcon.setFont(new Font("Segoe UI Symbol", Font.PLAIN, 13));
		volIcon.setForeground(TEXT_SECONDARY);
		gbc.gridx = 0;
		gbc.weightx = 0;
		volumePanel.add(volIcon, gbc);

		volumeSlider = new JSlider(0, 100, 70);
		volumeSlider.setBackground(BG_COLOR);
		volumeSlider.setForeground(ACCENT_COLOR);
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
		volumeLabel.setForeground(TEXT_SECONDARY);
		volumeLabel.setPreferredSize(new Dimension(38, 16));
		gbc.gridx = 2;
		gbc.weightx = 0;
		gbc.fill = GridBagConstraints.NONE;
		volumePanel.add(volumeLabel, gbc);

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
		btn.setForeground(TEXT_PRIMARY);
		btn.setBackground(BUTTON_BG);
		btn.setFocusPainted(false);
		btn.setBorderPainted(false);
		btn.setContentAreaFilled(true);
		btn.setPreferredSize(new Dimension(38, 36));
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseEntered(MouseEvent e) {
				btn.setBackground(BUTTON_HOVER);
			}

			@Override
			public void mouseExited(MouseEvent e) {
				btn.setBackground(BUTTON_BG);
			}
		});
		return btn;
	}

	// ---- Playback actions ----

	private void onPlayPause() {
		if (TrackerMusic == null) return;

		if (TrackerMusic.isPaused()) {
			TrackerMusic.resumeMusic();
		} else if (TrackerMusic.isPlaying()) {
			TrackerMusic.pauseMusic();
		} else if (currentFile != null) {
			try {
				TrackerMusic.loadMusic(currentFile.path().toFile());
				TrackerMusic.playMusic(-1);
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
	public boolean load(QuickViewItem item) {
		
		this.currentFile = item;
		var file = currentFile.path().toFile();
		
		try {
			
			if (TrackerMusic != null) {
				TrackerMusic.stopMusic();
			} else {
				TrackerMusic = new SDLMixerAudio();
				audioRingBuffer = new AudioRingBuffer(44100); // ~1 second at 44.1kHz
				TrackerMusic.enableVisualizer(audioRingBuffer);
				waveformPanel.setRingBuffer(audioRingBuffer);
			}
			

			audioRingBuffer.clear();
			TrackerMusic.loadMusic(file);
			TrackerMusic.playMusic(-1);

			String name = file.getName();
			String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toUpperCase() : "";
			trackNameLabel.setText(name);
			trackInfoLabel.setText(ext + " audio");
			progressBar.setProgress(0);
			currentTimeLabel.setText("0:00");
			totalTimeLabel.setText("0:00");

			float vol = TrackerMusic.getVolume();
			volumeSlider.setValue(Math.round(vol * 100));

			updatePlayPauseIcon();

		} catch (Exception e) {
			log.error("Failed to read music file: {}", file.getAbsolutePath(), e);
			trackNameLabel.setText("Error loading file");
			trackInfoLabel.setText(e.getMessage());
			return false;
		}
		
		return true;
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
				g2.setColor(TRACK_BG);
				g2.fillRoundRect(0, y, w, barH, arc, arc);

				// Filled portion
				int fillW = (int) (w * progress);
				if (fillW > 0) {
					g2.setColor(ACCENT_COLOR);
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
		stopMusic();
		currentFile = null;
		trackNameLabel.setText("No track loaded");
		trackInfoLabel.setText(" ");
		progressBar.setProgress(0);
		currentTimeLabel.setText("0:00");
		totalTimeLabel.setText("0:00");		
	}


}
