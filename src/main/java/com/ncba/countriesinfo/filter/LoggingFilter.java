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
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Logs every request with a request id and records the
 * {@code http_requests_total} and {@code http_request_duration_seconds}
 * metrics, labeled with the matched route pattern.
 */
@Component
public class LoggingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

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
        long startNanos = System.nanoTime();

        try {
            chain.doFilter(request, response);
        } finally {
            long durationMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
            int status = res.getStatus();

            String method = req.getMethod();
            String path = req.getRequestURI();
            String route = routePattern(req);
            String remoteIp = req.getRemoteAddr();

            meterRegistry.counter("http.requests", "method", method, "path", route,
                    "status", String.valueOf(status)).increment();
            meterRegistry.timer("http.request.duration", "method", method, "path", route)
                    .record(durationMillis, TimeUnit.MILLISECONDS);

            String logMessage = "request_id={} method={} path={} ip={} status={} latency={}ms";
            if (status >= 500) {
                log.error(logMessage, requestId, method, path, remoteIp, status, durationMillis);
            } else if (status >= 400) {
                log.warn(logMessage, requestId, method, path, remoteIp, status, durationMillis);
            } else {
                log.info(logMessage, requestId, method, path, remoteIp, status, durationMillis);
            }
        }
    }

    private String routePattern(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        return pattern != null ? pattern.toString() : request.getRequestURI();
    }
}
