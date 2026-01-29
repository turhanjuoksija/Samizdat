plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.example.samizdat"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.samizdat"
        minSdk = 26 // Supporting back to Android 8.0
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

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
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14" // Matches Kotlin 1.9.24
    }
    packaging {
        jniLibs {
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

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.zxing:core:3.5.2")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("androidx.compose.material:material-icons-extended")
    
    // Tor (Kotlin 1.9 Compatible - oldest 2.x version)
    implementation("io.matthewnelson.kmp-tor:runtime:2.0.0")
    implementation("io.matthewnelson.kmp-tor:runtime-service:2.0.0")
    implementation("io.matthewnelson.kmp-tor:resource-exec-tor:408.13.0")

    // Use Room 2.7 to support Kotlin 2.0 metadata from libraries
    val room_version = "2.7.0-alpha12"
    implementation("androidx.room:room-runtime:$room_version")
    implementation("androidx.room:room-ktx:$room_version")
    kapt("androidx.room:room-compiler:$room_version")
    
    // Fix for Room compiler vs Kotlin 2.0 metadata (from kmp-tor)
    // Fix for Room compiler vs Kotlin 2.0 metadata (from kmp-tor)
    kapt("org.jetbrains.kotlinx:kotlinx-metadata-jvm:0.9.0")
    
    // Google Play Services (Location)
    implementation("com.google.android.gms:play-services-location:21.2.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    
    testImplementation("junit:junit:4.13.2")
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("1.9.24")
        }
        if (requested.group == "org.jetbrains.kotlinx" && requested.name.startsWith("kotlinx-coroutines")) {
            useVersion("1.8.1") // Last version compatible with Kotlin 1.9
        }
    }
}
