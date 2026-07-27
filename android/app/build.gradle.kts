import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.github.aljge.tensorspeak"
    compileSdk = 35
    // Pinned: the nix SDK is read-only, so AGP must not try to auto-install a different one.
    buildToolsVersion = "35.0.0"
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.github.aljge.tensorspeak"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ONNX Runtime ships native libs for four ABIs; keeping only these two roughly
        // halves the APK. espeak-ng is compiled for the same pair.
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                // No C++ in the espeak-ng subset we build (speechPlayer is excluded), so we
                // can skip the STL entirely.
                arguments += listOf("-DANDROID_STL=none")
            }
        }
    }

    // Optional release signing: android/keystore.properties or TENSORSPEAK_* env vars.
    // Without them, assembleRelease still builds (unsigned / debug-signed by AGP default).
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { keystoreProperties.load(it) }
    }
    fun signingProp(name: String, envName: String): String? =
        keystoreProperties.getProperty(name)?.takeIf { it.isNotBlank() }
            ?: System.getenv(envName)?.takeIf { it.isNotBlank() }

    val storeFilePath = signingProp("storeFile", "TENSORSPEAK_STORE_FILE")
    val storePassword = signingProp("storePassword", "TENSORSPEAK_STORE_PASSWORD")
    val keyAlias = signingProp("keyAlias", "TENSORSPEAK_KEY_ALIAS")
    val keyPassword = signingProp("keyPassword", "TENSORSPEAK_KEY_PASSWORD")
    val releaseSigningConfigured =
        !storeFilePath.isNullOrBlank() &&
            !storePassword.isNullOrBlank() &&
            !keyAlias.isNullOrBlank() &&
            !keyPassword.isNullOrBlank()

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(storeFilePath!!)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
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
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        getByName("androidTest").java.srcDirs("src/androidTest/kotlin")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        // PhonemeTokenizer logs dropped phonemes through android.util.Log, which is a stub
        // that throws in JVM unit tests unless the stubs are told to return defaults.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // 1.27.0, not 1.20.0: the 1.20 AAR ships libonnxruntime4j_jni.so with 4 KB LOAD
    // segments, which fails the 16 KB ELF check on Android 15+ devices. 1.27 aligns both
    // libonnxruntime.so and the JNI shim to 0x4000.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // org.json ships with Android but is a stub in JVM unit tests; supply a real one there.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
