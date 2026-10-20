import java.util.Properties

plugins {
    id("com.android.application")
}

android {
    namespace = "com.ranovalabs.mirror"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ranovalabs.mirror"
        minSdk = 24
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"
    }

    // Upload key lives outside the repo (~/.mirrora-signing/keystore.properties, or the path in
    // the mirroraSigningProperties Gradle property). Without it, release builds are unsigned.
    val signingProps = file(project.findProperty("mirroraSigningProperties") as String?
        ?: "${System.getProperty("user.home")}/.mirrora-signing/keystore.properties")
    signingConfigs {
        if (signingProps.exists()) {
            val props = Properties().apply { signingProps.inputStream().use(::load) }
            create("upload") {
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // No R8: Netty and the plist/crypto libraries rely on reflection.
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    // AirPlay protocol (vendored java-airplay sources depend on these)
    implementation("io.netty:netty-codec-http:4.1.114.Final")
    implementation("io.netty:netty-handler:4.1.114.Final")
    implementation("com.googlecode.plist:dd-plist:1.28")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    implementation("org.whispersystems:curve25519-java:0.5.0")
    implementation("io.lindstrom:m3u8-parser:0.30")
    // mDNS fallback for TVs whose system responder never answers (see MdnsAdvertiser)
    implementation("org.jmdns:jmdns:3.5.12")
    implementation("org.slf4j:slf4j-api:2.0.13")
    implementation("com.github.tony19:logback-android:3.0.0")
    compileOnly("org.projectlombok:lombok:1.18.34")
    annotationProcessor("org.projectlombok:lombok:1.18.34")

    // HLS / URL video playback (YouTube, Safari video, etc.)
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    testImplementation("junit:junit:4.13.2")
}
