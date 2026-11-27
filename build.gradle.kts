import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.compose") version "2.2.10"
    id("org.jetbrains.compose") version "1.9.0"
    id("com.google.devtools.ksp") version "2.2.10-2.0.2"
    id("androidx.room") version "2.8.4"
}

repositories {
    mavenCentral()
    google {
        content { includeGroupByRegex("androidx\\..*") }
    }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.compose.material3:material3:1.9.0")
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.sqlite:sqlite-bundled:2.6.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation(kotlin("test-junit"))
}

room { schemaDirectory("$projectDir/schemas") }

compose.desktop {
    application {
        mainClass = "journal.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "TradingJournal"
            packageVersion = "1.0.1"
            modules("jdk.unsupported")
            windows {
                menu = true
                shortcut = true
                upgradeUuid = "695a51ac-322b-4d88-8612-9238d4daac12"
            }
        }
    }
}
