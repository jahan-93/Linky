plugins {
    id("com.android.application")
}

android {
    namespace = "dev.phonemirror.probe"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.phonemirror.probe"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
