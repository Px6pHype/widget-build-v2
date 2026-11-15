plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.glasswidgets"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.glasswidgets"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
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
}

dependencies {
    // Внешние библиотеки не нужны:
    //  - запрос погоды: стандартные HttpURLConnection + org.json (входят в Android SDK);
    //  - координаты: стандартный LocationManager;
    //  - виджеты: классические RemoteViews, без Jetpack Glance;
    //  - java.time доступен «из коробки» начиная с API 26 (minSdk = 26).
}
