import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.personal.englishautotalk"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.personal.englishautotalk"
        minSdk = 31
        targetSdk = 36
        versionCode = 23
        versionName = "0.5.7-stage4"
    }

    signingConfigs {
        // 테스트 빌드용 공용 디버그 키. 클라우드 컨테이너가 바뀌어도 같은 서명으로 업데이트 설치가 되도록 저장소에 둔다.
        // 최종 배포용(5단계)은 사용자가 관리하는 별도 릴리스 키를 사용한다.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
    // 단위 테스트에서 Android 스텁 대신 실제 org.json 사용
    testImplementation("org.json:json:20240303")
}
