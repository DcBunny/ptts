package com.example.ptts.features.jump_session.presentation

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ptts.features.parent_camera.data.JumpRecordRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class JumpSessionViewModel(
    application: Application,
) : ViewModel() {
    private val repository = JumpRecordRepository(application)

    val bestRecord: StateFlow<Int> = repository.bestRecord
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0,
        )

    class Factory(
        private val application: Application,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return JumpSessionViewModel(application) as T
        }
    }
}
