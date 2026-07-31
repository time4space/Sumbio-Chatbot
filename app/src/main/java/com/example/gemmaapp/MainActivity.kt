package com.example.gemmaapp

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
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
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var llmInference: LlmInference? = null
    // 🌟 글자를 목소리로 읽어주는 도구 준비 🌟
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 🌟 안드로이드 기본 목소리(한국어) 설정 🌟
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            }
        }

        setContent {
            var statusText by remember { mutableStateOf("상태 확인 중...") }
            var isReady by remember { mutableStateOf(false) }
            var isDownloading by remember { mutableStateOf(false) }
            var progressText by remember { mutableStateOf("") }
            var inputText by remember { mutableStateOf("") }
            var responseText by remember { mutableStateOf("질문을 입력하거나 말씀해주세요.") }
            var isLoading by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            val modelFile = File(filesDir, "gemma-2b-it-gpu-int4.bin")

            val speechLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val data = result.data
                    val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    if (!matches.isNullOrEmpty()) {
                        inputText = matches[0]
                    }
                }
            }

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
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        label = { Text("여기에 질문을 적어주세요") },
                        modifier = Modifier.weight(1f),
                        enabled = isReady
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            // 질문할 때 인공지능이 말하고 있던 게 있다면 조용히 시키기
                            tts?.stop() 
                            
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "질문을 말씀해주세요...")
                            }
                            try {
                                speechLauncher.launch(intent)
                            } catch (e: Exception) {
                                // 구글 앱(음성인식)이 없는 경우 대비
                            }
                        },
                        enabled = isReady
                    ) {
                        Text("🎤")
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {
                        if (llmInference == null) return@Button
                        isLoading = true
                        responseText = "생각하는 중..."
                        // 새로 질문 보낼 때도 말 멈추기
                        tts?.stop()

                        scope.launch(Dispatchers.IO) {
                            val result = llmInference?.generateResponse(inputText) ?: "앗, 다시 물어봐주세요!"
                            withContext(Dispatchers.Main) {
                                responseText = result
                                isLoading = false
                                
                                // 🌟 인공지능이 글자를 사람 목소리로 소리 내어 읽어주기! 🌟
                                tts?.speak(result, TextToSpeech.QUEUE_FLUSH, null, null)
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
        var currentUrl = urlString
        var connection: HttpURLConnection
        var redirects = 0
        
        while (true) {
            val url = URL(currentUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.instanceFollowRedirects = false 

            val status = connection.responseCode
            if (status == HttpURLConnection.HTTP_MOVED_TEMP ||
                status == HttpURLConnection.HTTP_MOVED_PERM ||
                status == HttpURLConnection.HTTP_SEE_OTHER) {
                currentUrl = connection.getHeaderField("Location")
                redirects++
                if (redirects > 5) throw Exception("주소 이동이 너무 많습니다.")
                continue
            }
            break
        }

        if (connection.responseCode !in 200..299) {
            throw Exception("서버 연결 실패: ${connection.responseCode}")
        }

        val fileLength = connection.contentLengthLong
        val input = connection.inputStream
        val output = FileOutputStream(outputFile)
        
        val data = ByteArray(16384) 
        var total: Long = 0
        var count: Int
        var lastUpdate: Long = 0

        while (input.read(data).also { count = it } != -1) {
            total += count.toLong()
            output.write(data, 0, count)
            
            if (total - lastUpdate > 1024 * 1024 || total == fileLength) {
                lastUpdate = total
                if (fileLength > 0) {
                    onProgress(total, fileLength)
                }
            }
        }
        
        output.flush()
        output.close()
        input.close()
    }

    // 🌟 앱을 끌 때는 입 다물고 깔끔하게 정리하기 🌟
    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
