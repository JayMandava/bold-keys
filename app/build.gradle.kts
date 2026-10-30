import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI must opt in to unsigned output. A typo must not accidentally enable signing or read secrets.
val unsignedRelease = when (providers.gradleProperty("unsignedRelease").orElse("false").get()) {
    "true" -> true
    "false" -> false
    else -> throw GradleException("unsignedRelease must be exactly true or false.")
}

val releaseSigningProperties = Properties()
var releaseSigningProblem: String? = null
if (!unsignedRelease) {
    // Keep the existing local signing configuration, but never read it during an unsigned CI build.
    val propertiesPath = providers.environmentVariable("BOLD_KEYSTORE_PROPERTIES").orNull
        ?: "${System.getProperty("user.home")}/.bold-keys-keystore/keystore.properties"
    val propertiesFile = file(propertiesPath)
    releaseSigningProblem = when {
        !propertiesFile.isFile || !propertiesFile.canRead() -> "Release signing properties are missing or unreadable."
        else -> try {
            propertiesFile.inputStream().use(releaseSigningProperties::load)
            val missing = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
                .filter { releaseSigningProperties.getProperty(it).isNullOrBlank() }
            when {
                missing.isNotEmpty() -> "Release signing properties are incomplete: ${missing.joinToString()}."
                releaseSigningProperties.getProperty("keyAlias").equals("androiddebugkey", ignoreCase = true) ->
                    "The Android debug key must not be used for a release."
                !file(releaseSigningProperties.getProperty("storeFile")).let { it.isFile && it.canRead() } ->
                    "The configured release keystore is missing or unreadable."
                else -> null
            }
        } catch (_: Exception) {
            // Do not include exception text: it can contain signing configuration values.
            "Release signing properties could not be loaded."
        }
    }
}

val verifyReleaseSigningMode = tasks.register("verifyReleaseSigningMode") {
    group = "verification"
    description = "Require local release signing credentials or explicit secret-free unsigned mode."
    val problem = releaseSigningProblem
    doLast {
        if (!unsignedRelease && problem != null) {
            throw GradleException(
                "$problem Configure BOLD_KEYSTORE_PROPERTIES or ~/.bold-keys-keystore/keystore.properties " +
                    "with the production signing configuration. For a secret-free CI artifact, pass " +
                    "-PunsignedRelease=true; that artifact must be signed separately before distribution. " +
                    "Release builds never fall back to the debug key.",
            )
        }
    }
}

// Gate artifact-producing entry points, not compilation, unit tests or lint. The packaging dependency
// also protects direct package invocations and checks even when previous outputs are up to date.
tasks.configureEach {
    if (name in setOf("packageRelease", "packageReleaseBundle", "signReleaseBundle", "assembleRelease", "bundleRelease", "installRelease")) {
        dependsOn(verifyReleaseSigningMode)
    }
}

android {
    namespace = "ai.boldkeys"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.boldkeys"
        minSdk = 33
        targetSdk = 36
        versionCode = 10
        versionName = "0.6.2"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // Release even for debug variants: a debug-built llama.cpp is unusably slow.
                arguments += listOf(
                    "-DCMAKE_BUILD_TYPE=Release", "-DANDROID_STL=c++_static", "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DCMAKE_C_FLAGS=-march=armv8.2-a+dotprod", "-DCMAKE_CXX_FLAGS=-march=armv8.2-a+dotprod",
                )
            }
        }
    }
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }

    signingConfigs {
        if (!unsignedRelease && releaseSigningProblem == null) {
            create("release") {
                storeFile = file(releaseSigningProperties.getProperty("storeFile"))
                storePassword = releaseSigningProperties.getProperty("storePassword")
                keyAlias = releaseSigningProperties.getProperty("keyAlias")
                keyPassword = releaseSigningProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (unsignedRelease) null else signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Keep JDK 17 CI usable. API 36 Robolectric runs require JDK 21 and an explicit opt-in.
            all {
                it.systemProperty("robolectric.enabledSdks", providers.gradleProperty("robolectricSdks").orElse("33").get())
                // Robolectric's API 36 FileDescriptor interceptor uses this JDK-internal bridge.
                it.jvmArgs("--add-opens=java.base/jdk.internal.access=ALL-UNNAMED")
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3:1.5.0-alpha04")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
}
