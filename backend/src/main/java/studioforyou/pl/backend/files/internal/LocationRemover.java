package studioforyou.pl.backend.files.internal;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;

import studioforyou.pl.backend.files.FileType;

/**
 * Removes where a photo was taken: phones write the place in the EXIF GPS block, and some apps in
 * XMP ({@code exif:GPSLatitude} and the like), and the app's photo picker keeps them.
 * <p>
 * Every EXIF (TIFF) structure in the file is looked for, so this covers a JPEG's APP1 segment
 * and the extra images some phones put after it, a PNG's {@code eXIf} chunk and a HEIC's Exif item
 * alike. The GPS block is zeroed and its pointer taken out of the first IFD; everything else,
 * the orientation among it, stays. XMP GPS properties, names and values, are overwritten with
 * spaces, which XML takes as the whitespace between attributes or elements. The file keeps its
 * size and its pixels are not touched; a PNG gets its chunk checksums back.
 */
final class LocationRemover {

	/** The EXIF tag pointing to the GPS block. */
	private static final int GPS_POINTER = 0x8825;

	/** Most entries an IFD is taken to have; more means it is not one. */
	private static final int MAX_ENTRIES = 1000;

	/** Bytes of one value of each TIFF field type (1-13), by type. */
	private static final int[] TYPE_SIZES = { 0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4 };

	/** XMP GPS properties written as attributes, e.g. {@code exif:GPSLatitude="51,6.5N"}. */
	private static final Pattern XMP_ATTRIBUTE = Pattern
		.compile("[A-Za-z][\\w.-]{0,40}:GPS[A-Za-z]{0,40}\\s{0,10}=\\s{0,10}(?:\"([^\"<>]{0,500})\"|'([^'<>]{0,500})')");

	/** XMP GPS properties written as elements, e.g. {@code <exif:GPSLatitude>51,6.5N</exif:GPSLatitude>}. */
	private static final Pattern XMP_ELEMENT = Pattern
		.compile("<([A-Za-z][\\w.-]{0,40}:GPS[A-Za-z]{0,40})(?:\\s[^<>]{0,500})?>([^<]{0,500})</\\1>");

	private LocationRemover() {
	}

	/**
	 * Removes the location from the photo in place.
	 *
	 * @return whether there was one
	 */
	static boolean strip(byte[] content, FileType type) {
		boolean found = false;
		for (int i = 0; i + 8 <= content.length; i++) {
			if (isTiffHeader(content, i)) {
				found |= stripTiff(content, i);
			}
		}
		found |= stripXmp(content);
		if (found && type == FileType.PNG) {
			fixPngChecksums(content);
		}
		return found;
	}

	private static boolean isTiffHeader(byte[] b, int at) {
		return (b[at] == 'I' && b[at + 1] == 'I' && b[at + 2] == 42 && b[at + 3] == 0)
				|| (b[at] == 'M' && b[at + 1] == 'M' && b[at + 2] == 0 && b[at + 3] == 42);
	}

	/**
	 * Zeroes the GPS block of the TIFF structure starting at {@code tiff} and takes its pointer
	 * out of the first IFD, if the structure is a valid one with such a pointer.
	 */
	private static boolean stripTiff(byte[] b, int tiff) {
		boolean little = b[tiff] == 'I';
		long firstOffset = u32(b, tiff + 4, little);
		if (firstOffset < 8 || tiff + firstOffset + 2 > b.length) {
			return false;
		}
		int ifd = tiff + (int) firstOffset;
		int count = u16(b, ifd, little);
		if (count == 0 || count > MAX_ENTRIES || ifd + 2 + 12L * count + 4 > b.length) {
			return false;
		}
		int gpsIndex = -1;
		for (int k = 0; k < count; k++) {
			int entry = ifd + 2 + 12 * k;
			int fieldType = u16(b, entry + 2, little);
			if (fieldType < 1 || fieldType >= TYPE_SIZES.length) {
				// Not an IFD: some other bytes that happen to look like a TIFF header.
				return false;
			}
			if (u16(b, entry, little) == GPS_POINTER && (fieldType == 4 || fieldType == 13)
					&& u32(b, entry + 4, little) == 1) {
				gpsIndex = k;
			}
		}
		if (gpsIndex < 0) {
			return false;
		}
		zeroGpsBlock(b, tiff, u32(b, ifd + 2 + 12 * gpsIndex + 8, little), little);
		// Take the pointer out: the entries after it move up, the next-IFD offset follows them.
		int entry = ifd + 2 + 12 * gpsIndex;
		int nextOffset = ifd + 2 + 12 * count;
		byte[] next = Arrays.copyOfRange(b, nextOffset, nextOffset + 4);
		System.arraycopy(b, entry + 12, b, entry, 12 * (count - gpsIndex - 1));
		putU16(b, ifd, count - 1, little);
		int newNextOffset = ifd + 2 + 12 * (count - 1);
		System.arraycopy(next, 0, b, newNextOffset, 4);
		Arrays.fill(b, newNextOffset + 4, nextOffset + 4, (byte) 0);
		return true;
	}

	/** Zeroes the GPS IFD and the values it points to. */
	private static void zeroGpsBlock(byte[] b, int tiff, long offset, boolean little) {
		if (offset < 8 || tiff + offset + 2 > b.length) {
			return;
		}
		int gps = tiff + (int) offset;
		int count = u16(b, gps, little);
		long end = gps + 2 + 12L * count + 4;
		if (count > MAX_ENTRIES || end > b.length) {
			return;
		}
		for (int k = 0; k < count; k++) {
			int entry = gps + 2 + 12 * k;
			int fieldType = u16(b, entry + 2, little);
			long size = (fieldType >= 1 && fieldType < TYPE_SIZES.length)
					? TYPE_SIZES[fieldType] * u32(b, entry + 4, little) : 0;
			long valueOffset = u32(b, entry + 8, little);
			// Values longer than four bytes are elsewhere; shorter ones are in the entry itself.
			if (size > 4 && valueOffset >= 8 && tiff + valueOffset + size <= b.length) {
				Arrays.fill(b, tiff + (int) valueOffset, tiff + (int) (valueOffset + size), (byte) 0);
			}
		}
		Arrays.fill(b, gps, (int) end, (byte) 0);
	}

	/** Overwrites XMP GPS properties with spaces, keeping every other byte where it is. */
	private static boolean stripXmp(byte[] b) {
		// One character per byte, so positions in the text are positions in the file.
		String text = new String(b, StandardCharsets.ISO_8859_1);
		if (!text.contains(":GPS")) {
			return false;
		}
		boolean found = false;
		Matcher attributes = XMP_ATTRIBUTE.matcher(text);
		while (attributes.find()) {
			found |= blank(b, attributes.start(), attributes.end());
		}
		Matcher elements = XMP_ELEMENT.matcher(text);
		while (elements.find()) {
			found |= blank(b, elements.start(), elements.end());
		}
		return found;
	}

	private static boolean blank(byte[] b, int start, int end) {
		boolean changed = false;
		for (int i = start; i < end; i++) {
			changed |= b[i] != ' ';
			b[i] = ' ';
		}
		return changed;
	}

	/** Computes the CRC of every PNG chunk again, after its data changed. */
	private static void fixPngChecksums(byte[] b) {
		int at = 8;
		while (at + 12 <= b.length) {
			long length = u32(b, at, false);
			if (at + 12 + length > b.length) {
				return;
			}
			CRC32 crc = new CRC32();
			crc.update(b, at + 4, 4 + (int) length);
			long value = crc.getValue();
			int crcAt = at + 8 + (int) length;
			putU16(b, crcAt, (int) (value >>> 16), false);
			putU16(b, crcAt + 2, (int) (value & 0xFFFF), false);
			at = crcAt + 4;
		}
	}

	private static int u16(byte[] b, int at, boolean little) {
		int x = b[at] & 0xFF;
		int y = b[at + 1] & 0xFF;
		return little ? (y << 8) | x : (x << 8) | y;
	}

	private static long u32(byte[] b, int at, boolean little) {
		long first = u16(b, at, little);
		long second = u16(b, at + 2, little);
		return little ? (second << 16) | first : (first << 16) | second;
	}

	private static void putU16(byte[] b, int at, int value, boolean little) {
		byte high = (byte) (value >>> 8);
		byte low = (byte) value;
		b[at] = little ? low : high;
		b[at + 1] = little ? high : low;
	}

}
