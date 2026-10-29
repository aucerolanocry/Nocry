plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versao do app: sobe junto com a tag do release (ex.: v1.0.0 -> 1.0.0)
val appVersionName = (System.getenv("NOCRY_VERSION")?.trim()?.takeIf { it.isNotEmpty() } ?: "1.1.0").removePrefix("v")
val appVersionCode = System.getenv("NOCRY_VERSION_CODE")?.trim()?.toIntOrNull() ?: 10100

// endereco do servidor das Keys (Cloudflare Worker). Nao e segredo: so diz onde o app confere a key.
val apiUrl = (System.getenv("NOCRY_API")?.trim()?.takeIf { it.isNotEmpty() } ?: "https://nocry.osreaisnocry.workers.dev").trimEnd('/')

android {
    namespace = "com.nocry.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nocry.app"
        minSdk = 26
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersionName

        ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a")) }
        buildConfigField("String", "API_URL", "\"" + apiUrl + "\"")
    }

    buildFeatures { buildConfig = true }

    // chave fixa: quem baixa uma versao nova instala por cima da antiga.
    // Os dados vem de segredos do GitHub (nunca ficam no repositorio).
    fun env(n: String): String? = System.getenv(n)?.trim()?.takeIf { it.isNotEmpty() }
    val ksPath = env("NOCRY_KEYSTORE")
    val temChave = ksPath != null && file(ksPath).isFile &&
        env("NOCRY_KEYSTORE_PASS") != null &&
        env("NOCRY_KEY_ALIAS") != null &&
        env("NOCRY_KEY_PASS") != null

    signingConfigs {
        if (temChave) {
            create("nocry") {
                storeFile = file(ksPath!!)
                storePassword = env("NOCRY_KEYSTORE_PASS")
                keyAlias = env("NOCRY_KEY_ALIAS")
                keyPassword = env("NOCRY_KEY_PASS")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            // sem chave configurada, assina com a chave de debug (so pra testar)
            signingConfig = if (temChave) signingConfigs.getByName("nocry") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources { excludes += listOf("META-INF/*.version", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("androidx.annotation:annotation:1.9.1")
}
