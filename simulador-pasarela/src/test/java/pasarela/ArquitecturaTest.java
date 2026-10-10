package pasarela;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** El simulador es un sistema externo: no usa código de M1 ni de M2, solo HTTP. */
@AnalyzeClasses(packages = "pasarela", importOptions = ImportOption.DoNotIncludeTests.class)
class ArquitecturaTest {

    @ArchTest
    static final ArchRule noImportaVentasNiPostventa = noClasses()
            .that().resideInAPackage("pasarela..")
            .should().dependOnClassesThat().resideInAnyPackage("ventas..", "postventa..")
            .allowEmptyShould(true);
}
