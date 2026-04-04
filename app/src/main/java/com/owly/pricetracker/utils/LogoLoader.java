package com.owly.pricetracker.utils;

import android.content.Context;
import android.content.res.Configuration;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

public class LogoLoader {

    private static final String LOGO_LIGHT =
            "https://storage.googleapis.com/owly_images/owly_logo_light.png";
    private static final String LOGO_DARK =
            "https://storage.googleapis.com/owly_images/owly_logo_dark.png";

    /**
     * Loads the Owly logo into the given ImageView,
     * automatically picking dark or light variant based on the current system UI mode.
     */
    public static void load(Context context, ImageView imageView) {
        boolean isDark = (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;

        String url = isDark ? LOGO_DARK : LOGO_LIGHT;

        Glide.with(context)
                .load(url)
                .apply(new RequestOptions()
                        .diskCacheStrategy(DiskCacheStrategy.ALL)
                        .fitCenter())
                .into(imageView);
    }
}
