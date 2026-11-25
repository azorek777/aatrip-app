plugins {
    id("com.android.application")
}

// Numer builda z GitHub Actions, dzięki czemu każda nowa wersja instaluje się na poprzednią.
val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1

// Własny klucz podpisu. Plik i hasło pochodzą z sekretów GitHub, nigdy z repozytorium.
val keystorePath: String? = System.getenv("KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")

android {
    namespace = "pl.aatrip"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.aatrip"
        minSdk = 28
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        if (keystorePath != null && keystorePassword != null) {
            create("own") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "aatrip"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("own")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    // Wymagane, żeby aplikacja działała w Android Auto (wyświetlanie z telefonu).
    implementation("androidx.car.app:app-projected:1.7.0")
    implementation("androidx.lifecycle:lifecycle-common:2.8.7")
}
