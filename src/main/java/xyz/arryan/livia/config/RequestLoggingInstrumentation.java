package xyz.arryan.livia.config;

import graphql.ExecutionResult;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;

@Component
public class RequestLoggingInstrumentation extends SimplePerformantInstrumentation {

    private static final Logger log = LoggerFactory.getLogger("graphql.request");

    @Override
    public InstrumentationContext<ExecutionResult> beginExecution(
            InstrumentationExecutionParameters parameters,
            InstrumentationState state) {

        long startTime = System.currentTimeMillis();
        String query = parameters.getQuery().replaceAll("\\s+", " ").trim();

        String clientIp = "unknown";
        String region = System.getenv("LIVIA_REGION");
        if (region == null) region = "unknown";

        try {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs != null) {
                HttpServletRequest request = attrs.getRequest();
                clientIp = request.getHeader("X-Forwarded-For");
                if (clientIp == null || clientIp.isEmpty()) {
                    clientIp = request.getHeader("X-Real-IP");
                }
                if (clientIp == null || clientIp.isEmpty()) {
                    clientIp = request.getRemoteAddr();
                }
                if (clientIp != null && clientIp.contains(",")) {
                    clientIp = clientIp.split(",")[0].trim();
                }
            }
        } catch (Exception e) {
            // ignore
        }

        final String ip = clientIp;
        final String server = region;

        return new InstrumentationContext<>() {
            @Override
            public void onDispatched() {}

            @Override
            public void onCompleted(ExecutionResult result, Throwable t) {
                long duration = System.currentTimeMillis() - startTime;
                String status = (t != null || result.getErrors().size() > 0) ? "ERROR" : "OK";
                log.info("ip={} server={} duration={}ms status={} query={}", ip, server, duration, status, query);
            }
        };
    }
}
