plugins {
    id("com.android.application")
}

android {
    namespace = "dev.linky.phone"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "dev.linky.phone"
        // TLS 1.3, MediaCodec 저지연 설정 등을 위해 안드로이드 10 이상
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets {
        getByName("main") {
            // 노트북 앱과 같이 쓰는 통신 규칙 코드
            kotlin.directories.add("../../shared/src/main/kotlin")
        }
    }

    packaging {
        resources {
            // BouncyCastle jar들에 같은 이름의 메타 파일이 있어 충돌하므로 뺀다
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    // 자체 서명 인증서 만들기 (shared/Identity.kt)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.87")
}
