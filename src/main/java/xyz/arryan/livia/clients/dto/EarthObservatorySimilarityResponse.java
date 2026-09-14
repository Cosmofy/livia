package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;

public record EarthObservatorySimilarityResponse(
        LocalDate date, List<EarthObservatorySimilarityResultResponse> results) {}
