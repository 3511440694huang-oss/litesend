package app.qingchuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.qingchuan.core.Contact
import app.qingchuan.ui.components.Avatar
import app.qingchuan.ui.components.EmptyState
import app.qingchuan.ui.components.QcGroup
import app.qingchuan.ui.components.RowSeparator
import app.qingchuan.ui.theme.QcTheme

/**
 * 常用对象（联系人）管理：盖在整页之上的浮层。
 *
 * 交互只保留两条主线：点整行 = 用存下来的地址直接连；右上「⋯」= 重命名 / 删除。
 * 这里不碰网络，也不持有联系人状态 —— 数据由上层 collect [app.qingchuan.core.ContactStore] 后传进来，
 * 增删改一律通过回调交给上层，避免界面和存储两边各有一份真相。
 */
@Composable
fun ContactsScreen(
    contacts: List<Contact>,
    onBack: () -> Unit,
    onConnect: (Contact) -> Unit,
    onAdd: (name: String, host: String, port: Int) -> Unit,
    onRename: (Contact, String) -> Unit,
    onRemove: (Contact) -> Unit,
) {
    // 「⋯」当前展开在哪一行；同一时刻只会有一个菜单
    var menuFor by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Contact?>(null) }
    var removing by remember { mutableStateOf<Contact?>(null) }

    // 存储层已经排好序，这里再排一次只是不让界面依赖调用方的顺序
    val rows = remember(contacts) { contacts.sortedByDescending { it.lastUsedAt } }

    Column(
        // 浮层必须自带不透明底色，否则会看到下面那层首页
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "常用对象",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "完成",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onBack)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "手动连过的地址会存在这里，点一下直接发",
                style = MaterialTheme.typography.bodySmall,
                color = QcTheme.extra.textSecondary,
            )
        }

        if (rows.isEmpty()) {
            // 空态居中放：列表区整块留给「还没有常用对象」，别贴在标题下面
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "还没有常用对象",
                    subtitle = "在首页手动添加设备时可以保存",
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
            ) {
                item {
                    QcGroup(Modifier.fillMaxWidth()) {
                        rows.forEachIndexed { index, contact ->
                            ContactListRow(
                                contact = contact,
                                menuOpen = menuFor == contact.id,
                                onConnect = { onConnect(contact) },
                                onMenu = { menuFor = contact.id },
                                onDismissMenu = { menuFor = null },
                                onRename = {
                                    menuFor = null
                                    renaming = contact
                                },
                                onRemove = {
                                    menuFor = null
                                    removing = contact
                                },
                            )
                            if (index != rows.lastIndex) RowSeparator(startInset = 74.dp)
                        }
                    }
                }
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 12.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable { adding = true }
                .padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                "+ 添加常用对象",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }

    if (adding) {
        AddContactDialog(
            onDismiss = { adding = false },
            onConfirm = { name, host, port ->
                adding = false
                onAdd(name, host, port)
            },
        )
    }

    renaming?.let { target ->
        RenameContactDialog(
            contact = target,
            onDismiss = { renaming = null },
            onConfirm = { name ->
                renaming = null
                onRename(target, name)
            },
        )
    }

    removing?.let { target ->
        DeleteContactDialog(
            contact = target,
            onDismiss = { removing = null },
            onConfirm = {
                removing = null
                onRemove(target)
            },
        )
    }
}

/** 一行：头像 + 名称/地址 + 「连接」+「⋯」。点整行和点「连接」都是连接。 */
@Composable
private fun ContactListRow(
    contact: Contact,
    menuOpen: Boolean,
    onConnect: () -> Unit,
    onMenu: () -> Unit,
    onDismissMenu: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onConnect)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact.name, size = 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                contact.addr,
                style = MaterialTheme.typography.bodySmall,
                color = QcTheme.extra.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "连接",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onConnect)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Box {
            // 给「⋯」一个方一点的点击区，纯文字太小不好按
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onMenu),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "⋯",
                    style = MaterialTheme.typography.labelLarge,
                    color = QcTheme.extra.textSecondary,
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = onDismissMenu) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = onRename)
                DropdownMenuItem(
                    text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                    onClick = onRemove,
                )
            }
        }
    }
}

/** 新增：名称可空、IP 必填、端口可空（留空 = 交给上层走默认端口）。 */
@Composable
private fun AddContactDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, host: String, port: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("") }

    // 粘贴来的 IP 常带空格，边输边去掉，确认时也再去一次
    val ip = host.trim().filterNot { it.isWhitespace() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加常用对象", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { v -> name = v.take(40) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("名称") },
                    placeholder = { Text("小美的手机") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = host,
                    onValueChange = { v -> host = v.take(64) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("IP") },
                    placeholder = { Text("192.168.1.8") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { v -> port = v.filter { it.isDigit() }.take(5) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("端口") },
                    placeholder = { Text("53318") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "只填 IP 也能连；端口留空就用默认的。",
                    style = MaterialTheme.typography.bodySmall,
                    color = QcTheme.extra.textTertiary,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = ip.isNotEmpty(),
                onClick = {
                    // 端口非数字/越界都记 0，上层按 0 换成默认端口
                    val parsedPort = port.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: 0
                    onConfirm(name.trim(), ip, parsedPort)
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun RenameContactDialog(
    contact: Contact,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    // 用 id 做 key：连着给两个对象改名时不会把上一份草稿带过来
    var text by remember(contact.id) { mutableStateOf(contact.name) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("重命名", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.take(40) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("名称") },
                    placeholder = { Text(contact.host) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "留空就用 IP 当名字。",
                    style = MaterialTheme.typography.bodySmall,
                    color = QcTheme.extra.textTertiary,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

/** 删除要二次确认：这条记录是用户手动存的，误删就得重新输 IP。 */
@Composable
private fun DeleteContactDialog(
    contact: Contact,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("删除常用对象", style = MaterialTheme.typography.titleMedium) },
        text = {
            Text(
                "「${contact.name}」会从列表里删掉，下次要重新手动输入 IP。",
                style = MaterialTheme.typography.bodySmall,
                color = QcTheme.extra.textSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("删除", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
