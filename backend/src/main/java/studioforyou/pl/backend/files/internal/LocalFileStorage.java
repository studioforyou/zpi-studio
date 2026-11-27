package studioforyou.pl.backend.files.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

import org.springframework.core.io.InputStreamSource;

/**
 * Keeps files in a local directory (local development and tests): the key is the path under it.
 * The bucket is ignored when reading, so every file is looked up in this directory.
 */
class LocalFileStorage implements FileStorage {

	/** The bucket saved with files kept here. */
	static final String BUCKET = "local";

	private final Path root;

	LocalFileStorage(Path root) {
		this.root = root.toAbsolutePath().normalize();
	}

	@Override
	public String bucket() {
		return BUCKET;
	}

	@Override
	public void put(String key, InputStreamSource content, String contentType) throws IOException {
		Path target = resolve(key);
		Files.createDirectories(target.getParent());
		// Written next to the target and moved, so a reader never sees half a file.
		Path partial = Files.createTempFile(target.getParent(), ".upload-", ".part");
		try {
			try (InputStream in = content.getInputStream()) {
				Files.copy(in, partial, StandardCopyOption.REPLACE_EXISTING);
			}
			Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
		}
		finally {
			Files.deleteIfExists(partial);
		}
	}

	@Override
	public Optional<InputStream> open(String bucket, String key) throws IOException {
		try {
			return Optional.of(Files.newInputStream(resolve(key)));
		}
		catch (NoSuchFileException ex) {
			return Optional.empty();
		}
	}

	@Override
	public void delete(String bucket, String key) throws IOException {
		Files.deleteIfExists(resolve(key));
	}

	private Path resolve(String key) {
		Path path = root.resolve(key).normalize();
		if (!path.startsWith(root) || path.equals(root)) {
			throw new IllegalArgumentException("The key leaves the storage directory: " + key);
		}
		return path;
	}

}
