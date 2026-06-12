plugins {
    id("org.jetbrains.kotlin.jvm")
}

dependencies {
    // JSON parsing
    implementation("com.google.code.gson:gson:2.11.0")

    // HTTP Client
    implementation("org.apache.httpcomponents:httpclient:4.5.14")
    implementation("org.apache.httpcomponents:httpmime:4.5.14")

    // JWT for token parsing
    implementation("com.auth0:java-jwt:4.4.0")
}
