plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.monopoly.lan"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.monopoly.lan"
        minSdk = 24
        targetSdk = 30
        versionCode = 3
        versionName = "2.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // 调试包也带上网页资源，方便直接装手机试
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].assets.srcDir("src/main/assets")
}

/**
 * 网页只有一份：src\www\index.html（WS 优先、SSE 兜底）。
 * 每次构建前同步进 assets/www，改页面只改 src 里那一份。
 */
val syncWeb by tasks.registering(Copy::class) {
    from(rootProject.file("../src/www/index.html"))
    into("src/main/assets/www")
}

tasks.named("preBuild") { dependsOn(syncWeb) }
