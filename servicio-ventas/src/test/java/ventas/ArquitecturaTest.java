package ventas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Reglas de estructura-backend.md: M1 no usa código de M2 y los controllers no tocan repositorios. */
@AnalyzeClasses(packages = "ventas", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    @ArchTest
    static final ArchRule m1NoImportaM2 = noClasses()
            .that().resideInAPackage("ventas..")
            .should().dependOnClassesThat().resideInAPackage("postventa..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersNoUsanRepositorios = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true);
}
