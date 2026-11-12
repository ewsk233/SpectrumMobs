plugins {
    kotlin("jvm") version "2.2.0" apply false
    id("io.izzel.taboolib") version "2.0.36" apply false
}

allprojects {
    group = "org.ewsk.spectrummobs"
    version = providers.gradleProperty("version").orElse("0.1.0-SNAPSHOT").get()
}

subprojects {
    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        dependencies {
            add("testImplementation", kotlin("test"))
        }
        tasks.withType<Test>().configureEach {
            useJUnitPlatform()
        }
    }
}
