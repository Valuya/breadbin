import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Upload key details, from `keystore.properties` at the repository root or from the environment on
 * a machine that has no business holding that file. Neither is in the repository, so a clone of it
 * still builds for anyone — the release simply comes out unsigned.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use(::load)
}

fun uploadKey(property: String, variable: String): String? =
    keystoreProperties.getProperty(property) ?: System.getenv(variable)

android {
    namespace = "be.valuya.breadbin"
    compileSdk = 37

    defaultConfig {
        applicationId = "be.valuya.breadbin"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("upload") {
            val store = uploadKey("breadbinStoreFile", "BREADBIN_STORE_FILE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = uploadKey("breadbinStorePassword", "BREADBIN_STORE_PASSWORD")
                keyAlias = uploadKey("breadbinKeyAlias", "BREADBIN_KEY_ALIAS")
                keyPassword = uploadKey("breadbinKeyPassword", "BREADBIN_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned rather than debug-signed when no key is configured: an unsigned artefact is
            // obviously not shippable, where a debug-signed one looks fine until Play rejects it.
            signingConfig = signingConfigs.getByName("upload").takeIf { it.storeFile != null }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
    }

    lint {
        // Compiled against 37 and targeted at 36 on purpose: 36 is what Play asks of a new app, and
        // opting into a new Android's behaviour changes is a decision to make with a device to test
        // it on, not a warning to silence by bumping a number.
        disable += "OldTargetApi"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(project(":engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    // Android provides org.json at runtime but stubs it in unit tests, so the real thing has to be
    // on the test classpath for the response parsing to be testable at all.
    testImplementation("org.json:json:20240303")
}
