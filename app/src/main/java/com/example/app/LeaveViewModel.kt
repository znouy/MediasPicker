package com.example.app


import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mediapicker.engine.compress.ImageCompressEngine
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

sealed interface LeaveUiEvent {
    data object SubmitSuccess : LeaveUiEvent
}

class LeaveViewModel : ViewModel() {

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting = _isSubmitting.asStateFlow()

    private val _uiEvent = MutableSharedFlow<LeaveUiEvent>()
    val uiEvent = _uiEvent.asSharedFlow()

    val leaveType = MutableStateFlow("事假")
    val startTime = MutableStateFlow("")
    val endTime = MutableStateFlow("")
    val images = MutableStateFlow<List<Uri>>(emptyList())

    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    init {
        // 初始化当前时间为默认起始时间
        val currentStr = timeFormat.format(Date())
        startTime.value = currentStr
        endTime.value = currentStr
    }

    fun addImages(newUris: List<Uri>, maxLimit: Int) {
        val current = images.value.toMutableList()
        for (uri in newUris) {
            if (current.size < maxLimit /*&& !current.contains(uri)*/) {
                current.add(uri)
            }
        }
        images.value = current
    }

    fun removeImage(index: Int) {
        val current = images.value.toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            images.value = current
        }
    }

    fun submitLeave(reason: String) {
        /*  val reasonTrimmed = reason.trim()

          // 1. 表单严格合法性校验
          if (reasonTrimmed.isEmpty()) {
              emitMessage("请输入请假事由")
              return
          }

          try {
              val start = timeFormat.parse(startTime.value)?.time ?: 0L
              val end = timeFormat.parse(endTime.value)?.time ?: 0L
              if (end < start) {
                  emitMessage("结束时间不能早于开始时间")
                  return
              }
          } catch (e: Exception) {
              emitMessage("时间格式有误，请重新选择")
              return
          }

          if (images.value.isEmpty()) {
              emitMessage("请至少上传1张证明图片")
              return
          }

          // 2. 协程安全提交
          viewModelScope.launch {
              coRunCatching(_isSubmitting) {
                  // 模拟网络 Multipart/Form-Data 上传
                  delay(1500)
              }
                  .logOnFailure("LeaveViewModel")
                  .mapNetworkException()
                  .onSuccess {
                      emitMessage("请假申请提交成功")
                      _uiEvent.emit(LeaveUiEvent.SubmitSuccess)
                  }
                  .onFailure { exception ->
                      emitMessage(exception.message ?: "提交失败，请重试")
                  }
          }*/
    }

    fun submitForm(context: Context) {
        viewModelScope.launch {
            val compressedUris = ImageCompressEngine.compressBatch(
                context = context,
                uris = images.value
            )
            compressedUris.forEach { Log.e("",it.toString()) }
        }
    }
}