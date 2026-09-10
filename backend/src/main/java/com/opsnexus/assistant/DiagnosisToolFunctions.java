package com.opsnexus.assistant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The only methods exposed to the diagnosis model. They are intentionally small, read-only DTO operations;
 * repositories, SQL, shell access and arbitrary HTTP endpoints are never registered as tools.
 */
@Component
public class DiagnosisToolFunctions {
    private final JdbcTemplate db;

    public DiagnosisToolFunctions(JdbcTemplate db) {
        this.db = db;
    }

    public record ServiceInput(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[a-z][a-z0-9-]{1,99}") String service) { }
    public record ServiceStatus(String serviceName, String displayName, String ownerName,
            String currentVersion, String runtimeStatus) { }
    public record Release(String version, String environment, String status, Instant releasedAt, String summary) { }
    public record Incident(String symptom, String rootCause, String resolution, String status, Instant occurredAt) { }

    @Tool(name = "lookup_service_status", description = "Read the selected service's owner, current version and runtime status. Use when current service state is needed.")
    public ServiceStatus lookupServiceStatus(ServiceInput input) {
        return db.queryForObject("SELECT service_name,display_name,owner_name,current_version,runtime_status FROM service_catalog WHERE service_name=?",
            (row, index) -> new ServiceStatus(row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5)), input.service());
    }

    @Tool(name = "lookup_recent_releases", description = "Read at most five recent release records for the selected service. Use when a deployment or version change may be relevant.")
    public List<Release> lookupRecentReleases(ServiceInput input) {
        return db.query("SELECT version_no,environment,status,released_at,summary FROM release_record WHERE service_name=? ORDER BY released_at DESC LIMIT 5",
            (row, index) -> new Release(row.getString(1), row.getString(2), row.getString(3), row.getTimestamp(4).toInstant(), row.getString(5)), input.service());
    }

    /** Historical incident details contain operational root-cause material and are ADMIN-only in the registry. */
    @Tool(name = "lookup_recent_incidents", description = "Read at most five historical incidents for the selected service. This tool requires administrator permission.")
    public List<Incident> lookupRecentIncidents(ServiceInput input) {
        return db.query("SELECT symptom,root_cause,resolution,status,occurred_at FROM incident_record WHERE service_name=? ORDER BY occurred_at DESC LIMIT 5",
            (row, index) -> new Incident(row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getTimestamp(5).toInstant()), input.service());
    }

    public boolean exists(String service) {
        return db.queryForObject("SELECT COUNT(*) FROM service_catalog WHERE service_name=?", Integer.class, service) > 0;
    }
}
