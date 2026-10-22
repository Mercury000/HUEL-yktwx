package com.mercury.yktwx.ui.pages

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mercury.yktwx.data.CampusCardAction
import com.mercury.yktwx.data.FeeProject
import com.mercury.yktwx.ui.ThemeSettings
import com.mercury.yktwx.ui.components.*
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical

@Composable
fun HallPage(
    settings: ThemeSettings,
    bottomPadding: Dp,
    onOpenFee: (FeeProject) -> Unit = {},
    feeOpening: FeeProject? = null,
    onOpenCardService: (CampusCardAction) -> Unit = {},
    cardServiceOpening: Boolean = false,
    cardServiceLoadingAction: CampusCardAction? = null,
) {
    val scrollBehavior = MiuixScrollBehavior(rememberTopAppBarState())
    BarBlurHost(bottomBarBlurEnabled = false, topBarBlurEnabled = settings.blurTopBar.value) {
        Scaffold(topBar = {
            BlurredBar(topGradient = true) {
                TopAppBar(title = "大厅", largeTitle = "大厅", scrollBehavior = scrollBehavior, color = Color.Transparent)
            }
        }) { padding ->
            BarBackdropContent(Modifier.fillMaxSize().background(MiuixTheme.colorScheme.surface)) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().overScrollVertical().nestedScroll(scrollBehavior.nestedScrollConnection).imePadding(),
                    contentPadding = PaddingValues(
                        top = padding.calculateTopPadding() + 16.dp,
                        bottom = bottomPadding + 24.dp,
                        start = 16.dp, end = 16.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { LifeFeesCard(feeOpening, onOpenFee) }
                    item { CampusCardServicesCard(cardServiceOpening, onOpenCardService, cardServiceLoadingAction) }
                }
            }
        }
    }
}

@Composable
private fun LifeFeesCard(opening: FeeProject?, onOpen: (FeeProject) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("生活缴费", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            FeeProject.entries.chunked(3).forEach { projects ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    projects.forEach { project ->
                        ServiceEntry(
                            label = project.label,
                            icon = when (project) {
                                FeeProject.Network -> AppIcons.Network
                                FeeProject.FamilyWater -> AppIcons.Water
                                else -> AppIcons.Electricity
                            },
                            loading = opening == project,
                            enabled = opening == null,
                            modifier = Modifier.weight(1f),
                            onClick = { onOpen(project) },
                        )
                    }
                    repeat(3 - projects.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/** Campus card services share the three-column grid used by living fees. */
@Composable
internal fun CampusCardServicesCard(opening: Boolean, onOpen: (CampusCardAction) -> Unit, loadingAction: CampusCardAction? = null) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("校园卡服务", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(CampusCardAction.ManageState, CampusCardAction.OthersRecharge, CampusCardAction.ChangePassword).forEach { action ->
                    ServiceEntry(
                        label = action.label,
                        icon = when (action) {
                            CampusCardAction.ManageState -> AppIcons.CardState
                            CampusCardAction.ChangePassword -> AppIcons.Password
                            else -> AppIcons.OthersRecharge
                        },
                        loading = loadingAction == action,
                        enabled = !opening,
                        modifier = Modifier.weight(1f),
                        onClick = { onOpen(action) },
                    )
                }
            }
        }
    }
}

/** The whole entry is tappable, but its press indication stays inside the circular icon. */
@Composable
private fun ServiceEntry(label: String, icon: ImageVector, loading: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier = modifier
            .semantics { contentDescription = label }
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(48.dp)
                .clip(CircleShape)
                .background(MiuixTheme.colorScheme.secondaryContainer)
                .indication(interactionSource, LocalIndication.current),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) CircularProgressIndicator(Modifier.size(24.dp))
            else Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MiuixTheme.colorScheme.primary)
        }
        Text(label, fontSize = 12.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}
