// Macrobenchmarks for Orbit (UX rubric 4.2, D10). Runs against the app's
// `benchmark` build type on a real device or emulator:
//
//   ./gradlew :benchmark:connectedBenchmarkAndroidTest
//
// StartupBenchmark reports cold-start time and frame timing with and without
// the Baseline Profile; BaselineProfileGenerator records a fresh profile to
// replace the hand-written app/src/main/baseline-prof.txt.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.orbit.benchmark"
    compileSdk = 35

    defaultConfig {
        minSdk = 31
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
}

androidComponents {
    beforeVariants(selector().all()) { variant ->
        variant.enable = variant.buildType == "benchmark"
    }
}
