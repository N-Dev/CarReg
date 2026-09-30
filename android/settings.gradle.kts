// RoadSight: PlateSight (number-plate reader) and TrafficSight (traffic counter) as one native Android app.
//   core  plain Kotlin: plate formats, voting and tracking, the traffic counter, statistics, and the
//         pre- and post-processing around the AI models. Unit-tested on the JVM with the real models.
//   app   the Android app: camera, ONNX Runtime, screens, storage, reports.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "RoadSight"
include(":core", ":app")
