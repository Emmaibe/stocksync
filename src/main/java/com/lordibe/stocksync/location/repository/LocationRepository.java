package com.lordibe.stocksync.location.repository;

import com.lordibe.stocksync.location.domain.Location;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LocationRepository extends JpaRepository<Location, UUID> {

    Optional<Location> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<Location> findByTenantIdAndCode(UUID tenantId, String code);

    List<Location> findByTenantIdAndActiveTrueOrderByCode(UUID tenantId);
}
