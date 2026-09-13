package xyz.arryan.livia.clients.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record NatureGeometryResponse(
        Double magnitudeValue, String magnitudeUnit, String date, String type, List<Double> coordinates) { }
