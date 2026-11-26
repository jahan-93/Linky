import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

repositories {
    mavenCentral()
    google()
}

/** FFmpeg(영상 해독) 네이티브 라이브러리는 Windows 64비트용만 넣는다. */
val nativePlatform = "windows-x86_64"

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)

    // H.264 해독: FFmpeg의 avcodec을 직접 호출
    implementation("org.bytedeco:javacpp:1.5.14")
    implementation("org.bytedeco:javacpp:1.5.14:$nativePlatform")
    implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14")
    implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14:$nativePlatform")

    // 같은 Wi-Fi에서 폰 찾기 (mDNS)
    implementation("org.jmdns:jmdns:3.6.5")

    // 자체 서명 인증서 만들기 (shared/Identity.kt)
    implementation("org.bouncycastle:bcpkix-jdk18on:1.87")
}

kotlin {
    jvmToolchain(21)
    // 폰 앱과 같이 쓰는 통신 규칙 코드
    sourceSets["main"].kotlin.srcDir("../shared/src/main/kotlin")
}

compose.desktop {
    application {
        mainClass = "dev.linky.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi)
            packageName = "Linky"
            packageVersion = "0.1.0"
            // TLS(jdk.crypto.ec), JavaCPP(jdk.unsupported) 등 필요한 JDK 모듈을 빠짐없이 넣는다
            includeAllModules = true
            windows {
                menuGroup = "Linky"
                shortcut = true
                dirChooser = true
            }
        }
    }
}
