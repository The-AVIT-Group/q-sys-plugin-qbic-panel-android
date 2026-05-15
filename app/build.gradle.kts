plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

// Redirect build output outside OneDrive to avoid Windows Defender file-lock issues
layout.buildDirectory.set(file("C:/Temp/qbic-android-build/app"))

android {
  namespace = "au.com.theavitgroup.qbiccontrol"
  compileSdk = 34
  buildToolsVersion = "34.0.0"

  defaultConfig {
    applicationId = "au.com.theavitgroup.qbiccontrol"
    minSdk = 29        // Android 10 — covers all current TD-1070 firmware
    targetSdk = 34
    versionCode = 1
    versionName = "1.0"
    ndk { abiFilters += "arm64-v8a" }  // TD-1070 is arm64; drop x86 native libs
  }

  buildTypes {
    debug {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
    release {
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    viewBinding = true
    buildConfig = true
  }
}

dependencies {
  // WebSocket server — pure Java, no native deps, works on Android
  implementation("org.java-websocket:Java-WebSocket:1.5.4")
}
