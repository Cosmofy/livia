package xyz.arryan.livia.services;

import org.springframework.stereotype.Service;
import xyz.arryan.livia.clients.NatureClient;
import xyz.arryan.livia.codegen.types.Event;
import xyz.arryan.livia.errors.NatureException;
import xyz.arryan.livia.mappers.NatureMapper;

import java.util.List;

@Service
public class NatureService {
    private final NatureClient client;
    private final NatureMapper mapper;

    public NatureService(NatureClient client, NatureMapper mapper) {
        this.client = client;
        this.mapper = mapper;
    }

    public List<Event> events(Integer days) {
        int resolvedDays = days == null ? 14 : days;
        if (resolvedDays < 0) throw NatureException.validation("INVALID_EVENTS_QUERY");
        return mapper.toEvents(client.events(resolvedDays));
    }
}
