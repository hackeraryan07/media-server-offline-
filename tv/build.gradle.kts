plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.google.devtools.ksp)
}

android {
  namespace = "com.example.tv"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.videostreamer.tv"
    minSdk = 24
    targetSdk = 36
    versionCode = 1
    versionName = "1.0"
    resourceConfigurations += setOf("en")
    ndk {
      abiFilters += listOf("arm64-v8a", "armeabi-v7a")
    }
  }

  signingConfigs {
    create("release") {
      val customPath = System.getenv("KEYSTORE_PATH")
      val keystoreFile = if (customPath != null && file(customPath).exists()) {
        file(customPath)
      } else if (file("${rootDir}/my-upload-key.jks").exists()) {
        file("${rootDir}/my-upload-key.jks")
      } else {
        file("${rootDir}/debug.keystore")
      }
      storeFile = keystoreFile
      storePassword = System.getenv("STORE_PASSWORD") ?: "android"
      keyAlias = System.getenv("KEY_ALIAS") ?: "androiddebugkey"
      keyPassword = System.getenv("KEY_PASSWORD") ?: "android"
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      signingConfig = signingConfigs.getByName("release")
    }
  }
  packaging {
    resources {
      excludes += listOf(
        "/META-INF/{AL2.0,LGPL2.1}",
        "/META-INF/INDEX.LIST",
        "/META-INF/DEPENDENCIES",
        "META-INF/*.version",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*",
        "DebugProbesKt.bin"
      )
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    buildConfig = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.leanback)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.okhttp)
  implementation(libs.nextlib.media3ext)

  implementation("androidx.appcompat:appcompat:1.6.1")
  implementation("com.google.android.material:material:1.11.0")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")

  implementation("com.github.bumptech.glide:glide:4.16.0")
  "ksp"("com.github.bumptech.glide:ksp:4.16.0")
  implementation("androidx.media3:media3-exoplayer:1.2.1")
  implementation("androidx.media3:media3-ui:1.2.1")
  implementation("androidx.media3:media3-ui-leanback:1.2.1")
}
