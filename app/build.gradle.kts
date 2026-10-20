plugins {
    id("com.android.application")
}

android {
    namespace = "com.segar.livetv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.segar.livetv"
        minSdk = 23
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sideloaded onto the user's own TV; debug key keeps installs simple.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
        resValues = false
    }

    packaging {
        resources.excludes += setOf("META-INF/**", "kotlin/**", "**.kotlin_builtins", "DebugProbesKt.bin")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

dependencies {
    // The one library allowed by docs/PLAN.md: ExoPlayer core + HLS. No media3-ui.
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
}
