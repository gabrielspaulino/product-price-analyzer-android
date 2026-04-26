package com.promo.tracker.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.widget.ImageView;

import com.promo.tracker.R;

public class LogoLoader {

    public static void load(Context context, ImageView imageView) {
        boolean isDark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        imageView.setImageResource(isDark ? R.drawable.logo_dark : R.drawable.logo);
    }
}
