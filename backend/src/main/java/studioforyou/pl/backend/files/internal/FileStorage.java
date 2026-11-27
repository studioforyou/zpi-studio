package studioforyou.pl.backend.files.internal;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

import org.springframework.core.io.InputStreamSource;

/**
 * Where the content of stored files lives, selected by {@code app.files.provider}: a local
 * directory or a Cloud Storage bucket. Objects are addressed by the bucket and key saved in
 * {@code stored_file}.
 */
interface FileStorage {

	/** The bucket new files go to, saved with each file. */
	String bucket();

	/** Saves the content under the key in {@link #bucket()}. */
	void put(String key, InputStreamSource content, String contentType) throws IOException;

	/** The content under the key, or empty when there is none. */
	Optional<InputStream> open(String bucket, String key) throws IOException;

	/** Removes the content under the key; does nothing when there is none. */
	void delete(String bucket, String key) throws IOException;

}
