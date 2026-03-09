import com.github.benmanes.gradle.versions.updates.DependencyUpdatesTask

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
    `maven-publish`
    alias(libs.plugins.shadow)
    alias(libs.plugins.ben.manes.versions)
}

description = "discord-tempchannel-bot"
java.sourceCompatibility = JavaVersion.VERSION_21

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)

    implementation(libs.net.dv8tion.jda)

    implementation(libs.bundles.exposed)
    implementation(libs.mysql.connector)
    implementation(libs.sqlite.jdbc)

    implementation(libs.jackson.databind)
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-Xlint:deprecation")
    }

    withType<Javadoc> {
        options.encoding = "UTF-8"
    }

    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("bot.jar")
        manifest {
            attributes["Main-Class"] = "de.novium.dev.BotMain"
        }
        mergeServiceFiles()
    }

    withType<DependencyUpdatesTask> {
        checkForGradleUpdate = false
        rejectVersionIf {
            candidate.version.matches(Regex("(?i).*[.-](rc|beta|alpha|milestone)[.\\d-]*"))
        }
    }
}
