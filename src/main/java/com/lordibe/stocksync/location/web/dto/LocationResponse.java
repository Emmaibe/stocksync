package com.lordibe.stocksync.location.web.dto;

import com.lordibe.stocksync.location.domain.Location;
import com.lordibe.stocksync.location.domain.LocationType;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record LocationResponse(
        UUID id,
        String code,
        String name,
        LocationType type,
        Map<String, Object> address,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {

    public static LocationResponse from(Location location) {
        return new LocationResponse(location.getId(), location.getCode(), location.getName(),
                location.getType(), location.getAddress(), location.isActive(),
                location.getCreatedAt(), location.getUpdatedAt());
    }
}
