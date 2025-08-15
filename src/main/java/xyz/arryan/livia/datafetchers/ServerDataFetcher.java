package xyz.arryan.livia.datafetchers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;

@DgsComponent
public class ServerDataFetcher {

    @DgsQuery
    public String server() {
        return System.getenv("LIVIA_REGION");
    }
}
