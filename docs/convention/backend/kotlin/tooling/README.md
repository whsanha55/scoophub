# Tooling

| 파일 | 위치 | 용도 |
|---|---|---|
| `.editorconfig` | 프로젝트 루트 | ktlint 포맷 규칙, IDE 설정 |
| `detekt.yml` | `config/detekt/detekt.yml` | 정적 분석 규칙 (`!!`, `now()`, `println` 금지 등) |

## build.gradle.kts

```kotlin
plugins {
    kotlin("jvm") version "<kotlin-version>"
    kotlin("plugin.spring") version "<kotlin-version>"
    kotlin("plugin.jpa") version "<kotlin-version>"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
}

dependencies {
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(module = "mockito-core")
    }
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("com.ninja-squad:springmockk:4.0.2")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// Entity의 protected set과 지연 로딩 프록시를 위해 open으로 만든다.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom("$rootDir/config/detekt/detekt.yml")
}
```

버전은 적용 시점의 최신 안정 버전을 확인해 맞춘다.

## 실행

```text
./gradlew ktlintCheck      # 포맷 검사 (ktlintFormat으로 자동 수정)
./gradlew detektMain       # 정적 분석 (타입 해석 포함)
./gradlew test
./gradlew build
```

CI는 포맷 검사, 정적 분석, 테스트, 빌드 순서로 실행한다.
