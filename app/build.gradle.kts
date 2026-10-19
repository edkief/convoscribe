plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
}

// sherpa-onnx 1.13.8 native + Kotlin API. Both are built by
// `scripts/build-sherpa-onnx.sh` into these paths and are .gitignore'd.
val sherpaJar = file("libs/sherpa-onnx-classes-1.13.8.jar")
val sherpaJni = file("src/main/jniLibs/arm64-v8a/libsherpa-onnx-jni.so")
check(sherpaJar.exists() && sherpaJni.exists()) {
    "Missing sherpa-onnx native build - run bash scripts/build-sherpa-onnx.sh"
}

android {
    namespace = "com.convoscribe"
    compileSdk = 35
    // Matches setup-toolchain.sh (and so the release builder image); without
    // it AGP falls back to its default 34.0.0 and downloads it on every CI run.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.convoscribe"
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.0"
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Debug builds normally sign with the per-machine
    // ~/.android/debug.keystore, so an APK built in the container and one
    // built on the host have different signatures and `adb install -r`
    // fails with INSTALL_FAILED_UPDATE_INCOMPATIBLE. When
    // app/debug.keystore exists, every machine signs with it instead.
    // It uses the standard debug-keystore credentials.
    val sharedDebugKeystore = file("debug.keystore")
    if (sharedDebugKeystore.exists()) {
        signingConfigs {
            getByName("debug") {
                storeFile = sharedDebugKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            // Until a dedicated release key exists (issue #16), release
            // builds (scripts/release.sh) are signed with the shared debug
            // keystore so they upgrade in place over debug installs.
            // Without app/debug.keystore the release APK is left unsigned.
            if (sharedDebugKeystore.exists()) {
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
    }

    // Room's exported schemas, so MigrationTest can build each old
    // version of the database. Robolectric only reads the variant's
    // merged assets (not the `test` source set's), so they are debug
    // assets: a few KB in debug APKs, nothing in release.
    sourceSets["debug"].assets.srcDir("$projectDir/schemas")

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Return default values (0/null/false) for every
        // `android.util.*` method that is not mocked by Robolectric.
        // Without this, every diagnostic `Log.i(TAG, ...)` /
        // `Log.w(TAG, ...)` call from production code throws
        // `RuntimeException("Method i in android.util.Log not
        // mocked")` in pure-JUnit tests where Robolectric is not
        // active (e.g. `RecordingControllerTest`,
        // `ModelDownloaderTest`).
        unitTests.isReturnDefaultValues = true
        // Serialise unit tests: many ViewModel suites call
        // `Dispatchers.setMain` / `resetMain` on a static
        // dispatcher and the `kotlinx-coroutines-test`
        // `TestMainDispatcher` throws when two test classes race
        // to swap it. With forks = 1 we still run inside a worker
        // pool but only one fork at a time, which is well within
        // budget for our test-suite size.
        unitTests.all { test ->
            test.maxParallelForks = 1
            // `-Proborazzi.test.record=true` makes the screenshot tests
            // write PNGs to build/outputs/roborazzi; without it they only
            // compose each screen (no image capture, no golden files).
            val record = project.findProperty("roborazzi.test.record")?.toString() ?: "false"
            test.systemProperty("roborazzi.test.record", record)
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
        // User-visible text belongs in strings.xml (issue #4).
        error += "HardcodedText"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(files(sherpaJar))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.security.crypto)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.room.testing)
    // Screenshot tests (ui/screenshots): render screen bodies on the JVM.
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
}
