buildscript {
    repositories {
        jcenter()
        mavenCentral()
        maven("https://dl.bintray.com/kotlin/kotlin-dev")
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.3.70-dev-1416")
    }
}
apply(plugin="kotlin-multiplatform")
repositories {
    jcenter()
    mavenCentral()
}