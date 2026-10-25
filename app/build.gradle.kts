plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNum = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.handy.stt.natlep"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.handy.stt.natlep"
        minSdk = 24
        targetSdk = 34
        versionCode = 700 + runNum
        versionName = "7.0.$runNum"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        val fixedKeystore = file("handy_deterministic.jks")
        if (fixedKeystore.exists()) {
            getByName("debug") {
                storeFile = fixedKeystore
                storePassword = "handy123456"
                keyAlias = "handy_key"
                keyPassword = "handy123456"
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("androidx.lifecycle:lifecycle-service:2.8.2")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
