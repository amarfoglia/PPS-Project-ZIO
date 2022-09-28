plugins {
    scala
    alias(libs.plugins.scalafmt)
    alias(libs.plugins.spotless)
}

group = "it.unibo.chatapp"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.scala.library)
    implementation(libs.zio)
    implementation(libs.zio.streams)
    implementation(libs.zio.json)
    testImplementation(libs.bundles.scala.test)
    testImplementation(libs.bundles.zio.test)
    testRuntimeOnly(libs.scala.xml)
}