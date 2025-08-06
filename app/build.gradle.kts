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
}