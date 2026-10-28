plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nativemorph.engine"
    compileSdk = 35
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.nativemorph.engine"
        minSdk = 24
        targetSdk = 35
        versionCode = 8
        versionName = "1.3.3"
        ndk {
            // Samsung Galaxy Tab A9+ (SM-X210/X216) is arm64-v8a.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore/nativemorph.jks")
            storePassword = "nativemorph"
            keyAlias = "nativemorph"
            keyPassword = "nativemorph"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            pickFirsts += listOf("**/libc++_shared.so")
        }
        resources {
            excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/DEPENDENCIES")
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Godot 4.7.2 stable runtime, prebuilt by the Godot project (MIT license).
    implementation(files("../libs/godot-lib-4.7.2-release.aar"))
    // Godot 3.6.2 stable runtime (relocated java layer; the native side is
    // jniLibs/libgodot3x_droid.so + the nm_godot3_jni registration shim).
    implementation(project(":godot3-lib"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.annotation:annotation:1.8.1")
}
