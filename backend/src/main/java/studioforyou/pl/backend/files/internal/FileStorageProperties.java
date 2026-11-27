package studioforyou.pl.backend.files.internal;

import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * File storage settings ({@code app.files.*}).
 *
 * @param provider which {@link FileStorage} to use
 * @param localDirectory where {@code LOCAL} keeps the files
 * @param bucket the Cloud Storage bucket {@code GCS} keeps the files in
 */
@Validated
@ConfigurationProperties("app.files")
public record FileStorageProperties(@NotNull Provider provider, @Nullable String localDirectory,
		@Nullable String bucket) {

	public enum Provider {

		/** A private Cloud Storage bucket (stage, prod). */
		GCS,

		/** A local directory, e.g. a Docker volume (local development, tests). */
		LOCAL

	}

}
