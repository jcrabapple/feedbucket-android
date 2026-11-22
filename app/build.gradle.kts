import java.io.FileInputStream
import java.util.Properties
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeFeatureFlag

plugins {
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.room)
    alias(libs.plugins.hilt)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.kotlin.parcelize)
}

fun fetchGitCommitHash(): String {
    val process =
        ProcessBuilder("git", "rev-parse", "--verify", "--short", "HEAD")
            .redirectErrorStream(true)
            .start()
    return process.inputStream.bufferedReader().use { it.readText().trim() }
}

val gitCommitHash = fetchGitCommitHash()
val keyProps = Properties()
// S35a: private release keystore lives OUTSIDE the repo. Properties are read
// from $FEEDBUCKET_KEYSTORE_PROPERTIES or a gitignored repo-root
// keystore.properties. The configuration phase never throws on absence; the
// loud failure is scoped to release assembly/packaging via taskGraph guard.
val keystorePropsOverride = System.getenv("FEEDBUCKET_KEYSTORE_PROPERTIES")
val keyPropsFile: File =
    keystorePropsOverride?.let { rootProject.file(it) } ?: rootProject.file("keystore.properties")

if (keyPropsFile.exists()) {
    keyProps.load(FileInputStream(keyPropsFile))
}

android {
    compileSdk = 36

    defaultConfig {
        applicationId = "us.snakepit.feedbucket"
        minSdk = 26
        targetSdk = 36
        versionCode = 10
        versionName = "0.1.9"

        buildConfigField(
            "String",
            "USER_AGENT_STRING",
            "\"FeedBucket/${versionName}(${versionCode})\"",
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        room { schemaDirectory("$projectDir/schemas") }

        ksp { arg("room.incremental", "true") }
    }

    flavorDimensions.add("channel")
    productFlavors {
        create("github") {
            isDefault = true
            dimension = "channel"
        }
        create("fdroid") { dimension = "channel" }
        create("googlePlay") {
            dimension = "channel"
            applicationIdSuffix = ".google.play"
        }
    }
    signingConfigs {
        create("release") {
            (keyProps["keyAlias"] as String?)?.let { keyAlias = it }
            (keyProps["keyPassword"] as String?)?.let { keyPassword = it }
            (keyProps["storeFile"] as String?)?.let { storeFile = rootProject.file(it) }
            (keyProps["storePassword"] as String?)?.let { storePassword = it }
        }
    }
    lint { disable.addAll(listOf("MissingTranslation", "ExtraTranslation")) }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug" }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    // S35a: loud failure scoped to release assembly/packaging only. The
    // configuration phase stays clean so debug builds and `gradlew tasks`
    // work without any signing material present.
    gradle.taskGraph.whenReady {
        val releasePackagingScheduled =
            allTasks.any {
                it.path.startsWith(":app:") &&
                    (it.name.startsWith("assemble") ||
                        it.name.startsWith("package") ||
                        it.name.startsWith("bundle")) &&
                    it.name.endsWith("Release")
            }
        if (releasePackagingScheduled && !keyPropsFile.exists()) {
            throw GradleException(
                "Release signing requires private keystore; see docs/SPEC-v1.md S35a"
            )
        }
    }
    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "FeedBucket-${defaultConfig.versionName}-${gitCommitHash}.apk"
        }
    }
    kotlinOptions {
        freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn"
        jvmTarget = JavaVersion.VERSION_11.toString()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { buildConfig = true }
    packaging {
        resources.excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        resources.excludes.add("rome-utils-*.jar")
    }
    androidResources { generateLocaleConfig = true }
    sourceSets { getByName("androidTest").assets.srcDir("$projectDir/schemas") }
    composeCompiler { featureFlags = setOf(ComposeFeatureFlag.PausableComposition) }
    namespace = "me.ash.reader"
}

aboutLibraries { excludeFields = arrayOf("generated") }

dependencies {
    // AboutLibraries
    implementation(libs.aboutlibraries.core)
    implementation(libs.aboutlibraries.compose)

    // Compose
    implementation(libs.compose.html)
    implementation(platform(libs.compose.bom.alpha))
    implementation(libs.androidx.ui.graphics)
    androidTestImplementation(platform(libs.compose.bom.stable))
    implementation(libs.compose.animation.graphics)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.util)
    implementation(libs.compose.material)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling)
    implementation(libs.compose.ui.tooling.preview)
    androidTestImplementation(libs.compose.ui.test.junit4)
    implementation(libs.compose.material3)

    // Coil
    implementation(libs.coil.base)
    implementation(libs.coil.compose)
    implementation(libs.coil.svg)
    implementation(libs.coil.gif)

    // Hilt
    implementation(libs.hilt.work)
    implementation(libs.hilt.android)
    androidTestImplementation(platform(libs.compose.bom.stable))
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    ksp(libs.hilt.android.compiler)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.viewmodel)

    // AndroidX
    implementation(libs.android.svg)
    implementation(libs.java.diff.utils)
    implementation(libs.opml.parser)
    implementation(libs.readability4j)
    implementation(libs.rome)
    implementation(libs.rome.modules)
    implementation(libs.telephoto)
    implementation(libs.okhttp)
    implementation(libs.okhttp.coroutines)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.profileinstaller)
    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.room.paging)
    implementation(libs.room.common)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.paging.common.ktx)
    implementation(libs.paging.runtime.ktx)
    implementation(libs.paging.compose)
    implementation(libs.browser)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.core.ktx)
    implementation(libs.activity.compose)
    implementation(libs.appcompat)
    implementation(libs.glance.appwidget)
    implementation(libs.glance.appwidget.preview)
    implementation(libs.glance.material3)
    implementation(libs.glance.preview)

    implementation(libs.navigation3.runtime)
    implementation(libs.navigation3.ui)
    //    implementation(libs.compose.material3.adaptive.navigation3)
    implementation(libs.lifecycle.viewmodel.navigation3)
    implementation(libs.navigationevent)
    implementation(libs.compose.material3.adaptive.navigation)
    implementation(libs.compose.material3.adaptive.navigation.suite)
    implementation(libs.compose.material3.adaptive.layout)

    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.timber)

    // S30a — hardened offline renderer (androidx.webkit, Apache-2.0).
    implementation(libs.webkit)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.work.testing)
    androidTestImplementation(libs.junit.ext)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.room.testing)
    testImplementation(libs.mockito.core)
    testImplementation(libs.mockito.junit.jupiter)
    testImplementation(libs.mockito.kotlin)
    // Wave G (S32a): real SQLite for synthetic fixture DBs in JVM tests —
    // Apache-2.0, test scope only (S36 license record in review packet).
    testImplementation(libs.sqlite.jdbc)
}
