import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Real AdMob ids live in ads.properties at the repo root (gitignored):
//   admobAppId=ca-app-pub-XXXXXXXX~YYYYYYYY
//   admobBannerUnitId=ca-app-pub-XXXXXXXX/ZZZZZZZZ
// Without that file every build uses Google's public test ids, which never earn
// a cent and never risk the account. See ads.properties.example.
val adsProps = Properties().apply {
    val f = rootProject.file("ads.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Google's documented test ids, safe to click and safe to ship by accident.
val testAdmobAppId = "ca-app-pub-3940256099942544~3347511713"
val testBannerUnitId = "ca-app-pub-3940256099942544/9214589741"

// Upload-key credentials from keystore.properties at the repo root (gitignored).
// See keystore.properties.example for the expected schema.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.vikash.voicescribe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vikash.voicescribe"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.6.1"
        vectorDrawables { useSupportLibrary = true }
        // Debug and any unsigned build get test ads; only release reads ads.properties.
        manifestPlaceholders["admobAppId"] = testAdmobAppId
        buildConfigField("String", "ADMOB_BANNER_UNIT_ID", "\"$testBannerUnitId\"")
    }

    signingConfigs {
        create("release") {
            val storeFileName = keystoreProps.getProperty("storeFile") ?: "upload-keystore.jks"
            val resolvedStoreFile = rootProject.file(storeFileName)
            if (resolvedStoreFile.exists() && keystoreProps.getProperty("storePassword") != null) {
                storeFile = resolvedStoreFile
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            manifestPlaceholders["admobAppId"] =
                adsProps.getProperty("admobAppId") ?: testAdmobAppId
            buildConfigField(
                "String",
                "ADMOB_BANNER_UNIT_ID",
                "\"${adsProps.getProperty("admobBannerUnitId") ?: testBannerUnitId}\"",
            )
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Sign only when keystore.properties is present; public clones still build unsigned
            signingConfigs.getByName("release").storeFile?.let {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    androidResources {
        // The bundled ggml model is already quantized — zipping it saves ~2% of
        // download and costs an inflate on every load. Store it as-is.
        noCompress += "bin"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":whisper"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.play.services.ads)
}
