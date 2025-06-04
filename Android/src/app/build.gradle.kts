/*
 * Copyright 2025 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.kotlin.serialization)
import java.util.Properties
import java.io.FileInputStream
import org.gradle.api.Project

}

// Function to get property from local.properties or environment variable
fun getApiKey(propertyKey: String, project: Project): String {
    val localProperties = Properties()
    val localPropertiesFile = project.rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localProperties.load(FileInputStream(localPropertiesFile))
        val key = localProperties.getProperty(propertyKey)
        if (key != null) return key
    }
    // Fallback to environment variable if not found in local.properties
    // (You might not need this fallback for this specific case, but it's a common pattern)
    // return System.getenv(propertyKey.toUpperCase().replace('.', '_')) ?: ""
    return "" // Return empty string if key is not found, build will fail if it's required later
}

android {
  namespace = "com.google.ai.edge.gallery"
  compileSdk = 35

  defaultConfig {
    // Don't change to com.google.ai.edge.gallery yet.
    applicationId = "com.google.aiedge.gallery"
    minSdk = 26
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.3"

    // Needed for HuggingFace auth workflows.
    manifestPlaceholders["appAuthRedirectScheme"] = "com.google.ai.edge.gallery.oauth"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    // vectorDrawables { // This block seems to be missing in the original, adding it as per example if needed.
    //    useSupportLibrary = true
    // }

    // Define TAVILY_API_KEY as a BuildConfig field
    // "tavily.apiKey" is the key name in local.properties
    val tavilyApiKey = getApiKey("tavily.apiKey", project)
    if (tavilyApiKey.isEmpty()) {
        // Optionally, throw an error if the API key is mandatory for the build
        // throw GradleException("Tavily API Key (tavily.apiKey) not found in local.properties. Please add it.")
        // Or allow build to continue with an empty key, but app might not function fully.
        println("Warning: Tavily API Key (tavily.apiKey) not found in local.properties. Web search functionality will not work.")
    }
    buildConfigField("String", "TAVILY_API_KEY", "\"${tavilyApiKey}\"")
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      signingConfig = signingConfigs.getByName("debug")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  kotlinOptions {
    jvmTarget = "11"
    freeCompilerArgs += "-Xcontext-receivers"
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
}

dependencies {
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.ui)
  implementation(libs.androidx.ui.graphics)
  implementation(libs.androidx.ui.tooling.preview)
  implementation(libs.androidx.material3)
  implementation(libs.androidx.compose.navigation)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.material.icon.extended)
  implementation(libs.androidx.work.runtime)
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.com.google.code.gson)
  implementation(libs.androidx.lifecycle.process)
  implementation(libs.mediapipe.tasks.text)
  implementation(libs.mediapipe.tasks.genai)
  implementation(libs.mediapipe.tasks.imagegen)
  implementation(libs.commonmark)
  implementation(libs.richtext)
  implementation(libs.tflite)
  implementation(libs.tflite.gpu)
  implementation(libs.tflite.support)
  implementation(libs.camerax.core)
  implementation(libs.camerax.camera2)
  implementation(libs.camerax.lifecycle)
  implementation(libs.camerax.view)
  implementation(libs.openid.appauth)
  implementation(libs.androidx.splashscreen)
  implementation("com.squareup.okhttp3:okhttp:4.12.0")
  testImplementation(libs.junit)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.ui.test.junit4)
  debugImplementation(libs.androidx.ui.tooling)
  debugImplementation(libs.androidx.ui.test.manifest)
}
