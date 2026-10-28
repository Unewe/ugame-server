import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))

    // В Spring Boot 4 стартеры модульные: web → webmvc, oauth2-resource-server → security-oauth2-resource-server,
    // а Flyway без spring-boot-starter-flyway не запускается.
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
