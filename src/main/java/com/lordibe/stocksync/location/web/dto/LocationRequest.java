package com.lordibe.stocksync.location.web.dto;

import com.lordibe.stocksync.location.domain.LocationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record LocationRequest(
        @NotBlank @Size(max = 40) String code,
        @NotBlank @Size(max = 150) String name,
        LocationType type,
        Map<String, Object> address
) {
}
