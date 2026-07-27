plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fastt.inflect"
    compileSdk = 35
    // Pinned: the nix SDK is read-only, so AGP must not try to auto-install a different one.
    buildToolsVersion = "35.0.0"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.fastt.inflect"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ONNX Runtime ships native libs for four ABIs; keeping only these two roughly
        // halves the APK. Stage 3 (espeak-ng via CMake/NDK) adds `externalNativeBuild` here.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    androidResources {
        // Keep the ONNX graphs uncompressed so ORT can map them straight out of the APK
        // instead of inflating 38 MB into the heap on every cold start.
        noCompress += "onnx"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("test").java.srcDirs("src/test/kotlin")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // org.json ships with Android but is a stub in JVM unit tests; supply a real one there.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
