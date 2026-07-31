package com.example.gemmaapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : ComponentActivity() {
    private var llmInference: LlmInference? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            var statusText by remember { mutableStateOf("상태 확인 중...") }
            var isReady by remember { mutableStateOf(false) }
            var isDownloading by remember { mutableStateOf(false) }
            var progressText by remember { mutableStateOf("") }
            var inputText by remember { mutableStateOf("") }
            var responseText by remember { mutableStateOf("질문을 입력하세요.") }
            var isLoading by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            val modelFile = File(filesDir, "gemma-2b-it-gpu-int4.bin")

            LaunchedEffect(Unit) {
                if (modelFile.exists() && modelFile.length() > 0) {
                    statusText = "준비 완료! (인터넷 없이도 작동합니다)"
                    isReady = true
                    initEngine(modelFile.absolutePath)
                } else {
                    statusText = "처음 1번, AI 머리(모델)를 다운받아야 합니다."
                }
            }

            Column(modifier = Modifier.padding(20.dp).fillMaxSize()) {
                Text(text = "내 폰 안의 인공지능", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = statusText, 
                    color = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                
                if (!isReady) {
                    Spacer(modifier = Modifier.height(12.dp))
                    if (isDownloading) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(text = progressText, style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(
                            onClick = {
                                isDownloading = true
                                scope.launch(Dispatchers.IO) {
                                    val modelUrl = "https://huggingface.co/google/gemma-2b-it-gpu-int4/resolve/main/gemma-2b-it-gpu-int4.bin"
                                    try {
                                        downloadFile(modelUrl, modelFile) { downloaded, total ->
                                            val mbDownloaded = downloaded / (1024 * 1024)
                                            val mbTotal = total / (1024 * 1024)
                                            scope.launch(Dispatchers.Main) {
                                                progressText = "다운로드 중: ${mbDownloaded}MB / ${mbTotal}MB"
                                            }
                                        }
                                        withContext(Dispatchers.Main) {
                                            isDownloading = false
                                            isReady = true
                                            statusText = "다운로드 끝! 이제 인터넷을 꺼도 됩니다."
                                            initEngine(modelFile.absolutePath)
                                        }
                                    } catch (e: Exception) {
                                        withContext(Dispatchers.Main) {
                                            isDownloading = false
                                            statusText = "실패했어요: ${e.message}"
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("여기를 눌러서 인공지능 다운받기")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                Divider()
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("여기에 질문을 적어주세요") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isReady
                )

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (llmInference == null) return@Button
                        isLoading = true
                        responseText = "생각하는 중..."

                        scope.launch(Dispatchers.IO) {
                            val result = llmInference?.generateResponse(inputText) ?: "앗, 다시 물어봐주세요!"
                            withContext(Dispatchers.Main) {
                                responseText = result
                                isLoading = false
                            }
                        }
                    },
                    enabled = isReady && !isLoading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isLoading) "기다려주세요..." else "질문 보내기")
                }

                Spacer(modifier = Modifier.height(20.dp))

                Card(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Text(
                        text = responseText,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }

    private fun initEngine(path: String) {
        try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(512)
                .build()
            llmInference = LlmInference.createFromOptions(this, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun downloadFile(urlString: String, outputFile: File, onProgress: (Long, Long) -> Unit) {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connect()
        val fileLength = connection.contentLengthLong
        val input = connection.inputStream
        val output = FileOutputStream(outputFile)
        val data = ByteArray(4096)
        var total: Long = 0
        var count: Int
        while (input.read(data).also { count = it } != -1) {
            total += count.toLong()
            if (fileLength > 0) onProgress(total, fileLength)
            output.write(data, 0, count)
        }
        output.flush()
        output.close()
        input.close()
    }
}
