package studioforyou.pl.backend.files;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Types of files the application accepts. The type of an uploaded file is recognized from its
 * content, never from its name or the type the client declared.
 */
public enum FileType {

	JPEG("image/jpeg", List.of("jpg", "jpeg"), true),
	PNG("image/png", List.of("png"), true),
	HEIC("image/heic", List.of("heic", "heif"), true),
	PDF("application/pdf", List.of("pdf"), false),
	DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document", List.of("docx"), false),
	XLSX("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", List.of("xlsx"), false),
	/** A Word document from before 2007. */
	DOC("application/msword", List.of("doc"), false),
	/** An Excel workbook from before 2007. */
	XLS("application/vnd.ms-excel", List.of("xls"), false),
	/** UTF-8 text; recognized only in a file named {@code *.txt}. */
	TXT("text/plain", List.of("txt"), false);

	private final String mimeType;

	private final List<String> extensions;

	private final boolean image;

	FileType(String mimeType, List<String> extensions, boolean image) {
		this.mimeType = mimeType;
		this.extensions = extensions;
		this.image = image;
	}

	/** The media type the file is stored and served with. */
	public String mimeType() {
		return mimeType;
	}

	/** The usual extension, without the dot. */
	public String extension() {
		return extensions.get(0);
	}

	/** Whether the name has one of this type's extensions, in any letter case. */
	public boolean matchesName(String name) {
		String lower = name.toLowerCase(Locale.ROOT);
		return extensions.stream().anyMatch((extension) -> lower.endsWith("." + extension));
	}

	public boolean isImage() {
		return image;
	}

	/** The type stored with this media type, if it is one of these. */
	public static Optional<FileType> fromMimeType(String mimeType) {
		for (FileType type : values()) {
			if (type.mimeType.equalsIgnoreCase(mimeType)) {
				return Optional.of(type);
			}
		}
		return Optional.empty();
	}

}
