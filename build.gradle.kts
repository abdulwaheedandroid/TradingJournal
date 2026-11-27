import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.2.10"
    kotlin("plugin.compose") version "2.2.10"
    id("org.jetbrains.compose") version "1.9.0"
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
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation(kotlin("test-junit"))
}

compose.desktop {
    application {
        mainClass = "journal.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "TradingJournal"
            packageVersion = "1.0.0"
            modules("java.sql", "jdk.unsupported")
            windows {
                menu = true
                shortcut = true
                upgradeUuid = "695a51ac-322b-4d88-8612-9238d4daac12"
            }
        }
    }
}
