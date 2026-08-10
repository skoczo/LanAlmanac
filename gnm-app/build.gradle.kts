plugins {
    java
    id("io.quarkus") version "3.38.0"
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
    implementation(enforcedPlatform("io.quarkus.platform:quarkus-bom:3.38.1"))
    
    // REST API & JSON
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    
    // WebSocket
    implementation("io.quarkus:quarkus-websockets-next")
    
    // Auth & OIDC
    implementation("io.quarkus:quarkus-smallrye-jwt")
    implementation("io.quarkus:quarkus-security-jpa")
    implementation("io.quarkus:quarkus-smallrye-jwt-build")
    implementation("io.quarkus:quarkus-oidc")
    
    // Database & Hibernate
    implementation("io.quarkus:quarkus-hibernate-orm-panache")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    
    // Redis & Scheduler
    implementation("io.quarkus:quarkus-redis-client")
    implementation("io.quarkus:quarkus-scheduler")
    
    // Networking (pcap4j)
    implementation("org.pcap4j:pcap4j-core:1.8.2")
    implementation("org.pcap4j:pcap4j-packetfactory-static:1.8.2")
    
    // SNMP support for Network Topology Mapping
    implementation("org.snmp4j:snmp4j:3.7.7")
    
    // Cryptography
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    
    // SSH (Remote Access)
    implementation("org.apache.sshd:sshd-core:2.13.1")
    
    // Testing
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.quarkus:quarkus-test-security")
    testImplementation("io.quarkus:quarkus-jacoco")
    testImplementation("org.testcontainers:testcontainers:1.20.1")
    testImplementation("org.testcontainers:junit-jupiter:1.20.1")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}
