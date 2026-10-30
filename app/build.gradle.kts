import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.paparazzi)
}

// Release signing is read from keystore.properties (not committed) or from env vars in CI.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = keystoreProps.getProperty(key) ?: System.getenv(env)

android {
    namespace = "io.github.dockside"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.dockside"
        minSdk = 27
        targetSdk = 35
        // Bump both for every release: versionCode is the build number shown in Settings.
        versionCode = 11
        versionName = "1.9.0"
        buildConfigField(
            "String", "BUILD_DATE",
            "\"" + LocalDate.now(ZoneOffset.UTC).toString() + "\"",
        )
    }

    signingConfigs {
        val storeFilePath = signingValue("storeFile", "DOCKSIDE_KEYSTORE")
        if (storeFilePath != null) {
            create("release") {
                storeFile = rootProject.file(storeFilePath)
                storePassword = signingValue("storePassword", "DOCKSIDE_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "DOCKSIDE_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "DOCKSIDE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // False positives: our producers assign `value` inside loops / frame callbacks.
        disable += "ProduceStateDoesNotAssignValue"
        // Versions are pinned on purpose; upgrades are done deliberately.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit)
}
