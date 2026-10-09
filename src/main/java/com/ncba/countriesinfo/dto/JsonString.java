package com.ncba.countriesinfo.dto;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Serializes a bare JSON string (e.g. {@code "OK"}) rather than an object.
 * Used for endpoints whose contract returns a JSON string literal.
 */
public record JsonString(@JsonValue String value) {
}
