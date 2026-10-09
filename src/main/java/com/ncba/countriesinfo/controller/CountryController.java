package com.ncba.countriesinfo.controller;

import com.ncba.countriesinfo.dto.CountryInfoRequest;
import com.ncba.countriesinfo.dto.CountryInfoResponse;
import com.ncba.countriesinfo.dto.CountryListResponse;
import com.ncba.countriesinfo.dto.ErrorResponse;
import com.ncba.countriesinfo.dto.JsonString;
import com.ncba.countriesinfo.exception.ResourceNotFoundException;
import com.ncba.countriesinfo.service.CountryService;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * HTTP layer. The detail routes are keyed by the database id; writes are
 * soft deletes (the vacuum job purges them after the retention period).
 */
@RestController
public class CountryController {

    private static final Logger log = LoggerFactory.getLogger(CountryController.class);

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;

    private final CountryService countryService;
    private final PrometheusMeterRegistry prometheusRegistry;

    public CountryController(CountryService countryService, PrometheusMeterRegistry prometheusRegistry) {
        this.countryService = countryService;
        this.prometheusRegistry = prometheusRegistry;
    }

    @GetMapping("/health")
    public JsonString health() {
        return new JsonString("OK");
    }

    @GetMapping("/ready")
    public ResponseEntity<?> ready() {
        try {
            countryService.ping();
            return ResponseEntity.ok(new JsonString("OK"));
        } catch (Exception e) {
            log.warn("Readiness check failed", e);
            return ResponseEntity.status(503).body(new ErrorResponse("not ready"));
        }
    }

    @GetMapping(value = "/metrics", produces = "text/plain; version=0.0.4; charset=utf-8")
    public ResponseEntity<String> metrics() {
        return ResponseEntity.ok(prometheusRegistry.scrape());
    }

    @PostMapping("/country")
    public ResponseEntity<?> countryInfo(@RequestBody CountryInfoRequest request) {
        String name = request.name() == null ? "" : request.name().trim();
        name = toTitleCase(name);
        if (name.isEmpty()) {
            return badRequest("country name cannot be empty");
        }

        try {
            return ResponseEntity.ok(countryService.lookupByName(name));
        } catch (ResourceNotFoundException e) {
            return notFound();
        } catch (Exception e) {
            log.error("Failed to get country info: country_name={}", name, e);
            return internalError("failed to get country info");
        }
    }

    @GetMapping("/countries")
    public ResponseEntity<?> listCountries(@RequestParam(required = false) String page,
                                           @RequestParam(required = false) String limit) {
        int pageValue = DEFAULT_PAGE;
        int limitValue = DEFAULT_LIMIT;

        if (page != null && !page.isEmpty()) {
            try {
                pageValue = Integer.parseInt(page);
                if (pageValue < 1) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                return badRequest("page must be a positive integer");
            }
        }
        if (limit != null && !limit.isEmpty()) {
            try {
                limitValue = Integer.parseInt(limit);
                if (limitValue < 1) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                return badRequest("limit must be a positive integer");
            }
            if (limitValue > MAX_LIMIT) {
                return badRequest("limit must not exceed %d".formatted(MAX_LIMIT));
            }
        }

        try {
            return ResponseEntity.ok(paginate(countryService.listCountries(), pageValue, limitValue));
        } catch (Exception e) {
            log.error("Failed to list countries", e);
            return internalError("failed to list countries");
        }
    }

    @GetMapping("/countries/{id}")
    public ResponseEntity<?> getCountry(@PathVariable String id) {
        Long countryId = parseId(id);
        if (countryId == null) {
            return invalidId();
        }

        try {
            return ResponseEntity.ok(countryService.getCountry(countryId));
        } catch (ResourceNotFoundException e) {
            return notFound();
        } catch (Exception e) {
            log.error("Failed to get country: id={}", countryId, e);
            return internalError("failed to get country");
        }
    }

    /** Updates an active country; the id in the path wins over the body. */
    @PutMapping("/countries/{id}")
    public ResponseEntity<?> updateCountry(@PathVariable String id, @RequestBody CountryInfoResponse info) {
        Long countryId = parseId(id);
        if (countryId == null) {
            return invalidId();
        }

        info.setId(countryId);
        info.setName(info.getName() == null ? "" : info.getName().trim());
        if (info.getName().isEmpty()) {
            return badRequest("country name cannot be empty");
        }

        try {
            return ResponseEntity.ok(countryService.updateCountry(countryId, info));
        } catch (ResourceNotFoundException e) {
            return notFound();
        } catch (Exception e) {
            log.error("Failed to update country: id={}", countryId, e);
            return internalError("failed to update country");
        }
    }

    /** Soft-deletes the country: the row stays until the vacuum purges it. */
    @DeleteMapping("/countries/{id}")
    public ResponseEntity<?> deleteCountry(@PathVariable String id) {
        Long countryId = parseId(id);
        if (countryId == null) {
            return invalidId();
        }

        try {
            countryService.deleteCountry(countryId);
            log.info("country deleted successfully");
            return ResponseEntity.noContent().build();
        } catch (ResourceNotFoundException e) {
            return notFound();
        } catch (Exception e) {
            log.error("Failed to delete country: id={}", countryId, e);
            return internalError("failed to delete country");
        }
    }

    private CountryListResponse paginate(List<CountryInfoResponse> countries, int page, int limit) {
        int total = countries.size();
        CountryListResponse response = new CountryListResponse(
                new ArrayList<>(), page, limit, total, (total + limit - 1) / limit);

        if (page > response.getTotalPages()) {
            return response;
        }

        int start = (page - 1) * limit;
        int end = Math.min(start + limit, total);
        response.getCountries().addAll(countries.subList(start, end));
        return response;
    }

    private Long parseId(String raw) {
        try {
            long id = Long.parseLong(raw);
            return id >= 1 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String toTitleCase(String input) {
        StringBuilder result = new StringBuilder(input.length());
        boolean wordStart = true;
        for (int i = 0; i < input.length(); ) {
            int codePoint = input.codePointAt(i);
            if (Character.isLetter(codePoint)) {
                result.appendCodePoint(wordStart ? Character.toTitleCase(codePoint) : Character.toLowerCase(codePoint));
                wordStart = false;
            } else {
                result.appendCodePoint(codePoint);
                wordStart = true;
            }
            i += Character.charCount(codePoint);
        }
        return result.toString();
    }

    private ResponseEntity<ErrorResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ErrorResponse(message));
    }

    private ResponseEntity<ErrorResponse> notFound() {
        return ResponseEntity.status(404).body(new ErrorResponse("country not found"));
    }

    private ResponseEntity<ErrorResponse> invalidId() {
        return badRequest("invalid country id: expected a positive integer");
    }

    private ResponseEntity<ErrorResponse> internalError(String message) {
        return ResponseEntity.status(500).body(new ErrorResponse(message));
    }
}
