package com.convoscribe.ui.organise

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.convoscribe.data.SessionRepository
import com.convoscribe.data.TagUsage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Settings › Tags (issue #32): every tag with how many sessions carry
 * it, to rename or delete. Renaming onto another tag's name merges the
 * two ([SessionRepository.renameTag]).
 */
class TagsViewModel(private val repository: SessionRepository) : ViewModel() {

    val tags: StateFlow<List<TagUsage>> = repository.observeTagUsage()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun rename(id: Long, name: String) {
        viewModelScope.launch { repository.renameTag(id, name) }
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.deleteTag(id) }
    }
}
