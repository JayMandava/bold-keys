import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ai.boldkeys"
    compileSdk = 36

    defaultConfig {
        applicationId = "ai.boldkeys"
        minSdk = 33
        targetSdk = 35
        versionCode = 9
        versionName = "0.6.1"
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

    // Release signing: a keystore.properties file (storeFile, storePassword, keyAlias, keyPassword) kept outside the repository, found through
    // the BOLD_KEYSTORE_PROPERTIES environment variable or ~/.bold-keys-keystore/keystore.properties. Without one, release builds use the debug key.
    val keystoreProps = Properties()
    val keystoreFile = (System.getenv("BOLD_KEYSTORE_PROPERTIES") ?: "${System.getProperty("user.home")}/.bold-keys-keystore/keystore.properties").let { file(it) }
    if (keystoreFile.exists()) keystoreFile.inputStream().use { stream -> keystoreProps.load(stream) }
    signingConfigs {
        if (keystoreProps.containsKey("storeFile")) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
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
