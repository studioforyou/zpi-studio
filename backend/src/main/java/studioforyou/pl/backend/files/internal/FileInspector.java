package studioforyou.pl.backend.files.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.jspecify.annotations.Nullable;

import org.springframework.core.io.InputStreamSource;

import studioforyou.pl.backend.files.FileType;

/**
 * Recognizes the type of a file from its content, and reads its SHA-256 and, for JPEG and PNG
 * images, the size the image is displayed with. Only the first bytes of a file and the directory
 * of a ZIP archive are looked at, so nothing is decoded.
 */
final class FileInspector {

	/** How much of the start of a file is kept for recognizing it and reading an image's size. */
	static final int HEAD_SIZE = 256 * 1024;

	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] PDF_SIGNATURE = { '%', 'P', 'D', 'F', '-' };

	private static final byte[] ZIP_SIGNATURE = { 'P', 'K', 3, 4 };

	/** Major brands of the ISO base media file format used by HEIC/HEIF images. */
	private static final Set<String> HEIF_BRANDS = Set.of("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm",
			"hevs", "mif1", "msf1");

	/** EXIF orientations that turn the image by 90 degrees, so its width and height swap. */
	private static final Set<Integer> TURNED_ORIENTATIONS = Set.of(5, 6, 7, 8);

	private FileInspector() {
	}

	/**
	 * Looks at the file.
	 *
	 * @param name the name the client gave it; only a {@code *.txt} file can be text
	 */
	static Inspection inspect(InputStreamSource content, @Nullable String name) throws IOException {
		byte[] head;
		String sha256;
		try (InputStream in = content.getInputStream()) {
			MessageDigest digest = sha256();
			DigestInputStream digesting = new DigestInputStream(in, digest);
			head = digesting.readNBytes(HEAD_SIZE);
			digesting.transferTo(OutputStream.nullOutputStream());
			sha256 = HexFormat.of().formatHex(digest.digest());
		}
		FileType type = detect(head, content, name);
		int[] size = (type == FileType.JPEG) ? jpegSize(head) : (type == FileType.PNG) ? pngSize(head) : null;
		return new Inspection(type, sha256, (size != null) ? size[0] : null, (size != null) ? size[1] : null);
	}

	private static @Nullable FileType detect(byte[] head, InputStreamSource content, @Nullable String name)
			throws IOException {
		if (head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xFF) == 0xD8 && (head[2] & 0xFF) == 0xFF) {
			return FileType.JPEG;
		}
		if (startsWith(head, PNG_SIGNATURE)) {
			return FileType.PNG;
		}
		if (head.length >= 12 && head[4] == 'f' && head[5] == 't' && head[6] == 'y' && head[7] == 'p'
				&& HEIF_BRANDS.contains(new String(head, 8, 4, StandardCharsets.US_ASCII))) {
			return FileType.HEIC;
		}
		if (startsWith(head, PDF_SIGNATURE)) {
			return FileType.PDF;
		}
		if (startsWith(head, ZIP_SIGNATURE)) {
			return officeDocument(content);
		}
		if (name != null && FileType.TXT.matchesName(name) && isUtf8Text(content)) {
			return FileType.TXT;
		}
		return null;
	}

	/**
	 * A Word or Excel document (Office Open XML), by the parts in the archive's directory. The
	 * entries are not decompressed.
	 */
	private static @Nullable FileType officeDocument(InputStreamSource content) throws IOException {
		Path copy = Files.createTempFile("upload-", ".zip");
		try {
			try (InputStream in = content.getInputStream()) {
				Files.copy(in, copy, StandardCopyOption.REPLACE_EXISTING);
			}
			boolean contentTypes = false;
			boolean word = false;
			boolean excel = false;
			try (ZipFile zip = new ZipFile(copy.toFile())) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					String entry = entries.nextElement().getName();
					contentTypes |= entry.equals("[Content_Types].xml");
					word |= entry.startsWith("word/");
					excel |= entry.startsWith("xl/");
				}
			}
			catch (ZipException ex) {
				return null;
			}
			if (!contentTypes || word == excel) {
				return null;
			}
			return word ? FileType.DOCX : FileType.XLSX;
		}
		finally {
			Files.deleteIfExists(copy);
		}
	}

	/** Valid UTF-8 (with or without a byte order mark) without NUL characters. */
	private static boolean isUtf8Text(InputStreamSource content) throws IOException {
		byte[] bytes;
		try (InputStream in = content.getInputStream()) {
			bytes = in.readAllBytes();
		}
		CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
			.onMalformedInput(CodingErrorAction.REPORT)
			.onUnmappableCharacter(CodingErrorAction.REPORT);
		try {
			return decoder.decode(ByteBuffer.wrap(bytes)).chars().noneMatch((c) -> c == 0);
		}
		catch (CharacterCodingException ex) {
			return false;
		}
	}

	/** Width and height from the PNG's IHDR chunk. */
	private static int @Nullable [] pngSize(byte[] head) {
		if (head.length < 24 || head[12] != 'I' || head[13] != 'H' || head[14] != 'D' || head[15] != 'R') {
			return null;
		}
		return positive(u32(head, 16, false), u32(head, 20, false));
	}

	/**
	 * Width and height from the JPEG's frame header, swapped when the EXIF orientation turns the
	 * image, so they are the size it is displayed with.
	 */
	static int @Nullable [] jpegSize(byte[] head) {
		int i = 2;
		Integer orientation = null;
		while (i + 4 <= head.length) {
			if ((head[i] & 0xFF) != 0xFF) {
				return null;
			}
			int marker = head[i + 1] & 0xFF;
			if (marker == 0xFF) {
				i++;
				continue;
			}
			if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD8)) {
				i += 2;
				continue;
			}
			if (marker == 0xD9 || marker == 0xDA) {
				return null;
			}
			int length = u16(head, i + 2, false);
			if (length < 2) {
				return null;
			}
			int data = i + 4;
			if (marker == 0xE1 && orientation == null) {
				orientation = exifOrientation(head, data, Math.min(head.length, data + length - 2));
			}
			if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
				if (data + 5 > head.length) {
					return null;
				}
				int height = u16(head, data + 1, false);
				int width = u16(head, data + 3, false);
				return (orientation != null && TURNED_ORIENTATIONS.contains(orientation)) ? positive(height, width)
						: positive(width, height);
			}
			i = data + length - 2;
		}
		return null;
	}

	/** The Orientation tag (0x0112) of the first IFD of an EXIF APP1 segment, if it has one. */
	private static @Nullable Integer exifOrientation(byte[] head, int start, int end) {
		if (end - start < 14 || head[start] != 'E' || head[start + 1] != 'x' || head[start + 2] != 'i'
				|| head[start + 3] != 'f' || head[start + 4] != 0 || head[start + 5] != 0) {
			return null;
		}
		int tiff = start + 6;
		boolean little;
		if (head[tiff] == 'I' && head[tiff + 1] == 'I') {
			little = true;
		}
		else if (head[tiff] == 'M' && head[tiff + 1] == 'M') {
			little = false;
		}
		else {
			return null;
		}
		if (u16(head, tiff + 2, little) != 42) {
			return null;
		}
		long offset = u32(head, tiff + 4, little);
		if (offset < 8 || tiff + offset + 2 > end) {
			return null;
		}
		int ifd = tiff + (int) offset;
		int count = u16(head, ifd, little);
		for (int k = 0; k < count; k++) {
			int entry = ifd + 2 + 12 * k;
			if (entry + 12 > end) {
				return null;
			}
			if (u16(head, entry, little) == 0x0112) {
				return u16(head, entry + 8, little);
			}
		}
		return null;
	}

	private static int @Nullable [] positive(long width, long height) {
		if (width <= 0 || height <= 0 || width > Integer.MAX_VALUE || height > Integer.MAX_VALUE) {
			return null;
		}
		return new int[] { (int) width, (int) height };
	}

	private static int u16(byte[] bytes, int at, boolean little) {
		int a = bytes[at] & 0xFF;
		int b = bytes[at + 1] & 0xFF;
		return little ? (b << 8) | a : (a << 8) | b;
	}

	private static long u32(byte[] bytes, int at, boolean little) {
		long high = u16(bytes, at, little);
		long low = u16(bytes, at + 2, little);
		return little ? (low << 16) | high : (high << 16) | low;
	}

	private static boolean startsWith(byte[] bytes, byte[] prefix) {
		return bytes.length >= prefix.length && Arrays.equals(bytes, 0, prefix.length, prefix, 0, prefix.length);
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * What the file is.
	 *
	 * @param type its type, or {@code null} when it is none of {@link FileType}
	 * @param width the displayed width of a JPEG or PNG image, if known
	 * @param height the displayed height of a JPEG or PNG image, if known
	 */
	record Inspection(@Nullable FileType type, String sha256, @Nullable Integer width, @Nullable Integer height) {
	}

}
