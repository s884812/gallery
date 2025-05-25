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

package com.google.ai.edge.gallery.ui.llmchat

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.ConfigKey
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.TASK_LLM_CHAT
import com.google.ai.edge.gallery.data.TASK_LLM_ASK_IMAGE
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.WebSearchService
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageBenchmarkLlmResult
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageLoading
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageType
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageWarning
import com.google.ai.edge.gallery.ui.common.chat.ChatSide
import com.google.ai.edge.gallery.ui.common.chat.ChatViewModel
import com.google.ai.edge.gallery.ui.common.chat.Stat
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "AGLlmChatViewModel"
private val STATS = listOf(
  Stat(id = "time_to_first_token", label = "1st token", unit = "sec"),
  Stat(id = "prefill_speed", label = "Prefill speed", unit = "tokens/s"),
  Stat(id = "decode_speed", label = "Decode speed", unit = "tokens/s"),
  Stat(id = "latency", label = "Latency", unit = "sec")
)

open class LlmChatViewModel(
  curTask: Task = TASK_LLM_CHAT,
  private val webSearchService: WebSearchService
) : ChatViewModel(task = curTask) {
  fun generateResponse(model: Model, input: String, image: Bitmap? = null, onError: () -> Unit) {
    val accelerator = model.getStringConfigValue(key = ConfigKey.ACCELERATOR, defaultValue = "")
    viewModelScope.launch(Dispatchers.Default) {
      // Web Search Logic
      var augmentedInput = input
      var searchPerformed = false
      var searchSuccessful = false
      var searchErrorOccurred = false

      // Add search in-progress indicator
      val searchIndicatorMessage = ChatMessageLoading(
          text = "正在為您搜索網路獲取最新資訊...",
          accelerator = accelerator,
          side = ChatSide.AGENT
      )
      addMessage(model = model, message = searchIndicatorMessage)

      try {
        val tavilyResponse = webSearchService.search(apiKey = "YOUR_TAVILY_API_KEY_PLACEHOLDER", query = input)
        searchPerformed = true

        if (tavilyResponse != null) {
          searchSuccessful = true
          val searchAnswer = tavilyResponse.answer
          val searchResults = tavilyResponse.results

          if (!searchAnswer.isNullOrBlank()) {
            augmentedInput = "Based on web search results, answer the following: \"${searchAnswer}\". The original question was: \"${input}\""
          } else if (!searchResults.isNullOrEmpty()) {
            val snippets = searchResults.take(2).joinToString(separator = "; ") { it.content }
            if (snippets.isNotBlank()) {
              augmentedInput = "Based on web search results, here are some relevant snippets: \"${snippets}\". The original question was: \"${input}\""
            }
          }
        } else {
          searchErrorOccurred = true
        }
      } catch (e: Exception) {
        Log.e(TAG, "Web search call failed", e)
        searchErrorOccurred = true
      }

      // Remove search in-progress indicator
      val lastMessage = getLastMessage(model = model)
      if (lastMessage == searchIndicatorMessage) {
          removeLastMessage(model = model)
      }

      // Add search result status messages
      if (searchErrorOccurred) {
          addMessage(
              model = model,
              message = ChatMessageWarning(content = "網路搜索失敗，將嘗試使用模型知識回答。")
          )
      } else if (searchPerformed && !searchSuccessful) {
          addMessage(
              model = model,
              message = ChatMessageWarning(content = "網路搜索未能找到相關資訊，將嘗試使用模型知識回答。")
          )
      }

      setInProgress(true)
      setPreparing(true)

      // Loading.
      addMessage(
        model = model,
        message = ChatMessageLoading(accelerator = accelerator),
      )

      // Wait for instance to be initialized.
      while (model.instance == null) {
        delay(100)
      }
      delay(500)

      // Run inference.
      val instance = model.instance as LlmModelInstance
      var prefillTokens = instance.session.sizeInTokens(augmentedInput)
      if (image != null) {
        // Assuming image context is added separately and not part of the text prompt for token calculation here.
        // If image contributes to text prompt for LLM, this might need adjustment or be handled by the model instance.
        prefillTokens += 257 // This is a magic number, ensure it's correct for multimodal inputs.
      }

      var firstRun = true
      var timeToFirstToken = 0f
      var firstTokenTs = 0L
      var decodeTokens = 0
      var prefillSpeed = 0f
      var decodeSpeed: Float
      val start = System.currentTimeMillis()

      try {
        LlmChatModelHelper.runInference(model = model,
          input = augmentedInput, // Use augmentedInput here
          image = image,
          resultListener = { partialResult, done ->
            val curTs = System.currentTimeMillis()

            if (firstRun) {
              firstTokenTs = System.currentTimeMillis()
              timeToFirstToken = (firstTokenTs - start) / 1000f
              prefillSpeed = prefillTokens / timeToFirstToken
              firstRun = false
              setPreparing(false)
            } else {
              decodeTokens++
            }

            // Remove the last message if it is a "loading" message.
            // This will only be done once.
            val lastMessage = getLastMessage(model = model)
            if (lastMessage?.type == ChatMessageType.LOADING) {
              removeLastMessage(model = model)

              // Add an empty message that will receive streaming results.
              addMessage(
                model = model,
                message = ChatMessageText(
                  content = "",
                  side = ChatSide.AGENT,
                  accelerator = accelerator
                )
              )
            }

            // Incrementally update the streamed partial results.
            val latencyMs: Long = if (done) System.currentTimeMillis() - start else -1
            updateLastTextMessageContentIncrementally(
              model = model, partialContent = partialResult, latencyMs = latencyMs.toFloat()
            )

            if (done) {
              setInProgress(false)

              decodeSpeed = decodeTokens / ((curTs - firstTokenTs) / 1000f)
              if (decodeSpeed.isNaN()) {
                decodeSpeed = 0f
              }

              if (lastMessage is ChatMessageText) {
                updateLastTextMessageLlmBenchmarkResult(
                  model = model, llmBenchmarkResult = ChatMessageBenchmarkLlmResult(
                    orderedStats = STATS,
                    statValues = mutableMapOf(
                      "prefill_speed" to prefillSpeed,
                      "decode_speed" to decodeSpeed,
                      "time_to_first_token" to timeToFirstToken,
                      "latency" to (curTs - start).toFloat() / 1000f,
                    ),
                    running = false,
                    latencyMs = -1f,
                    accelerator = accelerator,
                  )
                )
              }
            }
          },
          cleanUpListener = {
            setInProgress(false)
            setPreparing(false)
          })
      } catch (e: Exception) {
        Log.e(TAG, "Error occurred while running inference", e)
        setInProgress(false)
        setPreparing(false)
        onError()
      }
    }
  }

  fun stopResponse(model: Model) {
    Log.d(TAG, "Stopping response for model ${model.name}...")
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }
    viewModelScope.launch(Dispatchers.Default) {
      setInProgress(false)
      val instance = model.instance as LlmModelInstance
      instance.session.cancelGenerateResponseAsync()
    }
  }

  fun resetSession(model: Model) {
    viewModelScope.launch(Dispatchers.Default) {
      setIsResettingSession(true)
      clearAllMessages(model = model)
      stopResponse(model = model)

      while (true) {
        try {
          LlmChatModelHelper.resetSession(model = model)
          break
        } catch (e: Exception) {
          Log.d(TAG, "Failed to reset session. Trying again")
        }
        delay(200)
      }
      setIsResettingSession(false)
    }
  }

  fun runAgain(model: Model, message: ChatMessageText, onError: () -> Unit) {
    viewModelScope.launch(Dispatchers.Default) {
      // Wait for model to be initialized.
      while (model.instance == null) {
        delay(100)
      }

      // Clone the clicked message and add it.
      addMessage(model = model, message = message.clone())

      // Run inference.
      generateResponse(
        model = model, input = message.content, onError = onError
      )
    }
  }

  fun handleError(
    context: Context,
    model: Model,
    modelManagerViewModel: ModelManagerViewModel,
    triggeredMessage: ChatMessageText,
  ) {
    // Clean up.
    modelManagerViewModel.cleanupModel(task = task, model = model)

    // Remove the "loading" message.
    if (getLastMessage(model = model) is ChatMessageLoading) {
      removeLastMessage(model = model)
    }

    // Remove the last Text message.
    if (getLastMessage(model = model) == triggeredMessage) {
      removeLastMessage(model = model)
    }

    // Add a warning message for re-initializing the session.
    addMessage(
      model = model,
      message = ChatMessageWarning(content = "Error occurred. Re-initializing the session.")
    )

    // Add the triggered message back.
    addMessage(model = model, message = triggeredMessage)

    // Re-initialize the session/engine.
    modelManagerViewModel.initializeModel(
      context = context, task = task, model = model
    )

    // Re-generate the response automatically.
    // The original triggeredMessage.content will go through the search logic again.
    generateResponse(model = model, input = triggeredMessage.content, onError = {})
  }
}

class LlmAskImageViewModel(webSearchService: WebSearchService) :
  LlmChatViewModel(curTask = TASK_LLM_ASK_IMAGE, webSearchService = webSearchService)