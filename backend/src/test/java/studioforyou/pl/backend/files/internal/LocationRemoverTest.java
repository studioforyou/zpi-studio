package studioforyou.pl.backend.files.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import studioforyou.pl.backend.TestFiles;
import studioforyou.pl.backend.files.FileType;

class LocationRemoverTest {

	@Test
	void removesTheLocationOfAJpegAndKeepsItsOrientation() {
		byte[] photo = TestFiles.jpegWithLocation();
		int size = photo.length;

		assertThat(LocationRemover.strip(photo, FileType.JPEG)).isTrue();

		assertThat(photo).hasSize(size);
		assertThat(contains(photo, TestFiles.latitude(false))).isFalse();
		// The GPS pointer (tag 0x8825, LONG) is gone from the first IFD.
		assertThat(contains(photo, new byte[] { (byte) 0x88, 0x25, 0, 4 })).isFalse();
		String text = new String(photo, StandardCharsets.ISO_8859_1);
		for (String value : TestFiles.XMP_LOCATION) {
			assertThat(text).doesNotContain(value);
		}
		// The XMP properties go away whole; the others stay.
		assertThat(text).doesNotContain(":GPS").contains("tiff:Orientation=\"6\"").contains("</rdf:Description>");
		// Orientation 6 is still there: the photo is shown turned, as 100 x 200.
		assertThat(FileInspector.jpegSize(photo)).containsExactly(100, 200);
		// Nothing left to remove.
		assertThat(LocationRemover.strip(photo, FileType.JPEG)).isFalse();
	}

	@Test
	void removesTheLocationOfAPngAndFixesItsChecksums() {
		byte[] photo = TestFiles.pngWithLocation();
		assertThat(TestFiles.pngChecksumsMatch(photo)).isTrue();

		assertThat(LocationRemover.strip(photo, FileType.PNG)).isTrue();

		assertThat(contains(photo, TestFiles.latitude(true))).isFalse();
		assertThat(TestFiles.pngChecksumsMatch(photo)).isTrue();
	}

	@Test
	void removesTheLocationOfAHeic() {
		byte[] photo = TestFiles.heicWithLocation();

		assertThat(LocationRemover.strip(photo, FileType.HEIC)).isTrue();

		assertThat(contains(photo, TestFiles.latitude(false))).isFalse();
		assertThat(photo).startsWith(TestFiles.heic());
	}

	@Test
	void leavesPhotosWithoutALocationAsTheyAre() {
		byte[] photo = TestFiles.jpeg(6);
		byte[] before = photo.clone();
		assertThat(LocationRemover.strip(photo, FileType.JPEG)).isFalse();
		assertThat(photo).isEqualTo(before);

		// Bytes that only look like the start of a TIFF structure are not touched.
		byte[] lookalike = { 'M', 'M', 0, 42, 0, 0, 0, 8, 0, 1, (byte) 0x88, 0x25, 0, 99, 0, 0, 0, 1, 0, 0, 0, 0, 0,
				0, 0, 0 };
		byte[] original = lookalike.clone();
		assertThat(LocationRemover.strip(lookalike, FileType.JPEG)).isFalse();
		assertThat(lookalike).isEqualTo(original);
	}

	private static boolean contains(byte[] data, byte[] part) {
		outer: for (int i = 0; i + part.length <= data.length; i++) {
			for (int j = 0; j < part.length; j++) {
				if (data[i + j] != part[j]) {
					continue outer;
				}
			}
			return true;
		}
		return false;
	}

}
