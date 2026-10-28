plugins {
    `java-library`
    alias(libs.plugins.protobuf)
}

dependencies {
    api(libs.protobuf.java)

    testImplementation(platform(libs.junit.bom))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.asProvider().get()}"
    }
}
