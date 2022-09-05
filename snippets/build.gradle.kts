@Suppress("DSL_SCOPE_VIOLATION")
plugins {
    scala
    alias(libs.plugins.scalafmt)
    alias(libs.plugins.spotless)
    application
}

group = "it.unibo.zio"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.scala.library)
    testImplementation(libs.bundles.scala.test)
}

fun Property<BigDecimal>.set(value: Double) =
    set(BigDecimal.valueOf(value))