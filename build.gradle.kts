import org.jetbrains.changelog.Changelog
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.3.20"
  id("org.jetbrains.kotlin.plugin.compose") version "2.3.20"
  id("checkstyle")
  id("com.diffplug.spotless") version "8.10.3"
  id("org.jetbrains.intellij.platform") version "2.19.0"
  id("org.jetbrains.changelog") version "2.5.0"
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

tasks.withType<KotlinCompile> {
  compilerOptions.jvmTarget = JvmTarget.JVM_21
  compilerOptions.optIn.addAll("androidx.compose.foundation.ExperimentalFoundationApi",
    "androidx.compose.ui.ExperimentalComposeUiApi", "org.jetbrains.jewel.foundation.ExperimentalJewelApi")
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
    composeUI()
    bundledPlugin("org.jetbrains.plugins.terminal")
    bundledModule("intellij.platform.compose.markdown")
    bundledModule("intellij.platform.jewel.markdown.core")
    bundledModule("intellij.platform.jewel.markdown.ideLafBridgeStyling")
    bundledModule("intellij.platform.jewel.markdown.extensions.gfmTables")
    bundledModule("intellij.platform.jewel.markdown.extensions.gfmStrikethrough")
    bundledModule("intellij.platform.jewel.markdown.extensions.autolink")
    testFramework(TestFrameworkType.Platform)
  }
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.opentest4j:opentest4j:1.3.0")
}

tasks.test {
  // Tests load every plugin into one class loader. In IU 262.11879 the Ultimate plugin's
  // obfuscated startup activity Q.Q.Q.Q.V clashes with an interface of the same name in
  // lib/product-backend.jar, so its creation fails and every platform test errors in setUp.
  systemProperty("idea.suppressed.plugins.id", "com.intellij.modules.ultimate")
}

spotless {
  lineEndings = com.diffplug.spotless.LineEnding.UNIX
  java {
    target("src/*/java/**/*.java")
    eclipse().configFile("config/formatter/eclipse-java.prefs")
    importOrder("\\#", "")
    removeUnusedImports()
  }
  kotlin {
    target("src/*/kotlin/**/*.kt")
    trimTrailingWhitespace()
    endWithNewline()
  }
  kotlinGradle {
    target("*.gradle.kts")
    trimTrailingWhitespace()
    endWithNewline()
  }
}

changelog {
  groups.empty()
  repositoryUrl = "https://github.com/firebiteio/tako"
}

checkstyle {
  toolVersion = "14.3.0"
  configFile = file("config/checkstyle/checkstyle.xml")
}

intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "262"
    }
    changeNotes = providers.provider {
      with(changelog) {
        renderItem((getOrNull(project.version.toString()) ?: getUnreleased()).withHeader(false).withEmptySections(false),
          Changelog.OutputType.HTML)
      }
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
