pluginManagement {
  repositories {
    gradlePluginPortal()
    mavenCentral()
    maven {
      name = "sonatype"
      url = uri("https://central.sonatype.com/repository/maven-snapshots/")
    }
  }
}

rootProject.name = "otel-custom-agent"
