plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    // 메타데이터 확인과 플러그인 메시지 전송만 하므로 1.21.4 API 로 빌드해 1.21.x ~ 26.x 에서 모두 쓸 수 있게 한다.
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    implementation(kotlin("stdlib"))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveBaseName.set("VelocityPlayerSync-Bridge")
        archiveClassifier.set("")
        // Kotlin 컴파일러용 메타데이터라 실행에는 필요 없고, 여러 벌이 겹쳐 경고만 난다.
        exclude("META-INF/versions/*/module-info.class", "module-info.class", "META-INF/*.kotlin_module")
        // Bridge 는 Kotlin stdlib 중 극히 일부만 쓰므로 사용하지 않는 클래스는 뺀다.
        minimize()

        val libs = "kr.hirest.velocityplayersync.bridge.libs"
        relocate("kotlin", "$libs.kotlin")
        relocate("org.intellij", "$libs.intellij")
        relocate("org.jetbrains.annotations", "$libs.jbannotations")
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }
}
