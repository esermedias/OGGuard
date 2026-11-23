import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.24"
}

group = "wtf.esuh"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Mojang-mapped Paper server classes. The packet layer works on net.minecraft.* directly.
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Paper and its forks run Mojang-mapped since 1.20.5, so the jar ships without reobfuscation.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.test {
    useJUnitPlatform()
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("OGGuard-${project.version}.jar")
    manifest {
        attributes("paperweight-mappings-namespace" to "mojang")
    }
}
