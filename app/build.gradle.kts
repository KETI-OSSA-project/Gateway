plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.gateway"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.gateway"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    buildFeatures {
        // compose = true
        dataBinding = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // ponytail: Moquette가 끌어오는 Netty jar들끼리 겹치는 META-INF 리소스 제외
    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/*.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA"
            )
        }
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
    implementation(libs.constraintlayout)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    // MQTT 클라이언트 라이브러리 (Android Service 대신 순수 Java 클라이언트 사용)
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    // Android Service와 LocalBroadcastManager 의존성 제거

    // ponytail: 임베디드 MQTT 브로커 (PC Mosquitto 대체 스파이크). Android 궁합 미검증 — gate 1에서 빌드 확인 중
    implementation("io.moquette:moquette-broker:0.15")
}