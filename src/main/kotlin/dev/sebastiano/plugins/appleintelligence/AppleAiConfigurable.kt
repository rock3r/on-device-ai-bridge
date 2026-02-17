// Copyright 2000-2026 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package dev.sebastiano.plugins.appleintelligence

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.BoundSearchableConfigurable
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.MessageDialogBuilder
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.actionButton
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.layout.ValidationInfoBuilder
import com.intellij.util.ui.NamedColorUtil
import java.awt.Component
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JLabel
import javax.swing.Timer
import kotlin.properties.Delegates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import javax.swing.BorderFactory
import javax.swing.JTextField

private const val LOOPBACK_HOST = "127.0.0.1"
private const val ALL_INTERFACES_HOST = "0.0.0.0"
private const val PORT_FIELD_COLUMNS = 8
private const val API_KEY_FIELD_COLUMNS = 36
private const val STATUS_TIMER_DELAY_MS = 1500
private const val STATUS_TIMER_INITIAL_DELAY_MS = 500
private const val MIN_PORT = 1
private const val MAX_PORT = 65535

internal class AppleAiConfigurable :
    BoundSearchableConfigurable(AppleAiBundle.message("apple.ai.configurable.displayName"), "apple.ai.settings") {
    private val service
        get() = AppleAiService.getInstance()

    override fun createPanel(): DialogPanel {
        val settings = AppleAiSettings.getInstance()
        lateinit var panel: DialogPanel

        lateinit var serverStatusLabel: JLabel
        lateinit var modelStatusLabel: JLabel
        lateinit var endpointComment: JEditorPane
        lateinit var toggleButton: JButton
        lateinit var disabledReasonLabel: JLabel

        val validHelper = BooleanComponentPredicate(service.isRunning || isHelperPathValid(settings))

        val reason = service.unavailabilityReason
        panel = panel {
            if (reason != null) {
                row { label(reason) }
                return@panel
            }

            statusGroup(settings, validHelper, { panel }) { labels, button, reasonLabel ->
                serverStatusLabel = labels.first
                modelStatusLabel = labels.second
                endpointComment = labels.third
                toggleButton = button
                disabledReasonLabel = reasonLabel
            }
            serverConfigGroup(settings, validHelper) { panel }
        }

        if (reason != null) return panel

        setupStatusTimer(
            settings, validHelper, serverStatusLabel, modelStatusLabel,
            endpointComment, toggleButton, disabledReasonLabel,
        )

        return panel
    }

    private fun Panel.statusGroup(
        settings: AppleAiSettings,
        validHelper: BooleanComponentPredicate,
        panel: () -> DialogPanel,
        capture: (Triple<JLabel, JLabel, JEditorPane>, JButton, JLabel) -> Unit,
    ) {
        lateinit var serverStatusLabel: JLabel
        lateinit var modelStatusLabel: JLabel
        lateinit var endpointComment: JEditorPane
        lateinit var toggleButton: JButton
        lateinit var disabledReasonLabel: JLabel

        group(AppleAiBundle.message("apple.ai.settings.status.group")) {
            row(AppleAiBundle.message("apple.ai.settings.status.server")) {
                label(serverStatusText(settings)).applyToComponent { serverStatusLabel = this }
            }
            row(AppleAiBundle.message("apple.ai.settings.status.model")) {
                label(AppleAiBundle.message("apple.ai.settings.status.model.unknown")).applyToComponent {
                    modelStatusLabel = this
                }
            }
            row(AppleAiBundle.message("apple.ai.settings.endpoint")) {
                @Suppress("HttpUrlsUsage")
                comment("http://${displayHost(settings)}:${settings.state.port}/v1/chat/completions").applyToComponent {
                    endpointComment = this
                }
                actionButton(
                    object :
                        DumbAwareAction(
                            AppleAiBundle.message("apple.ai.settings.endpoint.copy"),
                            null,
                            AllIcons.Actions.Copy,
                        ) {
                        override fun actionPerformed(e: AnActionEvent) {
                            @Suppress("HttpUrlsUsage")
                            val url = "http://${displayHost(settings)}:${settings.state.port}/v1/chat/completions"
                            CopyPasteManager.copyTextToClipboard(url)
                        }
                    }
                )
            }
            row("") {
                toggleButton =
                    button(toggleButtonText()) {
                            panel().apply()
                            if (service.isRunning) {
                                service.stopHelper()
                            } else {
                                val project = ProjectManager.getInstance().defaultProject
                                runWithModalProgressBlocking(
                                    project,
                                    AppleAiBundle.message("apple.ai.settings.start"),
                                ) {
                                    service.startHelper()
                                }
                            }
                            panel().reset()
                        }
                        .enabledIf(validHelper)
                        .component
                checkBox(AppleAiBundle.message("apple.ai.settings.autoStart"))
                    .bindSelected(
                        { settings.state.autoStart },
                        { settings.loadState(settings.state.copy(autoStart = it)) },
                    )
            }
            row("") {
                label(disabledReasonText())
                    .applyToComponent {
                        disabledReasonLabel = this
                        icon = AllIcons.General.Warning
                        foreground = NamedColorUtil.getInactiveTextColor()
                        isVisible = !validHelper()
                    }
            }
        }

        capture(Triple(serverStatusLabel, modelStatusLabel, endpointComment), toggleButton, disabledReasonLabel)
    }

    private fun Panel.serverConfigGroup(
        settings: AppleAiSettings,
        validHelper: BooleanComponentPredicate,
        panel: () -> DialogPanel,
    ) {
        group(AppleAiBundle.message("apple.ai.settings.server.group")) {
            listenOnGroup(settings)
            portRow(settings, panel)
            val apiKeyField = apiKeyRow(settings)
            apiKeyCommentRow()
            apiKeyRegenerateRow(settings, panel, apiKeyField)
            swiftBinaryRow(settings, validHelper)
            buildHelperRow(settings, panel)
        }
    }

    private fun Panel.listenOnGroup(settings: AppleAiSettings) {
        buttonsGroup(AppleAiBundle.message("apple.ai.settings.listenOn")) {
                row { radioButton(AppleAiBundle.message("apple.ai.settings.listenOn.localOnly"), LOOPBACK_HOST) }
                row {
                    radioButton(AppleAiBundle.message("apple.ai.settings.listenOn.network"), ALL_INTERFACES_HOST)
                        .comment(AppleAiBundle.message("apple.ai.settings.listenOn.network.warning"))
                }
            }
            .bind(
                object : MutableProperty<String> {
                    override fun get(): String = settings.state.host

                    override fun set(value: String) {
                        settings.loadState(settings.state.copy(host = value))
                    }
                },
                String::class.java,
            )
    }

    private fun Panel.portRow(settings: AppleAiSettings, panel: () -> DialogPanel) {
        row(AppleAiBundle.message("apple.ai.settings.port")) {
            intTextField(MIN_PORT..MAX_PORT)
                .columns(PORT_FIELD_COLUMNS)
                .bindIntText(
                    { settings.state.port },
                    {
                        settings.loadState(settings.state.copy(port = it))
                        AppleAiCustomPortServerManager.portChanged()
                    },
                )
                .onChanged { panel().apply() }
                .comment(AppleAiBundle.message("apple.ai.settings.restartRequired"))
        }
    }

    private fun Panel.apiKeyRow(settings: AppleAiSettings): JTextField {
        lateinit var apiKeyField: JTextField
        row(AppleAiBundle.message("apple.ai.settings.apiKey")) {
            apiKeyField =
                textField()
                    .columns(API_KEY_FIELD_COLUMNS)
                    .bindText({ settings.ensureApiKey() }, { settings.setApiKey(it) })
                    .applyToComponent {
                        isEditable = false
                        border = BorderFactory.createEmptyBorder()
                        isOpaque = false
                    }
                    .component
            actionButton(
                object :
                    DumbAwareAction(
                        AppleAiBundle.message("apple.ai.settings.apiKey.copy"),
                        null,
                        AllIcons.Actions.Copy,
                    ) {
                    override fun actionPerformed(e: AnActionEvent) {
                        CopyPasteManager.copyTextToClipboard(apiKeyField.text)
                    }
                }
            )
        }
        return apiKeyField
    }

    private fun Panel.swiftBinaryRow(settings: AppleAiSettings, validHelper: BooleanComponentPredicate) {
        row(AppleAiBundle.message("apple.ai.settings.swiftBinary")) {
            @Suppress("UnstableApiUsage")
            textFieldWithBrowseButton(
                    FileChooserDescriptorFactory.singleFile()
                        .withTitle(AppleAiBundle.message("apple.ai.settings.swiftBinary.title"))
                )
                .align(AlignX.FILL)
                .bindText(
                    { settings.state.swiftHelperPath },
                    { settings.loadState(settings.state.copy(swiftHelperPath = it)) },
                )
                .validationOnInput {
                    if (!isHelperPathValid(it.text)) {
                        validHelper.set(false)
                        ValidationInfoBuilder(component)
                            .error(AppleAiBundle.message("dialog.message.helper.path.not.valid"))
                    } else {
                        validHelper.set(true)
                        null
                    }
                }
                .validationOnApply {
                    if (!isHelperPathValid(it.text)) {
                        ValidationInfoBuilder(component)
                            .error(AppleAiBundle.message("dialog.message.helper.path.not.valid"))
                    } else {
                        null
                    }
                }
        }
    }

    private fun Panel.buildHelperRow(settings: AppleAiSettings, panel: () -> DialogPanel) {
        row("") {
            button(AppleAiBundle.message("apple.ai.settings.buildHelper.button")) {
                    val descriptor =
                        FileSaverDescriptor(AppleAiBundle.message("apple.ai.settings.buildHelper.title"), "")
                    val saver =
                        FileChooserFactory.getInstance().createSaveFileDialog(descriptor, (it.source as? Component)!!)
                    val fileWrapper = saver.save(null as VirtualFile?, "AppleAIHelper")
                    if (fileWrapper != null) {
                        @Suppress("IO_FILE_USAGE") val destination = fileWrapper.file.toPath()
                        val project = ProjectManager.getInstance().defaultProject
                        val success =
                            runWithModalProgressBlocking(
                                project,
                                AppleAiBundle.message("apple.ai.settings.buildHelper.button"),
                            ) {
                                service.buildHelper(destination)
                            }
                        if (success) {
                            panel().apply()
                            settings.loadState(settings.state.copy(swiftHelperPath = destination.toString()))
                            panel().reset()
                        }
                    }
                }
                .comment(AppleAiBundle.message("apple.ai.settings.buildHelper.info"))
        }
    }

    private fun setupStatusTimer(
        settings: AppleAiSettings,
        validHelper: BooleanComponentPredicate,
        serverStatusLabel: JLabel,
        modelStatusLabel: JLabel,
        endpointComment: JEditorPane,
        toggleButton: JButton,
        disabledReasonLabel: JLabel,
    ) {
        val statusTimer =
            Timer(STATUS_TIMER_DELAY_MS) {
                serverStatusLabel.text = serverStatusText(settings)
                @Suppress("HttpUrlsUsage")
                endpointComment.text = "http://${displayHost(settings)}:${settings.state.port}/v1/chat/completions"
                toggleButton.text = toggleButtonText()
                val canStart = service.isRunning || validHelper()
                toggleButton.isEnabled = canStart
                disabledReasonLabel.text = disabledReasonText()
                disabledReasonLabel.isVisible = !canStart

                service.coroutineScope.launch(Dispatchers.IO) {
                    val modelText = modelStatusText()
                    withContext(Dispatchers.Main) {
                        if (modelStatusLabel.text != modelText) {
                            modelStatusLabel.text = modelText
                        }
                    }
                }
            }
        statusTimer.initialDelay = STATUS_TIMER_INITIAL_DELAY_MS
        statusTimer.start()

        val panelDisposable = disposable
        if (panelDisposable != null) {
            Disposer.register(panelDisposable) { statusTimer.stop() }
        }
    }

    @Nls
    private fun serverStatusText(settings: AppleAiSettings): String =
        if (service.isRunning) {
            AppleAiBundle.message(
                "apple.ai.settings.status.server.running",
                displayHost(settings),
                settings.state.port.toString(),
            )
        } else {
            AppleAiBundle.message("apple.ai.settings.status.server.stopped")
        }

    @Nls
    private fun toggleButtonText(): String =
        if (service.isRunning) {
            AppleAiBundle.message("apple.ai.settings.stop")
        } else {
            AppleAiBundle.message("apple.ai.settings.start")
        }

    @Nls
    private suspend fun modelStatusText(): String {
        if (!service.isRunning) {
            return AppleAiBundle.message("apple.ai.settings.status.model.unknown")
        }
        return try {
            val status = service.checkModelStatus()
            when {
                status == null -> AppleAiBundle.message("apple.ai.settings.status.model.unknown")
                status.modelAvailable == true -> AppleAiBundle.message("apple.ai.settings.status.model.available")
                else ->
                    AppleAiBundle.message(
                        "apple.ai.settings.status.model.unavailable",
                        status.reason ?: "Unknown reason",
                    )
            }
        } catch (_: Exception) {
            AppleAiBundle.message("apple.ai.settings.status.model.unknown")
        }
    }

    @Nls
    private fun disabledReasonText(): String = AppleAiBundle.message("apple.ai.settings.status.disabled.reason")

    private fun isHelperPathValid(settings: AppleAiSettings): Boolean =
        isHelperPathValid(settings.state.swiftHelperPath)

    private fun isHelperPathValid(path: String): Boolean {
        if (path.isBlank()) return false
        val binary = Path.of(path)
        return Files.isRegularFile(binary) && Files.isExecutable(binary)
    }
}

private class BooleanComponentPredicate(initialValue: Boolean) : ComponentPredicate() {
    private val listeners: MutableList<(Boolean) -> Unit> = mutableListOf()
    private var value: Boolean by
        Delegates.observable(initialValue) { _, old, new -> if (old != new) listeners.forEach { it(new) } }

    fun set(newValue: Boolean) {
        value = newValue
    }

    override fun invoke() = value

    override fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }
}

private fun Panel.apiKeyCommentRow() {
    row("") { comment(AppleAiBundle.message("apple.ai.settings.apiKey.comment")) }
}

private fun Panel.apiKeyRegenerateRow(
    settings: AppleAiSettings,
    panel: () -> DialogPanel,
    apiKeyField: JTextField,
) {
    row("") {
        button(AppleAiBundle.message("apple.ai.settings.apiKey.regenerate")) {
            val confirmed =
                MessageDialogBuilder.okCancel(
                        AppleAiBundle.message("apple.ai.settings.apiKey.regenerate"),
                        AppleAiBundle.message("apple.ai.settings.apiKey.regenerate.confirm"),
                    )
                    .ask(panel())
            if (confirmed) {
                settings.setApiKey("")
                apiKeyField.text = settings.ensureApiKey()
            }
        }
    }
}

private fun displayHost(settings: AppleAiSettings): String =
    if (settings.state.host == ALL_INTERFACES_HOST) "localhost" else settings.state.host
