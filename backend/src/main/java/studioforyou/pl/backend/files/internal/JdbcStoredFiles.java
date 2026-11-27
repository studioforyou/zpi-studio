package studioforyou.pl.backend.files.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

	/** Added to a photo's key for its thumbnail's. */
	private static final String THUMBNAIL_SUFFIX = ".thumbnail.jpg";

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
		byte[] thumbnail = null;
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
				thumbnail = Thumbnails.of(bytes, type);
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
		String thumbnailKey = key + THUMBNAIL_SUFFIX;
		// The content is saved before the transaction commits, so it goes away if it rolls back.
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					remove(bucket, key);
					remove(bucket, thumbnailKey);
				}
			}
		});
		try {
			storage.put(key, content, type.mimeType());
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not save the uploaded file", ex);
		}
		if (thumbnail != null) {
			try {
				storage.put(thumbnailKey, new ByteArrayResource(thumbnail), Thumbnail.MIME_TYPE);
			}
			catch (IOException | RuntimeException ex) {
				// The photo is shown whole instead.
				log.warn("Could not save the thumbnail of a stored file ({}/{})", bucket, thumbnailKey, ex);
				thumbnail = null;
			}
		}
		String name = FileNames.of(upload.originalName(), type);
		jdbc.sql("""
				INSERT INTO stored_file (id, owner_module, uploaded_by, bucket, object_key, original_name, mime_type,
				                         size_bytes, sha256, image_width, image_height, thumbnail_key,
				                         thumbnail_size_bytes)
				VALUES (:id, :ownerModule, :uploadedBy, :bucket, :key, :name, :mimeType, :size, :sha256, :width, :height,
				        :thumbnailKey, :thumbnailSize)
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
			.param("thumbnailKey", (thumbnail != null) ? thumbnailKey : null)
			.param("thumbnailSize", (thumbnail != null) ? thumbnail.length : null)
			.update();
		return new StoredFile(id, name, type.mimeType(), upload.size(), inspection.width(), inspection.height(),
				thumbnail != null);
	}

	@Override
	@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
	public Map<UUID, StoredFile> findAll(Collection<UUID> ids) {
		if (ids.isEmpty()) {
			return Map.of();
		}
		return jdbc.sql("""
				SELECT id, original_name AS name, mime_type, size_bytes, image_width, image_height,
				       thumbnail_key IS NOT NULL AS has_thumbnail
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
				SELECT id, original_name AS name, mime_type, size_bytes, image_width, image_height,
				       thumbnail_key IS NOT NULL AS has_thumbnail, bucket, object_key
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
	@Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
	public Optional<Thumbnail> openThumbnail(UUID id) {
		Optional<ThumbnailLocation> found = jdbc.sql("""
				SELECT bucket, thumbnail_key, thumbnail_size_bytes
				FROM stored_file
				WHERE id = :id AND deleted_at IS NULL AND thumbnail_key IS NOT NULL
				""")
			.param("id", id)
			.query(ThumbnailLocation.class)
			.optional();
		if (found.isEmpty()) {
			return Optional.empty();
		}
		ThumbnailLocation location = found.get();
		Optional<InputStream> stream;
		try {
			stream = storage.open(location.bucket(), location.thumbnailKey());
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Could not read the thumbnail of a stored file", ex);
		}
		if (stream.isEmpty()) {
			log.warn("The thumbnail of stored file {} is missing ({}/{})", id, location.bucket(),
					location.thumbnailKey());
		}
		return stream.map((in) -> new Thumbnail(location.thumbnailSizeBytes(), in));
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
				RETURNING bucket, object_key, thumbnail_key
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
				deleted.forEach((location) -> {
					remove(location.bucket(), location.objectKey());
					if (location.thumbnailKey() != null) {
						remove(location.bucket(), location.thumbnailKey());
					}
				});
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

	private record Location(String bucket, String objectKey, @Nullable String thumbnailKey) {
	}

	private record ThumbnailLocation(String bucket, String thumbnailKey, long thumbnailSizeBytes) {
	}

	private record Located(UUID id, String name, String mimeType, long sizeBytes, @Nullable Integer imageWidth,
			@Nullable Integer imageHeight, boolean hasThumbnail, String bucket, String objectKey) {

		StoredFile file() {
			return new StoredFile(id, name, mimeType, sizeBytes, imageWidth, imageHeight, hasThumbnail);
		}

	}

}
