package com.nexo.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nexo.domain.model.Book
import com.nexo.domain.model.ReadingProgress
import com.nexo.domain.repository.LibraryRepository
import com.nexo.domain.session.ActiveUserProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

data class BookDetailUiState(
    val book: Book? = null,
    val readingProgress: ReadingProgress? = null,
    val isLoading: Boolean = true,
)

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModel(
    private val bookId: String,
    private val libraryRepository: LibraryRepository,
    private val activeUser: ActiveUserProvider,
) : ViewModel() {
    private val _uiState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            activeUser.userId
                .flatMapLatest { userId ->
                    val id = userId.orEmpty()
                    libraryRepository
                        .observeBookById(bookId, id)
                        .combine(libraryRepository.observeProgressForBook(bookId, id)) { book, progress ->
                            BookDetailUiState(
                                book = book,
                                readingProgress = progress,
                                isLoading = false,
                            )
                        }
                }.collect { state ->
                    _uiState.value = state
                }
        }
    }

    fun updateRating(rating: Int?) {
        viewModelScope.launch {
            libraryRepository.updateBookRating(bookId, activeUser.requireUserId(), rating)
        }
    }

    fun getCurrentPageDisplay(): String {
        val progress = _uiState.value.readingProgress
        val book = _uiState.value.book
        val page = progress?.currentPage
        val total = book?.totalPages
        return when {
            page != null && total != null -> "Page $page / $total"
            page != null -> "Page $page"
            total != null -> "0 / $total"
            else -> "-- / --"
        }
    }

    class Factory(
        private val bookId: String,
        private val libraryRepository: LibraryRepository,
        private val activeUser: ActiveUserProvider,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BookDetailViewModel(bookId, libraryRepository, activeUser) as T
    }
}
