import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

val signingPropsFile = file("${System.getProperty("user.home")}/.aw-android-signing/signing.properties")
val signingProps = Properties().apply {
  if (signingPropsFile.exists()) {
    val cleanContent = signingPropsFile.readText(Charsets.UTF_8).removePrefix("\uFEFF")
    load(cleanContent.reader())
  }
}

val releaseStoreFilePath = System.getenv("KEYSTORE_FILE") ?: signingProps.getProperty("storeFile")
val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD") ?: signingProps.getProperty("storePassword")
val releaseKeyAlias = System.getenv("KEY_ALIAS") ?: signingProps.getProperty("keyAlias")
val releaseKeyPassword = System.getenv("KEY_PASSWORD") ?: signingProps.getProperty("keyPassword")
val hasReleaseSigning = !releaseStoreFilePath.isNullOrBlank() &&
  file(releaseStoreFilePath).exists() &&
  !releaseStorePassword.isNullOrBlank() &&
  !releaseKeyAlias.isNullOrBlank() &&
  !releaseKeyPassword.isNullOrBlank()

android {
  namespace = "com.carloj005.awanime"
  compileSdk = 36
  buildToolsVersion = "36.1.0"

  defaultConfig {
    applicationId = "com.carloj005.awanime"
    minSdk = 26
    targetSdk = 36
    versionCode = 6
    versionName = "1.2.3"
    buildConfigField("String", "GITHUB_REPO", "\"Carloj005/aw-android\"")
  }

  signingConfigs {
    if (hasReleaseSigning) {
      create("release") {
        storeFile = file(releaseStoreFilePath!!)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = if (hasReleaseSigning) {
        signingConfigs.getByName("release")
      } else {
        signingConfigs.getByName("debug")
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  buildFeatures {
    compose = true
    aidl = false
    buildConfig = true
    shaders = false
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  jvmToolchain(21)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.activity.compose)

  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons)
  debugImplementation(libs.androidx.compose.ui.tooling)

  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.okhttp)
  implementation(libs.coil.compose)

  implementation(libs.media3.exoplayer)
  implementation(libs.media3.exoplayer.hls)
  implementation(libs.media3.datasource.okhttp)
  implementation(libs.media3.ui)
  implementation(libs.androidx.work.runtime.ktx)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
