// Pure Kotlin/JVM: the Wi-Fi logic ported from the Mac app (Sources/WiFiCore). No Android types,
// so its tests run on the JVM without a device.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

// Simulation benchmarks are opt-in: ./gradlew :core:test -Pbench --tests '*SearchBenchmark*' -i
tasks.test {
    systemProperty("bench", providers.gradleProperty("bench").isPresent.toString())
    for (key in listOf("runs", "intervals", "only", "harsh")) {
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    maxHeapSize = "2g"
}
