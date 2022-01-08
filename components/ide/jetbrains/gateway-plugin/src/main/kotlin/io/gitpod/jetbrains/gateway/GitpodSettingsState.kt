// Copyright (c) 2022 Gitpod GmbH. All rights reserved.
// Licensed under the GNU Affero General Public License (AGPL).
// See License-AGPL.txt in the project root for license information.

package io.gitpod.jetbrains.gateway

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@State(
    name = "io.gitpod.jetbrains.gateway.GitpodSettingsState",
    storages = [Storage("gitpod.xml")]
)
class GitpodSettingsState : PersistentStateComponent<GitpodSettingsState> {

    var gitpodHost: String = "gitpod.io"

    override fun getState(): GitpodSettingsState? = this

    override fun loadState(state: GitpodSettingsState) {
        XmlSerializerUtil.copyBean(state, this)
    }
}