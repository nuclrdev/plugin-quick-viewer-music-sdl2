package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.Random;

import sdl2.AudioRingBuffer;

/**
 * "Hyperspace" — a rock-shooting vector arcade game that plays itself to the music.
 * <p>
 * This is not a picture of a game, it is a game: a ship with real momentum, rocks that <b>split
 * into smaller rocks</b> when they are hit, bullets with travel time, saucers that shoot back, and
 * a screen that wraps at every edge. An autopilot flies it — it leads its targets, dodges what is
 * closing on it, and drops into hyperspace when it is out of options. What the music does is pull
 * the trigger: <b>every shot is fired on a kick drum</b>, the thruster burns on the bass, and the
 * rocks drift faster as the track gets busier.
 * <p>
 * The look is a vector monitor pushed through a modern post chain. Geometry is stroked into an
 * ARGB persistence buffer that decays a fixed fraction every frame, so the beam leaves genuine
 * phosphor trails; that buffer is then downsampled and drawn back over itself enlarged, which is a
 * real bloom rather than a stack of fatter strokes. On impact the whole field takes a screen shake
 * and the outlines split into red and cyan for a few frames. Kills stack into a combo multiplier,
 * clearing a wave drops the world into slow motion for half a second, and the score floats up off
 * the wreckage.
 *
 * @see WaveformPanel.VisualizerMode#HYPERSPACE
 */
final class HyperspaceVisualizer {

	// ---- Analysis ----
	private static final int   FFT_SIZE    = 1024;
	private static final int   SAMPLE_RATE = 44100;
	private static final int   BANDS       = 16;
	private static final int   BASS_BINS   = 5;
	private static final float BEAT_FACTOR = 1.28f;
	private static final int   BEAT_HOLD   = 6;

	// ---- World limits ----
	private static final int MAX_ROCKS   = 42;
	private static final int MAX_BULLETS = 26;
	private static final int MAX_PARTS   = 320;
	private static final int ROCK_VERTS  = 11;
	private static final int SHAPES      = 6;

	// ---- Tuning (all in "units", scaled by the panel's short side) ----
	private static final float SHIP_R        = 11f;
	private static final float BULLET_SPEED  = 6.4f;
	private static final int   BULLET_LIFE   = 62;
	private static final float TURN_RATE     = 0.165f;
	private static final float THRUST        = 0.17f;
	private static final float DRAG          = 0.991f;
	private static final float MAX_SPEED     = 4.6f;
	private static final int   FIRE_COOLDOWN = 4;
	private static final int   AUTOFIRE_GAP   = 42;   // never let the screen go quiet for long
	private static final int   COMBO_WINDOW  = 48;

	private static final float[] ROCK_R = { 9f, 17f, 30f };   // by size class 0,1,2
	private static final int[]   ROCK_SCORE = { 100, 50, 20 };

	// ---- Colours ----
	private static final Color SHIP_CORE   = new Color(0xF4, 0xFC, 0xFF);
	private static final Color SHIP_GLOW   = new Color(0x53, 0xE6, 0xFF);
	// Rocks are tinted by size: the little ones run hot pink, the big ones deep indigo, so the
	// field reads as having depth instead of being one flat shade of purple.
	private static final Color[] ROCK_GLOW = {
			new Color(0xFF, 0x5E, 0xC8), new Color(0xA8, 0x72, 0xFF), new Color(0x6C, 0x74, 0xFF)
	};
	private static final Color[] ROCK_CORE = {
			new Color(0xFF, 0xD8, 0xF2), new Color(0xEC, 0xDE, 0xFF), new Color(0xD6, 0xDE, 0xFF)
	};
	private static final Color BULLET_CORE = new Color(0xFF, 0xF0, 0xFA);
	private static final Color BULLET_GLOW = new Color(0xFF, 0x3E, 0xA8);
	private static final Color UFO_GLOW    = new Color(0x4B, 0xFF, 0x9E);
	private static final Color UFO_CORE    = new Color(0xE8, 0xFF, 0xF2);
	private static final Color FLAME_GLOW  = new Color(0xFF, 0x8A, 0x1E);
	private static final Color FLAME_CORE  = new Color(0xFF, 0xE8, 0xB0);
	private static final Color HUD_DIM     = new Color(0x6F, 0x8C, 0xB4);

	// ---- Tables ----
	private final float[] hann    = new float[FFT_SIZE];
	private final int[]   bandBin = new int[BANDS + 1];

	// ---- FFT buffers ----
	private final float[] snapshot = new float[FFT_SIZE];
	private final float[] re       = new float[FFT_SIZE];
	private final float[] im       = new float[FFT_SIZE];

	// ---- Levels ----
	private final float[] bandRaw   = new float[BANDS];
	private final float[] bandLevel = new float[BANDS];
	private float autoGain = 1f;
	private float energy;
	private float bassRaw;
	private float bassMean = 1e-3f;
	private float bass;

	// ---- Beat ----
	private boolean beatNow;
	private int     beatCooldown;

	// ---- Ship ----
	private float   shipX, shipY, shipVx, shipVy, shipAngle;
	private float   thrustGlow;
	private boolean shipAlive = true;
	private int     respawn;
	private int     invulnerable;
	private int     fireCooldown;
	private int     sinceShot;
	private int     hyperFlash;
	private int     lives = 3;
	private int     lockTarget = -1;   // the autopilot commits to one rock; re-picking the nearest
	private int     lockTimer;         // every frame made the nose swing and it never got a shot off

	// ---- Rocks ----
	private final float[] rockX  = new float[MAX_ROCKS];
	private final float[] rockY  = new float[MAX_ROCKS];
	private final float[] rockVx = new float[MAX_ROCKS];
	private final float[] rockVy = new float[MAX_ROCKS];
	private final float[] rockA  = new float[MAX_ROCKS];
	private final float[] rockSpin = new float[MAX_ROCKS];
	private final byte[]  rockSize = new byte[MAX_ROCKS];
	private final byte[]  rockShape = new byte[MAX_ROCKS];
	private final boolean[] rockAlive = new boolean[MAX_ROCKS];

	// ---- Bullets ----
	private final float[] bulX = new float[MAX_BULLETS];
	private final float[] bulY = new float[MAX_BULLETS];
	private final float[] bulVx = new float[MAX_BULLETS];
	private final float[] bulVy = new float[MAX_BULLETS];
	private final int[]   bulLife = new int[MAX_BULLETS];
	private final boolean[] bulHostile = new boolean[MAX_BULLETS];

	// ---- Particles ----
	private final float[] parX = new float[MAX_PARTS];
	private final float[] parY = new float[MAX_PARTS];
	private final float[] parVx = new float[MAX_PARTS];
	private final float[] parVy = new float[MAX_PARTS];
	private final float[] parLife = new float[MAX_PARTS];
	private final float[] parMax = new float[MAX_PARTS];
	private final int[]   parRgb = new int[MAX_PARTS];
	private int parNext;

	// ---- Saucer ----
	private boolean ufoAlive;
	private float   ufoX, ufoY, ufoVx;
	private int     ufoSpawn = 900;
	private int     ufoFire;

	// ---- Score pop-ups ----
	private static final int MAX_POPS = 12;
	private final float[] popX = new float[MAX_POPS];
	private final float[] popY = new float[MAX_POPS];
	private final float[] popLife = new float[MAX_POPS];
	private final String[] popText = new String[MAX_POPS];
	private int popNext;

	// ---- Game state ----
	private int   score;
	private float scoreShown;
	private int   wave = 1;
	private int   combo;
	private int   comboTimer;
	private int   waveBanner;
	private int   gameOver;
	private float shake;
	private float impact;      // drives the chromatic split
	private float timeScale = 1f;
	private int   slowMo;

	// ---- Rock silhouettes ----
	private final float[][] shapeR = new float[SHAPES][ROCK_VERTS];

	// ---- Nebula (two pre-rendered blobs, drifted and blended every frame) ----
	private static final int NEBULA_TEX = 128;
	private final BufferedImage[] nebula = new BufferedImage[2];

	// ---- Starfield ----
	private static final int STARS = 90;
	private final float[] starX = new float[STARS];
	private final float[] starY = new float[STARS];
	private final float[] starZ = new float[STARS];

	// ---- Render buffers ----
	/**
	 * The vector layer renders at half the panel's resolution. Under this much bloom the softness
	 * is invisible, and four full-panel image operations a frame was the entire frame cost.
	 */
	private static final int VEC_SCALE = 2;
	private BufferedImage vec;    // persistence layer, half panel size
	private BufferedImage bloom;  // small copy of it, blown back up for the halo
	private BufferedImage frame;  // half-res opaque composite: stars + bloom + vectors
	private int bufW = -1, bufH = -1;

	// ---- Reused geometry ----
	private final Path2D.Float path = new Path2D.Float();
	private final AffineTransform xform = new AffineTransform();

	// ---- Strokes ----
	private static final BasicStroke HALO = new BasicStroke(7.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke MID  = new BasicStroke(3.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);
	private static final BasicStroke CORE = new BasicStroke(1.25f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

	// ---- Fonts ----
	private Font hudFont, bannerFont, smallFont;
	private int  fontsFor = -1;

	private String trackTitle;
	private int    width = 1, height = 1;
	private float  unit = 1f;

	private final Random rnd = new Random(0xA57E401DL);

	HyperspaceVisualizer() {
		for (int i = 0; i < FFT_SIZE; i++) {
			hann[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT_SIZE - 1)));
		}
		buildBandBins();
		buildShapes();
		buildNebula();
		for (int i = 0; i < STARS; i++) {
			starX[i] = rnd.nextFloat();
			starY[i] = rnd.nextFloat();
			starZ[i] = 0.25f + rnd.nextFloat() * 0.75f;
		}
	}

	void setTrackTitle(String title) {
		trackTitle = title == null || title.isBlank() ? null : title.toUpperCase();
	}

	// =========================================================================
	// Frame
	// =========================================================================

	void render(Graphics2D g2, int w, int h, AudioRingBuffer ring, int frameCount) {

		analyze(ring);
		ensureFonts(h);
		ensureBuffers(w, h);

		boolean resized = w != width || h != height;
		width  = w;
		height = h;
		unit   = Math.max(0.35f, Math.min(w, h) / 400f);
		if (resized || wave == 0) startWave(true);

		// Slow motion after a death or a cleared wave, easing back to full speed.
		if (slowMo > 0) {
			slowMo--;
			timeScale += (0.34f - timeScale) * 0.25f;
		} else {
			timeScale += (1f - timeScale) * 0.09f;
		}
		float dt = timeScale;

		updateShip(dt);
		updateBullets(dt);
		updateRocks(dt);
		updateSaucer(dt);
		updateParticles(dt);
		collisions();
		housekeeping();

		drawVectors(frameCount);
		compose(g2, w, h, frameCount);
		drawHud(g2, w, h);

		shake  *= 0.86f;
		impact *= 0.88f;
		if (beatCooldown > 0) beatCooldown--;
		if (waveBanner > 0) waveBanner--;
		if (gameOver > 0 && --gameOver == 0) restart();
		if (hyperFlash > 0) hyperFlash--;
		if (comboTimer > 0 && --comboTimer == 0) combo = 0;
		scoreShown += (score - scoreShown) * 0.14f;
	}

	// =========================================================================
	// Analysis
	// =========================================================================

	private void analyze(AudioRingBuffer ring) {

		beatNow = false;
		int samples = ring != null ? ring.snapshot(snapshot, FFT_SIZE) : 0;

		if (samples < BANDS * 2) {
			for (int b = 0; b < BANDS; b++) bandLevel[b] *= 0.90f;
			energy += (0f - energy) * 0.08f;
			bass   += (0f - bass) * 0.08f;
			return;
		}

		int pad = FFT_SIZE - samples;
		for (int i = 0; i < FFT_SIZE; i++) {
			float s = i >= pad ? snapshot[i - pad] : 0f;
			re[i] = s * hann[i];
			im[i] = 0f;
		}
		Fft.transform(re, im, FFT_SIZE);

		float maxMag = 1e-4f, sum = 0f;
		for (int b = 0; b < BANDS; b++) {
			float power = 0f;
			for (int k = bandBin[b]; k < bandBin[b + 1]; k++) {
				float p = re[k] * re[k] + im[k] * im[k];
				if (p > power) power = p;
			}
			bandRaw[b] = (float) Math.sqrt(power) / FFT_SIZE * (1f + 1.3f * b / BANDS);
			if (bandRaw[b] > maxMag) maxMag = bandRaw[b];
		}

		float targetGain = clamp(0.85f / maxMag, 0.5f, 60f);
		autoGain += (targetGain - autoGain) * (targetGain < autoGain ? 0.20f : 0.05f);

		for (int b = 0; b < BANDS; b++) {
			float lvl   = clamp((float) Math.pow(bandRaw[b] * autoGain, 0.70), 0f, 1.3f);
			float shown = bandLevel[b];
			bandLevel[b] = lvl > shown ? lvl : shown + (lvl - shown) * 0.22f;
			sum += bandLevel[b];
		}
		energy += (sum / BANDS - energy) * 0.20f;

		// Ratio against a slow running mean, never a clamped level — see SecondHorizonVisualizer.
		float b = 0f;
		for (int k = 1; k <= BASS_BINS; k++) {
			b += (float) Math.sqrt(re[k] * re[k] + im[k] * im[k]) / FFT_SIZE;
		}
		bassRaw += (b - bassRaw) * 0.45f;
		bass = clamp(bassRaw / (bassMean * 2f + 1e-6f), 0f, 1.4f);
		if (bassRaw > bassMean * BEAT_FACTOR && beatCooldown == 0 && bassRaw > 2e-4f) {
			beatNow = true;
			beatCooldown = BEAT_HOLD;
		}
		bassMean += (bassRaw - bassMean) * 0.02f;
	}

	// =========================================================================
	// Ship — the autopilot
	// =========================================================================

	private void updateShip(float dt) {

		if (!shipAlive) {
			if (--respawn <= 0) {
				shipAlive    = true;
				shipX        = width * 0.5f;
				shipY        = height * 0.5f;
				shipVx = shipVy = 0f;
				invulnerable = 110;
			}
			return;
		}
		if (invulnerable > 0) invulnerable--;
		if (fireCooldown > 0) fireCooldown--;

		if (lockTarget >= 0 && (!rockAlive[lockTarget] || --lockTimer <= 0)) lockTarget = -1;
		if (lockTarget < 0) {
			lockTarget = pickTarget();
			lockTimer  = 110;
		}
		int target = lockTarget;
		float want = shipAngle;
		if (target >= 0) {
			want = interceptAngle(rockX[target], rockY[target], rockVx[target], rockVy[target]);
		} else if (ufoAlive) {
			want = interceptAngle(ufoX, ufoY, ufoVx, 0f);
		}

		// Turn towards the firing solution, shortest way round.
		float diff = wrapAngle(want - shipAngle);
		shipAngle += clamp(diff, -TURN_RATE, TURN_RATE) * dt;

		// Threat assessment: anything closing inside the danger radius gets thrust applied away
		// from it, and anything about to land gets answered with hyperspace.
		float dangerX = 0f, dangerY = 0f, worst = 0f;
		for (int i = 0; i < MAX_ROCKS; i++) {
			if (!rockAlive[i]) continue;
			float dx = shortestDx(rockX[i] - shipX, width);
			float dy = shortestDx(rockY[i] - shipY, height);
			float d  = (float) Math.sqrt(dx * dx + dy * dy);
			float safe = (ROCK_R[rockSize[i]] + SHIP_R * 2.6f) * unit;
			if (d < safe * 2.2f) {
				float weight = (safe * 2.2f - d) / (safe * 2.2f);
				dangerX -= dx / Math.max(1f, d) * weight;
				dangerY -= dy / Math.max(1f, d) * weight;
				if (weight > worst) worst = weight;
			}
			if (d < safe * 0.78f && invulnerable == 0) {
				hyperspace();
				return;
			}
		}

		// Incoming saucer fire counts as a threat too, or a crossing would pick the ship off.
		for (int i = 0; i < MAX_BULLETS; i++) {
			if (bulLife[i] <= 0 || !bulHostile[i]) continue;
			float dx = shortestDx(bulX[i] - shipX, width);
			float dy = shortestDx(bulY[i] - shipY, height);
			float d  = (float) Math.sqrt(dx * dx + dy * dy);
			float safe = SHIP_R * 5f * unit;
			if (d >= safe) continue;
			float weight = (safe - d) / safe;
			dangerX -= dx / Math.max(1f, d) * weight * 1.4f;
			dangerY -= dy / Math.max(1f, d) * weight * 1.4f;
			if (weight > worst) worst = weight;
		}

		if (worst > 0.12f) {
			float len = (float) Math.sqrt(dangerX * dangerX + dangerY * dangerY);
			if (len > 1e-3f) {
				shipVx += dangerX / len * THRUST * unit * (0.6f + worst) * dt;
				shipVy += dangerY / len * THRUST * unit * (0.6f + worst) * dt;
				thrustGlow = 1f;
			}
		} else if (bass > 0.75f) {
			// Nothing to dodge: burn on the bass, purely for the look of the thing.
			shipVx += (float) Math.cos(shipAngle) * THRUST * unit * 0.5f * dt;
			shipVy += (float) Math.sin(shipAngle) * THRUST * unit * 0.5f * dt;
			thrustGlow = Math.max(thrustGlow, bass * 0.8f);
		}

		float sp = (float) Math.sqrt(shipVx * shipVx + shipVy * shipVy);
		float max = MAX_SPEED * unit;
		if (sp > max) {
			shipVx = shipVx / sp * max;
			shipVy = shipVy / sp * max;
		}
		shipVx *= DRAG;
		shipVy *= DRAG;
		shipX = wrap(shipX + shipVx * dt, width);
		shipY = wrap(shipY + shipVy * dt, height);
		thrustGlow *= 0.86f;

		// The trigger is the kick drum.
		// The kick pulls the trigger. If the beat detector has nothing to work with — or the nose
		// was pointing the wrong way each time one landed — a fallback shot keeps the guns warm, so
		// a quiet passage still looks like somebody is playing.
		sinceShot++;
		boolean aimed = Math.abs(wrapAngle(want - shipAngle)) < 0.55f;
		boolean have  = target >= 0 || ufoAlive;
		if (have && fireCooldown == 0 && aimed && (beatNow || sinceShot > AUTOFIRE_GAP)) {
			fire(shipX, shipY, shipAngle, false);
			fireCooldown = FIRE_COOLDOWN;
			sinceShot = 0;
		}
	}

	/** Closest rock, with big ones weighted up slightly so the field actually gets cleared. */
	private int pickTarget() {
		int best = -1;
		float bestScore = Float.MAX_VALUE;
		for (int i = 0; i < MAX_ROCKS; i++) {
			if (!rockAlive[i]) continue;
			float dx = shortestDx(rockX[i] - shipX, width);
			float dy = shortestDx(rockY[i] - shipY, height);
			float s  = (float) Math.sqrt(dx * dx + dy * dy) - rockSize[i] * 18f * unit;
			if (s < bestScore) {
				bestScore = s;
				best = i;
			}
		}
		return best;
	}

	/** Lead the target: solve roughly where it will be when a bullet could get there. */
	private float interceptAngle(float tx, float ty, float tvx, float tvy) {
		float dx = shortestDx(tx - shipX, width);
		float dy = shortestDx(ty - shipY, height);
		float t  = (float) Math.sqrt(dx * dx + dy * dy) / (BULLET_SPEED * unit);
		return (float) Math.atan2(dy + tvy * t, dx + tvx * t);
	}

	private void hyperspace() {
		burst(shipX, shipY, 16, 0x8AD8FF, 2.2f);
		shipX = rnd.nextFloat() * width;
		shipY = rnd.nextFloat() * height;
		shipVx = shipVy = 0f;
		invulnerable = 46;
		hyperFlash = 12;
		burst(shipX, shipY, 20, 0xFFFFFF, 2.8f);
	}

	private void fire(float x, float y, float angle, boolean hostile) {
		for (int i = 0; i < MAX_BULLETS; i++) {
			if (bulLife[i] > 0) continue;
			bulX[i]  = x + (float) Math.cos(angle) * SHIP_R * unit;
			bulY[i]  = y + (float) Math.sin(angle) * SHIP_R * unit;
			bulVx[i] = (float) Math.cos(angle) * BULLET_SPEED * unit + (hostile ? 0f : shipVx * 0.4f);
			bulVy[i] = (float) Math.sin(angle) * BULLET_SPEED * unit + (hostile ? 0f : shipVy * 0.4f);
			bulLife[i] = BULLET_LIFE;
			bulHostile[i] = hostile;
			return;
		}
	}

	// =========================================================================
	// World
	// =========================================================================

	private void updateBullets(float dt) {
		for (int i = 0; i < MAX_BULLETS; i++) {
			if (bulLife[i] <= 0) continue;
			bulX[i] = wrap(bulX[i] + bulVx[i] * dt, width);
			bulY[i] = wrap(bulY[i] + bulVy[i] * dt, height);
			bulLife[i]--;
		}
	}

	private void updateRocks(float dt) {
		float speed = 0.75f + energy * 0.9f;
		for (int i = 0; i < MAX_ROCKS; i++) {
			if (!rockAlive[i]) continue;
			rockX[i] = wrap(rockX[i] + rockVx[i] * speed * dt, width);
			rockY[i] = wrap(rockY[i] + rockVy[i] * speed * dt, height);
			rockA[i] += rockSpin[i] * dt;
		}
	}

	private void updateSaucer(float dt) {
		if (!ufoAlive) {
			if (--ufoSpawn <= 0) {
				ufoAlive = true;
				ufoY = height * (0.2f + rnd.nextFloat() * 0.6f);
				boolean fromLeft = rnd.nextBoolean();
				ufoX  = fromLeft ? -30f * unit : width + 30f * unit;
				ufoVx = (fromLeft ? 1f : -1f) * 1.5f * unit;
				ufoFire = 40;
			}
			return;
		}
		ufoX += ufoVx * dt;
		ufoY += (float) Math.sin(ufoX * 0.012f) * 0.9f * unit * dt;
		if (ufoX < -60f * unit || ufoX > width + 60f * unit) {
			ufoAlive = false;
			ufoSpawn = 700 + rnd.nextInt(600);
			return;
		}
		if (--ufoFire <= 0) {
			ufoFire = 66;
			if (shipAlive) {
				float dx = shortestDx(shipX - ufoX, width);
				float dy = shortestDx(shipY - ufoY, height);
				// Deliberately sloppy aim, or the ship would never survive a crossing.
				fire(ufoX, ufoY, (float) Math.atan2(dy, dx) + (rnd.nextFloat() - 0.5f) * 0.5f, true);
			}
		}
	}

	private void updateParticles(float dt) {
		for (int i = 0; i < MAX_PARTS; i++) {
			if (parLife[i] <= 0f) continue;
			parX[i] += parVx[i] * dt;
			parY[i] += parVy[i] * dt;
			parVx[i] *= 0.977f;
			parVy[i] *= 0.977f;
			parLife[i] -= dt;
		}
		for (int i = 0; i < MAX_POPS; i++) {
			if (popLife[i] <= 0f) continue;
			popY[i] -= 0.55f * unit * dt;
			popLife[i] -= dt;
		}
	}

	private void collisions() {

		for (int b = 0; b < MAX_BULLETS; b++) {
			if (bulLife[b] <= 0) continue;

			if (!bulHostile[b]) {
				for (int r = 0; r < MAX_ROCKS; r++) {
					if (!rockAlive[r]) continue;
					if (!hits(bulX[b], bulY[b], rockX[r], rockY[r], ROCK_R[rockSize[r]] * unit)) continue;
					bulLife[b] = 0;
					splitRock(r);
					break;
				}
				if (bulLife[b] > 0 && ufoAlive && hits(bulX[b], bulY[b], ufoX, ufoY, 15f * unit)) {
					bulLife[b] = 0;
					ufoAlive = false;
					ufoSpawn = 800 + rnd.nextInt(700);
					burst(ufoX, ufoY, 34, 0x6BFFB0, 3.4f);
					award(1000, ufoX, ufoY);
					shake  = Math.max(shake, 9f);
					impact = 1f;
				}
			} else if (shipAlive && invulnerable == 0
					&& hits(bulX[b], bulY[b], shipX, shipY, SHIP_R * unit)) {
				bulLife[b] = 0;
				killShip();
			}
		}

		if (!shipAlive || invulnerable > 0) return;
		for (int r = 0; r < MAX_ROCKS; r++) {
			if (!rockAlive[r]) continue;
			if (hits(shipX, shipY, rockX[r], rockY[r], (ROCK_R[rockSize[r]] + SHIP_R * 0.6f) * unit)) {
				splitRock(r);
				killShip();
				return;
			}
		}
	}

	private boolean hits(float ax, float ay, float bx, float by, float r) {
		float dx = shortestDx(ax - bx, width);
		float dy = shortestDx(ay - by, height);
		return dx * dx + dy * dy < r * r;
	}

	/** The mechanic the whole game is built on: one rock becomes two smaller, faster ones. */
	private void splitRock(int r) {
		int size = rockSize[r];
		float x = rockX[r], y = rockY[r];
		rockAlive[r] = false;

		burst(x, y, 12 + size * 8, 0xC8B4FF, 1.6f + size * 0.6f);
		award(ROCK_SCORE[size], x, y);
		shake  = Math.max(shake, 3f + size * 2.4f);
		impact = Math.max(impact, 0.45f + size * 0.2f);

		if (size == 0) {
			if (aliveRocks() == 0) clearWave();
			return;
		}
		for (int n = 0; n < 2; n++) {
			int slot = freeRock();
			if (slot < 0) break;
			float ang = rnd.nextFloat() * (float) Math.PI * 2f;
			float spd = (0.7f + rnd.nextFloat() * 0.9f) * unit * (1f + size * 0.25f);
			rockAlive[slot] = true;
			rockSize[slot]  = (byte) (size - 1);
			rockShape[slot] = (byte) rnd.nextInt(SHAPES);
			rockX[slot]  = x;
			rockY[slot]  = y;
			rockVx[slot] = (float) Math.cos(ang) * spd;
			rockVy[slot] = (float) Math.sin(ang) * spd;
			rockA[slot]  = rnd.nextFloat() * 6.28f;
			rockSpin[slot] = (rnd.nextFloat() - 0.5f) * 0.075f;
		}
		if (aliveRocks() == 0) clearWave();
	}

	private void killShip() {
		shipAlive = false;
		respawn   = 90;
		burst(shipX, shipY, 40, 0x9BE8FF, 3.2f);
		shake  = 16f;
		impact = 1f;
		slowMo = 26;
		combo  = 0;
		if (--lives <= 0) {
			gameOver = 200;
			lives = 0;
		}
	}

	private void clearWave() {
		wave++;
		waveBanner = 150;
		slowMo = 34;
		startWave(false);
	}

	private void restart() {
		score = 0;
		scoreShown = 0f;
		wave  = 1;
		lives = 3;
		startWave(true);
	}

	private void startWave(boolean reset) {
		if (reset) {
			for (int i = 0; i < MAX_ROCKS; i++) rockAlive[i] = false;
			for (int i = 0; i < MAX_BULLETS; i++) bulLife[i] = 0;
			shipX = width * 0.5f;
			shipY = height * 0.5f;
			shipVx = shipVy = 0f;
			shipAlive = true;
			invulnerable = 90;
			waveBanner = 150;
		}
		int count = Math.min(10, 4 + wave);
		for (int n = 0; n < count; n++) {
			int slot = freeRock();
			if (slot < 0) break;
			// Spawn on the rim, so nothing materialises on top of the ship.
			boolean vertical = rnd.nextBoolean();
			rockX[slot] = vertical ? rnd.nextFloat() * width : (rnd.nextBoolean() ? 0 : width);
			rockY[slot] = vertical ? (rnd.nextBoolean() ? 0 : height) : rnd.nextFloat() * height;
			float ang = rnd.nextFloat() * (float) Math.PI * 2f;
			float spd = (0.45f + rnd.nextFloat() * 0.7f) * unit;
			rockVx[slot] = (float) Math.cos(ang) * spd;
			rockVy[slot] = (float) Math.sin(ang) * spd;
			rockAlive[slot] = true;
			rockSize[slot]  = 2;
			rockShape[slot] = (byte) rnd.nextInt(SHAPES);
			rockA[slot]     = rnd.nextFloat() * 6.28f;
			rockSpin[slot]  = (rnd.nextFloat() - 0.5f) * 0.05f;
		}
	}

	private void award(int points, float x, float y) {
		comboTimer = COMBO_WINDOW;
		combo = Math.min(9, combo + 1);
		int gained = points * Math.max(1, combo);
		score += gained;
		popX[popNext] = x;
		popY[popNext] = y;
		popLife[popNext] = 46f;
		popText[popNext] = combo > 1 ? gained + "  x" + combo : Integer.toString(gained);
		popNext = (popNext + 1) % MAX_POPS;
	}

	private void burst(float x, float y, int count, int rgb, float speed) {
		for (int n = 0; n < count; n++) {
			float ang = rnd.nextFloat() * (float) Math.PI * 2f;
			float sp  = (0.3f + rnd.nextFloat()) * speed * unit;
			parX[parNext] = x;
			parY[parNext] = y;
			parVx[parNext] = (float) Math.cos(ang) * sp;
			parVy[parNext] = (float) Math.sin(ang) * sp;
			parMax[parNext] = 22f + rnd.nextFloat() * 26f;
			parLife[parNext] = parMax[parNext];
			parRgb[parNext] = rgb;
			parNext = (parNext + 1) % MAX_PARTS;
		}
	}

	private void housekeeping() {
		if (aliveRocks() == 0 && waveBanner <= 0 && gameOver == 0) startWave(false);
	}

	private int aliveRocks() {
		int n = 0;
		for (int i = 0; i < MAX_ROCKS; i++) if (rockAlive[i]) n++;
		return n;
	}

	private int freeRock() {
		for (int i = 0; i < MAX_ROCKS; i++) if (!rockAlive[i]) return i;
		return -1;
	}

	// =========================================================================
	// Rendering
	// =========================================================================

	/** Deep-space backdrop and a parallax starfield, straight into the half-res composite. */
	private void drawSpace(Graphics2D g, int w, int h, int frameCount) {
		g.setColor(new Color(0x03, 0x04, 0x0B));
		g.fillRect(0, 0, w, h);

		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		for (int n = 0; n < nebula.length; n++) {
			float t  = frameCount * (0.00035f + n * 0.00022f) + n * 2.1f;
			float sz = Math.max(w, h) * (1.05f + n * 0.35f);
			float nx = w * 0.5f + (float) Math.cos(t) * w * 0.30f - sz * 0.5f;
			float ny = h * 0.5f + (float) Math.sin(t * 1.3) * h * 0.28f - sz * 0.5f;
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
					0.16f + 0.10f * clamp(energy * 2f, 0f, 1f)));
			g.drawImage(nebula[n], Math.round(nx), Math.round(ny), Math.round(sz), Math.round(sz), null);
		}
		g.setComposite(AlphaComposite.SrcOver);

		float drift = frameCount * 0.12f / VEC_SCALE;
		for (int i = 0; i < STARS; i++) {
			float z = starZ[i];
			int x = (int) (((starX[i] * w + drift * z) % w + w) % w);
			int y = (int) (starY[i] * h);
			int a = (int) (30 + 150 * z * (0.6f + 0.4f * bandLevel[i % BANDS]));
			g.setColor(new Color(170, 200, 255, Math.min(255, a)));
			g.fillRect(x, y, z > 0.8f ? 2 : 1, z > 0.8f ? 2 : 1);
		}
	}

	/** Everything vector goes into the persistence buffer, which decays instead of clearing. */
	private void drawVectors(int frameCount) {
		Graphics2D g = vec.createGraphics();
		try {
			// DST_OUT scrubs alpha rather than painting black over it, so trails fade to genuinely
			// transparent and never leave a grey fog behind them.
			g.setComposite(AlphaComposite.getInstance(AlphaComposite.DST_OUT, 0.24f));
			g.setColor(Color.WHITE);
			g.fillRect(0, 0, vec.getWidth(), vec.getHeight());

			g.setComposite(AlphaComposite.SrcOver);
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
			// Everything below still works in panel coordinates; this one scale is the whole change.
			g.scale(1.0 / VEC_SCALE, 1.0 / VEC_SCALE);

			drawParticles(g);
			drawRocks(g);
			drawBullets(g);
			drawSaucer(g);
			if (shipAlive) drawShip(g, frameCount);
		} finally {
			g.dispose();
		}
	}

	private void drawRocks(Graphics2D g) {
		for (int i = 0; i < MAX_ROCKS; i++) {
			if (!rockAlive[i]) continue;
			final int idx = i;
			float r = ROCK_R[rockSize[i]] * unit;
			buildRockPath(i, r);
			Color glow = ROCK_GLOW[rockSize[i]], core = ROCK_CORE[rockSize[i]];
			forEachWrap(rockX[i], rockY[i], r,
					(dx, dy) -> stroke(g, path, dx, dy, rockA[idx], glow, core));
		}
	}

	private void buildRockPath(int i, float r) {
		float[] radii = shapeR[rockShape[i]];
		path.reset();
		for (int v = 0; v < ROCK_VERTS; v++) {
			double a = v * 2 * Math.PI / ROCK_VERTS;
			float  rr = r * radii[v];
			float  x  = (float) (Math.cos(a) * rr);
			float  y  = (float) (Math.sin(a) * rr);
			if (v == 0) path.moveTo(x, y); else path.lineTo(x, y);
		}
		path.closePath();
	}

	private void drawShip(Graphics2D g, int frameCount) {
		// Blink while the shields are up after a respawn.
		if (invulnerable > 0 && (frameCount / 4) % 2 == 0) return;

		float s = SHIP_R * unit;
		path.reset();
		path.moveTo(s, 0);
		path.lineTo(-s * 0.72f, s * 0.62f);
		path.lineTo(-s * 0.38f, 0);
		path.lineTo(-s * 0.72f, -s * 0.62f);
		path.closePath();

		forEachWrap(shipX, shipY, s,
				(dx, dy) -> stroke(g, path, dx, dy, shipAngle, SHIP_GLOW, SHIP_CORE));

		if (thrustGlow > 0.06f) {
			float f = s * (0.7f + thrustGlow * 0.9f);
			path.reset();
			path.moveTo(-s * 0.42f, s * 0.34f);
			path.lineTo(-s * 0.45f - f, 0);
			path.lineTo(-s * 0.42f, -s * 0.34f);
			forEachWrap(shipX, shipY, s + f,
					(dx, dy) -> stroke(g, path, dx, dy, shipAngle, FLAME_GLOW, FLAME_CORE));
		}
	}

	private void drawSaucer(Graphics2D g) {
		if (!ufoAlive) return;
		float s = 15f * unit;
		path.reset();
		path.moveTo(-s, 0);
		path.lineTo(-s * 0.42f, -s * 0.34f);
		path.lineTo(s * 0.42f, -s * 0.34f);
		path.lineTo(s, 0);
		path.lineTo(s * 0.42f, s * 0.34f);
		path.lineTo(-s * 0.42f, s * 0.34f);
		path.closePath();
		path.moveTo(-s * 0.42f, -s * 0.34f);
		path.lineTo(-s * 0.22f, -s * 0.66f);
		path.lineTo(s * 0.22f, -s * 0.66f);
		path.lineTo(s * 0.42f, -s * 0.34f);
		path.moveTo(-s, 0);
		path.lineTo(s, 0);

		forEachWrap(ufoX, ufoY, s, (dx, dy) -> stroke(g, path, dx, dy, 0f, UFO_GLOW, UFO_CORE));
	}

	/** Tracers, not dots: a short streak along the direction of travel reads far better in motion. */
	private void drawBullets(Graphics2D g) {
		for (int i = 0; i < MAX_BULLETS; i++) {
			if (bulLife[i] <= 0) continue;
			float tailX = bulX[i] - bulVx[i] * 2.6f;
			float tailY = bulY[i] - bulVy[i] * 2.6f;
			Color glow = bulHostile[i] ? UFO_GLOW : BULLET_GLOW;
			g.setStroke(HALO);
			g.setColor(alpha(glow, 46));
			g.drawLine(Math.round(bulX[i]), Math.round(bulY[i]), Math.round(tailX), Math.round(tailY));
			g.setStroke(MID);
			g.setColor(alpha(glow, 170));
			g.drawLine(Math.round(bulX[i]), Math.round(bulY[i]), Math.round(tailX), Math.round(tailY));
			g.setStroke(CORE);
			g.setColor(BULLET_CORE);
			g.drawLine(Math.round(bulX[i]), Math.round(bulY[i]),
					Math.round(bulX[i] - bulVx[i]), Math.round(bulY[i] - bulVy[i]));
		}
	}

	private void drawParticles(Graphics2D g) {
		for (int i = 0; i < MAX_PARTS; i++) {
			if (parLife[i] <= 0f) continue;
			float k = parLife[i] / parMax[i];
			int   a = (int) (235 * k * k);
			if (a < 6) continue;
			int rgb = parRgb[i];
			g.setColor(new Color((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, a));
			float sz = Math.max(1f, 2.4f * unit * k);
			g.fillRect(Math.round(parX[i]), Math.round(parY[i]), Math.round(sz), Math.round(sz));
		}
	}

	/**
	 * Three passes per shape — wide soft halo, tighter glow, hot thin core — which is what a beam
	 * blooming on phosphor actually looks like. On impact the outline also gets split into red and
	 * cyan a couple of pixels apart, the one modern flourish the vector monitor never had.
	 */
	private void stroke(Graphics2D g, Path2D.Float p, float dx, float dy, float rot,
			Color glow, Color core) {
		// Move the pen rather than the geometry: drawing the same Path2D again costs nothing, while
		// createTransformedShape would allocate a shape for every wrap image, every frame.
		AffineTransform old = g.getTransform();
		g.translate(dx, dy);
		if (rot != 0f) g.rotate(rot);

		if (impact > 0.22f) {
			float o = impact * 3.2f;
			g.setStroke(MID);
			g.setColor(new Color(255, 40, 60, (int) (120 * impact)));
			g.translate(-o, 0);
			g.draw(p);
			g.translate(2 * o, 0);
			g.setColor(new Color(40, 220, 255, (int) (120 * impact)));
			g.draw(p);
			g.translate(-o, 0);
		}
		g.setStroke(HALO);
		g.setColor(alpha(glow, 42));
		g.draw(p);
		g.setStroke(MID);
		g.setColor(alpha(glow, 120));
		g.draw(p);
		g.setStroke(CORE);
		g.setColor(core);
		g.draw(p);

		g.setTransform(old);
	}

	/**
	 * Compose. The persistence layer is shrunk hard and drawn back enlarged underneath itself,
	 * which is a real bloom rather than a stack of fatter strokes.
	 * <p>
	 * All of that happens in the half-resolution buffer — stars, bloom and vectors — so exactly one
	 * full-panel operation is left, and because that buffer is opaque the blit is a straight scaled
	 * copy instead of a per-pixel alpha blend. Compositing at panel resolution instead cost about
	 * four milliseconds a frame on its own.
	 */
	private void compose(Graphics2D g2, int w, int h, int frameCount) {

		int fw = frame.getWidth(), fh = frame.getHeight();

		Graphics2D bg = bloom.createGraphics();
		try {
			bg.setComposite(AlphaComposite.Clear);
			bg.fillRect(0, 0, bloom.getWidth(), bloom.getHeight());
			bg.setComposite(AlphaComposite.SrcOver);
			bg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			bg.drawImage(vec, 0, 0, bloom.getWidth(), bloom.getHeight(), null);
		} finally {
			bg.dispose();
		}

		Graphics2D fg = frame.createGraphics();
		try {
			drawSpace(fg, fw, fh, frameCount);
			fg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			fg.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.62f));
			fg.drawImage(bloom, -fw / 24, -fh / 24, fw + fw / 12, fh + fh / 12, null);
			fg.setComposite(AlphaComposite.SrcOver);
			fg.drawImage(vec, 0, 0, null);
		} finally {
			fg.dispose();
		}

		int sx = 0, sy = 0;
		if (shake > 0.4f) {
			sx = Math.round((rnd.nextFloat() - 0.5f) * shake);
			sy = Math.round((rnd.nextFloat() - 0.5f) * shake);
		}
		g2.drawImage(frame, sx, sy, w + sx, h + sy, 0, 0, fw, fh, null);

		if (hyperFlash > 0) {
			g2.setColor(new Color(180, 230, 255, hyperFlash * 9));
			g2.fillRect(0, 0, w, h);
		}
	}

	// =========================================================================
	// HUD
	// =========================================================================

	private void drawHud(Graphics2D g2, int w, int h) {
		Graphics2D g = (Graphics2D) g2.create();
		try {
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g.setFont(hudFont);
			FontMetrics fm = g.getFontMetrics();

			// Score, with the leading zeros every one of these machines insisted on.
			String sc = zeroPad(Math.round(scoreShown), 6);
			g.setColor(alpha(SHIP_GLOW, 70));
			g.drawString(sc, 13, 9 + fm.getAscent());
			g.setColor(SHIP_CORE);
			g.drawString(sc, 12, 8 + fm.getAscent());

			// Lives, drawn as little ships.
			for (int i = 0; i < lives; i++) {
				int cx = 14 + i * Math.round(11 * unit + 5);
				int cy = 12 + fm.getHeight() + fm.getAscent() / 2;
				drawLifeGlyph(g, cx, cy, 5.5f * unit);
			}

			String right = "WAVE " + wave;
			g.setFont(smallFont);
			FontMetrics sm = g.getFontMetrics();
			g.setColor(HUD_DIM);
			g.drawString(right, w - sm.stringWidth(right) - 12, 8 + sm.getAscent());

			if (combo > 1) {
				String c = "COMBO x" + combo;
				g.setColor(new Color(0xFF, 0x6A, 0xC0));
				g.drawString(c, w - sm.stringWidth(c) - 12, 10 + sm.getHeight() + sm.getAscent());
			}

			// Floating score pop-ups off the wreckage.
			for (int i = 0; i < MAX_POPS; i++) {
				if (popLife[i] <= 0f || popText[i] == null) continue;
				int a = (int) (255 * clamp(popLife[i] / 46f, 0f, 1f));
				g.setColor(new Color(0xFF, 0xE8, 0xF6, a));
				g.drawString(popText[i], popX[i], popY[i]);
			}

			// A thin spectrum along the bottom edge: the one place the audio is stated outright
			// rather than implied by what the ship is doing.
			int sbH = Math.max(3, Math.round(h * 0.035f));
			int bw  = Math.max(1, w / BANDS);
			for (int b = 0; b < BANDS; b++) {
				int bh = Math.round(sbH * clamp(bandLevel[b], 0f, 1f));
				if (bh <= 0) continue;
				g.setColor(new Color(0x53, 0xE6, 0xFF, 46));
				g.fillRect(b * bw, h - bh, bw - 1, bh);
			}

			if (waveBanner > 0 && gameOver == 0) {
				banner(g, w, h, "WAVE " + wave, trackTitle == null ? null : trackTitle,
						clamp(waveBanner / 40f, 0f, 1f));
			}
			if (gameOver > 0) {
				banner(g, w, h, "GAME OVER", "INSERT COIN", clamp(gameOver / 40f, 0f, 1f));
			}
		} finally {
			g.dispose();
		}
	}

	private void drawLifeGlyph(Graphics2D g, int cx, int cy, float s) {
		path.reset();
		path.moveTo(0, -s);
		path.lineTo(s * 0.62f, s * 0.72f);
		path.lineTo(0, s * 0.38f);
		path.lineTo(-s * 0.62f, s * 0.72f);
		path.closePath();
		xform.setToTranslation(cx, cy);
		g.setColor(alpha(SHIP_GLOW, 110));
		g.setStroke(MID);
		g.draw(xform.createTransformedShape(path));
		g.setColor(SHIP_CORE);
		g.setStroke(CORE);
		g.draw(xform.createTransformedShape(path));
	}

	private void banner(Graphics2D g, int w, int h, String title, String sub, float a) {
		g.setFont(bannerFont);
		FontMetrics fm = g.getFontMetrics();
		int tw = fm.stringWidth(title);
		if (tw > w - 20) return;
		int x = (w - tw) / 2;
		int y = h / 2;
		g.setColor(new Color(0x53, 0xE6, 0xFF, (int) (90 * a)));
		g.drawString(title, x - 2, y - 1);
		g.drawString(title, x + 2, y + 1);
		g.setColor(new Color(0xF4, 0xFC, 0xFF, (int) (255 * a)));
		g.drawString(title, x, y);
		if (sub != null) {
			g.setFont(smallFont);
			FontMetrics sm = g.getFontMetrics();
			int sw = sm.stringWidth(sub);
			if (sw < w - 20) {
				g.setColor(new Color(0x8F, 0xB4, 0xD8, (int) (200 * a)));
				g.drawString(sub, (w - sw) / 2, y + fm.getHeight() * 2 / 3 + sm.getAscent());
			}
		}
	}

	// =========================================================================
	// Plumbing
	// =========================================================================

	/** Runs the drawing callback once per visible wrap image, so shapes cross the edges properly. */
	private interface WrapDraw { void at(float x, float y); }

	private void forEachWrap(float x, float y, float r, WrapDraw draw) {
		draw.at(x, y);
		boolean left = x < r, right = x > width - r;
		boolean up   = y < r, down  = y > height - r;
		if (left)  draw.at(x + width, y);
		if (right) draw.at(x - width, y);
		if (up)    draw.at(x, y + height);
		if (down)  draw.at(x, y - height);
		if (left && up)    draw.at(x + width, y + height);
		if (right && up)   draw.at(x - width, y + height);
		if (left && down)  draw.at(x + width, y - height);
		if (right && down) draw.at(x - width, y - height);
	}

	private void ensureBuffers(int w, int h) {
		if (vec != null && bufW == w && bufH == h) return;
		vec   = new BufferedImage(Math.max(1, w / VEC_SCALE), Math.max(1, h / VEC_SCALE),
				BufferedImage.TYPE_INT_ARGB);
		bloom = new BufferedImage(Math.max(1, w / (VEC_SCALE * 4)), Math.max(1, h / (VEC_SCALE * 4)),
				BufferedImage.TYPE_INT_ARGB);
		frame = new BufferedImage(vec.getWidth(), vec.getHeight(), BufferedImage.TYPE_INT_RGB);
		bufW  = w;
		bufH  = h;
	}

	private void ensureFonts(int h) {
		if (fontsFor == h) return;
		fontsFor   = h;
		hudFont    = new Font(Font.MONOSPACED, Font.BOLD, Math.max(11, Math.round(h * 0.052f)));
		bannerFont = new Font(Font.MONOSPACED, Font.BOLD, Math.max(16, Math.round(h * 0.105f)));
		smallFont  = new Font(Font.MONOSPACED, Font.BOLD, Math.max(9, Math.round(h * 0.034f)));
	}

	/**
	 * Two soft radial blobs, rendered once. Drifting them across the backdrop at low opacity is
	 * what stops deep space looking like a plain black rectangle, and it costs two blits a frame.
	 */
	private void buildNebula() {
		int[] tints = { 0x3C64FF, 0xC83CFF };
		for (int n = 0; n < nebula.length; n++) {
			BufferedImage img = new BufferedImage(NEBULA_TEX, NEBULA_TEX, BufferedImage.TYPE_INT_ARGB);
			int[] px = ((java.awt.image.DataBufferInt) img.getRaster().getDataBuffer()).getData();
			int rgb = tints[n];
			float c = NEBULA_TEX * 0.5f;
			for (int y = 0; y < NEBULA_TEX; y++) {
				for (int x = 0; x < NEBULA_TEX; x++) {
					float dx = (x - c) / c, dy = (y - c) / c;
					float d = (float) Math.sqrt(dx * dx + dy * dy);
					// Falloff plus a couple of sine lobes, so the cloud is not a perfect circle.
					float f = clamp(1f - d, 0f, 1f);
					f *= f * (0.75f + 0.25f * (float) Math.sin(dx * 6.1 + dy * 4.3));
					px[y * NEBULA_TEX + x] = (clamp255((int) (f * 190)) << 24) | rgb;
				}
			}
			nebula[n] = img;
		}
	}

	private void buildShapes() {
		for (int s = 0; s < SHAPES; s++) {
			for (int v = 0; v < ROCK_VERTS; v++) {
				shapeR[s][v] = 0.72f + rnd.nextFloat() * 0.42f;
			}
		}
	}

	private void buildBandBins() {
		float logMin  = (float) Math.log(40f);
		float logMax  = (float) Math.log(16000f);
		int   nyquist = FFT_SIZE / 2;
		for (int b = 0; b <= BANDS; b++) {
			float freq = (float) Math.exp(logMin + (logMax - logMin) * b / BANDS);
			bandBin[b] = Math.max(1, Math.min(nyquist, Math.round(freq * FFT_SIZE / SAMPLE_RATE)));
		}
		for (int b = 1; b <= BANDS; b++) {
			if (bandBin[b] <= bandBin[b - 1]) bandBin[b] = Math.min(nyquist, bandBin[b - 1] + 1);
		}
	}

	private static String zeroPad(int value, int digits) {
		String s = Integer.toString(Math.max(0, value));
		if (s.length() >= digits) return s;
		StringBuilder sb = new StringBuilder(digits);
		for (int i = s.length(); i < digits; i++) sb.append('0');
		return sb.append(s).toString();
	}

	private static Color alpha(Color c, int a) {
		return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
	}

	/** Shortest signed distance on a wrapping axis, so nothing chases a target the long way round. */
	private static float shortestDx(float d, float span) {
		if (span <= 0f) return d;
		if (d > span * 0.5f) return d - span;
		if (d < -span * 0.5f) return d + span;
		return d;
	}

	private static float wrap(float v, float span) {
		if (span <= 0f) return v;
		if (v < 0f) return v + span;
		if (v >= span) return v - span;
		return v;
	}

	private static float wrapAngle(float a) {
		while (a > Math.PI) a -= 2 * Math.PI;
		while (a < -Math.PI) a += 2 * Math.PI;
		return a;
	}

	private static int clamp255(int v) {
		return Math.max(0, Math.min(255, v));
	}

	private static float clamp(float v, float min, float max) {
		return Math.max(min, Math.min(max, v));
	}
}
