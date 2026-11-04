package se.ataulnasar.pricingexception.api;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import se.ataulnasar.pricingexception.application.ApplicationInfo;
import se.ataulnasar.pricingexception.application.PricingExceptionService;
import se.ataulnasar.pricingexception.application.ReferenceDataService;
import se.ataulnasar.pricingexception.application.UserInfo;
import se.ataulnasar.pricingexception.domain.Actor;

/** Mortgage applications and users: seed data for the UI, and the approved discount for the mortgage process. */
@RestController
@RequestMapping("/api")
public class ApplicationController {

    private final PricingExceptionService service;
    private final ReferenceDataService referenceData;

    public ApplicationController(PricingExceptionService service, ReferenceDataService referenceData) {
        this.service = service;
        this.referenceData = referenceData;
    }

    @GetMapping("/applications")
    public List<ApplicationInfo> applications(Actor caller) {
        return referenceData.applications();
    }

    @GetMapping("/users")
    public List<UserInfo> users(Actor caller) {
        return referenceData.users();
    }

    /** Design decision 5: a small, separate read for the mortgage process. */
    @GetMapping("/applications/{id}/approved-discount")
    public ApprovedDiscountResponse approvedDiscount(Actor caller, @PathVariable String id) {
        return ApprovedDiscountResponse.from(service.approvedDiscount(caller, id));
    }
}
