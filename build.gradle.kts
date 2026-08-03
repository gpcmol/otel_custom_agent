plugins {
  java
  id("com.gradleup.shadow") version "9.2.2"
  id("com.diffplug.spotless") version "7.2.1"
  id("com.github.spotbugs") version "6.4.8"
}

group = "org.otel"
version = "0.1.0-SNAPSHOT"

repositories {
  mavenCentral()
  maven {
    name = "sonatype"
    url = uri("https://central.sonatype.com/repository/maven-snapshots/")
  }
}

configurations {
  create("otel")
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(21)
  }
}

dependencies {
  compileOnly("io.opentelemetry.javaagent:opentelemetry-javaagent-extension-api:2.30.0-alpha")
  compileOnly("io.opentelemetry.instrumentation:opentelemetry-instrumentation-api:2.30.0")
  compileOnly("net.bytebuddy:byte-buddy:1.17.7")
  compileOnly("com.google.auto.service:auto-service-annotations:1.1.1")
  annotationProcessor("com.google.auto.service:auto-service:1.1.1")

  testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
  testImplementation("io.opentelemetry:opentelemetry-api:1.64.0")
  testImplementation("org.testcontainers:testcontainers:2.0.5")
  testImplementation("com.squareup.okhttp3:okhttp:5.4.0")
  testImplementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
  testImplementation("io.opentelemetry.proto:opentelemetry-proto:1.10.0-alpha")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.13.4")

  add("otel", "io.opentelemetry.javaagent:opentelemetry-javaagent:2.30.0")
}

spotless {
  java {
    googleJavaFormat()
  }
}

tasks {
  test {
    useJUnitPlatform()
    systemProperty("otel.run.smoke.tests", System.getProperty("otel.run.smoke.tests", "false"))
    systemProperty("io.opentelemetry.smoketest.agentPath", configurations["otel"].singleFile.absolutePath)
    systemProperty("io.opentelemetry.smoketest.extensionPath", shadowJar.get().archiveFile.get().asFile.absolutePath)
    systemProperty(
      "io.opentelemetry.smoketest.extendedAgentPath",
      layout.buildDirectory.file("otel/opentelemetry-javaagent.jar").get().asFile.absolutePath
    )
  }

  compileJava {
    options.release = 21
  }

  assemble {
    dependsOn(shadowJar)
  }

  register<Jar>("extendedAgent") {
    dependsOn(configurations["otel"], shadowJar)
    archiveFileName.set("opentelemetry-javaagent.jar")
    destinationDirectory.set(layout.buildDirectory.dir("otel"))
    from(zipTree(configurations["otel"].singleFile))
    from(shadowJar) { into("extensions") }
    doFirst {
      manifest.from(
          zipTree(configurations["otel"].singleFile).matching {
            include("META-INF/MANIFEST.MF")
          }.singleFile)
    }
  }

  named("test") {
    dependsOn("extendedAgent")
  }

  withType<com.github.spotbugs.snom.SpotBugsTask>().configureEach {
    enabled = false
    reports {
      create("html") { required = true }
      create("xml") { required = false }
    }
    if (name == "spotbugsTest") {
      excludeFilter = layout.projectDirectory.file("spotbugs-test-exclude.xml").asFile
    }
  }
}

tasks.named("spotlessJavaCheck") {
  enabled = false
}
