import com.expediagroup.graphql.plugin.gradle.config.GraphQLSerializer

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlin.plugin.serialization)
    alias(libs.plugins.expedia.graphql)
    alias(libs.plugins.sonar)
    id("io.github.androa.gradle.plugin.avro") version "0.0.11"
    jacoco
}

group = "dab.poao.nav.no"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
    maven { url = uri("https://github-package-registry-mirror.gc.nav.no/cached/maven-release") }
    maven { url = uri("https://packages.confluent.io/maven") }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

tasks.shadowJar {
    mergeServiceFiles()
}

tasks.test {
    useJUnitPlatform()
    /* disable unused scanning which adds ~2 seconds to test-startup */
    systemProperty("kotest.framework.discovery.jar.scan.disable", "true")
    systemProperty("kotest.framework.classpath.scanning.config.disable", "true")
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.exposed.core)
    implementation(libs.exposed.datetime)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.jdbi3.core)
    implementation(libs.jdbi3.kotlin)
    implementation(libs.h2)
    implementation(libs.postgresql)
    implementation(libs.ktor.server.metrics)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.openapi)
    implementation(libs.ktor.server.swagger)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.auth)
    implementation(libs.ktor.server.metrics.micrometer)
    implementation(libs.caffeine)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.logstash)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    implementation(libs.hikaricp)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.kafka.avro)
    implementation(libs.kafka.streams)
    implementation(libs.kafka.streams.avro.serde)
    implementation(libs.graphql.kotlin.client)
    implementation(libs.graphql.kotlin.client.serialization)
    implementation(libs.graphql.kotlin.server)
    implementation(libs.graphql.kotlin.schema.generator)
    implementation(libs.token.validation.ktor.v3)
    implementation(libs.nav.poaotilgang.client.core)
    implementation(libs.shedlock)
    implementation(libs.shedlock.exposed)
    implementation(libs.micrometer.registry.prometheus)
    implementation(libs.micrometer.core)
    testImplementation(libs.embedded.postgres)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.mock.oauth2.server)
    testImplementation(libs.kafka.streams.test.utils)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.kotest.runner)
    testImplementation(libs.mockk)
    testImplementation(kotlin("test"))
}

graphql {
    schema {
        packages = listOf(
            "no.nav.http.graphql.queries",
            "no.nav.http.graphql.schemas",
        )
    }
    client {
        serializer = GraphQLSerializer.KOTLINX
        schemaFile = file("src/main/resources/graphql/schema.graphql")
        packageName = "no.nav.http.graphql.generated.client"
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
    }
}

tasks.sonar {
    dependsOn(tasks.jacocoTestReport)
}

sonar {
    properties {
        property("sonar.projectKey", "navikt_ao-oppfolgingskontor")
        property("sonar.organization", "navikt")
        property("sonar.host.url", "https://sonarcloud.io")
    }
}
