package studioforyou.pl.backend.files.internal;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import studioforyou.pl.backend.files.FileType;
import studioforyou.pl.backend.files.internal.FileInspector.ImageHeader;

/**
 * Small copies of photos, shown in conversations and lists instead of the whole photo: JPEGs of
 * at most {@link #MAX_SIDE} pixels on the longer side, turned upright by the EXIF orientation and
 * without any metadata. They are made of JPEG and PNG photos; Java cannot read HEIC.
 * <p>
 * Reading a photo takes memory, so it is bounded: a photo of more than {@link #MAX_PIXELS} pixels
 * gets no thumbnail (a small file can unpack into a huge image), a large one is read only every
 * few pixels, and at most {@link #AT_ONCE} thumbnails are made at the same time.
 */
final class Thumbnails {

	/** Longest side of a thumbnail, in pixels: sharp at the width a photo has in a conversation. */
	static final int MAX_SIDE = 1024;

	/** Photos with more pixels get no thumbnail. */
	static final long MAX_PIXELS = 50_000_000;

	/** Most pixels read of a photo; a larger one is read every second, third... pixel. */
	private static final long MAX_READ_PIXELS = 6_000_000;

	private static final float QUALITY = 0.8f;

	/** A thumbnail is kept only when it is smaller than this share of the photo. */
	private static final double MAX_SHARE = 0.75;

	private static final int AT_ONCE = 2;

	/** How long a photo waits for others to be done before it goes without a thumbnail. */
	private static final long WAIT_SECONDS = 10;

	private static final Semaphore permits = new Semaphore(AT_ONCE);

	private static final Logger log = LoggerFactory.getLogger(Thumbnails.class);

	private Thumbnails() {
	}

	/**
	 * The thumbnail of a JPEG or PNG photo, or {@code null} when it gets none: another type, too
	 * many pixels, content that cannot be read, or a thumbnail not much smaller than the photo.
	 */
	static byte @Nullable [] of(byte[] photo, FileType type) {
		ImageHeader header = FileInspector.imageHeader(photo, type);
		if (header == null || (long) header.width() * header.height() > MAX_PIXELS) {
			return null;
		}
		try {
			if (!permits.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS)) {
				log.info("No thumbnail: too many photos are being read at once");
				return null;
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			return null;
		}
		try {
			BufferedImage image = read(photo, header);
			if (image == null) {
				return null;
			}
			byte[] thumbnail = jpeg(upright(scale(image), header.orientation()));
			return (thumbnail.length < photo.length * MAX_SHARE) ? thumbnail : null;
		}
		catch (IOException | RuntimeException ex) {
			// E.g. a CMYK JPEG or a damaged file: the photo is shown whole.
			log.debug("No thumbnail: the photo could not be read", ex);
			return null;
		}
		finally {
			permits.release();
		}
	}

	private static @Nullable BufferedImage read(byte[] photo, ImageHeader header) throws IOException {
		try (ImageInputStream input = new MemoryCacheImageInputStream(new ByteArrayInputStream(photo))) {
			Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
			if (!readers.hasNext()) {
				return null;
			}
			ImageReader reader = readers.next();
			try {
				reader.setInput(input, true, true);
				ImageReadParam param = reader.getDefaultReadParam();
				double pixels = (double) header.width() * header.height();
				int step = (int) Math.ceil(Math.sqrt(pixels / MAX_READ_PIXELS));
				if (step > 1) {
					param.setSourceSubsampling(step, step, 0, 0);
				}
				return reader.read(0, param);
			}
			finally {
				reader.dispose();
			}
		}
	}

	/**
	 * Scales the image down to fit {@link #MAX_SIDE}, halving it step by step so no pixels are
	 * skipped, onto white (a PNG may be transparent, a JPEG cannot).
	 */
	private static BufferedImage scale(BufferedImage image) {
		double factor = Math.min(1.0, (double) MAX_SIDE / Math.max(image.getWidth(), image.getHeight()));
		int width = Math.max(1, (int) Math.round(image.getWidth() * factor));
		int height = Math.max(1, (int) Math.round(image.getHeight() * factor));
		BufferedImage current = image;
		do {
			int stepWidth = Math.max(width, current.getWidth() / 2);
			int stepHeight = Math.max(height, current.getHeight() / 2);
			BufferedImage next = new BufferedImage(stepWidth, stepHeight, BufferedImage.TYPE_INT_RGB);
			Graphics2D graphics = next.createGraphics();
			try {
				graphics.setColor(Color.WHITE);
				graphics.fillRect(0, 0, stepWidth, stepHeight);
				graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
				graphics.drawImage(current, 0, 0, stepWidth, stepHeight, null);
			}
			finally {
				graphics.dispose();
			}
			current = next;
		}
		while (current.getWidth() != width || current.getHeight() != height);
		return current;
	}

	/**
	 * Turns and flips the image as its EXIF orientation says, as the photo is displayed; the
	 * thumbnail has no EXIF to say it.
	 */
	static BufferedImage upright(BufferedImage image, int orientation) {
		if (orientation < 2 || orientation > 8) {
			return image;
		}
		int w = image.getWidth();
		int h = image.getHeight();
		boolean turned = orientation >= 5;
		// Where a pixel (x, y) goes: X = m00 x + m01 y + m02, Y = m10 x + m11 y + m12.
		AffineTransform transform = switch (orientation) {
			case 2 -> new AffineTransform(-1, 0, 0, 1, w, 0); // mirrored
			case 3 -> new AffineTransform(-1, 0, 0, -1, w, h); // turned upside down
			case 4 -> new AffineTransform(1, 0, 0, -1, 0, h); // mirrored upside down
			case 5 -> new AffineTransform(0, 1, 1, 0, 0, 0); // mirrored and turned left
			case 6 -> new AffineTransform(0, 1, -1, 0, h, 0); // turned right
			case 7 -> new AffineTransform(0, -1, -1, 0, h, w); // mirrored and turned right
			default -> new AffineTransform(0, -1, 1, 0, 0, w); // 8: turned left
		};
		BufferedImage result = new BufferedImage(turned ? h : w, turned ? w : h, BufferedImage.TYPE_INT_RGB);
		Graphics2D graphics = result.createGraphics();
		try {
			graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
					RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
			graphics.drawImage(image, transform, null);
		}
		finally {
			graphics.dispose();
		}
		return result;
	}

	private static byte[] jpeg(BufferedImage image) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new IOException("No JPEG writer");
		}
		ImageWriter writer = writers.next();
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ImageOutputStream output = new MemoryCacheImageOutputStream(bytes)) {
			writer.setOutput(output);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(QUALITY);
			writer.write(null, new IIOImage(image, null, null), param);
		}
		finally {
			writer.dispose();
		}
		return bytes.toByteArray();
	}

}
