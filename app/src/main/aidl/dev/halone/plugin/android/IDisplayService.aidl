package dev.halone.plugin.android;

import android.content.Intent;
import dev.halone.plugin.android.IDisplayListener;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

interface IDisplayService {
    void destroy() = 16777114;

    int uid() = 1;

    int createDisplay(String name, in Surface surface, int widthPx, int heightPx, int densityDpi) = 2;

    void resizeDisplay(int displayId, int widthPx, int heightPx, int densityDpi) = 3;

    void setSurface(int displayId, in Surface surface) = 4;

    void releaseDisplay(int displayId) = 5;

    String startActivity(int displayId, in Intent intent) = 6;

    oneway void injectMotion(int displayId, in MotionEvent event) = 7;

    oneway void injectKey(int displayId, in KeyEvent event) = 8;

    oneway void contextClick(int displayId, float x, float y) = 9;

    void setListener(IDisplayListener listener) = 10;

    oneway void pressBack(int displayId) = 11;
}
