package com.automation.fbautoreply.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.automation.fbautoreply.data.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class CommentAutoReplyService : AccessibilityService() {

    companion object {
        private const val TAG = "CommentAutoReplyService"
        val TARGET_PACKAGES = setOf(
            "com.facebook.katana",
            "com.facebook.lite",
            "com.facebook.orca"
        )
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val isProcessingAction = AtomicBoolean(false)

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // 1. Check if automation is active
        if (!PreferenceManager.isBotActive(this)) {
            return
        }

        // Avoid concurrent overlapping flows
        if (isProcessingAction.get()) {
            return
        }

        // Check target package
        val packageName = event.packageName?.toString() ?: ""
        if (packageName.isNotEmpty() && !TARGET_PACKAGES.contains(packageName)) {
            return
        }

        val eventType = event.eventType
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED ||
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            evaluateVisibleComments()
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.i(TAG, "Accessibility Service Destroyed")
    }

    private fun evaluateVisibleComments() {
        if (!isProcessingAction.compareAndSet(false, true)) {
            return
        }

        serviceScope.launch {
            try {
                processSingleAutoReply()
            } catch (e: Exception) {
                Log.e(TAG, "Error during auto-reply execution", e)
            } finally {
                // Cooldown delay before releasing the execution lock
                delay(1500L)
                isProcessingAction.set(false)
            }
        }
    }

    private suspend fun processSingleAutoReply() {
        val rootNode = rootInActiveWindow ?: return
        val delayMs = PreferenceManager.getActionDelayMs(this)
        val triggerKeywords = PreferenceManager.getTriggerKeywords(this)
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }

        if (triggerKeywords.isEmpty()) {
            return
        }

        // Step 1: Detect matching comment
        val candidate = findMatchingCommentNode(rootNode, triggerKeywords) ?: return
        val commentNode = candidate.node
        val commentSignature = candidate.signature
        val commentRect = Rect()
        commentNode.getBoundsInScreen(commentRect)

        Log.d(TAG, "Found target comment: $commentSignature at $commentRect")

        // Step 2: Find and click nearest "Reply" button
        val replyButtonNode = findReplyButtonForComment(commentNode, commentRect)
        if (replyButtonNode == null) {
            Log.d(TAG, "No suitable Reply button found for comment: $commentSignature")
            return
        }

        val clicked = clickNodeOrGesture(replyButtonNode)
        if (!clicked) {
            Log.w(TAG, "Failed to click Reply button")
            return
        }

        Log.d(TAG, "Successfully clicked Reply button. Waiting ${delayMs}ms for input field...")
        delay(delayMs)

        // Step 3: Find active EditText and inject configured reply message
        val replyMessage = PreferenceManager.getReplyMessage(this)
        val freshRootAfterClick = rootInActiveWindow ?: return
        val editNode = findActiveEditText(freshRootAfterClick)
        if (editNode == null) {
            Log.w(TAG, "Could not find active EditText after clicking reply")
            return
        }

        val editRect = Rect()
        editNode.getBoundsInScreen(editRect)
        val injected = injectReplyMessage(editNode, replyMessage)
        if (!injected) {
            Log.w(TAG, "Failed to inject reply message into EditText")
            return
        }

        Log.d(TAG, "Message injected. Waiting ${delayMs}ms before clicking Send...")
        delay(delayMs)

        // Step 4: Find and click Send icon
        val freshRootForSend = rootInActiveWindow ?: return
        val sendClicked = clickSendButton(freshRootForSend, editRect)
        if (sendClicked) {
            PreferenceManager.markAsReplied(commentSignature)
            PreferenceManager.incrementRepliedCount(this)
            Log.i(TAG, "Auto-reply sent successfully! Signature: $commentSignature")
        } else {
            Log.w(TAG, "Failed to click Send button")
        }
    }

    private data class CommentCandidate(
        val node: AccessibilityNodeInfo,
        val signature: String
    )

    private fun findMatchingCommentNode(
        root: AccessibilityNodeInfo,
        keywords: List<String>
    ): CommentCandidate? {
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.add(root)

        while (stack.isNotEmpty()) {
            val node = stack.removeFirst()

            // Skip editable fields (e.g. comment box itself)
            if (node.isEditable || node.className == "android.widget.EditText") {
                recycleChildren(node, stack, skipChildren = true)
                continue
            }

            val text = node.text?.toString() ?: ""
            if (text.length in 2..400) {
                val lowerText = text.lowercase()
                val hasMatch = keywords.any { kw ->
                    lowerText.contains(kw)
                }

                if (hasMatch) {
                    val bounds = Rect()
                    node.getBoundsInScreen(bounds)
                    val parentAuthor = getAuthorOrParentContext(node)
                    // Signature based on text, author/parent context, and normalized vertical bucket
                    val signature = "${text.take(40)}_${parentAuthor}_${bounds.top / 40}"

                    if (!PreferenceManager.isAlreadyReplied(signature)) {
                        return CommentCandidate(node, signature)
                    }
                }
            }

            recycleChildren(node, stack, skipChildren = false)
        }
        return null
    }

    private fun recycleChildren(
        node: AccessibilityNodeInfo,
        stack: ArrayDeque<AccessibilityNodeInfo>,
        skipChildren: Boolean
    ) {
        if (!skipChildren) {
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    stack.add(child)
                }
            }
        }
    }

    private fun getAuthorOrParentContext(node: AccessibilityNodeInfo): String {
        val parent = node.parent ?: return "unknown"
        val childCount = parent.childCount
        for (i in 0 until childCount) {
            val sibling = parent.getChild(i) ?: continue
            if (sibling != node && !sibling.text.isNullOrBlank()) {
                val sText = sibling.text.toString().trim()
                if (sText.length in 2..30 && !sText.equals("Reply", ignoreCase = true)) {
                    return sText
                }
            }
        }
        return parent.className?.toString() ?: "context"
    }

    private fun findReplyButtonForComment(
        commentNode: AccessibilityNodeInfo,
        commentRect: Rect
    ): AccessibilityNodeInfo? {
        val replyKeywords = listOf("reply", "উত্তর দিন", "responder", "balas", "répondre")

        // Search upward within 3 ancestor levels and their subtrees
        var current: AccessibilityNodeInfo? = commentNode
        var level = 0
        while (current != null && level < 3) {
            val candidate = searchSubtreeForReplyButton(current, commentRect, replyKeywords)
            if (candidate != null) {
                return candidate
            }
            current = current.parent
            level++
        }

        // Fallback: search entire active window near the comment rect
        val root = rootInActiveWindow ?: return null
        return searchSubtreeForReplyButton(root, commentRect, replyKeywords)
    }

    private fun searchSubtreeForReplyButton(
        root: AccessibilityNodeInfo,
        commentRect: Rect,
        replyKeywords: List<String>
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var bestCandidate: AccessibilityNodeInfo? = null
        var minVerticalDistance = Int.MAX_VALUE

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val text = (node.text?.toString() ?: "")
            val contentDesc = (node.contentDescription?.toString() ?: "")

            val matchesText = replyKeywords.any { kw ->
                text.equals(kw, ignoreCase = true) || contentDesc.equals(kw, ignoreCase = true)
            }

            if (matchesText) {
                val nodeRect = Rect()
                node.getBoundsInScreen(nodeRect)

                // Must be at or slightly below commentRect, within 350px below comment bottom
                val isBelowOrAligned = nodeRect.top >= (commentRect.top - 20)
                val verticalDist = nodeRect.top - commentRect.bottom
                val isWithinVerticalLimit = verticalDist <= 350

                if (isBelowOrAligned && isWithinVerticalLimit) {
                    val dist = abs(verticalDist)
                    if (dist < minVerticalDistance) {
                        minVerticalDistance = dist
                        bestCandidate = node
                    }
                }
            }

            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
        }

        return bestCandidate
    }

    private fun findActiveEditText(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable || node.className == "android.widget.EditText" || node.isFocused) {
                return node
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
        }
        return null
    }

    private fun injectReplyMessage(editNode: AccessibilityNodeInfo, message: String): Boolean {
        // Primary method: ACTION_SET_TEXT
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, message)
        }
        val textSetSuccess = editNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        if (textSetSuccess) {
            return true
        }

        // Fallback method: ClipboardManager + ACTION_FOCUS + ACTION_PASTE
        return try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("AutoReply", message)
            clipboard.setPrimaryClip(clip)

            editNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            editNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
        } catch (e: Exception) {
            Log.e(TAG, "Fallback injection failed", e)
            false
        }
    }

    private fun clickSendButton(root: AccessibilityNodeInfo, editRect: Rect): Boolean {
        val sendKeywords = listOf("send", "post", "comment", "পাঠান", "publicar", "kirim")
        val displayWidth = resources.displayMetrics.widthPixels

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var spatialFallbackCandidate: AccessibilityNodeInfo? = null

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            val contentDesc = (node.contentDescription?.toString() ?: "").lowercase()
            val viewId = (node.viewIdResourceName?.toString() ?: "").lowercase()
            val text = (node.text?.toString() ?: "").lowercase()

            val isKeywordMatch = sendKeywords.any { kw ->
                contentDesc.contains(kw) || viewId.contains(kw) || text.contains(kw)
            }

            if (isKeywordMatch) {
                if (clickNodeOrGesture(node)) {
                    return true
                }
            }

            // Spatial Heuristic Check: Right side of screen, vertically aligned with EditText
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val isClickableType = node.isClickable ||
                    node.className == "android.widget.ImageView" ||
                    node.className == "android.widget.ImageButton" ||
                    node.className == "android.widget.Button"

            if (isClickableType && rect.left > displayWidth * 0.70) {
                val verticalAlignmentDiff = abs(rect.centerY() - editRect.centerY())
                if (verticalAlignmentDiff < 150) {
                    spatialFallbackCandidate = node
                }
            }

            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
        }

        // If no direct keyword match, attempt spatial fallback candidate
        if (spatialFallbackCandidate != null) {
            Log.d(TAG, "Attempting spatial fallback send button click")
            return clickNodeOrGesture(spatialFallbackCandidate)
        }

        return false
    }

    private fun clickNodeOrGesture(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }

        // Check if any clickable parent exists
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 3) {
            if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            parent = parent.parent
            depth++
        }

        // Coordinate click gesture as fallback
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            return dispatchClickGesture(rect.centerX().toFloat(), rect.centerY().toFloat())
        }
        return false
    }

    private fun dispatchClickGesture(x: Float, y: Float): Boolean {
        val path = Path().apply {
            moveTo(x, y)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        return dispatchGesture(gesture, null, null)
    }
}
