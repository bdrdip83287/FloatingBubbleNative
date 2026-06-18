package com.dip83287.floatingbubble

import android.animation.Animator
import android.animation.ValueAnimator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.TextWatcher
import android.view.*
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.app.NotificationCompat
import androidx.core.view.doOnLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dip83287.floatingbubble.utils.EmergencyLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlin.math.abs

class FloatingBubbleService : Service() {

    // ... (বাকি কোড আগের মতোই থাকবে, শুধু showSelectionHandles() এবং updateHandlePositions() আপডেট হবে)

    // ✅ শুধুমাত্র এই ফাংশন দুটি আপডেট করা হয়েছে - বাকি কোড অপরিবর্তিত

    private fun updateHandlePositions() {
        if (isScrolling) {
            return
        }
        
        try {
            val currentLayout = editText.layout ?: return
            if (leftHandleView == null || rightHandleView == null) {
                return
            }

            val start = editText.selectionStart
            val end = editText.selectionEnd
            
            if (start == end || start < 0 || end < 0 || start > editText.text.length || end > editText.text.length) {
                return
            }

            val editLocation = IntArray(2)
            editText.getLocationOnScreen(editLocation)
            val editScreenX = editLocation[0]
            val editScreenY = editLocation[1]

            val startLine = currentLayout.getLineForOffset(start)
            val endLine = currentLayout.getLineForOffset(end)
            
            val startXRaw = currentLayout.getPrimaryHorizontal(start)
            val endXRaw = currentLayout.getPrimaryHorizontal(end)
            val scrollX = editText.scrollX
            val paddingLeft = editText.paddingLeft
            
            val startX = startXRaw - scrollX + paddingLeft
            val endX = endXRaw - scrollX + paddingLeft
            
            val startYRaw = currentLayout.getLineTop(startLine)
            val endYRaw = currentLayout.getLineTop(endLine)
            val scrollY = editText.scrollY
            val paddingTop = editText.paddingTop
            
            val startY = startYRaw - scrollY + paddingTop
            val endY = endYRaw - scrollY + paddingTop

            val handleSize = 40
            val halfHandle = handleSize / 2
            val upwardShift = dpToPx(15)

            val leftHandleScreenX = editScreenX + startX - halfHandle
            val leftHandleScreenY = editScreenY + startY - handleSize - upwardShift
            val rightHandleScreenX = editScreenX + endX - halfHandle
            val rightHandleScreenY = editScreenY + endY - handleSize - upwardShift
            
            val scrollLocation = IntArray(2)
            scrollView.getLocationOnScreen(scrollLocation)
            val viewportTop = scrollLocation[1]
            val viewportBottom = scrollLocation[1] + scrollView.height
            
            val isLeftInViewport = (leftHandleScreenY + handleSize > viewportTop && leftHandleScreenY < viewportBottom)
            val isRightInViewport = (rightHandleScreenY + handleSize > viewportTop && rightHandleScreenY < viewportBottom)

            if (isLeftInViewport) {
                leftHandleView?.let { handle ->
                    val params = handle.layoutParams as WindowManager.LayoutParams
                    params.x = leftHandleScreenX.toInt()
                    params.y = leftHandleScreenY.toInt()
                    try {
                        if (handle.parent == null) {
                            actionBarWindowManager?.addView(handle, params)
                        } else {
                            actionBarWindowManager?.updateViewLayout(handle, params)
                        }
                        handle.alpha = 1f
                    } catch (e: Exception) { }
                }
            } else if (leftHandleView?.parent != null) {
                leftHandleView?.let {
                    try { actionBarWindowManager?.removeView(it) } catch (e: Exception) { }
                }
            }
            
            if (isRightInViewport) {
                rightHandleView?.let { handle ->
                    val params = handle.layoutParams as WindowManager.LayoutParams
                    params.x = rightHandleScreenX.toInt()
                    params.y = rightHandleScreenY.toInt()
                    try {
                        if (handle.parent == null) {
                            actionBarWindowManager?.addView(handle, params)
                        } else {
                            actionBarWindowManager?.updateViewLayout(handle, params)
                        }
                        handle.alpha = 1f
                    } catch (e: Exception) { }
                }
            } else if (rightHandleView?.parent != null) {
                rightHandleView?.let {
                    try { actionBarWindowManager?.removeView(it) } catch (e: Exception) { }
                }
            }
            
        } catch (e: Exception) {
            EmergencyLog.logException(e, "updateHandlePositions")
        }
    }

    // ✅ Main function to show handles - creates new handles with correct positioning
    private fun showSelectionHandles() {
        try {
            val (start, end) = getSelection()
            if (start == end || start < 0 || end < 0) return
            
            // ✅ Always create fresh handles (remove old ones first)
            hideSelectionHandles()
            
            val handles = createSelectionHandles()
            leftHandleView = handles.first
            rightHandleView = handles.second
            
            val currentLayout = editText.layout
            if (currentLayout == null) return
            
            val location = IntArray(2)
            editText.getLocationOnScreen(location)
            val editScreenX = location[0]
            val editScreenY = location[1]
            
            val handleSize = 40
            val halfHandle = handleSize / 2
            val upwardShift = dpToPx(15)
            
            // ✅ Get selection bounds
            val startLine = currentLayout.getLineForOffset(start)
            val endLine = currentLayout.getLineForOffset(end)
            
            // ✅ Start position
            val startXRaw = currentLayout.getPrimaryHorizontal(start)
            val startYRaw = currentLayout.getLineTop(startLine)
            val scrollX = editText.scrollX
            val scrollY = editText.scrollY
            val paddingLeft = editText.paddingLeft
            val paddingTop = editText.paddingTop
            
            val startX = startXRaw - scrollX + paddingLeft
            val startY = startYRaw - scrollY + paddingTop
            
            // ✅ End position
            val endXRaw = currentLayout.getPrimaryHorizontal(end)
            val endYRaw = currentLayout.getLineTop(endLine)
            
            val endX = endXRaw - scrollX + paddingLeft
            val endY = endYRaw - scrollY + paddingTop
            
            // ✅ Calculate screen coordinates
            val leftHandleScreenX = editScreenX + startX - halfHandle
            val leftHandleScreenY = editScreenY + startY - handleSize - upwardShift
            val rightHandleScreenX = editScreenX + endX - halfHandle
            val rightHandleScreenY = editScreenY + endY - handleSize - upwardShift
            
            // ✅ Check viewport visibility
            val scrollLocation = IntArray(2)
            scrollView.getLocationOnScreen(scrollLocation)
            val viewportTop = scrollLocation[1]
            val viewportBottom = scrollLocation[1] + scrollView.height
            
            val isLeftInViewport = (leftHandleScreenY + handleSize > viewportTop && leftHandleScreenY < viewportBottom)
            val isRightInViewport = (rightHandleScreenY + handleSize > viewportTop && rightHandleScreenY < viewportBottom)
            
            // ✅ Add left handle at correct position
            if (isLeftInViewport) {
                val leftParams = WindowManager.LayoutParams(
                    handleSize, handleSize,
                    if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                leftParams.gravity = Gravity.TOP or Gravity.START
                leftParams.x = leftHandleScreenX.toInt()
                leftParams.y = leftHandleScreenY.toInt()
                try {
                    actionBarWindowManager?.addView(leftHandleView, leftParams)
                    leftHandleView?.alpha = 1f
                } catch (e: Exception) { }
            }
            
            // ✅ Add right handle at correct position
            if (isRightInViewport) {
                val rightParams = WindowManager.LayoutParams(
                    handleSize, handleSize,
                    if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                rightParams.gravity = Gravity.TOP or Gravity.START
                rightParams.x = rightHandleScreenX.toInt()
                rightParams.y = rightHandleScreenY.toInt()
                try {
                    actionBarWindowManager?.addView(rightHandleView, rightParams)
                    rightHandleView?.alpha = 1f
                } catch (e: Exception) { }
            }
            
            handlesHiddenByScroll = false
        } catch (e: Exception) {
            EmergencyLog.logException(e, "showSelectionHandles")
        }
    }

    // ... (বাকি কোড অপরিবর্তিত থাকবে - onCreate থেকে onDestroy পর্যন্ত আগের মতোই)
}
