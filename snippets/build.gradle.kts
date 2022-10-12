plugins {
    scala
    alias(libs.plugins.scalafmt)
    alias(libs.plugins.spotless)
//    alias(libs.plugins.scoverage)
}

group = "it.unibo.zio"

repositories {
    mavenCentral()
}

//scoverage {
//    scoverageScalaVersion.set("2.0.1")
//    minimumRate.set(BigDecimal(0))
//}

dependencies {
    implementation(libs.scala.library)
    implementation(libs.zio)
    implementation(libs.zio.streams)
    testImplementation(libs.bundles.scala.test)
    testImplementation(libs.bundles.zio.test)
    testRuntimeOnly(libs.scala.xml)
}

fun Property<BigDecimal>.set(value: Double) =
    set(BigDecimal.valueOf(value))