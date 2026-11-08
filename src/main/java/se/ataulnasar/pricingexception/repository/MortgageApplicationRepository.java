package se.ataulnasar.pricingexception.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import se.ataulnasar.pricingexception.model.MortgageApplicationEntity;

public interface MortgageApplicationRepository extends JpaRepository<MortgageApplicationEntity, String> {

    /**
     * The application row locked for the rest of the current transaction ({@code SELECT ... FOR UPDATE}).
     * Used for every INV-06 check (ADR-008): a concurrent close either committed before this read, or
     * waits until this transaction has committed. {@link #findById} stays for read-only paths.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MortgageApplicationEntity> findForUpdateById(String id);
}
