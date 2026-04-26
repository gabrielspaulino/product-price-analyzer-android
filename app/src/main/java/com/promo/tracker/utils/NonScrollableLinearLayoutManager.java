package com.promo.tracker.utils;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;

/**
 * LinearLayoutManager variant that lets a parent ScrollView/NestedScrollView handle scrolling.
 */
public class NonScrollableLinearLayoutManager extends LinearLayoutManager {

    public NonScrollableLinearLayoutManager(@NonNull Context context) {
        super(context);
        setAutoMeasureEnabled(true);
    }

    @Override
    public boolean canScrollVertically() {
        return false;
    }
}
