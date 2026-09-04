package xyz.arryan.livia.config;

import graphql.ExecutionResult;
import graphql.execution.instrumentation.InstrumentationContext;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationExecutionParameters;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import xyz.arryan.livia.observability.TraceLogContext;

@Component
public class RequestLoggingInstrumentation extends SimplePerformantInstrumentation {

    private static final Logger log = LoggerFactory.getLogger("graphql.request");

    @Override
    public InstrumentationContext<ExecutionResult> beginExecution(
            InstrumentationExecutionParameters parameters,
            InstrumentationState state) {

        long startTime = System.nanoTime();
        String operation = parameters.getOperation();
        if (operation == null || operation.isBlank()) operation = "anonymous";
        if (operation.length() > 128) operation = operation.substring(0, 128);
        String region = System.getenv("LIVIA_REGION");
        if (region == null) region = "unknown";
        final String operationName = operation;
        final String server = region;
        final String requestId = RequestIdFilter.currentRequestId();
        final Context traceContext = Context.current();

        return new InstrumentationContext<>() {
            @Override
            public void onDispatched() {}

            @Override
            public void onCompleted(ExecutionResult result, Throwable t) {
                long duration = (System.nanoTime() - startTime) / 1_000_000;
                boolean hasErrors = result != null && !result.getErrors().isEmpty();
                String status = (t != null || hasErrors) ? "ERROR" : "OK";
                try (Scope ignoredScope = traceContext.makeCurrent();
                     TraceLogContext ignored = TraceLogContext.open(requestId)) {
                    log.info("graphql request completed operation={} server={} duration_ms={} status={}",
                            operationName, server, duration, status);
                }
            }
        };
    }
}
