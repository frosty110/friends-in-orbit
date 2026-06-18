import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
}

val keystoreProps =
    Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) load(f.inputStream())
    }

android {
    namespace = "app.orbit"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.orbit"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Room exports schema JSON so future migrations can be diffed in PRs.
        ksp {
            arg("room.schemaLocation", "$projectDir/schemas")
            arg("room.generateKotlin", "true")
        }
    }

    sourceSets["androidTest"].assets.srcDirs("$projectDir/schemas")

    signingConfigs {
        create("release") {
            storeFile = keystoreProps["storeFile"]?.let { file(it as String) }
            storePassword = keystoreProps["storePassword"] as? String
            keyAlias = keystoreProps["keyAlias"] as? String
            keyPassword = keystoreProps["keyPassword"] as? String
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
            // Instrumented-test (androidTest) coverage instrumentation is opt-in:
            // the emulator CI job passes -PinstrumentedCoverage so Kover folds
            // connectedDebugAndroidTest results into the debug report. Off by
            // default so the JVM-only unit job never tries to reach a device.
            enableAndroidTestCoverage = project.hasProperty("instrumentedCoverage")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
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
        // AGP 8.x defaults buildConfig to false. OrbitApp.onCreate needs
        // BuildConfig.DEBUG to gate Timber.plant(OrbitDebugTree()) — the
        // CALL-07 scrubber must only run in debug builds.
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // Robolectric needs Android resources on the unit-test classpath so
    // ApplicationProvider.getApplicationContext() can back a real Context.
    // SettingsViewModelTest uses Robolectric to build AppPrefs over a real
    // DataStore in the JUnit temp folder.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    lint {
        // AGP 8.7.2 + Kotlin 2.2.20: NonNullableMutableLiveDataDetector throws KaCallableMemberCall ClassCastException — broken until AGP 8.8.
        disable += "NullSafeMutableLiveData"
    }
}

// Route Hilt aggregation through KSP instead of Hilt's Gradle AggregateDepsTask.
// The Gradle task exposes a JavaPoet classpath conflict with KSP 2.x
// (ClassName.canonicalName NoSuchMethodError at hiltAggregateDepsDebug). The
// KSP-based path produces equivalent generated code for our scope (no @Module,
// no @InstallIn, no cross-module @EntryPoint aggregation) and sidesteps the
// plugin-classpath bug entirely.
hilt {
    enableAggregatingTask = false
}

// Unified coverage via Kover, bound to the JVM unit-test source set (src/test).
// Kover wires itself to testDebugUnitTest, so the per-variant report tasks
// produce ONE merged number across every unit test:
//   ./gradlew :app:koverHtmlReportDebug   -> build/reports/kover/htmlDebug (browse)
//   ./gradlew :app:koverXmlReportDebug    -> build/reports/kover (single value, CI/badges)
//   ./gradlew :app:koverVerifyDebug       -> enforce a threshold (none set yet — measure first)
//
// Instrumented tests (src/androidTest: DAO/migration/Compose) are NOT in this
// number; folding them in needs an emulator in CI and a Kover variant merge —
// deferred per the JVM-only decision.
kover {
    reports {
        filters {
            excludes {
                // Generated code must not dilute the denominator.
                annotatedBy(
                    "dagger.internal.DaggerGenerated",
                    "javax.annotation.processing.Generated",
                    // @Composable UI is exercised by androidTest (out of scope for the
                    // JVM number), so counting it here would only deflate coverage.
                    "androidx.compose.runtime.Composable"
                )
                classes(
                    "*Hilt_*",
                    "*_Factory",
                    "*_Factory\$*",
                    "*_MembersInjector",
                    "*_HiltModules*",
                    "hilt_aggregated_deps.*",
                    "dagger.hilt.internal.*",
                    // Room-generated DAO/database implementations.
                    "*_Impl",
                    "*ComposableSingletons*",
                    "*BuildConfig"
                )
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.reorderable)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.timber)
    implementation(libs.libphonenumber)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlin.test)
    // Robolectric provides ApplicationProvider for building a
    // real AppPrefs (Context-bound DataStore) in JUnit unit tests. DataStore auto-creates
    // its backing preferences file on first write; Robolectric supplies an in-memory
    // Application context that satisfies AppPrefs' @ApplicationContext param without
    // requiring an emulator.
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.androidx.work.testing)
    testImplementation(libs.androidx.work.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.kotlin.test)
    // Compose UI test rules for androidTest. ui-test-junit4
    // gives `createComposeRule()` + finder + assertion APIs; ui-test-manifest
    // (debugImplementation, per Compose docs) registers the placeholder
    // ComponentActivity used by `createComposeRule()` so we can compose
    // arbitrary content inside an instrumentation test without a host activity.
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
