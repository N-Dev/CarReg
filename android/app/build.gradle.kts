import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ortVersion = "1.22.0"
val repoRoot: File = rootProject.projectDir.parentFile

android {
    namespace = "io.github.ndev.roadsight"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.ndev.roadsight"
        minSdk = 26
        targetSdk = 35
        // CI sets these from the run number, so every published build installs over the last one.
        versionCode = (System.getenv("VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("VERSION_NAME") ?: "1.0-dev"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // One key for every build, so updates install over each other. It's in the repository because the
    // app is sideloaded from GitHub; see android/README.md for moving it to a GitHub secret.
    signingConfigs {
        create("sideload") {
            storeFile = file("../keystore/roadsight.jks")
            storePassword = "roadsight-sideload"
            keyAlias = "roadsight"
            keyPassword = "roadsight-sideload"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    // Phones (arm64) and the CI emulator (x86_64) each get their own APK.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Models are read whole into memory: stored uncompressed they load faster.
        noCompress += listOf("onnx")
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

/** The AI models and a sample photo come from the web apps' folders, so there's one copy of each in the repository. */
abstract class CopyModels : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val root = outputDir.get().asFile
        root.deleteRecursively()
        for (f in sources.files) {
            val sub = if (f.extension == "jpg") "samples" else "models"
            f.copyTo(File(root, "$sub/${f.name}"), overwrite = true)
        }
    }
}

androidComponents {
    onVariants { variant ->
        val copy = tasks.register<CopyModels>("copy${variant.name.replaceFirstChar { it.uppercase() }}Models") {
            sources.from(fileTree(repoRoot.resolve("models")) { include("*.onnx", "ocr.json") })
            sources.from(fileTree(repoRoot.resolve("count/models")) { include("*.onnx", "LICENSE-YOLOX.txt") })
            sources.from(repoRoot.resolve("tests/e2e/assets/car_ie.jpg"), repoRoot.resolve("tests/e2e/assets/two_cars.jpg"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(copy, CopyModels::outputDir)
    }
}

dependencies {
    implementation(project(":core"))
    implementation("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion")

    val composeBom = platform("androidx.compose:compose-bom:2025.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
