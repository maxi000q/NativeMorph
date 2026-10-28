plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

// The Godot 3.6.2 Android java library (MIT), relocated from
// org.godotengine.godot to org.nativemorph.god3x so it can coexist with the
// Godot 4.7.2 AAR in one APK. Source: platform/android/java/lib of the
// 3.6.2-stable tag (tools/relocate_godot3.py performs the relocation).
android {
    namespace = "org.nativemorph.god3x"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        manifestPlaceholders["godotLibraryVersion"] = "3.6.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        aidl = true
        buildConfig = true
    }

    // Godot's upstream layout: java/kotlin under src/, resources under res/,
    // aidl under aidl/ (not the AGP src/main/* defaults).
    sourceSets {
        getByName("main") {
            java.srcDirs("src")
            res.srcDirs("res")
            aidl.srcDirs("aidl")
        }
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.activity:activity:1.9.3")
    implementation("androidx.annotation:annotation:1.8.1")
}
