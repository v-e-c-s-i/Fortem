import java.util.zip.ZipFile
import groovy.json.JsonSlurper

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "app.fortem"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.fortem"
        minSdk = 27
        targetSdk = 37
        versionCode = 78
        versionName = "1.0.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // CMake собирает низколатентный звуковой движок для основных архитектур Android.
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                // Oboe рекомендует общую реализацию стандартной библиотеки C++.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    // Версия NDK зафиксирована, чтобы сборка была воспроизводимой на разных компьютерах.
    ndkVersion = "28.2.13676358"

    buildFeatures {
        // Номер из versionName показывается внизу настроек без дублирования в коде.
        buildConfig = true
        compose = true
        prefab = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Robolectric exercises Compose UI in local JVM tests.
    testOptions { unitTests.isIncludeAndroidResources = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Oboe из того же официального AAR также находится в src/main/jniLibs.
            // Если Prefab включит свою копию, оставляем одну идентичную библиотеку.
            pickFirsts += "lib/*/liboboe.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    androidResources {
        // Исходные 24-битные WAV уже являются готовыми аудиоданными. Хранение
        // без ZIP-сжатия позволяет движку читать их прямо из APK без копии в ОЗУ.
        noCompress += "wav"
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)

    // BOM согласует совместимые версии всех библиотек Compose.
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Oboe выбирает наиболее быстрый звуковой путь, доступный на конкретном устройстве.
    implementation(libs.google.oboe)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// До сборки проверяется комплектность банка по именам и размерам файлов.
val pianoManifest = rootProject.file("tests/piano-assets.json")
val pianoAssetsDirectory = file("src/main/assets/piano")
tasks.register("checkPianoAssets") {
    group = "verification"
    description = "Rejects missing or truncated bundled piano files"
    inputs.file(pianoManifest)
    inputs.dir(pianoAssetsDirectory)
    doLast {
        val expected = JsonSlurper().parse(pianoManifest) as Map<*, *>
        expected.forEach { (name, size) ->
            val asset = pianoAssetsDirectory.resolve(name as String)
            check(asset.isFile && asset.length() == (size as Number).toLong()) {
                "Piano bank: $name is missing or truncated (expected $size bytes)"
            }
        }
    }
}
tasks.named("preBuild") { dependsOn("checkPianoAssets") }


// Отсутствующая динамическая библиотека должна обнаруживаться до установки.
tasks.register("verifyDebugNativeLibraries") {
    group = "verification"
    description = "Checks Oboe and C++ runtime in the built debug APK"
    dependsOn("assembleDebug")
    val apkDirectory = layout.buildDirectory.dir("outputs/apk/debug")
    doLast {
        val apks = apkDirectory.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "apk" }.toList()
        check(apks.isNotEmpty()) { "Debug APK не найден" }
        apks.forEach { apk ->
            ZipFile(apk).use { zip ->
                val expectedPiano = JsonSlurper().parse(pianoManifest) as Map<*, *>
                expectedPiano.forEach { (name, size) ->
                    check(zip.getEntry("assets/piano/$name")?.size == (size as Number).toLong()) {
                        "${apk.name}: missing or truncated piano/$name"
                    }
                }
                val names = zip.entries().asSequence().map { it.name }.toSet()
                val engines = names.filter { it.matches(Regex("lib/[^/]+/libfortem-audio\\.so")) }
                check(engines.isNotEmpty()) { "${apk.name}: отсутствует звуковой модуль" }
                engines.forEach { engine ->
                    val directory = engine.substringBeforeLast('/')
                    listOf("liboboe.so", "libc++_shared.so").forEach { library ->
                        check("$directory/$library" in names) {
                            "${apk.name}: отсутствует $directory/$library"
                        }
                    }
                }
            }
        }
        logger.lifecycle("Native libraries verified in ${apks.size} debug APK(s)")
    }
}
