package studioforyou.pl.backend;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import org.junit.jupiter.api.Test;

import org.springframework.modulith.core.ApplicationModules;

class ModuleArchitectureTest {

	@Test
	void staffDoesNotDependOnIdentity() {
		noClasses().that().resideInAPackage("..staff..")
			.should().dependOnClassesThat().resideInAPackage("..identity..")
			.check(new ClassFileImporter().importPackages("studioforyou.pl.backend"));
	}

	@Test
	void verifiesModuleBoundaries() {
		ApplicationModules modules = ApplicationModules.of(BackendApplication.class);
		assertThat(modules.stream().map(module -> module.getIdentifier().toString()))
			.containsExactlyInAnyOrder("identity", "customer", "staff", "offering", "trainings", "scheduling",
					"booking", "membership", "documents", "communication", "payments", "reporting", "integrations",
					"configuration", "files", "audit", "shared", "system");
		modules.verify();
	}

}
