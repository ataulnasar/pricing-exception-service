package se.ataulnasar.pricingexception;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Autowired;

/** CLAUDE.md code-style and layering rules for the whole main code. */
@AnalyzeClasses(packages = "se.ataulnasar.pricingexception", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule time_comes_from_the_clock_not_from_now = noClasses()
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .because("time comes from an injected Clock (CLAUDE.md)");

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_persistence = noClasses()
            .that().resideInAPackage("se.ataulnasar.pricingexception.api..")
            .should().dependOnClassesThat().resideInAPackage("se.ataulnasar.pricingexception.persistence..")
            .because("controllers call services, never repositories (CLAUDE.md)");

    @ArchTest
    static final ArchRule no_field_injection = noFields()
            .should().beAnnotatedWith(Autowired.class)
            .because("constructor injection only (CLAUDE.md)");

    @ArchTest
    static final ArchRule no_lombok = noClasses()
            .should().dependOnClassesThat().resideInAPackage("lombok..")
            .because("no Lombok (CLAUDE.md)");
}
