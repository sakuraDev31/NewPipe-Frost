package org.schabi.newpipe.util;

/**
 * Interface implemented by fragments hosting scrolling video/channel feeds that need
 * dynamic padding for the liquid glass top bar, status bar insets, grounded bottom dock,
 * and compact mini player card.
 */
public interface LiquidFeedPaddingListener {
    /**
     * Called whenever system bar insets, top header height, or bottom dock / mini player height changes.
     *
     * @param topPadding    The combined height of the status bar inset and the glass top header.
     * @param bottomPadding The combined height of the grounded bottom dock and (if visible) mini player.
     */
    void onFeedPaddingUpdated(int topPadding, int bottomPadding);
}
