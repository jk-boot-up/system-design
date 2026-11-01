package com.jkbootup.ecommerce.payment.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

@AnalyzeClasses(packages = "com.jkbootup.ecommerce.payment", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPureJava = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages("..domain..", "java..");

    @ArchTest
    static final ArchRule applicationDependsOnlyOnDomain = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideOutsideOfPackages("..application..", "..domain..", "java..");

    @ArchTest
    static final ArchRule adaptersAreIndependent = slices().matching("..adapter.(*)..")
            .should().notDependOnEachOther();
}
