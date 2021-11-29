plugins {
    id("com.android.application")
    kotlin("android")
}

android {
    compileSdk = 31
    buildToolsVersion = "31.0.0"

    defaultConfig {
        applicationId = "org.grapheneos.gmscompatui"
        minSdk = 31
        targetSdk = 31
        versionCode = 1
        versionName = versionCode.toString()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isShrinkResources = true
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    val dimen = "hasSystemPrivileges"
    flavorDimensions += dimen

    arrayOf("priv", "unpriv").forEach {
        val f = productFlavors.create(it)
        f.dimension = dimen
        f.buildConfigField("boolean", dimen, "${it == "priv"}")
        sourceSets.getByName(it).manifest.srcFile("flavors/$it/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_11)
        targetCompatibility(JavaVersion.VERSION_11)
    }
    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_11.toString()
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.3.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.12.0")
    // for signify signature verification
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.69")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.69")

    testImplementation("junit:junit:4.+")
    androidTestImplementation("androidx.test.ext:junit:1.1.3")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.4.0")
}
