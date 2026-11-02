// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("com.android.library") version "9.4.1" apply false
    id("com.android.legacy-kapt") version "9.4.1" apply false
    // AGP 9 has built-in Kotlin support. Declaring KGP here (apply false) only pins the
    // Kotlin compiler version that AGP uses; the plugin is NOT applied in app/.
    // Pinned to 2.3.x: Room 2.8.5 (kapt) bundles a shaded kotlin-metadata that only reads
    // metadata <= 2.3.0, so Kotlin 2.4 fails in kaptDebugKotlin. Revisit after a Room update or KSP.
    id("org.jetbrains.kotlin.android") version "2.3.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false
}

// Yes, this file is required! 
// It is the top-level build file that defines the Android and Kotlin plugins 
// and their versions for the entire project. Without it, the app/ module 
// won't know where to find the 'com.android.application' plugin.
