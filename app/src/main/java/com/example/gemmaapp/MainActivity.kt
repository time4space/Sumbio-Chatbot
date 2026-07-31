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
            var responseText by remember { mutableStateOf("질문을 입력하거나 말씀해주세요.") }
            var isLoading by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()

            val modelFile = File(filesDir, "gemma-2b-it-gpu-int4.bin")

            // 🌟 수정된 핵심 기능: 스마트폰에서 수동 다운로드한 파일을 찾아 앱 안으로 복사하기 🌟
            val filePickerLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                if (uri != null) {
                    isCopying = true
                    statusText = "파일을 앱으로 옮기는 중입니다... (약 1~2분 소요)"
                    
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
                                isReady = true
                                statusText = "복사 완료! 이제 완벽하게 작동합니다."
                                initEngine(modelFile.absolutePath)
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
                    statusText = "먼저 인공지능 파일(.bin)을 다운로드해주세요."
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
                    if (isCopying) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    } else {
                        Button(
                            onClick = { filePickerLauncher.launch("*/*") }, // 모든 파일 찾기 창 띄우기
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("다운받은 인공지능 파일 찾아서 넣기")
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
                        tts?.stop()

                        scope.launch(Dispatchers.IO) {
                            val result = llmInference?.generateResponse(inputText) ?: "앗, 다시 물어봐주세요!"
                            withContext(Dispatchers.Main) {
                                responseText = result
                                isLoading = false
                                tts?.speak(result, TextToSpeech.QUEUE_FLUSH, null, "response_id")
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

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
