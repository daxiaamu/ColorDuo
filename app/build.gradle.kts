plugins { id("com.android.application") }
android {
    namespace = "io.github.colorduo"
    compileSdk = 37
    defaultConfig {
        applicationId = "io.github.colorduo"
        minSdk = 33
        targetSdk = 36
        versionCode = 7
        versionName = "0.3.2"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    testImplementation("junit:junit:4.13.2")
}
