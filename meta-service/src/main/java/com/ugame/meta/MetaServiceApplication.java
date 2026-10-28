package com.ugame.meta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Мета-сервис: аккаунты (регистрация/вход/JWT), профиль, матчмейкинг, приём результатов матчей.
 * С игровым сервером общается только через Redis.
 */
@SpringBootApplication
@EnableScheduling
@ConfigurationPropertiesScan
public class MetaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(MetaServiceApplication.class, args);
    }
}
