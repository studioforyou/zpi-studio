package studioforyou.pl.backend.files.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import studioforyou.pl.backend.TestFiles;
import studioforyou.pl.backend.files.FileType;

class ThumbnailsTest {

	@Test
	void aPhotoGetsASmallJpegOfItself() throws IOException {
		byte[] photo = TestFiles.photo(2400, 1800, 1);

		byte[] thumbnail = Thumbnails.of(photo, FileType.JPEG);

		assertThat(thumbnail).isNotNull();
		assertThat(thumbnail.length).isLessThan(photo.length / 2);
		BufferedImage image = read(thumbnail);
		assertThat(image.getWidth()).isEqualTo(1024);
		assertThat(image.getHeight()).isEqualTo(768);
		assertThat(colorAt(image, 0.25, 0.25)).isEqualTo(Color.RED);
		assertThat(colorAt(image, 0.75, 0.25)).isEqualTo(Color.GREEN);
		assertThat(colorAt(image, 0.25, 0.75)).isEqualTo(Color.BLUE);
		assertThat(colorAt(image, 0.75, 0.75)).isEqualTo(Color.YELLOW);
	}

	@Test
	void aPhotoTakenWithThePhoneTurnedIsUprightInItsThumbnail() throws IOException {
		// Orientation 6: the stored pixels are shown turned right.
		BufferedImage image = read(Thumbnails.of(TestFiles.photo(2400, 1800, 6), FileType.JPEG));

		assertThat(image.getWidth()).isEqualTo(768);
		assertThat(image.getHeight()).isEqualTo(1024);
		assertThat(colorAt(image, 0.25, 0.25)).isEqualTo(Color.BLUE);
		assertThat(colorAt(image, 0.75, 0.25)).isEqualTo(Color.RED);
		assertThat(colorAt(image, 0.25, 0.75)).isEqualTo(Color.YELLOW);
		assertThat(colorAt(image, 0.75, 0.75)).isEqualTo(Color.GREEN);
	}

	/** Where the stored top left and top right pixels of a 3 x 2 image end up. */
	@ParameterizedTest
	@CsvSource({ "1, 3, 0, 0, 2, 0", "2, 3, 2, 0, 0, 0", "3, 3, 2, 1, 0, 1", "4, 3, 0, 1, 2, 1",
			"5, 2, 0, 0, 0, 2", "6, 2, 1, 0, 1, 2", "7, 2, 1, 2, 1, 0", "8, 2, 0, 2, 0, 0" })
	void everyOrientationIsTurnedAsTheExifSays(int orientation, int width, int topLeftX, int topLeftY, int topRightX,
			int topRightY) {
		BufferedImage stored = new BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB);
		stored.setRGB(0, 0, 0xFF0000);
		stored.setRGB(2, 0, 0x00FF00);

		BufferedImage upright = Thumbnails.upright(stored, orientation);

		assertThat(upright.getWidth()).isEqualTo(width);
		assertThat(upright.getHeight()).isEqualTo(5 - width);
		assertThat(upright.getRGB(topLeftX, topLeftY) & 0xFFFFFF).isEqualTo(0xFF0000);
		assertThat(upright.getRGB(topRightX, topRightY) & 0xFFFFFF).isEqualTo(0x00FF00);
	}

	@Test
	void aTransparentPngIsOnWhite() throws IOException {
		byte[] png = TestFiles.pngPhoto(3000, 2000);

		BufferedImage image = read(Thumbnails.of(png, FileType.PNG));

		assertThat(image.getWidth()).isEqualTo(1024);
		assertThat(image.getHeight()).isEqualTo(683);
		assertThat(colorAt(image, 0.75, 0.25)).isEqualTo(Color.GREEN);
		assertThat(new Color(image.getRGB(0, 0))).isEqualTo(Color.WHITE);
	}

	@Test
	void somePhotosGetNone() {
		// HEIC cannot be read, nor a damaged photo.
		assertThat(Thumbnails.of(TestFiles.heic(), FileType.HEIC)).isNull();
		assertThat(Thumbnails.of(TestFiles.jpeg(1), FileType.JPEG)).isNull();
		// A small photo would not be much smaller.
		assertThat(Thumbnails.of(TestFiles.photo(120, 80, 1), FileType.JPEG)).isNull();
		// A photo that says it has 100 megapixels is not read.
		byte[] huge = TestFiles.png();
		huge[16] = 0;
		huge[17] = 0;
		huge[18] = 0x27;
		huge[19] = 0x10;
		huge[20] = 0;
		huge[21] = 0;
		huge[22] = 0x27;
		huge[23] = 0x10;
		assertThat(Thumbnails.of(huge, FileType.PNG)).isNull();
	}

	private static BufferedImage read(byte[] jpeg) throws IOException {
		assertThat(jpeg).isNotNull();
		return ImageIO.read(new ByteArrayInputStream(jpeg));
	}

	/** The pure colour closest to the pixel at the share of the width and height, JPEG noise aside. */
	private static Color colorAt(BufferedImage image, double x, double y) {
		Color pixel = new Color(image.getRGB((int) (image.getWidth() * x), (int) (image.getHeight() * y)));
		Color[] colors = { Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW, Color.WHITE };
		Color closest = colors[0];
		for (Color color : colors) {
			if (distance(pixel, color) < distance(pixel, closest)) {
				closest = color;
			}
		}
		assertThat(distance(pixel, closest)).as("near %s", closest).isLessThan(40);
		return closest;
	}

	private static int distance(Color a, Color b) {
		return Math.abs(a.getRed() - b.getRed()) + Math.abs(a.getGreen() - b.getGreen())
				+ Math.abs(a.getBlue() - b.getBlue());
	}

}
