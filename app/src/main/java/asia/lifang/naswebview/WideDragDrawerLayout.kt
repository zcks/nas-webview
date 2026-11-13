package asia.lifang.naswebview

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.view.GravityCompat
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.abs

/**
 * QQ 式宽区侧滑抽屉：
 * 屏幕任意位置横向右滑 → 拉出左侧边栏；抽屉展开时左滑 → 收起。
 *
 * 原生 DrawerLayout 只认最边缘约 20dp 的拖拽，而那条区域恰好被
 * Android 全面屏的系统返回手势占据，导致"侧滑"名存实亡、只能点
 * 细竖条。这里以方向锁（横向位移占优）接管全屏手势来复刻 QQ 手感。
 */
class WideDragDrawerLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : DrawerLayout(context, attrs) {

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swipeHandled = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                swipeHandled = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!swipeHandled) {
                    val dx = ev.x - downX
                    val dy = ev.y - downY
                    val horizontal = abs(dx) > abs(dy) * 1.2f && abs(dx) > touchSlop
                    if (horizontal && !isDrawerOpen(GravityCompat.START) && dx > 0) {
                        // 全屏右滑 → 开抽屉
                        swipeHandled = true
                        openDrawer(GravityCompat.START, true)
                        return true
                    }
                    if (horizontal && isDrawerOpen(GravityCompat.START) && dx < 0) {
                        // 抽屉开着时全屏左滑 → 关抽屉
                        swipeHandled = true
                        closeDrawer(GravityCompat.START, true)
                        return true
                    }
                }
            }
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (swipeHandled) {
            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                ev.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                swipeHandled = false
            }
            return true // 吞掉本次手势的剩余事件，避免干扰网页
        }
        return super.onTouchEvent(ev)
    }
}
