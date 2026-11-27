package studioforyou.pl.backend.files.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

import org.springframework.core.io.ByteArrayResource;

import studioforyou.pl.backend.TestFiles;
import studioforyou.pl.backend.files.FileType;
import studioforyou.pl.backend.files.internal.FileInspector.Inspection;

class FileInspectorTest {

	@Test
	void recognizesFilesByTheirContentWhateverTheirName() throws IOException {
		assertThat(type(TestFiles.jpeg(1), "photo.png")).isEqualTo(FileType.JPEG);
		assertThat(type(TestFiles.png(), "screenshot")).isEqualTo(FileType.PNG);
		assertThat(type(TestFiles.heic(), "IMG_0001.HEIC")).isEqualTo(FileType.HEIC);
		assertThat(type(TestFiles.pdf(), "wyniki.docx")).isEqualTo(FileType.PDF);
		assertThat(type(TestFiles.docx(), "plan.zip")).isEqualTo(FileType.DOCX);
		assertThat(type(TestFiles.xlsx(), null)).isEqualTo(FileType.XLSX);
		assertThat(type(TestFiles.doc(), "plan")).isEqualTo(FileType.DOC);
		assertThat(type(TestFiles.xls(), "tabela.doc")).isEqualTo(FileType.XLS);
	}

	@Test
	void anOldOfficeFileIsWhatItsTopStreamSays() throws IOException {
		// Excel 95 calls its stream Book.
		assertThat(type(TestFiles.compoundFile("Book", null), "a.xls")).isEqualTo(FileType.XLS);
		// A spreadsheet embedded in a Word document is below the top: still a Word document.
		assertThat(type(TestFiles.compoundFile("WordDocument", "Workbook"), "a.doc")).isEqualTo(FileType.DOC);
		// Other compound files: a PowerPoint presentation, an Outlook message.
		assertThat(type(TestFiles.compoundFile("PowerPoint Document", null), "a.ppt")).isNull();
		assertThat(type(TestFiles.compoundFile("__properties_version1.0", null), "a.msg")).isNull();
	}

	@Test
	void aBrokenCompoundFileIsNoDocument() throws IOException {
		// The directory's sector chain runs in a circle.
		byte[] circle = TestFiles.doc();
		ByteBuffer.wrap(circle).order(ByteOrder.LITTLE_ENDIAN).putInt(512 + 4, 1);
		assertThat(type(circle, "a.doc")).isNull();
		// The directory is in a sector the file does not have.
		byte[] outside = TestFiles.doc();
		ByteBuffer.wrap(outside).order(ByteOrder.LITTLE_ENDIAN).putInt(0x30, 40);
		assertThat(type(outside, "a.doc")).isNull();
		// A sibling that is the entry itself.
		byte[] loop = TestFiles.doc();
		ByteBuffer.wrap(loop).order(ByteOrder.LITTLE_ENDIAN).putInt(2 * 512 + 128 + 0x48, 1);
		assertThat(type(loop, "a.doc")).isNull();
		// Only the signature.
		assertThat(type(Arrays.copyOf(TestFiles.doc(), 8), "a.doc")).isNull();
	}

	@Test
	void textIsOnlyUtf8TextNamedTxt() throws IOException {
		byte[] text = "Rozgrzewka: 10 min, rozciąganie: 15 min".getBytes(StandardCharsets.UTF_8);
		assertThat(type(text, "plan.TXT")).isEqualTo(FileType.TXT);
		assertThat(type(text, "plan.csv")).isNull();
		assertThat(type(text, null)).isNull();
		assertThat(type(new byte[] { 'a', 0, 'b' }, "plan.txt")).isNull();
		assertThat(type(new byte[] { (byte) 0xC3, (byte) 0x28 }, "plan.txt")).isNull();
	}

	@Test
	void rejectsOtherFiles() throws IOException {
		assertThat(type("<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8), "a.svg"))
			.isNull();
		assertThat(type(TestFiles.zip("readme.md"), "archive.docx")).isNull();
		// A broken archive is not a document.
		assertThat(type(new byte[] { 'P', 'K', 3, 4, 1, 2, 3 }, "plan.docx")).isNull();
		assertThat(type(new byte[] { 'M', 'Z', 0, 0 }, "setup.pdf")).isNull();
	}

	@Test
	void readsTheSizeAPhotoIsDisplayedWith() throws IOException {
		Inspection upright = inspect(TestFiles.jpeg(1), "a.jpg");
		assertThat(upright.width()).isEqualTo(200);
		assertThat(upright.height()).isEqualTo(100);
		// EXIF orientation 6: the camera was turned, so the photo is shown upright as 100 x 200.
		Inspection turned = inspect(TestFiles.jpeg(6), "a.jpg");
		assertThat(turned.width()).isEqualTo(100);
		assertThat(turned.height()).isEqualTo(200);
		Inspection png = inspect(TestFiles.png(), "a.png");
		assertThat(png.width()).isEqualTo(640);
		assertThat(png.height()).isEqualTo(480);
		assertThat(inspect(TestFiles.pdf(), "a.pdf").width()).isNull();
		assertThat(FileInspector.jpegSize(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF })).isNull();
	}

	@Test
	void computesTheSha256OfTheWholeFile() throws IOException {
		assertThat(inspect("abc".getBytes(StandardCharsets.UTF_8), "a.txt").sha256())
			.isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
	}

	private static FileType type(byte[] content, String name) throws IOException {
		return inspect(content, name).type();
	}

	private static Inspection inspect(byte[] content, String name) throws IOException {
		return FileInspector.inspect(new ByteArrayResource(content), name);
	}

}
