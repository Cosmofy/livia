package xyz.arryan.livia.datafetchers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsData;
import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import xyz.arryan.livia.codegen.types.Picture;
import xyz.arryan.livia.codegen.types.Pictures;
import xyz.arryan.livia.codegen.types.EarthObservatoryPicture;
import xyz.arryan.livia.codegen.types.SearchPicture;
import xyz.arryan.livia.codegen.types.EarthObservatorySearchPayload;
import xyz.arryan.livia.codegen.types.EarthObservatorySimilarityPayload;
import xyz.arryan.livia.errors.ApodException;
import xyz.arryan.livia.services.ApodService;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

@DgsComponent
public class PicturesDataFetcher {

    private final ApodService service;

    public PicturesDataFetcher(ApodService service) {
        this.service = service;
    }

    @DgsQuery(field = "pictures")
    public Pictures pictures() {
        return Pictures.newBuilder().build();
    }

    @DgsQuery(field = "picture")
    public Picture picture(@InputArgument String date) {
        String normalizedDate = date == null || date.isBlank() ? null : date;
        if (normalizedDate == null) return service.picture(null);
        try {
            return service.picture(LocalDate.parse(normalizedDate));
        } catch (DateTimeParseException exception) {
            throw ApodException.validation("INVALID_DATE_FORMAT");
        }
    }

    @DgsData(parentType = "Pictures", field = "astronomy")
    public List<Picture> astronomy(
            @InputArgument String date,
            @InputArgument String search,
            @InputArgument Integer limit) {
        String normalizedDate = date == null || date.isBlank() ? null : date;
        String normalizedSearch = search == null || search.isBlank() ? null : search;
        if (normalizedDate != null && normalizedSearch != null) {
            throw ApodException.validation("INVALID_PICTURE_LOOKUP");
        }
        if (normalizedSearch != null) {
            return service.search(normalizedSearch, limit);
        }
        if (normalizedDate == null) return List.of(service.picture(null));
        try {
            return List.of(service.picture(LocalDate.parse(normalizedDate)));
        } catch (DateTimeParseException exception) {
            throw ApodException.validation("INVALID_DATE_FORMAT");
        }
    }

    @DgsData(parentType = "Pictures", field = "earthObservatory")
    public EarthObservatoryPicture earthObservatory(@InputArgument String date) {
        if (date == null || date.isBlank()) return service.earthObservatory(null);
        try {
            return service.earthObservatory(LocalDate.parse(date));
        } catch (DateTimeParseException exception) {
            throw ApodException.validation("INVALID_DATE_FORMAT");
        }
    }

    @DgsData(parentType = "EarthObservatoryPicture", field = "search")
    public EarthObservatorySearchPayload earthObservatorySearch(@InputArgument String query, @InputArgument Integer limit) {
        return service.earthObservatorySearch(query, limit);
    }

    @DgsData(parentType = "EarthObservatoryPicture", field = "similar")
    public EarthObservatorySimilarityPayload earthObservatorySimilar(@InputArgument Integer limit, DgsDataFetchingEnvironment environment) {
        EarthObservatoryPicture picture = environment.getSource();
        if (picture == null || picture.getDate() == null) throw ApodException.invalidResponse(null);
        return service.earthObservatorySimilar(picture.getDate(), limit);
    }

    @DgsData(parentType = "Picture", field = "similar")
    public List<SearchPicture> similar(@InputArgument Integer limit, DgsDataFetchingEnvironment environment) {
        Picture picture = environment.getSource();
        if (picture == null || picture.getDate() == null) {
            throw ApodException.invalidResponse(null);
        }
        return service.similar(picture.getDate(), limit);
    }

}
