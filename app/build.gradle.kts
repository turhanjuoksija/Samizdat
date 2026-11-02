import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    // AGP 9 built-in Kotlin: no org.jetbrains.kotlin.android here. kapt comes from AGP's legacy-kapt.
    id("com.android.legacy-kapt")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.example.samizdat"
    compileSdk = 37 // Required by tor-android 0.4.9.12+ (minCompileSdk=37). targetSdk stays 34.

    defaultConfig {
        applicationId = "com.example.samizdat"
        minSdk = 26 // Supporting back to Android 8.0
        targetSdk = 34
        versionCode = 18 // BRouter offline routing, Tor segment download & Tor-only map tiles
        versionName = "0.7-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        ndk {
            // Support common architectures to prevent bloat and packaging conflicts
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign with debug key for alpha testing (produces installable APK)
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true // Needed for BuildConfig.VERSION_NAME (User-Agent)
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            pickFirsts += "**/libtor.so"
            pickFirsts += "**/libevent.so"
            pickFirsts += "**/libtorexec.so"
            
            // Prevent stripping of Tor binaries which could break execution
            keepDebugSymbols += "**/libtor.so"
            keepDebugSymbols += "**/libevent.so"
            keepDebugSymbols += "**/libtorexec.so"
        }
        resources {
            pickFirsts += "META-INF/kotlin-stdlib-jdk8.kotlin_module"
            pickFirsts += "META-INF/kotlin-stdlib-jdk7.kotlin_module"
            pickFirsts += "META-INF/kotlin-stdlib.kotlin_module"
            
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.compose.material:material-icons-extended")
    
    // WebSockets (Nostr Relays)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // Cryptography (Nostr Schnorr Signatures) — MUST use -jni-android, NOT -jvm!
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.14.0")

    // Tor (Kotlin 1.9 Compatible - oldest 2.x version)
    implementation("io.matthewnelson.kmp-tor:runtime:2.0.0")
    implementation("io.matthewnelson.kmp-tor:runtime-service:2.0.0")
    implementation("io.matthewnelson.kmp-tor:resource-exec-tor:408.13.0")

    // Room (kapt for now; KSP migration later)
    val room_version = "2.8.5"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")
    
    // Google Play Services (Location)
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")

    // Offline routing (BRouter). Pinned to v1.7.9: v1.7.10 fails to build on JitPack.
    implementation("com.github.abrensch.brouter:brouter-core:v1.7.9")
    // Runtime-scoped in brouter-core's POM, but its types (OsmNode, OsmPos) are needed at compile time
    implementation("com.github.abrensch.brouter:brouter-mapaccess:v1.7.9")
    
    testImplementation("junit:junit:4.13.2")
}
