package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
public record ApodErrorResponse(ErrorBody error, JsonNode detail) {

    public static ApodErrorResponse empty() {
        return new ApodErrorResponse(null, null);
    }

    public String errorCode() {
        return error == null ? null : error.code();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorBody(String code, String message) {
    }
}
