plugins {
    kotlin("jvm")
    id("com.gradleup.shadow")
    id("xyz.jpenilla.run-velocity")
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

val velocityVersion = "4.2.0"

dependencies {
    // Velocity 가 Gson, Configurate(YAML), Adventure/MiniMessage, slf4j 를 제공한다.
    compileOnly("com.velocitypowered:velocity-api:$velocityVersion")
    compileOnly("io.github.miniplaceholders:miniplaceholders-api:3.2.0")

    implementation(kotlin("stdlib"))
    implementation("redis.clients:jedis:8.0.1") {
        // Velocity 에 이미 있으므로 jar 에 넣지 않는다.
        exclude(group = "org.slf4j")
        exclude(group = "com.google.code.gson")
    }

    testImplementation(kotlin("test"))
    testImplementation("com.velocitypowered:velocity-api:$velocityVersion")
    testImplementation("com.github.codemonstur:embedded-redis:1.4.4")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
}

tasks {
    jar {
        archiveClassifier.set("plain")
    }

    shadowJar {
        archiveBaseName.set("VelocityPlayerSync")
        archiveClassifier.set("")
        mergeServiceFiles()
        // Kotlin 컴파일러용 메타데이터라 실행에는 필요 없고, 여러 벌이 겹쳐 경고만 난다.
        exclude("META-INF/versions/*/module-info.class", "module-info.class", "META-INF/*.kotlin_module")

        // 다른 플러그인이 가진 같은 라이브러리와 충돌하지 않도록 전부 재배치한다.
        val libs = "kr.hirest.velocityplayersync.libs"
        relocate("kotlin", "$libs.kotlin")
        relocate("org.intellij", "$libs.intellij")
        relocate("org.jetbrains.annotations", "$libs.jbannotations")
        relocate("redis.clients", "$libs.redis")
        relocate("org.apache.commons.pool2", "$libs.pool2")
        relocate("org.json", "$libs.json")
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("velocity-plugin.json") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "failed", "skipped")
            showStandardStreams = false
        }
    }

    runVelocity {
        velocityVersion(velocityVersion)
    }
}
