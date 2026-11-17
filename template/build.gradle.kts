plugins {
    id("com.android.application")
}

android {
    // Java classes live in com.webapper.template; the applicationId is a placeholder that the
    // installer rewrites (inside the binary AndroidManifest.xml) for every generated app.
    namespace = "com.webapper.template"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.webapper.gen.t0000000000000000"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // Left unsigned on purpose: the installer signs every generated APK on the device.
            signingConfig = null
        }
    }

    packaging {
        // Compressed native libraries (extractNativeLibs=true): keeps generated APKs small and
        // means the on-device zip writer only has to align resources.arsc.
        jniLibs.useLegacyPackaging = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:${property("geckoviewVersion")}")
}
