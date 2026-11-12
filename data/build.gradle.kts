import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Round 13 — Klipy GIF search: the app calls the Klipy API for GIF search
// and trending (see data/.../klipy/KlipyClient.kt). The API key lives
// ONLY in local.properties (repo root, gitignored, never committed) as
// `klipyApiKey=...` — never in a source file or a committed file.
// Kristin: paste your Klipy key from the Partner Panel (partner.klipy.com)
// into local.properties. Until a real key is set, the client reports no key
// and the GIF grid shows an empty state; everything else in the app works
// unchanged. Rate limit: the TEST key allows ~100 calls/hour. The PRODUCTION
// key is free and unlimited — requested via a simple form in the Partner Panel
// at partner.klipy.com. (Giphy was dropped 2026-11-11: its 100/hour beta limit
// was too small.)
// Round 18 — CI builds get the same key without committing it: the workflow
// writes local.properties from the KLIPY_API_KEY repository secret before
// the first Gradle step, so every APK (including CI-built ones) ships with
// GIF search working out of the box. Swap the secret's value to move from
// the test key to the production key — no code change needed.
val klipyApiKey: String = run {
    val props = Properties()
    val localProps = rootProject.file("local.properties")
    if (localProps.exists()) {
        localProps.inputStream().use { props.load(it) }
    }
    props.getProperty("klipyApiKey", "")
}

android {
    namespace = "com.filestech.sms.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 26

        buildConfigField("String", "KLIPY_API_KEY", "\"$klipyApiKey\"")
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "LOG_ENABLED", "true")
        }
        release {
            buildConfigField("boolean", "LOG_ENABLED", "false")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // isReturnDefaultValues : les stubs android.jar (ex. SystemClock.elapsedRealtime, appelé
        // par SafetyCallConfig.isExpired) renvoient une valeur par défaut au lieu de lever
        // "not mocked" — aligné sur :app, requis par AuditV190Test relocalisé dans ce module.
        unitTests {
            isReturnDefaultValues = true
        }
        unitTests.all { it.useJUnitPlatform() }
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

ksp {
    // Room génère ici (AppDatabase_Impl + schémas). On écrit les JSON dans le dossier historique
    // app/schemas pour que le MigrationTest instrumenté (resté dans :app) les lise sans changement.
    arg("room.schemaLocation", "$rootDir/app/schemas")
    arg("room.incremental", "true")
    arg("room.generateKotlin", "true")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":domain"))

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.documentfile)

    // @ApplicationContext qualifier (annotation seule ; le plugin/graph Hilt vit dans :app)
    implementation(libs.hilt.android)

    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    implementation(libs.javax.inject)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    // Gradle 9 ne fournit plus le lanceur de la plateforme JUnit (« Failed to load JUnit Platform »).
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.junit.jupiter.params)
    testImplementation(libs.truth)
    // v1.27.2 — nécessaires pour tester les gardes de [VaultManager] en JVM : ses collaborateurs
    // sont des classes concrètes adossées à DataStore, donc non instanciables hors appareil, et
    // ses points d'entrée sont `suspend`. Mêmes versions que `:app`, prises au catalogue.
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
}
