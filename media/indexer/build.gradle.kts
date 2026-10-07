plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.sekhar.helium.media.indexer"
    compileSdk = libs.versions.compileSdk.get().toInt()
    compileSdkMinor = libs.versions.compileSdkMinor.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:model"))
    api(project(":core:common"))
    // The indexer persists Semantic Video Memory, so it owns the cache contract
    // rather than making every caller remember to write it back.
    api(project(":core:database"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.common)
    implementation(libs.media3.extractor)
    implementation(libs.androidx.exifinterface)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
