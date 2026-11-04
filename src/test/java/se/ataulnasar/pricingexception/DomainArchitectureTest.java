package se.ataulnasar.pricingexception;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** CLAUDE.md: the domain package is plain Java. No Spring, no JPA, no Jackson, and no dependency on outer layers. */
@AnalyzeClasses(packages = "se.ataulnasar.pricingexception", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainArchitectureTest {

    private static final String DOMAIN = "se.ataulnasar.pricingexception.domain..";

    @ArchTest
    static final ArchRule domain_has_no_spring_jpa_or_jackson_imports = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta..",
                    "javax.persistence..",
                    "org.hibernate..",
                    "com.fasterxml.jackson..",
                    "lombok..")
            .because("domain holds business rules in plain Java (CLAUDE.md)");

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that().resideInAPackage(DOMAIN)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "se.ataulnasar.pricingexception.application..",
                    "se.ataulnasar.pricingexception.api..",
                    "se.ataulnasar.pricingexception.persistence..")
            .because("dependencies point inwards: application, api and persistence use domain, never the reverse");
}
