import com.android.build.gradle.BaseExtension
import com.lagradost.cloudstream3.gradle.CloudstreamExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories { google(); mavenCentral(); maven("https://jitpack.io") }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("com.github.recloudstream:gradle:81b1d424d2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.20")
    }
}
allprojects { repositories { google(); mavenCentral(); maven("https://jitpack.io") } }
fun Project.cloudstream(c: CloudstreamExtension.() -> Unit) = extensions.getByName<CloudstreamExtension>("cloudstream").c()
fun Project.android(c: BaseExtension.() -> Unit) = extensions.getByName<BaseExtension>("android").c()
subprojects {
    apply(plugin = "com.android.library")
    apply(plugin = "kotlin-android")
    apply(plugin = "com.lagradost.cloudstream3.gradle")
    cloudstream {
        setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/AbanobAshrafWilliam/MySeed-Cloudstream/")
        authors = listOf("AbanobAshrafWilliam")
    }
    android {
        namespace = "com.abanobashrafwilliam.myseed"
        defaultConfig { minSdk = 21; compileSdkVersion(35); targetSdk = 35 }
        compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
    }
    tasks.withType<KotlinJvmCompile>().configureEach { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    dependencies {
        val implementation by configurations
        val cloudstream by configurations
        cloudstream("com.lagradost:cloudstream3:pre-release")
        implementation(kotlin("stdlib"))
        implementation("com.github.Blatzar:NiceHttp:0.4.18")
        implementation("org.jsoup:jsoup:1.22.2")
    }
}
