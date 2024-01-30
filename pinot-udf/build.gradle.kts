plugins {
  `java-library`
  jacoco
  id("org.hypertrace.jacoco-report-plugin") version "0.2.0"
}

dependencies {
  implementation("org.hypertrace.core.attribute.service:attribute-projection-functions:0.14.18")
  compileOnly("org.apache.pinot:pinot-common:1.0.0")

  testImplementation("org.junit.jupiter:junit-jupiter:5.6.2")
}

tasks.test {
  useJUnitPlatform()
}
