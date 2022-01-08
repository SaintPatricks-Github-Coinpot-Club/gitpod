// Copyright (c) 2022 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License-AGPL.txt in the project root for license information.

package io.gitpod.jetbrains.gateway

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.wm.impl.welcomeScreen.WelcomeScreenUIManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.*
import com.intellij.ui.dsl.gridLayout.HorizontalAlign
import com.intellij.ui.dsl.gridLayout.VerticalAlign
import com.intellij.ui.layout.ComponentPredicate
import com.intellij.ui.layout.not
import com.intellij.util.EventDispatcher
import com.intellij.util.ui.JBFont
import com.jetbrains.gateway.api.GatewayConnector
import com.jetbrains.gateway.api.GatewayConnectorView
import com.jetbrains.gateway.api.GatewayRecentConnections
import com.jetbrains.gateway.api.GatewayUI.Companion.getInstance
import com.jetbrains.rd.util.lifetime.Lifetime
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rd.util.lifetime.isAlive
import com.jetbrains.rd.util.lifetime.onTermination
import io.gitpod.gitpodprotocol.api.entities.GetWorkspacesOptions
import io.gitpod.gitpodprotocol.api.entities.WorkspaceInstance
import io.gitpod.jetbrains.auth.GitpodAuthService
import io.gitpod.jetbrains.icons.GitpodIcons
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import java.awt.Component
import java.util.*
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument


class GitpodConnector : GatewayConnector {
    override val icon: Icon
        get() = GitpodIcons.Logo

    override fun createView(lifetime: Lifetime): GatewayConnectorView {
        return GitpodConnectorView()
    }

    override fun getActionText(): String {
        return "Connect to Gitpod"
    }

    override fun getDescription(): String? {
        return "Connect to Gitpod workspaces"
    }

    override fun getDocumentationLink(): String {
        // TODO(ak) something JetBrains specific
        return "https://www.gitpod.io/docs"
    }

    override fun getDocumentationLinkText(): String {
        return super.getDocumentationLinkText()
    }

    override fun getRecentConnections(setContentCallback: (Component) -> Unit): GatewayRecentConnections? {
        return GitpodRecentConnections(setContentCallback)
    }

    override fun getTitle(): String {
        return "Gitpod"
    }

    override fun getTitleAdornment(): JComponent? {
        return null
    }

    override fun initProcedure() {}

    class GitpodConnectorView : GatewayConnectorView {
        override val component = panel {
            indent {
                row {
                    icon(GitpodIcons.Logo).gap(RightGap.SMALL)
                    label("Gitpod").applyToComponent {
                        this.font = JBFont.h3().asBold()
                    }
                }.topGap(TopGap.MEDIUM).bottomGap(BottomGap.SMALL)
                row {
                    text("Gitpod is an open-source Kubernetes application for automated and ready-to-code development environments that blends in your existing workflow. It enables you to describe your dev environment as code and start instant and fresh development environments for each new task directly from your browser.")
                }
                row {
                    text("Tightly integrated with GitLab, GitHub, and Bitbucket, Gitpod automatically and continuously prebuilds dev environments for all your branches. As a result, team members can instantly start coding with fresh, ephemeral and fully-compiled dev environments - no matter if you are building a new feature, want to fix a bug or do a code review.")
                }
                row {
                    browserLink("Explore Gitpod", "https://www.gitpod.io/")
                }
                row {
                    // TODO(ak) allow to select backends supported by Gitpod
                    val contextUrl = textField()
                        .resizableColumn()
                        .horizontalAlign(HorizontalAlign.FILL)
                        .label("Start from any GitLab, GitHub or Bitbucket URL:", LabelPosition.TOP)
                        .applyToComponent {
                            this.text = "https://github.com/gitpod-io/spring-petclinic"
                        }
                    button("New Workspace") {
                        // TODO(ak) disable button if blank
                        if (contextUrl.component.text.isNotBlank()) {
                            val gitpodHost = service<GitpodSettingsState>().gitpodHost
                            BrowserUtil.browse("https://${gitpodHost}#referrer:jetbrains-gateway/${contextUrl.component.text}")
                        }
                    }
                    cell()
                }.topGap(TopGap.MEDIUM)
            }
            row {
                resizableRow()
                panel {
                    verticalAlign(VerticalAlign.BOTTOM)
                    separator(null, WelcomeScreenUIManager.getSeparatorColor())
                    indent {
                        row {
                            button("Back") {
                                getInstance().reset()
                            }
                        }
                    }
                }
            }.bottomGap(BottomGap.SMALL)
        }.apply {
            this.background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
        }
    }

    private class GitpodRecentConnections(
        val setContentCallback: (Component) -> Unit
    ) : GatewayRecentConnections {

        private lateinit var lifetime: Lifetime
        private lateinit var workspacesPane: JBScrollPane

        private var syncLifetime: LifetimeDefinition? = null

        override val recentsIcon = GitpodIcons.Logo

        interface Listener : EventListener {
            fun stateChanged()
        }

        private val connectedDispatcher = EventDispatcher.create(Listener::class.java)
        private val connected = object : ComponentPredicate() {

            lateinit var gitpodHost: String

            override fun addListener(listener: (Boolean) -> Unit) {
                connectedDispatcher.addListener(object : Listener {
                    override fun stateChanged() {
                        listener(invoke())
                    }
                })
            }

            override fun invoke(): Boolean {
                gitpodHost = service<GitpodSettingsState>().gitpodHost
                return GitpodAuthService.hasAccessToken(gitpodHost)
            }
        }

        override fun createRecentsView(lifetime: Lifetime): JComponent {
            this.lifetime = lifetime
            val loading = panel {
                row {
                    comment("Loading...")
                }
            }
            val view = panel {
                indent {
                    row {
                        label("Gitpod Workspaces").applyToComponent {
                            this.font = JBFont.h3().asBold()
                        }
                    }.topGap(TopGap.MEDIUM).bottomGap(BottomGap.SMALL)

                    row {
                        panel {
                            verticalAlign(VerticalAlign.CENTER)
                            for (i in 1..10) {
                                row {
                                    label("")
                                }
                            }
                            row {
                                resizableRow()
                                icon(GitpodIcons.Logo4x)
                                    .horizontalAlign(HorizontalAlign.CENTER)
                            }
                            row {
                                text(
                                    "Spin up fresh, automated dev environments for each task, in the cloud, in seconds.",
                                    35
                                ).applyToComponent {
                                    val attrs = SimpleAttributeSet()
                                    StyleConstants.setAlignment(attrs, StyleConstants.ALIGN_CENTER)
                                    (document as StyledDocument).setParagraphAttributes(
                                        0,
                                        document.length - 1,
                                        attrs,
                                        false
                                    )
                                }.horizontalAlign(HorizontalAlign.CENTER)
                            }
                            row {
                                browserLink("Explore Gitpod", "https://www.gitpod.io")
                                    .horizontalAlign(HorizontalAlign.CENTER)
                            }.bottomGap(BottomGap.MEDIUM)
                            row {
                                button("Connect") {
                                    GlobalScope.launch {
                                        GitpodAuthService.authorize(connected.gitpodHost)
                                        doUpdate()
                                    }
                                }.horizontalAlign(HorizontalAlign.CENTER)
                            }
                        }
                    }.visibleIf(connected.not())

                    rowsRange {
                        row {
                            link("Open Dashboard") {
                                BrowserUtil.browse("https://${connected.gitpodHost}")
                            }
                        }
                        row {
                            resizableRow()
                            workspacesPane = cell(JBScrollPane())
                                .resizableColumn()
                                .horizontalAlign(HorizontalAlign.FILL)
                                .verticalAlign(VerticalAlign.FILL)
                                .applyToComponent {
                                    viewport.view = loading
                                }
                                .component
                            cell()
                        }
                        row {
                            label("").resizableColumn().horizontalAlign(HorizontalAlign.FILL).visibleIf(connected)
                            button("Logout") {
                                GitpodAuthService.setAccessToken(connected.gitpodHost, null)
                                doUpdate()
                            }
                            cell()
                        }.topGap(TopGap.SMALL).bottomGap(BottomGap.SMALL)
                    }.visibleIf(connected)
                }
            }.apply {
                this.background = WelcomeScreenUIManager.getMainAssociatedComponentBackground()
            }
            doUpdate()
            return view
        }

        override fun getRecentsTitle(): String {
            return "Gitpod"
        }

        override fun updateRecentView() {
            doUpdate()
        }

        private fun doUpdate() {
            if (!this::lifetime.isInitialized || !this.lifetime.isAlive) {
                return;
            }
            connectedDispatcher.multicaster.stateChanged()
            this.syncLifetime?.terminate()
            if (!connected.invoke()) {
                return;
            }

            val syncLifetime = lifetime.createNested()
            this.syncLifetime = syncLifetime
            val job = GlobalScope.launch {
                val client = service<GitpodConnectionService>().obtainClient(connected.gitpodHost)
                val workspaces = client.server.getWorkspaces(GetWorkspacesOptions().apply {
                    this.limit = 20
                }).await()
                val workspacesMap = workspaces.associateBy { it.workspace.id }
                fun updateView() {
                    val view = panel {
                        for (info in workspacesMap.values) {
                            if (info.latestInstance == null) {
                                continue;
                            }
                            indent {
                                row {
                                    var canConnect = false
                                    icon(
                                        if (info.latestInstance.status.phase == "running") {
                                            canConnect = true
                                            GitpodIcons.Running
                                        } else if (info.latestInstance.status.phase == "stopped") {
                                            if (info.latestInstance.status.conditions.failed.isNullOrBlank()) {
                                                GitpodIcons.Stopped
                                            } else {
                                                GitpodIcons.Failed
                                            }
                                        } else if (info.latestInstance.status.phase == "interrupted") {
                                            GitpodIcons.Failed
                                        } else if (info.latestInstance.status.phase == "unknown") {
                                            GitpodIcons.Failed
                                        } else {
                                            canConnect = true
                                            GitpodIcons.Starting
                                        }
                                    ).gap(RightGap.SMALL)
                                    panel {
                                        row {
                                            browserLink(info.workspace.id, info.latestInstance.ideUrl)
                                        }.rowComment("<a href='${info.workspace.context.normalizedContextURL}'>${info.workspace.context.normalizedContextURL}</a>")
                                    }
                                    label("").resizableColumn().horizontalAlign(HorizontalAlign.FILL)
                                    button("Connect") {
                                        if (!canConnect) {
                                            BrowserUtil.browse(info.latestInstance.ideUrl)
                                        } else {
                                            getInstance().connect(
                                                mapOf(
                                                    "gitpodHost" to connected.gitpodHost,
                                                    "workspaceId" to info.workspace.id
                                                )
                                            )
                                        }
                                    }
                                    cell()
                                }
                            }
                        }
                    }
                    ApplicationManager.getApplication().invokeLater {
                        workspacesPane.viewport.view = view
                    }
                }
                updateView()
                val updates = client.listenToWorkspace(syncLifetime, "*")
                for (update in updates) {
                    val info = workspacesMap[update.workspaceId] ?: continue
                    if (WorkspaceInstance.isUpToDate(info.latestInstance, update)) {
                        continue;
                    }
                    info.latestInstance = update
                    updateView()
                }
            }
            lifetime.onTermination { job.cancel() }
        }
    }
}