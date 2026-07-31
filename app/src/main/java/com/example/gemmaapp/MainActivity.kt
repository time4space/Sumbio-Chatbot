package com.example.gemmaapp

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var llmInference: LlmInference? = null
    private var tts: TextToSpeech? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.KOREAN)
            }
        }

        setContent {
            var statusText by remember { mutableStateOf("상태 확인 중...") }
            var isReady by remember { mutableStateOf(false) }
            var isCopying by remember { mutableStateOf(false) }
            var inputText by remember { mutableStateOf("") }
            var responseText by remember { mutableStateOf("마이크를 누르고 말씀해주세요.") }
            var isLoading by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            // 🌟 파일 이름을 Gemma 4 전용으로 깔끔하게 정리 🌟
            val modelFile = File(filesDir, "gemma4_model.task")

            val askAi: (String) -> Unit = { query ->
                if (llmInference == null) {
                    responseText = "오류: Gemma 4 엔진이 로딩되지 않았습니다."
                } else if (query.isNotBlank()) {
                    isLoading = true
                    responseText = "생각하는 중..."
                    tts?.stop()

                    scope.launch(Dispatchers.IO) {
                        try {
                            val result = llmInference?.generateResponse(query) ?: "대답을 생성하지 못했습니다."
                            withContext(Dispatchers.Main) {
                                responseText = result
                                isLoading = false
                                tts?.speak(result, TextToSpeech.QUEUE_FLUSH, null, "response_id")
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                responseText = "에러 발생: ${e.message}"
                                isLoading = false
                            }
                        }
                    }
                }
            }

            val speechLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val data = result.data
                    val matches = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                    if (!matches.isNullOrEmpty()) {
                        inputText = matches[0]
                        askAi(inputText)
                    }
                }
            }

            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri != null) {
                    isCopying = true
                    statusText = "Gemma 4 파일을 앱으로 옮기는 중입니다... (약 1~2분 소요)"
                    
                    scope.launch(Dispatchers.IO) {
                        try {
                            contentResolver.openInputStream(uri)?.use { input ->
                                FileOutputStream(modelFile).use { output ->
                                    val buffer = ByteArray(16384)
                                    var length: Int
                                    while (input.read(buffer).also { length = it } > 0) {
                                        output.write(buffer, 0, length)
                                    }
                                }
                            }
                            withContext(Dispatchers.Main) {
                                isCopying = false
                                val resultMsg = initEngine(modelFile.absolutePath)
                                statusText = resultMsg
                                isReady = llmInference != null
                            }
                        } catch (e: Exception) {
                            withContext(Dispatchers.Main) {
                                isCopying = false
                                statusText = "파일 옮기기 실패: ${e.message}"
                            }
                        }
                    }
                }
            }

            LaunchedEffect(Unit) {
                if (modelFile.exists() && modelFile.length() > 0) {
                    statusText = "Gemma 4 엔진 로딩 중..."
                    val resultMsg = initEngine(modelFile.absolutePath)
                    statusText = resultMsg
                    isReady = llmInference != null
                } else {
                    statusText = "먼저 Gemma 4 모델 파일(.task)을 찾아 넣어주세요."
                }
            }

            Column(modifier = Modifier.padding(20.dp).fillMaxSize()) {
                Text(text = "Gemma 4 온디바이스 스피커", style = MaterialTheme.typography.headlineSmall)
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = statusText, 
                    color = if (isReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                
                if (!isReady) {
                    Spacer(modifier = Modifier.height(12.dp))
                    if (isCopying) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("다운받은 Gemma 4 모델 찾아 넣기")
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
                        enabled = isReady,
                        keyboardOptions = KeyboardOptions.Default.copy(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { askAi(inputText) })
                    )
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            tts?.stop() 
                            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
                                putExtra(RecognizerIntent.EXTRA_PROMPT, "질문을 말씀해주세요...")
                            }
                            try {
                                speechLauncher.launch(intent)
                            } catch (e: Exception) {
                                // Ignore
                            }
                        },
                        enabled = isReady,
                        shape = MaterialTheme.shapes.large,
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        Text("🎤", style = MaterialTheme.typography.titleLarge)
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                if (isLoading) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(8.dp))
                }

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

    private fun initEngine(path: String): String {
        return try {
            val options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(path)
                .setMaxTokens(512) // 대답의 최대 길이 제한 (메모리 절약)
                .build()
            llmInference = LlmInference.createFromOptions(this, options)
            "준비 완료! (인터넷 없이도 작동합니다)"
        } catch (e: Exception) {
            e.printStackTrace()
            "엔진 로딩 실패: 지원하지 않는 모델 형식이거나 기기 메모리가 부족합니다. (${e.message})"
        }
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
