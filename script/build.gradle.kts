plugins {
    kotlin("jvm")
}

dependencies {
    compileOnly(project(":core"))
    testImplementation(kotlin("test"))
}

tasks.test {
    inputs.dir(rootProject.file("docs"))
    systemProperty("spectrum.docs", rootProject.file("docs").absolutePath)
}
