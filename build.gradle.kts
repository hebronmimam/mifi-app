plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
}

// Optional: keep build output outside the source tree (useful on Windows, where deep
// paths can trip native tools). Set `mifi.buildRoot` in ~/.gradle/gradle.properties.
val buildRoot = providers.gradleProperty("mifi.buildRoot").orNull
if (buildRoot != null) {
    allprojects {
        layout.buildDirectory.set(file("$buildRoot/${rootProject.name}/${project.name}"))
    }
}
