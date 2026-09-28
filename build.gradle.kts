plugins {
    kotlin("jvm") version "2.4.20" apply false
    id("com.gradleup.shadow") version "9.6.1" apply false
    id("xyz.jpenilla.run-velocity") version "3.1.0" apply false
}

allprojects {
    group = "kr.hirest"
    version = "1.0.0"
}

// collectJars 가 하위 모듈의 shadowJar 태스크를 참조하므로 하위 모듈을 먼저 설정한다.
evaluationDependsOnChildren()

// 두 모듈의 최종 jar 를 루트 build/libs 로 모은다.
val collectJars = tasks.register<Copy>("collectJars") {
    from(project(":velocity").tasks.named("shadowJar"))
    from(project(":bridge").tasks.named("shadowJar"))
    into(layout.buildDirectory.dir("libs"))
}

tasks.register("build") {
    group = "build"
    dependsOn(":velocity:build", ":bridge:build", collectJars)
}
