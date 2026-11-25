import com.github.gradle.node.yarn.task.YarnTask
import com.github.gradle.node.npm.task.NpmTask

plugins {
    java
    id("com.github.node-gradle.node") version("7.1.0")
}

// No Java sources: the jar only carries the bundle. The toolchain matches the other subprojects so
// :gateway (Java 17) can depend on it whatever JVM runs Gradle.
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

// Path to the generated-resources folder; webpack writes the UMD bundle into mounted/ below this.
val projectOutput = layout.buildDirectory.dir("generated-resources")

node {
    version.set("24.22.3")
    yarnVersion.set("1.22.22")
    npmVersion.set("12.2.3")
    download.set(true)
    nodeProjectDir.set(file(project.projectDir))
}

// Install npm dependencies via yarn.
val yarnPackages = tasks.register<YarnTask>("yarnPackages") {
    description = "Runs 'yarn install' at web-ui/ to install npm dependencies."
    args.set(listOf("install", "--verbose"))

    inputs.files(
        fileTree(project.projectDir).matching {
            include("**/package.json", "**/yarn.lock")
        }
    )
    outputs.dirs(file("node_modules"))

    dependsOn("${project.path}:yarn", ":web-ui:npmSetup")
}

// Build the React bundle with webpack.
val webpack = tasks.register<NpmTask>("webpack") {
    group = "Ignition Module"
    description = "Runs 'npm run build-dev' (webpack) to build the Config Versioning page bundle."
    args.set(listOf("run", "build-dev"))

    dependsOn(yarnPackages)

    inputs.files(project.fileTree(project.projectDir).matching {
        exclude("**/node_modules/**", "**/dist/**", "**/.awcache/**", "**/yarn-error.log")
    }.toList())

    outputs.dir(projectOutput)
}

tasks {
    processResources {
        dependsOn(webpack, yarnPackages)
    }
}

tasks.register<Delete>("deepClean") {
    delete(".gradle", "node_modules")
    dependsOn(tasks.named("clean"))
}

// The gateway jar must include the built bundle, so block its processResources on webpack.
project(":gateway")?.tasks?.named("processResources")?.configure {
    dependsOn(webpack)
}

sourceSets {
    main {
        output.dir(projectOutput, "builtBy" to listOf(webpack))
    }
}
