plugins {
    scala
    alias(libs.plugins.scoverage)
    alias(libs.plugins.scalafmt)
    alias(libs.plugins.spotless)
}

group = "it.unibo.zio"

scoverage {
    minimumRate.set(0.5)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.scala.library)
    testImplementation(libs.bundles.scala.test)
}

fun Property<BigDecimal>.set(value: Double) =
    set(BigDecimal.valueOf(value))