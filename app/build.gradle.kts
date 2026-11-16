import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing lives outside the repository. Point `mifi.signing` (in
// ~/.gradle/gradle.properties) at a properties file with storeFile, storePassword,
// keyAlias and keyPassword. Without it, builds use the normal debug key.
val signingProps: Properties? = providers.gradleProperty("mifi.signing").orNull
    ?.let { file(it) }?.takeIf { it.exists() }
    ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

// The public half of the key that signs web-screen bundles (see tools/release).
// Bundles that don't verify against it are never loaded.
val webBundleKey: String = providers.gradleProperty("mifi.webKey").orNull
    ?: rootProject.file("keys/web-bundle.pub").takeIf { it.exists() }?.readText()?.trim()
    ?: ""

android {
    namespace = "com.hebronmimam.mifi"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.hebronmimam.mifi"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        // Where the app looks for new versions of itself and of its screens.
        buildConfigField("String", "UPDATE_REPO", "\"hebronmimam/mifi-app\"")
        buildConfigField("String", "WEB_BUNDLE_KEY", "\"$webBundleKey\"")
    }

    signingConfigs {
        if (signingProps != null) {
            create("owner") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("debug") {
            // Same key for debug and release, so either installs over the other.
            if (signingProps != null) signingConfig = signingConfigs.getByName("owner")
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingProps != null) signingConfigs.getByName("owner")
                            else signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
