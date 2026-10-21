package studioforyou.pl.backend;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModuleArchitectureTest {

	@Test
	void verifiesModuleBoundaries() {
		ApplicationModules modules = ApplicationModules.of(BackendApplication.class);
		assertThat(modules.getModuleByName("system")).isPresent();
		modules.verify();
	}

}
