package xyz.arryan.livia.datafetchers;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventsDataFetcherTest {

    @Test
    void preservesPointCoordinates() {
        assertEquals(
                List.of(-122.42, 37.77),
                EventsDataFetcher.normalizeCoordinates(List.of(-122.42, 37.77))
        );
    }

    @Test
    void extractsMarkerFromPolygonCoordinates() {
        List<?> polygon = List.of(
                List.of(
                        List.of(-120.0, 35.0),
                        List.of(-121.0, 36.0),
                        List.of(-120.0, 35.0)
                )
        );

        assertEquals(
                List.of(-120.0, 35.0),
                EventsDataFetcher.normalizeCoordinates(polygon)
        );
    }

    @Test
    void extractsMarkerFromMultiPolygonCoordinates() {
        List<?> multiPolygon = List.of(
                List.of(
                        List.of(
                                List.of(140.0, -30.0),
                                List.of(141.0, -31.0),
                                List.of(140.0, -30.0)
                        )
                )
        );

        assertEquals(
                List.of(140.0, -30.0),
                EventsDataFetcher.normalizeCoordinates(multiPolygon)
        );
    }

    @Test
    void rejectsMalformedCoordinates() {
        assertEquals(List.of(), EventsDataFetcher.normalizeCoordinates(List.of("unknown")));
        assertEquals(List.of(), EventsDataFetcher.normalizeCoordinates(null));
    }
}
