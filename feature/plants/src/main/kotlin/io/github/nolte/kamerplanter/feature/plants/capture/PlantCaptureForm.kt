package io.github.nolte.kamerplanter.feature.plants.capture

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Card
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.nolte.kamerplanter.core.network.SpeciesEntry
import io.github.nolte.kamerplanter.feature.plants.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The species, searched over the loaded catalogue by common and scientific name (R18).
 *
 * A chosen species shows its scientific name in the field so the user sees *what* was matched
 * (R17); typing again clears the choice and searches afresh. "Nothing matches" and "the
 * catalogue is empty" are different sentences, because they call for different actions.
 */
@Composable
internal fun SpeciesField(form: PlantCaptureState.Form, actions: FormActions) {
    Column {
        OutlinedTextField(
            value = form.inputs.speciesQuery,
            onValueChange = actions.onSearchSpecies,
            label = { Text(stringResource(R.string.plants_add_species)) },
            placeholder = { Text(stringResource(R.string.plants_add_species_hint)) },
            singleLine = true,
            isError = FormField.SPECIES in form.errors,
            supportingText = {
                when {
                    FormField.SPECIES in form.errors -> Text(stringResource(R.string.plants_add_species_required))
                    // Named by the recogniser, absent from the catalogue: created with the plant (R25).
                    form.inputs.pendingSpecies != null -> Text(stringResource(R.string.plants_add_species_pending))
                    form.species != null -> Text(form.species?.commonNames?.joinToString().orEmpty())
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        SpeciesMatches(form, actions.onChooseSpecies)
    }
}

@Composable
private fun SpeciesMatches(form: PlantCaptureState.Form, onChoose: (SpeciesEntry) -> Unit) {
    val query = form.inputs.speciesQuery.trim()
    when {
        form.inputs.pendingSpecies != null -> Unit
        form.catalogue.isEmpty() -> Text(
            text = stringResource(R.string.plants_add_species_empty),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        form.inputs.speciesKey != null || query.isEmpty() -> Unit
        form.speciesMatches.isEmpty() -> Text(
            text = stringResource(R.string.plants_add_species_none),
            style = MaterialTheme.typography.bodySmall,
        )
        else -> Card(modifier = Modifier.fillMaxWidth()) {
            form.speciesMatches.forEach { entry ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onChoose(entry) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(entry.scientificName, style = MaterialTheme.typography.bodyMedium)
                    if (entry.commonNames.isNotEmpty()) {
                        Text(
                            text = entry.commonNames.joinToString(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun NameField(form: PlantCaptureState.Form, actions: FormActions) {
    OutlinedTextField(
        value = form.inputs.plantName,
        onValueChange = actions.onEditPlantName,
        label = { Text(stringResource(R.string.plants_add_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Proposed from species and location, visible and editable throughout (R19–R21). */
@Composable
internal fun InstanceIdField(form: PlantCaptureState.Form, actions: FormActions) {
    val error = FormField.INSTANCE_ID in form.errors
    OutlinedTextField(
        value = form.inputs.instanceId,
        onValueChange = actions.onEditInstanceId,
        label = { Text(stringResource(R.string.plants_add_instance_id)) },
        singleLine = true,
        isError = error || form.instanceIdTaken,
        supportingText = {
            when {
                form.instanceIdTaken -> Text(stringResource(R.string.plants_add_instance_id_taken))
                error -> Text(stringResource(R.string.plants_add_instance_id_required))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Pre-filled with today, changed through the date picker, refused when cleared (R22). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlantedOnField(form: PlantCaptureState.Form, actions: FormActions) {
    var picking by remember { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    OutlinedTextField(
        value = form.inputs.plantedOn?.format(formatter).orEmpty(),
        onValueChange = {},
        readOnly = true,
        label = { Text(stringResource(R.string.plants_add_planted_on)) },
        isError = FormField.PLANTED_ON in form.errors,
        supportingText = {
            if (FormField.PLANTED_ON in form.errors) Text(stringResource(R.string.plants_add_planted_on_required))
        },
        trailingIcon = {
            IconButton(onClick = { picking = true }) {
                Icon(Icons.Filled.DateRange, contentDescription = stringResource(R.string.plants_add_pick_date))
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    if (picking) {
        PlantedOnPicker(
            initial = form.inputs.plantedOn,
            onPicked = { date ->
                picking = false
                actions.onEditPlantedOn(date)
            },
            onDismiss = { picking = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlantedOnPicker(initial: LocalDate?, onPicked: (LocalDate?) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    onPicked(
                        state.selectedDateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() },
                    )
                },
            ) {
                Text(stringResource(R.string.plants_add_date_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.plants_add_date_dismiss)) }
        },
    ) {
        DatePicker(state = state)
    }
}

/** Site first, then one of its locations; neither is required (R23). */
@Composable
internal fun PlaceFields(form: PlantCaptureState.Form, actions: FormActions) {
    ChoiceField(
        Choice(
            label = stringResource(R.string.plants_add_site),
            options = form.sites.map { it.key to it.name },
            chosen = form.inputs.siteKey,
            enabled = form.sites.isNotEmpty(),
        ),
        onChoose = actions.onChooseSite,
    )
    ChoiceField(
        Choice(
            label = stringResource(R.string.plants_add_location),
            options = form.locations.orEmpty().map { it.key to it.name },
            chosen = form.inputs.locationKey,
            enabled = form.inputs.siteKey != null && !form.locations.isNullOrEmpty(),
            supporting = stringResource(R.string.plants_add_location_needs_site).takeIf { form.inputs.siteKey == null },
        ),
        onChoose = actions.onChooseLocation,
    )
}

/** What a dropdown offers: `key to name` pairs, with "none" prepended by the field itself. */
private data class Choice(
    val label: String,
    val options: List<Pair<String, String>>,
    val chosen: String?,
    val enabled: Boolean,
    val supporting: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(choice: Choice, onChoose: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val none = stringResource(R.string.plants_add_none)
    val open = expanded && choice.enabled
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = choice.options.firstOrNull { it.first == choice.chosen }?.second ?: none,
            onValueChange = {},
            readOnly = true,
            enabled = choice.enabled,
            label = { Text(choice.label) },
            supportingText = choice.supporting?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, choice.enabled),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(none) },
                onClick = {
                    expanded = false
                    onChoose(null)
                },
            )
            choice.options.forEach { (key, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        expanded = false
                        onChoose(key)
                    },
                )
            }
        }
    }
}
