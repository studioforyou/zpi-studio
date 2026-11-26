package studioforyou.pl.backend.files.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import studioforyou.pl.backend.files.FileType;
import studioforyou.pl.backend.files.StoredFiles;
import studioforyou.pl.backend.files.internal.FileInspector.Inspection;
import studioforyou.pl.backend.shared.api.ApiException;
import studioforyou.pl.backend.shared.api.ErrorCode;
import studioforyou.pl.backend.shared.api.FieldErrorCode;

/** Stored files: metadata in {@code stored_file}, content in the {@link FileStorage}. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcStoredFiles implements StoredFiles {

	private static final Logger log = LoggerFactory.getLogger(JdbcStoredFiles.class);

	private final JdbcClient jdbc;

	private final FileStorage storage;

	JdbcStoredFiles(JdbcClient jdbc, FileStorage storage) {
		this.jdbc = jdbc;
		this.storage = storage;
	}

	@Override
	public StoredFile save(Upload upload, Set<FileType> allowed, long maxSize) {
		if (upload.size() <= 0) {
			throw ApiException.invalidField("files", FieldErrorCode.REQUIRED, "The file is empty.");
		}
		if (upload.size() > maxSize) {
			throw new ApiException(ErrorCode.FILE_TOO_LARGE,
					"A file can have at most " + (maxSize / (1024 * 1024)) + " MB.");
		}
		InputStreamSource content = upload.content();
		Inspection inspection;
		try {
			inspection = FileInspector.inspect(content, upload.originalName());
			FileType type = inspection.type();
			if (type != null && type.isImage() && allowed.contains(type)) {
				// Where the photo was taken is not passed on: the stored copy has no location.
				byte[] bytes;
				try (InputStream in = content.getInputStream()) {
					bytes = in.readAllBytes();
				}
				if (LocationRemover.strip(bytes, type)) {
					content = new ByteArrayResource(bytes);
					inspection = FileInspector.inspect(content, upload.originalName());
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read the uploaded file", ex);
		}
		FileType type = inspection.type();
		if (type == null || !allowed.contains(type)) {
			throw new ApiException(ErrorCode.FILE_TYPE_NOT_ALLOWED, "This kind of file cannot be sent.",
					Map.of("allowedTypes", allowed.stream().map(FileType::extension).sorted().toList()));
		}
		UUID id = UUID.randomUUID();
		String bucket = storage.bucket();
		String key = upload.keyPrefix() + "/" + id;
		try {
			storage.put(key, content, type.mimeType());
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not save the uploaded file", ex);
		}
		// The content is saved before the transaction commits, so it goes away if it rolls back.
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					remove(bucket, key);
				}
			}
		});
		String name = FileNames.of(upload.originalName(), type);
		jdbc.sql("""
				INSERT INTO stored_file (id, owner_module, uploaded_by, bucket, object_key, original_name, mime_type,
				                         size_bytes, sha256, image_width, image_height)
				VALUES (:id, :ownerModule, :uploadedBy, :bucket, :key, :name, :mimeType, :size, :sha256, :width, :height)
				""")
			.param("id", id)
			.param("ownerModule", upload.ownerModule())
			.param("uploadedBy", upload.uploadedBy())
			.param("bucket", bucket)
			.param("key", key)
			.param("name", name)
			.param("mimeType", type.mimeType())
			.param("size", upload.size())
			.param("sha256", inspection.sha256())
			.param("width", inspection.width())
			.param("height", inspection.height())
			.update();
		return new StoredFile(id, name, type.mimeType(), upload.size(), inspection.width(), inspection.height());
	}

	@Override
	@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
	public Map<UUID, StoredFile> findAll(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("""
				SELECT id, original_name AS name, mime_type, size_bytes, image_width, image_height
				FROM stored_file
				WHERE id IN (:ids) AND deleted_at IS NULL
				""")
			.param("ids", ids)
			.query(StoredFile.class)
			.list()
			.stream()
			.collect(Collectors.toMap(StoredFile::id, Function.identity()));
	}

	@Override
	@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
	public Content open(UUID id) {
		Located located = jdbc.sql("""
				SELECT id, original_name AS name, mime_type, size_bytes, image_width, image_height, bucket, object_key
				FROM stored_file
				WHERE id = :id AND deleted_at IS NULL
				""")
			.param("id", id)
			.query(Located.class)
			.optional()
			.orElseThrow(JdbcStoredFiles::fileNotFound);
		InputStream stream;
		try {
			stream = storage.open(located.bucket(), located.objectKey()).orElse(null);
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read the stored file", ex);
		}
		if (stream == null) {
			log.warn("The content of stored file {} is missing ({}/{})", id, located.bucket(), located.objectKey());
			throw fileNotFound();
		}
		return new Content(located.file(), stream);
	}

	@Override
	public void delete(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return;
		}
		List<Location> deleted = jdbc.sql("""
				UPDATE stored_file
				SET deleted_at = now()
				WHERE id IN (:ids) AND deleted_at IS NULL
				RETURNING bucket, object_key
				""")
			.param("ids", ids)
			.query(Location.class)
			.list();
		if (deleted.isEmpty()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				deleted.forEach((location) -> remove(location.bucket(), location.objectKey()));
			}
		});
	}

	/** Removes content; a failure leaves the object behind, which only takes up space. */
	private void remove(String bucket, String key) {
		try {
			storage.delete(bucket, key);
		}
		catch (IOException | RuntimeException ex) {
			log.warn("Could not remove the content of a stored file ({}/{})", bucket, key, ex);
		}
	}

	private static ApiException fileNotFound() {
		return new ApiException(ErrorCode.NOT_FOUND, "File not found.");
	}

	private record Location(String bucket, String objectKey) {
	}

	private record Located(UUID id, String name, String mimeType, long sizeBytes, @Nullable Integer imageWidth,
			@Nullable Integer imageHeight, String bucket, String objectKey) {

		StoredFile file() {
			return new StoredFile(id, name, mimeType, sizeBytes, imageWidth, imageHeight);
		}

	}

}
