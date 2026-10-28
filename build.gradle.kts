plugins {
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.protobuf) apply false
}

subprojects {
    apply(plugin = "java")

    group = "com.ugame"
    version = "0.1.0"

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(21))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
    }
}
