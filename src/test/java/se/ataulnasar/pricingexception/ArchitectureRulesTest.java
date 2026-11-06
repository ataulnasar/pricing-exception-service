package se.ataulnasar.pricingexception;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.RestController;
import se.ataulnasar.pricingexception.model.PricingExceptionRequestEntity;
import se.ataulnasar.pricingexception.service.PricingExceptionService;

/** CLAUDE.md code-style and layering rules for the classic MVC layout (ADR-007). */
@AnalyzeClasses(packages = "se.ataulnasar.pricingexception", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    private static final String CONTROLLER = "se.ataulnasar.pricingexception.controller..";
    private static final String SERVICE = "se.ataulnasar.pricingexception.service..";
    private static final String REPOSITORY = "se.ataulnasar.pricingexception.repository..";
    private static final String MODEL = "se.ataulnasar.pricingexception.model..";
    private static final String DTO = "se.ataulnasar.pricingexception.dto..";

    @ArchTest
    static final ArchRule time_comes_from_the_clock_not_from_now = noClasses()
            .should().callMethod(Instant.class, "now")
            .orShould().callMethod(LocalDateTime.class, "now")
            .orShould().callMethod(ZonedDateTime.class, "now")
            .because("time comes from an injected Clock (CLAUDE.md)");

    @ArchTest
    static final ArchRule controllers_do_not_depend_on_repositories = noClasses()
            .that().resideInAPackage(CONTROLLER)
            .should().dependOnClassesThat().resideInAPackage(REPOSITORY)
            .because("controllers call services, never repositories (CLAUDE.md)");

    @ArchTest
    static final ArchRule controllers_live_in_the_controller_package = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().resideInAPackage(CONTROLLER)
            .because("the MVC layout puts every @RestController in controller (ADR-007)");

    @ArchTest
    static final ArchRule services_live_in_the_service_package = classes()
            .that().areAnnotatedWith(Service.class)
            .should().resideInAPackage(SERVICE)
            .because("the MVC layout puts every @Service in service (ADR-007)");

    @ArchTest
    static final ArchRule dependencies_point_from_controller_to_service_to_repository = noClasses()
            .that().resideInAnyPackage(SERVICE, REPOSITORY, MODEL, DTO)
            .should().dependOnClassesThat().resideInAPackage(CONTROLLER)
            .because("nothing below the controllers depends on them (ADR-007)");

    @ArchTest
    static final ArchRule repositories_and_entities_do_not_depend_on_services = noClasses()
            .that().resideInAnyPackage(REPOSITORY, MODEL)
            .should().dependOnClassesThat().resideInAnyPackage(SERVICE, DTO)
            .because("repositories and entities are the bottom layer (ADR-007)");

    /** INV-01: the request row is only ever written by the service. Entity setters exist for JPA and the service. */
    @ArchTest
    static final ArchRule INV01_only_the_service_writes_the_request_entity = noClasses()
            .that().doNotHaveFullyQualifiedName(PricingExceptionService.class.getName())
            .should().callMethodWhere(target(owner(assignableTo(PricingExceptionRequestEntity.class)))
                    .and(target(nameStartingWith("set"))))
            .because("a submitted request never changes (INV-01); only PricingExceptionService sets request fields, "
                    + "and only through newRequest, recordDecision and transition (ADR-007)");

    @ArchTest
    static final ArchRule no_field_injection = noFields()
            .should().beAnnotatedWith(Autowired.class)
            .because("constructor injection only (CLAUDE.md)");

    @ArchTest
    static final ArchRule no_lombok = noClasses()
            .should().dependOnClassesThat().resideInAPackage("lombok..")
            .because("no Lombok (CLAUDE.md)");
}
