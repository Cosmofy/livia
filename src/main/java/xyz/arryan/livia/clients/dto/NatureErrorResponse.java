package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NatureErrorResponse(@JsonProperty("error") Error error) {
    public static NatureErrorResponse empty() { return new NatureErrorResponse(null); }
    public String code() { return error == null ? null : error.code(); }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(String code) { }
}
