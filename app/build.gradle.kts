import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Kata sandi pengawas diambil dari rahasia GitHub bernama ADMIN_PASSWORD.
val adminPassword: String = System.getenv("ADMIN_PASSWORD") ?: ""
require(adminPassword.length >= 6) {
    "Rahasia ADMIN_PASSWORD belum diisi di GitHub (minimal 6 karakter)."
}
val saltBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
val adminSalt = saltBytes.joinToString("") { "%02x".format(it) }
val adminHash = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
    .generateSecret(PBEKeySpec(adminPassword.toCharArray(), saltBytes, 120000, 256))
    .encoded.joinToString("") { "%02x".format(it) }

android {
    namespace = "id.sch.sman6ptk.cbt"
    compileSdk = 35

    defaultConfig {
        applicationId = "id.sch.sman6ptk.cbt"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
        buildConfigField("String", "ADMIN_SALT", "\"$adminSalt\"")
        buildConfigField("String", "ADMIN_HASH", "\"$adminHash\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
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
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
