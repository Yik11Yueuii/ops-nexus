package com.opsnexus.observability;

/** Request-scoped correlation id; never use it as a metric label. */
public final class TraceContext {
    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();
    private TraceContext() { }
    public static String current() { return TRACE_ID.get(); }
    public static void set(String traceId) { TRACE_ID.set(traceId); }
    public static void clear() { TRACE_ID.remove(); }
}
