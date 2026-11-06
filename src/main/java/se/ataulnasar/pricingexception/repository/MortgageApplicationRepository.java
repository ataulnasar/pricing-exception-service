package se.ataulnasar.pricingexception.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import se.ataulnasar.pricingexception.model.MortgageApplicationEntity;

public interface MortgageApplicationRepository extends JpaRepository<MortgageApplicationEntity, String> {
}
