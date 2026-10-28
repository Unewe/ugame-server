plugins {
    // скачивает JDK 21 автоматически, если его нет на машине
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ugame-server"

include(
    "protocol",      // общий протокол: .proto + ручная бинарная упаковка
    "game-server",   // игровой сервер: Netty + KCP (совместим с kcp2k)
    "meta-service",  // мета-сервис: Spring Boot (регистрация, вход, матчмейкинг)
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}
