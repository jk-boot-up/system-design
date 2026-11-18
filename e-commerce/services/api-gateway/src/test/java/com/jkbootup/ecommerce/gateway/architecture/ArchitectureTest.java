package com.jkbootup.ecommerce.gateway.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** The gateway has no use cases of its own: a pure domain (token buckets) and independent adapters (filters, fallbacks). */
@AnalyzeClasses(packages = "com.jkbootup.ecommerce.gateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPureJava = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideOutsideOfPackages("..domain..", "java..");

    @ArchTest
    static final ArchRule adaptersAreIndependent = slices().matching("..adapter.(*)..").should().notDependOnEachOther();
}
