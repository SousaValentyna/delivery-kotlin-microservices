plugins {
    kotlin("jvm") version "1.9.25"
    application
    kotlin("plugin.serialization") version "1.9.25"
}

group = "com.delivery"
version = "1.0.0"

repositories {
    mavenCentral()
}

val ktor = "2.3.12"

dependencies {
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-netty:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
}

application {
    mainClass.set("com.delivery.billing.ApplicationKt")
}
