import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.maven.publish)
}

// Native inputs staged by scripts/build-engine.sh or scripts/fetch-engine.sh.
val engineDir: File = rootProject.file("engine")
val androidAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

android {
    namespace = "dev.taladb"
    compileSdk = 36
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += androidAbis }
        externalNativeBuild {
            cmake { arguments += "-DTALADB_ENGINE_DIR=${engineDir.absolutePath}" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// libtaladb_ffi.so from the engine sits beside the CMake-built libtaladb_jni.so
// in the AAR. Added through the variant API: AGP 9's DSL source sets no longer
// take srcDir().
androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory(engineDir.resolve("jniLibs").path)
        // The unit tests are plain JUnit4 with nothing host-specific, so the
        // instrumented build runs them too: the same suite on the host JVM and
        // on a device, where the Android libraries and ART's JNI are real.
        variant.androidTest?.sources?.kotlin?.addStaticSourceDirectory("src/test/kotlin")
    }
}

kotlin {
    explicitApi()
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------------
// Engine inputs
// ---------------------------------------------------------------------------

val checkEngine = tasks.register("checkEngine") {
    description = "Fails with instructions when engine/ has not been staged."
    val header = engineDir.resolve("include/taladb.h")
    doLast {
        if (!header.isFile) {
            throw GradleException(
                "Missing ${header.path}. Stage the engine first:\n" +
                    "  scripts/build-engine.sh [../taladb]   build from a local checkout\n" +
                    "  scripts/fetch-engine.sh               download the pinned release",
            )
        }
    }
}
tasks.named("preBuild") { dependsOn(checkEngine) }

// ---------------------------------------------------------------------------
// Host JNI build for unit tests
//
// The unit tests run on the build machine's JVM, not on Android, so they need
// the shim compiled for the host and linked against the host engine library.
// That lets the whole Kotlin surface — and the C shim's string and error
// handling — be tested without an emulator. Instrumented tests in
// src/androidTest cover the Android build of the same code.
// ---------------------------------------------------------------------------

val isMac = System.getProperty("os.name").startsWith("Mac")
val hostLibExt = if (isMac) "dylib" else "so"
val hostJniDir: Provider<Directory> = layout.buildDirectory.dir("host-jni")

val buildHostJni = tasks.register<Exec>("buildHostJni") {
    description = "Compiles the JNI shim for the host JVM so unit tests can load it."
    dependsOn(checkEngine)
    val source = file("src/main/cpp/taladb_jni.c")
    val hostFfi = engineDir.resolve("host/libtaladb_ffi.$hostLibExt")
    // JNI headers from the JDK running Gradle; jni.h is stable across JDKs.
    val javaHome = File(System.getProperty("java.home"))
    val outDir = hostJniDir.get().asFile

    inputs.file(source)
    inputs.file(engineDir.resolve("include/taladb.h"))
    inputs.file(hostFfi)
    outputs.dir(outDir)

    doFirst {
        if (!hostFfi.isFile) {
            throw GradleException(
                "Missing ${hostFfi.path}. The release archive has no host library; " +
                    "run scripts/build-engine.sh to build one for the unit tests.",
            )
        }
        outDir.mkdirs()
        hostFfi.copyTo(outDir.resolve(hostFfi.name), overwrite = true)
    }
    commandLine(
        buildList {
            addAll(listOf("cc", "-shared", "-fPIC", "-O2", "-std=c11", "-Wall", "-Wextra", "-Werror"))
            addAll(listOf("-I", javaHome.resolve("include").path))
            addAll(listOf("-I", javaHome.resolve(if (isMac) "include/darwin" else "include/linux").path))
            addAll(listOf("-I", engineDir.resolve("include").path))
            add(source.path)
            addAll(listOf("-L", outDir.path, "-ltaladb_ffi"))
            // Find libtaladb_ffi beside the shim, wherever the test JVM runs.
            add(if (isMac) "-Wl,-rpath,@loader_path" else "-Wl,-rpath,\$ORIGIN")
            addAll(listOf("-o", outDir.resolve("libtaladb_jni.$hostLibExt").path))
        },
    )
}

tasks.withType<Test>().configureEach {
    dependsOn(buildHostJni)
    systemProperty("java.library.path", hostJniDir.get().asFile.path)
}

// ---------------------------------------------------------------------------
// Publishing — dev.taladb:taladb-android
// ---------------------------------------------------------------------------

mavenPublishing {
    // Maven Central requires a javadoc jar; javadoc cannot read Kotlin, so it
    // is empty until Dokka is added. Sources ship for IDE navigation.
    configure(AndroidSingleVariantLibrary(JavadocJar.Empty(), SourcesJar.Sources(), "release"))
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates(artifactId = "taladb-android")

    pom {
        name = "TalaDB for Android"
        description = "Kotlin bindings for TalaDB, an embedded document and vector database."
        url = "https://github.com/tala-io/taladb-kotlin"
        licenses {
            license {
                name = "MIT"
                url = "https://opensource.org/license/mit"
            }
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }
        developers {
            developer {
                id = "tala-io"
                name = "TalaDB"
                url = "https://github.com/tala-io"
            }
        }
        scm {
            url = "https://github.com/tala-io/taladb-kotlin"
            connection = "scm:git:https://github.com/tala-io/taladb-kotlin.git"
            developerConnection = "scm:git:ssh://git@github.com/tala-io/taladb-kotlin.git"
        }
    }
}
