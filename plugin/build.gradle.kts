import io.izzel.taboolib.gradle.Basic
import io.izzel.taboolib.gradle.Bukkit
import io.izzel.taboolib.gradle.BukkitHook
import io.izzel.taboolib.gradle.BukkitUtil
import io.izzel.taboolib.gradle.CommandHelper
import io.izzel.taboolib.gradle.I18n
import io.izzel.taboolib.gradle.Metrics
import io.izzel.taboolib.gradle.MinecraftChat
import org.gradle.jvm.tasks.Jar

plugins {
    java
    kotlin("jvm")
    id("io.izzel.taboolib")
}
taboolib {
    env {
        install(Basic)
        install(BukkitHook)
        install(BukkitUtil)
        install(CommandHelper)
        install(I18n)
        install(MinecraftChat)
        install(Bukkit)
        install(Metrics)
    }
    description {
        name = "SpectrumMobs"
        contributors {
            name("ewsk")
        }
    }
    version {
        taboolib = "6.3.0-0e96352"
    }
}

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("ink.ptms.core:v12004:12004:mapped")
    compileOnly("ink.ptms.core:v12004:12004:universal")
    testImplementation("ink.ptms.core:v12004:12004:mapped")
    testImplementation("ink.ptms.core:v12004:12004:universal")
    testRuntimeOnly("com.google.guava:guava:32.1.2-jre")
    testRuntimeOnly("org.joml:joml:1.10.5")
    testImplementation("org.mockito:mockito-core:4.11.0")
    implementation(project(":core"))
    implementation(project(":script"))
}

base {
    archivesName.set("SpectrumMobs")
}

tasks.named<Jar>("jar") {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn(":core:classes", ":script:classes")
    from(project(":core").layout.buildDirectory.dir("classes/kotlin/main"))
    from(project(":script").layout.buildDirectory.dir("classes/kotlin/main"))
}
