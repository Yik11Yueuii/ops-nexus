package com.opsnexus.observability;

import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
    private static final String HEADER = "X-Trace-Id";

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = valid(request.getHeader(HEADER)) ? request.getHeader(HEADER).trim() : UUID.randomUUID().toString();
        TraceContext.set(traceId);
        MDC.put("traceId", traceId);
        response.setHeader(HEADER, traceId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
            log.info("operation=http_request outcome={} status={} method={}", response.getStatus() < 400 ? "SUCCESS" : "FAILURE", response.getStatus(), request.getMethod());
        } finally {
            MDC.remove("traceId");
            TraceContext.clear();
        }
    }

    static boolean valid(String value) { return value != null && value.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{7,63}"); }
}
