package com.ugame.meta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Мета-сервис: аккаунты (регистрация/вход/JWT), профиль, матчмейкинг, приём результатов матчей.
 * Реактивный: WebFlux + R2DBC + реактивный Redis. С игровым сервером общается только через Redis.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class MetaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MetaServiceApplication.class, args);
    }
}
