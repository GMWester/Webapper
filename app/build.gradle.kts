import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
}

/**
 * Copies the GeckoView template APK (built by :template) into the installer's assets, keeping
 * only the native libraries of one ABI so each installer flavor stays as small as possible.
 */
abstract class PrepareTemplateTask : DefaultTask() {
    @get:InputFile
    abstract val templateApk: RegularFileProperty

    @get:Input
    abstract val abi: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        val keep = "lib/${abi.get()}/"
        ZipFile(templateApk.get().asFile).use { zin ->
            ZipOutputStream(File(out, "template.apk").outputStream().buffered()).use { zout ->
                for (e in zin.entries()) {
                    if (e.isDirectory) continue
                    if (e.name.startsWith("lib/") && !e.name.startsWith(keep)) continue
                    val ne = ZipEntry(e.name)
                    if (e.method == ZipEntry.STORED) {
                        ne.method = ZipEntry.STORED
                        ne.size = e.size
                        ne.compressedSize = e.size
                        ne.crc = e.crc
                    }
                    zout.putNextEntry(ne)
                    zin.getInputStream(e).use { it.copyTo(zout) }
                    zout.closeEntry()
                }
            }
        }
    }
}

val templateApkFile = project(":template").layout.buildDirectory
    .file("outputs/apk/release/template-release-unsigned.apk")

android {
    namespace = "com.webapper.installer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.webapper.installer"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    flavorDimensions += "abi"
    productFlavors {
        create("arm64") { dimension = "abi" }
        create("armv7") { dimension = "abi" }
        create("x86_64") { dimension = "abi" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so CI builds install directly; use your own key for
            // distribution (see README).
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        // The embedded template is already a zip; don't waste time recompressing it.
        noCompress += "apk"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants { variant ->
        val abi = when (variant.flavorName) {
            "arm64" -> "arm64-v8a"
            "armv7" -> "armeabi-v7a"
            else -> "x86_64"
        }
        val taskName = "prepareTemplate" + variant.name.replaceFirstChar { it.uppercase() }
        val task = tasks.register<PrepareTemplateTask>(taskName) {
            templateApk.set(templateApkFile)
            this.abi.set(abi)
            dependsOn(":template:assembleRelease")
        }
        variant.sources.assets?.addGeneratedSourceDirectory(task, PrepareTemplateTask::outputDir)
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(":template:assembleRelease")
    systemProperty("webapper.templateApk", templateApkFile.get().asFile.absolutePath)
    systemProperty("webapper.outDir", layout.buildDirectory.dir("generated-test").get().asFile.absolutePath)
}

dependencies {
    implementation("com.android.tools.build:apksig:8.11.1")
    testImplementation("junit:junit:4.13.2")
}
