package com.lordibe.stocksync.location.service;

import com.lordibe.stocksync.location.domain.Location;
import com.lordibe.stocksync.location.domain.LocationType;
import com.lordibe.stocksync.location.repository.LocationRepository;
import com.lordibe.stocksync.location.web.dto.LocationRequest;
import com.lordibe.stocksync.shared.tenancy.TenantContext;
import com.lordibe.stocksync.shared.web.ConflictException;
import com.lordibe.stocksync.shared.web.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;

/**
 * Places stock can sit.
 *
 * <p>As with items, removal is deactivation: movements reference a location
 * permanently.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class LocationService {

    private final LocationRepository locations;

    @PreAuthorize("hasAuthority('location.create')")
    public Location create(LocationRequest request) {
        UUID tenantId = TenantContext.require();
        locations.findByTenantIdAndCode(tenantId, request.code()).ifPresent(existing -> {
            throw new ConflictException("A location with code '" + request.code() + "' already exists");
        });
        return locations.save(Location.builder()
                .tenantId(tenantId)
                .code(request.code())
                .name(request.name())
                .type(request.type() == null ? LocationType.WAREHOUSE : request.type())
                .address(request.address() == null
                        ? new HashMap<>() : new HashMap<>(request.address()))
                .build());
    }

    @PreAuthorize("hasAuthority('location.update')")
    public Location update(UUID locationId, LocationRequest request) {
        Location location = require(locationId);
        if (!location.getCode().equals(request.code())
                && locations.findByTenantIdAndCode(location.getTenantId(), request.code()).isPresent()) {
            throw new ConflictException("A location with code '" + request.code() + "' already exists");
        }
        location.setCode(request.code());
        location.setName(request.name());
        if (request.type() != null) {
            location.setType(request.type());
        }
        if (request.address() != null) {
            location.setAddress(new HashMap<>(request.address()));
        }
        return location;
    }

    @PreAuthorize("hasAuthority('location.delete')")
    public void deactivate(UUID locationId) {
        require(locationId).setActive(false);
    }

    @PreAuthorize("hasAuthority('location.read')")
    @Transactional(readOnly = true)
    public Location get(UUID locationId) {
        return require(locationId);
    }

    @PreAuthorize("hasAuthority('location.read')")
    @Transactional(readOnly = true)
    public List<Location> listActive() {
        return locations.findByTenantIdAndActiveTrueOrderByCode(TenantContext.require());
    }

    private Location require(UUID locationId) {
        return locations.findByTenantIdAndId(TenantContext.require(), locationId)
                .orElseThrow(() -> new ResourceNotFoundException("Location", locationId));
    }
}
