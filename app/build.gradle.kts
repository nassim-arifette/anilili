import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

val forkApplicationId = "com.nassimarifette.anililiplus"
val requiredReleaseSigningProperties = listOf(
    "storeFile",
    "storePassword",
    "keyAlias",
    "keyPassword",
)
val releaseSigningConfigured = requiredReleaseSigningProperties.all {
    !keystoreProperties.getProperty(it).isNullOrBlank()
}
val allowUnsignedRelease = providers.gradleProperty("allowUnsignedRelease").orNull == "true"
val useReleaseSigning = releaseSigningConfigured && !allowUnsignedRelease
val releaseTaskRequested = gradle.startParameter.taskNames.any { requestedTask ->
    val taskName = requestedTask.substringAfterLast(':')
    taskName.contains("release", ignoreCase = true) &&
        listOf("assemble", "bundle", "package", "install", "publish").any {
            taskName.startsWith(it, ignoreCase = true)
        }
}
if (releaseTaskRequested && !releaseSigningConfigured && !allowUnsignedRelease) {
    val missing = requiredReleaseSigningProperties.filter { keystoreProperties.getProperty(it).isNullOrBlank() }
    if (missing.isNotEmpty()) {
        throw GradleException(
            "Release signing is not configured. Missing keystore.properties values: ${missing.joinToString()}",
        )
    }
}

android {
    namespace = "com.miruronative"
    compileSdk = 36
    buildToolsVersion = "35.0.1"

    defaultConfig {
        // The fork has its own application id because it cannot use the original author's
        // signing key. This lets AniLili+ coexist with the original app; every AniLili+ release
        // must keep this id and the same release key so Android accepts in-app updates.
        applicationId = forkApplicationId
        minSdk = 26
        targetSdk = 36
        versionCode = 31
        versionName = "0.2.2"
        resValue("string", "application_id", forkApplicationId)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (useReleaseSigning) create("release") {
            val storePath = keystoreProperties.getProperty("storeFile")
            if (storePath != null) {
                storeFile = rootProject.file(storePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["usesCleartextTraffic"] = "true"
            // Install debug builds alongside the signed release so testing never collides with
            // (and forces an uninstall of) the release's LibraryStore data.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "application_id", "$forkApplicationId.debug")
            buildConfigField("boolean", "UPDATE_ENABLED", "false")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (useReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            buildConfigField("boolean", "UPDATE_ENABLED", "true")
            manifestPlaceholders["usesCleartextTraffic"] = "false"
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                if (buildType.name == "release") "anilili-plus.apk" else "anilili-plus-debug.apk"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.ui)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.datasource.cronet)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.media3.cast)
    implementation(libs.play.services.cronet)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.fragment.ktx)

    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.tvprovider)
    implementation(libs.zxing.core)
    testImplementation(libs.junit)
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
