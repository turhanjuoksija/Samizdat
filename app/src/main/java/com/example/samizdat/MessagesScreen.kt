package com.example.samizdat

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

object ChatTimeFormatter {
    fun isSameCalendarDay(
        ts1: Long,
        ts2: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        val c1 = Calendar.getInstance(zone).apply { timeInMillis = ts1 }
        val c2 = Calendar.getInstance(zone).apply { timeInMillis = ts2 }
        return c1.get(Calendar.ERA) == c2.get(Calendar.ERA) &&
            c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
            c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    private fun isYesterday(
        ts: Long,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        val yesterday = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            add(Calendar.DAY_OF_YEAR, -1)
        }
        return isSameCalendarDay(ts, yesterday.timeInMillis, zone)
    }

    private fun isSameYear(
        ts: Long,
        nowMs: Long,
        zone: TimeZone = TimeZone.getDefault()
    ): Boolean {
        val c1 = Calendar.getInstance(zone).apply { timeInMillis = ts }
        val c2 = Calendar.getInstance(zone).apply { timeInMillis = nowMs }
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
    }

    fun formatMessageTime(
        ts: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.US
    ): String {
        val sdf = SimpleDateFormat("HH:mm", locale).apply { timeZone = zone }
        return sdf.format(Date(ts))
    }

    fun formatConversationTimestamp(
        ts: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.US
    ): String {
        return when {
            isSameCalendarDay(ts, nowMs, zone) -> formatMessageTime(ts, zone, locale)
            isYesterday(ts, nowMs, zone) -> "Yesterday"
            isSameYear(ts, nowMs, zone) -> {
                SimpleDateFormat("d MMM", locale).apply { timeZone = zone }.format(Date(ts))
            }
            else -> {
                SimpleDateFormat("d MMM yyyy", locale).apply { timeZone = zone }.format(Date(ts))
            }
        }
    }

    fun formatDateHeader(
        ts: Long,
        nowMs: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Locale.US
    ): String {
        return when {
            isSameCalendarDay(ts, nowMs, zone) -> "Today"
            isYesterday(ts, nowMs, zone) -> "Yesterday"
            isSameYear(ts, nowMs, zone) -> {
                SimpleDateFormat("d MMM", locale).apply { timeZone = zone }.format(Date(ts))
            }
            else -> {
                SimpleDateFormat("d MMM yyyy", locale).apply { timeZone = zone }.format(Date(ts))
            }
        }
    }
}

@Composable
fun MessagesScreen(
    viewModel: PeersViewModel,
    myNickname: String
) {
    val activePeerKey = viewModel.activeChatPeerKey
    val storedPeers by viewModel.storedPeers.collectAsState(initial = emptyList())

    if (activePeerKey != null) {
        ChatConversationScreen(
            peerKey = activePeerKey,
            storedPeers = storedPeers,
            viewModel = viewModel,
            myNickname = myNickname,
            onBack = { viewModel.closeChat() }
        )
    } else {
        ConversationListScreen(
            storedPeers = storedPeers,
            viewModel = viewModel,
            onOpenChat = { peerKey -> viewModel.openChat(peerKey) }
        )
    }
}

@Composable
private fun ConversationListScreen(
    storedPeers: List<Peer>,
    viewModel: PeersViewModel,
    onOpenChat: (String) -> Unit
) {
    val conversations by viewModel.recentConversations.collectAsState(initial = emptyList())
    val livePeersMap = viewModel.livePeerTimestamps.toMap()
    val nowMs = System.currentTimeMillis()
    val searchQuery = viewModel.chatSearchQuery

    var showNewChatDialog by remember { mutableStateOf(false) }
    var conversationToDelete by remember { mutableStateOf<Pair<String, String>?>(null) } // peerKey to displayName
    var contentMatchingPeerKeys by remember { mutableStateOf<Set<String>>(emptySet()) }

    LaunchedEffect(searchQuery, conversations) {
        contentMatchingPeerKeys = if (searchQuery.isBlank()) {
            emptySet()
        } else {
            viewModel.searchConversationPeerKeys(searchQuery)
        }
    }

    val filteredConversations = remember(conversations, storedPeers, searchQuery, contentMatchingPeerKeys) {
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        if (q.isEmpty()) {
            conversations
        } else {
            conversations.filter { msg ->
                val peerPk = msg.peerPublicKey
                val peer = storedPeers.find { it.publicKey == peerPk || it.onion == peerPk }
                val name = (peer?.nickname ?: peerPk).lowercase(Locale.getDefault())
                name.contains(q) ||
                    peerPk.lowercase(Locale.getDefault()).contains(q) ||
                    msg.content.lowercase(Locale.getDefault()).contains(q) ||
                    peerPk in contentMatchingPeerKeys
            }
        }
    }

    val (initialIndex, initialOffset) = viewModel.chatListScrollPosition
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = initialIndex,
        initialFirstVisibleItemScrollOffset = initialOffset
    )
    DisposableEffect(Unit) {
        onDispose {
            viewModel.chatListScrollPosition =
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showNewChatDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "New Chat")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Messages",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (conversations.isNotEmpty() || searchQuery.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.chatSearchQuery = it },
                    placeholder = { Text("Search chats...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.chatSearchQuery = "" }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (conversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No conversations yet.", color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Start a new chat with the + button!", style = MaterialTheme.typography.labelSmall)
                    }
                }
            } else if (filteredConversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No matching conversations.", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredConversations, key = { it.peerPublicKey }) { lastMsg ->
                        val peerPk = lastMsg.peerPublicKey
                        val peer = storedPeers.find { it.publicKey == peerPk || it.onion == peerPk }
                        val displayName = peer?.nickname ?: (peerPk.take(8) + "...")
                        val onionCandidate = peer?.onion ?: if (OnionUtils.isOnion(peerPk)) peerPk else null
                        val isLive = onionCandidate?.let { onion ->
                            val ts = livePeersMap[OnionUtils.ensureOnionSuffix(onion)]
                            ts != null && (nowMs - ts) <= PeersViewModel.LIVE_PEER_WINDOW_MS
                        } ?: false

                        ConversationItem(
                            displayName = displayName,
                            message = lastMsg,
                            isSavedContact = peer != null,
                            isLive = isLive,
                            nowMs = nowMs,
                            onClick = { onOpenChat(peerPk) },
                            onDeleteClick = { conversationToDelete = peerPk to displayName }
                        )
                    }
                }
            }
        }
    }

    if (showNewChatDialog) {
        NewChatDialog(
            peers = storedPeers,
            onDismiss = { showNewChatDialog = false },
            onPeerSelected = { peer ->
                showNewChatDialog = false
                onOpenChat(peer.publicKey)
            }
        )
    }

    if (conversationToDelete != null) {
        val (peerKey, displayName) = conversationToDelete!!
        DeleteConversationDialog(
            displayName = displayName,
            onConfirm = {
                viewModel.deleteConversation(peerKey)
                conversationToDelete = null
            },
            onDismiss = { conversationToDelete = null }
        )
    }
}

@Composable
private fun ChatConversationScreen(
    peerKey: String,
    storedPeers: List<Peer>,
    viewModel: PeersViewModel,
    myNickname: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val messages by viewModel.getMessagesForPeer(peerKey).collectAsState(initial = emptyList())
    val livePeersMap = viewModel.livePeerTimestamps.toMap()
    val nowMs = System.currentTimeMillis()

    val savedPeer = storedPeers.find { it.publicKey == peerKey || it.onion == peerKey }
    val displayName = savedPeer?.nickname ?: (peerKey.take(8) + "...")
    val peerOnion = savedPeer?.onion ?: if (OnionUtils.isOnion(peerKey)) peerKey else null
    val isLive = peerOnion?.let { onion ->
        val ts = livePeersMap[OnionUtils.ensureOnionSuffix(onion)]
        ts != null && (nowMs - ts) <= PeersViewModel.LIVE_PEER_WINDOW_MS
    } ?: false

    val resolvedPeer = remember(savedPeer, peerKey, displayName, peerOnion) {
        savedPeer ?: Peer(
            publicKey = peerKey,
            nickname = displayName,
            lastKnownIp = peerKey,
            onion = peerOnion,
            isTrusted = false,
            lastSeenTimestamp = System.currentTimeMillis()
        )
    }

    val draftText = viewModel.chatDrafts[peerKey] ?: ""
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showAddContactDialog by remember { mutableStateOf(false) }

    val (savedIndex, savedOffset) = viewModel.chatScrollPositions[peerKey] ?: (0 to 0)
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = savedIndex,
        initialFirstVisibleItemScrollOffset = savedOffset
    )
    DisposableEffect(peerKey) {
        onDispose {
            viewModel.chatScrollPositions[peerKey] =
                listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }
    }

    val reversedMessages = remember(messages) { messages.reversed() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Top App Bar with left-pointing back arrow
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }

                Surface(
                    shape = CircleShape,
                    color = if (savedPeer != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = displayName.take(1).uppercase(),
                            fontWeight = FontWeight.Bold,
                            color = if (savedPeer != null) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val subtitle = when {
                        isLive -> "Online"
                        savedPeer != null -> "Offline"
                        else -> "Not in contacts"
                    }
                    Text(
                        text = if (isLive) "🟢 $subtitle" else subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isLive) Color(0xFF2E7D32) else Color.Gray
                    )
                }

                if (savedPeer == null && peerOnion != null) {
                    IconButton(onClick = { showAddContactDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "Add Contact"
                        )
                    }
                }

                IconButton(onClick = { showDeleteDialog = true }) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Delete conversation"
                    )
                }
            }
        }

        // Messages List
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (reversedMessages.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No messages yet. Say hello!", color = Color.Gray)
                }
            } else {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(reversedMessages, key = { _, msg -> msg.id }) { index, msg ->
                        val olderMsg = reversedMessages.getOrNull(index + 1)
                        val showDateHeader = olderMsg == null ||
                            !ChatTimeFormatter.isSameCalendarDay(olderMsg.timestamp, msg.timestamp)

                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (showDateHeader) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
                                    ) {
                                        Text(
                                            text = ChatTimeFormatter.formatDateHeader(msg.timestamp, nowMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            ChatMessageBubble(
                                msg = msg,
                                onRetry = {
                                    viewModel.retryMessage(msg, resolvedPeer, myNickname)
                                    Toast.makeText(context, "Retrying message...", Toast.LENGTH_SHORT).show()
                                }
                            )
                        }
                    }
                }
            }
        }

        // Bottom Input Bar
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                OutlinedTextField(
                    value = draftText,
                    onValueChange = { viewModel.updateChatDraft(peerKey, it) },
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                FilledIconButton(
                    onClick = {
                        val trimmed = draftText.trim()
                        if (trimmed.isNotEmpty()) {
                            viewModel.sendMessage(resolvedPeer, trimmed, myNickname)
                            viewModel.updateChatDraft(peerKey, "")
                            coroutineScope.launch {
                                listState.animateScrollToItem(0)
                            }
                        }
                    },
                    enabled = draftText.isNotBlank(),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send"
                    )
                }
            }
        }
    }

    if (showDeleteDialog) {
        DeleteConversationDialog(
            displayName = displayName,
            onConfirm = {
                showDeleteDialog = false
                viewModel.deleteConversation(peerKey)
            },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showAddContactDialog && peerOnion != null) {
        var nicknameInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddContactDialog = false },
            title = { Text("Add to Contacts") },
            text = {
                Column {
                    Text(
                        text = "Save ${peerOnion.take(16)}... as a contact:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = nicknameInput,
                        onValueChange = { if (it.length <= 50) nicknameInput = it },
                        label = { Text("Nickname") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val cleanNick = nicknameInput.trim().ifEmpty { "Peer_${peerOnion.take(6)}" }
                        viewModel.addManualPeer(peerOnion, cleanNick)
                        Toast.makeText(context, "Saved $cleanNick to Contacts", Toast.LENGTH_SHORT).show()
                        showAddContactDialog = false
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddContactDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ChatMessageBubble(
    msg: ChatMessage,
    onRetry: () -> Unit
) {
    val isOutgoing = !msg.isIncoming
    val isFailed = isOutgoing && msg.status == "FAILED"
    val bubbleColor = if (isOutgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isOutgoing) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val bubbleShape = if (isOutgoing) {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start
    ) {
        Surface(
            shape = bubbleShape,
            color = bubbleColor,
            modifier = Modifier
                .widthIn(max = 290.dp)
                .then(if (isFailed) Modifier.clickable { onRetry() } else Modifier)
        ) {
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    text = msg.content,
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(
                    modifier = Modifier.align(Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = ChatTimeFormatter.formatMessageTime(msg.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 10.sp,
                        color = textColor.copy(alpha = 0.65f)
                    )
                    if (isOutgoing) {
                        val statusIcon = when (msg.status) {
                            "PENDING" -> "⌛"
                            "DELIVERED" -> "✔️✔️"
                            "FAILED" -> "❌"
                            else -> "✅"
                        }
                        Text(
                            text = statusIcon,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 10.sp,
                            color = if (isFailed) MaterialTheme.colorScheme.error else textColor.copy(alpha = 0.75f)
                        )
                    }
                }
            }
        }
        if (isFailed) {
            Text(
                text = "Failed • Tap to retry",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 2.dp, end = 4.dp)
                    .clickable { onRetry() }
            )
        }
    }
}

@Composable
private fun DeleteConversationDialog(
    displayName: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete Conversation") },
        text = { Text("Delete all messages with $displayName? This cannot be undone.") },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun ConversationItem(
    displayName: String,
    message: ChatMessage,
    isSavedContact: Boolean,
    isLive: Boolean,
    nowMs: Long,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = if (isSavedContact) MaterialTheme.colorScheme.primaryContainer else Color.LightGray,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = displayName.take(1).uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = if (isSavedContact) MaterialTheme.colorScheme.onPrimaryContainer else Color.Black
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isLive) "🟢 $displayName" else displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = ChatTimeFormatter.formatConversationTimestamp(message.timestamp, nowMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                }
                val previewPrefix = when {
                    message.isIncoming -> ""
                    message.status == "FAILED" -> "Failed: "
                    else -> "You: "
                }
                Text(
                    text = "$previewPrefix${message.content}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (!message.isIncoming && message.status == "FAILED") MaterialTheme.colorScheme.error else Color.DarkGray
                )
            }

            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.DeleteOutline,
                    contentDescription = "Delete conversation",
                    tint = Color.Gray
                )
            }
        }
    }
}

@Composable
fun NewChatDialog(
    peers: List<Peer>,
    onDismiss: () -> Unit,
    onPeerSelected: (Peer) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start a New Chat") },
        text = {
            if (peers.isEmpty()) {
                Text("No contacts found. Scan a QR code first!")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp)) {
                    items(peers) { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPeerSelected(peer) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(peer.nickname, style = MaterialTheme.typography.bodyLarge)
                            if (peer.isTrusted) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("✅", fontSize = 12.sp)
                            }
                        }
                        HorizontalDivider()
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
