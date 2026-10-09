package com.ncba.countriesinfo.filter;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Logs every request with a correlation id and records the
 * {@code http_requests_total} and {@code http_request_duration_seconds}
 * metrics, labeled with the matched route pattern.
 *
 * <p>The correlation id and request attributes are placed in the MDC so
 * every log statement emitted while handling the request carries them — in
 * the JSON container format they appear as top-level fields
 * ({@code requestId}, {@code ip}, {@code route}, {@code method}).</p>
 *
 * <p>Kubernetes liveness/readiness probes hit {@code /health} and
 * {@code /ready} every few seconds; successful probe requests are not
 * logged to keep the container output readable (failures still are).</p>
 */
@Component
public class LoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    /** Endpoints polled by probes and scrapers; successful hits are not logged. */
    private static final Set<String> PROBE_PATHS = Set.of("/health", "/ready", "/metrics");

    private final MeterRegistry meterRegistry;

    public LoggingFilter(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse res = (HttpServletResponse) response;

        String requestId = UUID.randomUUID().toString();
        String method = req.getMethod();
        String path = req.getRequestURI();
        String route = routePattern(req);
        String remoteIp = req.getRemoteAddr();
        long startNanos = System.nanoTime();

        MDC.put("requestId", requestId);
        MDC.put("method", method);
        MDC.put("route", route);
        MDC.put("ip", remoteIp);

        try {
            chain.doFilter(request, response);
        } finally {
            try {
                long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
                int status = res.getStatus();

                meterRegistry.counter("http.requests", "method", method, "path", route,
                        "status", String.valueOf(status)).increment();
                meterRegistry.timer("http.request.duration", "method", method, "path", route)
                        .record(durationMillis, TimeUnit.MILLISECONDS);

                if (status >= 400 || !PROBE_PATHS.contains(path)) {
                    MDC.put("status", String.valueOf(status));
                    MDC.put("latency", String.valueOf(durationMillis));
                    String logMessage = "method={} path={} ip={} status={} latency={}ms";
                    if (status >= 500) {
                        log.error(logMessage, method, path, remoteIp, status, durationMillis);
                    } else if (status >= 400) {
                        log.warn(logMessage, method, path, remoteIp, status, durationMillis);
                    } else {
                        log.info(logMessage, method, path, remoteIp, status, durationMillis);
                    }
                }
            } finally {
                MDC.clear();
            }
        }
    }

    private String routePattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }
}
