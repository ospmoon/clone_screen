plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "osp.moon.clonescreen"
    compileSdk = 36

    defaultConfig {
        applicationId = "osp.moon.clonescreen"
        minSdk = 21
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
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
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {

    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)

    // Добавьте эту зависимость для Android Auto и медиа
    //implementation(libs.media3.session)
}