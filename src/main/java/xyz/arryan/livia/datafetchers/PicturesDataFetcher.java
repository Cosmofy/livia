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
import xyz.arryan.livia.errors.ApodException;
import xyz.arryan.livia.services.ApodService;

import java.time.LocalDate;
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

    @DgsData(parentType = "Pictures", field = "astronomy")
    public List<Picture> astronomy(
            @InputArgument LocalDate date,
            @InputArgument String search,
            @InputArgument Integer limit) {
        if (date != null && search != null) {
            throw ApodException.validation("INVALID_PICTURE_LOOKUP");
        }
        if (search != null) {
            return service.search(search, limit);
        }
        return List.of(service.picture(date));
    }

    @DgsData(parentType = "Pictures", field = "earthObservatory")
    public EarthObservatoryPicture earthObservatory(@InputArgument LocalDate date) {
        return service.earthObservatory(date);
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
