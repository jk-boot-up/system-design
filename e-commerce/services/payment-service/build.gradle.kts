import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    alias(libs.plugins.spring.boot)
}

group = "com.jkbootup.ecommerce"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(libs.versions.java.get().toInt())
    }
}

dependencies {
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation(libs.shedlock.spring)
    implementation(libs.shedlock.jdbc)
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")

    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testImplementation("org.testcontainers:testcontainers-kafka")
    testImplementation(libs.archunit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform {
        // Container-based tests run when Docker is available (always in CI): ./gradlew test -PwithContainers
        if (!project.hasProperty("withContainers")) excludeTags("containers")
    }
    jvmArgs("-XX:+EnableDynamicAgentLoading")
}
