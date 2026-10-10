import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    java
    id("org.springframework.boot") version "4.1.0"
}

val lombokVersion = "1.18.46"

// Not managed by the Spring Boot BOM. springdoc 3.1.0 is built against Spring Boot 4.1.0;
// the 2.x line targets Boot 3.x and will not work here.
val springdocVersion = "3.1.0"

group = "com.stockmarketanalyzer"
version = "0.0.1-SNAPSHOT"
description = "Stock Market Analyzer :: backend"

java {
    toolchain {
        // Java 25 (LTS). Gradle downloads / locates a matching JDK automatically.
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

dependencies {
    // Spring Boot BOM -- keeps every Spring-managed dependency version in sync
    // with the Spring Boot plugin version declared above.
    implementation(platform(SpringBootPlugin.BOM_COORDINATES))
    annotationProcessor(platform(SpringBootPlugin.BOM_COORDINATES))
    testImplementation(platform(SpringBootPlugin.BOM_COORDINATES))

    // --- web / REST ---------------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // --- security -----------------------------------------------------------
    // OAuth2 resource server: validates Keycloak-issued JWTs. No session, no login page.
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")

    // --- API documentation --------------------------------------------------
    // Serves /v3/api-docs (OpenAPI 3.1 JSON/YAML) and /swagger-ui.html.
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")

    // --- persistence --------------------------------------------------------
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    // Boot 4 split the old monolithic spring-boot-autoconfigure into per-feature modules.
    // `liquibase-core` gives you the library but NOT `LiquibaseAutoConfiguration`, so without
    // this module migrations silently never run -- no error, no changelog tables, and the
    // actuator `liquibase` endpoint never appears. Version comes from the BOM.
    implementation("org.springframework.boot:spring-boot-liquibase")
    implementation("org.liquibase:liquibase-core")
    runtimeOnly("org.postgresql:postgresql")

    // --- Lombok -------------------------------------------------------------
    // Pinned explicitly: JDK 25 support landed in 1.18.40, JDK 26 in 1.18.46.
    compileOnly("org.projectlombok:lombok:$lombokVersion")
    annotationProcessor("org.projectlombok:lombok:$lombokVersion")
    testCompileOnly("org.projectlombok:lombok:$lombokVersion")
    testAnnotationProcessor("org.projectlombok:lombok:$lombokVersion")

    // --- build-time helpers -------------------------------------------------
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    // NOTE: `developmentOnly` is a standalone configuration -- it does NOT extend
    // `implementation`, so the BOM above does not reach it. Anything added here either
    // needs its own explicit version or its own platform() line, e.g.:
    //
    //   developmentOnly(platform(SpringBootPlugin.BOM_COORDINATES))
    //   developmentOnly("org.springframework.boot:spring-boot-devtools")

    // --- test ---------------------------------------------------------------
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-serial,-processing"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
