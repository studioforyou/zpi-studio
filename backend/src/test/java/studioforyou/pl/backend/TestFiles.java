package studioforyou.pl.backend;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** The smallest files of each accepted type that are recognized from their content. */
public final class TestFiles {

	private TestFiles() {
	}

	/**
	 * The start of a JPEG photo of 200 x 100 pixels as stored, with an EXIF orientation (1 keeps it,
	 * 6 turns it by 90 degrees, so it is displayed as 100 x 200).
	 */
	public static byte[] jpeg(int orientation) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, 0xFF, 0xD8);
		// APP1 with "Exif\0\0" and a big-endian TIFF header with one IFD entry: Orientation (SHORT).
		write(out, 0xFF, 0xE1, 0x00, 0x22);
		write(out, 'E', 'x', 'i', 'f', 0, 0);
		write(out, 'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08);
		write(out, 0x00, 0x01);
		write(out, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation, 0x00, 0x00);
		write(out, 0x00, 0x00, 0x00, 0x00);
		// SOF0: 8 bits, height 100, width 200, 3 components.
		write(out, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0x00, 0x64, 0x00, 0xC8, 0x03);
		write(out, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01);
		write(out, 0xFF, 0xD9);
		return out.toByteArray();
	}

	/** The start of a PNG image of 640 x 480 pixels. */
	public static byte[] png() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n');
		write(out, 0x00, 0x00, 0x00, 0x0D, 'I', 'H', 'D', 'R');
		write(out, 0x00, 0x00, 0x02, 0x80, 0x00, 0x00, 0x01, 0xE0, 0x08, 0x02, 0x00, 0x00, 0x00);
		write(out, 0x00, 0x00, 0x00, 0x00);
		return out.toByteArray();
	}

	/** The start of a HEIC photo. */
	public static byte[] heic() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, 0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c');
		write(out, 0x00, 0x00, 0x00, 0x00, 'm', 'i', 'f', '1', 'h', 'e', 'i', 'c');
		return out.toByteArray();
	}

	public static byte[] pdf() {
		return "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
	}

	/** A Word document: an Office Open XML archive with a {@code word/} part. */
	public static byte[] docx() {
		return zip("[Content_Types].xml", "word/document.xml");
	}

	/** An Excel workbook: an Office Open XML archive with an {@code xl/} part. */
	public static byte[] xlsx() {
		return zip("[Content_Types].xml", "xl/workbook.xml");
	}

	/** A ZIP archive with empty XML entries of the given names. */
	public static byte[] zip(String... entries) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(out)) {
			for (String entry : entries) {
				zip.putNextEntry(new ZipEntry(entry));
				zip.write("<?xml version=\"1.0\"?><x/>".getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	private static void write(ByteArrayOutputStream out, int... bytes) {
		for (int value : bytes) {
			out.write(value);
		}
	}

}
