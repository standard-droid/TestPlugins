import com.android.build.api.dsl.LibraryExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        // Shitpack repo which contains our tools and dependencies
        maven("https://jitpack.io")
    }

    dependencies {
        // 2026-09-25: build setup aligned with SaurabhKaperwan/CSX (AGP 9.1.0, Gradle 9.3.1,
        // Kotlin 2.3.20, plugin 81b1d424d2), a large extension repo that builds with it daily.
        classpath("com.android.tools.build:gradle:9.1.0")
        // Cloudstream gradle plugin which makes everything work and builds plugins
        // POSTMORTEM: this was pinned to a specific commit (32895aedb6) to survive
        // upstream API breaks (Plugin->BasePlugin, AcraApplication removal did exactly
        // that, twice). The pin itself broke instead — after ~1 month, JitPack could no
        // longer resolve that commit at all ("Could not find ...:gradle:32895aedb6"),
        // most likely artifact eviction for a rarely-requested raw-commit build (JitPack
        // treats these very differently from tagged releases, which recloudstream/gradle
        // doesn't publish). Floating SNAPSHOT it is — the weekly Monday cron build below
        // exists specifically to catch upstream breaks on our own schedule instead of
        // silently, so this is the more durable choice of two imperfect options.
        // UPDATE 2026-09-25: SNAPSHOT broke the same way. It resolves to upstream HEAD,
        // which is that same 32895aedb6 (2026-07-02, "Update dependencies": Kotlin 2.4.0,
        // sdk-common 32.1.1), and JitPack stopped serving it again ("Could not find
        // ...:gradle:-SNAPSHOT", pom gradle--32895aedb6-1). Pinned to 81b1d424d2 (2026-04-20,
        // "Add full configuration cache support"), the commit just before that update and the
        // one SaurabhKaperwan/CSX pins, which keeps its JitPack build in regular use. It has
        // AGP 9 support.
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    }
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

fun Project.cloudstream(configuration: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").configuration()

// AGP 9 removed BaseExtension; library modules are configured through LibraryExtension.
fun Project.android(configuration: LibraryExtension.() -> Unit) = extensions.getByName<LibraryExtension>("android").configuration()

subprojects {
    apply(plugin = "com.android.library")
    // No "kotlin-android": AGP 9 compiles Kotlin itself (built-in Kotlin), and applying
    // the separate plugin on top of it is an error.
    apply(plugin = "com.lagradost.cloudstream3.gradle")

    cloudstream {
        // when running through github workflow, GITHUB_REPOSITORY should contain current repository name
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "user/repo")
    }

    android {
        // Was a shared "com.example" for every module — harmless with just two modules
        // today, but a real collision risk (resource/manifest merging, R-class clashes)
        // the moment a third module is added, and increasingly strict AGP versions have
        // been known to enforce uniqueness more aggressively. Derived per-module instead,
        // so this can never collide and needs no manual upkeep when a new module is added.
        namespace = "com.a.${project.name.lowercase()}"

        compileSdk = 36
        defaultConfig {
            minSdk = 21
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_1_8
            targetCompatibility = JavaVersion.VERSION_1_8
        }
    }

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_1_8) // Required
            freeCompilerArgs.addAll(
                "-Xno-call-assertions",
                "-Xno-param-assertions",
                "-Xno-receiver-assertions",
                // From upstream PR #37: correct annotation defaults on Kotlin 2.x
                "-Xannotation-default-target=param-property"
            )
        }
    }

    dependencies {
        val implementation by configurations
        val cloudstream by configurations

        // REVERTED from the new library:-SNAPSHOT system (2026-09-10). That coordinate
        // started failing to resolve at all — not a caching issue (confirmed:
        // --refresh-dependencies made no difference) and not just our old pin expiring
        // (bare -SNAPSHOT failed identically). recloudstream/cloudstream's own app module
        // now references a "library-jvm.jar" build output, consistent with an in-progress
        // KMP/multiplatform restructuring of that module — plausible structural cause.
        // The official recloudstream/extensions template still uses the same (currently
        // broken) coordinate as of this writing, so this is a deliberate divergence for
        // reliability, not a step backward: this exact stub approach is what CSX
        // (SaurabhKaperwan/CSX), a large actively-maintained real-world extension
        // collection, uses successfully today. Revisit if/when upstream's library
        // artifact resolves cleanly again.
        // Stubs for all cloudstream classes (full pre-release APK — slower to resolve
        // than the library artifact was, but it works).
        // Last verified working: com.lagradost:cloudstream3:pre-release@2026-09-13
        // (coroutines dependency + CloudStreamApp/CloudflareKiller stub build succeeded,
        // Phase 1+2 code changes since have all built and run correctly against it)
        cloudstream("com.lagradost:cloudstream3:pre-release")

        // CORRECTION: the stub does NOT transitively expose kotlinx.coroutines on the
        // compile classpath (confirmed via a real build failure: every kotlinx.coroutines
        // import, Semaphore, coroutineScope, launch all came back "Unresolved reference").
        // My earlier assumption that the stub "already bundles coroutines directly" was
        // wrong. CSX (SaurabhKaperwan/CSX) — proof this stub system works — declares this
        // exact dependency itself; matching it exactly rather than guessing again.
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

        // These dependencies can include any of those which are added by the app,
        // but you don't need to include any of them if you don't need them.
        // https://github.com/recloudstream/cloudstream/blob/master/app/build.gradle.kts
        implementation(kotlin("stdlib")) // Adds Standard Kotlin Features
        implementation("com.github.Blatzar:NiceHttp:0.4.18") // HTTP Lib
        implementation("org.jsoup:jsoup:1.22.2") // HTML Parser
        // IMPORTANT: Do not bump Jackson above 2.13.1, as newer versions will
        // break compatibility on older Android devices.
        // Jackson removed (3.3) — confirmed unused; both providers parse JSON with
        // org.json (JSONObject/JSONArray) exclusively. One less dependency to track.
    }
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}