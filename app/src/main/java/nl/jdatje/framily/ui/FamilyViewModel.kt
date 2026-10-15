package nl.jdatje.framily.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.jdatje.framily.data.PhotoDb
import nl.jdatje.framily.data.Prefs
import nl.jdatje.framily.family.FamilyIndex
import nl.jdatje.framily.rotation.PoolBuilder
import nl.jdatje.framily.rotation.Rotator

data class FamilyState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val people: List<FamilyIndex.Person> = emptyList(),
    val groups: List<FamilyIndex.Group> = emptyList(),
    val notFamilyFaces: Int = 0,
    /** Photos whose faces still have to be (re)analysed by the scan. */
    val pendingPhotos: Int = 0,
    val familyOnly: Boolean = false,
    val threshold: Float = 0.55f,
    val showGroups: Int = 20,
)

class FamilyViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx = app
    private val prefs = Prefs.get(app)

    private val _state = MutableStateFlow(FamilyState())
    val state: StateFlow<FamilyState> = _state.asStateFlow()

    init {
        reload()
    }

    fun reload() = viewModelScope.launch(Dispatchers.IO) {
        _state.update {
            it.copy(
                loading = false,
                people = FamilyIndex.people(ctx),
                groups = FamilyIndex.openGroups(ctx, it.showGroups),
                notFamilyFaces = FamilyIndex.notFamilyFaces(ctx),
                pendingPhotos = PhotoDb.get(ctx).missingEmbeddings(prefs.selectedBuckets).size,
                familyOnly = prefs.familyOnly,
                threshold = prefs.familyThreshold,
            )
        }
    }

    fun showMoreGroups() {
        _state.update { it.copy(showGroups = it.showGroups + 20) }
        reload()
    }

    /** Run a change, re-match faces, and update the screen and the rotation. */
    private fun change(block: () -> Unit) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch(Dispatchers.IO) {
            block()
            FamilyIndex.rematch(ctx)
            // If the photo on screen no longer qualifies, move on.
            val pool = PoolBuilder.build(ctx).photos
            if (pool.isNotEmpty() && pool.none { it.id == prefs.currentPhotoId }) Rotator.next(ctx)
            reload().join()
            _state.update { it.copy(busy = false) }
        }
    }

    fun nameGroup(cluster: Long, personId: Long?, newName: String?) = change {
        val id = personId ?: FamilyIndex.addPerson(ctx, newName.orEmpty().ifBlank { "Family member" })
        FamilyIndex.assignGroup(ctx, cluster, id)
        // The first family member switches on "only my family": that's the point of naming them.
        if (FamilyIndex.familyCount(ctx) == 1 && !prefs.familyOnly && personId == null) prefs.familyOnly = true
    }

    fun groupNotFamily(cluster: Long) = change { FamilyIndex.assignGroupNotFamily(ctx, cluster) }

    fun renamePerson(id: Long, name: String) = change { FamilyIndex.renamePerson(ctx, id, name) }

    fun setPersonFamily(id: Long, family: Boolean) = change { FamilyIndex.setFamily(ctx, id, family) }

    fun deletePerson(id: Long) = change { FamilyIndex.deletePerson(ctx, id) }

    fun removeFace(faceId: Long) = change { FamilyIndex.removeFaceFromPerson(ctx, faceId) }

    fun resetNotFamily() = change { FamilyIndex.resetNotFamily(ctx) }

    fun setFamilyOnly(on: Boolean) = change { prefs.familyOnly = on }

    fun setThreshold(t: Float) = change { prefs.familyThreshold = t }

    fun personSamples(personId: Long, onResult: (List<FamilyIndex.FaceRef>) -> Unit) =
        viewModelScope.launch(Dispatchers.IO) {
            val list = FamilyIndex.personSamples(ctx, personId, 30)
            launch(Dispatchers.Main) { onResult(list) }
        }
}
