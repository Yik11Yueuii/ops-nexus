package com.opsnexus.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ConversationContextServiceTest {
    @Mock JdbcTemplate db;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String,String> values;

    @Test void usesUserAndConversationScopedRedisHitWithoutDatabaseRead() throws Exception {
        when(redis.opsForValue()).thenReturn(values);
        var mapper = new ObjectMapper();
        when(values.get("opsnexus:conversation:7:3:recent")).thenReturn(mapper.writeValueAsString(List.of(new ConversationContextService.Message("USER", "order-service 是什么？"))));
        var service = new ConversationContextService(db, redis, mapper, 8, 4000, 1800);
        var snapshot = service.recent(7, 3);
        assertEquals("REDIS", snapshot.source()); assertEquals(1, snapshot.messages().size());
        verifyNoInteractions(db);
    }

    @Test void cacheMissAndRedisFailureBothFallBackToDatabaseAndBoundContext() {
        when(redis.opsForValue()).thenReturn(values);
        when(db.queryForList(anyString(), eq(3L), eq(7L), eq(2))).thenReturn(List.of(
                Map.of("ROLE", "ASSISTANT", "CONTENT", "456"), Map.of("ROLE", "USER", "CONTENT", "789")));
        var service = new ConversationContextService(db, redis, new ObjectMapper(), 2, 6, 1800);
        var miss = service.recent(7, 3);
        assertEquals("DATABASE", miss.source()); assertEquals(2, miss.messages().size()); assertTrue(miss.chars() <= 6);
        verify(values).set(eq("opsnexus:conversation:7:3:recent"), anyString(), eq(Duration.ofSeconds(1800)));
        verify(db).queryForList(contains("confidence_level IS NOT NULL"), eq(3L), eq(7L), eq(2));

        when(redis.opsForValue()).thenThrow(new IllegalStateException("Redis unavailable"));
        var down = service.recent(7, 3);
        assertEquals("DATABASE", down.source()); assertEquals(2, down.messages().size());
    }
}
