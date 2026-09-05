plugins {
    id("java")
    id("eclipse")
}

allprojects {
    apply(plugin = "eclipse")
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

