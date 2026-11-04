package se.ataulnasar.pricingexception.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MortgageApplicationRepository extends JpaRepository<MortgageApplicationEntity, String> {
}
