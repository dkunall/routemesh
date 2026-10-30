package com.routemesh;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.routemesh", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule domainIsPlainJava = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", "jakarta..", "java.sql..",
                    "com.fasterxml.jackson..", "com.uber.h3core..", "..application..", "..adapter..");

    @ArchTest
    static final ArchRule applicationDoesNotReachIntoAdapters = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAPackage("..adapter..");

    @ArchTest
    static final ArchRule applicationDoesNotTouchInfrastructure = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..",
                    "org.springframework.web..", "java.sql..", "org.springframework.kafka..",
                    "org.springframework.data.redis..", "com.uber.h3core..");

    /** Native H3 stays behind the CellIndex port. */
    @ArchTest
    static final ArchRule h3OnlyInGeoAdapter = noClasses().that().resideOutsideOfPackage("com.routemesh.geo.adapter..")
            .should().dependOnClassesThat().resideInAPackage("com.uber.h3core..");

    @ArchTest
    static final ArchRule benchUsesOnlyTheDomain = noClasses().that().resideInAPackage("com.routemesh.routing.bench..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..",
                    "org.springframework..");

    @ArchTest
    static final ArchRule modulesHaveNoCycles = slices().matching("com.routemesh.(*)..").should().beFreeOfCycles();

    @ArchTest
    static final ArchRule controllersLiveInAdapters = classes().that()
            .areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
            .should().resideInAPackage("..adapter..").andShould().notBePublic();
}
