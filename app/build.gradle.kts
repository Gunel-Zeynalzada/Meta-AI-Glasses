// App module. Versions match Meta's official CameraAccess sample at SDK release 1.1.0
// (github.com/meta/meta-wearables-dat-android, tag "Release 1.1.0").
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.jetbrains.kotlin.android)
  alias(libs.plugins.compose.compiler)
}

// Optional credentials from local.properties (never commit that file).
// Developer Mode in the Meta AI app: leave them unset -> "0" is used.
val localProperties =
    Properties().apply {
      val file = rootDir.resolve("local.properties")
      if (file.exists()) file.inputStream().use { load(it) }
    }

android {
  namespace = "az.ada.glassesav"
  compileSdk = 36

  buildFeatures {
    buildConfig = true
    compose = true
  }

  defaultConfig {
    applicationId = "az.ada.glassesav"
    // 31 = Android 12: needed for AudioManager.setCommunicationDevice() used by the HFP route.
    minSdk = 31
    targetSdk = 36
    versionCode = 1
    versionName = "0.1.0"

    // Meta Wearables Device Access Toolkit credentials.
    // Developer Mode: both "0". Release channel / production: values from Wearables Developer Center.
    manifestPlaceholders["mwdat_application_id"] =
        localProperties.getProperty("mwdat_application_id", "0")
    manifestPlaceholders["mwdat_client_token"] =
        localProperties.getProperty("mwdat_client_token", "0")
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.material3)
  // Meta Wearables Device Access Toolkit
  implementation(libs.mwdat.core)
  implementation(libs.mwdat.camera)
}
