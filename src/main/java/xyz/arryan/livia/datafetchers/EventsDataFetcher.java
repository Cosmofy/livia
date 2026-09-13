package xyz.arryan.livia.datafetchers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import xyz.arryan.livia.codegen.types.Event;
import xyz.arryan.livia.services.NatureService;

import java.util.List;

@DgsComponent
public class EventsDataFetcher {
    private final NatureService natureService;

    public EventsDataFetcher(NatureService natureService) {
        this.natureService = natureService;
    }

    @DgsQuery
    public List<Event> events(@InputArgument Integer daysInput) {
        return natureService.events(daysInput);
    }
}
