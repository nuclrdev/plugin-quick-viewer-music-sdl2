package sdl2;

import java.util.Arrays;

/**
 * Lock-free single-producer single-consumer ring buffer for PCM audio samples.
 * <p>
 * Written by the SDL audio callback thread (single producer),
 * read by the Swing EDT for visualization (single consumer).
 * Uses a monotonically increasing volatile write counter for cross-thread visibility.
 */
public class AudioRingBuffer {

	private final float[] buffer;
	private final int capacity;
	private volatile long writeCount;

	public AudioRingBuffer(int capacity) {
		this.capacity = capacity;
		this.buffer = new float[capacity];
	}

	/**
	 * Write mono samples into the ring buffer.
	 * Called from the SDL audio callback thread ONLY.
	 */
	public void write(float[] samples, int offset, int count) {
		long wc = writeCount;
		for (int i = 0; i < count; i++) {
			buffer[(int) ((wc + i) % capacity)] = samples[offset + i];
		}
		writeCount = wc + count; // volatile write ensures visibility
	}

	/**
	 * Take a snapshot of the most recent samples into dest.
	 * Called from the Swing EDT.
	 *
	 * @return number of samples actually copied
	 */
	public int snapshot(float[] dest, int requestedLen) {
		long wc = writeCount;
		if (wc == 0) return 0;

		int len = Math.min(requestedLen, Math.min(dest.length, capacity));
		long available = Math.min(wc, capacity);
		len = (int) Math.min(len, available);

		long start = wc - len;
		for (int i = 0; i < len; i++) {
			dest[i] = buffer[(int) ((start + i) % capacity)];
		}
		return len;
	}

	/**
	 * Clear the buffer. Call when stopping playback.
	 */
	public void clear() {
		writeCount = 0;
		Arrays.fill(buffer, 0);
	}

	public int getCapacity() {
		return capacity;
	}
}
