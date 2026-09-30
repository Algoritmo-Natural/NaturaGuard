plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.algoritmonatural.naturaguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.algoritmonatural.naturaguard"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "0.2.1"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime:2.9.1")

    testImplementation("junit:junit:4.13.2")
}
