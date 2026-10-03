import com.github.gradle.node.npm.task.NpmTask
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
  id("java")
  id("checkstyle")
  id("com.diffplug.spotless") version "8.10.3"
  id("org.jetbrains.intellij.platform") version "2.19.0"
  id("com.github.node-gradle.node") version "7.1.0"
}

group = "io.firebite"
version = providers.gradleProperty("pluginVersion").get()

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

tasks.withType<JavaCompile> {
  options.release = 21
  options.compilerArgs.add("-Xlint:deprecation")
}

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
  }
}

dependencies {
  intellijPlatform {
    val localIde = providers.gradleProperty("tako.platformLocalPath")
    if (!localIde.orNull.isNullOrBlank()) {
      local(localIde)
    } else {
      intellijIdea(providers.gradleProperty("platformVersion"))
    }
    pluginVerifier()
    zipSigner()
    bundledPlugin("com.intellij.modules.jcef")
    testFramework(TestFrameworkType.Platform)
  }
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.opentest4j:opentest4j:1.3.0")
}

node {
  download = true
  version = "24.11.1"
  npmInstallCommand = "ci"
  nodeProjectDir = file("webview")
}

val webviewSources = fileTree("webview/src")
val webviewConfig = files("webview/package.json", "webview/package-lock.json", "webview/tsconfig.json")

val webviewCheck = tasks.register<NpmTask>("webviewCheck") {
  description = "Type-checks the chat page's TypeScript."
  dependsOn(tasks.npmInstall)
  args = listOf("run", "check")
  inputs.files(webviewSources, webviewConfig)
  val stamp = layout.buildDirectory.file("webview-check.stamp")
  outputs.file(stamp)
  doLast { stamp.get().asFile.writeText("ok") }
}

val webviewBundle = tasks.register<NpmTask>("webviewBundle") {
  description = "Bundles the chat page's TypeScript into webview/app.js."
  dependsOn(tasks.npmInstall, webviewCheck)
  args = listOf("run", "bundle")
  inputs.files(webviewSources, webviewConfig)
  outputs.dir(layout.buildDirectory.dir("generated/webview"))
}

sourceSets.main {
  resources.srcDir(webviewBundle)
}

val webviewFormat = tasks.register<NpmTask>("webviewFormat") {
  description = "Formats the chat page's TypeScript with Prettier."
  dependsOn(tasks.npmInstall)
  args = listOf("run", "format")
}

val webviewFormatCheck = tasks.register<NpmTask>("webviewFormatCheck") {
  description = "Fails when the chat page's TypeScript is not formatted."
  dependsOn(tasks.npmInstall)
  args = listOf("run", "format:check")
  inputs.files(webviewSources, webviewConfig, file("webview/.prettierrc.json"))
  val stamp = layout.buildDirectory.file("webview-format.stamp")
  outputs.file(stamp)
  doLast { stamp.get().asFile.writeText("ok") }
}

spotless {
  lineEndings = com.diffplug.spotless.LineEnding.UNIX
  java {
    target("src/*/java/**/*.java")
    eclipse().configFile("config/formatter/eclipse-java.prefs")
    importOrder("\\#", "")
    removeUnusedImports()
  }
  kotlinGradle {
    target("*.gradle.kts")
    trimTrailingWhitespace()
    endWithNewline()
  }
}

tasks.named("spotlessApply") { dependsOn(webviewFormat) }
tasks.named("spotlessCheck") { dependsOn(webviewFormatCheck) }

checkstyle {
  toolVersion = "14.3.0"
  configFile = file("config/checkstyle/checkstyle.xml")
}

intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "262"
    }
  }
  instrumentCode = false
  buildSearchableOptions = false
  pluginVerification {
    ides {
      recommended()
    }
  }
  signing {
    certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
    privateKey = providers.environmentVariable("PRIVATE_KEY")
    password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
  }
  publishing {
    token = providers.environmentVariable("PUBLISH_TOKEN")
  }
}
