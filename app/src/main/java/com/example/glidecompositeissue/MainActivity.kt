package com.example.glidecompositeissue

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.util.Util
import com.example.glidecompositeissue.databinding.ActivityMainBinding
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.shape.ShapeAppearanceModel
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.security.MessageDigest

class MainActivity : Activity() {
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val background = MaterialShapeDrawable.createWithElevationOverlay(this, 1f).apply {
            fillColor = ColorStateList.valueOf(Color.DKGRAY)
            elevation = 1f
            shapeAppearanceModel = ShapeAppearanceModel.builder()
                .setAllCornerSizes(resources.getDimension(R.dimen.avatar_background_radius))
                .build()
        }

        // The code demonstrates the bug. To "fix" it, replace the use of MaterialShapeDrawable
        // with a simple background colour, by uncommenting the line below, and assigning
        // backgroundJustColor to binding.avatar.background
        //val backgroundJustColor = ColorDrawable(Color.DKGRAY)

        binding.avatar.background = background

        val cornerRadiusPx = resources.getDimensionPixelSize(R.dimen.avatar_radius)

        // Load the image without performing the CompositeWithOpaqueBackground operation
        binding.loadAvatar.setOnClickListener {
            Glide.with(binding.avatar)
                .load(R.drawable.semi_transparent_avatar)
                .transform(
                    CenterCrop(),
                    RoundedCorners(cornerRadiusPx)
                )
                .into(binding.avatar)
        }

        // Load the image and perform the CompositeWithOpaqueBackground operation
        binding.loadAvatarComposite.setOnClickListener {
            Glide.with(binding.avatar)
                .load(R.drawable.semi_transparent_avatar)
                .transform(
                    CompositeWithOpaqueBackground(binding.avatar),
                    CenterCrop(),
                    RoundedCorners(cornerRadiusPx)
                )
                .into(binding.avatar)

        }
    }
}

/**
 * Set an opaque background behind the non-transparent areas of a bitmap.
 *
 * Profile images may have areas that are partially transparent (i.e., alpha value >= 1 and < 255).
 *
 * Displaying those can be a problem if there is anything drawn under them, as it will show
 * through the image.
 *
 * Fix this, by:
 *
 * - Creating a mask that matches the partially transparent areas of the image
 * - Creating a new bitmap that, in the areas that match the mask, contains the same background
 *   drawable as the [ImageView].
 * - Composite the original image over the top
 *
 * So the partially transparent areas on the original image are composited over the original
 * background, the fully transparent areas on the original image are left transparent.
 */
class CompositeWithOpaqueBackground(val view: View) : BitmapTransformation() {
    override fun equals(other: Any?): Boolean {
        if (other is CompositeWithOpaqueBackground) {
            return other.view == view
        }
        return false
    }

    override fun hashCode() = Util.hashCode(ID.hashCode(), view.hashCode())
    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update(ID_BYTES)
        messageDigest.update(ByteBuffer.allocate(4).putInt(view.hashCode()).array())
    }

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        // If the input bitmap has no alpha channel then there's nothing to do
        if (!toTransform.hasAlpha()) return toTransform

        Log.d(TAG, "toTransform: ${toTransform.width} ${toTransform.height}")
        // Get the background drawable for this view, falling back to the given attribute
        val backgroundDrawable = view.getFirstNonNullBackgroundOrAttr(android.R.attr.colorBackground)
        backgroundDrawable ?: return toTransform

        // Convert the background to a bitmap.
        val backgroundBitmap = pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        when (backgroundDrawable) {
            is ColorDrawable -> backgroundBitmap.eraseColor(backgroundDrawable.color)
            else -> {
                val backgroundCanvas = Canvas(backgroundBitmap)
                backgroundDrawable.setBounds(0, 0, outWidth, outHeight)
                backgroundDrawable.draw(backgroundCanvas)
            }
        }

        // Convert the alphaBitmap (where the alpha channel has 8bpp) to a mask of 1bpp
        // TODO: toTransform.extractAlpha(paint, ...) could be used here, but I can't find any
        // useful documentation covering paints and mask filters.
        val maskBitmap = pool.get(outWidth, outHeight, Bitmap.Config.ALPHA_8).apply {
            val canvas = Canvas(this)
            canvas.drawBitmap(toTransform, 0f, 0f, EXTRACT_MASK_PAINT)
        }

        val shader = BitmapShader(backgroundBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        val paintShader = Paint()
        paintShader.isAntiAlias = true
        paintShader.shader = shader
        paintShader.style = Paint.Style.FILL_AND_STROKE

        // Write the background to a new bitmap, masked to just the non-transparent areas of the
        // original image
        val dest = pool.get(outWidth, outHeight, toTransform.config)
        val canvas = Canvas(dest)
        canvas.drawBitmap(maskBitmap, 0f, 0f, paintShader)

        // Finally, write the original bitmap over the top
        canvas.drawBitmap(toTransform, 0f, 0f, null)

        // Clean up intermediate bitmaps
        pool.put(maskBitmap)
        pool.put(backgroundBitmap)

        return dest
    }

    companion object {
        @Suppress("unused")
        private const val TAG = "CompositeWithOpaqueBackground"
        private val ID = CompositeWithOpaqueBackground::class.qualifiedName!!
        private val ID_BYTES = ID.toByteArray(Charset.forName("UTF-8"))

        /** Paint with a color filter that converts 8bpp alpha images to a 1bpp mask */
        private val EXTRACT_MASK_PAINT = Paint().apply {
            colorFilter = ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 0f, 0f,
                        0f, 0f, 0f, 255f, 0f
                    )
                )
            )
            isAntiAlias = false
        }

        /**
         * @param attr default drawable if no background is set on this view or any of its
         *      ancestors
         * @return The first non-null background drawable from this view, or its ancestors,
         *      falling back to the drawable given by `attr` if none of the views have a
         *      background.
         */
        private fun View.getFirstNonNullBackgroundOrAttr(@DrawableRes attr: Int): Drawable? =
            background ?: (parent as? View)?.getFirstNonNullBackgroundOrAttr(attr) ?: run {
                val v = TypedValue()
                context.theme.resolveAttribute(attr, v, true)
                // TODO: On API 29 can use v.isColorType here
                if (v.type >= TypedValue.TYPE_FIRST_COLOR_INT && v.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    ColorDrawable(v.data)
                } else {
                    ContextCompat.getDrawable(context, v.resourceId)
                }
            }
    }
}
