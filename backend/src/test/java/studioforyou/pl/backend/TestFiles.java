package studioforyou.pl.backend;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.imageio.ImageIO;

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

	/**
	 * A real JPEG photo of the given size as its pixels are stored, in four quarters: red top left,
	 * green top right, blue bottom left and yellow bottom right; with an EXIF orientation unless it
	 * is 1.
	 */
	public static byte[] photo(int width, int height, int orientation) {
		byte[] jpeg = encode(quarters(width, height, BufferedImage.TYPE_INT_RGB), "jpeg");
		if (orientation == 1) {
			return jpeg;
		}
		// After the JFIF APP0 segment: APP1 with "Exif\0\0" and an IFD with the Orientation (SHORT).
		int app0End = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF));
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(jpeg, 0, app0End);
		write(out, 0xFF, 0xE1, 0x00, 0x22, 'E', 'x', 'i', 'f', 0, 0);
		write(out, 'M', 'M', 0x00, 0x2A, 0x00, 0x00, 0x00, 0x08, 0x00, 0x01);
		write(out, 0x01, 0x12, 0x00, 0x03, 0x00, 0x00, 0x00, 0x01, 0x00, orientation, 0x00, 0x00);
		write(out, 0x00, 0x00, 0x00, 0x00);
		out.write(jpeg, app0End, jpeg.length - app0End);
		return out.toByteArray();
	}

	/**
	 * A real PNG image of the given size in the quarters of {@link #photo}, its top left corner (a
	 * tenth of the shorter side) transparent.
	 */
	public static byte[] pngPhoto(int width, int height) {
		BufferedImage image = quarters(width, height, BufferedImage.TYPE_INT_ARGB);
		int corner = Math.max(1, Math.min(width, height) / 10);
		for (int x = 0; x < corner; x++) {
			for (int y = 0; y < corner; y++) {
				image.setRGB(x, y, 0);
			}
		}
		return encode(image, "png");
	}

	private static BufferedImage quarters(int width, int height, int type) {
		BufferedImage image = new BufferedImage(width, height, type);
		Graphics2D graphics = image.createGraphics();
		Color[] colors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW };
		for (int i = 0; i < 4; i++) {
			graphics.setColor(colors[i]);
			graphics.fillRect((i % 2) * width / 2, (i / 2) * height / 2, width - width / 2, height - height / 2);
		}
		graphics.dispose();
		return image;
	}

	private static byte[] encode(BufferedImage image, String format) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(image, format, out);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return out.toByteArray();
	}

	/** The XMP GPS values of {@link #jpegWithLocation()}. */
	public static final String[] XMP_LOCATION = { "51,6.5N", "17,2E", "120/1" };

	/**
	 * A TIFF (EXIF) structure with orientation 6 and a GPS block holding the latitude
	 * {@link #latitude(boolean) 51°6'30"} N.
	 */
	public static byte[] exifWithLocation(boolean littleEndian) {
		ByteBuffer tiff = ByteBuffer.allocate(92).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		tiff.put(littleEndian ? new byte[] { 'I', 'I' } : new byte[] { 'M', 'M' }).putShort((short) 42).putInt(8);
		// IFD0 at 8: Orientation (SHORT) and the GPS pointer (LONG) to 38.
		tiff.putShort((short) 2);
		tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0);
		tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(38);
		tiff.putInt(0);
		// GPS IFD at 38: GPSLatitudeRef "N" (ASCII, in the entry) and GPSLatitude (3 RATIONAL at 68).
		tiff.putShort((short) 2);
		tiff.putShort((short) 1).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0);
		tiff.putShort((short) 2).putShort((short) 5).putInt(3).putInt(68);
		tiff.putInt(0);
		tiff.put(latitude(littleEndian));
		return tiff.array();
	}

	/** The latitude 51/1, 6/1, 30/1 as {@link #exifWithLocation(boolean)} stores it. */
	public static byte[] latitude(boolean littleEndian) {
		ByteBuffer value = ByteBuffer.allocate(24).order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
		return value.putInt(51).putInt(1).putInt(6).putInt(1).putInt(30).putInt(1).array();
	}

	/**
	 * The start of a JPEG photo of 200 x 100 pixels turned by EXIF orientation 6, taken at a place
	 * written in its EXIF GPS block and in XMP.
	 */
	public static byte[] jpegWithLocation() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, 0xFF, 0xD8);
		byte[] exif = exifWithLocation(false);
		write(out, 0xFF, 0xE1, 0x00, 2 + 6 + exif.length);
		write(out, 'E', 'x', 'i', 'f', 0, 0);
		out.writeBytes(exif);
		byte[] xmp = ("http://ns.adobe.com/xap/1.0/\0<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF>"
				+ "<rdf:Description exif:GPSLatitude=\"" + XMP_LOCATION[0] + "\" exif:GPSLongitude='" + XMP_LOCATION[1]
				+ "' tiff:Orientation=\"6\"><exif:GPSAltitude>" + XMP_LOCATION[2]
				+ "</exif:GPSAltitude></rdf:Description></rdf:RDF></x:xmpmeta>")
			.getBytes(StandardCharsets.UTF_8);
		write(out, 0xFF, 0xE1, (2 + xmp.length) >> 8, (2 + xmp.length) & 0xFF);
		out.writeBytes(xmp);
		write(out, 0xFF, 0xC0, 0x00, 0x11, 0x08, 0x00, 0x64, 0x00, 0xC8, 0x03);
		write(out, 0x01, 0x22, 0x00, 0x02, 0x11, 0x01, 0x03, 0x11, 0x01);
		write(out, 0xFF, 0xD9);
		return out.toByteArray();
	}

	/** A PNG image of 640 x 480 pixels with a location in its {@code eXIf} chunk (little-endian EXIF). */
	public static byte[] pngWithLocation() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		write(out, 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n');
		chunk(out, "IHDR", new byte[] { 0, 0, 2, (byte) 0x80, 0, 0, 1, (byte) 0xE0, 8, 2, 0, 0, 0 });
		chunk(out, "eXIf", exifWithLocation(true));
		chunk(out, "IEND", new byte[0]);
		return out.toByteArray();
	}

	/** A HEIC photo whose Exif item holds a location. */
	public static byte[] heicWithLocation() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(heic());
		// An Exif item: the offset of the TIFF header, "Exif\0\0", the TIFF structure.
		write(out, 0x00, 0x00, 0x00, 0x06, 'E', 'x', 'i', 'f', 0, 0);
		out.writeBytes(exifWithLocation(false));
		return out.toByteArray();
	}

	/** Whether the CRC of every chunk of the PNG matches its type and data. */
	public static boolean pngChecksumsMatch(byte[] png) {
		ByteBuffer buffer = ByteBuffer.wrap(png);
		int at = 8;
		while (at + 12 <= png.length) {
			int length = buffer.getInt(at);
			CRC32 crc = new CRC32();
			crc.update(png, at + 4, 4 + length);
			if ((int) crc.getValue() != buffer.getInt(at + 8 + length)) {
				return false;
			}
			at += 12 + length;
		}
		return at == png.length;
	}

	private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
		ByteBuffer header = ByteBuffer.allocate(8).putInt(data.length).put(type.getBytes(StandardCharsets.US_ASCII));
		out.writeBytes(header.array());
		out.writeBytes(data);
		CRC32 crc = new CRC32();
		crc.update(header.array(), 4, 4);
		crc.update(data);
		out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
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
