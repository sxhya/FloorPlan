plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val javafxVersion = "17.0.2"
val osName = System.getProperty("os.name").lowercase()
val osArch = System.getProperty("os.arch").lowercase()
val platform = when {
    osName.contains("win") -> "win"
    osName.contains("mac") -> {
        if (osArch.contains("aarch64") || osArch.contains("arm64")) "mac-aarch64" else "mac"
    }
    osName.contains("linux") -> if (osArch.contains("aarch64") || osArch.contains("arm64")) "linux-aarch64" else "linux"
    else -> "linux"
}

dependencies {
    implementation(kotlin("stdlib"))
    
    val jfxModules = listOf("base", "graphics", "controls", "fxml", "swing")
    for (m in jfxModules) {
        implementation("org.openjfx:javafx-$m:$javafxVersion:$platform")
    }
}

sourceSets {
    main {
        kotlin {
            srcDir("src")
        }
    }
}

application {
    mainClass.set("MainKt")
}
