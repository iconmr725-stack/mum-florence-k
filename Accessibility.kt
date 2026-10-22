package com.mumflorence.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/** Lets Florence read what is on screen in any app and tap buttons (e.g. WhatsApp "Send"). */
class FlorenceAccessibility : AccessibilityService() {
    companion object {
        @Volatile var instance: FlorenceAccessibility? = null

        fun readScreen(): String {
            val root = instance?.rootInActiveWindow ?: return ""
            val seen = LinkedHashSet<String>()
            collect(root, seen)
            return seen.joinToString(". ").take(1500)
        }

        private fun collect(n: AccessibilityNodeInfo, out: MutableSet<String>) {
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
            for (i in 0 until n.childCount) n.getChild(i)?.let { collect(it, out) }
        }

        fun click(label: String): Boolean {
            val root = instance?.rootInActiveWindow ?: return false
            for (node in root.findAccessibilityNodeInfosByText(label)) {
                var cur: AccessibilityNodeInfo? = node
                while (cur != null) {
                    if (cur.isClickable) return cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    cur = cur.parent
                }
            }
            return false
        }
    }

    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
