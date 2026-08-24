package dev.nuclr.plugin.core.quick.viewer.music.sdl2;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;

import javax.imageio.ImageIO;

import lombok.extern.slf4j.Slf4j;

/**
 * Reads embedded cover art out of an audio file.
 * <p>
 * Deliberately dependency-free: the plugin ZIP is signed and shipped, so pulling in a full
 * tagging library (jaudiotagger being the obvious candidate) would add ~2.5 MB and an LGPL
 * obligation for the sake of one byte array. Everything here is a container walk down to the
 * picture payload — no tag model, no writing.
 * <p>
 * Supported carriers:
 * <ul>
 *   <li>ID3v2.2 {@code PIC} and ID3v2.3/2.4 {@code APIC} — mp3, aac, and any file that simply
 *       has an ID3 tag glued to the front</li>
 *   <li>FLAC {@code PICTURE} metadata blocks</li>
 *   <li>Ogg Vorbis / Opus comment headers ({@code METADATA_BLOCK_PICTURE}, {@code COVERART})</li>
 *   <li>RIFF/WAVE and AIFF {@code ID3 } chunks</li>
 * </ul>
 * All parsing is bounded: nothing here allocates on the strength of an unvalidated length field.
 */
@Slf4j
final class CoverArtExtractor {

	/** Largest tag we will buffer. Real cover-art tags are a few hundred KB. */
	private static final int MAX_TAG_BYTES = 32 * 1024 * 1024;
	/** Largest encoded image we will hand to ImageIO. */
	private static final int MAX_IMAGE_BYTES = 24 * 1024 * 1024;
	/** How far into an Ogg stream we look for the comment header before giving up. */
	private static final long MAX_OGG_SCAN_BYTES = 8L * 1024 * 1024;
	/** Decoded art is downscaled to this longest edge — it is only ever drawn as a thumbnail. */
	private static final int MAX_EDGE = 512;

	/** ID3 picture type for "cover (front)". */
	private static final int PIC_TYPE_FRONT_COVER = 3;

	private CoverArtExtractor() {
	}

	/**
	 * Extracts the front cover from {@code file}, or {@code null} when there is none.
	 * Never throws: a malformed tag is simply a file without art.
	 *
	 * @param file      an existing, readable audio file
	 * @param extension the original file extension (without the dot); picks the parser
	 */
	static BufferedImage extract(Path file, String extension) {
		if (file == null) {
			return null;
		}
		String ext = extension == null ? "" : extension.toLowerCase(Locale.ROOT);
		try {
			byte[] picture = switch (ext) {
				case "flac" -> flacPicture(file);
				case "ogg", "oga", "opus" -> oggPicture(file);
				case "wav", "wave" -> riffPicture(file);
				case "aiff", "aif", "aifc" -> aiffPicture(file);
				default -> id3Picture(file);
			};
			if (picture == null && !"mp3".equals(ext)) {
				// Taggers routinely staple an ID3v2 tag onto containers that have metadata of
				// their own (flac, aiff, wav). Try it before declaring the file bare.
				picture = id3Picture(file);
			}
			return decode(picture);
		} catch (IOException | RuntimeException e) {
			log.debug("No usable cover art in {}: {}", file, e.toString());
			return null;
		}
	}

	// ---------------------------------------------------------------- ID3v2

	private static byte[] id3Picture(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] header = tryReadFully(in, 10);
			if (header == null || header[0] != 'I' || header[1] != 'D' || header[2] != '3') {
				return null;
			}
			int major = header[3] & 0xFF;
			if (major < 2 || major > 4) {
				return null;
			}
			int flags = header[5] & 0xFF;
			int size = syncSafeInt(header, 6);
			if (size <= 0 || size > MAX_TAG_BYTES) {
				return null;
			}
			byte[] body = tryReadFully(in, size);
			if (body == null) {
				return null;
			}
			return id3Picture(body, major, flags);
		}
	}

	private static byte[] id3Picture(byte[] body, int major, int headerFlags) {
		boolean headerUnsync = (headerFlags & 0x80) != 0;
		if (major == 2 && (headerFlags & 0x40) != 0) {
			return null;   // whole-tag compression: never used in practice, not worth decoding
		}
		byte[] tag = body;
		if (major < 4 && headerUnsync) {
			// 2.2/2.3 unsynchronise the whole tag, so it has to come off before any frame size
			// can be read. 2.4 moved unsynchronisation to individual frames, handled below.
			tag = deUnsynchronise(body, 0, body.length);
		}

		int pos = 0;
		if (major >= 3 && (headerFlags & 0x40) != 0) {
			if (pos + 4 > tag.length) {
				return null;
			}
			// 2.3 stores the extended header size excluding itself, 2.4 syncsafe and including it.
			int extSize = major == 3 ? bigEndianInt(tag, pos) + 4 : syncSafeInt(tag, pos);
			if (extSize <= 0 || extSize > tag.length) {
				return null;
			}
			pos += extSize;
		}

		byte[] best = null;
		int bestRank = Integer.MAX_VALUE;
		int idLen = major == 2 ? 3 : 4;
		int frameHeaderLen = major == 2 ? 6 : 10;

		while (pos + frameHeaderLen <= tag.length) {
			if (tag[pos] == 0) {
				break;   // padding
			}
			String id = new String(tag, pos, idLen, StandardCharsets.ISO_8859_1);
			int frameSize;
			int formatFlags = 0;
			if (major == 2) {
				frameSize = ((tag[pos + 3] & 0xFF) << 16) | ((tag[pos + 4] & 0xFF) << 8) | (tag[pos + 5] & 0xFF);
			} else if (major == 3) {
				frameSize = bigEndianInt(tag, pos + 4);
				formatFlags = tag[pos + 9] & 0xFF;
			} else {
				frameSize = frameSizeV24(tag, pos);
				formatFlags = tag[pos + 9] & 0xFF;
			}
			int dataStart = pos + frameHeaderLen;
			if (frameSize <= 0 || dataStart + frameSize > tag.length) {
				break;
			}

			boolean wanted = major == 2 ? "PIC".equals(id) : "APIC".equals(id);
			// Compressed (0x08) or encrypted (0x04) frames do not hold a plain image.
			boolean opaque = major >= 3 && (formatFlags & 0x0C) != 0;
			if (wanted && !opaque) {
				int off = dataStart;
				int len = frameSize;
				byte[] frame = tag;
				if (major == 4 && (headerUnsync || (formatFlags & 0x02) != 0)) {
					frame = deUnsynchronise(tag, off, len);
					off = 0;
					len = frame.length;
				}
				if (major == 4 && (formatFlags & 0x01) != 0 && len > 4) {
					off += 4;   // a data-length indicator precedes the frame content
					len -= 4;
				}
				Picture picture = major == 2 ? parsePic(frame, off, len) : parseApic(frame, off, len);
				if (picture != null) {
					int rank = rank(picture.type());
					if (rank < bestRank) {
						best = picture.data();
						bestRank = rank;
						if (rank == 0) {
							return best;   // front cover: nothing outranks it
						}
					}
				}
			}
			pos = dataStart + frameSize;
		}
		return best;
	}

	/**
	 * ID3v2.4 declares frame sizes syncsafe, but a well-known share of taggers wrote plain
	 * big-endian instead — and an APIC frame is exactly where the two readings diverge, since
	 * cover art is the one frame big enough to overflow seven bits per byte.
	 * <p>
	 * A high bit anywhere in the field rules syncsafe out outright. Otherwise both readings are
	 * tried and the one that lands on something that looks like the next frame wins, with the
	 * spec-compliant reading preferred when neither can be told apart.
	 */
	private static int frameSizeV24(byte[] tag, int frameStart) {
		int off = frameStart + 4;
		for (int i = 0; i < 4; i++) {
			if ((tag[off + i] & 0x80) != 0) {
				return bigEndianInt(tag, off);
			}
		}
		int syncsafe = syncSafeInt(tag, off);
		int bigEndian = bigEndianInt(tag, off);
		if (syncsafe == bigEndian) {
			return syncsafe;
		}
		if (isFrameBoundary(tag, frameStart + 10 + syncsafe)) {
			return syncsafe;
		}
		if (isFrameBoundary(tag, frameStart + 10 + bigEndian)) {
			return bigEndian;
		}
		return syncsafe;
	}

	/** True when {@code at} is the end of the tag, the start of its padding, or a frame ID. */
	private static boolean isFrameBoundary(byte[] tag, int at) {
		if (at < 0 || at > tag.length) {
			return false;
		}
		if (at == tag.length || tag[at] == 0) {
			return true;
		}
		if (at + 4 > tag.length) {
			return false;
		}
		for (int i = 0; i < 4; i++) {
			int c = tag[at + i] & 0xFF;
			boolean idChar = (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9');
			if (!idChar) {
				return false;
			}
		}
		return true;
	}

	/** ID3v2.3/2.4 {@code APIC}: encoding, MIME (latin-1, NUL-terminated), type, description, data. */
	private static Picture parseApic(byte[] b, int off, int len) {
		int end = off + len;
		if (len < 4 || end > b.length) {
			return null;
		}
		int encoding = b[off] & 0xFF;
		int mimeEnd = indexOfZero(b, off + 1, end);
		if (mimeEnd < 0) {
			return null;
		}
		String mime = new String(b, off + 1, mimeEnd - (off + 1), StandardCharsets.ISO_8859_1);
		if ("-->".equals(mime.trim())) {
			return null;   // the frame holds a URL, not an image
		}
		int typePos = mimeEnd + 1;
		if (typePos >= end) {
			return null;
		}
		int type = b[typePos] & 0xFF;
		int dataStart = endOfText(b, typePos + 1, end, encoding);
		if (dataStart < 0 || dataStart >= end) {
			return null;
		}
		return new Picture(type, Arrays.copyOfRange(b, dataStart, end));
	}

	/** ID3v2.2 {@code PIC}: encoding, 3-character image format, type, description, data. */
	private static Picture parsePic(byte[] b, int off, int len) {
		int end = off + len;
		if (len < 6 || end > b.length) {
			return null;
		}
		int encoding = b[off] & 0xFF;
		int type = b[off + 4] & 0xFF;
		int dataStart = endOfText(b, off + 5, end, encoding);
		if (dataStart < 0 || dataStart >= end) {
			return null;
		}
		return new Picture(type, Arrays.copyOfRange(b, dataStart, end));
	}

	/** Position just past the NUL-terminated description, honouring the frame's text encoding. */
	private static int endOfText(byte[] b, int start, int end, int encoding) {
		boolean wide = encoding == 1 || encoding == 2;   // UTF-16 with BOM / UTF-16BE
		if (!wide) {
			int zero = indexOfZero(b, start, end);
			return zero < 0 ? -1 : zero + 1;
		}
		for (int i = start; i + 1 < end; i += 2) {
			if (b[i] == 0 && b[i + 1] == 0) {
				return i + 2;
			}
		}
		return -1;
	}

	/** Undoes ID3 unsynchronisation: every {@code FF 00} pair collapses back to {@code FF}. */
	private static byte[] deUnsynchronise(byte[] b, int off, int len) {
		byte[] out = new byte[len];
		int w = 0;
		for (int i = 0; i < len; i++) {
			byte cur = b[off + i];
			out[w++] = cur;
			if (cur == (byte) 0xFF && i + 1 < len && b[off + i + 1] == 0) {
				i++;
			}
		}
		return w == len ? out : Arrays.copyOf(out, w);
	}

	/** Front cover first, then "other"/unset, then anything else (back cover, band photo, ...). */
	private static int rank(int pictureType) {
		if (pictureType == PIC_TYPE_FRONT_COVER) {
			return 0;
		}
		return pictureType == 0 ? 1 : 2;
	}

	private record Picture(int type, byte[] data) {
	}

	// ----------------------------------------------------------------- FLAC

	private static byte[] flacPicture(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] magic = tryReadFully(in, 4);
			if (magic == null) {
				return null;
			}
			if (magic[0] != 'f' || magic[1] != 'L' || magic[2] != 'a' || magic[3] != 'C') {
				return null;   // ID3-prefixed or not FLAC at all: the caller's ID3 fallback covers it
			}
			return flacPictureBlocks(in);
		}
	}

	private static byte[] flacPictureBlocks(InputStream in) throws IOException {
		byte[] best = null;
		int bestRank = Integer.MAX_VALUE;
		while (true) {
			byte[] header = tryReadFully(in, 4);
			if (header == null) {
				return best;
			}
			boolean last = (header[0] & 0x80) != 0;
			int type = header[0] & 0x7F;
			int length = ((header[1] & 0xFF) << 16) | ((header[2] & 0xFF) << 8) | (header[3] & 0xFF);
			if (length > MAX_TAG_BYTES) {
				return best;
			}
			if (type == 6) {
				byte[] block = tryReadFully(in, length);
				if (block == null) {
					return best;
				}
				Picture picture = parseFlacPictureBlock(block);
				if (picture != null && rank(picture.type()) < bestRank) {
					best = picture.data();
					bestRank = rank(picture.type());
					if (bestRank == 0) {
						return best;
					}
				}
			} else if (!skipFully(in, length)) {
				return best;
			}
			if (last) {
				return best;
			}
		}
	}

	/**
	 * The FLAC {@code PICTURE} block body, which the Ogg {@code METADATA_BLOCK_PICTURE} comment
	 * reuses verbatim after base64 decoding.
	 */
	private static Picture parseFlacPictureBlock(byte[] b) {
		if (b == null || b.length < 32) {
			return null;
		}
		int pos = 0;
		int type = bigEndianInt(b, pos);
		pos += 4;
		int mimeLen = bigEndianInt(b, pos);
		pos += 4;
		if (mimeLen < 0 || pos + mimeLen > b.length) {
			return null;
		}
		String mime = new String(b, pos, mimeLen, StandardCharsets.US_ASCII);
		pos += mimeLen;
		if ("-->".equals(mime.trim())) {
			return null;
		}
		if (pos + 4 > b.length) {
			return null;
		}
		int descLen = bigEndianInt(b, pos);
		pos += 4;
		if (descLen < 0 || pos + descLen > b.length) {
			return null;
		}
		pos += descLen;
		pos += 16;   // width, height, colour depth, indexed-colour count
		if (pos < 0 || pos + 4 > b.length) {
			return null;
		}
		int dataLen = bigEndianInt(b, pos);
		pos += 4;
		if (dataLen <= 0 || pos + dataLen > b.length) {
			return null;
		}
		return new Picture(type, Arrays.copyOfRange(b, pos, pos + dataLen));
	}

	// ------------------------------------------------------------------ Ogg

	private static byte[] oggPicture(Path file) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			long consumed = 0;
			// Packets straddle pages, so segments accumulate until a lacing value below 255
			// closes the packet.
			ByteArrayOutputStream packet = new ByteArrayOutputStream();
			while (consumed < MAX_OGG_SCAN_BYTES) {
				byte[] header = tryReadFully(in, 27);
				if (header == null) {
					return null;
				}
				consumed += 27;
				if (header[0] != 'O' || header[1] != 'g' || header[2] != 'g' || header[3] != 'S') {
					return null;
				}
				int segments = header[26] & 0xFF;
				byte[] table = tryReadFully(in, segments);
				if (table == null) {
					return null;
				}
				consumed += segments;
				for (int i = 0; i < segments; i++) {
					int lacing = table[i] & 0xFF;
					byte[] segment = tryReadFully(in, lacing);
					if (segment == null) {
						return null;
					}
					consumed += lacing;
					if (packet.size() + lacing <= MAX_TAG_BYTES) {
						packet.write(segment, 0, lacing);
					}
					if (lacing < 255) {
						byte[] complete = packet.toByteArray();
						packet.reset();
						byte[] found = oggCommentPicture(complete);
						if (found != null) {
							return found;
						}
					}
				}
			}
			return null;
		}
	}

	/** Recognises the Vorbis and Opus comment packets and reads their picture fields. */
	private static byte[] oggCommentPicture(byte[] packet) {
		int pos;
		if (packet.length > 7 && (packet[0] & 0xFF) == 3
				&& "vorbis".equals(new String(packet, 1, 6, StandardCharsets.US_ASCII))) {
			pos = 7;
		} else if (packet.length > 8
				&& "OpusTags".equals(new String(packet, 0, 8, StandardCharsets.US_ASCII))) {
			pos = 8;
		} else {
			return null;
		}

		if (pos + 4 > packet.length) {
			return null;
		}
		int vendorLen = littleEndianInt(packet, pos);
		pos += 4;
		if (vendorLen < 0 || pos + vendorLen + 4 > packet.length) {
			return null;
		}
		pos += vendorLen;
		int count = littleEndianInt(packet, pos);
		pos += 4;
		if (count < 0 || count > 100_000) {
			return null;
		}

		byte[] best = null;
		int bestRank = Integer.MAX_VALUE;
		byte[] legacyCoverArt = null;
		for (int i = 0; i < count; i++) {
			if (pos + 4 > packet.length) {
				break;
			}
			int len = littleEndianInt(packet, pos);
			pos += 4;
			if (len < 0 || pos + len > packet.length) {
				break;
			}
			int eq = -1;
			for (int j = pos; j < pos + len; j++) {
				if (packet[j] == '=') {
					eq = j;
					break;
				}
			}
			if (eq > 0) {
				String key = new String(packet, pos, eq - pos, StandardCharsets.US_ASCII)
						.toUpperCase(Locale.ROOT);
				if ("METADATA_BLOCK_PICTURE".equals(key)) {
					Picture picture = parseFlacPictureBlock(decodeBase64(packet, eq + 1, pos + len));
					if (picture != null && rank(picture.type()) < bestRank) {
						best = picture.data();
						bestRank = rank(picture.type());
					}
				} else if ("COVERART".equals(key)) {
					legacyCoverArt = decodeBase64(packet, eq + 1, pos + len);   // pre-2009 convention
				}
			}
			pos += len;
		}
		return best != null ? best : legacyCoverArt;
	}

	private static byte[] decodeBase64(byte[] b, int from, int to) {
		if (to <= from) {
			return null;
		}
		try {
			return Base64.getMimeDecoder().decode(Arrays.copyOfRange(b, from, to));
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	// ------------------------------------------------------- RIFF/WAVE, AIFF

	private static byte[] riffPicture(Path file) throws IOException {
		return chunkedContainerPicture(file, "RIFF", "WAVE", true);
	}

	private static byte[] aiffPicture(Path file) throws IOException {
		return chunkedContainerPicture(file, "FORM", null, false);
	}

	/**
	 * Walks an IFF-style chunk list looking for an embedded ID3 tag. RIFF sizes are
	 * little-endian and AIFF's are big-endian; both pad odd-length chunks to an even boundary.
	 */
	private static byte[] chunkedContainerPicture(Path file, String magic, String form,
			boolean littleEndian) throws IOException {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] header = tryReadFully(in, 12);
			if (header == null) {
				return null;
			}
			if (!magic.equals(new String(header, 0, 4, StandardCharsets.US_ASCII))) {
				return null;
			}
			if (form != null && !form.equals(new String(header, 8, 4, StandardCharsets.US_ASCII))) {
				return null;
			}
			while (true) {
				byte[] chunk = tryReadFully(in, 8);
				if (chunk == null) {
					return null;
				}
				String id = new String(chunk, 0, 4, StandardCharsets.US_ASCII);
				int size = littleEndian ? littleEndianInt(chunk, 4) : bigEndianInt(chunk, 4);
				if (size < 0) {
					return null;
				}
				if ("id3 ".equalsIgnoreCase(id)) {
					if (size > MAX_TAG_BYTES) {
						return null;
					}
					byte[] tag = tryReadFully(in, size);
					if (tag == null || tag.length < 10
							|| tag[0] != 'I' || tag[1] != 'D' || tag[2] != '3') {
						return null;
					}
					int declared = syncSafeInt(tag, 6);
					int available = Math.min(declared, tag.length - 10);
					if (available <= 0) {
						return null;
					}
					return id3Picture(Arrays.copyOfRange(tag, 10, 10 + available),
							tag[3] & 0xFF, tag[5] & 0xFF);
				}
				if (!skipFully(in, size + (size & 1))) {
					return null;
				}
			}
		}
	}

	// ------------------------------------------------------------- decoding

	private static BufferedImage decode(byte[] bytes) {
		if (bytes == null || bytes.length < 16 || bytes.length > MAX_IMAGE_BYTES) {
			return null;
		}
		BufferedImage raw;
		try {
			raw = ImageIO.read(new ByteArrayInputStream(bytes));
		} catch (IOException | RuntimeException e) {
			log.debug("Cover art payload is not a decodable image: {}", e.toString());
			return null;
		}
		if (raw == null || raw.getWidth() <= 0 || raw.getHeight() <= 0) {
			return null;
		}
		return downscale(raw);
	}

	/** Keeps the retained image small: it is only ever drawn as a corner thumbnail. */
	private static BufferedImage downscale(BufferedImage src) {
		int longest = Math.max(src.getWidth(), src.getHeight());
		if (longest <= MAX_EDGE) {
			return src;
		}
		double scale = MAX_EDGE / (double) longest;
		int w = Math.max(1, (int) Math.round(src.getWidth() * scale));
		int h = Math.max(1, (int) Math.round(src.getHeight() * scale));
		BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g2 = out.createGraphics();
		try {
			g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g2.drawImage(src, 0, 0, w, h, null);
		} finally {
			g2.dispose();
		}
		return out;
	}

	// -------------------------------------------------------------- plumbing

	private static int syncSafeInt(byte[] b, int off) {
		return ((b[off] & 0x7F) << 21) | ((b[off + 1] & 0x7F) << 14)
				| ((b[off + 2] & 0x7F) << 7) | (b[off + 3] & 0x7F);
	}

	private static int bigEndianInt(byte[] b, int off) {
		return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
				| ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
	}

	private static int littleEndianInt(byte[] b, int off) {
		return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
				| ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
	}

	private static int indexOfZero(byte[] b, int from, int to) {
		for (int i = from; i < to; i++) {
			if (b[i] == 0) {
				return i;
			}
		}
		return -1;
	}

	/** Reads exactly {@code count} bytes, or returns {@code null} if the file ends first. */
	private static byte[] tryReadFully(InputStream in, int count) throws IOException {
		if (count < 0) {
			return null;
		}
		byte[] buf = new byte[count];
		int read = 0;
		while (read < count) {
			int n = in.read(buf, read, count - read);
			if (n < 0) {
				return null;
			}
			read += n;
		}
		return buf;
	}

	private static boolean skipFully(InputStream in, long count) throws IOException {
		long remaining = count;
		while (remaining > 0) {
			long skipped = in.skip(remaining);
			if (skipped <= 0) {
				if (in.read() < 0) {
					return false;
				}
				skipped = 1;
			}
			remaining -= skipped;
		}
		return true;
	}
}
