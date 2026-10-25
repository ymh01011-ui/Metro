package code.name.monkey.retromusic.util

import android.util.TypedValue
import android.view.View
import androidx.fragment.app.Fragment
import code.name.monkey.retromusic.R

/**
 * نفس AppBackdrop() في Square (SquareApp.kt سطر 4017): أرضية واحدة محايدة ورا كل الصفحات،
 * مرسومة مرة واحدة برا التنقل نفسه، مش جوه كل صفحة.
 *
 * وقت الانتقال الصفحة اللي بتخرج بتعمل fade out والجديدة fade in في نفس الوقت، فاللي بيظهر
 * بينهم لحظيًا هو الأرضية دي. لو لونها مختلف عن لون الصفحات بيبان وميض، ولو هي نفس لون
 * الصفحة الأساسي مبيبانش. هنا اللون هو colorSurface، نفس اللي صفحات التفاصيل بتبدأ بيه.
 */
fun Fragment.ensureSharedPageGround() {
    val container = activity?.findViewById<View>(R.id.fragment_container) ?: return
    val typedValue = TypedValue()
    val resolved = requireContext().theme.resolveAttribute(
        com.google.android.material.R.attr.colorSurface, typedValue, true
    )
    if (resolved) container.setBackgroundColor(typedValue.data)
}
