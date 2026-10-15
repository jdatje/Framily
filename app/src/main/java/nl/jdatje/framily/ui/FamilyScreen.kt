package nl.jdatje.framily.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import nl.jdatje.framily.family.FamilyIndex

@Composable
fun FamilyScreen(vm: FamilyViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val s by vm.state.collectAsStateWithLifecycle()
    var naming by remember { mutableStateOf<FamilyIndex.Group?>(null) }
    var person by remember { mutableStateOf<FamilyIndex.Person?>(null) }
    val family = s.people.filter { it.family }
    val others = s.people.filter { !it.family }

    Scaffold { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("‹ Back") }
                    Text("Family", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    "Framily groups faces that look alike. Tell it once who is family, and it finds them in all your photos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            }

            if (s.pendingPhotos > 0) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(
                            "Still learning faces from ${s.pendingPhotos} photos. This happens during the scan " +
                                "(while charging, if that's on). New groups appear here as it goes.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            item { SettingsCard(s, vm, hasFamily = family.isNotEmpty()) }

            item { SectionTitle("Your family", if (family.isEmpty()) "Nobody yet. Name a group below." else null) }
            items(family, key = { "person-${it.id}" }) { p -> PersonRow(p) { person = p } }

            if (others.isNotEmpty()) {
                item { SectionTitle("Other people you named", "Not counted as family") }
                items(others, key = { "person-${it.id}" }) { p -> PersonRow(p) { person = p } }
            }

            item {
                SectionTitle(
                    "Who is this?",
                    if (s.groups.isEmpty() && !s.loading) "No unnamed groups right now." else "Biggest groups first. Each group is usually one person.",
                )
            }
            items(s.groups, key = { "group-${it.cluster}" }) { g ->
                GroupCard(
                    g,
                    onFamily = { naming = g },
                    onNotFamily = { vm.groupNotFamily(g.cluster) },
                    onSuggested = {
                        val match = s.people.firstOrNull { it.name == g.suggestion }
                        if (match != null) vm.nameGroup(g.cluster, match.id, null)
                    },
                    enabled = !s.busy,
                )
            }
            if (s.groups.size >= s.showGroups) {
                item { OutlinedButton(onClick = { vm.showMoreGroups() }) { Text("Show more groups") } }
            }

            if (s.notFamilyFaces > 0) {
                item {
                    TextButton(onClick = { vm.resetNotFamily() }) {
                        Text("Forget all \"Not family\" answers (${s.notFamilyFaces} faces)")
                    }
                }
            }
        }
    }

    naming?.let { g ->
        NameDialog(
            group = g,
            family = family,
            onDismiss = { naming = null },
            onPick = { id -> vm.nameGroup(g.cluster, id, null); naming = null },
            onNew = { name -> vm.nameGroup(g.cluster, null, name); naming = null },
        )
    }

    person?.let { p ->
        PersonDialog(
            person = p,
            vm = vm,
            onDismiss = { person = null },
        )
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String?) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (subtitle != null) {
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsCard(s: FamilyState, vm: FamilyViewModel, hasFamily: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Only photos with family", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        if (hasFamily) "The face-size rule then applies to the family member" else "Name at least one family member first",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = s.familyOnly && hasFamily, enabled = hasFamily && !s.busy, onCheckedChange = { vm.setFamilyOnly(it) })
            }
            var t by remember(s.threshold) { mutableFloatStateOf(s.threshold) }
            Text(
                "Recognition: " + when {
                    t < 0.5f -> "loose (finds more, more mistakes)"
                    t > 0.62f -> "strict (fewer mistakes, may miss some)"
                    else -> "balanced"
                },
                style = MaterialTheme.typography.labelLarge,
            )
            Slider(value = t, onValueChange = { t = it }, onValueChangeFinished = { vm.setThreshold(t) }, valueRange = 0.45f..0.70f, enabled = !s.busy)
        }
    }
}

@Composable
private fun PersonRow(p: FamilyIndex.Person, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        p.sample?.let { FaceThumb(it, 52.dp) } ?: Spacer(Modifier.width(52.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.bodyLarge)
            Text("Recognized in ${p.photos} photos", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GroupCard(
    g: FamilyIndex.Group,
    onFamily: () -> Unit,
    onNotFamily: () -> Unit,
    onSuggested: () -> Unit,
    enabled: Boolean,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                g.samples.forEach { FaceThumb(it, 56.dp) }
            }
            Text(
                "${g.size} faces" + (g.suggestion?.let { " · looks like $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (g.suggestion != null) {
                    Button(onClick = onSuggested, enabled = enabled) { Text("Yes, ${g.suggestion}") }
                    OutlinedButton(onClick = onFamily, enabled = enabled) { Text("Someone else") }
                } else {
                    Button(onClick = onFamily, enabled = enabled) { Text("Family") }
                }
                TextButton(onClick = onNotFamily, enabled = enabled) { Text("Not family") }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NameDialog(
    group: FamilyIndex.Group,
    family: List<FamilyIndex.Person>,
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
    onNew: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Who is this?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    group.samples.take(4).forEach { FaceThumb(it, 56.dp) }
                }
                if (family.isNotEmpty()) {
                    Text("Same person as:", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        family.forEach { p -> FilterChip(selected = false, onClick = { onPick(p.id) }, label = { Text(p.name) }) }
                    }
                    Text("Or a new family member:", style = MaterialTheme.typography.labelLarge)
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onNew(name) }, enabled = name.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PersonDialog(person: FamilyIndex.Person, vm: FamilyViewModel, onDismiss: () -> Unit) {
    var name by remember(person.id) { mutableStateOf(person.name) }
    var faces by remember(person.id) { mutableStateOf<List<FamilyIndex.FaceRef>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(person.id) { vm.personSamples(person.id) { faces = it } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(person.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Family", Modifier.weight(1f))
                    Switch(checked = person.family, onCheckedChange = { vm.setPersonFamily(person.id, it); onDismiss() })
                }
                Text("Recognized as ${person.name}. Tap a face that isn't them to remove it.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    faces.orEmpty().forEach { f ->
                        FaceThumb(f, 48.dp, Modifier.clickable {
                            vm.removeFace(f.faceId)
                            faces = faces?.filter { it.faceId != f.faceId }
                        })
                    }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Forget ${person.name}", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isNotBlank() && name != person.name) vm.renamePerson(person.id, name)
                onDismiss()
            }) { Text("Done") }
        },
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Forget ${person.name}?") },
            text = { Text("Their faces become unknown again; you can name them again later.") },
            confirmButton = { TextButton(onClick = { vm.deletePerson(person.id); confirmDelete = false; onDismiss() }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
