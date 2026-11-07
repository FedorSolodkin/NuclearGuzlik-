import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

// Явное значение проверки входа (-PvoicemindAuthRequired=true|false); null — по типу сборки.
val authRequiredOverride: Boolean? = (project.findProperty("voicemindAuthRequired") as String?)
    ?.trim()?.lowercase()?.let {
        when (it) {
            "true" -> true
            "false" -> false
            else -> throw GradleException("voicemindAuthRequired: ожидается true или false, а не «$it»")
        }
    }

android {
    namespace = "com.example.voicemind"
    compileSdk = 36
    ndkVersion = "25.1.8937393"

    defaultConfig {
        minSdk = 26
        targetSdk = 36

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // OAuth 2.0 Client ID из Google Cloud Console — тип iOS (Google не пускает
        // Android-тип через Custom Tab, он зарезервирован под Google Sign-In SDK).
        // Задать в ~/.gradle/gradle.properties:
        //   googleOauthClientId=1234567890-xxxxx.apps.googleusercontent.com
        // Сервер подписки и публичный ключ лицензий (см. server/README.md).
        // Без ключа приложение работает со старым сервером в режиме /subscription/status.
        val apiUrl = (project.findProperty("voicemindApiUrl") as String?) ?: "https://api.voice-mind.ru"
        val licenseKey = (project.findProperty("voicemindLicensePublicKey") as String?).orEmpty()
        buildConfigField("String", "API_BASE_URL", "\"$apiUrl\"")
        buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$licenseKey\"")

        // Проверка входа и подписки. По умолчанию: в release включена, в debug выключена.
        // Пилотная сборка без сервера: -PvoicemindAuthRequired=false (работает и для release).
        buildConfigField("boolean", "AUTH_REQUIRED", (authRequiredOverride ?: true).toString())
        buildConfigField("boolean", "OFFLINE_PROTOTYPE", "false")

        // Какие модели ИИ-анализа можно выбрать в настройках: id через запятую
        // (-PvoicemindLlmModels=gemma2-2b — сборка «только Gemma»). Пусто — все.
        // Проверка файлов и удаление уже скачанных работают для всех моделей.
        // Опечатка в id молча вернула бы все модели — поэтому сверяем со списком.
        val knownLlmIds = setOf("gemma2-2b", "gemma2-2b-v90k", "gemma2-2b-q4_0", "vm-qwen06-ft")   // = LlmModels.ALL[*].id
        val llmModels = (project.findProperty("voicemindLlmModels") as String?).orEmpty()
        val unknownLlm = llmModels.split(',').map { it.trim() }.filter { it.isNotEmpty() && it !in knownLlmIds }
        if (unknownLlm.isNotEmpty()) {
            throw GradleException("voicemindLlmModels: неизвестные модели $unknownLlm, есть $knownLlmIds")
        }
        buildConfigField("String", "LLM_MODELS", "\"$llmModels\"")

        // Свой сервер моделей (server/, Caddy /models/): -PvoicemindModelsUrl=https://api.voice-mind.ru/models/
        // Задан — модели качаются сначала оттуда, потом с зеркала на GitHub, потом из оригиналов.
        val modelsUrl = (project.findProperty("voicemindModelsUrl") as String?).orEmpty().trim()
            .let { if (it.isEmpty() || it.endsWith("/")) it else "$it/" }
        buildConfigField("String", "MODELS_BASE_URL", "\"$modelsUrl\"")

        val gcpClientId: String = (project.findProperty("googleOauthClientId") as String?).orEmpty()
        buildConfigField("String", "GOOGLE_OAUTH_CLIENT_ID", "\"$gcpClientId\"")

        // Redirect URI использует reversed-DNS схему клиента: iOS OAuth клиент
        // диктует scheme = com.googleusercontent.apps.{client-id-prefix}
        val gcpReversedScheme: String = gcpClientId
            .removeSuffix(".apps.googleusercontent.com")
            .let { if (it.isEmpty()) "com.googleusercontent.apps.unconfigured" else "com.googleusercontent.apps.$it" }
        manifestPlaceholders["googleOauthScheme"] = gcpReversedScheme

        externalNativeBuild {
            cmake {
                // cFlags обязательны: debug-вариант AGP собирает CMake как Debug, а
                // горячие ядра ggml (ggml-cpu.c, arch/arm/quants.c) — это C, не C++.
                // Без явного -O3 они компилировались с -O0 → matmul в разы медленнее.
                cFlags   += listOf("-O3", "-DNDEBUG")
                cppFlags += listOf("-O3", "-DNDEBUG", "-std=c++17")
                arguments += listOf(
                    "-DWHISPER_BUILD_TESTS=OFF",
                    "-DWHISPER_BUILD_EXAMPLES=OFF",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_METAL=OFF",
                    // Бэкенды ggml — отдельные .so, грузятся через dlopen в рантайме
                    // (ggml_backend_load_all_from_path в JNI). CPU собирается в
                    // нескольких вариантах (armv8.0 / dotprod / fp16 / i8mm / armv9),
                    // ggml сам выбирает лучший под процессор: нет SIGILL на Cortex-A53/A73
                    // и есть i8mm-ядра на новых SoC. Vulkan, который не загрузился
                    // (Android 8, нет драйвера), просто пропускается — работаем на CPU.
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DBUILD_SHARED_LIBS=ON",
                    // Один общий libc++ на все .so. Со static STL у каждой библиотеки
                    // своя копия рантайма, и исключение из libggml-vulkan не ловится
                    // в JNI (так Adreno 618 ронял процесс на «Unsupported device»).
                    "-DANDROID_STL=c++_shared"
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Подписи читаем из keystore.properties в корне проекта (файл в .gitignore):
    //   storeFile=/путь/к/voicemind-release.p12      ← новый ключ (flavor store)
    //   storeType=pkcs12                                 (для .p12; для .jks не нужен)
    //   storePassword=...
    //   keyAlias=voicemind
    //   keyPassword=...
    //   legacyStoreFile=/путь/к/debug.keystore        ← ключ, которым подписаны v3.9 и раньше
    //   legacyStorePassword=android                     (flavor legacy, мостовая 3.10)
    //   legacyKeyAlias=androiddebugkey
    //   legacyKeyPassword=android
    // Без файла debug-сборки собираются как обычно.
    val keystorePropsFile = rootProject.file("keystore.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
    }
    fun prop(name: String): String? = keystoreProps.getProperty(name)?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (prop("storeFile")?.let { file(it).exists() } == true) {
            create("release") {
                storeFile     = file(prop("storeFile")!!)
                prop("storeType")?.let { storeType = it }
                storePassword = prop("storePassword")
                keyAlias      = prop("keyAlias")
                keyPassword   = prop("keyPassword")
            }
        }
        if (prop("legacyStoreFile")?.let { file(it).exists() } == true) {
            create("legacy") {
                storeFile     = file(prop("legacyStoreFile")!!)
                storePassword = prop("legacyStorePassword") ?: "android"
                keyAlias      = prop("legacyKeyAlias") ?: "androiddebugkey"
                keyPassword   = prop("legacyKeyPassword") ?: "android"
            }
        }
    }

    flavorDimensions += "channel"
    productFlavors {
        // Основное приложение: новый пакет и новый ключ (с v4.0).
        create("store") {
            dimension = "channel"
            applicationId = "ru.voicemind.app"
            versionCode = 47
            versionName = "4.0.7"
            buildConfigField("boolean", "LEGACY_BRIDGE", "false")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        // Мостовая версия для тех, у кого стоит com.example.voicemind (≤3.9):
        // ставится поверх старой, даёт выгрузить все записи архивом и
        // предлагает перейти на новое приложение.
        create("legacy") {
            dimension = "channel"
            applicationId = "com.example.voicemind"
            versionCode = 14
            versionName = "3.10"
            // Мостовое окно «перейдите на 4.0» включаем, когда выйдет 4.0
            // (-PlegacyBridge=true). Пока 3.10 — обычное обновление поверх 3.9.
            val bridge = (project.findProperty("legacyBridge") as String?) == "true"
            buildConfigField("boolean", "LEGACY_BRIDGE", bridge.toString())
            // Аккаунты пользователей ≤3.9 — на старом сервере.
            buildConfigField("String", "API_BASE_URL", "\"https://193-104-57-40.sslip.io\"")
            buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"\"")
            signingConfigs.findByName("legacy")?.let { signingConfig = it }
        }
    }

    buildTypes {
        debug {
            // Отладочные сборки ставятся рядом с рабочим приложением, не поверх него.
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            // Сервер подписки для отладки не нужен (-PvoicemindAuthRequired=true — проверить вход).
            buildConfigField("boolean", "AUTH_REQUIRED", (authRequiredOverride ?: false).toString())
            isMinifyEnabled = false
            // Для телефона хватает arm64; x86_64 (эмуляторы, Chromebook) — только в release,
            // иначе нативная часть собирается вдвое дольше.
            ndk { abiFilters += listOf("arm64-v8a") }
            // Локальный сервер для отладки: -PvoicemindDebugApiUrl=http://127.0.0.1:8000
            (project.findProperty("voicemindDebugApiUrl") as String?)?.let {
                buildConfigField("String", "API_BASE_URL", "\"$it\"")
            }
            (project.findProperty("voicemindDebugLicensePublicKey") as String?)?.let {
                buildConfigField("String", "LICENSE_PUBLIC_KEY", "\"$it\"")
            }
        }
        create("hackathon") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".hackathon"
            versionNameSuffix = "-hackathon"
            matchingFallbacks += "debug"
            buildConfigField("boolean", "AUTH_REQUIRED", "false")
            // Keep normal model preparation; the wearable prototype skips it.
            buildConfigField("boolean", "OFFLINE_PROTOTYPE", "false")
        }
        create("prototype") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".prototype"
            versionNameSuffix = "-prototype"
            matchingFallbacks += "debug"
            buildConfigField("boolean", "AUTH_REQUIRED", "false")
            buildConfigField("boolean", "OFFLINE_PROTOTYPE", "true")
        }
        release {
            // Release без проверки входа видно по версии («3.10-pilot»), чтобы такая
            // сборка случайно не ушла в магазин под видом обычной.
            if (authRequiredOverride == false) versionNameSuffix = "-pilot"
            ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // signingConfig задаётся во flavor; без keystore.properties release
            // соберётся неподписанным — такой APK не установится, и это правильно.
        }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            // Robolectric downloads its Android runtime outside Gradle repositories.
            // Google's Maven Central mirror avoids shared-runner HTTP 429 responses.
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2")
            // Private opt-in fixtures live outside Gradle's declared inputs and
            // can change between runs. Never reuse a stale evaluation result.
            if (System.getenv("VOICEMIND_LOCAL_EVIDENCE") != null ||
                System.getenv("VOICEMIND_ACOUSTIC_EVIDENCE") != null ||
                System.getenv("VOICEMIND_DIAR_BENCHMARK") != null) {
                it.outputs.upToDateWhen { false }
            }
        }
    }
    testBuildType = (project.findProperty("voicemindTestBuildType") as String?)?.also {
        require(it == "debug" || it == "prototype") { "voicemindTestBuildType: debug или prototype" }
    } ?: "debug"
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources/wearable")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Встроенные модели: -PvoicemindBundle=<папка с подпапкой models/>. Файлы кладутся в APK без сжатия
    // (иначе сборка и первый запуск долгие), при первом запуске копируются в папку приложения (BundledModels).
    (project.findProperty("voicemindBundle") as String?)?.takeIf { it.isNotBlank() }?.let { dir ->
        sourceSets.getByName("main").assets.srcDir(dir)
    }
    androidResources {
        noCompress += listOf("gguf", "onnx", "bin")
    }
    buildFeatures {
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // ggml ищет бэкенды (libggml-cpu-*.so, libggml-vulkan.so) сканированием
        // каталога nativeLibraryDir — он существует только если .so распакованы
        // из APK при установке.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            output.outputFileName = "VoiceMind-v${variant.versionName}-${variant.flavorName}-${variant.buildType.name}.apk"
        }
    }
}

kotlin {
    jvmToolchain(17)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ≥1.18: модель голосов с HuggingFace (wespeaker, ONNX IR v10) на 1.17 не
    // загружалась ("Unsupported model IR version: 10") — разбиение по голосам
    // молча падало в деление по паузам.
    implementation(libs.onnxruntime.android)
    implementation(libs.gson)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.browser)
    // Только для чтения старых EncryptedSharedPreferences при миграции (LegacyEncryptedPrefs).
    implementation(libs.androidx.security.crypto)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
}

// Release основного приложения без своего сервера и ключа лицензий собирать нельзя:
// такая сборка не сможет войти в аккаунт (см. server/README.md). Пилотной сборке
// без входа (-PvoicemindAuthRequired=false) сервер не нужен.
tasks.matching { it.name in setOf("assembleStoreRelease", "bundleStoreRelease") }.configureEach {
    doFirst {
        if (authRequiredOverride == false) return@doFirst
        val missing = listOf("voicemindApiUrl", "voicemindLicensePublicKey")
            .filter { (project.findProperty(it) as String?).isNullOrBlank() }
        if (missing.isNotEmpty()) {
            throw GradleException("Для store release задайте в ~/.gradle/gradle.properties: ${missing.joinToString()}")
        }
    }
}
