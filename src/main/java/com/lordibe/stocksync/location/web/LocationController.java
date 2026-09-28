package com.lordibe.stocksync.location.web;

import com.lordibe.stocksync.location.service.LocationService;
import com.lordibe.stocksync.location.web.dto.LocationRequest;
import com.lordibe.stocksync.location.web.dto.LocationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/locations")
@RequiredArgsConstructor
public class LocationController {

    private final LocationService locations;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public LocationResponse create(@Valid @RequestBody LocationRequest request) {
        return LocationResponse.from(locations.create(request));
    }

    @GetMapping
    public List<LocationResponse> list() {
        return locations.listActive().stream().map(LocationResponse::from).toList();
    }

    @GetMapping("/{locationId}")
    public LocationResponse get(@PathVariable UUID locationId) {
        return LocationResponse.from(locations.get(locationId));
    }

    @PutMapping("/{locationId}")
    public LocationResponse update(@PathVariable UUID locationId,
                                   @Valid @RequestBody LocationRequest request) {
        return LocationResponse.from(locations.update(locationId, request));
    }

    @DeleteMapping("/{locationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deactivate(@PathVariable UUID locationId) {
        locations.deactivate(locationId);
    }
}
