package dev.octoshrimpy.quik.common.widget
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.FrameLayout
import dev.octoshrimpy.quik.R
import dev.octoshrimpy.quik.common.Navigator
import dev.octoshrimpy.quik.common.util.Colors
import dev.octoshrimpy.quik.common.util.extensions.setBackgroundTint
import dev.octoshrimpy.quik.databinding.AvatarViewBinding
import dev.octoshrimpy.quik.injection.appComponent
import dev.octoshrimpy.quik.model.Recipient
import dev.octoshrimpy.quik.util.GlideApp
import javax.inject.Inject
class AvatarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {
    @Inject lateinit var colors: Colors
    @Inject lateinit var navigator: Navigator
    private var lookupKey: String? = null
    private var fullName: String? = null
    private var address: String? = null
    private var photoUri: String? = null
    private var lastUpdated: Long? = null
    private var theme: Colors.Theme
    private var layout: AvatarViewBinding
    init {
        if (!isInEditMode) { appComponent.inject(this) }
        theme = colors.theme()
        layout = AvatarViewBinding.inflate(LayoutInflater.from(context), this)
        setBackgroundResource(R.drawable.circle)
        clipToOutline = true
    }
    fun setRecipient(recipient: Recipient?) {
        lookupKey = recipient?.contact?.lookupKey
        fullName = recipient?.contact?.name
        address = recipient?.address
        photoUri = recipient?.contact?.photoUri
        lastUpdated = recipient?.contact?.lastUpdate
        theme = colors.theme(recipient)
        updateView()
    }
    /** Override bg + text colour — called by GroupAvatarView for category-based styling. */
    fun applyCategoryStyle(bgColor: Int, textColor: Int) {
        setBackgroundTint(bgColor)
        layout.initial.setTextColor(textColor)
        layout.icon.setColorFilter(textColor)
    }
    override fun onFinishInflate() {
        super.onFinishInflate()
        if (!isInEditMode) updateView()
    }
    private fun updateView() {
        setBackgroundTint(theme.theme)
        layout.initial.setTextColor(theme.textPrimary)
        layout.icon.setColorFilter(theme.textPrimary)
        val initials = fullName
            ?.substringBefore(",")
            ?.split(" ").orEmpty()
            .filter { name -> name.isNotEmpty() }
            .map { name -> name[0] }
            .filter { initial -> initial.isLetterOrDigit() }
            .map { initial -> initial.toString() }
        layout.icon.visibility = GONE
        val label = when {
            initials.isNotEmpty() ->
                if (initials.size > 1) initials.first() + initials.last() else initials.first()
            else -> deriveAddressLabel(address)
        }
        layout.initial.text = label
        // Scale font: 1-2 chars → 14sp, 3 chars → 11sp, 4+ chars → 9sp (matches HTML .av font-size:11px)
        layout.initial.textSize = when {
            label.length <= 2 -> 14f
            label.length == 3 -> 11f
            else              -> 9f
        }
        layout.photo.setImageDrawable(null)
        photoUri?.let { uri -> GlideApp.with(layout.photo).load(uri).into(layout.photo) }
    }
    private fun deriveAddressLabel(addr: String?): String {
        if (addr.isNullOrBlank()) return "?"
        val parts = addr.split("-").filter { it.isNotEmpty() }
        return if (parts.size >= 2) {
            // Shortcode like "JD-FLPKRT-S" → take up to 4 chars from second segment
            parts[1].take(4).uppercase()
        } else {
            // Plain phone number or unknown → show "UK"
            "UK"
        }
    }
}
