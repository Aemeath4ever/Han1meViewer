package com.yenaly.han1meviewer.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.yenaly.han1meviewer.logic.DatabaseRepo
import com.yenaly.han1meviewer.logic.entity.LocalFavoriteEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LocalFavoritesViewModel(application: Application) : AndroidViewModel(application) {

    val favorites: StateFlow<List<LocalFavoriteEntity>> =
        DatabaseRepo.LocalFavorite.observeAll()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptyList(),
            )

    fun update(item: LocalFavoriteEntity, rating: Int, comment: String) {
        viewModelScope.launch {
            DatabaseRepo.LocalFavorite.updateRating(item, rating, comment)
        }
    }

    fun delete(item: LocalFavoriteEntity) {
        viewModelScope.launch {
            DatabaseRepo.LocalFavorite.delete(item)
        }
    }
}
