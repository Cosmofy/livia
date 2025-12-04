package xyz.arryan.livia.controllers;

import com.netflix.graphql.dgs.DgsQueryExecutor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class HealthController {

    private final DgsQueryExecutor dgsQueryExecutor;

    public HealthController(DgsQueryExecutor dgsQueryExecutor) {
        this.dgsQueryExecutor = dgsQueryExecutor;
    }

    @GetMapping("/health")
    public ResponseEntity<String> health() {
        try {
            String serverValue = dgsQueryExecutor.executeAndExtractJsonPath(
                    "{ server }", "data.server"
            );
            if (serverValue != null && !serverValue.isEmpty()) {
                return ResponseEntity.ok("200");
            } else {
                return ResponseEntity.status(500).body("GraphQL query failed");
            }
        } catch (Exception e) {
            return ResponseEntity.status(500).body("GraphQL not responding");
        }
    }
}