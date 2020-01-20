import org.jetbrains.kotlin.gradle.dsl.KotlinCompile
import java.io.File

plugins {
    kotlin("jvm")
    id("jps-compatible")
}

dependencies {
    compile(kotlinStdlib())
    testCompile(projectTests(":compiler:visualizer"))
}

sourceSets {
    "main" { }
    "test" { projectDefault() }
}

testsJar {}

projectTest(parallel = true) {
    dependsOn(":dist")
    dependsOn(":kotlin-stdlib-js-ir:generateFullRuntimeKLib")

    workingDir = rootDir
    systemProperty("kotlin.test.script.classpath", testSourceSet.output.classesDirs.joinToString(File.pathSeparator))
}
