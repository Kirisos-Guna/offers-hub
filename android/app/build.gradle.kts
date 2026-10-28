import groovy.json.JsonSlurper
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing stays out of git: android/keystore.properties points at the keystore file.
// Without it the release build still assembles, signed with the debug key.
val keystoreProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val releaseKeystore = keystoreProperties.getProperty("storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }

// Firebase settings come from app/google-services.json (downloaded from the Firebase console).
// The values the Google Services plugin would generate are written as resources directly, which
// lets Firebase start up on its own. Without the file the app falls back to the static feed.
data class FirebaseConfig(val appId: String, val apiKey: String, val projectId: String,
                          val senderId: String, val bucket: String)

val firebaseConfig: FirebaseConfig? = file("google-services.json").takeIf { it.exists() }?.let { f ->
    @Suppress("UNCHECKED_CAST")
    val json = JsonSlurper().parse(f) as Map<String, Any?>
    val project = json["project_info"] as Map<String, Any?>
    val client = (json["client"] as List<Map<String, Any?>>).first {
        val info = (it["client_info"] as Map<*, *>)["android_client_info"] as Map<*, *>
        info["package_name"] == "com.offershub.app"
    }
    FirebaseConfig(
        appId = (client["client_info"] as Map<*, *>)["mobilesdk_app_id"] as String,
        apiKey = ((client["api_key"] as List<Map<*, *>>).first())["current_key"] as String,
        projectId = project["project_id"] as String,
        senderId = project["project_number"] as String,
        bucket = project["storage_bucket"] as String,
    )
}

android {
    namespace = "com.offershub.app"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.offershub.app"
        minSdk = 26
        targetSdk = 37
        // -PversionCodeOverride / -PversionNameOverride build an older-looking copy, to test updates.
        versionCode = providers.gradleProperty("versionCodeOverride").orNull?.toInt() ?: 4
        versionName = providers.gradleProperty("versionNameOverride").orNull ?: "2.0.1"

        firebaseConfig?.let {
            resValue("string", "google_app_id", it.appId)
            resValue("string", "google_api_key", it.apiKey)
            resValue("string", "project_id", it.projectId)
            resValue("string", "gcm_defaultSenderId", it.senderId)
            resValue("string", "google_storage_bucket", it.bucket)
        }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles("proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        resValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(platform(libs.firebase.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    // Firebase pulls in an old Fragment; activity-result APIs need 1.3+.
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.messaging)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
