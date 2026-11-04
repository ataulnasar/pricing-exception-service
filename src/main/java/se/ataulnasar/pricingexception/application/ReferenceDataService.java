package se.ataulnasar.pricingexception.application;

import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.ataulnasar.pricingexception.persistence.AppUserRepository;
import se.ataulnasar.pricingexception.persistence.MortgageApplicationRepository;

/** Read-only seed data for the UI: applications and users. */
@Service
public class ReferenceDataService {

    private final MortgageApplicationRepository applications;
    private final AppUserRepository users;

    public ReferenceDataService(MortgageApplicationRepository applications, AppUserRepository users) {
        this.applications = applications;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public List<ApplicationInfo> applications() {
        return applications.findAll().stream()
                .map(a -> new ApplicationInfo(a.getId(), a.getCustomerRef(), a.getStandardRateBps(),
                        a.getStatus().name(), a.getOwnerRmId()))
                .sorted(Comparator.comparing(ApplicationInfo::id))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<UserInfo> users() {
        return users.findAll().stream()
                .map(u -> new UserInfo(u.getId(), u.getName(), u.getRole(), u.getApprovalLimitBps()))
                .sorted(Comparator.comparing(UserInfo::id))
                .toList();
    }
}
