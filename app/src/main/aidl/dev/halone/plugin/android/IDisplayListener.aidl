package dev.halone.plugin.android;

oneway interface IDisplayListener {
    void onDisplayEmptied(int displayId);

    void onOrientationRequested(int displayId, int orientation);
}
