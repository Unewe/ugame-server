package com.ugame.meta.matchmaking;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Читает результаты матчей из Redis Stream через consumer group: запись подтверждается (XACK)
 * только после успешной записи в БД, так что при падении мета-сервиса результат не теряется.
 */
@Component
public class MatchResultConsumer {
    private static final Logger log = LoggerFactory.getLogger(MatchResultConsumer.class);
    private static final String GROUP = "meta-service";
    private static final String CONSUMER = "meta-1"; // при нескольких экземплярах — уникальное имя

    private final StringRedisTemplate redis;
    private final MatchResultService results;
    private volatile boolean ready;

    public MatchResultConsumer(StringRedisTemplate redis, MatchResultService results) {
        this.redis = redis;
        this.results = results;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void createGroup() {
        try {
            redis.execute((RedisCallback<String>) connection -> connection.streamCommands().xGroupCreate(
                    RedisKeys.MATCH_RESULTS_STREAM.getBytes(StandardCharsets.UTF_8), GROUP,
                    ReadOffset.from("0"), true));
        } catch (RuntimeException e) {
            // BUSYGROUP — группа уже есть, это нормально
            if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")
                    && (e.getCause() == null || !String.valueOf(e.getCause().getMessage()).contains("BUSYGROUP"))) {
                log.warn("Не удалось создать consumer group: {}", e.toString());
            }
        }
        ready = true;
    }

    @Scheduled(fixedDelay = 1000)
    public void poll() {
        if (!ready) {
            return;
        }
        // Сначала свои неподтверждённые (после падения), затем новые.
        process(ReadOffset.from("0"));
        process(ReadOffset.lastConsumed());
    }

    private void process(ReadOffset offset) {
        List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                Consumer.from(GROUP, CONSUMER),
                StreamReadOptions.empty().count(50),
                StreamOffset.create(RedisKeys.MATCH_RESULTS_STREAM, offset));
        if (records == null) {
            return;
        }
        for (MapRecord<String, Object, Object> record : records) {
            Map<Object, Object> f = record.getValue();
            String matchId = (String) f.get("matchId");
            String winner = (String) f.get("winnerTeam");
            if (matchId == null || winner == null) {
                // битая запись: подтверждаем, иначе она будет перечитываться вечно
                log.error("Некорректная запись результата {}: {}", record.getId(), f);
                redis.opsForStream().acknowledge(RedisKeys.MATCH_RESULTS_STREAM, GROUP, record.getId());
                continue;
            }
            try {
                results.apply(matchId, Integer.parseInt(winner), (String) f.get("results"));
                redis.opsForStream().acknowledge(RedisKeys.MATCH_RESULTS_STREAM, GROUP, record.getId());
            } catch (RuntimeException e) {
                log.error("Ошибка обработки результата {}: {}", record.getId(), e.toString());
            }
        }
    }
}
