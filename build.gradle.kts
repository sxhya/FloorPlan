plugins {
    kotlin("jvm") version "2.2.20"
    application
}

group = "com.example"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://jogamp.org/deployment/maven/")
}

dependencies {
    val joglVersion = "2.4.0"
    implementation("org.jogamp.jogl:jogl-all-main:$joglVersion")
    implementation("org.jogamp.gluegen:gluegen-rt-main:$joglVersion")
    implementation(kotlin("stdlib"))
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
