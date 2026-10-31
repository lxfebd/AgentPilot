plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Open-source build ships without a bundled official server: users self-host /
// connect to their own instance from the login screen.
//
// Two build intents share this project, and the release variant must stay
// server-free so a public APK never embeds a private endpoint:
//   - private/self-use  -> debug variant, may pin the operator's own server
//   - public release    -> release variant, ships with no default server at all
//
// The pinned address for a private build comes from an explicit Gradle property
// only. `local.properties` is machine-local and gitignored, so reading it here
// would bake whatever this workstation happens to hold into any release built on
// it. Pass it explicitly instead, e.g.
//   ./gradlew assembleDebug -PagentPilot.officialServerUrl=https://host
//
// A public release deliberately ignores both sources and stays empty.
val pinnedServerUrl = providers.gradleProperty("agentPilot.officialServerUrl")
    .orElse(providers.gradleProperty("agentPilot.serverUrl"))
    .orElse("")
    .get()

fun String.asBuildConfigString(): String = "\"" + replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("\n", "\\n")
    .replace("\r", "\\r") + "\""

// Release signing is opt-in per machine: the release keystore and its passwords
// are passed as Gradle properties (never committed). Without them the release
// variant builds unsigned, which keeps CI green and secrets out of the repo.
//   ./gradlew assembleRelease \
//     -PagentPilot.releaseStoreFile=/abs/path/agentpilot.jks \
//     -PagentPilot.releaseStorePassword=... \
//     -PagentPilot.releaseKeyAlias=agentpilot \
//     -PagentPilot.releaseKeyPassword=...
val releaseStorePath = (project.findProperty("agentPilot.releaseStoreFile") as String?)
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

android {
    namespace = "com.agentpilot.app"
    compileSdk = 36

    // Opt-in release signing: the keystore and its passwords arrive as Gradle
    // properties (never committed). Without them the release variant builds
    // unsigned, which keeps CI green and secrets out of the repo.
    if (releaseStorePath != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = providers.gradleProperty("agentPilot.releaseStorePassword").get()
                keyAlias = providers.gradleProperty("agentPilot.releaseKeyAlias").get()
                keyPassword = providers.gradleProperty("agentPilot.releaseKeyPassword").get()
            }
        }
    }

    defaultConfig {
        applicationId = "com.agentpilot.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 27
        versionName = "2.4.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            // Private builds: the pinned server is opt-in per invocation, so a
            // workstation's gitignored local.properties can never leak into a
            // shared artifact.
            buildConfigField("String", "OFFICIAL_SERVER_URL", pinnedServerUrl.asBuildConfigString())
        }
        release {
            // Shrink + obfuscate the release APK; proguard-rules.pro keeps what
            // reflection needs. Debug stays unminified.
            isMinifyEnabled = true
            // Bound only when the signing keystore properties are present; a
            // missing config leaves the APK unsigned instead of failing, so CI
            // can still build releases without any secret.
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Public release: no server is bundled. Users enter their own address
            // in the login flow, and the official-cloud entry stays disabled.
            buildConfigField("String", "OFFICIAL_SERVER_URL", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(platform(libs.sora.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.mlkit.vision)
    implementation(libs.androidx.camera.view)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window.size)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.compose.shimmer)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.task.list.items)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.lucide.icons)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.okhttp)
    implementation(libs.sora.editor)
    implementation(libs.sora.language.textmate)
    implementation(libs.sora.oniguruma.native)
    implementation(libs.telephoto.zoomable.image.coil3)
    implementation(libs.termux.terminal.view)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    // Compose UI test artifacts resolve versions from the BOM, and the
    // androidTestImplementation configuration does not inherit the platform
    // constraint from implementation() - without this, lintDebug fails on
    // an empty ui-test-junit4 version during generateDebugAndroidTestLintModel.
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.named("check") {
    dependsOn(rootProject.tasks.named("checkLegacyRoutes"))
}
