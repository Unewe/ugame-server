package com.ugame.meta.matchmaking;

import com.ugame.meta.config.UgameProperties;
import jakarta.annotation.PreDestroy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.ReactiveStreamOperations;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Читает результаты матчей из Redis Stream через consumer group: запись подтверждается (XACK)
 * только после успешной записи в БД, так что при падении мета-сервиса результат не теряется.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchResultConsumer {
    private static final String GROUP = "meta-service";
    private static final String CONSUMER = "meta-1"; // при нескольких экземплярах — уникальное имя

    private final ReactiveStringRedisTemplate redis;
    private final MatchResultService results;
    private final UgameProperties props;

    private Disposable loop;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        loop = createGroup()
                .thenMany(Flux.interval(props.matchmaking().pollInterval()))
                .onBackpressureDrop()
                // сначала свои неподтверждённые (остались после падения), затем новые
                .concatMap(tick -> process(ReadOffset.from("0"))
                        .then(process(ReadOffset.lastConsumed()))
                        .onErrorResume(e -> {
                            log.error("Error reading match results", e);
                            return Mono.empty();
                        }), 1)
                .subscribe();
    }

    @PreDestroy
    public void stop() {
        if (loop != null) {
            loop.dispose();
        }
    }

    /** XGROUP CREATE ... MKSTREAM; BUSYGROUP (группа уже есть) — нормально. */
    private Mono<Void> createGroup() {
        ByteBuffer key = ByteBuffer.wrap(RedisKeys.MATCH_RESULTS_STREAM.getBytes(StandardCharsets.UTF_8));
        return redis.execute(connection -> connection.streamCommands()
                        .xGroupCreate(key, GROUP, ReadOffset.from("0"), true))
                .then()
                .onErrorResume(e -> {
                    if (!String.valueOf(e.getMessage()).contains("BUSYGROUP")
                            && (e.getCause() == null || !String.valueOf(e.getCause().getMessage()).contains("BUSYGROUP"))) {
                        log.warn("Failed to create consumer group: {}", e.toString());
                    }
                    return Mono.empty();
                });
    }

    private Mono<Void> process(ReadOffset offset) {
        return streams()
                .read(Consumer.from(GROUP, CONSUMER),
                        StreamReadOptions.empty().count(50),
                        StreamOffset.create(RedisKeys.MATCH_RESULTS_STREAM, offset))
                .concatMap(this::handle)
                .then();
    }

    private Mono<Long> handle(MapRecord<String, String, String> record) {
        Map<String, String> f = record.getValue();
        String matchId = f.get("matchId");
        String winner = f.get("winnerTeam");
        Mono<Long> ack = streams().acknowledge(RedisKeys.MATCH_RESULTS_STREAM, GROUP, record.getId());

        if (matchId == null || winner == null) {
            // битая запись: подтверждаем, иначе она будет перечитываться вечно
            log.error("Incorrect recording of result {}: {}", record.getId(), f);
            return ack;
        }
        return Mono.defer(() -> results.apply(matchId, Integer.parseInt(winner), f.get("results")))
                .then(ack)
                .onErrorResume(e -> {
                    // не подтверждаем — запись перечитается на следующем тике
                    log.error("Error processing result {}: {}", record.getId(), e.toString());
                    return Mono.empty();
                });
    }

    private ReactiveStreamOperations<String, String, String> streams() {
        return redis.opsForStream();
    }
}
