package xyz.arryan.livia.mappers;

import org.springframework.stereotype.Component;
import xyz.arryan.livia.clients.dto.NatureCategoryResponse;
import xyz.arryan.livia.clients.dto.NatureEventResponse;
import xyz.arryan.livia.clients.dto.NatureGeometryResponse;
import xyz.arryan.livia.clients.dto.NatureSourceResponse;
import xyz.arryan.livia.codegen.types.Category;
import xyz.arryan.livia.codegen.types.Event;
import xyz.arryan.livia.codegen.types.Geometry;
import xyz.arryan.livia.codegen.types.Source;

import java.util.List;

@Component
public class NatureMapper {
    public List<Event> toEvents(List<NatureEventResponse> events) {
        if (events == null) return List.of();
        return events.stream().filter(event -> event != null).map(this::toEvent).toList();
    }

    private Event toEvent(NatureEventResponse event) {
        return Event.newBuilder().id(event.id()).title(event.title())
                .categories(categories(event.categories())).sources(sources(event.sources()))
                .geometry(geometry(event.geometry())).build();
    }

    private List<Category> categories(List<NatureCategoryResponse> categories) {
        if (categories == null) return List.of();
        return categories.stream().filter(category -> category != null)
                .map(category -> Category.newBuilder().id(category.id()).title(category.title()).build()).toList();
    }

    private List<Source> sources(List<NatureSourceResponse> sources) {
        if (sources == null) return List.of();
        return sources.stream().filter(source -> source != null)
                .map(source -> Source.newBuilder().id(source.id()).url(source.url()).build()).toList();
    }

    private List<Geometry> geometry(List<NatureGeometryResponse> geometry) {
        if (geometry == null) return List.of();
        return geometry.stream().filter(item -> item != null)
                .map(item -> Geometry.newBuilder().magnitudeValue(item.magnitudeValue()).magnitudeUnit(item.magnitudeUnit())
                        .date(item.date()).type(item.type()).coordinates(validCoordinates(item.coordinates())).build())
                .toList();
    }

    private List<Double> validCoordinates(List<Double> coordinates) {
        return coordinates != null && coordinates.size() == 2 ? coordinates : List.of();
    }
}
