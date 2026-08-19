package io.github.nolte.kamerplanter.feature.plants

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Search and the filter chips, above the list.
 *
 * Always on screen while there are plants at all — including when the narrowing matches
 * nothing, which is exactly when the user needs it to get back out. Its dropdowns offer only
 * values the loaded plants actually carry, so nothing here selects an empty list by
 * construction.
 *
 * Horizontally scrollable rather than wrapped onto several lines: five chips do not fit
 * across a phone, and a filter row that grows to three lines pushes the first plant off the
 * screen the list exists to show.
 */
@Composable
internal fun PlantFilterBar(
    filter: PlantFilter,
    options: PlantFilterOptions,
    onFilterChange: (PlantFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The field's own copy of what has been typed, and the ViewModel told separately.
        //
        // Not driven from the state flow alone: `collectAsStateWithLifecycle` conflates, so a
        // fast typist outruns it and the field snaps back to a value it already replaced.
        // Local text plus an effect that follows deliberate outside changes — clearing the
        // filters, switching instance — keeps both true without either overwriting the other.
        var typed by rememberSaveable { mutableStateOf(filter.query) }
        LaunchedEffect(filter.query) {
            if (filter.query != typed) typed = filter.query
        }
        OutlinedTextField(
            value = typed,
            onValueChange = {
                typed = it
                onFilterChange(filter.copy(query = it))
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            label = { Text(stringResource(R.string.plants_filter_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (filter.query.isNotEmpty()) {
                    IconButton(onClick = { onFilterChange(filter.copy(query = "")) }) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(
                                R.string.plants_filter_search_clear,
                            ),
                        )
                    }
                }
            },
        )
        FilterChips(filter = filter, options = options, onFilterChange = onFilterChange)
    }
}

/**
 * The dimensions, as chips.
 *
 * Split from [PlantFilterBar] only so neither function has to be read in one breath; the two
 * are always shown together.
 */
@Composable
private fun FilterChips(
    filter: PlantFilter,
    options: PlantFilterOptions,
    onFilterChange: (PlantFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A dimension the loaded plants say nothing about is not offered at all: a
        // "Phase" menu holding only "All" is a control that cannot do anything.
        if (options.locations.isNotEmpty()) {
            ValueChip(
                label = stringResource(R.string.plants_filter_location),
                selected = filter.location,
                values = options.locations,
                onSelect = { onFilterChange(filter.copy(location = it)) },
            )
        }
        if (options.species.isNotEmpty()) {
            ValueChip(
                label = stringResource(R.string.plants_filter_species),
                selected = filter.species,
                values = options.species,
                onSelect = { onFilterChange(filter.copy(species = it)) },
            )
        }
        if (options.phases.isNotEmpty()) {
            ValueChip(
                label = stringResource(R.string.plants_filter_phase),
                selected = filter.phase,
                values = options.phases,
                onSelect = { onFilterChange(filter.copy(phase = it)) },
            )
        }
        ToggleChip(
            label = stringResource(R.string.plants_filter_needs_attention),
            selected = filter.needsAttention,
            onToggle = { onFilterChange(filter.copy(needsAttention = it)) },
        )
        ToggleChip(
            label = stringResource(R.string.plants_filter_show_removed),
            selected = filter.includeRemoved,
            onToggle = { onFilterChange(filter.copy(includeRemoved = it)) },
        )
        if (filter.isActive) {
            TextButton(onClick = { onFilterChange(PlantFilter()) }) {
                Text(stringResource(R.string.plants_filter_clear))
            }
        }
    }
}

/**
 * A chip that opens a menu of values, and shows the chosen one in place of its own name.
 *
 * The chosen value replaces the label rather than sitting beside it, because that is the
 * only place the active narrowing is visible once the menu is closed — "Location" and
 * "Location: Kitchen" differ by more than a word, and the second is what the user needs to
 * see to understand why plants are missing.
 */
@Composable
private fun ValueChip(
    label: String,
    selected: String?,
    values: List<String>,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = {
                Text(
                    text = selected ?: label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            // Sighted, the chip's position says which dimension a bare "Kitchen" belongs to.
            // Spoken, it does not — so the dimension is said out loud with its value.
            modifier = Modifier.semantics {
                if (selected != null) contentDescription = "$label: $selected"
            },
            trailingIcon = {
                Icon(
                    imageVector = Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            // "All" is how a dimension is given up again. Without it the only way out of a
            // chosen value would be clearing every filter at once.
            DropdownMenuItem(
                text = { Text(stringResource(R.string.plants_filter_any)) },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
                trailingIcon = { if (selected == null) SelectedMark() },
            )
            values.forEach { value ->
                DropdownMenuItem(
                    text = { Text(value) },
                    onClick = {
                        onSelect(value)
                        expanded = false
                    },
                    trailingIcon = { if (selected == value) SelectedMark() },
                )
            }
        }
    }
}

@Composable
private fun SelectedMark() {
    Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onToggle: (Boolean) -> Unit) {
    FilterChip(
        selected = selected,
        onClick = { onToggle(!selected) },
        label = { Text(label) },
        // The tick is what a screen reader and a monochrome eye have to go on; the chip's
        // container colour alone would carry the state for neither.
        leadingIcon = { if (selected) SelectedMark() },
    )
}
