plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure JVM: no Android classes here, so the engine runs under plain JUnit against MockWebServer.
kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.okhttp)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
}
