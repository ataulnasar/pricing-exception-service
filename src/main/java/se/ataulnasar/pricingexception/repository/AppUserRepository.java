package se.ataulnasar.pricingexception.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import se.ataulnasar.pricingexception.model.AppUserEntity;

public interface AppUserRepository extends JpaRepository<AppUserEntity, String> {
}
