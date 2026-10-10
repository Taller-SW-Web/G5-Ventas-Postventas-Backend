package postventa;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** Reglas de estructura-backend.md: M2 no usa código de M1 y los controllers no tocan repositorios. */
@AnalyzeClasses(packages = "postventa", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    @ArchTest
    static final ArchRule m2NoImportaM1 = noClasses()
            .that().resideInAPackage("postventa..")
            .should().dependOnClassesThat().resideInAPackage("ventas..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersNoUsanRepositorios = noClasses()
            .that().haveSimpleNameEndingWith("Controller")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .allowEmptyShould(true);
}
