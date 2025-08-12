package xyz.arryan.livia.datafetchers;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DgsComponent
public class TimeDataFetcher {

    private static final Logger logger = LoggerFactory.getLogger(TimeDataFetcher.class);
    private static final String LOG_PREFIX = "[API 5: TIME] ";

    @DgsQuery
    public long time() {
        logger.info(LOG_PREFIX + "fetching current timestamp");
        long currentTimestamp = System.currentTimeMillis();
        logger.info(LOG_PREFIX + "fetch complete: {}", currentTimestamp);
        return currentTimestamp;
    }
}