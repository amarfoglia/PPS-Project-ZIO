plugins {
    scala
    alias(libs.plugins.scalafmt)
    alias(libs.plugins.spotless)
    alias(libs.plugins.scalatest)
}

group = "it.unibo.zio"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.vladsch.flexmark:flexmark-util:0.64.0")
    testImplementation("com.vladsch.flexmark:flexmark-all:0.64.0")
    implementation(libs.scala.library)
    implementation(libs.zio)
    testImplementation(libs.bundles.scala.test)
    testImplementation(libs.zio.test)
    testRuntimeOnly(libs.scala.xml)
}

fun Property<BigDecimal>.set(value: Double) =
    set(BigDecimal.valueOf(value))