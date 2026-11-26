package studioforyou.pl.backend.files.internal;

import java.nio.file.Path;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates the {@link FileStorage} selected by {@code app.files.provider}.
 */
@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
class FileStorageConfiguration {

	@Bean
	FileStorage fileStorage(FileStorageProperties properties) {
		return switch (properties.provider()) {
			case GCS -> {
				if (properties.bucket() == null || properties.bucket().isBlank()) {
					throw new IllegalStateException("app.files.provider=GCS needs app.files.bucket (FILE_STORAGE_BUCKET)");
				}
				yield new GcsFileStorage(properties.bucket());
			}
			case LOCAL -> {
				if (properties.localDirectory() == null || properties.localDirectory().isBlank()) {
					throw new IllegalStateException(
							"app.files.provider=LOCAL needs app.files.local-directory (FILE_STORAGE_LOCAL_DIRECTORY)");
				}
				yield new LocalFileStorage(Path.of(properties.localDirectory()));
			}
		};
	}

}
