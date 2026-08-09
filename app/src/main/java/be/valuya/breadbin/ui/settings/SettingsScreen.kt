package be.valuya.breadbin.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import be.valuya.breadbin.R
import be.valuya.breadbin.data.Aspect
import be.valuya.breadbin.data.Settings
import be.valuya.breadbin.data.SettingsRepository
import be.valuya.breadbin.engine.vic.VideoModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: Settings,
    repository: SettingsRepository,
    scope: CoroutineScope,
    onReplaceRoms: () -> Unit,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            Section(stringResource(R.string.settings_video))

            Choice(
                label = stringResource(R.string.settings_model_pal),
                selected = settings.model == VideoModel.PAL,
                onSelect = { scope.launch { repository.setModel(VideoModel.PAL) } },
            )
            Choice(
                label = stringResource(R.string.settings_model_ntsc),
                selected = settings.model == VideoModel.NTSC,
                onSelect = { scope.launch { repository.setModel(VideoModel.NTSC) } },
            )
            Hint(stringResource(R.string.settings_model_hint))

            Choice(
                label = stringResource(R.string.settings_aspect_tv),
                selected = settings.aspect == Aspect.TELEVISION,
                onSelect = { scope.launch { repository.setAspect(Aspect.TELEVISION) } },
            )
            Choice(
                label = stringResource(R.string.settings_aspect_pixels),
                selected = settings.aspect == Aspect.SQUARE,
                onSelect = { scope.launch { repository.setAspect(Aspect.SQUARE) } },
            )

            Toggle(
                label = stringResource(R.string.settings_smoothing),
                supporting = stringResource(R.string.settings_smoothing_hint),
                checked = settings.smoothing,
                onChange = { scope.launch { repository.setSmoothing(it) } },
            )
            Toggle(
                label = stringResource(R.string.settings_borders),
                checked = settings.showBorder,
                onChange = { scope.launch { repository.setShowBorder(it) } },
            )

            Section(stringResource(R.string.settings_audio))
            Toggle(
                label = stringResource(R.string.settings_sound),
                checked = settings.sound,
                onChange = { scope.launch { repository.setSound(it) } },
            )

            Section(stringResource(R.string.settings_input))
            Toggle(
                label = stringResource(R.string.settings_autostart),
                supporting = stringResource(R.string.settings_autostart_hint),
                checked = settings.autostart,
                onChange = { scope.launch { repository.setAutostart(it) } },
            )
            Choice(
                label = stringResource(R.string.emulator_joystick_port, 2),
                selected = settings.joystickPort == 2,
                onSelect = { scope.launch { repository.setJoystickPort(2) } },
            )
            Choice(
                label = stringResource(R.string.emulator_joystick_port, 1),
                selected = settings.joystickPort == 1,
                onSelect = { scope.launch { repository.setJoystickPort(1) } },
            )
            SliderRow(
                label = stringResource(R.string.settings_stick_size),
                value = settings.stickSize,
                range = 0.6f..1.6f,
                onChange = { scope.launch { repository.setStickSize(it) } },
            )
            SliderRow(
                label = stringResource(R.string.settings_opacity),
                value = settings.opacity,
                range = 0.15f..1f,
                onChange = { scope.launch { repository.setOpacity(it) } },
            )

            Section(stringResource(R.string.settings_roms))
            ListItem(
                headlineContent = { Text(stringResource(R.string.settings_replace_roms)) },
                modifier = Modifier.clickable(onClick = onReplaceRoms),
            )

            Section(stringResource(R.string.settings_about))
            Text(
                text = stringResource(R.string.settings_about_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        modifier = Modifier.selectable(selected = selected, onClick = onSelect),
    )
}

@Composable
private fun Toggle(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    supporting: String? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = value.coerceIn(range),
            valueRange = range,
            onValueChange = onChange,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
