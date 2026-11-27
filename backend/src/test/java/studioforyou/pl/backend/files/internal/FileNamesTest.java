package studioforyou.pl.backend.files.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import studioforyou.pl.backend.files.FileType;

class FileNamesTest {

	@Test
	void keepsANameWithTheRightExtension() {
		assertThat(FileNames.of("Plan treningowy.pdf", FileType.PDF)).isEqualTo("Plan treningowy.pdf");
		assertThat(FileNames.of("IMG_0001.JPEG", FileType.JPEG)).isEqualTo("IMG_0001.JPEG");
	}

	@Test
	void givesTheNameTheExtensionOfTheRealType() {
		assertThat(FileNames.of("photo.png", FileType.JPEG)).isEqualTo("photo.jpg");
		assertThat(FileNames.of("wyniki", FileType.PDF)).isEqualTo("wyniki.pdf");
		assertThat(FileNames.of("raport.v2", FileType.XLSX)).isEqualTo("raport.v2.xlsx");
		assertThat(FileNames.of(null, FileType.JPEG)).isEqualTo("file.jpg");
		assertThat(FileNames.of("  ", FileType.PDF)).isEqualTo("file.pdf");
	}

	@Test
	void dropsPathsAndCharactersFileSystemsDoNotAllow() {
		assertThat(FileNames.of("../../etc/passwd", FileType.TXT)).isEqualTo("passwd.txt");
		assertThat(FileNames.of("C:\\Users\\ola\\skan.pdf", FileType.PDF)).isEqualTo("skan.pdf");
		assertThat(FileNames.of("a:b*c?\"<>|.docx", FileType.DOCX)).isEqualTo("abc.docx");
		assertThat(FileNames.of("  .ukryty\n.txt  ", FileType.TXT)).isEqualTo("ukryty.txt");
	}

	@Test
	void shortensALongNameKeepingTheExtension() {
		String name = FileNames.of("a".repeat(300) + ".pdf", FileType.PDF);
		assertThat(name).hasSize(FileNames.MAX_LENGTH).endsWith("a.pdf");
		// A character outside the BMP is not cut in half.
		String emoji = FileNames.of("a".repeat(145) + "😀😀😀.pdf", FileType.PDF);
		assertThat(emoji).endsWith("a.pdf").hasSizeLessThanOrEqualTo(FileNames.MAX_LENGTH);
	}

}
