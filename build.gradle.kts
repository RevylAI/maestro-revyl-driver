plugins {
    kotlin("jvm") version "2.2.0"
    application
}

group = "ai.revyl"
version = "0.1.0"

repositories { mavenCentral() }

kotlin { jvmToolchain(21) }

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.graalvm.js" && requested.name == "js") {
            useTarget("org.graalvm.js:js-community:24.2.0")
            because("Use the community runtime, not the default GFTC enterprise runtime")
        }
    }
}

dependencyLocking { lockAllConfigurations() }

dependencies {
    implementation("dev.mobile:maestro-client:2.10.0")
    implementation("dev.mobile:maestro-orchestra:2.10.0")
    implementation("dev.mobile:maestro-orchestra-models:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio:3.16.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.17.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.apache.logging.log4j:log4j-core:2.25.3")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass.set("ai.revyl.maestro.MainKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true", "-Dpolyglot.engine.WarnInterpreterOnly=false")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    systemProperty("polyglot.engine.WarnInterpreterOnly", "false")
    environment("REVYL_API_KEY", "")
    environment("REVYL_MAESTRO_API_URL", "")
    dependsOn(tasks.installDist)
}

val verifyCommunityRuntime by tasks.registering {
    inputs.files(configurations.runtimeClasspath)
    doLast {
        val modules = configurations.runtimeClasspath.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion?.toString() }.toSet()
        check("org.graalvm.js:js-community:24.2.0" in modules)
        check("org.graalvm.truffle:truffle-runtime:24.2.0" in modules)
        check(modules.none { it.contains("enterprise", ignoreCase = true) })
    }
}

tasks.check { dependsOn(verifyCommunityRuntime) }
tasks.installDist { dependsOn(verifyCommunityRuntime) }
