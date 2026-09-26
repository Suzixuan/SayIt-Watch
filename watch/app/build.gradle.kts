plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.sayit.watch"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sayit.watch.debug"
        // Development candidate, not a release. 1C-D-04@R7: this connection-recovery and
        // explicit-switch candidate is distinguished from the previous dev.1 build.
        // 1C-D-04@R10: the NSD service-type form the platform's resolve callback really reports is
        // now accepted, so the APK is bumped to dev.3/code 7 and PM can read the version back to
        // confirm the tested build on the device.
        // R11: distinguish the switch-cancel status repair during device acceptance.
        versionCode = 9
        versionName = "0.3.0-dev.5"
        minSdk = 30
        targetSdk = 34
    }

    buildTypes {
        debug {
            // Cleartext is permitted only in the debug overlay manifest
            // (src/debug/AndroidManifest.xml). Nothing here re-enables it.
        }
        release {
            isMinifyEnabled = false
            // Release runtime code and release manifest deny cleartext.
            // No usable HTTP sender exists in release code paths.
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
    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
