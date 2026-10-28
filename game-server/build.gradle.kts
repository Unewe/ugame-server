plugins {
    application
}

dependencies {
    implementation(project(":protocol"))

    implementation(platform(libs.netty.bom))
    implementation(libs.netty.buffer)
    implementation(libs.netty.transport)
    implementation(libs.netty.epoll.classes)
    // нативный epoll для Linux-сервера; на Windows/macOS автоматически используется NIO
    runtimeOnly(variantOf(libs.netty.epoll.native) { classifier("linux-x86_64") })

    // Берём только ядро алгоритма (kcp.Kcp). netty-all исключаем, чтобы версии Netty
    // задавались одним BOM и не конфликтовали с Lettuce.
    implementation(libs.kcp.base) {
        exclude(group = "io.netty", module = "netty-all")
    }

    implementation(libs.lettuce)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("com.ugame.game.GameServerMain")
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseZGC",
        "-XX:+ZGenerational",
        "-Xms512m",
        "-Xmx512m",
    )
}
