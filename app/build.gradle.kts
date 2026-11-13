import java.io.File
import java.util.Base64

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------- 正式签名（可选） ----------
// 通过环境变量 KEYSTORE_BASE64 / KEYSTORE_PASSWORD / KEY_ALIAS / KEY_PASSWORD 注入。
// 未配置时构建 debug 签名 APK（同样可以直接安装）。
val keystoreBase64: String? = System.getenv("KEYSTORE_BASE64")
val hasReleaseKeystore: Boolean = !keystoreBase64.isNullOrBlank()
val releaseKeystoreFile: File = File(layout.buildDirectory.asFile.get(), "release.keystore")

if (!keystoreBase64.isNullOrBlank()) {
    try {
        releaseKeystoreFile.parentFile?.mkdirs()
        releaseKeystoreFile.writeBytes(Base64.getDecoder().decode(keystoreBase64))
    } catch (e: Exception) {
        throw GradleException("KEYSTORE_BASE64 解码失败：请检查 GitHub Secret 中的 base64 内容是否完整、未换行截断", e)
    }
}

android {
    namespace = "asia.lifang.naswebview"
    compileSdk = 34

    defaultConfig {
        applicationId = "asia.lifang.naswebview"
        minSdk = 24
        targetSdk = 34
        versionCode = 8
        versionName = "1.5.0"
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = releaseKeystoreFile
                storeType = System.getenv("KEYSTORE_TYPE") ?: "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
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
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
}
