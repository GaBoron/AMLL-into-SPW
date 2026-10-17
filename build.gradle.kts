import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.xuncorp.spw.workshop.gradle.PluginPermission

plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
    id("com.xuncorp.spw.workshop") version "0.1.0-dev23"
}

group = "io.github.gaboron.amllspw"
val dependencySources = configurations.create("dependencySources") { isTransitive = false }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
kotlin { compilerOptions {
    jvmTarget.set(JvmTarget.JVM_25)
    freeCompilerArgs.add("-jvm-default=no-compatibility")
} }

dependencies {
    compileOnly(kotlin("stdlib"))
    compileOnly("com.github.Moriafly.spw-workshop-api:spw-workshop-api:0.1.0-dev23") { isTransitive = false }
    compileOnly("org.pf4j:pf4j:3.12.0")
    // UI hooks share the host composition and its exact runtime. Never ship another Compose runtime.
    compileOnly(compose.desktop.currentOs)
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("org.ow2.asm:asm:9.10.1")
    dependencySources("net.jthink:jaudiotagger:3.0.1:sources")
}

spmod {
    PluginClass = "io.github.gaboron.amllspw.host.AmllPlugin"
    PluginId = "io.github.gaboron.amllspw"
    PluginName = "AMLL into SPW"
    PluginProvider = "GaBoron"
    PluginVersion = project.version.toString()
    PluginDescription = "在 SPW 原生播放界面呈现 TTML 动态歌词"
    PluginOpenSourceUrl = "https://github.com/GaBoron/AMLL-into-SPW"
    PluginHasConfig = true
    PluginPermissions = listOf(PluginPermission.CLASS_TRANSFORM, PluginPermission.LIBRARY_READ)
}

val sourceArchive = tasks.register<Zip>("sourceArchive") {
    archiveFileName.set("amll-into-spw-${project.version}-source.zip")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    from("src/main") { into("src/main") }
    from("gradle") { into("gradle") }
    from("docs") { into("docs") }
    from("licenses") { into("licenses") }
    from("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "gradlew", "gradlew.bat", ".gitignore", "README.md", "LICENSE", "THIRD_PARTY_NOTICES.md")
}

tasks.named<Zip>("plugin") {
    dependsOn(sourceArchive)
    archiveFileName.set("amll-into-spw-${project.version}.spmod")
    from("LICENSE", "README.md", "THIRD_PARTY_NOTICES.md")
    into("licenses") { from("licenses") }
    into("source") { from(sourceArchive); from(dependencySources) }
}
