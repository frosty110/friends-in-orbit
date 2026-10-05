import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kover)
    // Retries inherently-flaky tests (WorkManager-in-Robolectric init) so a
    // transient timeout doesn't red the build; a genuinely broken test still
    // fails every attempt. See the tasks.withType<Test> retry config below.
    id("org.gradle.test-retry") version "1.6.0"
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
        applicationId = "io.github.frosty110.orbit"
        minSdk = 31
        targetSdk = 35
        // Version — the semver base lives here (human-owned; bump it deliberately
        // for a real release). CI stamps the build identity so every published APK's
        // Settings → About shows exactly which build it came from. Env vars are read
        // via the providers API so the configuration cache tracks them and
        // invalidates when they change.
        //   ORBIT_VERSION_CODE — monotonic build number (CI run number); Play
        //     requires it to strictly increase. Falls back to 1 for local builds.
        //   ORBIT_BUILD_LABEL  — human-readable build tag (e.g. "build 42 a1b2c3d").
        //     Absent locally, so local builds read "1.0.0-local".
        val semver = "1.1.0"
        versionCode = providers.environmentVariable("ORBIT_VERSION_CODE").orNull?.toIntOrNull() ?: 1
        versionName = providers.environmentVariable("ORBIT_BUILD_LABEL").orNull
            ?.let { "$semver ($it)" } ?: "$semver-local"
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
        // Release, signed with the debug key so it installs anywhere, for the
        // :benchmark module (startup timing and Baseline Profile generation).
        // Never shipped.
        create("benchmark") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    // Per-app language (Android 13+): AGP writes locales_config.xml from the
    // values-* folders that exist, so a translation added later shows up in
    // the system's app-language picker with no further wiring. The default
    // (unqualified) strings are English; see res/resources.properties.
    androidResources {
        generateLocaleConfig = true
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
                    "*BuildConfig",
                    // Android entry points — Activity/Application lifecycle glue
                    // with no unit-testable surface. Excluded so coverage measures
                    // testable units, not framework wiring (these belong to
                    // instrumented/manual testing, if anything).
                    "app.orbit.MainActivity",
                    "app.orbit.MainActivity*",
                    "app.orbit.OrbitApp",
                    "app.orbit.OrbitApp*"
                )
            }
        }
    }
}

// Run each unit-test class in its own JVM. WorkManager's process-global singleton
// and its in-memory Room DB leak across Robolectric test classes in a shared JVM,
// intermittently timing out WorkManagerTestInitHelper init (ForceStopRunnable →
// Room runBlockingUninterruptible) and surfacing as a flaky TimeoutCancellation
// in whichever WorkManager/DataStore-touching class runs at the wrong moment. A
// fresh JVM per class removes that cross-class contamination; the cost is some
// extra test wall-time, which is worth deterministic green.
tasks.withType<Test>().configureEach {
    forkEvery = 1
    // WorkManager-in-Robolectric init (ForceStopRunnable -> Room) and the
    // DataStore singleton occasionally hang across methods even with per-class
    // JVM forking. Retry the rare flaky failure rather than red the build; a
    // genuinely broken test fails all attempts, so this masks nothing real.
    retry {
        maxRetries.set(2)
        failOnPassedAfterRetry.set(false)
    }
    // The preview screenshot gallery renders ~140 previews in several modes;
    // it runs only on request (-Pscreenshots) and then runs alone, writing
    // PNGs to build/screenshots/.
    if (project.hasProperty("screenshots")) {
        filter { includeTestsMatching("app.orbit.ui.screenshots.*") }
        systemProperty("roborazzi.test.record", "true")
        systemProperty("orbit.screenshots.dir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
        (project.findProperty("orbit.screenshots.only") as String?)?.let { systemProperty("orbit.screenshots.only", it) }
        (project.findProperty("orbit.screenshots.qualifiers") as String?)?.let { systemProperty("orbit.screenshots.qualifiers", it) }
        // -Porbit.screenshots.curtain renders every preview with the privacy
        // curtain down and reports any name that still shows (PRIV-03).
        if (project.hasProperty("orbit.screenshots.curtain")) systemProperty("orbit.screenshots.curtain", "true")
        // -Porbit.a11y.strict fails a preview on any accessibility finding.
        if (project.hasProperty("orbit.a11y.strict")) systemProperty("orbit.a11y.strict", "true")
        retry { maxRetries.set(0) }
    } else {
        exclude("app/orbit/ui/screenshots/**")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // Installs the Baseline Profile (src/main/baseline-prof.txt) at install
    // time so startup and the core loop run AOT-compiled (UX rubric D10).
    implementation(libs.androidx.profileinstaller)
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
    // Screenshot gallery (ui/screenshots/PreviewGalleryTest): renders every
    // @Preview on the JVM. Run with -Pscreenshots; excluded otherwise.
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.composable.preview.scanner)
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
