package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NatureEventResponse(
        String id, String title, List<NatureCategoryResponse> categories,
        List<NatureSourceResponse> sources, List<NatureGeometryResponse> geometry) { }
