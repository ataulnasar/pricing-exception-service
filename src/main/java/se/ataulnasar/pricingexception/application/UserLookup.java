package se.ataulnasar.pricingexception.application;

import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import se.ataulnasar.pricingexception.domain.Actor;
import se.ataulnasar.pricingexception.persistence.AppUserRepository;

/** Turns the {@code X-User-Id} header value into an {@link Actor} from the user table. */
@Service
public class UserLookup {

    private final AppUserRepository users;

    public UserLookup(AppUserRepository users) {
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Optional<Actor> findActor(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return users.findById(userId).map(u -> new Actor(
                u.getId(), u.getRole(), u.getApprovalLimitBps() == null ? 0 : u.getApprovalLimitBps()));
    }
}
