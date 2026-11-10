plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17))
    }
}

dependencies {
    api(project(":common"))

    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdk_version"]}")
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:${rootProject.extra["sdk_version"]}")

    // Jython is the platform's and must NEVER be shipped: a second copy would not be the one
    // running project scripts, so the automation payload would be built from foreign PyObject
    // classes. compileOnly, at or below what the gateway bundles (8.3.x ships jython-ia 2.7.4.x).
    compileOnly("org.python:jython-ia:2.7.3.5")

    modlImplementation("org.eclipse.jgit:org.eclipse.jgit.ssh.apache:7.8.0.202609011348-r")

    // Bundles the Config Versioning React page (built by :web-ui) into the gateway jar under mounted/.
    modlImplementation(project(":web-ui"))

    // Tests run outside a Gateway: JGit only, for the pure branch logic.
    testImplementation("org.eclipse.jgit:org.eclipse.jgit:7.8.0.202609011348-r")
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.14.4")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.14.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.14.4")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
}

tasks.test {
    useJUnitPlatform()
}
