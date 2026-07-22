/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

package code.name.monkey.retromusic.util;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Color;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import code.name.monkey.appthemehelper.ThemeStore;
import code.name.monkey.appthemehelper.util.ColorUtil;
import code.name.monkey.appthemehelper.util.VersionUtils;

public class RetroColorUtil {
  public static int desaturateColor(int color, float ratio) {
    float[] hsv = new float[3];
    Color.colorToHSV(color, hsv);

    hsv[1] = (hsv[1] * ratio) + (0.2f * (1.0f - ratio));

    return Color.HSVToColor(hsv);
  }

  @Nullable
  public static Palette generatePalette(@Nullable Bitmap bitmap) {
    if (bitmap == null) {
      return null;
    }
    return new Palette.Builder(bitmap)
        .clearFilters()
        .maximumColorCount(32)
        .generate();
  }

  public static int getTextColor(@Nullable Palette palette) {
    if (palette == null) {
      return -1;
    }

    int inverse = -1;
    if (palette.getVibrantSwatch() != null) {
      inverse = palette.getVibrantSwatch().getRgb();
    } else if (palette.getLightVibrantSwatch() != null) {
      inverse = palette.getLightVibrantSwatch().getRgb();
    } else if (palette.getDarkVibrantSwatch() != null) {
      inverse = palette.getDarkVibrantSwatch().getRgb();
    }

    int background = getSwatch(palette).getRgb();

    if (inverse != -1) {
      return ColorUtil.INSTANCE.getReadableText(inverse, background, 150);
    }
    return ColorUtil.INSTANCE.stripAlpha(getSwatch(palette).getTitleTextColor());
  }

  @NonNull
  public static Palette.Swatch getSwatch(@Nullable Palette palette) {
    if (palette == null) {
      return new Palette.Swatch(Color.WHITE, 1);
    }
    return getBestPaletteSwatchFrom(palette.getSwatches());
  }

  public static int getMatColor(Context context, String typeColor) {
    int returnColor = Color.BLACK;
    int arrayId =
        context
            .getResources()
            .getIdentifier(
                "md_" + typeColor, "array", context.getApplicationContext().getPackageName());

    if (arrayId != 0) {
      TypedArray colors = context.getResources().obtainTypedArray(arrayId);
      int index = (int) (Math.random() * colors.length());
      returnColor = colors.getColor(index, Color.BLACK);
      colors.recycle();
    }
    return returnColor;
  }

  @ColorInt
  public static int getColor(@Nullable Palette palette, int fallback) {
    if (palette != null) {
      if (palette.getVibrantSwatch() != null) {
        return palette.getVibrantSwatch().getRgb();
      } else if (palette.getDarkVibrantSwatch() != null) {
        return palette.getDarkVibrantSwatch().getRgb();
      } else if (palette.getLightVibrantSwatch() != null) {
        return palette.getLightVibrantSwatch().getRgb();
      } else if (palette.getMutedSwatch() != null) {
        return palette.getMutedSwatch().getRgb();
      } else if (palette.getLightMutedSwatch() != null) {
        return palette.getLightMutedSwatch().getRgb();
      } else if (palette.getDarkMutedSwatch() != null) {
        return palette.getDarkMutedSwatch().getRgb();
      } else if (!palette.getSwatches().isEmpty()) {
        return Collections.max(palette.getSwatches(), SwatchComparator.getInstance()).getRgb();
      }
    }
    return fallback;
  }

  private static Palette.Swatch getTextSwatch(@Nullable Palette palette) {
    if (palette == null) {
      return new Palette.Swatch(Color.BLACK, 1);
    }
    if (palette.getVibrantSwatch() != null) {
      return palette.getVibrantSwatch();
    } else {
      return new Palette.Swatch(Color.BLACK, 1);
    }
  }

  @ColorInt
  public static int getBackgroundColor(@Nullable Palette palette) {
    return getProperBackgroundSwatch(palette).getRgb();
  }

  private static Palette.Swatch getProperBackgroundSwatch(@Nullable Palette palette) {
    if (palette == null) {
      return new Palette.Swatch(Color.BLACK, 1);
    }
    if (palette.getDarkMutedSwatch() != null) {
      return palette.getDarkMutedSwatch();
    } else if (palette.getMutedSwatch() != null) {
      return palette.getMutedSwatch();
    } else if (palette.getLightMutedSwatch() != null) {
      return palette.getLightMutedSwatch();
    } else {
      return new Palette.Swatch(Color.BLACK, 1);
    }
  }

  private static Palette.Swatch getBestPaletteSwatchFrom(Palette palette) {
    if (palette != null) {
      if (palette.getVibrantSwatch() != null) {
        return palette.getVibrantSwatch();
      } else if (palette.getMutedSwatch() != null) {
        return palette.getMutedSwatch();
      } else if (palette.getDarkVibrantSwatch() != null) {
        return palette.getDarkVibrantSwatch();
      } else if (palette.getDarkMutedSwatch() != null) {
        return palette.getDarkMutedSwatch();
      } else if (palette.getLightVibrantSwatch() != null) {
        return palette.getLightVibrantSwatch();
      } else if (palette.getLightMutedSwatch() != null) {
        return palette.getLightMutedSwatch();
      } else if (!palette.getSwatches().isEmpty()) {
        return getBestPaletteSwatchFrom(palette.getSwatches());
      }
    }
    return null;
  }

  private static Palette.Swatch getBestPaletteSwatchFrom(List<Palette.Swatch> swatches) {
    if (swatches == null) {
      return null;
    }
    return Collections.max(
        swatches,
        (opt1, opt2) -> {
          int a = opt1 == null ? 0 : opt1.getPopulation();
          int b = opt2 == null ? 0 : opt2.getPopulation();
          return a - b;
        });
  }

  public static int getDominantColor(Bitmap bitmap, int defaultFooterColor) {
    List<Palette.Swatch> swatchesTemp = Palette.from(bitmap).generate().getSwatches();
    List<Palette.Swatch> swatches = new ArrayList<>(swatchesTemp);
    Collections.sort(
        swatches, (swatch1, swatch2) -> swatch2.getPopulation() - swatch1.getPopulation());
    return swatches.size() > 0 ? swatches.get(0).getRgb() : defaultFooterColor;
  }

  @ColorInt
  public static int shiftBackgroundColorForLightText(@ColorInt int backgroundColor) {
    while (ColorUtil.INSTANCE.isColorLight(backgroundColor)) {
      backgroundColor = ColorUtil.INSTANCE.darkenColor(backgroundColor);
    }
    return backgroundColor;
  }

  @ColorInt
  public static int shiftBackgroundColorForDarkText(@ColorInt int backgroundColor) {
    int color = backgroundColor;
    while (!ColorUtil.INSTANCE.isColorLight(backgroundColor)) {
      color = ColorUtil.INSTANCE.lightenColor(backgroundColor);
    }
    return color;
  }

  @ColorInt
  public static int shiftBackgroundColor(@ColorInt int backgroundColor) {
    int color = backgroundColor;
    if (ColorUtil.INSTANCE.isColorLight(color)) {
      color = ColorUtil.INSTANCE.shiftColor(color, 0.5F);
    } else {
      color = ColorUtil.INSTANCE.shiftColor(color, 1.5F);
    }
    return color;
  }

  public static int getMD3AccentColor(@NotNull Context context) {
    if (VersionUtils.hasS()) {
      return ContextCompat.getColor(context, code.name.monkey.appthemehelper.R.color.m3_accent_color);
    } else {
      return ThemeStore.Companion.accentColor(context);
    }
  }

  // بيمزج بين لونين بنسبة (ratio) عشان الانتقال بين طبقات الـ Gradient يبقى ناعم
  // من غير حدود واضحة بين كل لون واللي بعده
  @ColorInt
  public static int blendColors(@ColorInt int color1, @ColorInt int color2, float ratio) {
    float clampedRatio = Math.max(0f, Math.min(1f, ratio));
    float inverseRatio = 1f - clampedRatio;
    float r = (Color.red(color1) * clampedRatio) + (Color.red(color2) * inverseRatio);
    float g = (Color.green(color1) * clampedRatio) + (Color.green(color2) * inverseRatio);
    float b = (Color.blue(color1) * clampedRatio) + (Color.blue(color2) * inverseRatio);
    return Color.rgb(Math.round(r), Math.round(g), Math.round(b));
  }

  // لو اللون باهت (Saturation واطية)، بنزوّده شوية من غير مبالغة عشان الخلفية
  // تبقى حيوية زي أبل ميوزك بدل ما تطلع رمادية
  @ColorInt
  public static int boostSaturationIfDull(@ColorInt int color, float minSaturation, float boostAmount) {
    float[] hsv = new float[3];
    Color.colorToHSV(color, hsv);
    if (hsv[1] < minSaturation) {
      hsv[1] = Math.min(1f, hsv[1] + boostAmount);
    }
    return Color.HSVToColor(hsv);
  }

  public static boolean isLightColor(@ColorInt int color) {
    return ColorUtils.calculateLuminance(color) > 0.5;
  }

  public static boolean isVeryDarkColor(@ColorInt int color) {
    return ColorUtils.calculateLuminance(color) < 0.06;
  }

  // بندلهن اللون خطوة خطوة لو طلع فاتح أوي، عشان الـ Gradient يبقى أغمق تلقائيًا
  // على صور الغلاف الفاتحة (Light Artwork)
  @ColorInt
  public static int darkenIfLight(@ColorInt int color, float stepAmount) {
    int result = color;
    int guard = 0;
    while (isLightColor(result) && guard < 8) {
      float[] hsv = new float[3];
      Color.colorToHSV(result, hsv);
      hsv[2] = Math.max(0f, hsv[2] - stepAmount);
      result = Color.HSVToColor(hsv);
      guard++;
    }
    return result;
  }

  @ColorInt
  private static int firstAvailableRgb(@ColorInt int fallback, Palette.Swatch... swatches) {
    for (Palette.Swatch swatch : swatches) {
      if (swatch != null) {
        return swatch.getRgb();
      }
    }
    return fallback;
  }

  /**
   * بيستخرج مجموعة الألوان اللي بتتبني منها التدرّج السينمائي (Cinematic Gradient)
   * على طريقة Apple Music: Dominant + Vibrant + Dark Vibrant + Dark Muted، مع تعديل
   * تلقائي للـ Saturation والسطوع بدل الاعتماد على Swatch واحد بس.
   */
  @NonNull
  public static ArtistGradientColors getArtistGradientColors(
      @Nullable Palette palette, @ColorInt int fallback) {
    if (palette == null) {
      return new ArtistGradientColors(fallback, fallback, fallback, fallback);
    }

    int dominant =
        palette.getDominantSwatch() != null ? palette.getDominantSwatch().getRgb() : fallback;

    int vibrant =
        firstAvailableRgb(dominant, palette.getVibrantSwatch(), palette.getLightVibrantSwatch());

    int darkVibrant = firstAvailableRgb(vibrant, palette.getDarkVibrantSwatch());

    int darkMuted =
        firstAvailableRgb(darkVibrant, palette.getDarkMutedSwatch(), palette.getMutedSwatch());

    // لو الألوان طالعة باهتة، بنزوّد الـ Saturation شوية بس، من غير ما نبالغ
    dominant = boostSaturationIfDull(dominant, 0.35f, 0.15f);
    vibrant = boostSaturationIfDull(vibrant, 0.35f, 0.15f);
    darkVibrant = boostSaturationIfDull(darkVibrant, 0.3f, 0.12f);
    darkMuted = boostSaturationIfDull(darkMuted, 0.25f, 0.1f);

    // صورة الغلاف الفاتحة (Light Artwork) بتخلي الـ Gradient أغمق تلقائيًا
    if (isLightColor(dominant)) {
      dominant = darkenIfLight(dominant, 0.08f);
      vibrant = darkenIfLight(vibrant, 0.08f);
    }

    return new ArtistGradientColors(dominant, vibrant, darkVibrant, darkMuted);
  }

  /** مجموعة الألوان الأربعة اللي بيتبنى منها التدرّج السينمائي لشاشة تفاصيل الفنان. */
  public static final class ArtistGradientColors {
    @ColorInt public final int dominant;
    @ColorInt public final int vibrant;
    @ColorInt public final int darkVibrant;
    @ColorInt public final int darkMuted;

    public ArtistGradientColors(
        @ColorInt int dominant,
        @ColorInt int vibrant,
        @ColorInt int darkVibrant,
        @ColorInt int darkMuted) {
      this.dominant = dominant;
      this.vibrant = vibrant;
      this.darkVibrant = darkVibrant;
      this.darkMuted = darkMuted;
    }
  }

  private static class SwatchComparator implements Comparator<Palette.Swatch> {

    private static SwatchComparator sInstance;

    static SwatchComparator getInstance() {
      if (sInstance == null) {
        sInstance = new SwatchComparator();
      }
      return sInstance;
    }

    @Override
    public int compare(Palette.Swatch lhs, Palette.Swatch rhs) {
      return lhs.getPopulation() - rhs.getPopulation();
    }
  }
}
