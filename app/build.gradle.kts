import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

android {
    useLibrary("android.car")
    namespace = "com.example.woojoo_1"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.woojoo_1"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 배포 서명: 프로젝트 루트에 keystore.properties(커밋 금지)가 있으면 그 키로,
    // 없으면 debug 키로 서명해 바로 설치 가능한 release APK를 만든다.
    //   storeFile=../release.jks  storePassword=…  keyAlias=…  keyPassword=…
    val keystoreProps = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreProps.exists()) {
            create("release") {
                val p = Properties().apply { keystoreProps.inputStream().use { load(it) } }
                storeFile = file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (keystoreProps.exists()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
            // R8 최적화 끔: 템플릿 기본값(androidx/kotlin/kotlinx 패키지 최적화)으로 빌드하면
            // 실행 즉시 IllegalAccessError(kotlin.collections) 로 종료됨 — 2026-11-15 에뮬레이터 실측.
            // Pleos SDK가 Kotlin 리플렉션을 쓰므로 축소·난독화 없이 배포한다.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    implementation("ai.pleos.playground:Vehicle:2.3.2")
}