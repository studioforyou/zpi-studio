package studioforyou.pl.backend.files.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.util.Optional;

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.cloud.storage.StorageOptions;

import org.springframework.core.io.InputStreamSource;

/**
 * Keeps files in a private Cloud Storage bucket (stage, prod). Credentials come from Application
 * Default Credentials: the backend's service account on Cloud Run. The client is created on first
 * use, so the application starts without credentials.
 * <p>
 * The {@code google-cloud-storage} client comes with {@code firebase-admin}. It is not declared on
 * its own, because that would change the versions of other Google libraries firebase-admin uses.
 */
class GcsFileStorage implements FileStorage {

	private final String bucket;

	private volatile Storage storage;

	GcsFileStorage(String bucket) {
		this.bucket = bucket;
	}

	@Override
	public String bucket() {
		return bucket;
	}

	@Override
	public void put(String key, InputStreamSource content, String contentType) throws IOException {
		BlobInfo blob = BlobInfo.newBuilder(BlobId.of(bucket, key)).setContentType(contentType).build();
		try (InputStream in = content.getInputStream()) {
			storage().createFrom(blob, in, Storage.BlobWriteOption.doesNotExist());
		}
		catch (StorageException ex) {
			throw new IOException("Could not save gs://" + bucket + "/" + key, ex);
		}
	}

	@Override
	public Optional<InputStream> open(String bucket, String key) throws IOException {
		if (!this.bucket.equals(bucket)) {
			// The backend can only read its own bucket, e.g. seed data names one that does not exist.
			return Optional.empty();
		}
		try {
			Blob blob = storage().get(BlobId.of(bucket, key));
			if (blob == null) {
				return Optional.empty();
			}
			return Optional.of(Channels.newInputStream(blob.reader()));
		}
		catch (StorageException ex) {
			throw new IOException("Could not read gs://" + bucket + "/" + key, ex);
		}
	}

	@Override
	public void delete(String bucket, String key) throws IOException {
		if (!this.bucket.equals(bucket)) {
			return;
		}
		try {
			storage().delete(BlobId.of(bucket, key));
		}
		catch (StorageException ex) {
			throw new IOException("Could not delete gs://" + bucket + "/" + key, ex);
		}
	}

	private Storage storage() {
		Storage current = storage;
		if (current == null) {
			synchronized (this) {
				current = storage;
				if (current == null) {
					current = StorageOptions.getDefaultInstance().getService();
					storage = current;
				}
			}
		}
		return current;
	}

}
