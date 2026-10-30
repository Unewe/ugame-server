package com.ugame.meta.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

/**
 * Миграции Flyway при старте. Flyway умеет только JDBC, поэтому подключается отдельно от R2DBC.
 * Это единственное блокирующее место в сервисе, и оно выполняется до приёма HTTP-запросов.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DatabaseMigration implements InitializingBean {

    private final UgameProperties props;

    @Override
    public void afterPropertiesSet() {
        UgameProperties.Db db = props.db();
        var result = Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        // если миграций не было, targetSchemaVersion = null — тогда показываем текущую версию
        String version = result.targetSchemaVersion != null ? result.targetSchemaVersion : result.initialSchemaVersion;
        log.info("Flyway: migrations applied {}, current version {}", result.migrationsExecuted, version);
    }
}
