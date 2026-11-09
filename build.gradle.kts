plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
}

// Optional: put every module's build/ elsewhere (local.properties `pull.buildRoot`), e.g. when the repo disk is full.
val buildRoot = rootProject.file("local.properties").takeIf { it.exists() }
    ?.let { java.util.Properties().apply { it.inputStream().use(::load) }.getProperty("pull.buildRoot") }
if (buildRoot != null) {
    allprojects { layout.buildDirectory.set(file("$buildRoot/${project.path.replace(':', '/').ifEmpty { "/root" }}")) }
}
