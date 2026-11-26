package studioforyou.pl.backend.files;

import java.io.InputStream;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import org.springframework.core.io.InputStreamSource;

/**
 * Files kept in file storage (a local directory or a Cloud Storage bucket, see
 * {@code app.files.provider}) with their metadata in {@code stored_file}. The module that owns a
 * file decides who may read it; this one only stores and serves the content. Must be called
 * within a transaction: content saved by a transaction that rolls back is removed, and content of
 * deleted files is removed once the transaction commits.
 */
public interface StoredFiles {

	/**
	 * Saves an uploaded file. Its type is recognized from the content (see {@link FileType}), and
	 * its name gets that type's extension. A photo is stored without where it was taken: its EXIF
	 * GPS block and XMP GPS values are removed, the rest of its metadata (e.g. the orientation)
	 * and its pixels stay as they are.
	 *
	 * @param allowed the types the caller accepts
	 * @param maxSize the largest accepted size, in bytes
	 * @throws studioforyou.pl.backend.shared.api.ApiException {@code VALIDATION_FAILED} for an empty
	 * file, {@code FILE_TOO_LARGE} or {@code FILE_TYPE_NOT_ALLOWED}
	 */
	StoredFile save(Upload upload, Set<FileType> allowed, long maxSize);

	/** The given files that are not deleted, by id; unknown and deleted ones are left out. */
	Map<UUID, StoredFile> findAll(Collection<UUID> ids);

	/**
	 * Opens the content of a file. The caller closes the stream.
	 *
	 * @throws studioforyou.pl.backend.shared.api.ApiException {@code NOT_FOUND} when the file is
	 * deleted or its content is missing from the storage
	 */
	Content open(UUID id);

	/** Marks the files deleted and removes their content once the transaction commits. */
	void delete(Collection<UUID> ids);

	/**
	 * A file to save.
	 *
	 * @param ownerModule the module the file belongs to, e.g. {@code communication}
	 * @param keyPrefix where in the storage it goes, e.g. {@code chat/<conversation id>}; the file's
	 * id is added after it
	 * @param uploadedBy the account that sent it
	 * @param originalName the name the client gave it, if any
	 * @param size its size in bytes
	 * @param content its content, which can be read more than once
	 */
	record Upload(String ownerModule, String keyPrefix, @Nullable UUID uploadedBy, @Nullable String originalName,
			long size, InputStreamSource content) {
	}

	/**
	 * A stored file.
	 *
	 * @param name the file name shown to people, with the extension of its type
	 * @param mimeType the media type it is served with
	 * @param imageWidth the width of an image as displayed; {@code null} for other files or when unknown
	 * @param imageHeight the height of an image as displayed; {@code null} for other files or when unknown
	 */
	record StoredFile(UUID id, String name, String mimeType, long sizeBytes, @Nullable Integer imageWidth,
			@Nullable Integer imageHeight) {

		public boolean isImage() {
			return mimeType.startsWith("image/");
		}

	}

	/** The content of a file, to be read once and closed. */
	record Content(StoredFile file, InputStream stream) {
	}

}
