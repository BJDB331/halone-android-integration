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

    /**
     * The phone's own screen, off and back on, while the glasses stay on.
     * command 1 turns it off for the holder of token, 2 renews that lease
     * (within 8 s, or it comes back on), 3 turns it back on. It also comes
     * back on if token's process dies or the glasses' display goes.
     */
    void phoneScreen(int command, IBinder token, int glassesDisplayId) = 12;

    /** A JPEG of one of this service's displays, at most maxSidePx on its longer side; null for none or protected content. */
    byte[] captureDisplay(int displayId, int maxSidePx) = 13;
}
